#pragma once
/* POSIX stand-in for the parts of the Flipper furi.h that the Gitphin core uses.
 * Implemented in port/port_furi.c. */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

#define UNUSED(x)   (void)(x)
#define COUNT_OF(x) (sizeof(x) / sizeof((x)[0]))
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

void port_log(char level, const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
#define FURI_LOG_E(tag, ...) port_log('E', tag, __VA_ARGS__)
#define FURI_LOG_W(tag, ...) port_log('W', tag, __VA_ARGS__)
#define FURI_LOG_I(tag, ...) port_log('I', tag, __VA_ARGS__)
#define FURI_LOG_D(tag, ...) ((void)0)

#define furi_check(x)  ((x) ? (void)0 : abort())
#define furi_assert(x) furi_check(x)

/* "/ext/..." is the server root (port_storage_set_root). */
#define EXT_PATH(p) "/ext/" p

/* ---- kernel ---- */

typedef enum { FuriStatusOk = 0, FuriStatusErrorTimeout = -2, FuriStatusError = -1 } FuriStatus;

uint32_t furi_get_tick(void); /* ms since start, wraps like the firmware tick */
static inline uint32_t furi_ms_to_ticks(uint32_t ms) { return ms; }
static inline uint32_t furi_kernel_get_tick_frequency(void) { return 1000; }

/* The phone has no 256 KB heap: these report plenty, so the guards of the core never refuse. */
size_t memmgr_get_free_heap(void);
size_t memmgr_heap_get_max_free_block(void);

typedef struct FuriMutex FuriMutex;
typedef enum { FuriMutexTypeNormal, FuriMutexTypeRecursive } FuriMutexType;
FuriMutex* furi_mutex_alloc(FuriMutexType type);
void furi_mutex_free(FuriMutex* m);
FuriStatus furi_mutex_acquire(FuriMutex* m, uint32_t timeout_ms);
FuriStatus furi_mutex_release(FuriMutex* m);

/* ---- FuriString ---- */

typedef struct {
    char* b;
    size_t n, cap;
} FuriString;

static inline void fs_grow(FuriString* s, size_t need) {
    if(need + 1 <= s->cap) return;
    size_t c = s->cap ? s->cap : 16;
    while(c < need + 1) c *= 2;
    char* nb = realloc(s->b, c);
    furi_check(nb);
    s->b = nb;
    s->cap = c;
}
static inline FuriString* furi_string_alloc(void) {
    FuriString* s = calloc(1, sizeof(*s));
    furi_check(s);
    fs_grow(s, 0);
    s->b[0] = 0;
    return s;
}
static inline void furi_string_free(FuriString* s) {
    if(!s) return;
    free(s->b);
    free(s);
}
static inline const char* furi_string_get_cstr(const FuriString* s) { return s->b; }
static inline size_t furi_string_size(const FuriString* s) { return s->n; }
static inline void furi_string_reset(FuriString* s) {
    s->n = 0;
    s->b[0] = 0;
}
static inline void furi_string_reserve(FuriString* s, size_t n) { fs_grow(s, n); }
static inline void furi_string_push_back(FuriString* s, char c) {
    fs_grow(s, s->n + 1);
    s->b[s->n++] = c;
    s->b[s->n] = 0;
}
static inline void furi_string_cat_str(FuriString* s, const char* t) {
    size_t l = strlen(t);
    fs_grow(s, s->n + l);
    memcpy(s->b + s->n, t, l + 1);
    s->n += l;
}
static inline void furi_string_set_str(FuriString* s, const char* t) {
    furi_string_reset(s);
    furi_string_cat_str(s, t);
}
static inline void furi_string_cat(FuriString* s, const FuriString* t) {
    fs_grow(s, s->n + t->n);
    memcpy(s->b + s->n, t->b, t->n + 1);
    s->n += t->n;
}
static inline void furi_string_set_fs(FuriString* s, const FuriString* t) {
    if(s == t) return;
    furi_string_reset(s);
    furi_string_cat(s, t);
}
/* Like the firmware macro: the source is a C string or a FuriString. */
#define furi_string_set(s, t) \
    _Generic((t), char*: furi_string_set_str, const char*: furi_string_set_str, default: furi_string_set_fs)(s, t)
static inline FuriString* furi_string_alloc_set(const char* t) {
    FuriString* s = furi_string_alloc();
    furi_string_cat_str(s, t);
    return s;
}
static inline void furi_string_vcat(FuriString* s, const char* f, va_list ap) {
    va_list c;
    va_copy(c, ap);
    int l = vsnprintf(NULL, 0, f, c);
    va_end(c);
    if(l <= 0) return;
    fs_grow(s, s->n + (size_t)l);
    vsnprintf(s->b + s->n, (size_t)l + 1, f, ap);
    s->n += (size_t)l;
}
static inline void furi_string_cat_printf(FuriString* s, const char* f, ...) {
    va_list ap;
    va_start(ap, f);
    furi_string_vcat(s, f, ap);
    va_end(ap);
}
static inline void furi_string_printf(FuriString* s, const char* f, ...) {
    furi_string_reset(s);
    va_list ap;
    va_start(ap, f);
    furi_string_vcat(s, f, ap);
    va_end(ap);
}
static inline FuriString* furi_string_alloc_printf(const char* f, ...) {
    FuriString* s = furi_string_alloc();
    va_list ap;
    va_start(ap, f);
    furi_string_vcat(s, f, ap);
    va_end(ap);
    return s;
}
static inline void furi_string_set_n(FuriString* s, const FuriString* t, size_t off, size_t n) {
    if(off > t->n) off = t->n;
    if(n > t->n - off) n = t->n - off;
    char* tmp = malloc(n + 1);
    furi_check(tmp);
    memcpy(tmp, t->b + off, n); /* s and t may be the same string */
    furi_string_reset(s);
    fs_grow(s, n);
    memcpy(s->b, tmp, n);
    s->n = n;
    s->b[n] = 0;
    free(tmp);
}
#define FURI_STRING_FAILURE ((size_t) - 1)
static inline size_t furi_string_search_char(const FuriString* s, char c, size_t start) {
    if(start >= s->n) return FURI_STRING_FAILURE;
    const char* p = memchr(s->b + start, c, s->n - start);
    return p ? (size_t)(p - s->b) : FURI_STRING_FAILURE;
}
static inline char furi_string_get_char(const FuriString* s, size_t i) { return s->b[i]; }
