#pragma once
/* Smart HTTP: request body (Content-Length or chunked, optional gzip). */
#include "hub_req.h"
#include "util.h"

/* ---------- request body ---------- */

typedef struct {
    HubReq* r;
    bool chunked, gzip;
    uint64_t left; /* Content-Length mode: bytes still to come */
    uint64_t chunk_left;
    int st;
    bool eof, err;
    const uint8_t* pre;
    size_t pre_len;
    uint8_t* raw;
    size_t rpos, rlen;
} SmBody;
bool sm_body_begin(SmBody* b, HubReq* r);
/* Body bytes after the transfer coding (chunked), before the content coding (gzip). */
size_t sm_body_read(SmBody* b, uint8_t* out, size_t max);
/* All of the body, gunzipped when it was gzip, to feed (false from feed stops). */
bool sm_body_pump(SmBody* b, bool (*feed)(void*, const uint8_t*, size_t), void* ctx);
void sm_body_end(SmBody* b); /* reads what is left, frees */

