/* Smart HTTP (git-upload-pack, git-receive-pack), protocol v0 over stateless RPC.
 *
 *   GET  /git/<name>.git/info/refs?service=git-upload-pack|git-receive-pack   ref advertisement
 *   POST /git/<name>.git/git-upload-pack                                      clone, fetch
 *   POST /git/<name>.git/git-receive-pack                                     push
 *
 * Everything else (dumb GET routes, WebDAV) falls through to the core. A client that asks for
 * protocol v2 gets v0 (it falls back). The receive-pack discovery of a missing repo creates it,
 * like the core's dumb route (push to create). See smart.h for the files. */
#define _FILE_OFFSET_BITS 64
#include "port_hooks.h"
#include "smart.h"

#include "hub_git.h"

#include <furi.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>


#define UPLOAD_CAPS                                                                         \
    "multi_ack multi_ack_detailed no-done side-band side-band-64k ofs-delta shallow "       \
    "deepen-since deepen-not deepen-relative no-progress include-tag allow-tip-sha1-in-want " \
    "allow-reachable-sha1-in-want object-format=sha1 agent=" SM_AGENT
#define RECEIVE_CAPS                                                                     \
    "report-status delete-refs side-band-64k quiet atomic ofs-delta no-thin object-format=sha1 " \
    "agent=" SM_AGENT

#define SKELETON_CONFIG "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = true\n"

/* ---------- repo ---------- */

/* Push to create: the same skeleton as git_create in hub_git.c (HEAD refs/heads/main,
 * "Unnamed repository", the core's config, info/refs and objects/info/packs). */
static bool repo_create(SmRepo* rp) {
    HubReq* r = rp->r;
    static const char* const dirs[] = {
        "", "/refs", "/refs/heads", "/refs/tags", "/objects", "/objects/pack", "/objects/info", "/info"};
    bool ok = hub_git_mkroot(r->storage);
    char p[200];
    for(size_t i = 0; ok && i < COUNT_OF(dirs); i++) {
        snprintf(p, sizeof p, "%s%s", rp->base, dirs[i]);
        ok = storage_simply_mkdir(r->storage, p);
    }
    char hp[SM_PATH_MAX + 32];
    if(ok) {
        snprintf(hp, sizeof hp, "%s/HEAD", rp->repo);
        ok = sm_write_file(hp, "ref: refs/heads/main\n", 21);
    }
    if(ok) {
        snprintf(hp, sizeof hp, "%s/config", rp->repo);
        ok = sm_write_file(hp, SKELETON_CONFIG, sizeof(SKELETON_CONFIG) - 1);
    }
    if(ok) {
        snprintf(hp, sizeof hp, "%s/description", rp->repo);
        ok = sm_write_file(hp, "Unnamed repository\n", 19);
    }
    if(ok) ok = hub_git_changed(r, rp->name);
    if(!ok) storage_simply_remove_recursive(r->storage, rp->base);
    return ok;
}

static bool repo_exists(const SmRepo* rp) {
    struct stat st;
    return stat(rp->repo, &st) == 0 && S_ISDIR(st.st_mode);
}

/* Follow annotated tags to the first object that is not a tag. */
static bool peel(SmOdb* odb, const uint8_t id[20], uint8_t out[20]) {
    uint8_t cur[20];
    memcpy(cur, id, 20);
    for(int i = 0; i < 16; i++) {
        int type;
        uint8_t* d;
        size_t n;
        if(sm_odb_read(odb, cur, &type, &d, &n)) return false;
        if(type != SM_TAG) {
            free(d);
            memcpy(out, cur, 20);
            return true;
        }
        bool ok = n > 47 && !strncmp((char*)d, "object ", 7) && sm_unhex((char*)d + 7, cur);
        free(d);
        if(!ok) return false;
    }
    return false;
}

static void advertise(SmRepo* rp, bool upload) {
    HubReq* r = rp->r;
    const char* svc = upload ? "git-upload-pack" : "git-receive-pack";
    SmRefs refs;
    if(!sm_refs_load(rp->repo, &refs)) {
        sm_refs_free(&refs);
        hub_send_error(r, 503, "low memory or bad refs, try again");
        return;
    }
    char sym[256];
    uint8_t head[20];
    bool has_head = upload && sm_head(rp->repo, &refs, sym, sizeof sym, head);
    SmOdb* odb = NULL;
    if(upload) {
        for(size_t i = 0; i < refs.n && !odb; i++)
            if(!strncmp(refs.v[i].name, "refs/tags/", 10)) odb = sm_odb_open(rp->repo);
    }
    char ctype[64];
    snprintf(ctype, sizeof ctype, "application/x-%s-advertisement", svc);
    SmOut o;
    if(!sm_out_begin(&o, r, ctype)) {
        sm_odb_close(odb);
        sm_refs_free(&refs);
        return;
    }
    sm_out_pkt(&o, "# service=%s\n", svc);
    sm_out_flushpkt(&o);

    char caps[512];
    if(upload) {
        if(has_head && sym[0])
            snprintf(caps, sizeof caps, "%s symref=HEAD:%s", UPLOAD_CAPS, sym);
        else
            snprintf(caps, sizeof caps, "%s", UPLOAD_CAPS);
    } else {
        snprintf(caps, sizeof caps, "%s", RECEIVE_CAPS);
    }
    bool first = true;
    char hex[41];
    if(has_head) {
        sm_hex(head, hex);
        sm_out_pkt(&o, "%s HEAD%c%s\n", hex, 0, caps);
        first = false;
    }
    for(size_t i = 0; i < refs.n; i++) {
        sm_hex(refs.v[i].id, hex);
        if(first) {
            sm_out_pkt(&o, "%s %s%c%s\n", hex, refs.v[i].name, 0, caps);
            first = false;
        } else {
            sm_out_pkt(&o, "%s %s\n", hex, refs.v[i].name);
        }
        uint8_t pk[20];
        if(odb && !strncmp(refs.v[i].name, "refs/tags/", 10) && peel(odb, refs.v[i].id, pk) &&
           memcmp(pk, refs.v[i].id, 20) != 0) {
            sm_hex(pk, hex);
            sm_out_pkt(&o, "%s %s^{}\n", hex, refs.v[i].name);
        }
    }
    if(first) sm_out_pkt(&o, "%040d capabilities^{}%c%s\n", 0, 0, caps);
    sm_out_flushpkt(&o);
    sm_out_end(&o);
    sm_odb_close(odb);
    sm_refs_free(&refs);
}

/* ---------- route ---------- */

bool port_smart_route(HubReq* r) {
    if(strncmp(r->path, "/git/", 5) != 0) return false;
    bool get = !strcmp(r->method, "GET");
    bool post = !strcmp(r->method, "POST");
    if(!get && !post) return false;
    const char* p = r->path + 5;
    const char* slash = strchr(p, '/');
    if(!slash) return false;
    const char* rest = slash + 1;
    int svc; /* 1 advertise upload, 2 advertise receive, 3 upload-pack, 4 receive-pack */
    if(get && !strcmp(rest, "info/refs")) {
        char s[40];
        if(!hub_query_get(r->query, "service", s, sizeof s)) return false; /* dumb */
        if(!strcmp(s, "git-upload-pack"))
            svc = 1;
        else if(!strcmp(s, "git-receive-pack"))
            svc = 2;
        else {
            hub_send_error(r, 403, "unknown service");
            return true;
        }
    } else if(post && !strcmp(rest, "git-upload-pack")) {
        svc = 3;
    } else if(post && !strcmp(rest, "git-receive-pack")) {
        svc = 4;
    } else {
        return false;
    }

    SmRepo* rp = calloc(1, sizeof(SmRepo));
    if(!rp) {
        hub_send_error(r, 503, "low memory, try again");
        return true;
    }
    rp->r = r;
    size_t seg = (size_t)(slash - p);
    if(seg < 5 || seg - 4 >= sizeof rp->name || strncmp(slash - 4, ".git", 4) != 0) {
        hub_send_error(r, 404, "not found");
        free(rp);
        return true;
    }
    memcpy(rp->name, p, seg - 4);
    rp->name[seg - 4] = '\0';
    if(!hub_git_name_ok(rp->name)) {
        hub_send_error(r, 404, "not found");
        free(rp);
        return true;
    }
    snprintf(rp->base, sizeof rp->base, HUB_GIT_ROOT "/%s.git", rp->name);
    if(!port_storage_host_path(rp->base, rp->repo, sizeof rp->repo)) {
        hub_send_error(r, 500, "bad repository path");
        free(rp);
        return true;
    }
    hub_long_op = true;

    if(!repo_exists(rp)) {
        if(svc != 2) {
            hub_send_error(r, 404, "repository not found");
            free(rp);
            return true;
        }
        if(!repo_create(rp)) {
            hub_send_error(r, 500, "could not make the repository");
            free(rp);
            return true;
        }
    }
    switch(svc) {
    case 1:
    case 2:
        advertise(rp, svc == 1);
        break;
    case 3:
        hub_prog.kind = HUB_PG_PULL;
        sm_upload_pack(rp);
        break;
    default:
        sm_receive_pack(rp);
        break;
    }
    free(rp);
    return true;
}
