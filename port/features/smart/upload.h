#pragma once
/* Smart HTTP: git-upload-pack internals shared by upload_pack.c, upload_walk.c,
 * upload_shallow.c and pack_writer.c. */
#include "smart.h"
#include "hub_deflate.h"

#define REQ_MAX     (128u * 1024 * 1024)
#define INF_DEPTH   0x7fffffff
#define OBJ_UNINT   0xfffffffeu
#define DEPTH_UNSET INT32_MAX

/* commit flags */
#define C_PARSED    0x0001u
#define C_UNINT     0x0002u
#define C_SEEN      0x0004u
#define C_ADDED     0x0008u
#define C_THEY_HAVE 0x0010u
#define C_CLIENT_SH 0x0020u
#define C_NOT_SH    0x0040u
#define C_SHALLOW   0x0080u
#define C_GRAFT     0x0100u
#define C_REACH     0x0200u
#define C_DNOT      0x0400u
#define C_INCL      0x0800u

typedef struct {
    uint8_t id[20];
    uint8_t tree[20];
    int64_t time;
    uint32_t flags;
    uint32_t par; /* first parent in Up.par */
    uint32_t npar;
    int32_t depth;
    uint32_t stamp;
} Cm;

typedef struct {
    uint8_t id[20];
    uint8_t type;
    bool written;
    int32_t pack; /* -1: loose */
    uint64_t off;
    uint64_t out_off;
} Obj;

typedef struct {
    SmRepo* rp;
    SmOdb* odb;
    SmOut o;
    bool failed;
    char err[160];

    /* capabilities */
    int multi_ack; /* 0, 1 multi_ack, 2 multi_ack_detailed */
    bool no_done, ofs, no_progress, include_tag, deepen_relative;

    /* request */
    SmBuf want_ids, shallow_ids, dnot;
    int depth;
    int64_t since;
    bool deepen;

    /* commits */
    Cm* cm;
    size_t ncm, capcm;
    SmMap cmap;
    uint32_t* par;
    size_t npar, cappar;
    uint32_t stamp;

    uint32_t* wants; /* commit indexes */
    size_t nwants, capwants;
    uint32_t* edges; /* extra edges (unshallowed commits) */
    size_t nedges, capedges;
    SmBuf haves; /* have ids we have */
    size_t nhaves;
    int64_t oldest_have;
    bool have_oldest;

    /* objects */
    SmMap objs;
    Obj* send;
    size_t nsend, capsend;
    SmBuf extra_objs; /* non-commit want targets (tree, blob) with their type: 21 bytes each */

    /* pack writer */
    HubSha1 sha;
    uint64_t written;
    uint8_t* pbuf;
    size_t pn;
    HubDeflate* defl;
    unsigned reused, reused_delta, deltas;
} Up;

/* parents as the walk sees them (shallow grafts have none) */
static inline uint32_t cm_npar(const Up* u, uint32_t ci) {
    const Cm* c = &u->cm[ci];
    return (c->flags & C_GRAFT) ? 0 : c->npar;
}

void up_fail(Up* u, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
bool up_grow(void** p, size_t* cap, size_t need, size_t elem);
bool up_push_u32(uint32_t** v, size_t* n, size_t* cap, uint32_t x);
/* upload_walk.c */
uint32_t up_cm_get(Up* u, const uint8_t id[20]);
bool up_cm_parse(Up* u, uint32_t ci);
uint32_t up_obj_add(Up* u, const uint8_t id[20], int type);
bool up_obj_unint(Up* u, const uint8_t id[20]);
bool up_tree_entries(Up* u, const uint8_t tree[20], SmBuf* out);
void up_mark_tree_unint(Up* u, const uint8_t root[20]);
void up_add_tree(Up* u, const uint8_t root[20]);
uint32_t* up_walk_commits(Up* u, size_t* nout);
bool up_ok_to_give_up(Up* u);
int up_got_have(Up* u, const uint8_t id[20]);
/* upload_shallow.c */
void up_do_shallow(Up* u);
/* pack_writer.c */
void up_compute_objects(Up* u, size_t* ncommits);
void up_send_pack(Up* u);
void up_pw_flush(Up* u);
