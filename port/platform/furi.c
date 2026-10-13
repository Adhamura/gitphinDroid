/* Kernel, HAL and log stand-ins for the Gitphin core on Android and Linux. */
#include <furi.h>
#include <furi_hal.h>

#include <pthread.h>
#include <errno.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include "port.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

/* ---- log ---- */

void port_log(char level, const char* tag, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
#ifdef __ANDROID__
    int prio = level == 'E' ? ANDROID_LOG_ERROR : level == 'W' ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_vprint(prio, tag, fmt, ap);
#else
    fprintf(stderr, "[%c] %s: ", level, tag);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
#endif
    va_end(ap);
}

/* ---- kernel ---- */

uint32_t furi_get_tick(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)((uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u);
}

/* Free memory = MemAvailable of /proc/meminfo (cached for 1 s). The core uses it in guards
 * (hub_heap_ok), so it never goes below a floor of 64 MiB, and it stays small enough for 32-bit size_t sums. */
#define PORT_HEAP_FLOOR ((size_t)64 << 20)
#define PORT_HEAP_FALLBACK ((size_t)256 << 20)

static size_t port_heap_cap(void) {
    return (SIZE_MAX == 0xFFFFFFFFu) ? (((size_t)1 << 30) - 1) : (SIZE_MAX / 2);
}

static size_t port_mem_available(void) {
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    static size_t cached = 0;
    static uint32_t cached_at = 0;
    static int have = 0;
    uint32_t now = furi_get_tick();
    pthread_mutex_lock(&lock);
    if(have && (uint32_t)(now - cached_at) < 1000u) {
        size_t v = cached;
        pthread_mutex_unlock(&lock);
        return v;
    }
    size_t v = PORT_HEAP_FALLBACK;
    FILE* f = fopen("/proc/meminfo", "r");
    if(f) {
        char line[128];
        while(fgets(line, sizeof(line), f)) {
            unsigned long long kib;
            if(sscanf(line, "MemAvailable: %llu kB", &kib) == 1) {
                unsigned long long cap = port_heap_cap();
                unsigned long long bytes = kib * 1024ull;
                if(kib > (~0ull) / 1024ull || bytes > cap) bytes = cap;
                v = (size_t)bytes;
                break;
            }
        }
        fclose(f);
    }
    if(v < PORT_HEAP_FLOOR) v = PORT_HEAP_FLOOR;
    cached = v;
    cached_at = now;
    have = 1;
    pthread_mutex_unlock(&lock);
    return v;
}

size_t memmgr_get_free_heap(void) {
    return port_mem_available();
}
size_t memmgr_heap_get_max_free_block(void) {
    size_t v = port_mem_available() / 2;
    if(v > ((size_t)128 << 20)) v = (size_t)128 << 20;
    if(v < PORT_HEAP_FLOOR) v = PORT_HEAP_FLOOR;
    return v;
}

struct FuriMutex {
    pthread_mutex_t m;
};

FuriMutex* furi_mutex_alloc(FuriMutexType type) {
    FuriMutex* m = malloc(sizeof(FuriMutex));
    furi_check(m);
    pthread_mutexattr_t a;
    pthread_mutexattr_init(&a);
    if(type == FuriMutexTypeRecursive) pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&m->m, &a);
    pthread_mutexattr_destroy(&a);
    return m;
}

void furi_mutex_free(FuriMutex* m) {
    if(!m) return;
    pthread_mutex_destroy(&m->m);
    free(m);
}

FuriStatus furi_mutex_acquire(FuriMutex* m, uint32_t timeout_ms) {
    uint32_t start = furi_get_tick();
    for(;;) {
        if(pthread_mutex_trylock(&m->m) == 0) return FuriStatusOk;
        if(furi_get_tick() - start >= timeout_ms) return FuriStatusErrorTimeout;
        usleep(1000);
    }
}

FuriStatus furi_mutex_release(FuriMutex* m) {
    return pthread_mutex_unlock(&m->m) == 0 ? FuriStatusOk : FuriStatusError;
}

/* ---- HAL ---- */

uint32_t furi_hal_rtc_get_timestamp(void) {
    return (uint32_t)time(NULL);
}

uint32_t furi_hal_random_get(void) {
#if defined(__ANDROID__) || defined(__APPLE__)
    return arc4random();
#else
    uint32_t v = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if(fd >= 0) {
        if(read(fd, &v, sizeof v) != (ssize_t)sizeof v) v = 0;
        close(fd);
    }
    if(!v) v = (uint32_t)time(NULL) ^ (uint32_t)clock() ^ furi_get_tick() * 2654435761u;
    return v;
#endif
}

static pthread_mutex_t dev_lock = PTHREAD_MUTEX_INITIALIZER;
static char dev_name[64] = "Android";
static char dev_version[64] = "gitphinDroid";
static volatile uint8_t dev_battery = 100;
static volatile bool dev_charging;

void port_set_device(const char* name, const char* version, int battery_pct, bool charging) {
    pthread_mutex_lock(&dev_lock);
    if(name) snprintf(dev_name, sizeof dev_name, "%s", name);
    if(version) snprintf(dev_version, sizeof dev_version, "%s", version);
    pthread_mutex_unlock(&dev_lock);
    dev_battery = (uint8_t)(battery_pct < 0 ? 0 : battery_pct > 100 ? 100 : battery_pct);
    dev_charging = charging;
}

uint8_t furi_hal_power_get_pct(void) {
    return dev_battery;
}
bool furi_hal_power_is_charging(void) {
    return dev_charging;
}

/* The worker is the only reader; the strings change only between requests in practice,
 * and a torn read can only garble the text of one /api/info answer. */
const char* furi_hal_version_get_name_ptr(void) {
    return dev_name;
}

struct Version {
    int unused;
};
static const Version the_version;
const Version* furi_hal_version_get_firmware_version(void) {
    return &the_version;
}
const char* version_get_version(const Version* v) {
    (void)v;
    return dev_version;
}
