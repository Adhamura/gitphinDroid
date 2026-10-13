/* Repo avatars:
 *   GET  /api/git/avatar?name=X         the picture (ETag, no-cache), 404 when none
 *   POST /api/git/avatar?name=X         raw PNG/JPEG/GIF/WEBP body, max 2 MB
 *   POST /api/git/avatar-delete?name=X  remove it
 * Stored as <repo>.git/hub/avatar.png (the file the Gitphin 1.2 page uses), so rename and delete of the repo carry it along. */
#include "port_hooks.h"

#include "hub_git.h"

#include <furi.h>
#include <stdio.h>
#include <string.h>

#define AVATAR_MAX (2u * 1024 * 1024)

static const char* sniff(const uint8_t* b, size_t n) {
    if(n >= 8 && !memcmp(b, "\x89PNG\r\n\x1a\n", 8)) return "image/png";
    if(n >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return "image/jpeg";
    if(n >= 6 && (!memcmp(b, "GIF87a", 6) || !memcmp(b, "GIF89a", 6))) return "image/gif";
    if(n >= 12 && !memcmp(b, "RIFF", 4) && !memcmp(b + 8, "WEBP", 4)) return "image/webp";
    return NULL;
}

/* Paths of the repo, or false (the answer is sent). */
static bool paths(HubReq* r, char* dir, size_t dcap, char* file, size_t fcap) {
    char name[80];
    if(!hub_query_get(r->query, "name", name, sizeof(name)) || !hub_git_name_ok(name)) {
        hub_send_error(r, 404, "no such repository");
        return false;
    }
    snprintf(dir, dcap, HUB_GIT_ROOT "/%s.git", name);
    FileInfo info;
    if(storage_common_stat(r->storage, dir, &info) != FSE_OK || !file_info_is_dir(&info)) {
        hub_send_error(r, 404, "no such repository");
        return false;
    }
    snprintf(file, fcap, "%s/hub/avatar.png", dir);
    return true;
}

static void avatar_get(HubReq* r) {
    char dir[160], file[200];
    if(!paths(r, dir, sizeof(dir), file, sizeof(file))) return;
    File* f = storage_file_alloc(r->storage);
    if(!storage_file_open(f, file, FSAM_READ, FSOM_OPEN_EXISTING)) {
        storage_file_free(f);
        hub_send_error(r, 404, "no avatar");
        return;
    }
    /* ETag = FNV-1a of the content + size (no mtime in the storage API) */
    uint32_t h = 2166136261u;
    uint64_t total = 0;
    uint8_t first[16];
    size_t nfirst = 0;
    size_t n;
    while((n = storage_file_read(f, r->io, sizeof(r->io))) > 0) {
        if(nfirst < sizeof(first)) {
            size_t c = sizeof(first) - nfirst < n ? sizeof(first) - nfirst : n;
            memcpy(first + nfirst, r->io, c);
            nfirst += c;
        }
        for(size_t i = 0; i < n; i++) h = (h ^ r->io[i]) * 16777619u;
        total += n;
        hub_beat();
    }
    const char* type = sniff(first, nfirst);
    if(!type || total == 0) {
        storage_file_close(f);
        storage_file_free(f);
        hub_send_error(r, 404, "no avatar");
        return;
    }
    char hdr[160];
    char etag[40];
    snprintf(etag, sizeof(etag), "\"%08lx-%lx\"", (unsigned long)h, (unsigned long)total);
    snprintf(hdr, sizeof(hdr), "Cache-Control: no-cache\r\nETag: %s\r\n", etag);
    if(r->h_if_none_match && strstr(r->h_if_none_match, etag)) {
        hub_send_head(r, 304, type, 0, hdr);
    } else {
        storage_file_seek(f, 0, true);
        bool ok = hub_send_head(r, 200, type, (size_t)total, hdr);
        uint64_t sent = 0;
        while(ok && sent < total) {
            n = storage_file_read(f, r->io, sizeof(r->io));
            if(n == 0) break;
            ok = hub_conn_write(r->t, r->io, n);
            sent += n;
            hub_beat();
        }
    }
    storage_file_close(f);
    storage_file_free(f);
}

static void avatar_post(HubReq* r) {
    char dir[160], file[200];
    if(!paths(r, dir, sizeof(dir), file, sizeof(file))) return;
    if(r->content_length == 0) {
        hub_send_error(r, 411, "Content-Length required");
        return;
    }
    if(r->content_length > AVATAR_MAX) {
        hub_send_error(r, 413, "picture too large (max 2 MB)");
        return;
    }
    if(!hub_sd_ok(r, r->content_length)) return;

    /* the first bytes decide the type */
    size_t have = 0, n;
    while(have < 16 && (n = hub_body_read(r, r->io + have, 16 - have)) > 0)
        have += n;
    const char* type = sniff(r->io, have);
    if(!type) {
        hub_send_error(r, 415, "only PNG, JPEG, GIF or WEBP pictures");
        return;
    }

    char hub[200], tmp[220];
    snprintf(hub, sizeof(hub), "%s/hub", dir);
    storage_common_mkdir(r->storage, hub); /* exists already: fine */
    snprintf(tmp, sizeof(tmp), "%s/avatar.png.tmp", hub);

    File* f = storage_file_alloc(r->storage);
    if(!storage_file_open(f, tmp, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        storage_file_free(f);
        hub_send_error(r, 500, "could not write the picture");
        return;
    }
    bool ok = storage_file_write(f, r->io, have) == have;
    size_t total = have;
    while(ok && (n = hub_body_read(r, r->io, sizeof(r->io))) > 0) {
        total += n;
        if(total > AVATAR_MAX) {
            ok = false;
            break;
        }
        ok = storage_file_write(f, r->io, n) == n;
        hub_beat();
    }
    bool too_big = total > AVATAR_MAX;
    storage_file_close(f);
    storage_file_free(f);
    if(ok && total != r->content_length) ok = false; /* cut off */
    if(!ok) {
        storage_common_remove(r->storage, tmp);
        if(too_big) hub_send_error(r, 413, "picture too large (max 2 MB)");
        else hub_send_error(r, 500, "could not write the picture");
        return;
    }
    storage_common_remove(r->storage, file); /* rename does not replace */
    if(storage_common_rename(r->storage, tmp, file) != FSE_OK) {
        storage_common_remove(r->storage, tmp);
        hub_send_error(r, 500, "could not write the picture");
        return;
    }
    char body[96];
    int bl = snprintf(body, sizeof(body), "{\"ok\":true,\"type\":\"%s\",\"size\":%lu}", type, (unsigned long)total);
    hub_send_body(r, 200, "application/json", body, (size_t)bl);
}

static void avatar_delete(HubReq* r) {
    char dir[160], file[200];
    if(!paths(r, dir, sizeof(dir), file, sizeof(file))) return;
    FS_Error e = storage_common_remove(r->storage, file);
    if(e != FSE_OK && e != FSE_NOT_EXIST) {
        hub_send_error(r, 500, "could not remove the picture");
        return;
    }
    hub_send_ok(r);
}

bool port_avatar_route(HubReq* r) {
    if(!r->path || !r->method) return false;
    bool get = !strcmp(r->method, "GET");
    bool post = !strcmp(r->method, "POST");
    if(!strcmp(r->path, "/api/git/avatar")) {
        if(get) avatar_get(r);
        else if(post) avatar_post(r);
        else hub_send_error(r, 405, "method not allowed");
        return true;
    }
    if(!strcmp(r->path, "/api/git/avatar-delete")) {
        if(post) avatar_delete(r);
        else hub_send_error(r, 405, "method not allowed");
        return true;
    }
    return false;
}
