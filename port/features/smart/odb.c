/* Smart HTTP: object store reader (see odb.h). */
#define _FILE_OFFSET_BITS 64
#include "odb.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

/* ---------- the object store ---------- */

#define CACHE_SLOTS  1024
#define CACHE_BUDGET (48u * 1024 * 1024)
#define CACHE_ONE    (4u * 1024 * 1024)
#define CHAIN_MAX    10000

typedef struct {
    int fd;
    uint64_t size;
    uint8_t* map;
    size_t map_len;
    uint32_t n;
    const uint8_t* fan;
    const uint8_t* names;
    const uint8_t* offs;
    const uint8_t* offs64;
    uint32_t n64;
    uint64_t* rev_off; /* sorted offsets, then the trailer offset (n + 1 values) */
    uint32_t* rev_nth;
    SmWin win;
} Pack;

typedef struct {
    int pack;
    uint64_t off;
    int type;
    uint8_t* data;
    size_t len;
} CacheEnt;

struct SmOdb {
    char repo[SM_PATH_MAX];
    Pack* packs;
    int np;
    CacheEnt* cache;
    size_t cache_bytes;
};

static uint32_t be32(const uint8_t* p) {
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}

static bool pack_load(Pack* k, const char* idx_path, const char* pack_path) {
    memset(k, 0, sizeof(*k));
    k->fd = -1;
    int ifd = open(idx_path, O_RDONLY | O_CLOEXEC);
    if(ifd < 0) return false;
    struct stat st;
    bool ok = fstat(ifd, &st) == 0 && st.st_size >= 8 + 1024 + 40;
    if(ok) {
        k->map_len = (size_t)st.st_size;
        k->map = mmap(NULL, k->map_len, PROT_READ, MAP_PRIVATE, ifd, 0);
        if(k->map == MAP_FAILED) {
            k->map = NULL;
            ok = false;
        }
    }
    close(ifd);
    if(!ok) return false;
    const uint8_t* m = k->map;
    if(memcmp(m, "\377tOc", 4) != 0 || be32(m + 4) != 2) goto bad;
    k->fan = m + 8;
    k->n = be32(k->fan + 255 * 4);
    uint64_t need = 8 + 1024 + (uint64_t)k->n * 28 + 40;
    if(need > k->map_len) goto bad;
    k->names = k->fan + 1024;
    k->offs = k->names + (size_t)k->n * 24; /* names, then crc32 */
    k->offs64 = k->offs + (size_t)k->n * 4;
    k->n64 = (uint32_t)((k->map_len - need) / 8);
    k->fd = open(pack_path, O_RDONLY | O_CLOEXEC);
    if(k->fd < 0) goto bad;
    if(fstat(k->fd, &st) != 0 || st.st_size < 32) goto bad;
    k->size = (uint64_t)st.st_size;
    uint8_t h[12];
    if(pread(k->fd, h, 12, 0) != 12 || memcmp(h, "PACK", 4) != 0 || be32(h + 8) != k->n) goto bad;
    if(!sm_win_init(&k->win, k->fd, 64 * 1024)) goto bad;
    return true;
bad:
    if(k->fd >= 0) close(k->fd);
    if(k->map) munmap(k->map, k->map_len);
    memset(k, 0, sizeof(*k));
    k->fd = -1;
    return false;
}

static void pack_free(Pack* k) {
    if(k->fd >= 0) close(k->fd);
    if(k->map) munmap(k->map, k->map_len);
    free(k->rev_off);
    free(k->rev_nth);
    sm_win_free(&k->win);
}

static uint64_t pack_nth_off(const Pack* k, uint32_t nth) {
    uint32_t v = be32(k->offs + (size_t)nth * 4);
    if(!(v & 0x80000000u)) return v;
    uint32_t i = v & 0x7fffffffu;
    if(i >= k->n64) return 0;
    const uint8_t* p = k->offs64 + (size_t)i * 8;
    return (uint64_t)be32(p) << 32 | be32(p + 4);
}

static bool pack_lookup(const Pack* k, const uint8_t id[20], uint32_t* nth) {
    uint32_t lo = id[0] ? be32(k->fan + (id[0] - 1) * 4) : 0;
    uint32_t hi = be32(k->fan + id[0] * 4);
    while(lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        int c = memcmp(k->names + (size_t)mid * 20, id, 20);
        if(c == 0) {
            *nth = mid;
            return true;
        }
        if(c < 0)
            lo = mid + 1;
        else
            hi = mid;
    }
    return false;
}

/* qsort context: a plain global is fine (one worker thread) */
static const uint64_t* sort_offs;
static int cmp_nth_by_off(const void* a, const void* b) {
    uint64_t x = sort_offs[*(const uint32_t*)a], y = sort_offs[*(const uint32_t*)b];
    return x < y ? -1 : x > y;
}

static bool pack_rev(Pack* k) {
    if(k->rev_off) return true;
    uint64_t* offs = malloc(((size_t)k->n + 1) * sizeof(uint64_t));
    uint32_t* nth = malloc(((size_t)k->n + 1) * sizeof(uint32_t));
    uint64_t* so = malloc(((size_t)k->n + 1) * sizeof(uint64_t));
    if(!offs || !nth || !so) {
        free(offs);
        free(nth);
        free(so);
        return false;
    }
    for(uint32_t i = 0; i < k->n; i++) {
        offs[i] = pack_nth_off(k, i);
        nth[i] = i;
    }
    sort_offs = offs;
    qsort(nth, k->n, sizeof(uint32_t), cmp_nth_by_off);
    for(uint32_t i = 0; i < k->n; i++)
        so[i] = offs[nth[i]];
    so[k->n] = k->size - 20;
    nth[k->n] = SM_NONE;
    free(offs);
    k->rev_off = so;
    k->rev_nth = nth;
    return true;
}

static bool pack_rev_find(Pack* k, uint64_t off, uint32_t* pos) {
    if(!pack_rev(k)) return false;
    uint32_t lo = 0, hi = k->n;
    while(lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        if(k->rev_off[mid] == off) {
            *pos = mid;
            return true;
        }
        if(k->rev_off[mid] < off)
            lo = mid + 1;
        else
            hi = mid;
    }
    return false;
}

SmOdb* sm_odb_open(const char* repo) {
    SmOdb* o = calloc(1, sizeof(SmOdb));
    if(!o) return NULL;
    snprintf(o->repo, sizeof o->repo, "%s", repo);
    o->cache = calloc(CACHE_SLOTS, sizeof(CacheEnt));
    if(!o->cache) {
        free(o);
        return NULL;
    }
    char dir[SM_PATH_MAX + 32];
    snprintf(dir, sizeof dir, "%s/objects/pack", repo);
    DIR* d = opendir(dir);
    if(!d) return o;
    struct dirent* e;
    int cap = 0;
    while((e = readdir(d)) != NULL) {
        size_t l = strlen(e->d_name);
        if(l < 10 || strncmp(e->d_name, "pack-", 5) != 0 || strcmp(e->d_name + l - 4, ".idx") != 0)
            continue;
        char ip[SM_PATH_MAX + 300], pp[SM_PATH_MAX + 300];
        snprintf(ip, sizeof ip, "%s/%s", dir, e->d_name);
        snprintf(pp, sizeof pp, "%s/%.*s.pack", dir, (int)(l - 4), e->d_name);
        if(o->np == cap) {
            int nc = cap ? cap * 2 : 8;
            Pack* np = realloc(o->packs, sizeof(Pack) * (size_t)nc);
            if(!np) break;
            o->packs = np;
            cap = nc;
        }
        if(pack_load(&o->packs[o->np], ip, pp)) o->np++;
    }
    closedir(d);
    return o;
}

void sm_odb_close(SmOdb* o) {
    if(!o) return;
    for(int i = 0; i < o->np; i++)
        pack_free(&o->packs[i]);
    free(o->packs);
    for(int i = 0; i < CACHE_SLOTS; i++)
        free(o->cache[i].data);
    free(o->cache);
    free(o);
}

const char* sm_odb_repo(const SmOdb* o) {
    return o->repo;
}

int sm_odb_npacks(const SmOdb* o) {
    return o->np;
}

bool sm_odb_find(SmOdb* o, const uint8_t id[20], int* pack, uint64_t* off) {
    for(int i = 0; i < o->np; i++) {
        uint32_t nth;
        if(pack_lookup(&o->packs[i], id, &nth)) {
            if(pack) *pack = i;
            if(off) *off = pack_nth_off(&o->packs[i], nth);
            return true;
        }
    }
    return false;
}

bool sm_odb_has(SmOdb* o, const uint8_t id[20]) {
    if(sm_odb_find(o, id, NULL, NULL)) return true;
    char p[SM_PATH_MAX + 64];
    sm_loose_path(o, id, p, sizeof p);
    return access(p, F_OK) == 0;
}

int sm_odb_entry(SmOdb* o, int pack, uint64_t off, SmEnt* e) {
    Pack* k = &o->packs[pack];
    uint8_t h[64];
    if(off < 12 || off >= k->size - 20) return SM_E_CORRUPT;
    size_t n = sm_win_get(&k->win, off, h, sizeof h);
    uint64_t size;
    int type;
    size_t hl = sm_entry_hdr_parse(h, n, &type, &size);
    if(!hl) return SM_E_CORRUPT;
    memset(e, 0, sizeof(*e));
    e->type = type;
    e->size = size;
    if(type == SM_OFS_DELTA) {
        size_t i = hl;
        if(i >= n) return SM_E_CORRUPT;
        uint8_t c = h[i++];
        uint64_t ofs = c & 127;
        while(c & 128) {
            if(i >= n || ofs > (UINT64_MAX >> 8)) return SM_E_CORRUPT;
            c = h[i++];
            ofs = ((ofs + 1) << 7) | (c & 127);
        }
        if(ofs == 0 || ofs > off - 12) return SM_E_CORRUPT;
        e->base_off = off - ofs;
        hl = i;
    } else if(type == SM_REF_DELTA) {
        if(hl + 20 > n) return SM_E_CORRUPT;
        memcpy(e->base_id, h + hl, 20);
        hl += 20;
    } else if(type < SM_COMMIT || type > SM_TAG) {
        return SM_E_CORRUPT;
    }
    e->hdr_len = (uint32_t)hl;
    return SM_OK;
}

uint64_t sm_odb_entry_end(SmOdb* o, int pack, uint64_t off) {
    Pack* k = &o->packs[pack];
    uint32_t pos;
    if(!pack_rev_find(k, off, &pos)) return 0;
    return k->rev_off[pos + 1];
}

bool sm_odb_id_at(SmOdb* o, int pack, uint64_t off, uint8_t id[20]) {
    Pack* k = &o->packs[pack];
    uint32_t pos;
    if(!pack_rev_find(k, off, &pos)) return false;
    memcpy(id, k->names + (size_t)k->rev_nth[pos] * 20, 20);
    return true;
}

bool sm_odb_copy(
    SmOdb* o,
    int pack,
    uint64_t from,
    uint64_t to,
    bool (*sink)(void*, const uint8_t*, size_t),
    void* ctx) {
    Pack* k = &o->packs[pack];
    uint8_t* b = malloc(64 * 1024);
    if(!b) return false;
    bool ok = true;
    while(ok && from < to) {
        size_t want = to - from > 65536 ? 65536 : (size_t)(to - from);
        ssize_t r = pread(k->fd, b, want, (off_t)from);
        if(r <= 0) {
            if(r < 0 && errno == EINTR) continue;
            ok = false;
            break;
        }
        ok = sink(ctx, b, (size_t)r);
        from += (uint64_t)r;
    }
    free(b);
    return ok;
}

/* inflate the zlib data of an entry into a new buffer of exactly size bytes */
static int pack_inflate(Pack* k, uint64_t data_off, uint64_t size, uint8_t** out) {
    if(size > (uint64_t)SIZE_MAX - 1) return SM_E_NOMEM;
    uint8_t* b = malloc((size_t)size + 1);
    if(!b) return SM_E_NOMEM;
    k->win.pos = data_off;
    size_t ol = 0;
    int rc = hub_inflate_buf(sm_win_read, &k->win, b, (size_t)size, 0, &ol, NULL);
    if(rc != HUB_INF_OK || ol != size) {
        free(b);
        return rc == HUB_INF_ERR_NOMEM ? SM_E_NOMEM : SM_E_CORRUPT;
    }
    b[size] = 0;
    *out = b;
    return SM_OK;
}

static CacheEnt* cache_slot(SmOdb* o, int pack, uint64_t off) {
    uint64_t h = (off * 0x9E3779B97F4A7C15ull) ^ (uint64_t)pack;
    return &o->cache[(h >> 20) % CACHE_SLOTS];
}

static void cache_put(SmOdb* o, int pack, uint64_t off, int type, const uint8_t* data, size_t len) {
    if(len > CACHE_ONE) return;
    CacheEnt* c = cache_slot(o, pack, off);
    if(c->data) {
        o->cache_bytes -= c->len;
        free(c->data);
        c->data = NULL;
    }
    if(o->cache_bytes + len > CACHE_BUDGET) return;
    c->data = malloc(len + 1);
    if(!c->data) return;
    memcpy(c->data, data, len);
    c->data[len] = 0;
    c->len = len;
    c->pack = pack;
    c->off = off;
    c->type = type;
    o->cache_bytes += len;
}

int sm_odb_read_at(SmOdb* o, int pack, uint64_t off, int* type, uint8_t** data, size_t* len) {
    typedef struct {
        int pack;
        uint64_t off;
        uint64_t size;
        uint64_t data_off;
    } Step;
    Step* chain = NULL;
    size_t nchain = 0, cap = 0;
    uint8_t* cur = NULL;
    size_t cur_len = 0;
    int cur_type = 0;
    int rc = SM_OK;
    int p = pack;
    uint64_t at = off;
    for(;;) {
        CacheEnt* c = cache_slot(o, p, at);
        if(c->data && c->pack == p && c->off == at) {
            cur = malloc(c->len + 1);
            if(!cur) {
                rc = SM_E_NOMEM;
                goto out;
            }
            memcpy(cur, c->data, c->len + 1);
            cur_len = c->len;
            cur_type = c->type;
            break;
        }
        SmEnt e;
        rc = sm_odb_entry(o, p, at, &e);
        if(rc) goto out;
        if(e.type >= SM_COMMIT && e.type <= SM_TAG) {
            rc = pack_inflate(&o->packs[p], at + e.hdr_len, e.size, &cur);
            if(rc) goto out;
            cur_len = (size_t)e.size;
            cur_type = e.type;
            if(nchain) cache_put(o, p, at, cur_type, cur, cur_len); /* a delta base */
            break;
        }
        if(nchain >= CHAIN_MAX) {
            rc = SM_E_CORRUPT;
            goto out;
        }
        if(nchain == cap) {
            size_t nc = cap ? cap * 2 : 16;
            Step* ns = realloc(chain, nc * sizeof(Step));
            if(!ns) {
                rc = SM_E_NOMEM;
                goto out;
            }
            chain = ns;
            cap = nc;
        }
        chain[nchain++] = (Step){p, at, e.size, at + e.hdr_len};
        if(e.type == SM_OFS_DELTA) {
            at = e.base_off;
        } else {
            uint32_t nth;
            if(pack_lookup(&o->packs[p], e.base_id, &nth)) {
                at = pack_nth_off(&o->packs[p], nth);
            } else if(sm_odb_find(o, e.base_id, &p, &at)) {
                /* in another pack */
            } else {
                rc = sm_loose_read(o, e.base_id, &cur_type, &cur, &cur_len);
                if(rc) goto out;
                break;
            }
        }
    }
    while(nchain) {
        Step s = chain[--nchain];
        uint8_t* d = NULL;
        rc = pack_inflate(&o->packs[s.pack], s.data_off, s.size, &d);
        if(rc) goto out;
        uint8_t* res = NULL;
        size_t rl = 0;
        rc = sm_delta_apply(cur, cur_len, d, (size_t)s.size, &res, &rl);
        free(d);
        if(rc) goto out;
        free(cur);
        cur = res;
        cur_len = rl;
        if(nchain || cur_type == SM_TREE) cache_put(o, s.pack, s.off, cur_type, cur, cur_len);
    }
    *type = cur_type;
    *data = cur;
    *len = cur_len;
    cur = NULL;
out:
    free(cur);
    free(chain);
    return rc;
}

