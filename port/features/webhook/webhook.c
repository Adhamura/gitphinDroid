/* Webhooks after a push (see README: Webhooks).
 *
 * port_hook_before (first line of port_route) takes a snapshot of the refs of the repo that the
 * request may change. port_hook_after (after the answer) takes a second one and sends the diff
 * as one JSON event to the sink (JNI: NativeServer.onEvent; gitphind: --webhook-log FILE).
 * Only the worker thread calls these, so the state below needs no lock. */
#include "port.h"
#include "port_hooks.h"

#include "hub_git.h"
#include "hub_gitio.h"
#include "hub_gitobj.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define WH_REFS_MAX   4096 /* refs kept per snapshot */
#define WH_EVENT_REFS 200 /* refs listed in one event */
#define WH_REF_LEN    208
#define WH_NAME_MAX   64
#define WH_ZERO       "0000000000000000000000000000000000000000"

static PortWebhookSink sink;

void port_set_webhook_sink(PortWebhookSink fn) {
    sink = fn;
}

/* ---------- a small growing string ---------- */

typedef struct {
    char* p;
    size_t n, cap;
    bool bad;
} Buf;

static void buf_add(Buf* b, const char* s, size_t len) {
    if(b->bad) return;
    if(b->n + len + 1 > b->cap) {
        size_t nc = b->cap ? b->cap * 2 : 512;
        while(nc < b->n + len + 1)
            nc *= 2;
        char* np = realloc(b->p, nc);
        if(!np) {
            b->bad = true;
            return;
        }
        b->p = np;
        b->cap = nc;
    }
    memcpy(b->p + b->n, s, len);
    b->n += len;
    b->p[b->n] = '\0';
}

static void buf_lit(Buf* b, const char* s) {
    buf_add(b, s, strlen(s));
}

static void buf_fmt(Buf* b, const char* fmt, ...) {
    char tmp[128];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if(n > 0 && (size_t)n < sizeof tmp) buf_add(b, tmp, (size_t)n);
}

/* A JSON string, ASCII only: every non-ASCII character becomes \uXXXX (invalid UTF-8 becomes '?'),
 * so the Java side never meets odd UTF-8. */
static void buf_json(Buf* b, const char* s, size_t n) {
    buf_lit(b, "\"");
    for(size_t i = 0; i < n;) {
        unsigned char c = (unsigned char)s[i];
        if(c == '"' || c == '\\') {
            char e[2] = {'\\', (char)c};
            buf_add(b, e, 2);
            i++;
        } else if(c == '\n') {
            buf_lit(b, "\\n"), i++;
        } else if(c == '\r') {
            buf_lit(b, "\\r"), i++;
        } else if(c == '\t') {
            buf_lit(b, "\\t"), i++;
        } else if(c < 0x20 || c == 0x7f) {
            buf_fmt(b, "\\u%04x", c), i++;
        } else if(c < 0x80) {
            buf_add(b, (const char*)&c, 1), i++;
        } else {
            unsigned len = c >= 0xf0 ? 4 : c >= 0xe0 ? 3 : c >= 0xc2 ? 2 : 0;
            uint32_t cp = len == 4 ? c & 7 : len == 3 ? c & 15 : c & 31;
            bool ok = len && i + len <= n;
            for(unsigned k = 1; ok && k < len; k++) {
                unsigned char d = (unsigned char)s[i + k];
                if((d & 0xc0) != 0x80) ok = false;
                cp = (cp << 6) | (d & 0x3f);
            }
            if(ok && ((len == 3 && cp < 0x800) || (len == 4 && (cp < 0x10000 || cp > 0x10ffff)) ||
                      (cp >= 0xd800 && cp < 0xe000)))
                ok = false;
            if(!ok) {
                buf_lit(b, "?");
                i++;
            } else {
                if(cp >= 0x10000) {
                    cp -= 0x10000;
                    buf_fmt(b, "\\u%04x\\u%04x", 0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
                } else {
                    buf_fmt(b, "\\u%04x", cp);
                }
                i += len;
            }
        }
    }
    buf_lit(b, "\"");
}

/* ---------- ref snapshot ---------- */

typedef struct {
    char sha[41];
    char name[WH_REF_LEN];
} WhRef;

typedef struct {
    WhRef* v;
    size_t n, cap;
} Snap;

static void snap_free(Snap* s) {
    free(s->v);
    s->v = NULL;
    s->n = s->cap = 0;
}

static bool snap_add(Snap* s, const char* name, const char* sha) {
    if(strlen(name) >= WH_REF_LEN) return true; /* skipped, not an error */
    if(s->n >= WH_REFS_MAX) return false;
    if(s->n == s->cap) {
        size_t nc = s->cap ? s->cap * 2 : 16;
        WhRef* nv = realloc(s->v, nc * sizeof(WhRef));
        if(!nv) return false;
        s->v = nv;
        s->cap = nc;
    }
    WhRef* e = &s->v[s->n++];
    snprintf(e->name, sizeof e->name, "%s", name);
    memcpy(e->sha, sha, 41);
    return true;
}

static int ref_cmp(const void* a, const void* b) {
    return strcmp(((const WhRef*)a)->name, ((const WhRef*)b)->name);
}

static bool hex40(char* s) { /* lowercases in place */
    for(int i = 0; i < 40; i++) {
        char c = s[i];
        if(c >= 'A' && c <= 'F') s[i] = c = (char)(c + 32);
        if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
}

static bool walk(Storage* io, const char* base, const char* rel, Snap* s, int depth) {
    char path[640];
    snprintf(path, sizeof path, "%s/%s", base, rel);
    HubGitDir* d = hub_gitio_dir_open(io, path);
    if(!d) return true; /* no such folder: no refs */
    bool ok = true;
    char nm[128];
    bool is_dir;
    while(ok && hub_gitio_dir_read(d, nm, sizeof nm, &is_dir)) {
        char child[WH_REF_LEN + 64];
        int cn = snprintf(child, sizeof child, "%s/%s", rel, nm);
        if(cn < 0 || cn >= WH_REF_LEN) continue;
        if(is_dir) {
            if(depth < 8) ok = walk(io, base, child, s, depth + 1);
            continue;
        }
        size_t nl = strlen(nm);
        if((nl > 5 && !strcmp(nm + nl - 5, ".lock")) || (nl > 5 && !strcmp(nm + nl - 5, ".part")))
            continue;
        snprintf(path, sizeof path, "%s/%s", base, child);
        HubGitFile* f = hub_gitio_open(io, path);
        if(!f) continue;
        char sha[48];
        size_t got = hub_gitio_read(f, sha, 40);
        hub_gitio_close(f);
        if(got != 40) continue;
        sha[40] = '\0';
        if(hex40(sha)) ok = snap_add(s, child, sha);
    }
    hub_gitio_dir_close(d);
    return ok;
}

static bool is_ref_name(const char* n) {
    return !strncmp(n, "refs/heads/", 11) || !strncmp(n, "refs/tags/", 10);
}

static bool find_ref(const Snap* s, const char* name) {
    for(size_t i = 0; i < s->n; i++)
        if(!strcmp(s->v[i].name, name)) return true;
    return false;
}

/* Loose refs (heads and tags) and packed-refs lines that have no loose file. */
static bool snap_load(Storage* io, const char* name, Snap* s) {
    char base[128];
    snprintf(base, sizeof base, HUB_GIT_ROOT "/%s.git", name);
    memset(s, 0, sizeof *s);
    bool ok = walk(io, base, "refs/heads", s, 0) && walk(io, base, "refs/tags", s, 0);
    if(ok) {
        char path[160];
        snprintf(path, sizeof path, "%s/packed-refs", base);
        HubGitFile* f = hub_gitio_open(io, path);
        if(f) {
            uint64_t sz = hub_gitio_size(f);
            if(sz > 0 && sz <= (8u << 20)) {
                char* txt = malloc((size_t)sz + 1);
                if(txt) {
                    size_t got = hub_gitio_read(f, txt, (size_t)sz);
                    txt[got] = '\0';
                    for(char* p = txt; ok && *p;) {
                        char* e = strchr(p, '\n');
                        size_t len = e ? (size_t)(e - p) : strlen(p);
                        if(len > 41 && p[40] == ' ') {
                            char sha[41];
                            memcpy(sha, p, 40);
                            sha[40] = '\0';
                            char saved = p[len];
                            p[len] = '\0';
                            if(hex40(sha) && is_ref_name(p + 41) && !find_ref(s, p + 41))
                                ok = snap_add(s, p + 41, sha);
                            p[len] = saved;
                        }
                        p += len + (e ? 1 : 0);
                    }
                    free(txt);
                } else {
                    ok = false;
                }
            }
            hub_gitio_close(f);
        }
    }
    if(!ok) {
        snap_free(s);
        return false;
    }
    if(s->n > 1) qsort(s->v, s->n, sizeof(WhRef), ref_cmp);
    return true;
}

static bool repo_exists(Storage* io, const char* name) {
    char path[160];
    snprintf(path, sizeof path, HUB_GIT_ROOT "/%s.git/HEAD", name);
    HubGitFile* f = hub_gitio_open(io, path);
    if(!f) return false;
    hub_gitio_close(f);
    return true;
}

/* ---------- what a request may change ---------- */

enum { K_NONE, K_REFS, K_CREATE, K_DELETE };

static struct {
    int kind;
    char name[WH_NAME_MAX + 1];
    bool existed;
    bool have_before;
    Snap before;
} pend;

static const char* const API_REFS[] = {"commit-file", "merge", "ref"};

/* Which repo and what kind of change: fills pend.kind and pend.name. */
static void classify(HubReq* r) {
    pend.kind = K_NONE;
    pend.name[0] = '\0';
    if(!r->path || !r->method) return;
    const char* m = r->method;
    if(!strncmp(r->path, "/git/", 5)) {
        if(!strcmp(m, "GET") || !strcmp(m, "HEAD") || !strcmp(m, "PROPFIND") ||
           !strcmp(m, "OPTIONS") || !strcmp(m, "LOCK") || !strcmp(m, "UNLOCK") ||
           !strcmp(m, "MKCOL"))
            return;
        const char* n = r->path + 5;
        const char* g = strstr(n, ".git/");
        if(!g || g == n || (size_t)(g - n) > WH_NAME_MAX) return;
        memcpy(pend.name, n, (size_t)(g - n));
        pend.name[g - n] = '\0';
        const char* rest = g + 5;
        /* objects are not refs, unless a MOVE puts them into refs/ */
        if(!strncmp(rest, "objects/", 8)) {
            const char* dst = r->h_destination;
            if(strcmp(m, "MOVE") != 0 || !dst || !strstr(dst, "/refs/")) {
                pend.name[0] = '\0';
                return;
            }
        }
        pend.kind = K_REFS;
    } else if(!strncmp(r->path, "/api/git/", 9) && !strcmp(m, "POST")) {
        const char* a = r->path + 9;
        int kind = K_NONE;
        for(size_t i = 0; i < sizeof API_REFS / sizeof API_REFS[0]; i++)
            if(!strcmp(a, API_REFS[i])) kind = K_REFS;
        if(!strcmp(a, "create")) kind = K_CREATE;
        else if(!strcmp(a, "delete")) kind = K_DELETE;
        if(kind == K_NONE || !r->query) return;
        if(!hub_query_get(r->query, "name", pend.name, sizeof pend.name)) return;
        pend.kind = kind;
    } else {
        return;
    }
    if(!hub_git_name_ok(pend.name)) {
        pend.kind = K_NONE;
        pend.name[0] = '\0';
    }
}

static void pend_clear(void) {
    snap_free(&pend.before);
    pend.kind = K_NONE;
    pend.have_before = false;
    pend.existed = false;
    pend.name[0] = '\0';
}

void port_hook_before(HubReq* r) {
    pend_clear();
    classify(r);
    if(pend.kind == K_NONE) return;
    pend.existed = repo_exists(r->storage, pend.name);
    if(pend.kind == K_REFS) pend.have_before = snap_load(r->storage, pend.name, &pend.before);
}

/* ---------- events ---------- */

static const char* via_name(void) {
    switch(port_transport_via()) {
    case PORT_VIA_PHONE: return "phone";
    case PORT_VIA_USB: return "usb";
    case PORT_VIA_TETHER: return "tether";
    case PORT_VIA_WIFI: return "wifi";
    default: return "";
    }
}

static void emit(Buf* b) {
    if(!b->bad && b->p && sink) sink(b->p);
    free(b->p);
}

static void simple_event(const char* event, const char* repo) {
    Buf b = {0};
    buf_lit(&b, "{\"event\":\"");
    buf_lit(&b, event);
    buf_lit(&b, "\",\"repo\":");
    buf_json(&b, repo, strlen(repo));
    buf_fmt(&b, ",\"via\":\"%s\",\"server\":\"gitphin\",\"time\":%lld}", via_name(), (long long)time(NULL));
    emit(&b);
}

/* "head_commit":{...} for the tip sha of a branch; nothing when it cannot be read. */
static void head_commit(Storage* io, const char* repo, const char* sha, Buf* b) {
    char base[128];
    snprintf(base, sizeof base, HUB_GIT_ROOT "/%s.git", repo);
    HubGit* g = hub_git_open(io, base);
    if(!g) return;
    HubOid oid;
    uint8_t type = 0, *data = NULL;
    size_t size = 0;
    if(hub_oid_from_hex(sha, &oid)) {
        hub_git_set_limit(g, 1u << 20);
        if(hub_git_read(g, &oid, &type, &data, &size) == HUB_GIT_OK && data) {
            HubGitCommit c;
            if(type == HUB_OBJ_COMMIT && hub_git_parse_commit(data, size, &c) == HUB_GIT_OK) {
                size_t ml = 0;
                while(ml < c.msg_len && c.msg[ml] != '\n')
                    ml++;
                buf_lit(b, ",\"head_commit\":{\"id\":\"");
                buf_lit(b, sha);
                buf_lit(b, "\",\"message\":");
                buf_json(b, c.msg, ml);
                buf_lit(b, ",\"author\":");
                buf_json(b, c.author.name, c.author.name_len);
                buf_fmt(b, ",\"time\":%lld}", (long long)c.author.time);
            }
            hub_git_free(data);
        }
    }
    hub_git_close(g);
}

static void push_event(Storage* io, const char* repo, const Snap* a, const Snap* z) {
    Buf b = {0};
    size_t nrefs = 0;
    char tip[41] = "";
    buf_lit(&b, "{\"event\":\"push\",\"repo\":");
    buf_json(&b, repo, strlen(repo));
    buf_lit(&b, ",\"refs\":[");
    size_t i = 0, j = 0;
    while(i < a->n || j < z->n) {
        const WhRef* x = i < a->n ? &a->v[i] : NULL;
        const WhRef* y = j < z->n ? &z->v[j] : NULL;
        int c = !x ? 1 : !y ? -1 : strcmp(x->name, y->name);
        const char *name, *before, *after;
        if(c < 0) { /* deleted */
            name = x->name, before = x->sha, after = WH_ZERO;
            i++;
        } else if(c > 0) { /* created */
            name = y->name, before = WH_ZERO, after = y->sha;
            j++;
        } else {
            name = y->name, before = x->sha, after = y->sha;
            i++, j++;
            if(!strcmp(before, after)) continue;
        }
        if(nrefs >= WH_EVENT_REFS) continue;
        if(nrefs) buf_lit(&b, ",");
        nrefs++;
        buf_lit(&b, "{\"ref\":");
        buf_json(&b, name, strlen(name));
        buf_fmt(&b, ",\"before\":\"%s\",\"after\":\"%s\"}", before, after);
        if(!tip[0] && !strncmp(name, "refs/heads/", 11) && strcmp(after, WH_ZERO) != 0)
            memcpy(tip, after, 41);
    }
    if(!nrefs) {
        free(b.p);
        return;
    }
    buf_lit(&b, "]");
    if(tip[0]) head_commit(io, repo, tip, &b);
    buf_fmt(&b, ",\"via\":\"%s\",\"server\":\"gitphin\",\"time\":%lld}", via_name(), (long long)time(NULL));
    emit(&b);
}

void port_hook_after(HubReq* r) {
    if(pend.kind == K_NONE) return;
    Storage* io = r->storage;
    bool exists = repo_exists(io, pend.name);
    if(pend.kind == K_CREATE) {
        if(!pend.existed && exists) simple_event("repo_created", pend.name);
    } else if(pend.kind == K_DELETE) {
        if(pend.existed && !exists) simple_event("repo_deleted", pend.name);
    } else if(pend.have_before) {
        Snap after;
        if(snap_load(io, pend.name, &after)) {
            push_event(io, pend.name, &pend.before, &after);
            snap_free(&after);
        }
    }
    pend_clear();
}
