/* Smart HTTP: git-upload-pack: shallow, deepen N, deepen-relative, deepen-since, deepen-not. */
#define _FILE_OFFSET_BITS 64
#include "upload.h"

#include "hub_deflate.h"

#include <furi.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------- shallow ---------- */

/* git's get_shallow_commits: commits at distance depth - 1 from the heads become shallow. */
void up_shallow_by_depth(Up* u, const uint32_t* heads, size_t nheads, int depth, uint32_t** res, size_t* nres) {
    uint32_t* st = NULL;
    size_t sn = 0, scap = 0, rcap = 0;
    for(size_t i = 0; i < u->ncm; i++)
        u->cm[i].depth = DEPTH_UNSET;
    size_t hi = 0;
    uint32_t commit = SM_NONE;
    int32_t cur = 0;
    unsigned beat = 0;
    while(!u->failed) {
        if(commit == SM_NONE) {
            if(hi < nheads) {
                commit = heads[hi++];
                u->cm[commit].depth = 0;
                cur = 0;
            } else if(sn) {
                commit = st[--sn];
                cur = u->cm[commit].depth;
            } else {
                break;
            }
        }
        if(!up_cm_parse(u, commit)) break;
        cur++;
        if(depth != INF_DEPTH && cur >= depth) {
            u->cm[commit].flags |= C_SHALLOW;
            up_push_u32(res, nres, &rcap, commit);
            commit = SM_NONE;
            continue;
        }
        u->cm[commit].flags |= C_NOT_SH;
        uint32_t next = SM_NONE;
        Cm* c = &u->cm[commit];
        for(uint32_t k = 0; k < c->npar; k++) {
            uint32_t p = u->par[c->par + k];
            if(u->cm[p].depth != DEPTH_UNSET && u->cm[p].depth <= cur) continue;
            u->cm[p].depth = cur;
            if(k + 1 < c->npar)
                up_push_u32(&st, &sn, &scap, p);
            else
                next = p;
        }
        commit = next;
        if(next != SM_NONE) cur = u->cm[next].depth;
        if(++beat % 512 == 0) hub_beat();
    }
    free(st);
}

/* deepen-since / deepen-not (git's get_shallow_commits_by_rev_list) */
void up_shallow_by_list(Up* u, uint32_t** res, size_t* nres) {
    uint32_t* st = NULL;
    size_t sn = 0, scap = 0, rcap = 0;
    /* everything reachable from the deepen-not refs is excluded */
    SmRefs refs;
    bool have_refs = sm_refs_load(u->rp->repo, &refs);
    for(size_t off = 0; off < u->dnot.n && have_refs;) {
        const char* name = (const char*)u->dnot.p + off;
        off += strlen(name) + 1;
        const SmRef* r = sm_refs_find(&refs, name);
        char full[300];
        if(!r) {
            snprintf(full, sizeof full, "refs/heads/%s", name);
            r = sm_refs_find(&refs, full);
        }
        if(!r) {
            snprintf(full, sizeof full, "refs/tags/%s", name);
            r = sm_refs_find(&refs, full);
        }
        if(!r) continue;
        int type;
        uint8_t cid[20];
        memcpy(cid, r->id, 20);
        for(int g = 0; g < 16; g++) { /* annotated tags: to the commit */
            uint8_t* d;
            size_t n;
            if(sm_odb_type(u->odb, cid, &type) || type != SM_TAG) break;
            if(sm_odb_read(u->odb, cid, &type, &d, &n)) break;
            bool ok = n > 47 && !strncmp((char*)d, "object ", 7) && sm_unhex((char*)d + 7, cid);
            free(d);
            if(!ok) break;
        }
        if(sm_odb_type(u->odb, cid, &type) || type != SM_COMMIT) continue;
        uint32_t ci = up_cm_get(u, cid);
        if(ci == SM_NONE || (u->cm[ci].flags & C_DNOT)) continue;
        u->cm[ci].flags |= C_DNOT;
        up_push_u32(&st, &sn, &scap, ci);
        while(sn && !u->failed) {
            uint32_t c = st[--sn];
            if(!up_cm_parse(u, c)) break;
            for(uint32_t k = 0; k < u->cm[c].npar; k++) {
                uint32_t p = u->par[u->cm[c].par + k];
                if(u->cm[p].flags & C_DNOT) continue;
                u->cm[p].flags |= C_DNOT;
                up_push_u32(&st, &sn, &scap, p);
            }
        }
    }
    if(have_refs) sm_refs_free(&refs);
    /* included: reachable from the wants, not excluded, not older than since */
    uint32_t* incl = NULL;
    size_t ni = 0, icap = 0;
    for(size_t i = 0; i < u->nwants; i++) {
        uint32_t w = u->wants[i];
        if(u->cm[w].flags & C_INCL) continue;
        u->cm[w].flags |= C_INCL; /* a want is always included */
        up_push_u32(&st, &sn, &scap, w);
    }
    while(sn && !u->failed) {
        uint32_t c = st[--sn];
        if(!up_cm_parse(u, c)) break;
        up_push_u32(&incl, &ni, &icap, c);
        for(uint32_t k = 0; k < u->cm[c].npar; k++) {
            uint32_t p = u->par[u->cm[c].par + k];
            if(u->cm[p].flags & (C_INCL | C_DNOT)) continue;
            if(!up_cm_parse(u, p)) break;
            if(u->since && u->cm[p].time < u->since) continue;
            u->cm[p].flags |= C_INCL;
            up_push_u32(&st, &sn, &scap, p);
        }
    }
    for(size_t i = 0; i < ni; i++)
        u->cm[incl[i]].flags |= C_NOT_SH;
    for(size_t i = 0; i < ni; i++) {
        Cm* c = &u->cm[incl[i]];
        for(uint32_t k = 0; k < c->npar; k++) {
            if(!(u->cm[u->par[c->par + k]].flags & C_INCL)) {
                c->flags |= C_SHALLOW;
                up_push_u32(res, nres, &rcap, incl[i]);
                break;
            }
        }
    }
    for(size_t i = 0; i < *nres; i++)
        u->cm[(*res)[i]].flags &= ~C_NOT_SH;
    free(incl);
    free(st);
}

/* The shallow section of the answer, then the grafts for the walk. */
void up_do_shallow(Up* u) {
    uint32_t* client = NULL;
    size_t nclient = 0, ccap = 0;
    for(size_t i = 0; i + 20 <= u->shallow_ids.n; i += 20) {
        const uint8_t* id = u->shallow_ids.p + i;
        int type;
        if(sm_odb_type(u->odb, id, &type) != SM_OK) continue; /* unknown: ignored, like git */
        if(type != SM_COMMIT) {
            char hex[41];
            sm_hex(id, hex);
            up_fail(u, "invalid shallow object %s", hex);
            break;
        }
        uint32_t ci = up_cm_get(u, id);
        if(ci == SM_NONE || (u->cm[ci].flags & C_CLIENT_SH)) continue;
        u->cm[ci].flags |= C_CLIENT_SH;
        up_push_u32(&client, &nclient, &ccap, ci);
    }
    if(u->failed) {
        free(client);
        return;
    }
    if(u->deepen) {
        uint32_t* res = NULL;
        size_t nres = 0;
        if(u->depth > 0 && (u->since || u->dnot.n)) {
            up_fail(u, "deepen and deepen-since (or deepen-not) cannot be used together");
        } else if(u->depth >= INF_DEPTH) {
            for(size_t i = 0; i < nclient; i++)
                u->cm[client[i]].flags |= C_NOT_SH;
        } else if(u->depth > 0 && u->deepen_relative) {
            up_shallow_by_depth(u, client, nclient, u->depth + 1, &res, &nres);
        } else if(u->depth > 0) {
            up_shallow_by_depth(u, u->wants, u->nwants, u->depth, &res, &nres);
        } else {
            up_shallow_by_list(u, &res, &nres);
        }
        char hex[41];
        for(size_t i = 0; i < nres && !u->failed; i++) {
            Cm* c = &u->cm[res[i]];
            if(c->flags & (C_CLIENT_SH | C_NOT_SH)) continue;
            if(c->flags & C_GRAFT) continue; /* listed twice */
            sm_hex(c->id, hex);
            sm_out_pkt(&u->o, "shallow %s\n", hex);
            c->flags |= C_GRAFT;
        }
        for(size_t i = 0; i < nclient && !u->failed; i++) {
            uint32_t ci = client[i];
            if(!(u->cm[ci].flags & C_NOT_SH)) continue;
            sm_hex(u->cm[ci].id, hex);
            sm_out_pkt(&u->o, "unshallow %s\n", hex);
            u->cm[ci].flags &= ~C_CLIENT_SH;
            if(!up_cm_parse(u, ci)) break;
            for(uint32_t k = 0; k < u->cm[ci].npar; k++)
                up_push_u32(&u->wants, &u->nwants, &u->capwants, u->par[u->cm[ci].par + k]);
            up_push_u32(&u->edges, &u->nedges, &u->capedges, ci);
        }
        free(res);
        if(!u->failed) sm_out_flushpkt(&u->o);
    }
    /* the walk follows the client's view of the history */
    for(size_t i = 0; i < nclient; i++)
        u->cm[client[i]].flags |= C_GRAFT;
    free(client);
}

