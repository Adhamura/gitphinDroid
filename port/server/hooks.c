/* The routes and the after-request call that the port adds to the core (see port_hooks.h). */
#include "port_hooks.h"

#include <furi.h>
#include <strings.h>

static const char extra_js[] =
#include "web/extra_js.inc"
    ;

bool port_route(HubReq* r) {
    port_hook_before(r);
    if(!strcmp(r->path, "/port/extra.js")) {
        hub_send_body(r, 200, "text/javascript; charset=utf-8", extra_js, sizeof(extra_js) - 1);
        return true;
    }
    return port_smart_route(r) || port_zip_route(r) || port_avatar_route(r);
}

void port_after_request(HubReq* r) {
    if(!r->path || !r->method) return; /* the head never parsed */
    port_hook_after(r);
}

/* After hub_parse_head the head holds "\0"-terminated lines: the request line, then one header
 * per line up to head_len (the "\r\n" ends became "\0\n"... the core writes '\0' over '\r'). */
bool port_header(const HubReq* r, const char* name, char* out, size_t cap) {
    size_t nl = strlen(name);
    const char* p = (const char*)r->head;
    const char* end = (const char*)r->head + r->head_len;
    while(p < end) {
        const char* line = p;
        size_t len = strnlen(line, (size_t)(end - line));
        if(len > nl && !strncasecmp(line, name, nl) && line[nl] == ':') {
            const char* v = line + nl + 1;
            while(*v == ' ' || *v == '\t')
                v++;
            size_t vl = strcspn(v, "\r\n");
            if(vl >= cap) vl = cap - 1;
            memcpy(out, v, vl);
            out[vl] = '\0';
            return true;
        }
        p = line + len + 1;
        if(p < end && *p == '\n') p++;
    }
    return false;
}
