/*
 * Minimal shim of the Android-internal <cutils/properties.h> for the NDK
 * (spec 013 T6).
 *
 * cutils is Linux-platform-source-only; its public behavior for out-of-tree
 * consumers (Mesa's util/os_misc.c vendored in virglrenderer-1.2.0 reads
 * "mesa.*" options as system properties) is reproducible with the public
 * <sys/system_properties.h>. Values are written exactly as property_get():
 * zero when the key is absent or truncated (NUL terminated), the length of
 * the value otherwise.
 */
#ifndef _VIRGL_ANDROID_SHIM_CUTILS_PROPERTIES_H
#define _VIRGL_ANDROID_SHIM_CUTILS_PROPERTIES_H

#include <stddef.h>
#include <string.h>
#include <sys/system_properties.h>

#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#define PROPERTY_KEY_MAX 32

static inline int property_get(const char *key, char *value, const char *default_value);

/**
 * Reads the system property @p key into @p value.
 *
 * @param key property name, null-terminated
 * @param value buffer of at least PROPERTY_VALUE_MAX bytes
 * @param default_value copied into @p value when the key is empty or missing
 * @return the length of the stored value (0 when there is none)
 */
static inline int property_get(const char *key, char *value, const char *default_value) {
    uint32_t length = __system_property_get(key, value);
    if (length == 0 && default_value != NULL) {
        size_t len = strlcpy(value, default_value, PROPERTY_VALUE_MAX);
        return (int)len;
    }
    return (int)length;
}

#endif /* _VIRGL_ANDROID_SHIM_CUTILS_PROPERTIES_H */
