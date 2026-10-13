#pragma once
/* Smart HTTP: object store reader (packs with 64-bit offsets, loose objects, deltas). */
#include "util.h"

/* ---------- object store ---------- */


typedef struct {
    int type; /* raw type: 1..4, SM_OFS_DELTA, SM_REF_DELTA */
    uint64_t size; /* object size, or the delta data size */
    uint32_t hdr_len; /* bytes before the zlib data (with the delta base) */
    uint64_t base_off; /* OFS_DELTA */
    uint8_t base_id[20]; /* REF_DELTA */
} SmEnt;

typedef struct SmOdb SmOdb;
SmOdb* sm_odb_open(const char* repo); /* host path of the bare repo */
void sm_odb_close(SmOdb* o);
int sm_odb_npacks(const SmOdb* o);
/* Where a packed object is (the first pack that has it). */
bool sm_odb_find(SmOdb* o, const uint8_t id[20], int* pack, uint64_t* off);
bool sm_odb_has(SmOdb* o, const uint8_t id[20]); /* packed or loose */
/* Type of an object (deltas followed to the base). 0 = ok. */
int sm_odb_type(SmOdb* o, const uint8_t id[20], int* type);
/* Whole object (any size that fits in RAM). *data is malloc'd, one 0 byte after the end. */
int sm_odb_read(SmOdb* o, const uint8_t id[20], int* type, uint8_t** data, size_t* len);
int sm_odb_read_at(SmOdb* o, int pack, uint64_t off, int* type, uint8_t** data, size_t* len);
/* Raw entry of a pack. */
int sm_odb_entry(SmOdb* o, int pack, uint64_t off, SmEnt* e);
/* End of the entry at off (start of the next one, or the trailer). 0 on error. */
uint64_t sm_odb_entry_end(SmOdb* o, int pack, uint64_t off);
/* Id of the entry at off. */
bool sm_odb_id_at(SmOdb* o, int pack, uint64_t off, uint8_t id[20]);
/* Copy raw pack bytes [from, to) to a sink. */
bool sm_odb_copy(SmOdb* o, int pack, uint64_t from, uint64_t to, bool (*sink)(void*, const uint8_t*, size_t), void* ctx);
/* Loose objects: header, then the content streamed to wr. */
int sm_odb_loose_info(SmOdb* o, const uint8_t id[20], int* type, uint64_t* size);
int sm_odb_loose_stream(SmOdb* o, const uint8_t id[20], HubInfWrite wr, void* ctx);


/* inside the store (odb.c, odb_loose.c) */
const char* sm_odb_repo(const SmOdb* o);
void sm_loose_path(const SmOdb* o, const uint8_t id[20], char* out, size_t cap);
int sm_loose_read(SmOdb* o, const uint8_t id[20], int* type, uint8_t** data, size_t* len);
