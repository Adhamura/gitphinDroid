/* The Flipper Storage API on POSIX files. "/ext/..." is the server root. */
#include <storage/storage.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

#define PORT_PATH_MAX 1024

struct Storage {
    int unused;
};
static Storage the_storage;
static char root[PORT_PATH_MAX];
static char git_dir[PORT_PATH_MAX]; /* "" = the repos stay under root */
#define GIT_PREFIX "/ext/apps_data/gitphin/git"

struct File {
    int fd;
    DIR* d;
    int err; /* errno of the last failed call */
    char path[PORT_PATH_MAX]; /* host path of an open folder */
};

void port_storage_set_root(const char* r) {
    snprintf(root, sizeof root, "%s", r);
    size_t n = strlen(root);
    while(n > 1 && root[n - 1] == '/') root[--n] = '\0';
}

void port_storage_set_git_dir(const char* d) {
    snprintf(git_dir, sizeof git_dir, "%s", d ? d : "");
    size_t n = strlen(git_dir);
    while(n > 1 && git_dir[n - 1] == '/') git_dir[--n] = '\0';
}

Storage* port_storage(void) {
    return &the_storage;
}

/* Flipper path -> host path. NULL (errno ENOENT) for anything outside /ext or with "..". */
static const char* map(const char* path, char* buf, size_t cap) {
    if(!path || strncmp(path, "/ext", 4) != 0 || (path[4] != '\0' && path[4] != '/')) {
        errno = ENOENT;
        return NULL;
    }
    for(const char* p = path; (p = strstr(p, "..")) != NULL; p += 2) {
        bool start = p == path || p[-1] == '/';
        bool end = p[2] == '\0' || p[2] == '/';
        if(start && end) {
            errno = EACCES;
            return NULL;
        }
    }
    size_t gl = sizeof(GIT_PREFIX) - 1;
    int n;
    if(git_dir[0] && !strncmp(path, GIT_PREFIX, gl) && (path[gl] == '\0' || path[gl] == '/'))
        n = snprintf(buf, cap, "%s%s", git_dir, path + gl); /* the repos folder chosen in the app */
    else
        n = snprintf(buf, cap, "%s%s", root, path + 4);
    if(n < 0 || (size_t)n >= cap) {
        errno = ENAMETOOLONG;
        return NULL;
    }
    return buf;
}

/* Port only (smart HTTP needs POSIX files with 64-bit offsets): the host path of a Flipper path. */
const char* port_storage_host_path(const char* path, char* buf, size_t cap) {
    return map(path, buf, cap);
}

static FS_Error err_of(int e) {
    switch(e) {
    case 0:
        return FSE_OK;
    case ENOENT:
    case ENOTDIR:
        return FSE_NOT_EXIST;
    case EEXIST:
    case ENOTEMPTY:
        return FSE_EXIST;
    case EACCES:
    case EPERM:
    case EROFS:
        return FSE_DENIED;
    case ENAMETOOLONG:
    case EINVAL:
        return FSE_INVALID_NAME;
    default:
        return FSE_INTERNAL;
    }
}

const char* storage_error_get_desc(FS_Error e) {
    switch(e) {
    case FSE_OK:
        return "OK";
    case FSE_NOT_READY:
        return "storage not ready";
    case FSE_EXIST:
        return "file/dir already exist";
    case FSE_NOT_EXIST:
        return "file/dir not exist";
    case FSE_INVALID_PARAMETER:
        return "invalid parameter";
    case FSE_DENIED:
        return "access denied";
    case FSE_INVALID_NAME:
        return "invalid name/path";
    case FSE_NOT_IMPLEMENTED:
        return "function not implemented";
    case FSE_ALREADY_OPEN:
        return "file is already open";
    default:
        return "internal error";
    }
}

/* ---- files ---- */

File* storage_file_alloc(Storage* s) {
    (void)s;
    File* f = calloc(1, sizeof(File));
    furi_check(f);
    f->fd = -1;
    return f;
}

void storage_file_free(File* f) {
    if(!f) return;
    if(f->fd >= 0) close(f->fd);
    if(f->d) closedir(f->d);
    free(f);
}

bool storage_file_open(File* f, const char* path, FS_AccessMode a, FS_OpenMode m) {
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) {
        f->err = errno;
        return false;
    }
    int fl = O_CLOEXEC;
    fl |= a == FSAM_READ_WRITE ? O_RDWR : a == FSAM_WRITE ? O_WRONLY : O_RDONLY;
    switch(m) {
    case FSOM_OPEN_ALWAYS:
        fl |= O_CREAT;
        break;
    case FSOM_OPEN_APPEND:
        fl |= O_CREAT | O_APPEND;
        break;
    case FSOM_CREATE_NEW:
        fl |= O_CREAT | O_EXCL;
        break;
    case FSOM_CREATE_ALWAYS:
        fl |= O_CREAT | O_TRUNC;
        break;
    default:
        break;
    }
    f->fd = open(p, fl, 0644);
    if(f->fd < 0) {
        f->err = errno;
        return false;
    }
    struct stat st;
    if(fstat(f->fd, &st) == 0 && S_ISDIR(st.st_mode)) {
        close(f->fd);
        f->fd = -1;
        f->err = EISDIR;
        return false;
    }
    f->err = 0;
    return true;
}

bool storage_file_close(File* f) {
    if(f->fd < 0) return false;
    close(f->fd);
    f->fd = -1;
    return true;
}

size_t storage_file_read(File* f, void* buf, size_t n) {
    if(f->fd < 0) return 0;
    size_t got = 0;
    while(got < n) {
        ssize_t r = read(f->fd, (char*)buf + got, n - got);
        if(r < 0 && errno == EINTR) continue;
        if(r < 0) f->err = errno;
        if(r <= 0) break;
        got += (size_t)r;
    }
    return got;
}

size_t storage_file_write(File* f, const void* buf, size_t n) {
    if(f->fd < 0) return 0;
    size_t put = 0;
    while(put < n) {
        ssize_t r = write(f->fd, (const char*)buf + put, n - put);
        if(r < 0 && errno == EINTR) continue;
        if(r < 0) f->err = errno;
        if(r <= 0) break;
        put += (size_t)r;
    }
    return put;
}

bool storage_file_seek(File* f, uint32_t offset, bool from_start) {
    if(f->fd < 0) return false;
    off_t r = lseek(f->fd, (off_t)offset, from_start ? SEEK_SET : SEEK_CUR);
    if(r < 0) f->err = errno;
    return r >= 0;
}

uint64_t storage_file_size(File* f) {
    struct stat st;
    if(f->fd < 0 || fstat(f->fd, &st) != 0) return 0;
    return (uint64_t)st.st_size;
}

bool storage_file_sync(File* f) {
    if(f->fd < 0) return false;
    if(fsync(f->fd) != 0) {
        f->err = errno;
        return false;
    }
    return true;
}

const char* storage_file_get_error_desc(File* f) {
    return storage_error_get_desc(err_of(f->err));
}

/* ---- folders ---- */

bool storage_dir_open(File* f, const char* path) {
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) {
        f->err = errno;
        return false;
    }
    f->d = opendir(p);
    if(!f->d) {
        f->err = errno;
        return false;
    }
    snprintf(f->path, sizeof f->path, "%s", p);
    return true;
}

bool storage_dir_read(File* f, FileInfo* info, char* name, uint16_t cap) {
    if(!f->d) return false;
    struct dirent* e;
    while((e = readdir(f->d))) {
        if(!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char full[PORT_PATH_MAX + 256];
        snprintf(full, sizeof full, "%s/%s", f->path, e->d_name);
        struct stat st;
        if(stat(full, &st) != 0) continue;
        if(info) {
            info->size = S_ISDIR(st.st_mode) ? 0 : (uint64_t)st.st_size;
            info->flags = S_ISDIR(st.st_mode) ? FSF_DIRECTORY : 0;
        }
        if(name && cap) snprintf(name, cap, "%s", e->d_name);
        return true;
    }
    return false;
}

bool storage_dir_close(File* f) {
    if(!f->d) return false;
    closedir(f->d);
    f->d = NULL;
    return true;
}

bool storage_dir_exists(Storage* s, const char* path) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    struct stat st;
    return p && stat(p, &st) == 0 && S_ISDIR(st.st_mode);
}

/* ---- common ---- */

FS_Error storage_common_stat(Storage* s, const char* path, FileInfo* info) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) return err_of(errno);
    struct stat st;
    if(stat(p, &st) != 0) return err_of(errno);
    if(info) {
        info->size = S_ISDIR(st.st_mode) ? 0 : (uint64_t)st.st_size;
        info->flags = S_ISDIR(st.st_mode) ? FSF_DIRECTORY : 0;
    }
    return FSE_OK;
}

FS_Error storage_common_remove(Storage* s, const char* path) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) return err_of(errno);
    if(unlink(p) == 0) return FSE_OK;
    if(errno == EISDIR || errno == EPERM) {
        if(rmdir(p) == 0) return FSE_OK;
        return errno == ENOTEMPTY || errno == EEXIST ? FSE_DENIED : err_of(errno);
    }
    return err_of(errno);
}

FS_Error storage_common_rename(Storage* s, const char* from, const char* to) {
    (void)s;
    char b1[PORT_PATH_MAX], b2[PORT_PATH_MAX];
    const char* a = map(from, b1, sizeof b1);
    if(!a) return err_of(errno);
    const char* b = map(to, b2, sizeof b2);
    if(!b) return err_of(errno);
    struct stat st;
    if(lstat(a, &st) != 0) return err_of(errno);
    if(lstat(b, &st) == 0) return FSE_EXIST; /* like the firmware: rename does not replace */
    return rename(a, b) == 0 ? FSE_OK : err_of(errno);
}

FS_Error storage_common_mkdir(Storage* s, const char* path) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) return err_of(errno);
    return mkdir(p, 0755) == 0 ? FSE_OK : err_of(errno);
}

FS_Error storage_common_fs_info(Storage* s, const char* path, uint64_t* total, uint64_t* free_) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) return err_of(errno);
    struct statvfs v;
    if(git_dir[0]) p = git_dir; /* the space that matters is where the repos go */
    if(statvfs(p, &v) != 0) return err_of(errno);
    if(total) *total = (uint64_t)v.f_blocks * v.f_frsize;
    if(free_) *free_ = (uint64_t)v.f_bavail * v.f_frsize;
    return FSE_OK;
}

/* Makes the missing parents too (the firmware call makes one level; more never hurts here). */
bool storage_simply_mkdir(Storage* s, const char* path) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p) return false;
    char tmp[PORT_PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", p);
    for(char* c = tmp + 1; *c; c++) { /* existing parents just fail with EEXIST */
        if(*c != '/') continue;
        *c = '\0';
        mkdir(tmp, 0755);
        *c = '/';
    }
    if(mkdir(tmp, 0755) == 0) return true;
    struct stat st;
    return errno == EEXIST && stat(tmp, &st) == 0 && S_ISDIR(st.st_mode);
}

static bool rm_tree(const char* p) {
    struct stat st;
    if(lstat(p, &st) != 0) return errno == ENOENT;
    if(!S_ISDIR(st.st_mode)) return unlink(p) == 0;
    DIR* d = opendir(p);
    if(!d) return false;
    bool ok = true;
    struct dirent* e;
    while((e = readdir(d))) {
        if(!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char full[PORT_PATH_MAX + 256];
        int n = snprintf(full, sizeof full, "%s/%s", p, e->d_name);
        if(n < 0 || (size_t)n >= sizeof full || !rm_tree(full)) ok = false;
    }
    closedir(d);
    return rmdir(p) == 0 && ok;
}

bool storage_simply_remove_recursive(Storage* s, const char* path) {
    (void)s;
    char buf[PORT_PATH_MAX];
    const char* p = map(path, buf, sizeof buf);
    if(!p || strcmp(p, root) == 0 || (git_dir[0] && strcmp(p, git_dir) == 0))
        return false; /* never the whole root or the whole repos folder */
    return rm_tree(p);
}
