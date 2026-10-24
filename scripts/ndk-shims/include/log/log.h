/*
 * Minimal shim of the Android-internal <log/log.h> for the NDK (spec 013 T6).
 *
 * liblog's public NDK surface is <android/log.h>; the internal <log/log.h>
 * (Linux platform sources) additionally provides convenience macros such as
 * LOG_PRI that out-of-tree code (Mesa's util/os_misc.c, vendored inside
 * virglrenderer-1.2.0) still uses. This shim maps them onto the public API so
 * the VirGL server pipeline needs no patched Android platform headers.
 *
 * ANDROID_LOG_* levels and the __android_log_* functions come from
 * <android/log.h>; LOG_TAG is defined by the including consumer (as upstream
 * os_misc.c does). Link the consumer against liblog (-llog), part of the NDK.
 */
#ifndef _VIRGL_ANDROID_SHIM_LOG_LOG_H
#define _VIRGL_ANDROID_SHIM_LOG_LOG_H

#include <android/log.h>

#define LOG_PRI(priority, tag, ...) \
    ((void)__android_log_print((priority), (tag), __VA_ARGS__))

#endif /* _VIRGL_ANDROID_SHIM_LOG_LOG_H */
