#pragma once
/* POSIX stand-in for the Flipper Storage API (port/port_storage.c).
 * Paths are Flipper paths: "/ext/..." maps to the server root, nothing else is reachable.
 * The semantics follow the firmware: rename does not replace, remove takes files and empty
 * folders, mkdir does not make parents (FSE_NOT_EXIST when the parent is missing). */
#include <furi.h>

typedef struct Storage Storage;
typedef struct File File;

typedef struct {
    uint64_t size;
    uint8_t flags;
} FileInfo;
#define FSF_DIRECTORY 1
static inline bool file_info_is_dir(const FileInfo* i) { return i->flags & FSF_DIRECTORY; }

typedef enum {
    FSE_OK = 0,
    FSE_NOT_READY,
    FSE_EXIST,
    FSE_NOT_EXIST,
    FSE_INVALID_PARAMETER,
    FSE_DENIED,
    FSE_INVALID_NAME,
    FSE_INTERNAL,
    FSE_NOT_IMPLEMENTED,
    FSE_ALREADY_OPEN,
} FS_Error;

typedef enum { FSAM_READ = 1, FSAM_WRITE = 2, FSAM_READ_WRITE = 3 } FS_AccessMode;
typedef enum {
    FSOM_OPEN_EXISTING = 1,
    FSOM_OPEN_ALWAYS = 2,
    FSOM_OPEN_APPEND = 4,
    FSOM_CREATE_NEW = 8,
    FSOM_CREATE_ALWAYS = 16,
} FS_OpenMode;

#define STORAGE_EXT_PATH_PREFIX "/ext"
#define RECORD_STORAGE          "storage"

/* Port only: the folder that "/ext" stands for. Call once before the server starts. */
void port_storage_set_root(const char* root);
/* Port only: a folder for the repos ("/ext/apps_data/gitphin/git"), for example on shared storage.
 * NULL or "" = under the root. Call before the server starts. */
void port_storage_set_git_dir(const char* dir);
/* Port only: the Storage* handle (there is one, it carries no state). */
Storage* port_storage(void);
/* Port only: the host path of "/ext/..." (the same mapping as every call below) into buf.
 * NULL (errno set) when the path is outside /ext, has "..", or does not fit. */
const char* port_storage_host_path(const char* path, char* buf, size_t cap);

File* storage_file_alloc(Storage* s);
void storage_file_free(File* f);
bool storage_file_open(File* f, const char* path, FS_AccessMode a, FS_OpenMode m);
bool storage_file_close(File* f);
size_t storage_file_read(File* f, void* buf, size_t n);
size_t storage_file_write(File* f, const void* buf, size_t n);
bool storage_file_seek(File* f, uint32_t offset, bool from_start);
uint64_t storage_file_size(File* f);
bool storage_file_sync(File* f);
const char* storage_file_get_error_desc(File* f);

bool storage_dir_open(File* f, const char* path);
bool storage_dir_read(File* f, FileInfo* info, char* name, uint16_t cap);
bool storage_dir_close(File* f);
bool storage_dir_exists(Storage* s, const char* path);

FS_Error storage_common_stat(Storage* s, const char* path, FileInfo* info);
FS_Error storage_common_remove(Storage* s, const char* path);
FS_Error storage_common_rename(Storage* s, const char* from, const char* to);
FS_Error storage_common_mkdir(Storage* s, const char* path);
FS_Error storage_common_fs_info(Storage* s, const char* path, uint64_t* total, uint64_t* free_);
bool storage_simply_mkdir(Storage* s, const char* path);
bool storage_simply_remove_recursive(Storage* s, const char* path);
const char* storage_error_get_desc(FS_Error e);
