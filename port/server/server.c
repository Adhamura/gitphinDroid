/* The worker of gitphin.c without the Flipper screen: one thread that serves the
 * requests one at a time and packs repos while it is idle. */
#include "port.h"
#include "hub_http.h"
#include "hub_git.h"
#include "hub_gitobj.h"
#include "hub_req.h"
#include "hub_plugin.h"

#include <furi.h>
#include <storage/storage.h>
#include <pthread.h>
#include <errno.h>

#define IDLE_PACK_AFTER_MS 10000

static pthread_mutex_t srv_lock = PTHREAD_MUTEX_INITIALIZER; /* start / stop */
static pthread_t srv_thread;
static HubTransport* srv_t;
static HubStats srv_stats;
static volatile bool srv_running;
static char srv_bind[64];
static int srv_port;

static void* worker(void* arg) {
    (void)arg;
    Storage* storage = port_storage();
    hub_beat();
    hub_gitobj_beat = hub_beat_hook;
    hub_git_migrate(storage);
    hub_git_cleanup_temp(storage);
    hub_git_mark_all(storage);
    uint32_t last_conn = furi_get_tick();

    while(srv_running) {
        hub_beat();
        if(hub_transport_accept(srv_t, 500)) {
            hub_http_handle(srv_t, storage, &srv_stats);
            last_conn = furi_get_tick();
        } else if(srv_running && furi_get_tick() - last_conn >= IDLE_PACK_AFTER_MS) {
            hub_git_idle_tick(storage);
        }
    }

    hub_gitobj_beat = NULL;
    hub_git_flush(storage);
    hub_git_free_state();
    return NULL;
}

int port_server_start(const PortConfig* cfg) {
    pthread_mutex_lock(&srv_lock);
    if(srv_running) {
        pthread_mutex_unlock(&srv_lock);
        return -EALREADY;
    }
    port_storage_set_root(cfg->root);
    port_storage_set_git_dir(cfg->git_dir);
    Storage* storage = port_storage();
    storage_simply_mkdir(storage, EXT_PATH("apps_data/gitphin/git"));
    storage_simply_mkdir(storage, EXT_PATH("apps_assets/gitphin"));

    int err = 0;
    srv_t = port_transport_listen(cfg, &err);
    if(!srv_t) {
        pthread_mutex_unlock(&srv_lock);
        FURI_LOG_E("Gitphin", "listen on %s:%d failed: %d", cfg->bind, cfg->port, err);
        return err ? err : -EIO;
    }
    if(!srv_stats.lock) srv_stats.lock = furi_mutex_alloc(FuriMutexTypeNormal);
    srv_stats.requests = 0;
    srv_stats.last[0] = '\0';
    snprintf(srv_bind, sizeof srv_bind, "%s", cfg->bind ? cfg->bind : "127.0.0.1");
    srv_port = cfg->port;
    srv_running = true;
    if(pthread_create(&srv_thread, NULL, worker, NULL) != 0) {
        srv_running = false;
        hub_transport_free(srv_t);
        srv_t = NULL;
        pthread_mutex_unlock(&srv_lock);
        return -EAGAIN;
    }
    FURI_LOG_I("Gitphin", "serving %s on %s:%d", cfg->root, srv_bind, srv_port);
    pthread_mutex_unlock(&srv_lock);
    return 0;
}

void port_server_stop(void) {
    pthread_mutex_lock(&srv_lock);
    if(srv_running) {
        srv_running = false;
        port_transport_wake(srv_t);
        pthread_join(srv_thread, NULL);
        hub_transport_free(srv_t);
        srv_t = NULL;
        FURI_LOG_I("Gitphin", "stopped");
    }
    pthread_mutex_unlock(&srv_lock);
}

bool port_server_running(void) {
    return srv_running;
}

static void json_esc(char* out, size_t cap, const char* s) {
    size_t o = 0;
    for(; *s && o + 7 < cap; s++) {
        unsigned char c = (unsigned char)*s;
        if(c == '"' || c == '\\') {
            out[o++] = '\\';
            out[o++] = (char)c;
        } else if(c < 0x20) {
            o += (size_t)snprintf(out + o, cap - o, "\\u%04x", c);
        } else {
            out[o++] = (char)c;
        }
    }
    out[o] = '\0';
}

/* The transfer view of the Flipper screen (gitphin.c hub_draw), as JSON for the app:
 * "prog":{"show":..,"live":..,"state":"Pushing|Pulling|Transfer|Done","pct":0-100 or -1 (unknown),
 *         "sub":0-100 or -1 (current file of a whole-clone bar),"line":"12 obj, 340 KB","rate":"120 KB/s"} */
static void prog_json(char* out, size_t cap) {
    uint32_t now = furi_get_tick();
    uint8_t act = hub_activity;
    uint8_t kind = hub_prog.kind;
    uint32_t done = hub_prog.done, total = hub_prog.total, objs = hub_prog.objects;
    uint32_t pstart = hub_prog.start, plast = hub_prog.last;
    bool big = kind == HUB_PG_BIG;
    uint32_t kb = (hub_prog.bytes + (big ? 0 : done)) >> 10;
    bool live = act == HUB_ACT_REQUEST || now - plast < 500;
    bool show = kind != HUB_PG_NONE && (act == HUB_ACT_REQUEST || now - plast < 2500);
    if(!show) {
        snprintf(out, cap, "{\"show\":false,\"state\":\"%s\"}",
            act == HUB_ACT_PACK ? "Packing" : act == HUB_ACT_REQUEST ? "Busy" : "Ready");
        return;
    }
    const char* st = live ? (kind == HUB_PG_PUSH ? "Pushing" : kind == HUB_PG_PULL ? "Pulling" : "Transfer") : "Done";
    uint32_t stot = hub_prog.sess_total;
    bool whole = kind == HUB_PG_PULL && stot > 0;
    uint32_t bd = whole ? MIN(hub_prog.sess_done + done, stot) : done;
    uint32_t bt = whole ? stot : total;
    int pct = !live ? 100 : bt ? (int)(100.0 * MIN(bd, bt) / bt) : -1;
    int sub = whole && live && total > 8192 ? (int)(100.0 * MIN(done, total) / total) : -1;
    char line[64];
    if(big && live)
        snprintf(line, sizeof line, "%lu of %lu KB", (unsigned long)(done >> 10), (unsigned long)(total >> 10));
    else if(big)
        snprintf(line, sizeof line, "%lu KB", (unsigned long)kb);
    else
        snprintf(line, sizeof line, "%lu %s, %lu KB", (unsigned long)objs,
            kind == HUB_PG_PUSH ? "obj" : "files", (unsigned long)kb);
    char rate[32] = "";
    uint32_t ms = (live ? now : plast) - pstart;
    if(ms >= 1000 && !big) snprintf(rate, sizeof rate, "%lu KB/s", (unsigned long)((uint64_t)kb * 1000 / ms));
    snprintf(out, cap,
        "{\"show\":true,\"live\":%s,\"state\":\"%s\",\"pct\":%d,\"sub\":%d,\"line\":\"%s\",\"rate\":\"%s\"}",
        live ? "true" : "false", st, pct, sub, line, rate);
}

void port_server_status(char* out, size_t cap) {
    uint32_t requests = 0;
    char last[sizeof(srv_stats.last)] = "";
    if(srv_stats.lock && furi_mutex_acquire(srv_stats.lock, 50) == FuriStatusOk) {
        requests = srv_stats.requests;
        memcpy(last, srv_stats.last, sizeof last);
        furi_mutex_release(srv_stats.lock);
    }
    last[sizeof last - 1] = '\0';
    char esc[sizeof last * 6 + 1];
    json_esc(esc, sizeof esc, last);
    char prog[256];
    prog_json(prog, sizeof prog);
    static const char* const via_names[] = {"", "phone", "usb", "tether", "wifi"};
    uint8_t via = srv_running ? port_transport_via() : PORT_VIA_NONE;
    snprintf(
        out,
        cap,
        "{\"running\":%s,\"requests\":%lu,\"last\":\"%s\",\"activity\":%u,\"bind\":\"%s\",\"port\":%d,"
        "\"via\":\"%s\",\"prog\":%s}",
        srv_running ? "true" : "false",
        (unsigned long)requests,
        esc,
        (unsigned)hub_activity,
        srv_bind,
        srv_port,
        via_names[via < 5 ? via : 0],
        prog);
}
