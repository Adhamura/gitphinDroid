/* Smart HTTP: request body: Content-Length or chunked, gzip (see body.h). */
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

#define READ_TIMEOUT_MS 120000
#define RAW_CAP         (64 * 1024)

/* ---------- request body ---------- */

enum { ST_SIZE, ST_DATA, ST_DATA_END, ST_TRAILER };

bool sm_body_begin(SmBody* b, HubReq* r) {
    memset(b, 0, sizeof(*b));
    b->r = r;
    char v[128];
    if(port_header(r, "Transfer-Encoding", v, sizeof v) && strcasestr(v, "chunked")) b->chunked = true;
    if(port_header(r, "Content-Encoding", v, sizeof v) && strcasestr(v, "gzip")) b->gzip = true;
    b->pre = r->pre;
    if(b->chunked) {
        /* the core cut pre_len to Content-Length (0 here): take what came with the head */
        const uint8_t* end = r->head + r->head_len;
        b->pre_len = (r->pre && r->pre >= r->head && r->pre <= end) ? (size_t)(end - r->pre) : 0;
    } else {
        b->left = r->content_length - r->body_read;
        b->pre_len = r->pre_len;
    }
    b->st = ST_SIZE;
    b->raw = malloc(RAW_CAP);
    if(!b->raw) return false;
    if(r->h_expect && strstr(r->h_expect, "100")) {
        static const char cont[] = "HTTP/1.1 100 Continue\r\n\r\n";
        hub_conn_write(r->t, cont, sizeof(cont) - 1);
    }
    return true;
}

/* Bytes of the connection (Content-Length mode: never past the body). */
static size_t raw_direct(SmBody* b, uint8_t* out, size_t max) {
    if(!b->chunked) {
        if(b->left == 0) return 0;
        if(max > b->left) max = (size_t)b->left;
    }
    size_t n;
    if(b->pre_len) {
        n = max < b->pre_len ? max : b->pre_len;
        memcpy(out, b->pre, n);
        b->pre += n;
        b->pre_len -= n;
    } else {
        n = hub_conn_read(b->r->t, out, max, READ_TIMEOUT_MS);
        if(n == 0) {
            b->err = true;
            return 0;
        }
    }
    if(!b->chunked) b->left -= n;
    hub_prog.done += (uint32_t)n;
    hub_beat();
    return n;
}

static size_t raw_bytes(SmBody* b, uint8_t* out, size_t max) {
    if(b->rpos < b->rlen) {
        size_t k = b->rlen - b->rpos < max ? b->rlen - b->rpos : max;
        memcpy(out, b->raw + b->rpos, k);
        b->rpos += k;
        return k;
    }
    return raw_direct(b, out, max);
}

static int raw_byte(SmBody* b) {
    if(b->rpos == b->rlen) {
        b->rpos = 0;
        b->rlen = raw_direct(b, b->raw, RAW_CAP);
        if(!b->rlen) return -1;
    }
    return b->raw[b->rpos++];
}

/* One line of the chunked framing (CR dropped). false at the end of the input. */
static bool raw_line(SmBody* b, char* line, size_t cap) {
    size_t n = 0;
    for(;;) {
        int c = raw_byte(b);
        if(c < 0) return false;
        if(c == '\n') break;
        if(c != '\r' && n + 1 < cap) line[n++] = (char)c;
    }
    line[n] = '\0';
    return true;
}

size_t sm_body_read(SmBody* b, uint8_t* out, size_t max) {
    if(b->eof || b->err || max == 0) return 0;
    if(!b->chunked) {
        size_t n = raw_bytes(b, out, max);
        if(!n) b->eof = true;
        return n;
    }
    char line[256];
    for(;;) {
        switch(b->st) {
        case ST_DATA: {
            if(b->chunk_left == 0) {
                b->st = ST_DATA_END;
                continue;
            }
            size_t want = b->chunk_left < max ? (size_t)b->chunk_left : max;
            size_t n = raw_bytes(b, out, want);
            if(!n) {
                b->err = true;
                return 0;
            }
            b->chunk_left -= n;
            return n;
        }
        case ST_DATA_END:
            if(!raw_line(b, line, sizeof line) || line[0]) {
                b->err = true;
                return 0;
            }
            b->st = ST_SIZE;
            continue;
        case ST_SIZE: {
            if(!raw_line(b, line, sizeof line)) {
                b->err = true;
                return 0;
            }
            uint64_t sz = 0;
            int digits = 0;
            for(const char* p = line; *p; p++) {
                int v = (*p >= '0' && *p <= '9') ? *p - '0' :
                        (*p >= 'a' && *p <= 'f') ? *p - 'a' + 10 :
                        (*p >= 'A' && *p <= 'F') ? *p - 'A' + 10 :
                                                   -1;
                if(v < 0) break;
                if(++digits > 15) {
                    b->err = true;
                    return 0;
                }
                sz = sz * 16 + (uint64_t)v;
            }
            if(!digits) {
                b->err = true;
                return 0;
            }
            if(sz == 0) {
                b->st = ST_TRAILER;
                continue;
            }
            b->chunk_left = sz;
            b->st = ST_DATA;
            continue;
        }
        case ST_TRAILER:
        default:
            for(;;) {
                if(!raw_line(b, line, sizeof line)) {
                    b->err = true;
                    return 0;
                }
                if(!line[0]) break;
            }
            b->eof = true;
            return 0;
        }
    }
}

static size_t body_rd(void* ctx, uint8_t* buf, size_t max) {
    return sm_body_read(ctx, buf, max);
}

static bool body_exact(SmBody* b, uint8_t* out, size_t n) {
    size_t got = 0;
    while(got < n) {
        size_t k = sm_body_read(b, out + got, n - got);
        if(!k) return false;
        got += k;
    }
    return true;
}

bool sm_body_pump(SmBody* b, bool (*feed)(void*, const uint8_t*, size_t), void* ctx) {
    if(!b->gzip) {
        uint8_t* buf = malloc(RAW_CAP);
        if(!buf) return false;
        bool ok = true;
        for(;;) {
            size_t n = sm_body_read(b, buf, RAW_CAP);
            if(!n) break;
            if(!feed(ctx, buf, n)) {
                ok = false;
                break;
            }
        }
        free(buf);
        return ok && !b->err;
    }
    /* gzip member: header, raw deflate, 8 byte trailer */
    uint8_t h[10];
    if(!body_exact(b, h, 10) || h[0] != 0x1f || h[1] != 0x8b || h[2] != 8) return false;
    uint8_t flg = h[3], t[2];
    if(flg & 4) {
        if(!body_exact(b, t, 2)) return false;
        for(unsigned n = t[0] | (unsigned)t[1] << 8; n; n--)
            if(!body_exact(b, t, 1)) return false;
    }
    for(int f = 8; f <= 16; f <<= 1) {
        if(!(flg & f)) continue;
        do {
            if(!body_exact(b, t, 1)) return false;
        } while(t[0]);
    }
    if(flg & 2 && !body_exact(b, t, 2)) return false;
    uint64_t out = 0;
    int rc = sm_inflate_stream(body_rd, b, feed, ctx, HUB_INF_RAW, &out, NULL);
    return rc == HUB_INF_OK && !b->err;
}

void sm_body_end(SmBody* b) {
    if(b->raw && !b->err) {
        while(sm_body_read(b, b->raw, RAW_CAP) > 0) {
        }
    }
    free(b->raw);
    b->raw = NULL;
    /* the core must not wait for more body bytes */
    b->r->body_read = b->r->content_length;
    b->r->pre_len = 0;
}

