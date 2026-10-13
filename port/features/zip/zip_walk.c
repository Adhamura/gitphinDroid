/* Download as ZIP, tree walk: nothing recurses, folders go through an explicit stack. */
#include "zip_int.h"

/* ---------- traversal ---------- */

bool zip_push_dir(Z* z, const HubOid* oid, const char* path, size_t plen) {
    if(z->stn == z->stcap) {
        size_t nc = z->stcap ? z->stcap * 2 : 16;
        DirItem* n = realloc(z->st, nc * sizeof(DirItem));
        if(!n) return false;
        z->st = n;
        z->stcap = nc;
    }
    char* p = malloc(plen + 1);
    if(!p) return false;
    memcpy(p, path, plen);
    p[plen] = 0;
    z->st[z->stn].oid = *oid;
    z->st[z->stn].path = p;
    z->stn++;
    return true;
}

static bool walk_cb(void* ctx, uint32_t mode, const char* name, size_t nl, const HubOid* oid) {
    Z* z = ctx;
    if((mode & 0170000) == 0160000) return z->ok; /* submodule */
    if(nl == 0 || (nl == 1 && name[0] == '.') || (nl == 2 && name[0] == '.' && name[1] == '.')) {
        return z->ok;
    }
    if(memchr(name, '/', nl) || memchr(name, 0, nl)) return z->ok;
    size_t pl = strlen(z->dirpath);
    if(pl + nl + 1 > NAME_MAX_Z) return z->ok; /* too long: skip */
    memcpy(z->nbuf, z->dirpath, pl);
    memcpy(z->nbuf + pl, name, nl);
    size_t full = pl + nl;
    hub_beat();
    if((mode & 0170000) == 0040000) {
        z->nbuf[full++] = '/';
        z->nbuf[full] = 0;
        if(!zip_emit_dir(z, z->nbuf, full) || !zip_push_dir(z, oid, z->nbuf, full)) {
            z->ok = false;
            return false;
        }
        return z->ok;
    }
    z->nbuf[full] = 0;
    if(!zip_emit_file(z, z->nbuf, full, mode, oid)) {
        z->ok = false;
        return false;
    }
    return z->ok;
}

/* Walk the folders on the stack until it is empty or an error stops it. */
bool zip_walk_stack(Z* z) {
    while(z->ok && z->stn) {
        DirItem it = z->st[--z->stn];
        z->dirpath = it.path;
        int rc = hub_git_tree_walk(z->g, &it.oid, walk_cb, z);
        free(it.path);
        z->dirpath = NULL;
        if(rc != HUB_GIT_OK) z->ok = false;
    }
    return z->ok;
}
