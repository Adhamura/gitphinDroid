/* Smart HTTP: chunked answer writer, pkt-lines, side band (see out.h). */
#define _FILE_OFFSET_BITS 64
#include "smart.h"
#include "port_hooks.h"

#include <furi.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

#define OUT_CAP         (64 * 1024)
#define OUT_HEAD        16 /* room for the chunk size line in front of the data */

/* ---------- answer ---------- */

bool sm_out_begin(SmOut* o, HubReq* r, const char* ctype) {
    memset(o, 0, sizeof(*o));
    o->r = r;
    o->buf = malloc(OUT_HEAD + OUT_CAP + 2);
    if(!o->buf) {
        hub_send_error(r, 503, "low memory, try again");
        return false;
    }
    o->ok = hub_send_head(
        r,
        200,
        ctype,
        HUB_CHUNKED,
        "Expires: Fri, 01 Jan 1980 00:00:00 GMT\r\nPragma: no-cache\r\n"
        "Cache-Control: no-cache, max-age=0, must-revalidate\r\n");
    o->started = true;
    return true;
}

void sm_out_push(SmOut* o) {
    if(!o->n) return;
    if(o->ok) {
        char hx[OUT_HEAD];
        int hl = snprintf(hx, sizeof hx, "%zx\r\n", o->n);
        uint8_t* start = o->buf + OUT_HEAD - hl;
        memcpy(start, hx, (size_t)hl);
        o->buf[OUT_HEAD + o->n] = '\r';
        o->buf[OUT_HEAD + o->n + 1] = '\n';
        o->ok = hub_conn_write(o->r->t, start, (size_t)hl + o->n + 2);
        hub_beat();
    }
    o->sent += o->n;
    o->n = 0;
}

void sm_out_raw(SmOut* o, const void* d, size_t n) {
    const uint8_t* p = d;
    while(n) {
        size_t k = OUT_CAP - o->n < n ? OUT_CAP - o->n : n;
        memcpy(o->buf + OUT_HEAD + o->n, p, k);
        o->n += k;
        p += k;
        n -= k;
        if(o->n == OUT_CAP) sm_out_push(o);
    }
}

void sm_out_pkt_bytes(SmOut* o, const void* d, size_t n) {
    char h[5];
    snprintf(h, sizeof h, "%04zx", n + 4);
    sm_out_raw(o, h, 4);
    sm_out_raw(o, d, n);
}

void sm_out_pkt(SmOut* o, const char* fmt, ...) {
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    if(n < 0) return;
    if((size_t)n >= sizeof line) n = sizeof line - 1;
    sm_out_pkt_bytes(o, line, (size_t)n);
}

void sm_out_flushpkt(SmOut* o) {
    sm_out_raw(o, "0000", 4);
}

void sm_out_band(SmOut* o, int band, const void* d, size_t n) {
    const uint8_t* p = d;
    size_t max = o->band_max ? o->band_max : 65515;
    while(n) {
        size_t k = n < max ? n : max;
        char h[6];
        snprintf(h, sizeof h, "%04zx", k + 5);
        h[4] = (char)band;
        sm_out_raw(o, h, 5);
        sm_out_raw(o, p, k);
        p += k;
        n -= k;
    }
}

void sm_out_progress(SmOut* o, const char* fmt, ...) {
    if(!o->band_max) return;
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    if(n <= 0) return;
    if((size_t)n >= sizeof line) n = sizeof line - 1;
    sm_out_band(o, 2, line, (size_t)n);
}

bool sm_out_end(SmOut* o) {
    sm_out_push(o);
    if(o->ok) o->ok = hub_conn_write(o->r->t, "0\r\n\r\n", 5);
    free(o->buf);
    o->buf = NULL;
    return o->ok;
}

/* ---------- pkt-lines in memory ---------- */

int sm_pkt_next(SmPktIn* in, char* buf, size_t cap, size_t* len) {
    if(in->pos >= in->n) return -1;
    if(in->n - in->pos < 4) return -2;
    unsigned l = 0;
    for(int i = 0; i < 4; i++) {
        char c = (char)in->p[in->pos + i];
        int v = (c >= '0' && c <= '9') ? c - '0' :
                (c >= 'a' && c <= 'f') ? c - 'a' + 10 :
                (c >= 'A' && c <= 'F') ? c - 'A' + 10 :
                                         -1;
        if(v < 0) return -2;
        l = l * 16 + (unsigned)v;
    }
    if(l == 0) {
        in->pos += 4;
        return 0;
    }
    if(l < 4 || in->n - in->pos < l) return -2;
    size_t pl = l - 4;
    const uint8_t* d = in->p + in->pos + 4;
    in->pos += l;
    if(pl && d[pl - 1] == '\n') pl--;
    if(pl >= cap) pl = cap - 1;
    memcpy(buf, d, pl);
    buf[pl] = '\0';
    *len = pl;
    return 1;
}

