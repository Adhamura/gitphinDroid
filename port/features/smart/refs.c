/* Smart HTTP: refs, HEAD, ref name rules, small atomic file writes (see refs.h). */
#define _FILE_OFFSET_BITS 64
#include "smart.h"
#include "port_hooks.h"

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

/* ---------- refs ---------- */

void sm_refs_free(SmRefs* l) {
    for(size_t i = 0; i < l->n; i++)
        free(l->v[i].name);
    free(l->v);
    memset(l, 0, sizeof(*l));
}

static bool refs_add(SmRefs* l, const char* name, size_t nlen, const uint8_t id[20]) {
    if(l->n == l->cap) {
        size_t nc = l->cap ? l->cap * 2 : 32;
        SmRef* nv = realloc(l->v, nc * sizeof(SmRef));
        if(!nv) return false;
        l->v = nv;
        l->cap = nc;
    }
    char* nm = malloc(nlen + 1);
    if(!nm) return false;
    memcpy(nm, name, nlen);
    nm[nlen] = '\0';
    l->v[l->n].name = nm;
    memcpy(l->v[l->n].id, id, 20);
    l->n++;
    return true;
}

const SmRef* sm_refs_find(const SmRefs* l, const char* name) {
    for(size_t i = 0; i < l->n; i++)
        if(!strcmp(l->v[i].name, name)) return &l->v[i];
    return NULL;
}

static int ref_cmp(const void* a, const void* b) {
    return strcmp(((const SmRef*)a)->name, ((const SmRef*)b)->name);
}

static bool read_small(const char* path, char* out, size_t cap, size_t* len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if(fd < 0) return false;
    ssize_t n = read(fd, out, cap - 1);
    close(fd);
    if(n < 0) return false;
    out[n] = '\0';
    if(len) *len = (size_t)n;
    return true;
}

/* Read packed-refs whole (NULL when missing). */
static char* packed_refs(const char* repo) {
    char p[SM_PATH_MAX + 32];
    snprintf(p, sizeof p, "%s/packed-refs", repo);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    if(fd < 0) return NULL;
    struct stat st;
    char* s = NULL;
    if(fstat(fd, &st) == 0 && st.st_size < 64 * 1024 * 1024) {
        s = malloc((size_t)st.st_size + 1);
        if(s) {
            size_t got = 0;
            while(got < (size_t)st.st_size) {
                ssize_t n = read(fd, s + got, (size_t)st.st_size - got);
                if(n <= 0) break;
                got += (size_t)n;
            }
            s[got] = '\0';
        }
    }
    close(fd);
    return s;
}

bool sm_refs_load(const char* repo, SmRefs* l) {
    memset(l, 0, sizeof(*l));
    /* loose refs: a stack of folders below refs/heads and refs/tags */
    SmBuf stack = {0};
    smbuf_add(&stack, "refs/heads\nrefs/tags\n", 21);
    size_t pos = 0;
    bool ok = true;
    char path[SM_PATH_MAX + 256];
    while(ok && pos < stack.n) {
        char* nl = memchr(stack.p + pos, '\n', stack.n - pos);
        if(!nl) break;
        char rel[300];
        size_t rl = (size_t)(nl - (char*)stack.p) - pos;
        if(rl >= sizeof rel) {
            pos += rl + 1;
            continue;
        }
        memcpy(rel, stack.p + pos, rl);
        rel[rl] = '\0';
        pos += rl + 1;
        snprintf(path, sizeof path, "%s/%s", repo, rel);
        DIR* d = opendir(path);
        if(!d) continue;
        struct dirent* e;
        while(ok && (e = readdir(d)) != NULL) {
            if(e->d_name[0] == '.') continue;
            size_t nl2 = strlen(e->d_name);
            if(nl2 > 5 && !strcmp(e->d_name + nl2 - 5, ".part")) continue;
            if(nl2 > 5 && !strcmp(e->d_name + nl2 - 5, ".lock")) continue;
            char child[600];
            int cn = snprintf(child, sizeof child, "%s/%s", rel, e->d_name);
            if(cn <= 0 || cn > 255) continue;
            snprintf(path, sizeof path, "%s/%s", repo, child);
            struct stat st;
            if(stat(path, &st) != 0) continue;
            if(S_ISDIR(st.st_mode)) {
                ok = smbuf_add(&stack, child, (size_t)cn) && smbuf_add(&stack, "\n", 1);
                continue;
            }
            char v[128];
            uint8_t id[20];
            if(read_small(path, v, sizeof v, NULL) && sm_unhex(v, id))
                ok = refs_add(l, child, (size_t)cn, id);
        }
        closedir(d);
    }
    smbuf_free(&stack);
    /* packed-refs: the refs without a loose file */
    char* s = ok ? packed_refs(repo) : NULL;
    if(s) {
        for(char* p = s; ok && *p;) {
            char* e = strchr(p, '\n');
            size_t len = e ? (size_t)(e - p) : strlen(p);
            if(e) *e = '\0';
            uint8_t id[20];
            if(len > 41 && p[40] == ' ' && sm_unhex(p, id)) {
                const char* nm = p + 41;
                if((!strncmp(nm, "refs/heads/", 11) || !strncmp(nm, "refs/tags/", 10)) &&
                   !sm_refs_find(l, nm))
                    ok = refs_add(l, nm, strlen(nm), id);
            }
            p += len + (e ? 1 : 0);
        }
        free(s);
    }
    if(ok && l->n > 1) qsort(l->v, l->n, sizeof(SmRef), ref_cmp);
    return ok;
}

bool sm_ref_read(const char* repo, const char* ref, uint8_t id[20]) {
    char path[SM_PATH_MAX + 256], v[128];
    snprintf(path, sizeof path, "%s/%s", repo, ref);
    if(read_small(path, v, sizeof v, NULL) && sm_unhex(v, id)) return true;
    char* s = packed_refs(repo);
    if(!s) return false;
    bool found = false;
    size_t rl = strlen(ref);
    for(char* p = s; *p;) {
        char* e = strchr(p, '\n');
        size_t len = e ? (size_t)(e - p) : strlen(p);
        if(len == 41 + rl && p[40] == ' ' && !memcmp(p + 41, ref, rl) && sm_unhex(p, id)) {
            found = true;
            break;
        }
        p += len + (e ? 1 : 0);
    }
    free(s);
    return found;
}

bool sm_head(const char* repo, const SmRefs* l, char* sym, size_t cap, uint8_t id[20]) {
    char path[SM_PATH_MAX + 16], v[512];
    sym[0] = '\0';
    snprintf(path, sizeof path, "%s/HEAD", repo);
    if(!read_small(path, v, sizeof v, NULL)) return false;
    if(!strncmp(v, "ref: ", 5)) {
        size_t n = strcspn(v + 5, "\r\n");
        if(n >= cap) return false;
        memcpy(sym, v + 5, n);
        sym[n] = '\0';
        const SmRef* r = sm_refs_find(l, sym);
        if(!r) return false;
        memcpy(id, r->id, 20);
        return true;
    }
    return sm_unhex(v, id);
}

/* The core's git_ref_ok (hub_git.c): refs/heads/ or refs/tags/ with git's name rules. */
bool sm_ref_ok(const char* ref) {
    size_t len = strlen(ref);
    size_t pre;
    if(!strncmp(ref, "refs/heads/", 11))
        pre = 11;
    else if(!strncmp(ref, "refs/tags/", 10))
        pre = 10;
    else
        return false;
    if(len == pre || len > 180) return false;
    if(ref[len - 1] == '/' || ref[len - 1] == '.') return false;
    if(ref[pre] == '.') return false;
    for(size_t i = 0; i < len; i++) {
        char c = ref[i];
        if((unsigned char)c <= 0x20 || c == 0x7f || strchr("~^:?*[\\", c)) return false;
        char n = ref[i + 1];
        if(c == '.' && n == '.') return false;
        if(c == '/' && (n == '/' || n == '.')) return false;
        if(c == '@' && n == '{') return false;
        if((c == '/' || n == '\0') && i >= 4) {
            const char* end = (n == '\0') ? ref + i + 1 : ref + i;
            if(!strncmp(end - 5, ".lock", 5)) return false;
        }
    }
    return true;
}

bool sm_mkdirs(const char* path, size_t from) {
    char tmp[SM_PATH_MAX + 256];
    snprintf(tmp, sizeof tmp, "%s", path);
    for(char* p = tmp + from; *p; p++) {
        if(*p != '/') continue;
        *p = '\0';
        bool ok = mkdir(tmp, 0755) == 0 || errno == EEXIST;
        struct stat st;
        if(ok) ok = stat(tmp, &st) == 0 && S_ISDIR(st.st_mode);
        *p = '/';
        if(!ok) return false;
    }
    return true;
}

bool sm_write_file(const char* path, const void* data, size_t len) {
    char tmp[SM_PATH_MAX + 300];
    snprintf(tmp, sizeof tmp, "%s.part", path);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if(fd < 0) return false;
    const uint8_t* p = data;
    bool ok = true;
    while(ok && len) {
        ssize_t n = write(fd, p, len);
        if(n < 0 && errno == EINTR) continue;
        if(n <= 0) ok = false;
        else {
            p += n;
            len -= (size_t)n;
        }
    }
    if(ok) ok = fsync(fd) == 0;
    if(close(fd) != 0) ok = false;
    if(ok) ok = rename(tmp, path) == 0;
    if(!ok) unlink(tmp);
    return ok;
}

