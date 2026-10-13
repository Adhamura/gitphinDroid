#pragma once
/* Smart HTTP (git-upload-pack, git-receive-pack): the files in port/smart/.
 *
 *   route.c          port_smart_route: routes, ref advertisement, push to create
 *   body.c           request body (chunked, gzip)          out.c     answer writer, pkt-lines
 *   refs.c           refs, HEAD, small file writes         util.c    ids, map, inflate, deltas
 *   odb.c            object store reader
 *   upload_pack.c    upload-pack request and negotiation   upload_walk.c  commit/tree walk
 *   upload_shallow.c shallow / deepen                      pack_writer.c  the outgoing pack
 *   receive_pack.c   receive-pack commands, refs, report   index_pack.c   index the pushed pack
 *
 * Files are accessed with POSIX calls on host paths (port_storage_host_path), so packs can be
 * larger than 4 GB also on 32-bit Android. Only the worker thread runs this code. */
#include "hub_req.h"
#include "util.h"
#include "odb.h"
#include "body.h"
#include "out.h"
#include "refs.h"

/* ---------- the two services ---------- */

typedef struct {
    HubReq* r;
    char name[72]; /* repo name */
    char base[128]; /* core path HUB_GIT_ROOT "/<name>.git" */
    char repo[SM_PATH_MAX]; /* host path */
} SmRepo;

void sm_upload_pack(SmRepo* rp);
void sm_receive_pack(SmRepo* rp);
