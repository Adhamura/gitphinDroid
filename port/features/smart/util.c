/* Smart HTTP: small shared helpers (see util.h). */
#define _FILE_OFFSET_BITS 64
#include "util.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

/* ---------- small helpers ---------- */

const uint8_t sm_zero_id[20] = {0};

void sm_hex(const uint8_t id[20], char out[41]) {
    static const char hx[] = "0123456789abcdef";
    for(int i = 0; i < 20; i++) {
        out[2 * i] = hx[id[i] >> 4];
        out[2 * i + 1] = hx[id[i] & 15];
    }
    out[40] = '\0';
}

static int hexv(char c) {
    if(c >= '0' && c <= '9') return c - '0';
    if(c >= 'a' && c <= 'f') return c - 'a' + 10;
    if(c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool sm_unhex(const char* s, uint8_t id[20]) {
    for(int i = 0; i < 20; i++) {
        int a = hexv(s[2 * i]), b = a < 0 ? -1 : hexv(s[2 * i + 1]);
        if(a < 0 || b < 0) return false;
        id[i] = (uint8_t)(a << 4 | b);
    }
    char e = s[40];
    return e == '\0' || e == ' ' || e == '\n' || e == '\t';
}

bool sm_is_zero(const uint8_t id[20]) {
    return memcmp(id, sm_zero_id, 20) == 0;
}

static uint32_t crc_tab[256];
static bool crc_ready;

uint32_t sm_crc32(uint32_t crc, const void* p, size_t n) {
    if(!crc_ready) {
        for(uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for(int k = 0; k < 8; k++)
                c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            crc_tab[i] = c;
        }
        crc_ready = true;
    }
    const uint8_t* b = p;
    crc = ~crc;
    while(n--)
        crc = crc_tab[(crc ^ *b++) & 0xff] ^ (crc >> 8);
    return ~crc;
}

const char* sm_type_name(int type) {
    switch(type) {
    case SM_COMMIT:
        return "commit";
    case SM_TREE:
        return "tree";
    case SM_BLOB:
        return "blob";
    case SM_TAG:
        return "tag";
    default:
        return "?";
    }
}

void sm_hash_begin(HubSha1* s, int type, uint64_t len) {
    char h[40];
    int n = snprintf(h, sizeof h, "%s %llu", sm_type_name(type), (unsigned long long)len);
    hub_sha1_init(s);
    hub_sha1_update(s, h, (size_t)n + 1); /* with the NUL */
}

void sm_hash_obj(int type, const void* data, size_t len, uint8_t id[20]) {
    HubSha1 s;
    sm_hash_begin(&s, type, len);
    hub_sha1_update(&s, data, len);
    hub_sha1_final(&s, id);
}

bool smbuf_add(SmBuf* b, const void* d, size_t n) {
    if(b->bad) return false;
    if(b->n + n + 1 > b->cap) {
        size_t nc = b->cap ? b->cap : 256;
        while(nc < b->n + n + 1)
            nc *= 2;
        uint8_t* np = realloc(b->p, nc);
        if(!np) {
            b->bad = true;
            return false;
        }
        b->p = np;
        b->cap = nc;
    }
    if(n) memcpy(b->p + b->n, d, n);
    b->n += n;
    b->p[b->n] = 0;
    return true;
}

void smbuf_free(SmBuf* b) {
    free(b->p);
    memset(b, 0, sizeof(*b));
}

/* ---------- id map ---------- */

static inline size_t map_slot(const uint8_t* id, size_t cap) {
    uint32_t h = (uint32_t)id[0] << 24 | (uint32_t)id[1] << 16 | (uint32_t)id[2] << 8 | id[3];
    h ^= (uint32_t)id[7] << 5;
    return h & (cap - 1);
}

static bool map_alloc(SmMap* m, size_t cap) {
    m->keys = malloc(cap * 20);
    m->vals = malloc(cap * sizeof(uint32_t));
    if(!m->keys || !m->vals) {
        free(m->keys);
        free(m->vals);
        m->keys = NULL;
        m->vals = NULL;
        return false;
    }
    for(size_t i = 0; i < cap; i++)
        m->vals[i] = SM_NONE;
    m->cap = cap;
    m->n = 0;
    return true;
}

bool smmap_init(SmMap* m, size_t hint) {
    size_t cap = 64;
    while(cap < hint * 2)
        cap *= 2;
    return map_alloc(m, cap);
}

void smmap_free(SmMap* m) {
    free(m->keys);
    free(m->vals);
    memset(m, 0, sizeof(*m));
}

uint32_t smmap_get(const SmMap* m, const uint8_t id[20]) {
    if(!m->cap) return SM_NONE;
    size_t i = map_slot(id, m->cap);
    for(;;) {
        uint32_t v = m->vals[i];
        if(v == SM_NONE) return SM_NONE;
        if(memcmp(m->keys + i * 20, id, 20) == 0) return v;
        i = (i + 1) & (m->cap - 1);
    }
}

static void map_raw_put(SmMap* m, const uint8_t* id, uint32_t val) {
    size_t i = map_slot(id, m->cap);
    for(;;) {
        if(m->vals[i] == SM_NONE) {
            memcpy(m->keys + i * 20, id, 20);
            m->vals[i] = val;
            m->n++;
            return;
        }
        if(memcmp(m->keys + i * 20, id, 20) == 0) {
            m->vals[i] = val;
            return;
        }
        i = (i + 1) & (m->cap - 1);
    }
}

bool smmap_put(SmMap* m, const uint8_t id[20], uint32_t val) {
    if(!m->cap && !smmap_init(m, 16)) return false;
    if((m->n + 1) * 2 > m->cap) {
        SmMap old = *m;
        if(!map_alloc(m, old.cap * 2)) {
            *m = old;
            return false;
        }
        for(size_t i = 0; i < old.cap; i++)
            if(old.vals[i] != SM_NONE) map_raw_put(m, old.keys + i * 20, old.vals[i]);
        free(old.keys);
        free(old.vals);
    }
    map_raw_put(m, id, val);
    return true;
}

/* ---------- windowed file reads ---------- */

bool sm_win_init(SmWin* w, int fd, size_t cap) {
    memset(w, 0, sizeof(*w));
    w->fd = fd;
    w->buf = malloc(cap);
    w->cap = cap;
    struct stat st;
    if(fd >= 0 && fstat(fd, &st) == 0) w->size = (uint64_t)st.st_size;
    return w->buf != NULL;
}

void sm_win_free(SmWin* w) {
    free(w->buf);
    w->buf = NULL;
}

size_t sm_win_get(SmWin* w, uint64_t off, uint8_t* out, size_t n) {
    size_t got = 0;
    while(got < n) {
        uint64_t o = off + got;
        if(o < w->start || o >= w->start + w->len) {
            if(o >= w->size) break;
            ssize_t r;
            do {
                r = pread(w->fd, w->buf, w->cap, (off_t)o);
            } while(r < 0 && errno == EINTR);
            if(r <= 0) {
                w->err = true;
                w->len = 0;
                break;
            }
            w->start = o;
            w->len = (size_t)r;
        }
        size_t in = (size_t)(w->start + w->len - o);
        size_t k = n - got < in ? n - got : in;
        memcpy(out + got, w->buf + (o - w->start), k);
        got += k;
    }
    return got;
}

size_t sm_win_read(void* ctx, uint8_t* buf, size_t max) {
    SmWin* w = ctx;
    size_t n = sm_win_get(w, w->pos, buf, max);
    w->pos += n;
    return n;
}

/* ---------- streaming inflate with a history for far back-references ---------- */

#define RING_SZ (64 * 1024)

typedef struct {
    HubInfWrite wr;
    void* wctx;
    uint8_t* ring;
    uint64_t total;
} Ring;

static bool ring_wr(void* c, const uint8_t* b, size_t n) {
    Ring* r = c;
    size_t skip = n > RING_SZ ? n - RING_SZ : 0;
    uint64_t at = r->total + skip;
    for(size_t i = skip; i < n;) {
        size_t p = (size_t)(at % RING_SZ);
        size_t k = RING_SZ - p;
        if(k > n - i) k = n - i;
        memcpy(r->ring + p, b + i, k);
        i += k;
        at += k;
    }
    r->total += n;
    return r->wr ? r->wr(r->wctx, b, n) : true;
}

static bool ring_far(void* c, uint64_t pos, uint8_t* buf, size_t n) {
    Ring* r = c;
    if(pos + n > r->total || r->total - pos > RING_SZ) return false;
    for(size_t i = 0; i < n;) {
        size_t p = (size_t)((pos + i) % RING_SZ);
        size_t k = RING_SZ - p;
        if(k > n - i) k = n - i;
        memcpy(buf + i, r->ring + p, k);
        i += k;
    }
    return true;
}

int sm_inflate_stream(
    HubInfRead rd,
    void* rctx,
    HubInfWrite wr,
    void* wctx,
    uint32_t flags,
    uint64_t* out_len,
    size_t* in_used) {
    Ring r = {.wr = wr, .wctx = wctx, .ring = malloc(RING_SZ), .total = 0};
    if(!r.ring) return HUB_INF_ERR_NOMEM;
    size_t ol = 0;
    int rc = hub_inflate_stream(rd, rctx, ring_wr, &r, flags, &ol, in_used, ring_far, &r);
    if(out_len) *out_len = r.total;
    free(r.ring);
    return rc;
}

/* ---------- deltas and entry headers ---------- */

static bool delta_varint(const uint8_t** p, const uint8_t* end, uint64_t* v) {
    uint64_t r = 0;
    int shift = 0;
    for(;;) {
        if(*p >= end || shift > 63) return false;
        uint8_t c = *(*p)++;
        r |= (uint64_t)(c & 0x7f) << shift;
        shift += 7;
        if(!(c & 0x80)) break;
    }
    *v = r;
    return true;
}

uint64_t sm_delta_result_size(const uint8_t* d, size_t dlen) {
    const uint8_t* p = d;
    const uint8_t* end = d + dlen;
    uint64_t a, b;
    if(!delta_varint(&p, end, &a) || !delta_varint(&p, end, &b)) return 0;
    return b;
}

int sm_delta_apply(
    const uint8_t* base,
    size_t blen,
    const uint8_t* d,
    size_t dlen,
    uint8_t** out,
    size_t* olen) {
    const uint8_t* p = d;
    const uint8_t* end = d + dlen;
    uint64_t src, dst;
    if(!delta_varint(&p, end, &src) || !delta_varint(&p, end, &dst)) return SM_E_CORRUPT;
    if(src != blen || dst > (uint64_t)SIZE_MAX - 1) return SM_E_CORRUPT;
    uint8_t* o = malloc((size_t)dst + 1);
    if(!o) return SM_E_NOMEM;
    size_t w = 0;
    while(p < end) {
        uint8_t op = *p++;
        if(op & 0x80) {
            uint64_t off = 0, sz = 0;
            for(int i = 0; i < 4; i++)
                if(op & (1u << i)) {
                    if(p >= end) goto bad;
                    off |= (uint64_t)*p++ << (8 * i);
                }
            for(int i = 0; i < 3; i++)
                if(op & (0x10u << i)) {
                    if(p >= end) goto bad;
                    sz |= (uint64_t)*p++ << (8 * i);
                }
            if(sz == 0) sz = 0x10000;
            if(off + sz > blen || w + sz > dst) goto bad;
            memcpy(o + w, base + off, (size_t)sz);
            w += (size_t)sz;
        } else if(op) {
            if((size_t)(end - p) < op || w + op > dst) goto bad;
            memcpy(o + w, p, op);
            p += op;
            w += op;
        } else {
            goto bad;
        }
    }
    if(w != dst) goto bad;
    o[w] = 0;
    *out = o;
    *olen = w;
    return SM_OK;
bad:
    free(o);
    return SM_E_CORRUPT;
}

size_t sm_entry_hdr_parse(const uint8_t* p, size_t n, int* type, uint64_t* size) {
    if(n < 1) return 0;
    uint8_t c = p[0];
    *type = (c >> 4) & 7;
    uint64_t s = c & 15;
    int shift = 4;
    size_t i = 1;
    while(c & 0x80) {
        if(i >= n || shift > 60) return 0;
        c = p[i++];
        s |= (uint64_t)(c & 0x7f) << shift;
        shift += 7;
    }
    *size = s;
    return i;
}

size_t sm_entry_hdr_put(uint8_t* out, int type, uint64_t size) {
    size_t i = 0;
    uint8_t c = (uint8_t)((type << 4) | (size & 15));
    size >>= 4;
    while(size) {
        out[i++] = c | 0x80;
        c = size & 0x7f;
        size >>= 7;
    }
    out[i++] = c;
    return i;
}

size_t sm_ofs_put(uint8_t* out, uint64_t ofs) {
    uint8_t tmp[16];
    size_t pos = sizeof tmp - 1;
    tmp[pos] = ofs & 127;
    while(ofs >>= 7)
        tmp[--pos] = 128 | (--ofs & 127);
    size_t n = sizeof tmp - pos;
    memcpy(out, tmp + pos, n);
    return n;
}

