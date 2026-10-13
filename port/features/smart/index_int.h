#pragma once
/* Smart HTTP: index_pack.c and index_write.c inside. */
#include "index_pack.h"

typedef struct {
    uint64_t off, size, base; /* base: OFS entry index, REF index in refids */
    uint32_t crc, hdr;
    uint8_t rtype, type; /* type: 0 until resolved */
    uint8_t id[20];
} Ent;

struct IpPack {
    int fd;
    SmWin win;
    uint64_t len;
    Ent* e;
    uint32_t n, nthin;
    uint8_t* refids;
    uint32_t nref;
    SmMap ids;
    uint32_t *kid_first, *kids; /* OFS children (CSR) */
    SmMap refmap; /* REF base id -> first waiting delta */
    uint32_t* ref_next;
    SmBuf needed; /* ids named by commits, trees and tags */
    SmOdb* odb;
    uint8_t trailer[20];
    SmBuf thin; /* ids of the bases appended from the repo */
    char* err;
    size_t errcap;
};

void* ip_fail(IpPack* p, const char* m);
bool ip_pass1(IpPack* p); /* index_pack.c */
bool ip_pass2(IpPack* p);
