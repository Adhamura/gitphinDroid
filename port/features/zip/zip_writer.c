/* Download as ZIP, writer: CRC, chunked output, local headers, data descriptors, the central
 * directory spool and the ZIP64 end records. */
#include "zip_int.h"

/* ---------- crc ---------- */

static uint32_t crc_tab[256];
static bool crc_ready = false;
void zip_crc_init(void) {
    if(crc_ready) return;
    for(uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for(int k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        crc_tab[i] = c;
    }
    crc_ready = true;
}
static uint32_t crc_update(uint32_t crc, const uint8_t* p, size_t n) {
    crc = ~crc;
    while(n--) crc = crc_tab[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

/* ---------- output ---------- */

void zip_z_flush(Z* z) {
    if(!z->on) return;
    if(z->ok) {
        char h[16];
        int hl = snprintf(h, sizeof h, "%zx\r\n", z->on);
        uint8_t* start = z->ob + 8 - hl;
        memcpy(start, h, (size_t)hl);
        z->ob[8 + z->on] = '\r';
        z->ob[8 + z->on + 1] = '\n';
        if(!hub_conn_write(z->r->t, start, (size_t)hl + z->on + 2)) z->ok = false;
    }
    z->on = 0;
    hub_beat();
}

bool zip_z_put(Z* z, const void* data, size_t len) {
    const uint8_t* p = data;
    z->off += len;
    while(len && z->ok) {
        size_t room = OUT_CHUNK - z->on;
        size_t n = len < room ? len : room;
        memcpy(z->ob + 8 + z->on, p, n);
        z->on += n;
        p += n;
        len -= n;
        if(z->on == OUT_CHUNK) zip_z_flush(z);
    }
    return z->ok;
}

static void le16(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}
static void le32(uint8_t* p, uint32_t v) {
    le16(p, v & 0xFFFF);
    le16(p + 2, v >> 16);
}
static void le64(uint8_t* p, uint64_t v) {
    le32(p, (uint32_t)v);
    le32(p + 4, (uint32_t)(v >> 32));
}

/* ---------- central directory spool ---------- */

static bool sp_add(Z* z, const void* data, size_t len) {
    const uint8_t* p = data;
    z->cd_size += len;
    while(len) {
        if(z->sn == z->scap) {
            if(z->sf) {
                if(hub_gitio_write(z->sf, z->sb, z->sn) != z->sn) return false;
                z->sn = 0;
            } else {
                size_t nc = z->scap * 2;
                uint8_t* nb = realloc(z->sb, nc);
                if(!nb) return false;
                z->sb = nb;
                z->scap = nc;
            }
        }
        size_t room = z->scap - z->sn;
        size_t n = len < room ? len : room;
        memcpy(z->sb + z->sn, p, n);
        z->sn += n;
        p += n;
        len -= n;
    }
    return true;
}

/* ---------- entries ---------- */

void zip_dos_time(Z* z, int64_t t) {
    if(t < 315532800) t = 315532800; /* 1980-01-01 */
    int64_t days = t / 86400, rem = t % 86400;
    int64_t zz = days + 719468;
    int64_t era = zz / 146097;
    int64_t doe = zz - era * 146097;
    int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    int64_t y = yoe + era * 400;
    int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    int64_t mp = (5 * doy + 2) / 153;
    int64_t d = doy - (153 * mp + 2) / 5 + 1;
    int64_t m = mp < 10 ? mp + 3 : mp - 9;
    if(m <= 2) y++;
    if(y > 2107) y = 2107;
    z->ddate = (uint16_t)(((y - 1980) << 9) | (m << 5) | d);
    z->dtime = (uint16_t)(((rem / 3600) << 11) | (((rem / 60) % 60) << 5) | ((rem % 60) / 2));
}

static bool is_stored_ext(const char* name, size_t n) {
    static const char* const ext[] = {"png", "jpg", "jpeg", "gif", "webp", "zip", "gz", "xz",
                                      "bz2", "7z",  "mp3",  "mp4", "mkv",  "pdf", "apk", "jar"};
    size_t i = n;
    while(i > 0 && name[i - 1] != '.' && name[i - 1] != '/') i--;
    if(i == 0 || name[i - 1] != '.') return false;
    const char* e = name + i;
    size_t el = n - i;
    for(size_t k = 0; k < sizeof(ext) / sizeof(ext[0]); k++) {
        if(strlen(ext[k]) == el && strncasecmp(e, ext[k], el) == 0) return true;
    }
    return false;
}

/* Write the central directory record of one entry. */
static bool cd_record(
    Z* z,
    const char* name,
    size_t nlen,
    uint16_t flags,
    uint16_t method,
    uint32_t crc,
    uint64_t csize,
    uint64_t usize,
    uint64_t off,
    uint32_t xattr,
    bool z64_local) {
    bool nu = z64_local || usize >= z->lim, nc = z64_local || csize >= z->lim, no = off >= z->lim;
    uint8_t h[46 + 28];
    size_t xl = 0;
    uint8_t x[28];
    if(nu) { le64(x + 4 + xl, usize); xl += 8; }
    if(nc) { le64(x + 4 + xl, csize); xl += 8; }
    if(no) { le64(x + 4 + xl, off); xl += 8; }
    if(xl) { le16(x, 1); le16(x + 2, (uint32_t)xl); }
    bool zip64 = xl != 0;
    memset(h, 0, sizeof h);
    le32(h, 0x02014b50);
    le16(h + 4, (3u << 8) | (zip64 ? 45 : 20));
    le16(h + 6, zip64 ? 45 : 20);
    le16(h + 8, flags);
    le16(h + 10, method);
    le16(h + 12, z->dtime);
    le16(h + 14, z->ddate);
    le32(h + 16, crc);
    le32(h + 20, nc ? 0xFFFFFFFFu : (uint32_t)csize);
    le32(h + 24, nu ? 0xFFFFFFFFu : (uint32_t)usize);
    le16(h + 28, (uint32_t)nlen);
    le16(h + 30, xl ? (uint32_t)(xl + 4) : 0);
    le32(h + 38, xattr);
    le32(h + 42, no ? 0xFFFFFFFFu : (uint32_t)off);
    if(!sp_add(z, h, 46)) return false;
    if(!sp_add(z, name, nlen)) return false;
    if(xl && !sp_add(z, x, xl + 4)) return false;
    z->entries++;
    return true;
}

bool zip_emit_dir(Z* z, const char* name, size_t nlen) {
    uint8_t h[30 + 12];
    memset(h, 0, sizeof h);
    le32(h, 0x04034b50);
    le16(h + 4, 20);
    le16(h + 6, 0x0800);
    le16(h + 10, z->dtime);
    le16(h + 12, z->ddate);
    le16(h + 26, (uint32_t)nlen);
    uint64_t off = z->off;
    /* Info-ZIP unzip takes the central extra length for the local one in its overlap check:
     * pad the local extra to the same length (an unknown block id) when the central record
     * will carry a ZIP64 offset. */
    size_t xl = off >= z->lim ? 12 : 0;
    if(xl) {
        le16(h + 30, 0x7a50);
        le16(h + 32, 8);
        le16(h + 28, 12);
    }
    if(!zip_z_put(z, h, 30) || !zip_z_put(z, name, nlen) || (xl && !zip_z_put(z, h + 30, xl))) return false;
    return cd_record(z, name, nlen, 0x0800, 0, 0, 0, 0, off, (040755u << 16) | 0x10u, false);
}

static bool sink_store(void* ctx, const uint8_t* d, size_t n) {
    Z* z = ctx;
    z->crc = crc_update(z->crc, d, n);
    z->usize += n;
    z->csize += n;
    return zip_z_put(z, d, n);
}

/* Raw deflate out: drop the 2-byte zlib header and hold back the last 4 bytes (adler). */
bool zip_defl_out(void* ctx, const uint8_t* d, size_t n) {
    Z* z = ctx;
    if(z->skip) {
        size_t s = n < z->skip ? n : z->skip;
        d += s;
        n -= s;
        z->skip -= s;
    }
    if(!n) return true;
    size_t total = z->ntail + n;
    if(total <= 4) {
        memcpy(z->tail + z->ntail, d, n);
        z->ntail = total;
        return true;
    }
    size_t emit = total - 4;
    size_t from_tail = emit < z->ntail ? emit : z->ntail;
    if(from_tail) {
        z->csize += from_tail;
        if(!zip_z_put(z, z->tail, from_tail)) return false;
    }
    size_t from_d = emit - from_tail;
    if(from_d) {
        z->csize += from_d;
        if(!zip_z_put(z, d, from_d)) return false;
    }
    /* new tail: last 4 bytes of (tail ++ d) */
    uint8_t nt[4];
    size_t k = 0;
    for(size_t i = total - 4; i < total; i++) nt[k++] = i < z->ntail ? z->tail[i] : d[i - z->ntail];
    memcpy(z->tail, nt, 4);
    z->ntail = 4;
    return true;
}

static bool sink_defl(void* ctx, const uint8_t* d, size_t n) {
    Z* z = ctx;
    z->crc = crc_update(z->crc, d, n);
    z->usize += n;
    hub_beat();
    return hub_deflate_write(z->defl, d, n);
}

bool zip_emit_file(Z* z, const char* name, size_t nlen, uint32_t mode, const HubOid* oid) {
    bool link = (mode & 0170000) == 0120000;
    uint8_t type = 0;
    size_t size = 0;
    if(hub_git_info(z->g2, oid, &type, &size) != HUB_GIT_OK || type != HUB_OBJ_BLOB) return false;
    uint16_t method = (size == 0 || link || is_stored_ext(name, nlen)) ? 0 : 8;
    /* An entry at an offset that needs ZIP64 is a ZIP64 entry too (24-byte descriptor): Info-ZIP
     * unzip assumes that for every entry with a ZIP64 extra in the central directory. */
    bool z64 = (uint64_t)size >= z->lim_entry || z->off >= z->lim;
    uint32_t unix_mode = link ? 0120777u : ((mode & 0100) ? 0100755u : 0100644u);

    uint8_t h[30 + 32];
    memset(h, 0, sizeof h);
    le32(h, 0x04034b50);
    le16(h + 4, z64 ? 45 : 20);
    le16(h + 6, 0x0808);
    le16(h + 8, method);
    le16(h + 10, z->dtime);
    le16(h + 12, z->ddate);
    le16(h + 26, (uint32_t)nlen);
    size_t xl = 0;
    if(z64) {
        le16(h + 30, 1);
        le16(h + 32, 16);
        xl = 20;
    }
    uint64_t off = z->off;
    if(off >= z->lim) { /* see zip_emit_dir: same length as the central extra */
        size_t padn = z64 ? 8 : 12;
        le16(h + 30 + xl, 0x7a50);
        le16(h + 32 + xl, (uint32_t)(padn - 4));
        xl += padn;
    }
    le16(h + 28, (uint32_t)xl);
    if(!zip_z_put(z, h, 30) || !zip_z_put(z, name, nlen) || (xl && !zip_z_put(z, h + 30, xl))) return false;

    z->crc = 0;
    z->usize = 0;
    z->csize = 0;
    if(link) {
        uint8_t t;
        uint8_t* data = NULL;
        size_t n = 0;
        int rc = hub_git_read(z->g2, oid, &t, &data, &n);
        if(rc == HUB_GIT_OK) {
            bool ok = sink_store(z, data, n);
            hub_git_free(data);
            if(!ok) return false;
        } else {
            return false;
        }
    } else if(size) {
        uint8_t t;
        size_t sz;
        if(method == 8) {
            z->skip = 2;
            z->ntail = 0;
            hub_deflate_reset(z->defl, zip_defl_out, z);
            if(hub_git_stream(z->g2, oid, sink_defl, z, &t, &sz) != HUB_GIT_OK) return false;
            if(!hub_deflate_finish(z->defl)) return false;
        } else {
            if(hub_git_stream(z->g2, oid, sink_store, z, &t, &sz) != HUB_GIT_OK) return false;
        }
    }
    if(z->usize != size) return false;

    uint8_t dd[24];
    le32(dd, 0x08074b50);
    le32(dd + 4, z->crc);
    size_t dl;
    if(z64) {
        le64(dd + 8, z->csize);
        le64(dd + 16, z->usize);
        dl = 24;
    } else {
        le32(dd + 8, (uint32_t)z->csize);
        le32(dd + 12, (uint32_t)z->usize);
        dl = 16;
    }
    if(!zip_z_put(z, dd, dl)) return false;
    return cd_record(
        z, name, nlen, 0x0808, method, z->crc, z->csize, z->usize, off, unix_mode << 16, z64);
}

bool zip_z_central_end(Z* z) {
    uint64_t cd_off = z->off;
    /* read the spool back */
    if(z->sf) {
        if(hub_gitio_write(z->sf, z->sb, z->sn) != z->sn) return false;
        z->sn = 0;
        if(!hub_gitio_seek(z->sf, 0)) return false;
        uint64_t left = z->cd_size;
        while(left) {
            size_t want = left < z->scap ? (size_t)left : z->scap;
            size_t n = hub_gitio_read(z->sf, z->sb, want);
            if(n == 0) return false;
            if(!zip_z_put(z, z->sb, n)) return false;
            left -= n;
        }
    } else if(z->sn) {
        if(!zip_z_put(z, z->sb, z->sn)) return false;
    }
    uint64_t n = z->entries, cds = z->cd_size;
    bool z64 = n >= z->ent_lim || cd_off >= z->lim || cds >= z->lim;
    if(z64) {
        uint8_t e[56 + 20];
        memset(e, 0, sizeof e);
        le32(e, 0x06064b50);
        le64(e + 4, 44);
        le16(e + 12, (3u << 8) | 45);
        le16(e + 14, 45);
        le64(e + 24, n);
        le64(e + 32, n);
        le64(e + 40, cds);
        le64(e + 48, cd_off);
        uint64_t z64_off = z->off;
        le32(e + 56, 0x07064b50);
        le32(e + 60, 0);
        le64(e + 64, z64_off);
        le32(e + 72, 1);
        if(!zip_z_put(z, e, 76)) return false;
    }
    uint8_t e[22];
    memset(e, 0, sizeof e);
    le32(e, 0x06054b50);
    uint32_t n16 = (z64 || n >= 0xFFFF) ? 0xFFFF : (uint32_t)n;
    le16(e + 8, n16);
    le16(e + 10, n16);
    le32(e + 12, z64 ? 0xFFFFFFFFu : (uint32_t)cds);
    le32(e + 16, z64 ? 0xFFFFFFFFu : (uint32_t)cd_off);
    return zip_z_put(z, e, 22);
}
