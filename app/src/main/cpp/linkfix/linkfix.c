/*
 * termux-linkfix.c — LD_PRELOAD shim emulating hardlinks with copies.
 *
 * Why: on some Android 15 devices the kernel/SELinux policy denies link(2)/
 * linkat(2) inside the app data directory (touch/write work, link fails with
 * EACCES even as fake root under proot -0, even on the same filesystem).
 * dpkg fundamentally requires hardlinks: every invocation that touches the
 * status database creates the "status-old" backup via link(), and every
 * unpacked file is backed up via link() ("unable to make backup link").
 * Without working link(), dpkg is 100% unusable in the guest.
 *
 * What: interpose link() and linkat(). Try the real call first (passthrough
 * via dlsym(RTLD_NEXT, ...)); only when it fails with EACCES, EPERM, EROFS,
 * EXDEV or ENOSYS — i.e. "links impossible here" — fall back to copying the
 * file contents. A copy preserves dpkg's backup semantics (frozen snapshot
 * for rollback), unlike symlink fakery. Non-regular files are never
 * emulated: the real errno is returned instead.
 *
 * Why stat() too: shadow-utils (groupadd, useradd, adduser, passwd, ...)
 * locks /etc/group, /etc/passwd, /etc/shadow and /etc/gshadow with
 * link(2): it writes a "<file>.<pid>" temp, link()s it to "<file>.lock"
 * and then REQUIRES stat("<file>.<pid>").st_nlink == 2 (lib/commonio.c,
 * check_link_count()). A copy-emulated link leaves the temp at nlink 1, so
 * every shadow lock fails with "lock file already used (nlink: 1)" and the
 * tool exits (dpkg then reports a broken postinst). The emulation therefore
 * also remembers each inode it "linked" in THIS process and reports nlink 2
 * for them through the stat family, which is exactly what shadow (running in
 * the same process) checks. No cross-process state is involved: dpkg/apt and
 * the shadow tools are single-shot processes, and the entries are forgotten
 * at exit. On devices where link(2) works the registry stays empty and stat
 * results are never altered.
 *
 * Scope: regular files only, best effort, single-threaded callers assumed
 * (dpkg/apt). No logging, no stderr output — this runs inside every guest
 * process via LD_PRELOAD.
 *
 * Target: Debian glibc aarch64 (guest rootfs). Uses only ancient stable
 * symbols (openat/read/write/close/fstatat/fchmod/unlink/dlsym) so it loads
 * on any trixie-or-newer glibc.
 *
 * Rebuild (needs aarch64 glibc cross toolchain):
 *   aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra \
 *       -o termux-linkfix.so linkfix.c
 *   file termux-linkfix.so  # must say: ELF shared object, ARM aarch64
 *   readelf -d termux-linkfix.so | grep NEEDED  # must list only libc.so.6
 *
 * SPDX-License-Identifier: MIT
 */
#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define COPY_BUF_SIZE 65536

typedef int (*link_fn_t)(const char *oldpath, const char *newpath);
typedef int (*linkat_fn_t)(int olddirfd, const char *oldpath,
                           int newdirfd, const char *newpath, int flags);

typedef int (*stat_fn_t)(const char *path, struct stat *buf);
typedef int (*lstat_fn_t)(const char *path, struct stat *buf);
typedef int (*fstat_fn_t)(int fd, struct stat *buf);
typedef int (*fstatat_fn_t)(int dirfd, const char *path, struct stat *buf,
                            int flags);

/*
 * Copy-emulated links keep the source and the target at nlink 1, but
 * shadow-utils expects the source of a lock link(2) to report nlink 2
 * (lib/commonio.c, check_link_count()). Remember the inodes this process
 * "linked" and report nlink 2 for them via the stat family. Same-process
 * only: shadow checks in the process that created the lock.
 */
#define LINKFIX_LINK_MAX 64

struct linkfix_link {
    dev_t src_dev;
    ino_t src_ino;
    dev_t dst_dev;
    ino_t dst_ino;
};

static struct linkfix_link s_links[LINKFIX_LINK_MAX];
static size_t s_link_count;

static void linkfix_remember_link(dev_t src_dev, ino_t src_ino,
                                  dev_t dst_dev, ino_t dst_ino) {
    for (size_t i = 0; i < s_link_count; i++) {
        if (s_links[i].src_dev == src_dev && s_links[i].src_ino == src_ino &&
            s_links[i].dst_dev == dst_dev && s_links[i].dst_ino == dst_ino)
            return;
    }
    if (s_link_count < LINKFIX_LINK_MAX) {
        s_links[s_link_count].src_dev = src_dev;
        s_links[s_link_count].src_ino = src_ino;
        s_links[s_link_count].dst_dev = dst_dev;
        s_links[s_link_count].dst_ino = dst_ino;
        s_link_count++;
    }
}

static void linkfix_groom_nlink(struct stat *st) {
    for (size_t i = 0; i < s_link_count; i++) {
        if ((st->st_dev == s_links[i].src_dev && st->st_ino == s_links[i].src_ino) ||
            (st->st_dev == s_links[i].dst_dev && st->st_ino == s_links[i].dst_ino)) {
            st->st_nlink = 2;
            return;
        }
    }
}

/* Resolve (and cache) the next interposition slot for @sym. */
static void *linkfix_dlsym_once(void **cache, const char *sym) {
    if (*cache == NULL)
        *(void **)cache = dlsym(RTLD_NEXT, sym);
    return *cache;
}

/* link() is impossible here (vs a transient error)? Only then emulate. */
static int is_link_impossible(int saved_errno) {
    return saved_errno == EACCES || saved_errno == EPERM ||
           saved_errno == EROFS || saved_errno == EXDEV ||
           saved_errno == ENOSYS;
}

static int copy_fd(int src_fd, int dst_fd) {
    char buf[COPY_BUF_SIZE];
    for (;;) {
        ssize_t nread = read(src_fd, buf, sizeof(buf));
        if (nread < 0)
            return -1;
        if (nread == 0)
            return 0;
        ssize_t written = 0;
        while (written < nread) {
            ssize_t n = write(dst_fd, buf + written,
                              (size_t)(nread - written));
            if (n < 0)
                return -1;
            written += n;
        }
    }
}

/*
 * Emulate link(src -> dst) by copying. Only regular files are emulated;
 * anything else keeps the original errno from the failed real call.
 */
static int copy_fallback_at(int src_dirfd, const char *src,
                            int dst_dirfd, const char *dst,
                            int saved_errno) {
    static fstatat_fn_t real_fstatat = NULL;
    fstatat_fn_t real_fstatat_fn =
        (fstatat_fn_t)linkfix_dlsym_once((void **)&real_fstatat, "fstatat");
    struct stat st;
    /* No AT_SYMLINK_FOLLOW: never emulate symlinks, only real files.
     * Use the real function directly so this path is not re-entered
     * through the interposed fstatat below. */
    if (real_fstatat_fn == NULL ||
        real_fstatat_fn(src_dirfd, src, &st, 0) != 0) {
        errno = saved_errno;
        return -1;
    }
    if (!S_ISREG(st.st_mode)) {
        errno = saved_errno;
        return -1;
    }

    int src_fd = openat(src_dirfd, src, O_RDONLY);
    if (src_fd < 0) {
        /* openat errno (e.g. ENOENT after a race) is most accurate here. */
        return -1;
    }
    int dst_fd = openat(dst_dirfd, dst,
                        O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (dst_fd < 0) {
        int open_errno = errno;
        (void)close(src_fd);
        errno = open_errno;
        return -1;
    }
    if (copy_fd(src_fd, dst_fd) != 0) {
        int io_errno = errno;
        (void)close(src_fd);
        (void)close(dst_fd);
        (void)unlinkat(dst_dirfd, dst, 0);
        errno = io_errno;
        return -1;
    }
    (void)close(src_fd);
    /* Preserve the permission bits of the backed-up file; best effort. */
    (void)fchmod(dst_fd, st.st_mode & 07777);
    /* Ownership cannot be set without privilege; ignore (dpkg runs as fake
     * root, files stay owned by the app UID, as with everything else). */
    if (fchown(dst_fd, (uid_t)-1, (gid_t)-1) != 0) {
    }
    /* Remember the emulated link so stat() reports nlink 2: shadow-utils
     * requires it on the lock temp after link(2) (check_link_count()). */
    struct stat dst_st;
    if (fstat(dst_fd, &dst_st) == 0)
        linkfix_remember_link(st.st_dev, st.st_ino,
                              dst_st.st_dev, dst_st.st_ino);
    (void)close(dst_fd);
    return 0;
}

int link(const char *oldpath, const char *newpath) {
    static link_fn_t real_link = NULL;
    static int resolved = 0;
    if (!resolved) {
        *(void **)(&real_link) = dlsym(RTLD_NEXT, "link");
        resolved = 1;
    }
    if (real_link != NULL) {
        int saved_errno;
        if (real_link(oldpath, newpath) == 0)
            return 0;
        saved_errno = errno;
        if (!is_link_impossible(saved_errno)) {
            errno = saved_errno;
            return -1;
        }
        return copy_fallback_at(AT_FDCWD, oldpath, AT_FDCWD, newpath,
                                saved_errno);
    }
    /* Should not happen (libc always provides link); plain copy attempt. */
    return copy_fallback_at(AT_FDCWD, oldpath, AT_FDCWD, newpath, EACCES);
}

int linkat(int olddirfd, const char *oldpath,
           int newdirfd, const char *newpath, int flags) {
    static linkat_fn_t real_linkat = NULL;
    static int resolved = 0;
    if (!resolved) {
        *(void **)(&real_linkat) = dlsym(RTLD_NEXT, "linkat");
        resolved = 1;
    }
    if (real_linkat != NULL) {
        int saved_errno;
        if (real_linkat(olddirfd, oldpath, newdirfd, newpath, flags) == 0)
            return 0;
        saved_errno = errno;
        if (!is_link_impossible(saved_errno)) {
            errno = saved_errno;
            return -1;
        }
        if ((flags & ~(AT_SYMLINK_FOLLOW)) != 0) {
            /* AT_EMPTY_PATH and friends: do not emulate, keep errno. */
            errno = saved_errno;
            return -1;
        }
        return copy_fallback_at(olddirfd, oldpath, newdirfd, newpath,
                                saved_errno);
    }
    return copy_fallback_at(olddirfd, oldpath, newdirfd, newpath, EACCES);
}

int stat(const char *path, struct stat *buf) {
    static stat_fn_t real = NULL;
    stat_fn_t fn = (stat_fn_t)linkfix_dlsym_once((void **)&real, "stat");
    if (fn == NULL) {
        errno = ENOSYS;
        return -1;
    }
    int ret = fn(path, buf);
    if (ret == 0)
        linkfix_groom_nlink(buf);
    return ret;
}

int lstat(const char *path, struct stat *buf) {
    static lstat_fn_t real = NULL;
    lstat_fn_t fn = (lstat_fn_t)linkfix_dlsym_once((void **)&real, "lstat");
    if (fn == NULL) {
        errno = ENOSYS;
        return -1;
    }
    int ret = fn(path, buf);
    if (ret == 0)
        linkfix_groom_nlink(buf);
    return ret;
}

int fstat(int fd, struct stat *buf) {
    static fstat_fn_t real = NULL;
    fstat_fn_t fn = (fstat_fn_t)linkfix_dlsym_once((void **)&real, "fstat");
    if (fn == NULL) {
        errno = ENOSYS;
        return -1;
    }
    int ret = fn(fd, buf);
    if (ret == 0)
        linkfix_groom_nlink(buf);
    return ret;
}

int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    static fstatat_fn_t real = NULL;
    fstatat_fn_t fn =
        (fstatat_fn_t)linkfix_dlsym_once((void **)&real, "fstatat");
    if (fn == NULL) {
        errno = ENOSYS;
        return -1;
    }
    int ret = fn(dirfd, path, buf, flags);
    if (ret == 0)
        linkfix_groom_nlink(buf);
    return ret;
}
