/* Smart HTTP: git-receive-pack (push), protocol v0, stateless RPC.
 *
 * Request: commands "old new ref" (the first with "\0caps"), flush, then the pack (absent when
 * every command is a delete). The pack is spooled to objects/pack/tmp_pack_rp_* (the core's
 * hub_git_cleanup_temp removes such leftovers), its trailer checked, then indexed (index_pack.c)
 * and checked for connectivity. Only when a command will be applied are the pack and its .idx
 * renamed to pack-<sha>.pack/.idx (pack first). Refs are written like the core does it (loose
 * file through ".part" + rename; a delete also drops the packed-refs line), then info/refs and
 * objects/info/packs are rebuilt and the idle packer is told (hub_git_changed). */
#define _FILE_OFFSET_BITS 64
#include "smart.h"
#include "index_pack.h"

#include "hub_git.h"

#include <furi.h>
#include <furi_hal.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define CMDS_MAX 4096

typedef struct {
    uint8_t old[20], nw[20];
    char ref[256];
    const char* ng; /* NULL: ok */
    bool done; /* applied */
} Cmd;

typedef struct {
    SmRepo* rp;
    /* pkt-lines of the command list */
    char lenb[4];
    size_t lenn;
    char* pkt;
    size_t plen, pgot;
    bool in_pkt, cmds_done, bad;
    Cmd* cmds;
    size_t ncmd;
    bool report, quiet, atomic, sideband;
    bool need_pack;
    /* pack spool */
    int fd;
    char tmp[SM_PATH_MAX + 80];
    uint64_t bytes, since_check;
    HubSha1 sha;
    uint8_t tail[20];
    size_t tailn;
    bool io_err, full;
} Rp;

static void parse_cmd(Rp* p, const char* line, size_t len) {
    if(len >= 8 && !strncmp(line, "shallow ", 8)) return; /* the connectivity check decides */
    if(len < 83 || line[40] != ' ' || line[81] != ' ' || p->ncmd >= CMDS_MAX) {
        p->bad = true;
        return;
    }
    Cmd* c = &p->cmds[p->ncmd];
    memset(c, 0, sizeof(*c));
    if(!sm_unhex(line, c->old) || !sm_unhex(line + 41, c->nw)) {
        p->bad = true;
        return;
    }
    const char* ref = line + 82;
    size_t rl = strnlen(ref, len - 82);
    if(rl >= sizeof c->ref) rl = sizeof c->ref - 1;
    memcpy(c->ref, ref, rl);
    c->ref[rl] = '\0';
    if(p->ncmd == 0 && 82 + rl < len) {
        const char* caps = ref + rl + 1;
        p->report = strstr(caps, "report-status") != NULL;
        p->quiet = strstr(caps, "quiet") != NULL;
        p->atomic = strstr(caps, "atomic") != NULL;
        p->sideband = strstr(caps, "side-band-64k") != NULL;
    }
    if(!sm_is_zero(c->nw)) p->need_pack = true;
    p->ncmd++;
}

static bool spool(Rp* p, const uint8_t* d, size_t n) {
    /* the pack SHA-1 covers everything but the last 20 bytes: hold those back */
    uint8_t tmp[40];
    if(n >= 20) {
        hub_sha1_update(&p->sha, p->tail, p->tailn);
        hub_sha1_update(&p->sha, d, n - 20);
        memcpy(p->tail, d + n - 20, 20);
        p->tailn = 20;
    } else {
        memcpy(tmp, p->tail, p->tailn);
        memcpy(tmp + p->tailn, d, n);
        size_t t = p->tailn + n;
        size_t h = t > 20 ? t - 20 : 0;
        hub_sha1_update(&p->sha, tmp, h);
        memcpy(p->tail, tmp + h, t - h);
        p->tailn = t - h;
    }
    while(n && !p->io_err) {
        ssize_t k = write(p->fd, d, n);
        if(k < 0 && errno == EINTR) continue;
        if(k <= 0) {
            p->io_err = true;
            break;
        }
        d += k;
        n -= (size_t)k;
        p->bytes += (uint64_t)k;
        p->since_check += (uint64_t)k;
    }
    if(p->since_check >= 32u * 1024 * 1024) {
        p->since_check = 0;
        if(!hub_sd_free_ok(p->rp->r->storage, 0)) p->full = p->io_err = true;
    }
    return !p->io_err;
}

static bool feed(void* ctx, const uint8_t* d, size_t n) {
    Rp* p = ctx;
    while(n && !p->bad) {
        if(p->cmds_done) {
            if(!p->need_pack) return true; /* nothing should come: ignored */
            if(p->fd < 0) {
                int r = (int)(furi_hal_random_get() & 0xffffff);
                snprintf(p->tmp, sizeof p->tmp, "%s/objects/pack/tmp_pack_rp_%06x", p->rp->repo, r);
                p->fd = open(p->tmp, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
                if(p->fd < 0) {
                    p->io_err = true;
                    return false;
                }
                hub_sha1_init(&p->sha);
            }
            return spool(p, d, n);
        }
        if(!p->in_pkt) {
            p->lenb[p->lenn++] = (char)*d++;
            n--;
            if(p->lenn < 4) continue;
            p->lenn = 0;
            char hx[5] = {p->lenb[0], p->lenb[1], p->lenb[2], p->lenb[3], 0};
            char* e;
            unsigned long l = strtoul(hx, &e, 16);
            if(*e) {
                p->bad = true;
                break;
            }
            if(l == 0) {
                p->cmds_done = true;
                continue;
            }
            if(l < 5 || l > 65520) {
                p->bad = true;
                break;
            }
            p->plen = l - 4;
            p->pgot = 0;
            p->in_pkt = true;
            continue;
        }
        size_t k = p->plen - p->pgot < n ? p->plen - p->pgot : n;
        memcpy(p->pkt + p->pgot, d, k);
        p->pgot += k;
        d += k;
        n -= k;
        if(p->pgot == p->plen) {
            p->in_pkt = false;
            size_t l = p->plen;
            if(l && p->pkt[l - 1] == '\n') l--;
            p->pkt[l] = '\0';
            parse_cmd(p, p->pkt, l);
        }
    }
    return !p->bad;
}

/* ---------- refs ---------- */

static void prune_dirs(const SmRepo* rp, const char* ref) {
    char path[SM_PATH_MAX + 300];
    size_t bl = strlen(rp->repo);
    snprintf(path, sizeof path, "%s/%s", rp->repo, ref);
    char* slash;
    while((slash = strrchr(path, '/')) != NULL) {
        *slash = '\0';
        if(!strcmp(path + bl, "/refs/heads") || !strcmp(path + bl, "/refs/tags")) break;
        if(rmdir(path) != 0) break; /* not empty */
    }
}

static bool ref_set(SmRepo* rp, const char* ref, const uint8_t id[20]) {
    char path[SM_PATH_MAX + 300];
    snprintf(path, sizeof path, "%s/%s", rp->repo, ref);
    if(sm_is_zero(id)) {
        if(unlink(path) != 0 && errno != ENOENT) return false;
        bool ok = hub_git_drop_packed(rp->r, rp->name, ref);
        prune_dirs(rp, ref);
        return ok;
    }
    char v[42];
    sm_hex(id, v);
    v[40] = '\n';
    return sm_mkdirs(path, strlen(rp->repo) + 1) && sm_write_file(path, v, 41);
}

/* creating a/b next to a, or a next to a/b, is a file/folder clash */
static bool ref_clash(const SmRefs* refs, const char* ref) {
    size_t rl = strlen(ref);
    for(size_t i = 0; i < refs->n; i++) {
        const char* n = refs->v[i].name;
        size_t nl = strlen(n);
        if(nl > rl && !strncmp(n, ref, rl) && n[rl] == '/') return true;
        if(rl > nl && !strncmp(ref, n, nl) && ref[nl] == '/') return true;
    }
    return false;
}

/* ---------- the request ---------- */

static void check_cmds(Rp* p, IpPack* ip, SmOdb* odb, const char* unpack_err) {
    SmRefs refs;
    bool have_refs = sm_refs_load(p->rp->repo, &refs);
    uint8_t missing[20];
    bool connected = !ip || ip_connected(ip, odb, missing);
    for(size_t i = 0; i < p->ncmd; i++) {
        Cmd* c = &p->cmds[i];
        uint8_t cur[20];
        bool exists = sm_ref_read(p->rp->repo, c->ref, cur);
        if(!exists) memset(cur, 0, 20);
        bool del = sm_is_zero(c->nw);
        int type = 0;
        if(unpack_err)
            c->ng = "unpacker error";
        else if(!sm_ref_ok(c->ref))
            c->ng = "funny refname";
        else if(del && sm_is_zero(c->old) && !exists)
            c->ng = "nothing to delete";
        else if(memcmp(cur, c->old, 20) != 0)
            c->ng = "failed to lock (the ref changed: fetch first)";
        else if(!del && !(ip && (type = ip_type(ip, c->nw))) &&
                (!sm_odb_has(odb, c->nw) || sm_odb_type(odb, c->nw, &type) != SM_OK))
            c->ng = "missing necessary objects";
        else if(!del && !connected)
            c->ng = "missing necessary objects";
        else if(!del && !strncmp(c->ref, "refs/heads/", 11) && type != SM_COMMIT)
            c->ng = "refusing a non-commit object for a branch";
        else if(!exists && !del && have_refs && ref_clash(&refs, c->ref))
            c->ng = "failed to lock (a ref with that name as folder or file exists)";
    }
    if(have_refs) sm_refs_free(&refs);
    if(p->atomic) {
        bool any = false;
        for(size_t i = 0; i < p->ncmd; i++)
            any |= p->cmds[i].ng != NULL;
        for(size_t i = 0; any && i < p->ncmd; i++)
            if(!p->cmds[i].ng) p->cmds[i].ng = "atomic push failed";
    }
}

/* pack + idx into place: tmp names, then pack-<sha>.pack and .idx (pack first) */
static bool store_pack(Rp* p, IpPack* ip) {
    char idx_tmp[SM_PATH_MAX + 100], fin[SM_PATH_MAX + 100], hex[41];
    snprintf(idx_tmp, sizeof idx_tmp, "%s.idx", p->tmp);
    if(fsync(p->fd) != 0 || !ip_write_idx(ip, idx_tmp)) {
        unlink(idx_tmp);
        return false;
    }
    uint8_t sha[20];
    ip_trailer(ip, sha);
    sm_hex(sha, hex);
    snprintf(fin, sizeof fin, "%s/objects/pack/pack-%s.pack", p->rp->repo, hex);
    struct stat st;
    if(stat(fin, &st) == 0) { /* the same pack is there already */
        unlink(idx_tmp);
        return true;
    }
    bool ok = rename(p->tmp, fin) == 0;
    if(ok) {
        snprintf(fin, sizeof fin, "%s/objects/pack/pack-%s.idx", p->rp->repo, hex);
        ok = rename(idx_tmp, fin) == 0;
    }
    if(!ok) unlink(idx_tmp);
    return ok;
}

static void apply_cmds(Rp* p) {
    for(size_t i = 0; i < p->ncmd; i++) {
        Cmd* c = &p->cmds[i];
        if(c->ng) continue;
        if(ref_set(p->rp, c->ref, c->nw)) {
            c->done = true;
            continue;
        }
        c->ng = "failed to write the ref";
        if(!p->atomic) continue;
        for(size_t k = 0; k < p->ncmd; k++) { /* roll back */
            Cmd* d = &p->cmds[k];
            if(d->done) ref_set(p->rp, d->ref, d->old);
            d->done = false;
            if(!d->ng) d->ng = "atomic push failed";
        }
        break;
    }
}

static void pkt_to(SmBuf* b, const char* s) {
    char h[20];
    snprintf(h, sizeof h, "%04zx", strlen(s) + 4);
    smbuf_add(b, h, 4);
    smbuf_add(b, s, strlen(s));
}

void sm_receive_pack(SmRepo* rp) {
    HubReq* r = rp->r;
    Rp* p = calloc(1, sizeof(Rp));
    if(p) p->pkt = malloc(65536);
    if(p) p->cmds = malloc(sizeof(Cmd) * CMDS_MAX);
    if(!p || !p->pkt || !p->cmds) {
        if(p) free(p->pkt), free(p->cmds);
        free(p);
        hub_send_error(r, 503, "low memory, try again");
        return;
    }
    p->rp = rp;
    p->fd = -1;
    SmBody b;
    bool ok = sm_body_begin(&b, r) && sm_body_pump(&b, feed, p);
    sm_body_end(&b);
    if(!ok || p->bad || (p->ncmd && !p->cmds_done)) {
        if(p->fd >= 0) close(p->fd), unlink(p->tmp);
        hub_send_error(r, p->full ? 507 : 400, p->full ? "storage full" : "bad or cut push request");
        free(p->pkt), free(p->cmds), free(p);
        return;
    }
    hub_git_touch(rp->name);
    SmOut o;
    if(!sm_out_begin(&o, r, "application/x-git-receive-pack-result")) {
        if(p->fd >= 0) close(p->fd), unlink(p->tmp);
        free(p->pkt), free(p->cmds), free(p);
        return;
    }
    if(p->sideband) o.band_max = 65515;
    if(!p->ncmd) { /* the probe of a big request, or nothing to do */
        sm_out_end(&o);
        free(p->pkt), free(p->cmds), free(p);
        return;
    }
    SmOdb* odb = sm_odb_open(rp->repo);
    IpPack* ip = NULL;
    char err[160] = "";
    const char* unpack_err = NULL;
    if(!odb) {
        unpack_err = "out of memory";
    } else if(p->need_pack) {
        uint8_t sum[20];
        hub_sha1_final(&p->sha, sum);
        if(p->fd < 0 || p->bytes < 32 || p->tailn != 20 || memcmp(sum, p->tail, 20) != 0) {
            unpack_err = "bad pack checksum";
        } else {
            if(!p->quiet) sm_out_progress(&o, "Indexing %llu bytes\n", (unsigned long long)p->bytes);
            sm_out_push(&o);
            ip = ip_index(p->fd, odb, err, sizeof err);
            if(!ip) unpack_err = err;
        }
    }
    check_cmds(p, ip, odb, unpack_err);
    bool apply = false;
    for(size_t i = 0; i < p->ncmd; i++)
        apply |= !p->cmds[i].ng;
    if(apply && ip && ip_count(ip) && !store_pack(p, ip)) {
        unpack_err = "cannot store the pack";
        for(size_t i = 0; i < p->ncmd; i++)
            if(!p->cmds[i].ng) p->cmds[i].ng = "unpacker error";
        apply = false;
    } else if(ip && ip_count(ip) && apply) {
        p->tmp[0] = '\0'; /* renamed */
    }
    if(p->fd >= 0) close(p->fd);
    if(p->fd >= 0 && p->tmp[0]) unlink(p->tmp);
    if(apply) apply_cmds(p);
    ip_free(ip);
    sm_odb_close(odb);
    hub_git_changed(r, rp->name); /* info/refs, objects/info/packs, the packer and graph marks */
    hub_git_mark_clean(rp->name);

    if(p->report) {
        SmBuf rep = {0};
        char line[400];
        snprintf(line, sizeof line, "unpack %s\n", unpack_err ? unpack_err : "ok");
        pkt_to(&rep, line);
        for(size_t i = 0; i < p->ncmd; i++) {
            Cmd* c = &p->cmds[i];
            if(c->ng)
                snprintf(line, sizeof line, "ng %s %s\n", c->ref, c->ng);
            else
                snprintf(line, sizeof line, "ok %s\n", c->ref);
            pkt_to(&rep, line);
        }
        smbuf_add(&rep, "0000", 4);
        if(o.band_max)
            sm_out_band(&o, 1, rep.p, rep.n);
        else
            sm_out_raw(&o, rep.p, rep.n);
        smbuf_free(&rep);
    }
    if(o.band_max) sm_out_flushpkt(&o);
    sm_out_end(&o);
    if(unpack_err) FURI_LOG_E("smart", "receive-pack %s: %s", rp->name, unpack_err);
    free(p->pkt), free(p->cmds), free(p);
}
