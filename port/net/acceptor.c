/* TCP transport, acceptor thread: accepts, reads each request head, checks who may connect,
 * answers /pc/hub, /pc/info, /pc/wifi and queues the rest for the worker; drains finished connections. */
#include "net_int.h"

void conn_free(Conn* c) {
    free(c->buf);
    c->buf = NULL;
}

static void closing_add(HubTransport* t, int fd) {
    shutdown(fd, SHUT_WR);
    if(t->nclosing == CLOSE_MAX) {
        close(fd);
        return;
    }
    t->closing[t->nclosing] = fd;
    t->closing_t[t->nclosing] = furi_get_tick();
    t->nclosing++;
}

static void closing_drop(HubTransport* t, int i) {
    close(t->closing[i]);
    t->closing[i] = t->closing[t->nclosing - 1];
    t->closing_t[i] = t->closing_t[t->nclosing - 1];
    t->nclosing--;
}

static void pend_remove(HubTransport* t, int i) {
    t->pend[i] = t->pend[t->npend - 1];
    t->npend--;
}

/* The head of pend[i] is complete (or the buffer is full): decide what happens to it. */
static void dispatch(HubTransport* t, int i) {
    Conn c = t->pend[i];
    pend_remove(t, i);
    const char* h = (const char*)c.buf;
    bool head_done = strstr(h, "\r\n\r\n") != NULL;

    if(!c.trusted) {
        if(t->wifi == PORT_WIFI_OFF) {
            net_send_answer(c.fd, 403, "Forbidden", "text/plain; charset=utf-8", NULL,
                "Gitphin: access over this network is off (turn it on in the app)\n");
            closing_add(t, c.fd);
            conn_free(&c);
            return;
        }
        if(t->wifi != PORT_WIFI_OPEN && (!head_done || !head_auth_ok(h, t->user, t->pass))) {
            net_send_answer(c.fd, 401, "Unauthorized", "text/plain; charset=utf-8",
                "WWW-Authenticate: Basic realm=\"Gitphin\", charset=\"UTF-8\"\r\n",
                "Gitphin: user name and password required (see Settings in the app).\n");
            closing_add(t, c.fd);
            conn_free(&c);
            return;
        }
    }

    const char* sp = strchr(h, ' ');
    if(head_done && sp && pc_local(sp + 1)) { /* other /pc/ paths (e.g. /pc/zip) go to the worker */
        answer_pc(t, c.fd, sp + 1);
        closing_add(t, c.fd);
        conn_free(&c);
        return;
    }

    /* for the worker (a head that is too large still goes: the core answers 431) */
    c.via = via_of(&c);
    pthread_mutex_lock(&t->mu);
    t->ready[t->nready++] = c;
    pthread_cond_signal(&t->cv);
    pthread_mutex_unlock(&t->mu);
}

void* net_acceptor(void* arg) {
    HubTransport* t = arg;
    for(;;) {
        /* fds the worker finished */
        pthread_mutex_lock(&t->mu);
        bool stop = t->stopping;
        int nq = t->nclosed_q;
        int q[CLOSE_MAX];
        memcpy(q, t->closed_q, sizeof(int) * (size_t)nq);
        t->nclosed_q = 0;
        int nready = t->nready;
        pthread_mutex_unlock(&t->mu);
        for(int i = 0; i < nq; i++)
            closing_add(t, q[i]);
        if(stop) break;

        uint32_t now = furi_get_tick();
        for(int i = t->npend - 1; i >= 0; i--) {
            if(now - t->pend[i].t >= PEND_IDLE_MS) {
                close(t->pend[i].fd);
                conn_free(&t->pend[i]);
                pend_remove(t, i);
            }
        }
        for(int i = t->nclosing - 1; i >= 0; i--)
            if(now - t->closing_t[i] >= CLOSE_DRAIN_MS) closing_drop(t, i);

        struct pollfd p[2 + PEND_MAX + CLOSE_MAX];
        int idx_pend[PEND_MAX], idx_close[CLOSE_MAX];
        int np = 0;
        p[np++] = (struct pollfd){.fd = t->ctl[0], .events = POLLIN};
        /* a full queue: stop taking new work until the worker catches up */
        bool room = nready < READY_MAX && t->npend < PEND_MAX;
        p[np++] = (struct pollfd){.fd = room ? t->lfd : -1, .events = POLLIN};
        for(int i = 0; i < t->npend; i++) {
            idx_pend[i] = np;
            p[np++] = (struct pollfd){.fd = nready < READY_MAX ? t->pend[i].fd : -1, .events = POLLIN};
        }
        for(int i = 0; i < t->nclosing; i++) {
            idx_close[i] = np;
            p[np++] = (struct pollfd){.fd = t->closing[i], .events = POLLIN};
        }
        int r = poll(p, (nfds_t)np, 500);
        if(r < 0 && errno != EINTR) break;
        if(r <= 0) continue;

        if(p[0].revents) {
            char buf[64];
            while(read(t->ctl[0], buf, sizeof buf) > 0) {
            }
        }

        for(int i = t->nclosing - 1; i >= 0; i--) {
            if(!p[idx_close[i]].revents) continue;
            char junk[1024];
            ssize_t n = recv(t->closing[i], junk, sizeof junk, 0);
            if(n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) closing_drop(t, i);
        }

        for(int i = t->npend - 1; i >= 0; i--) {
            if(!p[idx_pend[i]].revents) continue;
            Conn* c = &t->pend[i];
            ssize_t n = recv(c->fd, c->buf + c->len, HEAD_MAX - c->len, 0);
            if(n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if(n <= 0) {
                close(c->fd);
                conn_free(c);
                pend_remove(t, i);
                continue;
            }
            c->len += (size_t)n;
            c->buf[c->len] = 0;
            if(strstr((const char*)c->buf, "\r\n\r\n") || c->len >= HEAD_MAX) dispatch(t, i);
        }

        if(p[1].revents & POLLIN) {
            while(t->npend < PEND_MAX) {
                int fd = accept4(t->lfd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if(fd < 0) break;
                uint8_t* buf = malloc(HEAD_MAX + 1);
                if(!buf) {
                    close(fd);
                    break;
                }
                int one = 1;
                setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
                bool loop = peer_is_loopback(fd);
                bool usb = !loop && local_is_usb(fd);
                t->pend[t->npend++] = (Conn){
                    .fd = fd,
                    .t = furi_get_tick(),
                    .trusted = loop || usb,
                    .tether = usb,
                    .len = 0,
                    .buf = buf,
                };
            }
        }
    }
    return NULL;
}
