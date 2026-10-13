/* Smart HTTP: index a pushed pack (see index_pack.h).
 *
 * Pass 1 reads the entries in order: header, inflate (whole objects are hashed, deltas only
 * measured), CRC-32 of the raw bytes. Pass 2 resolves the deltas depth first from each base
 * (an explicit stack, the base and the result of each level in RAM), like git index-pack.
 * REF_DELTA bases that are not in the pack come from the repo and are appended (fix-thin). */
#define _FILE_OFFSET_BITS 64
#include "index_int.h"

#include "hub_deflate.h"
#include "hub_req.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define BUF_LIMIT (64u * 1024 * 1024) /* bigger blobs are hashed while they stream */

void* ip_fail(IpPack* p, const char* m) {
    snprintf(p->err, p->errcap, "%s", m);
    return NULL;
}

/* ---- object content: what it names (for the connectivity check) ---- */

static void names(IpPack* p, int type, const uint8_t* d, size_t n) {
    const uint8_t* end = d + n;
    uint8_t id[20];
    if(type == SM_TREE) {
        while(d < end) {
            const uint8_t* sp = memchr(d, ' ', (size_t)(end - d));
            const uint8_t* nul = sp ? memchr(sp, 0, (size_t)(end - sp)) : NULL;
            if(!nul || end - nul < 21) return;
            bool gitlink = sp - d == 6 && !memcmp(d, "160000", 6);
            if(!gitlink) smbuf_add(&p->needed, nul + 1, 20);
            d = nul + 21;
        }
        return;
    }
    while(d < end && *d != '\n') {
        const uint8_t* e = memchr(d, '\n', (size_t)(end - d));
        if(!e) e = end;
        const char* s = (const char*)d;
        if(type == SM_COMMIT && e - d >= 45 && !strncmp(s, "tree ", 5) && sm_unhex(s + 5, id))
            smbuf_add(&p->needed, id, 20);
        else if(type == SM_COMMIT && e - d >= 47 && !strncmp(s, "parent ", 7) && sm_unhex(s + 7, id))
            smbuf_add(&p->needed, id, 20);
        else if(type == SM_TAG && e - d >= 47 && !strncmp(s, "object ", 7) && sm_unhex(s + 7, id))
            smbuf_add(&p->needed, id, 20);
        d = e + 1;
    }
}

/* ---- inflate helpers ---- */

static uint8_t* inflate_ent(IpPack* p, const Ent* e, size_t* used) {
    if(e->size > BUF_LIMIT * 8ull || e->size > (uint64_t)SIZE_MAX - 1) return NULL;
    uint8_t* b = malloc((size_t)e->size + 1);
    if(!b) return NULL;
    p->win.pos = e->off + e->hdr;
    size_t ol = 0, iu = 0;
    if(hub_inflate_buf(sm_win_read, &p->win, b, (size_t)e->size, 0, &ol, &iu) != HUB_INF_OK ||
       ol != e->size) {
        free(b);
        return NULL;
    }
    b[e->size] = 0;
    if(used) *used = iu;
    return b;
}

static bool hash_wr(void* c, const uint8_t* d, size_t n) {
    hub_sha1_update(c, d, n);
    hub_beat();
    return true;
}

static bool null_wr(void* c, const uint8_t* d, size_t n) {
    (void)c, (void)d, (void)n;
    hub_beat();
    return true;
}

/* ---- pass 1 ---- */

bool ip_pass1(IpPack* p) {
    uint64_t pos = 12;
    uint8_t* cbuf = malloc(65536);
    if(!cbuf) return ip_fail(p, "out of memory");
    for(uint32_t i = 0; i < p->n; i++) {
        Ent* e = &p->e[i];
        uint8_t h[64];
        size_t hn = sm_win_get(&p->win, pos, h, sizeof h);
        int type;
        uint64_t size;
        size_t hl = sm_entry_hdr_parse(h, hn, &type, &size);
        if(!hl) goto bad;
        e->off = pos;
        e->rtype = (uint8_t)type;
        e->size = size;
        if(type == SM_OFS_DELTA) {
            if(hl >= hn) goto bad;
            uint8_t c = h[hl++];
            uint64_t ofs = c & 127;
            while(c & 128) {
                if(hl >= hn || ofs > (UINT64_MAX >> 8)) goto bad;
                c = h[hl++];
                ofs = ((ofs + 1) << 7) | (c & 127);
            }
            if(!ofs || ofs > pos - 12) goto bad;
            e->base = pos - ofs; /* an offset for now */
        } else if(type == SM_REF_DELTA) {
            if(hl + 20 > hn) goto bad;
            memcpy(p->refids + (size_t)p->nref * 20, h + hl, 20);
            e->base = p->nref++;
            hl += 20;
        } else if(type < SM_COMMIT || type > SM_TAG) {
            goto bad;
        }
        e->hdr = (uint32_t)hl;
        size_t used = 0;
        if(type <= SM_TAG && (type != SM_BLOB || size <= BUF_LIMIT)) {
            uint8_t* d = inflate_ent(p, e, &used);
            if(!d) goto bad;
            sm_hash_obj(type, d, (size_t)size, e->id);
            if(type != SM_BLOB) names(p, type, d, (size_t)size);
            free(d);
            e->type = (uint8_t)type;
        } else if(type <= SM_TAG) {
            HubSha1 s;
            sm_hash_begin(&s, type, size);
            uint64_t ol = 0;
            p->win.pos = pos + hl;
            if(sm_inflate_stream(sm_win_read, &p->win, hash_wr, &s, 0, &ol, &used) != HUB_INF_OK ||
               ol != size)
                goto bad;
            hub_sha1_final(&s, e->id);
            e->type = (uint8_t)type;
        } else if(size <= BUF_LIMIT) {
            uint8_t* d = inflate_ent(p, e, &used);
            if(!d) goto bad;
            free(d);
        } else {
            uint64_t ol = 0;
            p->win.pos = pos + hl;
            if(sm_inflate_stream(sm_win_read, &p->win, null_wr, NULL, 0, &ol, &used) != HUB_INF_OK ||
               ol != size)
                goto bad;
        }
        uint64_t end = pos + hl + used;
        if(end > p->len - 20) goto bad;
        uint32_t crc = 0;
        for(uint64_t o = pos; o < end;) {
            size_t k = end - o > 65536 ? 65536 : (size_t)(end - o);
            if(sm_win_get(&p->win, o, cbuf, k) != k) goto bad;
            crc = sm_crc32(crc, cbuf, k);
            o += k;
        }
        e->crc = crc;
        if(e->type && !smmap_put(&p->ids, e->id, i)) goto bad;
        pos = end;
        hub_beat();
    }
    free(cbuf);
    if(p->needed.bad) return ip_fail(p, "out of memory");
    if(pos != p->len - 20) return ip_fail(p, "garbage after the last pack entry");
    return true;
bad:
    free(cbuf);
    return ip_fail(p, "corrupt pack entry");
}

/* ---- pass 2: deltas ---- */

typedef struct {
    uint32_t idx; /* SM_NONE: a base from the repo */
    uint8_t id[20];
    uint8_t type;
    uint8_t* data;
    size_t len;
    uint32_t kid; /* next OFS child position */
    uint32_t ref; /* next REF child (SM_NONE = done) */
    bool ref_started;
} Frame;

static uint32_t ent_by_off(const IpPack* p, uint64_t off) {
    uint32_t lo = 0, hi = p->n;
    while(lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        if(p->e[mid].off == off) return mid;
        if(p->e[mid].off < off)
            lo = mid + 1;
        else
            hi = mid;
    }
    return SM_NONE;
}

static bool has_kids(const IpPack* p, uint32_t idx, const uint8_t id[20]) {
    if(idx != SM_NONE && p->kid_first[idx + 1] > p->kid_first[idx]) return true;
    return smmap_get(&p->refmap, id) != SM_NONE;
}

/* Resolve everything below one base (its data is taken over and freed). */
static bool resolve_from(IpPack* p, uint32_t idx, const uint8_t id[20], int type, uint8_t* data, size_t len) {
    Frame* st = NULL;
    size_t n = 0, cap = 0;
    bool ok = true;
#define PUSH(I, ID, T, D, L)                                                    \
    do {                                                                        \
        if(n == cap) {                                                          \
            size_t nc = cap ? cap * 2 : 16;                                     \
            Frame* ns = realloc(st, nc * sizeof(Frame));                        \
            if(!ns) {                                                           \
                free(D);                                                        \
                ok = false;                                                     \
                break;                                                          \
            }                                                                   \
            st = ns;                                                            \
            cap = nc;                                                           \
        }                                                                       \
        st[n] = (Frame){.idx = (I), .type = (uint8_t)(T), .data = (D), .len = (L)}; \
        memcpy(st[n].id, (ID), 20);                                             \
        st[n].kid = (I) == SM_NONE ? 0 : p->kid_first[(I)];                     \
        n++;                                                                    \
    } while(0)
    PUSH(idx, id, type, data, len);
    while(n && ok) {
        Frame* f = &st[n - 1];
        uint32_t c = SM_NONE;
        if(f->idx != SM_NONE && f->kid < p->kid_first[f->idx + 1]) {
            c = p->kids[f->kid++];
        } else {
            if(!f->ref_started) {
                f->ref_started = true;
                f->ref = smmap_get(&p->refmap, f->id);
            }
            if(f->ref != SM_NONE) {
                c = f->ref;
                f->ref = p->ref_next[c];
            }
        }
        if(c == SM_NONE) {
            free(f->data);
            n--;
            continue;
        }
        Ent* e = &p->e[c];
        if(e->type) continue; /* the same base twice */
        uint8_t* d = inflate_ent(p, e, NULL);
        if(!d) {
            ip_fail(p, "corrupt delta");
            ok = false;
            break;
        }
        uint8_t* res = NULL;
        size_t rl = 0;
        int rc = sm_delta_apply(f->data, f->len, d, (size_t)e->size, &res, &rl);
        free(d);
        if(rc) {
            ip_fail(p, rc == SM_E_NOMEM ? "out of memory" : "bad delta");
            ok = false;
            break;
        }
        e->type = f->type;
        sm_hash_obj(e->type, res, rl, e->id);
        if(e->type != SM_BLOB) names(p, e->type, res, rl);
        if(!smmap_put(&p->ids, e->id, c)) {
            free(res);
            ok = false;
            break;
        }
        hub_beat();
        if(has_kids(p, c, e->id))
            PUSH(c, e->id, e->type, res, rl);
        else
            free(res);
    }
#undef PUSH
    while(n)
        free(st[--n].data);
    free(st);
    if(!ok && !p->err[0]) ip_fail(p, "out of memory");
    return ok;
}

bool ip_pass2(IpPack* p) {
    p->kid_first = calloc((size_t)p->n + 2, sizeof(uint32_t));
    p->kids = malloc(((size_t)p->n + 1) * sizeof(uint32_t));
    p->ref_next = malloc(((size_t)p->n + 1) * sizeof(uint32_t));
    if(!p->kid_first || !p->kids || !p->ref_next) return ip_fail(p, "out of memory");
    /* OFS: the base offset becomes an index; children grouped by base */
    for(uint32_t i = 0; i < p->n; i++) {
        Ent* e = &p->e[i];
        if(e->rtype != SM_OFS_DELTA) continue;
        uint32_t b = ent_by_off(p, e->base);
        if(b == SM_NONE) return ip_fail(p, "delta base offset is not an entry");
        e->base = b;
        p->kid_first[b + 2]++;
    }
    for(uint32_t i = 0; i < p->n; i++)
        p->kid_first[i + 2] += p->kid_first[i + 1];
    for(uint32_t i = 0; i < p->n; i++)
        if(p->e[i].rtype == SM_OFS_DELTA) p->kids[p->kid_first[p->e[i].base + 1]++] = i;
    /* REF: a list per base id (in pack order) */
    for(uint32_t i = p->n; i-- > 0;) {
        Ent* e = &p->e[i];
        if(e->rtype != SM_REF_DELTA) continue;
        const uint8_t* bid = p->refids + e->base * 20;
        p->ref_next[i] = smmap_get(&p->refmap, bid);
        if(!smmap_put(&p->refmap, bid, i)) return ip_fail(p, "out of memory");
    }
    for(uint32_t i = 0; i < p->n; i++) {
        Ent* e = &p->e[i];
        if(e->rtype > SM_TAG || !has_kids(p, i, e->id)) continue;
        uint8_t* d = inflate_ent(p, e, NULL);
        if(!d) return ip_fail(p, "corrupt pack entry");
        if(!resolve_from(p, i, e->id, e->type, d, (size_t)e->size)) return false;
    }
    /* thin pack: bases from the repo */
    for(uint32_t i = 0; i < p->n; i++) {
        Ent* e = &p->e[i];
        if(e->type || e->rtype != SM_REF_DELTA) continue;
        const uint8_t* bid = p->refids + e->base * 20;
        int type;
        uint8_t* d;
        size_t len;
        if(sm_odb_read(p->odb, bid, &type, &d, &len) != SM_OK)
            return ip_fail(p, "delta base is missing");
        if(!smbuf_add(&p->thin, bid, 20)) {
            free(d);
            return ip_fail(p, "out of memory");
        }
        p->nthin++;
        if(!resolve_from(p, SM_NONE, bid, type, d, len)) return false;
    }
    for(uint32_t i = 0; i < p->n; i++)
        if(!p->e[i].type) return ip_fail(p, "unresolved delta");
    return true;
}
