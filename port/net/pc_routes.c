/* The /pc/ routes the acceptor answers itself (/pc/hub, /pc/info, /pc/wifi). */
#include "net_int.h"

PortCollector net_collector;

void port_set_collector(PortCollector fn) {
    net_collector = fn;
}

/* True for the /pc/ paths the acceptor answers itself; every other path goes to the worker. */
bool pc_local(const char* path) {
    static const char* const names[] = {"/pc/hub", "/pc/info", "/pc/wifi"};
    for(size_t i = 0; i < sizeof names / sizeof *names; i++) {
        size_t n = strlen(names[i]);
        if(!strncmp(path, names[i], n) && (path[n] == '?' || path[n] == ' ' || path[n] == '\0')) return true;
    }
    return false;
}

/* The /pc/ routes, answered here like the PC bridge does on the Flipper. */
void answer_pc(HubTransport* t, int fd, const char* path) {
    char route[32];
    size_t n = strcspn(path, "? ");
    snprintf(route, sizeof route, "%.*s", (int)(n < sizeof route ? n : sizeof route - 1), path);
    if(!strcmp(route, "/pc/hub")) {
        uint32_t b = t->busy_since;
        char body[160];
        if(b)
            snprintf(
                body,
                sizeof body,
                "{\"connected\":true,\"busy_s\":%.1f,\"stuck_after_s\":%d,\"via\":\"%s\"}",
                (furi_get_tick() - b) / 1000.0,
                STUCK_S,
                via_name(net_last_via));
        else
            snprintf(
                body,
                sizeof body,
                "{\"connected\":true,\"busy_s\":null,\"stuck_after_s\":%d,\"via\":\"%s\"}",
                STUCK_S,
                via_name(net_last_via));
        net_send_answer(fd, 200, "OK", "application/json", NULL, body);
        return;
    }
    const char* kind = !strcmp(route, "/pc/info") ? "info" : !strcmp(route, "/pc/wifi") ? "wifi" : NULL;
    char* json = kind && net_collector ? net_collector(kind) : NULL;
    if(json) {
        net_send_answer(fd, 200, "OK", "application/json", NULL, json);
        free(json);
    } else {
        net_send_answer(fd, 404, "Not Found", "application/json", NULL, "{\"ok\":false,\"error\":\"not found\"}");
    }
}
