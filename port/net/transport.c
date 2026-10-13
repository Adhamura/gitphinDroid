/* TCP transport, worker side: listen, stop, and the hub_transport/hub_conn API the core calls
 * (accept the next queued connection, read, write, close). See acceptor.c for the other thread. */
#include "net_int.h"

#include <fcntl.h>
#include <time.h>

volatile uint8_t net_last_via; /* global: the status may ask while the transport is freed */

void net_set_nonblock(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if(fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

void net_ctl_poke(HubTransport* t) {
    char c = 1;
    if(write(t->ctl[1], &c, 1) < 0) {
        /* full pipe: the acceptor wakes anyway */
    }
}

/* ---- sending ---- */

/* Wait until fd is ready for ev. false on timeout or error. */
bool net_wait_fd(int fd, short ev, uint32_t timeout_ms) {
    uint32_t start = furi_get_tick();
    for(;;) {
        uint32_t el = furi_get_tick() - start;
        if(el > timeout_ms) return false;
        struct pollfd p = {.fd = fd, .events = ev};
        int r = poll(&p, 1, (int)(timeout_ms - el));
        if(r > 0) return true;
        if(r == 0) return false;
        if(errno != EINTR) return false;
    }
}

bool net_send_all(int fd, const void* data, size_t len, uint32_t timeout_ms) {
    const uint8_t* p = data;
    while(len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if(n > 0) {
            p += n;
            len -= (size_t)n;
        } else if(n < 0 && errno == EINTR) {
            continue;
        } else if(n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if(!net_wait_fd(fd, POLLOUT, timeout_ms)) return false;
        } else {
            return false;
        }
    }
    return true;
}

/* A whole small answer from the acceptor (it must not hang on a slow client: 2 s). */
void net_send_answer(int fd, int code, const char* reason, const char* ctype, const char* extra, const char* body) {
    size_t blen = strlen(body);
    char head[512];
    int n = snprintf(
        head,
        sizeof head,
        "HTTP/1.1 %d %s\r\n%sContent-Type: %s\r\nContent-Length: %u\r\nCache-Control: no-store\r\n"
        "Connection: close\r\n\r\n",
        code,
        reason,
        extra ? extra : "",
        ctype,
        (unsigned)blen);
    if(net_send_all(fd, head, (size_t)n, 2000)) net_send_all(fd, body, blen, 2000);
}

/* ---- setup ---- */

HubTransport* port_transport_listen(const PortConfig* cfg, int* err) {
    const char* bind_addr = cfg->bind;
    int port = cfg->port;
    *err = 0;
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t)port);
    if(inet_pton(AF_INET, bind_addr && *bind_addr ? bind_addr : "127.0.0.1", &sa.sin_addr) != 1) {
        *err = -EINVAL;
        return NULL;
    }
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if(fd < 0) {
        *err = -errno;
        return NULL;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    if(bind(fd, (struct sockaddr*)&sa, sizeof sa) != 0 || listen(fd, 32) != 0) {
        *err = -errno;
        close(fd);
        return NULL;
    }
    net_set_nonblock(fd);

    HubTransport* t = calloc(1, sizeof(HubTransport));
    if(!t || pipe(t->ctl) != 0) {
        *err = -ENOMEM;
        free(t);
        close(fd);
        return NULL;
    }
    net_set_nonblock(t->ctl[0]);
    net_set_nonblock(t->ctl[1]);
    t->lfd = fd;
    t->fd = -1;
    t->wifi = cfg->wifi;
    if(cfg->user) snprintf(t->user, sizeof t->user, "%s", cfg->user);
    if(cfg->password) snprintf(t->pass, sizeof t->pass, "%s", cfg->password);
    if(t->wifi == PORT_WIFI_PASSWORD && !t->pass[0]) t->wifi = PORT_WIFI_OFF; /* never an empty password */
    pthread_mutex_init(&t->mu, NULL);
    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_cond_init(&t->cv, &ca);
    pthread_condattr_destroy(&ca);
    if(pthread_create(&t->acceptor, NULL, net_acceptor, t) != 0) {
        *err = -EAGAIN;
        close(t->ctl[0]);
        close(t->ctl[1]);
        close(fd);
        free(t);
        return NULL;
    }
    return t;
}

void port_transport_wake(HubTransport* t) {
    pthread_mutex_lock(&t->mu);
    t->stopping = true;
    pthread_cond_broadcast(&t->cv);
    pthread_mutex_unlock(&t->mu);
    net_ctl_poke(t);
}

void hub_transport_free(HubTransport* t) {
    if(!t) return;
    port_transport_wake(t);
    pthread_join(t->acceptor, NULL);
    if(t->fd >= 0) close(t->fd);
    free(t->pre);
    for(int i = 0; i < t->npend; i++) {
        close(t->pend[i].fd);
        conn_free(&t->pend[i]);
    }
    for(int i = 0; i < t->nready; i++) {
        close(t->ready[i].fd);
        conn_free(&t->ready[i]);
    }
    for(int i = 0; i < t->nclosed_q; i++)
        close(t->closed_q[i]);
    for(int i = 0; i < t->nclosing; i++)
        close(t->closing[i]);
    close(t->lfd);
    close(t->ctl[0]);
    close(t->ctl[1]);
    pthread_cond_destroy(&t->cv);
    pthread_mutex_destroy(&t->mu);
    free(t);
}

/* ---- the worker side (hub_transport.h) ---- */

bool hub_transport_accept(HubTransport* t, uint32_t timeout_ms) {
    struct timespec until;
    clock_gettime(CLOCK_MONOTONIC, &until);
    until.tv_sec += timeout_ms / 1000;
    until.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
    if(until.tv_nsec >= 1000000000L) {
        until.tv_sec++;
        until.tv_nsec -= 1000000000L;
    }
    pthread_mutex_lock(&t->mu);
    while(!t->nready && !t->stopping) {
        if(pthread_cond_timedwait(&t->cv, &t->mu, &until) == ETIMEDOUT) break;
    }
    if(!t->nready || t->stopping) {
        pthread_mutex_unlock(&t->mu);
        return false;
    }
    Conn c = t->ready[0];
    memmove(t->ready, t->ready + 1, sizeof(Conn) * (size_t)(t->nready - 1));
    t->nready--;
    t->busy_since = furi_get_tick() | 1;
    t->via = c.via;
    net_last_via = c.via;
    pthread_mutex_unlock(&t->mu);
    net_ctl_poke(t); /* the queue has room again */

    t->fd = c.fd;
    t->pre = c.buf;
    t->pre_len = c.len;
    t->pre_pos = 0;
    t->peer_closed = false;
    return true;
}

size_t hub_conn_read(HubTransport* t, uint8_t* buf, size_t len, uint32_t timeout_ms) {
    if(t->fd < 0 || len == 0) return 0;
    if(t->pre_pos < t->pre_len) {
        size_t n = MIN(len, t->pre_len - t->pre_pos);
        memcpy(buf, t->pre + t->pre_pos, n);
        t->pre_pos += n;
        return n;
    }
    if(t->peer_closed) return 0;
    for(;;) {
        ssize_t n = recv(t->fd, buf, len, 0);
        if(n > 0) return (size_t)n;
        if(n == 0) {
            t->peer_closed = true;
            return 0;
        }
        if(errno == EINTR) continue;
        if(errno != EAGAIN && errno != EWOULDBLOCK) {
            t->peer_closed = true;
            return 0;
        }
        if(!net_wait_fd(t->fd, POLLIN, timeout_ms)) return 0;
    }
}

bool hub_conn_write(HubTransport* t, const void* data, size_t len) {
    if(t->fd < 0) return false;
    return net_send_all(t->fd, data, len, WRITE_TIMEOUT_MS);
}

void hub_conn_close(HubTransport* t) {
    if(t->fd < 0) return;
    shutdown(t->fd, SHUT_WR);
    pthread_mutex_lock(&t->mu);
    if(t->nclosed_q < CLOSE_MAX)
        t->closed_q[t->nclosed_q++] = t->fd;
    else
        close(t->fd);
    t->busy_since = 0;
    pthread_mutex_unlock(&t->mu);
    net_ctl_poke(t);
    t->fd = -1;
    free(t->pre);
    t->pre = NULL;
    t->pre_len = t->pre_pos = 0;
}

uint8_t port_transport_via(void) {
    return net_last_via;
}

void hub_transport_service(HubTransport* t) {
    (void)t; /* TCP has no PING frames */
}
