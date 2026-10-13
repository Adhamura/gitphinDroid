#pragma once
/* Smart HTTP: small shared helpers (ids, CRC-32, id map, windowed reads, inflate, deltas). */
#include "hub_sha1.h"
#include "hub_inflate.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define SM_PATH_MAX 1024
#define SM_NONE     0xffffffffu
#define SM_AGENT    "gitphin"

/* results */
#define SM_OK         0
#define SM_E_NOTFOUND (-1)
#define SM_E_CORRUPT  (-2)
#define SM_E_NOMEM    (-3)
#define SM_E_IO       (-4)

/* object types (pack numbers) */
#define SM_COMMIT    1
#define SM_TREE      2
#define SM_BLOB      3
#define SM_TAG       4
#define SM_OFS_DELTA 6
#define SM_REF_DELTA 7

/* ---------- small helpers ---------- */

extern const uint8_t sm_zero_id[20];
void sm_hex(const uint8_t id[20], char out[41]);
bool sm_unhex(const char* s, uint8_t id[20]); /* exactly 40 hex digits (then end, space or LF) */
bool sm_is_zero(const uint8_t id[20]);
uint32_t sm_crc32(uint32_t crc, const void* p, size_t n);
const char* sm_type_name(int type);
/* git object id of content: SHA-1 of "<type> <len>\0" + data */
void sm_hash_obj(int type, const void* data, size_t len, uint8_t id[20]);
void sm_hash_begin(HubSha1* s, int type, uint64_t len);

typedef struct {
    uint8_t* p;
    size_t n, cap;
    bool bad;
} SmBuf;
bool smbuf_add(SmBuf* b, const void* d, size_t n);
void smbuf_free(SmBuf* b);

/* ---------- object id -> uint32 map (open addressing) ---------- */

typedef struct {
    uint8_t* keys; /* cap * 20 */
    uint32_t* vals; /* SM_NONE = empty slot */
    size_t cap, n;
} SmMap;
bool smmap_init(SmMap* m, size_t hint);
void smmap_free(SmMap* m);
uint32_t smmap_get(const SmMap* m, const uint8_t id[20]); /* SM_NONE when absent */
/* Insert or overwrite (val must not be SM_NONE). false: out of memory. */
bool smmap_put(SmMap* m, const uint8_t id[20], uint32_t val);

/* ---------- file reading with a window (pread, 64-bit offsets) ---------- */

typedef struct {
    int fd;
    uint64_t size;
    uint8_t* buf;
    size_t cap, len;
    uint64_t start; /* file offset of buf[0] */
    uint64_t pos; /* cursor for sm_win_read (the inflate callback) */
    bool err;
} SmWin;
bool sm_win_init(SmWin* w, int fd, size_t cap);
void sm_win_free(SmWin* w);
/* Copy up to n bytes at off. Returns the count (0 at the end of the file or on error). */
size_t sm_win_get(SmWin* w, uint64_t off, uint8_t* out, size_t n);
size_t sm_win_read(void* ctx, uint8_t* buf, size_t max); /* HubInfRead at w->pos */

/* hub_inflate_stream with a 64 KB history, so streams with a 32 KB window need no far callback
 * of the caller. */
int sm_inflate_stream(
    HubInfRead rd,
    void* rctx,
    HubInfWrite wr,
    void* wctx,
    uint32_t flags,
    uint64_t* out_len,
    size_t* in_used);

/* Apply a git delta. *out is malloc'd (len + 1 bytes, the last one 0). 0 or an error (< 0). */
int sm_delta_apply(
    const uint8_t* base,
    size_t blen,
    const uint8_t* d,
    size_t dlen,
    uint8_t** out,
    size_t* olen);
/* The result size a delta announces (0 on a bad header). */
uint64_t sm_delta_result_size(const uint8_t* d, size_t dlen);

/* Pack entry header: type and size; returns the header length (0 = bad). */
size_t sm_entry_hdr_parse(const uint8_t* p, size_t n, int* type, uint64_t* size);
size_t sm_entry_hdr_put(uint8_t* out, int type, uint64_t size);
size_t sm_ofs_put(uint8_t* out, uint64_t ofs); /* OFS_DELTA distance, returns the length */

