/* Smart HTTP: loose objects, and reads by id over packs and loose objects (see odb.h). */
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

#define CHAIN_MAX 10000

void sm_loose_path(const SmOdb* o, const uint8_t id[20], char* out, size_t cap) {
    char hex[41];
    sm_hex(id, hex);
    snprintf(out, cap, "%s/objects/%.2s/%s", sm_odb_repo(o), hex, hex + 2);
}

/* ---- loose objects ---- */

static bool loose_hdr(const uint8_t* h, size_t n, int* type, uint64_t* size, size_t* hl) {
    const uint8_t* nul = memchr(h, 0, n);
    if(!nul) return false;
    const char* s = (const char*)h;
    if(!strncmp(s, "blob ", 5))
        *type = SM_BLOB, s += 5;
    else if(!strncmp(s, "tree ", 5))
        *type = SM_TREE, s += 5;
    else if(!strncmp(s, "commit ", 7))
        *type = SM_COMMIT, s += 7;
    else if(!strncmp(s, "tag ", 4))
        *type = SM_TAG, s += 4;
    else
        return false;
    uint64_t v = 0;
    if(*s < '0' || *s > '9') return false;
    while(*s >= '0' && *s <= '9')
        v = v * 10 + (uint64_t)(*s++ - '0');
    if((const uint8_t*)s != nul) return false;
    *size = v;
    *hl = (size_t)(nul - h) + 1;
    return true;
}

static int loose_open(SmOdb* o, const uint8_t id[20], SmWin* w) {
    char p[SM_PATH_MAX + 64];
    sm_loose_path(o, id, p, sizeof p);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    if(fd < 0) return SM_E_NOTFOUND;
    if(!sm_win_init(w, fd, 32 * 1024)) {
        close(fd);
        return SM_E_NOMEM;
    }
    return SM_OK;
}

static void loose_close(SmWin* w) {
    close(w->fd);
    sm_win_free(w);
}

static int loose_head(SmWin* w, int* type, uint64_t* size, size_t* hl) {
    uint8_t h[64];
    size_t got = 0;
    w->pos = 0;
    int rc = hub_inflate_buf(sm_win_read, w, h, sizeof h, HUB_INF_PARTIAL, &got, NULL);
    if(rc != HUB_INF_OK || !loose_hdr(h, got, type, size, hl)) return SM_E_CORRUPT;
    return SM_OK;
}

int sm_odb_loose_info(SmOdb* o, const uint8_t id[20], int* type, uint64_t* size) {
    SmWin w;
    int rc = loose_open(o, id, &w);
    if(rc) return rc;
    size_t hl;
    rc = loose_head(&w, type, size, &hl);
    loose_close(&w);
    return rc;
}

int sm_loose_read(SmOdb* o, const uint8_t id[20], int* type, uint8_t** data, size_t* len) {
    SmWin w;
    int rc = loose_open(o, id, &w);
    if(rc) return rc;
    uint64_t size;
    size_t hl;
    rc = loose_head(&w, type, &size, &hl);
    if(!rc && size + hl > (uint64_t)SIZE_MAX - 1) rc = SM_E_NOMEM;
    uint8_t* b = NULL;
    if(!rc) {
        b = malloc((size_t)size + hl + 1);
        if(!b) rc = SM_E_NOMEM;
    }
    if(!rc) {
        size_t got = 0;
        w.pos = 0;
        int ir = hub_inflate_buf(sm_win_read, &w, b, (size_t)size + hl, 0, &got, NULL);
        if(ir != HUB_INF_OK || got != size + hl) {
            rc = ir == HUB_INF_ERR_NOMEM ? SM_E_NOMEM : SM_E_CORRUPT;
        } else {
            memmove(b, b + hl, (size_t)size);
            b[size] = 0;
            *data = b;
            *len = (size_t)size;
            b = NULL;
        }
    }
    free(b);
    loose_close(&w);
    return rc;
}

typedef struct {
    HubInfWrite wr;
    void* ctx;
    size_t skip;
} SkipWr;

static bool skip_wr(void* c, const uint8_t* b, size_t n) {
    SkipWr* s = c;
    if(s->skip) {
        size_t k = n < s->skip ? n : s->skip;
        s->skip -= k;
        b += k;
        n -= k;
        if(!n) return true;
    }
    return s->wr(s->ctx, b, n);
}

int sm_odb_loose_stream(SmOdb* o, const uint8_t id[20], HubInfWrite wr, void* ctx) {
    SmWin w;
    int rc = loose_open(o, id, &w);
    if(rc) return rc;
    int type;
    uint64_t size, got = 0;
    size_t hl;
    rc = loose_head(&w, &type, &size, &hl);
    if(!rc) {
        SkipWr s = {wr, ctx, hl};
        w.pos = 0;
        int ir = sm_inflate_stream(sm_win_read, &w, skip_wr, &s, 0, &got, NULL);
        if(ir != HUB_INF_OK || got != size + hl) rc = ir == HUB_INF_ERR_WRITE ? SM_E_IO : SM_E_CORRUPT;
    }
    loose_close(&w);
    return rc;
}

int sm_odb_read(SmOdb* o, const uint8_t id[20], int* type, uint8_t** data, size_t* len) {
    int p;
    uint64_t off;
    if(sm_odb_find(o, id, &p, &off)) return sm_odb_read_at(o, p, off, type, data, len);
    return sm_loose_read(o, id, type, data, len);
}

int sm_odb_type(SmOdb* o, const uint8_t id[20], int* type) {
    int p;
    uint64_t off;
    if(!sm_odb_find(o, id, &p, &off)) {
        uint64_t size;
        return sm_odb_loose_info(o, id, type, &size);
    }
    for(int guard = 0; guard < CHAIN_MAX; guard++) {
        SmEnt e;
        int rc = sm_odb_entry(o, p, off, &e);
        if(rc) return rc;
        if(e.type >= SM_COMMIT && e.type <= SM_TAG) {
            *type = e.type;
            return SM_OK;
        }
        if(e.type == SM_OFS_DELTA) {
            off = e.base_off;
        } else if(!sm_odb_find(o, e.base_id, &p, &off)) {
            uint64_t size;
            return sm_odb_loose_info(o, e.base_id, type, &size);
        }
    }
    return SM_E_CORRUPT;
}
