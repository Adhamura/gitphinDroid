#pragma once
/* Smart HTTP: chunked answer writer, pkt-lines, side band; pkt-line reader over memory. */
#include "hub_req.h"
#include "util.h"

/* ---------- answer: chunked HTTP, pkt-lines, side band ---------- */

typedef struct {
    HubReq* r;
    uint8_t* buf;
    size_t n;
    bool ok;
    bool started;
    size_t band_max; /* data bytes per side-band packet; 0: no side band */
    uint64_t sent;
} SmOut;
bool sm_out_begin(SmOut* o, HubReq* r, const char* ctype);
void sm_out_raw(SmOut* o, const void* d, size_t n);
void sm_out_pkt(SmOut* o, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
void sm_out_pkt_bytes(SmOut* o, const void* d, size_t n);
void sm_out_flushpkt(SmOut* o);
void sm_out_band(SmOut* o, int band, const void* d, size_t n);
void sm_out_push(SmOut* o); /* send what is buffered now */
bool sm_out_end(SmOut* o);
/* a side-band message (band 2 progress, band 3 error) when side band is on; error also without */
void sm_out_progress(SmOut* o, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

/* ---------- pkt-lines in memory ---------- */

typedef struct {
    const uint8_t* p;
    size_t n, pos;
} SmPktIn;
/* 1 = data line (LF removed, *line 0-terminated copy in buf), 0 = flush, -1 = end, -2 = bad */
int sm_pkt_next(SmPktIn* in, char* buf, size_t cap, size_t* len);

