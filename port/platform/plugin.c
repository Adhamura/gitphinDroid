/* The Flipper loads the routes of the five plugins (.fal) on demand to save RAM.
 * Here every plugin is linked into the one library, so "load" is a table lookup.
 * hub_gitext.c is built once with HUB_EXT_QUERY and HUB_EXT_WRITE (one object holds both
 * plugins); its descriptor is hub_pl_query_ep. The pack functions (hub_git_pack_loose ...)
 * are called directly, so the wrappers of hub_plugin.c are not needed. */
#include "hub_plugin.h"

#include <furi.h>

DavLock hub_dav_locks[DAV_LOCKS];

/* Same as in hub_plugin.c: is a WebDAV lock held on this repo (a push is going on)? */
bool hub_dav_repo_locked(const char* name) {
    uint32_t repo = 2166136261u;
    for(const char* p = name; *p; p++)
        repo = (repo ^ (uint8_t)*p) * 16777619u;
    uint32_t now = furi_get_tick() / furi_kernel_get_tick_frequency();
    for(size_t i = 0; i < DAV_LOCKS; i++) {
        if(hub_dav_locks[i].used && hub_dav_locks[i].repo == repo &&
           (int32_t)(hub_dav_locks[i].expires - now) > 0)
            return true;
    }
    return false;
}

const FlipperAppPluginDescriptor* hub_pl_dav_ep(void);
const FlipperAppPluginDescriptor* hub_pl_query_ep(void);
const FlipperAppPluginDescriptor* hub_pl_doc_ep(void);

static const HubPluginPack pack_ops = {
    .pack_loose = hub_git_pack_loose,
    .pack_merge = hub_git_pack_merge,
    .pack_stats = hub_git_pack_stats,
    .graph_update = hub_git_graph_update,
};

const void* hub_plugin_use(HubPluginId id, HubReq* r) {
    switch(id) {
    case HUB_PL_DAV:
        return hub_pl_dav_ep()->entry_point;
    case HUB_PL_PACK:
        return &pack_ops;
    case HUB_PL_WRITE:
    case HUB_PL_QUERY:
        return hub_pl_query_ep()->entry_point;
    case HUB_PL_DOC:
        return hub_pl_doc_ep()->entry_point;
    default:
        if(r) hub_send_error(r, 500, "no such plugin");
        return NULL;
    }
}

bool hub_plugin_fits(HubPluginId id) {
    (void)id;
    return true;
}

void hub_plugin_unload(void) {
}

void hub_plugin_tick(void) {
}

bool hub_plugin_dispatch(HubReq* r, HubPluginId id) {
    const HubPluginRoute* ops = hub_plugin_use(id, r);
    if(ops) ops->route(r);
    return true;
}

/* Same as in hub_plugin.c. */
HubPluginId hub_plugin_for(const HubReq* r) {
    if(strncmp(r->path, "/api/git/", 9) != 0) return HUB_PL_COUNT;
    const char* n = r->path + 9;
    if(!strcmp(n, "doc") || !strcmp(n, "doc-delete") || !strcmp(n, "docs")) return HUB_PL_DOC;
    if(!strcmp(n, "diff") || !strcmp(n, "compare") || !strcmp(n, "search") || !strcmp(n, "log") ||
       !strcmp(n, "commit") || !strcmp(n, "storage"))
        return HUB_PL_QUERY;
    if(!strcmp(n, "commit-file") || !strcmp(n, "merge")) return HUB_PL_WRITE;
    return HUB_PL_COUNT;
}
