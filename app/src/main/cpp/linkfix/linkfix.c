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
    struct stat st;
    /* No AT_SYMLINK_FOLLOW: never emulate symlinks, only real files. */
    if (fstatat(src_dirfd, src, &st, 0) != 0) {
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
