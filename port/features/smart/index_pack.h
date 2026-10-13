#pragma once
/* Smart HTTP: index a pushed pack (git index-pack --fix-thin), write its .idx v2. */
#include "odb.h"

typedef struct IpPack IpPack;

/* Index the pack in fd (the whole file, trailer checked by the caller). Every entry is
 * inflated, deltas (OFS and REF, bases in the pack or in the repo) are resolved, ids and CRC-32
 * are computed. Bases that came from the repo (a thin pack) are appended to the file, and the
 * count and the trailer are rewritten. NULL on error (the reason in err). */
IpPack* ip_index(int fd, SmOdb* repo, char* err, size_t errcap);
void ip_free(IpPack* p);
uint32_t ip_count(const IpPack* p);
uint32_t ip_thin(const IpPack* p); /* bases appended from the repo */
/* Type of an object of the pack (0 when it is not in the pack). */
int ip_type(const IpPack* p, const uint8_t id[20]);
/* Every object that a commit, tree or tag of the pack names is in the pack or in the repo.
 * false: missing gets the first one that is not. */
bool ip_connected(const IpPack* p, SmOdb* repo, uint8_t missing[20]);
void ip_trailer(const IpPack* p, uint8_t sha[20]);
/* The .idx v2 (sorted names, CRC-32, 31/64-bit offsets, checksums), fsync'd. */
bool ip_write_idx(IpPack* p, const char* path);
