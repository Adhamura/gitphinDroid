#pragma once
/* Internal to features/zip/: the archive state and the functions the files share. */
#include "port_hooks.h"

#include "hub_git.h"
#include "hub_gitobj.h"
#include "hub_gitio.h"
#include "hub_deflate.h"

#include <furi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>

#define OUT_CHUNK  16384
#define SPOOL_BUF  32768
#define NAME_MAX_Z 4096

typedef struct {
    HubOid oid;
    char* path; /* "top/a/b/" */
} DirItem;

typedef struct {
    HubReq* r;
    HubGit* g; /* tree walks */
    HubGit* g2; /* blobs */
    HubDeflate* defl;
    bool ok;
    uint64_t off; /* bytes of the archive written */
    uint64_t lim, ent_lim, lim_entry;

    /* output chunk */
    uint8_t* ob; /* 8 + OUT_CHUNK + 2 */
    size_t on;

    /* central directory spool */
    uint8_t* sb;
    size_t sn, scap;
    HubGitFile* sf;
    char spath[560];
    uint64_t cd_size;
    uint64_t entries;

    uint16_t dtime, ddate;

    /* current entry */
    uint32_t crc;
    uint64_t usize, csize;
    uint8_t tail[4];
    size_t ntail;
    size_t skip;

    /* traversal */
    DirItem* st;
    size_t stn, stcap;
    const char* dirpath;
    char* nbuf; /* NAME_MAX_Z + 1 */
} Z;

/* zip_writer.c: bytes out, entries, central directory */
void zip_crc_init(void);
void zip_z_flush(Z* z);
bool zip_z_put(Z* z, const void* data, size_t len);
void zip_dos_time(Z* z, int64_t t);
bool zip_emit_dir(Z* z, const char* name, size_t nlen);
bool zip_emit_file(Z* z, const char* name, size_t nlen, uint32_t mode, const HubOid* oid);
bool zip_defl_out(void* ctx, const uint8_t* d, size_t n);
bool zip_z_central_end(Z* z);

/* zip_walk.c: the explicit stack of folders */
bool zip_push_dir(Z* z, const HubOid* oid, const char* path, size_t plen);
bool zip_walk_stack(Z* z);
