#pragma once
/* Smart HTTP: refs (loose + packed-refs), HEAD, name rules, small atomic file writes. */
#include "util.h"

/* ---------- refs ---------- */

typedef struct {
    char* name;
    uint8_t id[20];
} SmRef;
typedef struct {
    SmRef* v;
    size_t n, cap;
} SmRefs;
/* refs/heads and refs/tags: loose files first, then packed-refs; sorted by name */
bool sm_refs_load(const char* repo, SmRefs* l);
void sm_refs_free(SmRefs* l);
const SmRef* sm_refs_find(const SmRefs* l, const char* name);
/* HEAD: target of "ref: x" into sym ("" when detached or unreadable); id when it resolves. */
bool sm_head(const char* repo, const SmRefs* l, char* sym, size_t cap, uint8_t id[20]);
bool sm_ref_ok(const char* ref); /* the core's rules: refs/heads/ or refs/tags/ */
/* Value of one ref (loose, else packed-refs). false when it does not exist. */
bool sm_ref_read(const char* repo, const char* ref, uint8_t id[20]);
bool sm_mkdirs(const char* path, size_t from); /* folders above the last '/', starting at from */
/* Write a small file through "<path>.part" and rename (fsync first). */
bool sm_write_file(const char* path, const void* data, size_t len);

