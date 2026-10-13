/* Smart HTTP: git-upload-pack (clone and fetch), protocol v0, stateless RPC.
 *
 * Negotiation: multi_ack_detailed + no-done (also multi_ack and plain), shallow / deepen N /
 * deepen-relative / deepen-since / deepen-not like git's upload-pack.c. The pack holds every
 * object reachable from the wants and not from the common haves (and not beyond the shallow
 * boundary). Entries are reused from the existing packs as they are (raw copy of the compressed
 * bytes); a delta is reused only when its base goes into the same answer from the same pack
 * (OFS_DELTA when the base is already written and the client has ofs-delta, else REF_DELTA).
 * Everything else is sent whole, compressed with the core's deflate. The pack is streamed (side
 * band when asked), never held in RAM. */
#define _FILE_OFFSET_BITS 64
#include "upload.h"

#include "hub_deflate.h"

#include <furi.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void up_fail(Up* u, const char* fmt, ...) {
    if(u->failed) return;
    u->failed = true;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(u->err, sizeof u->err, fmt, ap);
    va_end(ap);
}

bool up_grow(void** p, size_t* cap, size_t need, size_t elem) {
    if(need <= *cap) return true;
    size_t nc = *cap ? *cap * 2 : 64;
    while(nc < need)
        nc *= 2;
    void* np = realloc(*p, nc * elem);
    if(!np) return false;
    *p = np;
    *cap = nc;
    return true;
}

bool up_push_u32(uint32_t** v, size_t* n, size_t* cap, uint32_t x) {
    if(!up_grow((void**)v, cap, *n + 1, sizeof(uint32_t))) return false;
    (*v)[(*n)++] = x;
    return true;
}

/* ---------- the request ---------- */

static bool collect(void* ctx, const uint8_t* d, size_t n) {
    SmBuf* b = ctx;
    if(b->n + n > REQ_MAX) return false;
    return smbuf_add(b, d, n);
}

static void parse_caps(Up* u, const char* caps) {
    for(const char* p = caps; *p;) {
        while(*p == ' ')
            p++;
        size_t n = strcspn(p, " ");
        if(!n) break;
#define CAP(s) (n == sizeof(s) - 1 && !strncmp(p, s, n))
        if(CAP("multi_ack_detailed"))
            u->multi_ack = 2;
        else if(CAP("multi_ack") && u->multi_ack < 1)
            u->multi_ack = 1;
        else if(CAP("no-done"))
            u->no_done = true;
        else if(CAP("side-band-64k"))
            u->o.band_max = 65515;
        else if(CAP("side-band") && !u->o.band_max)
            u->o.band_max = 995;
        else if(CAP("ofs-delta"))
            u->ofs = true;
        else if(CAP("no-progress"))
            u->no_progress = true;
        else if(CAP("include-tag"))
            u->include_tag = true;
        else if(CAP("deepen-relative"))
            u->deepen_relative = true;
#undef CAP
        p += n;
    }
}

/* Tags the client asked for directly go in; the walk starts at what they point to. */
static void resolve_wants(Up* u) {
    for(size_t i = 0; i + 20 <= u->want_ids.n && !u->failed; i += 20) {
        uint8_t cur[20];
        memcpy(cur, u->want_ids.p + i, 20);
        for(int guard = 0; guard < 16; guard++) {
            int type;
            uint8_t* d;
            size_t n;
            char hex[41];
            if(sm_odb_type(u->odb, cur, &type) != SM_OK) {
                sm_hex(cur, hex);
                up_fail(u, "upload-pack: not our ref %s", hex);
                return;
            }
            if(type == SM_COMMIT) {
                uint32_t ci = up_cm_get(u, cur);
                if(ci == SM_NONE) return;
                bool dup = false;
                for(size_t k = 0; k < u->nwants; k++)
                    dup |= u->wants[k] == ci;
                if(!dup) up_push_u32(&u->wants, &u->nwants, &u->capwants, ci);
                break;
            }
            if(type != SM_TAG) {
                uint8_t t = (uint8_t)type;
                smbuf_add(&u->extra_objs, &t, 1);
                smbuf_add(&u->extra_objs, cur, 20);
                break;
            }
            /* tag: remember it for the pack (type byte 4), go on with its object */
            uint8_t t = SM_TAG;
            smbuf_add(&u->extra_objs, &t, 1);
            smbuf_add(&u->extra_objs, cur, 20);
            if(sm_odb_read(u->odb, cur, &type, &d, &n) != SM_OK) {
                sm_hex(cur, hex);
                up_fail(u, "cannot read tag %s", hex);
                return;
            }
            bool ok = n > 47 && !strncmp((char*)d, "object ", 7) && sm_unhex((char*)d + 7, cur);
            free(d);
            if(!ok) {
                up_fail(u, "bad tag");
                return;
            }
        }
    }
}

static void up_free(Up* u) {
    smbuf_free(&u->want_ids);
    smbuf_free(&u->shallow_ids);
    smbuf_free(&u->dnot);
    smbuf_free(&u->haves);
    smbuf_free(&u->extra_objs);
    free(u->cm);
    smmap_free(&u->cmap);
    free(u->par);
    free(u->wants);
    free(u->edges);
    smmap_free(&u->objs);
    free(u->send);
    free(u->pbuf);
    if(u->defl) hub_deflate_free(u->defl);
    sm_odb_close(u->odb);
}

void sm_upload_pack(SmRepo* rp) {
    HubReq* r = rp->r;
    SmBuf req = {0};
    SmBody b;
    if(!sm_body_begin(&b, r)) {
        sm_body_end(&b);
        hub_send_error(r, 503, "low memory, try again");
        return;
    }
    bool ok = sm_body_pump(&b, collect, &req);
    sm_body_end(&b);
    if(!ok || req.bad) {
        smbuf_free(&req);
        hub_send_error(r, 400, "bad or too large request");
        return;
    }

    Up* u = calloc(1, sizeof(Up));
    if(!u) {
        smbuf_free(&req);
        hub_send_error(r, 503, "low memory, try again");
        return;
    }
    u->rp = rp;
    SmPktIn in = {req.p, req.n, 0};
    char* line = malloc(65536);
    size_t len;
    int k = 0;
    bool first = true, bad = false;
    while(line && (k = sm_pkt_next(&in, line, 65536, &len)) == 1) {
        uint8_t id[20];
        if(!strncmp(line, "want ", 5) && sm_unhex(line + 5, id)) {
            smbuf_add(&u->want_ids, id, 20);
            if(first && len > 45) parse_caps(u, line + 46);
            first = false;
        } else if(!strncmp(line, "shallow ", 8) && sm_unhex(line + 8, id)) {
            smbuf_add(&u->shallow_ids, id, 20);
        } else if(!strncmp(line, "deepen ", 7)) {
            long long d = atoll(line + 7);
            u->depth = d > INF_DEPTH ? INF_DEPTH : d < 0 ? 0 : (int)d;
            if(u->depth > 0) u->deepen = true;
        } else if(!strncmp(line, "deepen-since ", 13)) {
            u->since = atoll(line + 13);
            u->deepen = true;
        } else if(!strncmp(line, "deepen-not ", 11)) {
            smbuf_add(&u->dnot, line + 11, strlen(line + 11) + 1);
            u->deepen = true;
        } else if(!strncmp(line, "filter ", 7)) {
            /* not advertised: ignored */
        } else {
            bad = true;
        }
    }
    if(!line || k == -2 || bad || (k == -1 && u->want_ids.n)) {
        /* a request must close the want list with a flush */
        free(line);
        smbuf_free(&req);
        up_free(u);
        free(u);
        hub_send_error(r, 400, "bad upload-pack request");
        return;
    }
    if(!u->want_ids.n) { /* nothing wanted (or the probe of a big request) */
        free(line);
        smbuf_free(&req);
        up_free(u);
        free(u);
        SmOut o;
        if(sm_out_begin(&o, r, "application/x-git-upload-pack-result")) sm_out_end(&o);
        return;
    }

    size_t band = u->o.band_max;
    if(!sm_out_begin(&u->o, r, "application/x-git-upload-pack-result")) {
        free(line);
        smbuf_free(&req);
        up_free(u);
        free(u);
        return;
    }
    u->o.band_max = 0; /* the side band starts with the pack */
    u->odb = sm_odb_open(rp->repo);
    if(!u->odb) up_fail(u, "out of memory");
    if(!u->failed) resolve_wants(u);
    if(!u->failed) up_do_shallow(u);

    bool send = false;
    if(!u->failed && in.pos < in.n) {
        bool got_common = false, got_other = false, sent_ready = false;
        uint8_t last[20] = {0};
        char hex[41];
        for(;;) {
            k = sm_pkt_next(&in, line, 65536, &len);
            uint8_t id[20];
            if(k == 1 && !strncmp(line, "have ", 5) && sm_unhex(line + 5, id)) {
                if(up_got_have(u, id)) {
                    got_common = true;
                    memcpy(last, id, 20);
                    sm_hex(id, hex);
                    if(u->multi_ack == 2)
                        sm_out_pkt(&u->o, "ACK %s common\n", hex);
                    else if(u->multi_ack)
                        sm_out_pkt(&u->o, "ACK %s continue\n", hex);
                    else if(u->nhaves == 1)
                        sm_out_pkt(&u->o, "ACK %s\n", hex);
                } else {
                    got_other = true;
                    if(u->multi_ack && up_ok_to_give_up(u)) {
                        sm_hex(id, hex);
                        if(u->multi_ack == 2) {
                            sent_ready = true;
                            sm_out_pkt(&u->o, "ACK %s ready\n", hex);
                        } else {
                            sm_out_pkt(&u->o, "ACK %s continue\n", hex);
                        }
                    }
                }
                if(u->failed) break;
                continue;
            }
            if(k == 1 && !strcmp(line, "done")) {
                if(u->nhaves) {
                    if(u->multi_ack) {
                        sm_hex(last, hex);
                        sm_out_pkt(&u->o, "ACK %s\n", hex);
                    }
                } else {
                    sm_out_pkt(&u->o, "NAK\n");
                }
                send = true;
                break;
            }
            if(k == 0 || k == -1) { /* end of a round */
                if(u->multi_ack == 2 && got_common && !got_other && up_ok_to_give_up(u)) {
                    sent_ready = true;
                    sm_hex(last, hex);
                    sm_out_pkt(&u->o, "ACK %s ready\n", hex);
                }
                if(!u->nhaves || u->multi_ack) sm_out_pkt(&u->o, "NAK\n");
                if(u->no_done && sent_ready) {
                    sm_hex(last, hex);
                    sm_out_pkt(&u->o, "ACK %s\n", hex);
                    send = true;
                }
                break;
            }
            up_fail(u, "upload-pack: expected a have line");
            break;
        }
    }
    free(line);
    smbuf_free(&req);

    if(send && !u->failed) {
        u->o.band_max = band;
        size_t ncommits = 0;
        up_compute_objects(u, &ncommits);
        if(!u->failed) {
            if(!u->no_progress)
                sm_out_progress(&u->o, "Enumerating objects: %zu, done.\n", u->nsend);
            up_send_pack(u);
        }
        if(!u->failed && !u->no_progress)
            sm_out_progress(
                &u->o,
                "Total %zu (delta %u), reused %u (delta %u), pack-reused 0\n",
                u->nsend,
                u->deltas,
                u->reused,
                u->reused_delta);
        if(u->failed) {
            if(u->o.band_max) {
                char m[200];
                int n = snprintf(m, sizeof m, "error: %s\n", u->err);
                up_pw_flush(u);
                sm_out_band(&u->o, 3, m, (size_t)n);
            }
            FURI_LOG_E("smart", "upload-pack %s: %s", rp->name, u->err);
        }
        if(u->o.band_max) sm_out_flushpkt(&u->o);
    } else if(u->failed) {
        sm_out_pkt(&u->o, "ERR %s\n", u->err);
        FURI_LOG_E("smart", "upload-pack %s: %s", rp->name, u->err);
    }
    sm_out_end(&u->o);
    up_free(u);
    free(u);
}
