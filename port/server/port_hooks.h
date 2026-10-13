#pragma once
/* gitphinDroid hooks into the core (sync_core.sh adds the calls to hub_http.c). */
#include "hub_req.h"

/* Before the git routes: true when the port answered the request. */
bool port_route(HubReq* r);
/* After every request (the answer is sent and the connection closed). */
void port_after_request(HubReq* r);

/* The parts behind the hooks, one file each. */
bool port_smart_route(HubReq* r); /* port_smart.c: smart HTTP (git-upload-pack, git-receive-pack) */
bool port_zip_route(HubReq* r); /* port_zip.c: GET /git/<name>.git/archive/<ref>.zip */
bool port_avatar_route(HubReq* r); /* port_avatar.c: /api/git/avatar (repo pictures) */
void port_hook_after(HubReq* r); /* port_webhook.c: webhooks after a push */

/* A raw request header (the core keeps only some): copies the value of `name` (no colon,
 * any case) into out. false when absent. Works after the core parsed the head. */
bool port_header(const HubReq* r, const char* name, char* out, size_t cap);

/* port_webhook.c: a snapshot of the refs that the request may change (called first in port_route). */
void port_hook_before(HubReq* r);
