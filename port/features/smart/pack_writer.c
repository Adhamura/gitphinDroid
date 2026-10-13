/* Smart HTTP: git-upload-pack: the object list and the outgoing pack (reused entries, deltas, deflate). */
#define _FILE_OFFSET_BITS 64
#include "upload.h"

#include "hub_deflate.h"

#include <furi.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------- pack writer ---------- */

#define PBUF 65515

void up_pw_flush(Up* u) {
    if(!u->pn) return;
    if(u->o.band_max)
        sm_out_band(&u->o, 1, u->pbuf, u->pn);
    else
        sm_out_raw(&u->o, u->pbuf, u->pn);
    u->pn = 0;
}

static bool pw_write(void* ctx, const uint8_t* d, size_t n) {
    Up* u = ctx;
    hub_sha1_update(&u->sha, d, n);
    u->written += n;
    hub_prog.bytes += (uint32_t)n;
    while(n) {
        size_t k = PBUF - u->pn < n ? PBUF - u->pn : n;
        memcpy(u->pbuf + u->pn, d, k);
        u->pn += k;
        d += k;
        n -= k;
        if(u->pn == PBUF) up_pw_flush(u);
    }
    return u->o.ok;
}

static bool defl_out(void* ctx, const uint8_t* d, size_t n) {
    return pw_write(ctx, d, n);
}

static bool defl_feed(void* ctx, const uint8_t* d, size_t n) {
    Up* u = ctx;
    hub_beat();
    return hub_deflate_write(u->defl, d, n);
}

static bool write_whole(Up* u, int type, const uint8_t* data, size_t len) {
    uint8_t h[16];
    size_t hl = sm_entry_hdr_put(h, type, len);
    pw_write(u, h, hl);
    hub_deflate_reset(u->defl, defl_out, u);
    return hub_deflate_write(u->defl, data, len) && hub_deflate_finish(u->defl);
}

static bool write_obj(Up* u, Obj* ob) {
    if(ob->pack >= 0) {
        SmEnt e;
        if(sm_odb_entry(u->odb, ob->pack, ob->off, &e) != SM_OK) return false;
        uint64_t end = sm_odb_entry_end(u->odb, ob->pack, ob->off);
        if(!end || end <= ob->off + e.hdr_len) return false;
        if(e.type >= SM_COMMIT && e.type <= SM_TAG) {
            u->reused++;
            return sm_odb_copy(u->odb, ob->pack, ob->off, end, pw_write, u);
        }
        /* a delta: reuse it when its base goes out in this pack, from the same pack */
        uint32_t bi = SM_NONE;
        if(e.type == SM_OFS_DELTA) {
            uint8_t bid[20];
            if(sm_odb_id_at(u->odb, ob->pack, e.base_off, bid)) {
                bi = smmap_get(&u->objs, bid);
                if(bi != SM_NONE && bi != OBJ_UNINT &&
                   (u->send[bi].pack != ob->pack || u->send[bi].off != e.base_off))
                    bi = SM_NONE;
            }
        } else {
            bi = smmap_get(&u->objs, e.base_id);
            if(bi != SM_NONE && bi != OBJ_UNINT && u->send[bi].pack != ob->pack) bi = SM_NONE;
        }
        if(bi != SM_NONE && bi != OBJ_UNINT) {
            Obj* base = &u->send[bi];
            uint8_t h[48];
            size_t hl;
            if(u->ofs && base->written) {
                hl = sm_entry_hdr_put(h, SM_OFS_DELTA, e.size);
                hl += sm_ofs_put(h + hl, ob->out_off - base->out_off);
            } else {
                hl = sm_entry_hdr_put(h, SM_REF_DELTA, e.size);
                memcpy(h + hl, base->id, 20);
                hl += 20;
            }
            pw_write(u, h, hl);
            u->reused++;
            u->reused_delta++;
            u->deltas++;
            return sm_odb_copy(u->odb, ob->pack, ob->off + e.hdr_len, end, pw_write, u);
        }
        int type;
        uint8_t* d;
        size_t len;
        if(sm_odb_read_at(u->odb, ob->pack, ob->off, &type, &d, &len) != SM_OK) return false;
        bool ok = write_whole(u, type, d, len);
        free(d);
        return ok;
    }
    int type;
    uint64_t size;
    if(sm_odb_loose_info(u->odb, ob->id, &type, &size) != SM_OK) return false;
    uint8_t h[16];
    size_t hl = sm_entry_hdr_put(h, type, size);
    pw_write(u, h, hl);
    hub_deflate_reset(u->defl, defl_out, u);
    if(sm_odb_loose_stream(u->odb, ob->id, defl_feed, u) != SM_OK) return false;
    return hub_deflate_finish(u->defl);
}

static int obj_cmp(const void* a, const void* b) {
    const Obj* x = a;
    const Obj* y = b;
    if(x->pack != y->pack) return x->pack < y->pack ? -1 : 1;
    if(x->off != y->off) return x->off < y->off ? -1 : 1;
    return 0;
}

void up_send_pack(Up* u) {
    unsigned beat = 0;
    for(size_t i = 0; i < u->nsend; i++) {
        int p;
        uint64_t off;
        if(sm_odb_find(u->odb, u->send[i].id, &p, &off)) {
            u->send[i].pack = p;
            u->send[i].off = off;
        }
        if(++beat % 4096 == 0) hub_beat();
    }
    qsort(u->send, u->nsend, sizeof(Obj), obj_cmp);
    /* the map points at the new places */
    for(size_t i = 0; i < u->nsend; i++)
        smmap_put(&u->objs, u->send[i].id, (uint32_t)i);

    u->pbuf = malloc(PBUF);
    u->defl = hub_deflate_new(defl_out, u);
    if(!u->pbuf || !u->defl) {
        up_fail(u, "out of memory");
        return;
    }
    hub_sha1_init(&u->sha);
    uint8_t h[12] = {'P', 'A', 'C', 'K', 0, 0, 0, 2};
    uint32_t n = (uint32_t)u->nsend;
    h[8] = (uint8_t)(n >> 24);
    h[9] = (uint8_t)(n >> 16);
    h[10] = (uint8_t)(n >> 8);
    h[11] = (uint8_t)n;
    pw_write(u, h, 12);
    hub_prog.total = 0;
    for(size_t i = 0; i < u->nsend && u->o.ok; i++) {
        Obj* ob = &u->send[i];
        ob->out_off = u->written;
        if(!write_obj(u, ob)) {
            char hex[41];
            sm_hex(ob->id, hex);
            up_fail(u, "cannot read object %s", hex);
            return;
        }
        ob->written = true;
        hub_prog.objects++;
    }
    uint8_t tr[20];
    hub_sha1_final(&u->sha, tr);
    /* the trailer itself is not hashed: it goes around pw_write */
    u->written += 20;
    if(u->pn + 20 > PBUF) up_pw_flush(u);
    memcpy(u->pbuf + u->pn, tr, 20);
    u->pn += 20;
    up_pw_flush(u);
}

/* include-tag: annotated tags whose object goes into the pack go in too. */
static void up_include_tags(Up* u) {
    SmRefs refs;
    if(!sm_refs_load(u->rp->repo, &refs)) {
        sm_refs_free(&refs);
        return;
    }
    bool more = true;
    for(int round = 0; more && round < 8 && !u->failed; round++) {
        more = false;
        for(size_t i = 0; i < refs.n && !u->failed; i++) {
            if(strncmp(refs.v[i].name, "refs/tags/", 10) != 0) continue;
            if(smmap_get(&u->objs, refs.v[i].id) != SM_NONE) continue;
            int type;
            uint8_t* d;
            size_t n;
            uint8_t target[20];
            if(sm_odb_type(u->odb, refs.v[i].id, &type) || type != SM_TAG) continue;
            if(sm_odb_read(u->odb, refs.v[i].id, &type, &d, &n) != SM_OK) continue;
            bool ok = n > 47 && !strncmp((char*)d, "object ", 7) && sm_unhex((char*)d + 7, target);
            free(d);
            if(!ok) continue;
            /* follow a chain of tags to the first object that is in the pack */
            uint8_t cur[20];
            memcpy(cur, target, 20);
            bool in = false;
            for(int g = 0; g < 16; g++) {
                uint32_t v = smmap_get(&u->objs, cur);
                if(v != SM_NONE && v != OBJ_UNINT) {
                    in = true;
                    break;
                }
                if(sm_odb_type(u->odb, cur, &type) || type != SM_TAG) break;
                if(sm_odb_read(u->odb, cur, &type, &d, &n) != SM_OK) break;
                ok = n > 47 && !strncmp((char*)d, "object ", 7) && sm_unhex((char*)d + 7, cur);
                free(d);
                if(!ok) break;
            }
            if(in && up_obj_add(u, refs.v[i].id, SM_TAG) != SM_NONE) more = true;
        }
    }
    sm_refs_free(&refs);
}

void up_compute_objects(Up* u, size_t* ncommits) {
    size_t nout = 0;
    uint32_t* out = up_walk_commits(u, &nout);
    *ncommits = nout;
    /* edges: trees of the client's commits right below ours */
    for(size_t i = 0; i < nout && !u->failed; i++) {
        uint32_t ci = out[i];
        uint32_t np = cm_npar(u, ci);
        for(uint32_t k = 0; k < np && !u->failed; k++) {
            uint32_t p = u->par[u->cm[ci].par + k];
            if(!(u->cm[p].flags & C_UNINT) || !up_cm_parse(u, p)) continue;
            up_mark_tree_unint(u, u->cm[p].tree);
        }
    }
    for(size_t i = 0; i < u->nedges && !u->failed; i++)
        if(up_cm_parse(u, u->edges[i])) up_mark_tree_unint(u, u->cm[u->edges[i]].tree);
    for(size_t i = 0; i < nout && !u->failed; i++)
        up_obj_add(u, u->cm[out[i]].id, SM_COMMIT);
    for(size_t i = 0; i + 21 <= u->extra_objs.n && !u->failed; i += 21) {
        uint8_t t = u->extra_objs.p[i];
        const uint8_t* id = u->extra_objs.p + i + 1;
        if(t == SM_TREE)
            up_add_tree(u, id);
        else
            up_obj_add(u, id, t);
    }
    for(size_t i = 0; i < nout && !u->failed; i++) {
        up_add_tree(u, u->cm[out[i]].tree);
        if(i % 64 == 0) hub_beat();
    }
    free(out);
    if(u->include_tag && !u->failed) up_include_tags(u);
}

