/* gitphind: the Android server on Linux, for tests.
 * Usage: gitphind --root DIR [--bind 127.0.0.1] [--port 8080] [--user U] [--password PW] [--open]
 *                 [--git-dir DIR] [--webhook-log FILE]
 * With --bind 0.0.0.0, other hosts need --user/--password (403 without a password), or --open.
 * Copy core/files/index.html* to DIR/apps_assets/gitphin/ for the web UI. Ctrl+C stops it. */
#include "port.h"

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* A small /pc/info and /pc/wifi for tests (the PC proxy pc/gitphin-usb has the real collector). */
static char* host_collect(const char* kind) {
    char host[128] = "";
    gethostname(host, sizeof host - 1);
    char* out = malloc(512);
    if(!out) return NULL;
    if(!strcmp(kind, "info"))
        snprintf(out, 512, "{\"hostname\":\"%s\",\"os\":\"Linux\",\"ips\":[],\"connections\":[]}", host);
    else
        snprintf(out, 512, "{\"connected\":false,\"note\":\"gitphind test server\"}");
    return out;
}

/* --webhook-log FILE: every webhook event as one JSON line (for tests). */
static const char* webhook_log;
static void host_webhook(const char* json) {
    FILE* f = fopen(webhook_log, "a");
    if(!f) return;
    fprintf(f, "%s\n", json);
    fclose(f);
}

static volatile sig_atomic_t stop;
static void on_signal(int s) {
    (void)s;
    stop = 1;
}

int main(int argc, char** argv) {
    PortConfig cfg = {.root = NULL, .bind = "127.0.0.1", .port = 8080, .user = "", .password = ""};
    bool open = false;
    for(int i = 1; i < argc; i++) {
        if(!strcmp(argv[i], "--open")) {
            open = true;
            continue;
        }
        if(i + 1 >= argc) {
            fprintf(stderr, "missing value for %s\n", argv[i]);
            return 2;
        }
        if(!strcmp(argv[i], "--root")) cfg.root = argv[i + 1];
        else if(!strcmp(argv[i], "--bind")) cfg.bind = argv[i + 1];
        else if(!strcmp(argv[i], "--port")) cfg.port = atoi(argv[i + 1]);
        else if(!strcmp(argv[i], "--password")) cfg.password = argv[i + 1];
        else if(!strcmp(argv[i], "--git-dir")) cfg.git_dir = argv[i + 1];
        else if(!strcmp(argv[i], "--user")) cfg.user = argv[i + 1];
        else if(!strcmp(argv[i], "--webhook-log")) webhook_log = argv[i + 1];
        else {
            fprintf(stderr, "unknown option %s\n", argv[i]);
            return 2;
        }
        i++; /* the value */
    }
    if(!cfg.root) {
        fprintf(stderr, "usage: gitphind --root DIR [--bind ADDR] [--port N] [--user U] [--password PW] [--open] [--git-dir DIR] [--webhook-log FILE]\n");
        return 2;
    }
    cfg.wifi = open ? PORT_WIFI_OPEN : cfg.password[0] ? PORT_WIFI_PASSWORD : PORT_WIFI_OFF;
    port_set_device("Linux host", "gitphind", 100, true);
    port_set_collector(host_collect);
    if(webhook_log) port_set_webhook_sink(host_webhook);
    int rc = port_server_start(&cfg);
    if(rc != 0) {
        fprintf(stderr, "start failed: %d\n", rc);
        return 1;
    }
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);
    printf("gitphind: http://%s:%d/  root %s\n", cfg.bind, cfg.port, cfg.root);
    fflush(stdout);
    while(!stop)
        pause();
    port_server_stop();
    return 0;
}
