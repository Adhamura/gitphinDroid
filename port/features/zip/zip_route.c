/* Download as ZIP:
 *   GET /git/<name>.git/archive/<ref>.zip
 *   GET /api/git/archive?name=X&ref=Y[&path=sub/dir]
 *   GET /pc/zip?name=X&ref=Y[&path=sub/dir]   (the Gitphin 1.2 page button; same handler)
 * The archive is streamed as chunked HTTP (chunks of 16 KB). No file and no archive is held in
 * RAM. Entries use a data descriptor (bit 3), UTF-8 names, raw deflate or store, unix modes, and
 * ZIP64 when a size, an offset or the entry count needs it. The central directory records are
 * spooled to <repo>/hub/tmp/zipcd (or kept in RAM if the file cannot be made). Nothing recurses:
 * folders go through an explicit stack. With -DPORT_ZIP64_TEST the env var GITPHIN_ZIP64_TEST=<n>
 * lowers the ZIP64 limits to n bytes (and 20 entries). */
#include "zip_int.h"

/* ---------- helpers ---------- */

static int hexv(int c) {
    if(c >= '0' && c <= '9') return c - '0';
    if(c >= 'a' && c <= 'f') return c - 'a' + 10;
    if(c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool pct_decode(const char* in, size_t n, char* out, size_t cap) {
    size_t o = 0;
    for(size_t i = 0; i < n; i++) {
        int c = (unsigned char)in[i];
        if(c == '%') {
            if(i + 2 >= n) return false;
            int a = hexv(in[i + 1]), b = hexv(in[i + 2]);
            if(a < 0 || b < 0) return false;
            c = a * 16 + b;
            i += 2;
        }
        if(o + 1 >= cap) return false;
        out[o++] = (char)c;
    }
    out[o] = 0;
    return true;
}

static bool ref_ok(const char* ref) {
    size_t n = strlen(ref);
    if(n == 0 || n > HUB_GIT_REF_MAX || ref[0] == '/' || strstr(ref, "..") || strstr(ref, "//")) {
        return false;
    }
    for(size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)ref[i];
        if(c < 0x20 || c == 0x7f || c == '\\') return false;
    }
    return true;
}

/* Normalise a sub path in place: no leading or trailing "/", no "." or ".." or empty parts. */
static bool subpath_ok(char* p) {
    size_t n = strlen(p);
    while(n && p[n - 1] == '/') p[--n] = 0;
    char* s = p;
    while(*s == '/') s++;
    if(s != p) memmove(p, s, strlen(s) + 1);
    if(!*p) return true;
    const char* seg = p;
    for(;;) {
        const char* e = strchr(seg, '/');
        size_t l = e ? (size_t)(e - seg) : strlen(seg);
        if(l == 0 || (l == 1 && seg[0] == '.') || (l == 2 && seg[0] == '.' && seg[1] == '.')) {
            return false;
        }
        if(!e) break;
        seg = e + 1;
    }
    return true;
}

static void safe_ref(const char* ref, char* out, size_t cap) {
    size_t o = 0;
    for(size_t i = 0; ref[i] && o + 1 < cap; i++) {
        unsigned char c = (unsigned char)ref[i];
        out[o++] = (c == '/') ? '-' : (isalnum(c) || strchr("._-+@=,~", c)) ? (char)c : '_';
    }
    out[o] = 0;
}

static void z_free(Z* z) {
    for(size_t i = 0; i < z->stn; i++) free(z->st[i].path);
    free(z->st);
    free(z->ob);
    free(z->sb);
    free(z->nbuf);
    if(z->sf) {
        hub_gitio_close(z->sf);
        hub_gitio_remove(z->r->storage, z->spath);
    }
    if(z->defl) hub_deflate_free(z->defl);
    if(z->g2) hub_git_close(z->g2);
    if(z->g) hub_git_close(z->g);
}

static void archive(HubReq* r, const char* name, const char* ref, const char* sub) {
    if(!hub_git_name_ok(name)) {
        hub_send_error(r, 404, "repository not found");
        return;
    }
    if(!ref_ok(ref)) {
        hub_send_error(r, 404, "ref not found");
        return;
    }
    if(!hub_heap_ok(r, 120 * 1024)) return;
    Z* z = calloc(1, sizeof(Z));
    if(!z) {
        hub_send_error(r, 503, "low memory, try again");
        return;
    }
    z->r = r;
    z->ok = true;
    z->lim = 0xFFFFFFFFu;
    z->ent_lim = 0xFFFF;
    z->lim_entry = 0xF0000000u;
#ifdef PORT_ZIP64_TEST
    {
        const char* t = getenv("GITPHIN_ZIP64_TEST");
        if(t && *t) {
            z->lim = (uint64_t)strtoull(t, NULL, 10);
            z->ent_lim = 20;
            z->lim_entry = z->lim - z->lim / 16;
        }
    }
#endif
    zip_crc_init();
    char base[512];
    snprintf(base, sizeof base, HUB_GIT_ROOT "/%s.git", name);
    z->g = hub_git_open(r->storage, base);
    z->g2 = z->g ? hub_git_open(r->storage, base) : NULL;
    if(!z->g || !z->g2) {
        bool nomem = z->g != NULL;
        z_free(z);
        free(z);
        if(nomem) hub_send_error(r, 503, "low memory, try again");
        else hub_send_error(r, 404, "repository not found");
        return;
    }
    HubOid commit, tree, target;
    uint32_t tmode = 040000;
    uint8_t* cdata = NULL;
    size_t clen = 0;
    uint8_t ctype = 0;
    HubGitCommit cm;
    if(hub_git_resolve(z->g, ref, &commit) != HUB_GIT_OK ||
       hub_git_read(z->g, &commit, &ctype, &cdata, &clen) != HUB_GIT_OK) {
        z_free(z);
        free(z);
        hub_send_error(r, 404, "ref not found");
        return;
    }
    bool pc = ctype == HUB_OBJ_COMMIT && hub_git_parse_commit(cdata, clen, &cm) == HUB_GIT_OK;
    if(pc) {
        tree = cm.tree;
        zip_dos_time(z, cm.committer.time);
    }
    hub_git_free(cdata);
    if(!pc) {
        z_free(z);
        free(z);
        hub_send_error(r, 404, "ref is not a commit");
        return;
    }
    target = tree;
    if(sub[0]) {
        if(hub_git_tree_find(z->g, &tree, sub, &target, &tmode) != HUB_GIT_OK ||
           (tmode & 0170000) != 0040000) {
            z_free(z);
            free(z);
            hub_send_error(r, 404, "path not found");
            return;
        }
    }

    char sref[HUB_GIT_REF_MAX + 8];
    safe_ref(ref, sref, sizeof sref);
    z->ob = malloc(8 + OUT_CHUNK + 2);
    z->scap = SPOOL_BUF;
    z->sb = malloc(z->scap);
    z->nbuf = malloc(NAME_MAX_Z + 2);
    z->defl = hub_deflate_new(zip_defl_out, z);
    if(!z->ob || !z->sb || !z->nbuf || !z->defl) {
        z_free(z);
        free(z);
        hub_send_error(r, 503, "low memory, try again");
        return;
    }
    /* spool file */
    {
        char p[560];
        snprintf(p, sizeof p, "%s/hub", base);
        hub_gitio_mkdir(r->storage, p);
        snprintf(p, sizeof p, "%s/hub/tmp", base);
        hub_gitio_mkdir(r->storage, p);
        snprintf(z->spath, sizeof z->spath, "%s/hub/tmp/zipcd", base);
        z->sf = hub_gitio_create(r->storage, z->spath);
    }

    char extra[HUB_GIT_REF_MAX + HUB_GIT_PATH_MAX + 128];
    snprintf(
        extra,
        sizeof extra,
        "Content-Disposition: attachment; filename=\"%.60s-%s.zip\"\r\n",
        name,
        sref);
    bool was_long = hub_long_op;
    hub_long_op = true;
    if(!hub_send_head(r, 200, "application/zip", HUB_CHUNKED, extra)) {
        z->ok = false;
    }

    /* top folder, then the parents of the sub path, then the tree */
    char* path = z->nbuf;
    size_t pl = (size_t)snprintf(path, 600, "%.60s-%s/", name, sref);
    if(z->ok && !zip_emit_dir(z, path, pl)) z->ok = false;
    if(z->ok && sub[0]) {
        const char* seg = sub;
        for(;;) {
            const char* e = strchr(seg, '/');
            size_t l = e ? (size_t)(e - seg) : strlen(seg);
            if(pl + l + 1 > NAME_MAX_Z) { z->ok = false; break; }
            memcpy(path + pl, seg, l);
            pl += l;
            path[pl++] = '/';
            path[pl] = 0;
            if(!zip_emit_dir(z, path, pl)) { z->ok = false; break; }
            if(!e) break;
            seg = e + 1;
        }
    }
    if(z->ok && !zip_push_dir(z, &target, path, pl)) z->ok = false;
    zip_walk_stack(z);
    if(z->ok && !zip_z_central_end(z)) z->ok = false;
    if(z->ok) {
        zip_z_flush(z);
        if(z->ok && !hub_conn_write(r->t, "0\r\n\r\n", 5)) z->ok = false;
    }
    hub_long_op = was_long;
    /* On error the connection is closed without the final chunk: the client sees a broken download. */
    z_free(z);
    free(z);
}

bool port_zip_route(HubReq* r) {
    if(strcmp(r->method, "GET") != 0) return false;
    char name[HUB_GIT_PATH_MAX], ref[HUB_GIT_REF_MAX + 8], sub[HUB_PATH_MAX];
    sub[0] = 0;
    if(strncmp(r->path, "/git/", 5) == 0) {
        const char* s = r->path + 5;
        const char* m = strstr(s, ".git/archive/");
        if(!m) return false;
        size_t nl = (size_t)(m - s);
        if(nl == 0 || nl >= sizeof name || memchr(s, '/', nl) || memchr(s, '%', nl)) return false;
        memcpy(name, s, nl);
        name[nl] = 0;
        const char* rs = m + 13;
        size_t rl = strlen(rs);
        if(rl < 5 || strcmp(rs + rl - 4, ".zip") != 0) return false;
        if(!pct_decode(rs, rl - 4, ref, sizeof ref)) {
            hub_send_error(r, 404, "ref not found");
            return true;
        }
    } else if(strcmp(r->path, "/api/git/archive") == 0 || strcmp(r->path, "/pc/zip") == 0) {
        if(!hub_query_get(r->query, "name", name, sizeof name)) {
            hub_send_error(r, 400, "missing name");
            return true;
        }
        if(!hub_query_get(r->query, "ref", ref, sizeof ref)) {
            hub_send_error(r, 400, "missing ref");
            return true;
        }
        if(hub_query_get(r->query, "path", sub, sizeof sub) && !subpath_ok(sub)) {
            hub_send_error(r, 404, "path not found");
            return true;
        }
    } else {
        return false;
    }
    archive(r, name, ref, sub);
    return true;
}
