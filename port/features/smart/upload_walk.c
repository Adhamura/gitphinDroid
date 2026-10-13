/* Smart HTTP: git-upload-pack: the commit walk (git's limit_list), trees, negotiation helpers. */
#define _FILE_OFFSET_BITS 64
#include "upload.h"

#include "hub_deflate.h"

#include <furi.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------- commits ---------- */

uint32_t up_cm_get(Up* u, const uint8_t id[20]) {
    uint32_t i = smmap_get(&u->cmap, id);
    if(i != SM_NONE) return i;
    if(!up_grow((void**)&u->cm, &u->capcm, u->ncm + 1, sizeof(Cm))) {
        up_fail(u, "out of memory");
        return SM_NONE;
    }
    i = (uint32_t)u->ncm;
    Cm* c = &u->cm[i];
    memset(c, 0, sizeof(*c));
    memcpy(c->id, id, 20);
    c->depth = DEPTH_UNSET;
    if(!smmap_put(&u->cmap, id, i)) {
        up_fail(u, "out of memory");
        return SM_NONE;
    }
    u->ncm++;
    return i;
}

/* Read the commit (tree, parents, committer time). false (and failed) on error. */
bool up_cm_parse(Up* u, uint32_t ci) {
    if(u->cm[ci].flags & C_PARSED) return true;
    int type;
    uint8_t* d;
    size_t n;
    char hex[41];
    if(sm_odb_read(u->odb, u->cm[ci].id, &type, &d, &n) != SM_OK || type != SM_COMMIT) {
        if(type == SM_COMMIT) free(d);
        sm_hex(u->cm[ci].id, hex);
        up_fail(u, "cannot read commit %s", hex);
        return false;
    }
    uint32_t first = (uint32_t)u->npar, np = 0;
    int64_t time = 0;
    uint8_t tree[20];
    bool has_tree = false;
    const char* p = (const char*)d;
    const char* end = p + n;
    while(p < end && *p != '\n') {
        const char* e = memchr(p, '\n', (size_t)(end - p));
        if(!e) e = end;
        uint8_t id[20];
        if(!strncmp(p, "tree ", 5) && e - p >= 45 && sm_unhex(p + 5, tree)) {
            has_tree = true;
        } else if(!strncmp(p, "parent ", 7) && e - p >= 47 && sm_unhex(p + 7, id)) {
            uint32_t pi = up_cm_get(u, id);
            if(pi == SM_NONE || !up_push_u32(&u->par, &u->npar, &u->cappar, pi)) {
                free(d);
                up_fail(u, "out of memory");
                return false;
            }
            np++;
        } else if(!strncmp(p, "committer ", 10)) {
            const char* gt = NULL;
            for(const char* q = p; q < e; q++)
                if(*q == '>') gt = q;
            if(gt) {
                const char* q = gt + 1;
                while(q < e && *q == ' ')
                    q++;
                int64_t t = 0;
                while(q < e && *q >= '0' && *q <= '9')
                    t = t * 10 + (*q++ - '0');
                time = t;
            }
        }
        p = e + 1;
    }
    free(d);
    if(!has_tree) {
        sm_hex(u->cm[ci].id, hex);
        up_fail(u, "bad commit %s", hex);
        return false;
    }
    Cm* c = &u->cm[ci]; /* cm may have moved */
    memcpy(c->tree, tree, 20);
    c->time = time;
    c->par = first;
    c->npar = np;
    c->flags |= C_PARSED;
    return true;
}

/* ---------- object set ---------- */

uint32_t up_obj_add(Up* u, const uint8_t id[20], int type) {
    uint32_t v = smmap_get(&u->objs, id);
    if(v != SM_NONE) return SM_NONE; /* sent already, or the client has it */
    if(!up_grow((void**)&u->send, &u->capsend, u->nsend + 1, sizeof(Obj))) {
        up_fail(u, "out of memory");
        return SM_NONE;
    }
    Obj* o = &u->send[u->nsend];
    memset(o, 0, sizeof(*o));
    memcpy(o->id, id, 20);
    o->type = (uint8_t)type;
    o->pack = -1;
    if(!smmap_put(&u->objs, id, (uint32_t)u->nsend)) {
        up_fail(u, "out of memory");
        return SM_NONE;
    }
    return (uint32_t)u->nsend++;
}

bool up_obj_unint(Up* u, const uint8_t id[20]) {
    if(smmap_get(&u->objs, id) != SM_NONE) return false;
    if(!smmap_put(&u->objs, id, OBJ_UNINT)) up_fail(u, "out of memory");
    return true;
}

/* Walk tree entries: cb(mode_kind, id) with kind 0 blob, 1 tree (gitlinks are skipped). */
bool up_tree_entries(Up* u, const uint8_t tree[20], SmBuf* out) {
    int type;
    uint8_t* d;
    size_t n;
    char hex[41];
    if(sm_odb_read(u->odb, tree, &type, &d, &n) != SM_OK || type != SM_TREE) {
        if(type == SM_TREE) free(d);
        sm_hex(tree, hex);
        up_fail(u, "cannot read tree %s", hex);
        return false;
    }
    const uint8_t* p = d;
    const uint8_t* end = d + n;
    bool ok = true;
    while(p < end) {
        const uint8_t* sp = memchr(p, ' ', (size_t)(end - p));
        if(!sp) {
            ok = false;
            break;
        }
        uint32_t mode = 0;
        for(const uint8_t* q = p; q < sp; q++)
            mode = mode * 8 + (uint32_t)(*q - '0');
        const uint8_t* nul = memchr(sp, 0, (size_t)(end - sp));
        if(!nul || end - nul < 21) {
            ok = false;
            break;
        }
        const uint8_t* id = nul + 1;
        p = id + 20;
        if(mode == 0160000) continue; /* submodule commit: not in this repo */
        uint8_t kind = (mode & 0170000) == 0040000 ? 1 : 0;
        smbuf_add(out, &kind, 1);
        smbuf_add(out, id, 20);
    }
    free(d);
    if(!ok) {
        sm_hex(tree, hex);
        up_fail(u, "bad tree %s", hex);
    }
    if(out->bad) up_fail(u, "out of memory");
    return ok && !out->bad;
}

/* Everything below a tree the client has: never sent. */
void up_mark_tree_unint(Up* u, const uint8_t root[20]) {
    if(!up_obj_unint(u, root)) return;
    SmBuf stack = {0};
    smbuf_add(&stack, root, 20);
    SmBuf ents = {0};
    unsigned beat = 0;
    while(stack.n && !u->failed) {
        uint8_t t[20];
        stack.n -= 20;
        memcpy(t, stack.p + stack.n, 20);
        ents.n = 0;
        if(!up_tree_entries(u, t, &ents)) break;
        for(size_t i = 0; i < ents.n; i += 21) {
            if(up_obj_unint(u, ents.p + i + 1) && ents.p[i] == 1) smbuf_add(&stack, ents.p + i + 1, 20);
        }
        if(++beat % 256 == 0) hub_beat();
    }
    smbuf_free(&ents);
    smbuf_free(&stack);
}

/* Every object of a tree that is not known yet goes into the pack. */
void up_add_tree(Up* u, const uint8_t root[20]) {
    if(up_obj_add(u, root, SM_TREE) == SM_NONE) return;
    SmBuf stack = {0};
    smbuf_add(&stack, root, 20);
    SmBuf ents = {0};
    unsigned beat = 0;
    while(stack.n && !u->failed) {
        uint8_t t[20];
        stack.n -= 20;
        memcpy(t, stack.p + stack.n, 20);
        ents.n = 0;
        if(!up_tree_entries(u, t, &ents)) break;
        for(size_t i = 0; i < ents.n; i += 21) {
            bool tree = ents.p[i] == 1;
            if(up_obj_add(u, ents.p + i + 1, tree ? SM_TREE : SM_BLOB) != SM_NONE && tree)
                smbuf_add(&stack, ents.p + i + 1, 20);
        }
        if(++beat % 256 == 0) hub_beat();
    }
    smbuf_free(&ents);
    smbuf_free(&stack);
}

/* ---------- the commit walk (git's limit_list) ---------- */

typedef struct {
    uint32_t* v;
    size_t n, cap;
} Heap;

static bool heap_less(const Up* u, uint32_t a, uint32_t b) {
    return u->cm[a].time < u->cm[b].time;
}

static bool heap_push(Up* u, Heap* h, uint32_t x) {
    if(!up_grow((void**)&h->v, &h->cap, h->n + 1, sizeof(uint32_t))) return false;
    size_t i = h->n++;
    h->v[i] = x;
    while(i > 0) {
        size_t p = (i - 1) / 2;
        if(!heap_less(u, h->v[p], h->v[i])) break;
        uint32_t t = h->v[p];
        h->v[p] = h->v[i];
        h->v[i] = t;
        i = p;
    }
    return true;
}

static uint32_t heap_pop(Up* u, Heap* h) {
    uint32_t top = h->v[0];
    h->v[0] = h->v[--h->n];
    size_t i = 0;
    for(;;) {
        size_t l = 2 * i + 1, r = l + 1, m = i;
        if(l < h->n && heap_less(u, h->v[m], h->v[l])) m = l;
        if(r < h->n && heap_less(u, h->v[m], h->v[r])) m = r;
        if(m == i) break;
        uint32_t t = h->v[m];
        h->v[m] = h->v[i];
        h->v[i] = t;
        i = m;
    }
    return top;
}

/* A commit the client has: so are all its ancestors (as far as they are parsed now; the
 * others get the flag when the walk reaches them). */
static void mark_parents_unint(Up* u, uint32_t ci) {
    uint32_t* st = NULL;
    size_t n = 0, cap = 0;
    if(!up_push_u32(&st, &n, &cap, ci)) return;
    while(n) {
        uint32_t c = st[--n];
        uint32_t np = cm_npar(u, c);
        for(uint32_t k = 0; k < np; k++) {
            uint32_t p = u->par[u->cm[c].par + k];
            if(u->cm[p].flags & C_UNINT) continue;
            u->cm[p].flags |= C_UNINT;
            if(u->cm[p].flags & C_PARSED) up_push_u32(&st, &n, &cap, p);
        }
    }
    free(st);
}

static bool queue_all_unint(const Up* u, const Heap* h) {
    for(size_t i = 0; i < h->n; i++)
        if(!(u->cm[h->v[i]].flags & C_UNINT)) return false;
    return true;
}

/* Returns the interesting commits, newest first (out, malloc'd). */
uint32_t* up_walk_commits(Up* u, size_t* nout) {
    Heap h = {0};
    uint32_t* out = NULL;
    size_t n = 0, cap = 0;
    *nout = 0;
    const uint8_t* hp = u->haves.p;
    for(size_t i = 0; i < u->nhaves && !u->failed; i++) {
        uint32_t ci = smmap_get(&u->cmap, hp + i * 20);
        if(ci == SM_NONE) {
            up_obj_unint(u, hp + i * 20); /* a tree, blob or tag the client has */
            continue;
        }
        if(u->cm[ci].flags & C_SEEN) {
            u->cm[ci].flags |= C_UNINT;
            continue;
        }
        u->cm[ci].flags |= C_UNINT | C_SEEN;
        heap_push(u, &h, ci);
    }
    for(size_t i = 0; i < u->nedges; i++) {
        uint32_t ci = u->edges[i];
        u->cm[ci].flags |= C_UNINT;
        if(!(u->cm[ci].flags & C_SEEN)) {
            u->cm[ci].flags |= C_SEEN;
            heap_push(u, &h, ci);
        }
    }
    for(size_t i = 0; i < u->nwants; i++) {
        uint32_t ci = u->wants[i];
        if(!(u->cm[ci].flags & C_SEEN)) {
            u->cm[ci].flags |= C_SEEN;
            heap_push(u, &h, ci);
        }
    }
    int slop = 5;
    unsigned beat = 0;
    while(h.n && !u->failed) {
        if(queue_all_unint(u, &h)) {
            if(--slop <= 0) break;
        } else {
            slop = 5;
        }
        uint32_t ci = heap_pop(u, &h);
        if(!up_cm_parse(u, ci)) break;
        if(u->cm[ci].flags & C_UNINT) mark_parents_unint(u, ci);
        else if(!up_push_u32(&out, &n, &cap, ci)) up_fail(u, "out of memory");
        uint32_t np = cm_npar(u, ci);
        for(uint32_t k = 0; k < np; k++) {
            uint32_t p = u->par[u->cm[ci].par + k];
            if(u->cm[p].flags & C_SEEN) continue;
            u->cm[p].flags |= C_SEEN;
            if(!up_cm_parse(u, p)) break;
            if(!heap_push(u, &h, p)) up_fail(u, "out of memory");
        }
        if(++beat % 512 == 0) hub_beat();
    }
    free(h.v);
    /* commits that turned out to be the client's after they were taken */
    size_t w = 0;
    for(size_t i = 0; i < n; i++)
        if(!(u->cm[out[i]].flags & C_UNINT)) out[w++] = out[i];
    *nout = w;
    return out;
}

/* ---------- negotiation ---------- */

/* Every want reaches a commit the client has (git's ok_to_give_up). */
bool up_ok_to_give_up(Up* u) {
    if(!u->nhaves) return false;
    uint32_t* st = NULL;
    size_t cap = 0;
    bool all = true;
    for(size_t i = 0; i < u->nwants && all && !u->failed; i++) {
        uint32_t w = u->wants[i];
        if(u->cm[w].flags & C_REACH) continue;
        u->stamp++;
        size_t n = 0;
        bool found = false;
        up_push_u32(&st, &n, &cap, w);
        u->cm[w].stamp = u->stamp;
        while(n && !found) {
            uint32_t c = st[--n];
            if(u->cm[c].flags & C_THEY_HAVE) {
                found = true;
                break;
            }
            if(!up_cm_parse(u, c)) break;
            uint32_t np = cm_npar(u, c);
            for(uint32_t k = 0; k < np; k++) {
                uint32_t p = u->par[u->cm[c].par + k];
                if(u->cm[p].stamp == u->stamp) continue;
                u->cm[p].stamp = u->stamp;
                if(!up_cm_parse(u, p)) break;
                if(u->have_oldest && u->cm[p].time < u->oldest_have) continue;
                up_push_u32(&st, &n, &cap, p);
            }
        }
        if(found)
            u->cm[w].flags |= C_REACH;
        else
            all = false;
    }
    free(st);
    return all && !u->failed;
}

/* A "have": 1 we have it too (common), 0 we do not. */
int up_got_have(Up* u, const uint8_t id[20]) {
    if(!sm_odb_has(u->odb, id)) return 0;
    int type = 0;
    if(sm_odb_type(u->odb, id, &type) != SM_OK) return 0;
    if(type == SM_COMMIT) {
        uint32_t ci = up_cm_get(u, id);
        if(ci == SM_NONE || !up_cm_parse(u, ci)) return 0;
        u->cm[ci].flags |= C_THEY_HAVE;
        if(!u->have_oldest || u->cm[ci].time < u->oldest_have) {
            u->oldest_have = u->cm[ci].time;
            u->have_oldest = true;
        }
        uint32_t np = cm_npar(u, ci);
        for(uint32_t k = 0; k < np; k++)
            u->cm[u->par[u->cm[ci].par + k]].flags |= C_THEY_HAVE;
    }
    smbuf_add(&u->haves, id, 20);
    u->nhaves++;
    return 1;
}

