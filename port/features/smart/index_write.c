/* Smart HTTP: index a pushed pack: the entry point, fix-thin, the .idx v2 (see index_pack.h). */
#define _FILE_OFFSET_BITS 64
#include "index_int.h"

#include "hub_deflate.h"
#include "hub_req.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void put32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

/* ---- buffered appends to a file, with an optional SHA-1 and CRC-32 of what goes through ---- */

typedef struct {
    int fd;
    uint64_t pos;
    uint8_t buf[65536];
    size_t n;
    HubSha1* sha;
    uint32_t crc;
    bool ok;
} Wr;

static void wr_flush(Wr* w) {
    size_t off = 0;
    while(w->ok && off < w->n) {
        ssize_t k = pwrite(w->fd, w->buf + off, w->n - off, (off_t)(w->pos + off));
        if(k < 0 && errno == EINTR) continue;
        if(k <= 0) w->ok = false;
        else off += (size_t)k;
    }
    w->pos += w->n;
    w->n = 0;
}

static bool wr_put(void* ctx, const uint8_t* d, size_t n) {
    Wr* w = ctx;
    if(w->sha) hub_sha1_update(w->sha, d, n);
    w->crc = sm_crc32(w->crc, d, n);
    while(n && w->ok) {
        size_t k = sizeof w->buf - w->n < n ? sizeof w->buf - w->n : n;
        memcpy(w->buf + w->n, d, k);
        w->n += k;
        d += k;
        n -= k;
        if(w->n == sizeof w->buf) wr_flush(w);
    }
    return w->ok;
}

/* ---- fix-thin: append the bases that came from the repo ---- */

static bool fix_thin(IpPack* p) {
    Ent* ne = realloc(p->e, ((size_t)p->n + p->nthin) * sizeof(Ent));
    if(!ne) return ip_fail(p, "out of memory");
    p->e = ne;
    Wr* w = calloc(1, sizeof(Wr));
    HubDeflate* df = hub_deflate_new(wr_put, w);
    if(!w || !df) {
        free(w);
        if(df) hub_deflate_free(df);
        return ip_fail(p, "out of memory");
    }
    w->fd = p->fd;
    w->pos = p->len - 20; /* over the old trailer */
    w->ok = true;
    bool ok = true;
    for(uint32_t t = 0; t < p->nthin && ok; t++) {
        const uint8_t* id = p->thin.p + (size_t)t * 20;
        int type;
        uint8_t* d;
        size_t len;
        if(sm_odb_read(p->odb, id, &type, &d, &len) != SM_OK) {
            ok = false;
            break;
        }
        Ent* e = &p->e[p->n + t];
        memset(e, 0, sizeof(*e));
        e->off = w->pos + w->n;
        e->type = e->rtype = (uint8_t)type;
        e->size = len;
        memcpy(e->id, id, 20);
        w->crc = 0;
        uint8_t h[16];
        size_t hl = sm_entry_hdr_put(h, type, len);
        wr_put(w, h, hl);
        hub_deflate_reset(df, wr_put, w);
        ok = hub_deflate_write(df, d, len) && hub_deflate_finish(df) && w->ok;
        free(d);
        e->crc = w->crc;
        if(ok) ok = smmap_put(&p->ids, id, p->n + t);
        hub_beat();
    }
    wr_flush(w);
    ok = ok && w->ok;
    uint64_t end = w->pos;
    free(w);
    hub_deflate_free(df);
    if(!ok) return ip_fail(p, "cannot append the thin pack bases");
    p->n += p->nthin;
    /* new count, new trailer over the whole file */
    uint8_t c[4];
    put32(c, p->n);
    if(pwrite(p->fd, c, 4, 8) != 4 || ftruncate(p->fd, (off_t)end) != 0)
        return ip_fail(p, "cannot rewrite the pack header");
    HubSha1 s;
    hub_sha1_init(&s);
    uint8_t* b = malloc(65536);
    if(!b) return ip_fail(p, "out of memory");
    for(uint64_t o = 0; o < end;) {
        ssize_t k = pread(p->fd, b, end - o > 65536 ? 65536 : (size_t)(end - o), (off_t)o);
        if(k <= 0) {
            free(b);
            return ip_fail(p, "cannot read the pack");
        }
        hub_sha1_update(&s, b, (size_t)k);
        o += (uint64_t)k;
    }
    free(b);
    hub_sha1_final(&s, p->trailer);
    if(pwrite(p->fd, p->trailer, 20, (off_t)end) != 20) return ip_fail(p, "cannot write the trailer");
    p->len = end + 20;
    return true;
}

/* ---- entry point ---- */

IpPack* ip_index(int fd, SmOdb* repo, char* err, size_t errcap) {
    IpPack* p = calloc(1, sizeof(IpPack));
    if(!p) {
        snprintf(err, errcap, "out of memory");
        return NULL;
    }
    p->fd = fd;
    p->odb = repo;
    p->err = err;
    p->errcap = errcap;
    err[0] = '\0';
    if(!sm_win_init(&p->win, fd, 256 * 1024)) {
        ip_fail(p, "out of memory");
        goto bad;
    }
    p->len = p->win.size;
    uint8_t h[12];
    if(p->len < 32 || sm_win_get(&p->win, 0, h, 12) != 12 || memcmp(h, "PACK", 4) != 0 ||
       (h[7] != 2 && h[7] != 3) || h[4] || h[5] || h[6]) {
        ip_fail(p, "not a pack");
        goto bad;
    }
    p->n = (uint32_t)h[8] << 24 | (uint32_t)h[9] << 16 | (uint32_t)h[10] << 8 | h[11];
    if(p->n > (p->len - 32) / 2) {
        ip_fail(p, "bad object count");
        goto bad;
    }
    sm_win_get(&p->win, p->len - 20, p->trailer, 20);
    p->e = calloc((size_t)p->n + 1, sizeof(Ent));
    p->refids = malloc(((size_t)p->n + 1) * 20);
    if(!p->e || !p->refids || !smmap_init(&p->ids, p->n) || !smmap_init(&p->refmap, 16)) {
        ip_fail(p, "out of memory");
        goto bad;
    }
    if(!ip_pass1(p) || !ip_pass2(p)) goto bad;
    if(p->nthin && !fix_thin(p)) goto bad;
    return p;
bad:
    ip_free(p);
    return NULL;
}

void ip_free(IpPack* p) {
    if(!p) return;
    sm_win_free(&p->win);
    free(p->e);
    free(p->refids);
    smmap_free(&p->ids);
    smmap_free(&p->refmap);
    free(p->kid_first);
    free(p->kids);
    free(p->ref_next);
    smbuf_free(&p->needed);
    smbuf_free(&p->thin);
    free(p);
}

uint32_t ip_count(const IpPack* p) {
    return p->n;
}

uint32_t ip_thin(const IpPack* p) {
    return p->nthin;
}

int ip_type(const IpPack* p, const uint8_t id[20]) {
    uint32_t i = smmap_get(&p->ids, id);
    return i == SM_NONE ? 0 : p->e[i].type;
}

void ip_trailer(const IpPack* p, uint8_t sha[20]) {
    memcpy(sha, p->trailer, 20);
}

bool ip_connected(const IpPack* p, SmOdb* repo, uint8_t missing[20]) {
    SmMap seen = {0};
    bool ok = true;
    for(size_t i = 0; i + 20 <= p->needed.n && ok; i += 20) {
        const uint8_t* id = p->needed.p + i;
        if(smmap_get(&p->ids, id) != SM_NONE || smmap_get(&seen, id) != SM_NONE) continue;
        if(!sm_odb_has(repo, id)) {
            memcpy(missing, id, 20);
            ok = false;
        }
        smmap_put(&seen, id, 1);
        if(i % (20 * 4096) == 0) hub_beat();
    }
    smmap_free(&seen);
    return ok;
}

/* ---- .idx v2 ---- */

static const Ent* sort_ents;
static int cmp_by_id(const void* a, const void* b) {
    return memcmp(sort_ents[*(const uint32_t*)a].id, sort_ents[*(const uint32_t*)b].id, 20);
}

bool ip_write_idx(IpPack* p, const char* path) {
    uint32_t* ord = malloc(((size_t)p->n + 1) * sizeof(uint32_t));
    Wr* w = calloc(1, sizeof(Wr));
    HubSha1* s = malloc(sizeof(HubSha1));
    int fd = open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    bool ok = ord && w && s && fd >= 0;
    if(ok) {
        for(uint32_t i = 0; i < p->n; i++)
            ord[i] = i;
        sort_ents = p->e;
        qsort(ord, p->n, sizeof(uint32_t), cmp_by_id);
        hub_sha1_init(s);
        w->fd = fd;
        w->ok = true;
        w->sha = s;
        uint8_t b[8] = {0xff, 't', 'O', 'c', 0, 0, 0, 2};
        wr_put(w, b, 8);
        uint32_t k = 0;
        for(int f = 0; f < 256; f++) {
            while(k < p->n && p->e[ord[k]].id[0] == f)
                k++;
            put32(b, k);
            wr_put(w, b, 4);
        }
        for(uint32_t i = 0; i < p->n; i++)
            wr_put(w, p->e[ord[i]].id, 20);
        for(uint32_t i = 0; i < p->n; i++) {
            put32(b, p->e[ord[i]].crc);
            wr_put(w, b, 4);
        }
        uint32_t big = 0;
        for(uint32_t i = 0; i < p->n; i++) {
            uint64_t off = p->e[ord[i]].off;
            put32(b, off < 0x80000000u ? (uint32_t)off : 0x80000000u | big++);
            wr_put(w, b, 4);
        }
        for(uint32_t i = 0; i < p->n; i++) {
            uint64_t off = p->e[ord[i]].off;
            if(off < 0x80000000u) continue;
            put32(b, (uint32_t)(off >> 32));
            put32(b + 4, (uint32_t)off);
            wr_put(w, b, 8);
        }
        wr_put(w, p->trailer, 20);
        uint8_t sum[20];
        hub_sha1_final(s, sum);
        w->sha = NULL;
        wr_put(w, sum, 20);
        wr_flush(w);
        ok = w->ok && fsync(fd) == 0;
    }
    if(fd >= 0 && close(fd) != 0) ok = false;
    free(ord);
    free(w);
    free(s);
    return ok;
}
