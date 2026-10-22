package com.termux.shared.termux.proot;

import com.termux.shared.termux.TermuxConstants;

import androidx.annotation.NonNull;

/**
 * Pure helper over the Debian Mesa {@code .deb} catalog constants (spec 011, RF-3, NFR-2).
 *
 * <p>Exposes the six package triplets defined in {@link TermuxConstants} as parallel arrays in
 * the documented apt install order, plus the two pure functions the pipeline needs:
 * {@link #debUrlFor(String, String)} (URL building with {@code +} percent-encoded as
 * {@code %2B}) and {@link #isWellFormedSha256(String)} (shape check for the pinned SHA-256
 * digests).</p>
 *
 * <p>This class must not use any Android API, so it stays unit-testable with plain JUnit.</p>
 */
public final class GpuDebCatalog {

    private static final String LOG_TAG = "GpuDebCatalog";

    /** Package names of the six Mesa .deb files, in the documented install order. */
    public static final String[] PACKAGE_NAMES = {
        TermuxConstants.DEBIAN_MESA_LIBEGL_MESA0_DEB_PACKAGE,
        TermuxConstants.DEBIAN_MESA_LIBGBM1_DEB_PACKAGE,
        TermuxConstants.DEBIAN_MESA_LIBGL1_MESA_DRI_DEB_PACKAGE,
        TermuxConstants.DEBIAN_MESA_LIBGLX_MESA0_DEB_PACKAGE,
        TermuxConstants.DEBIAN_MESA_MESA_LIBGALLIUM_DEB_PACKAGE,
        TermuxConstants.DEBIAN_MESA_MESA_VULKAN_DRIVERS_DEB_PACKAGE,
    };

    /** Download file names of the six Mesa .deb files, in the same order as {@link #PACKAGE_NAMES}. */
    public static final String[] DEB_FILE_NAMES = {
        TermuxConstants.DEBIAN_MESA_LIBEGL_MESA0_DEB_FILE_NAME,
        TermuxConstants.DEBIAN_MESA_LIBGBM1_DEB_FILE_NAME,
        TermuxConstants.DEBIAN_MESA_LIBGL1_MESA_DRI_DEB_FILE_NAME,
        TermuxConstants.DEBIAN_MESA_LIBGLX_MESA0_DEB_FILE_NAME,
        TermuxConstants.DEBIAN_MESA_MESA_LIBGALLIUM_DEB_FILE_NAME,
        TermuxConstants.DEBIAN_MESA_MESA_VULKAN_DRIVERS_DEB_FILE_NAME,
    };

    /** Release download URLs of the six Mesa .deb files, in the same order as {@link #PACKAGE_NAMES}. */
    public static final String[] DEB_URLS = {
        TermuxConstants.DEBIAN_MESA_LIBEGL_MESA0_DEB_URL,
        TermuxConstants.DEBIAN_MESA_LIBGBM1_DEB_URL,
        TermuxConstants.DEBIAN_MESA_LIBGL1_MESA_DRI_DEB_URL,
        TermuxConstants.DEBIAN_MESA_LIBGLX_MESA0_DEB_URL,
        TermuxConstants.DEBIAN_MESA_MESA_LIBGALLIUM_DEB_URL,
        TermuxConstants.DEBIAN_MESA_MESA_VULKAN_DRIVERS_DEB_URL,
    };

    /** Pinned SHA-256 digests of the six Mesa .deb files, in the same order as {@link #PACKAGE_NAMES}. */
    public static final String[] DEB_SHA256S = {
        TermuxConstants.DEBIAN_MESA_LIBEGL_MESA0_DEB_SHA256,
        TermuxConstants.DEBIAN_MESA_LIBGBM1_DEB_SHA256,
        TermuxConstants.DEBIAN_MESA_LIBGL1_MESA_DRI_DEB_SHA256,
        TermuxConstants.DEBIAN_MESA_LIBGLX_MESA0_DEB_SHA256,
        TermuxConstants.DEBIAN_MESA_MESA_LIBGALLIUM_DEB_SHA256,
        TermuxConstants.DEBIAN_MESA_MESA_VULKAN_DRIVERS_DEB_SHA256,
    };

    /**
     * Build the release download URL of a .deb file, percent-encoding {@code +} as
     * {@code %2B}, as required by the GitHub release asset route (the {@code +} in
     * Debian version strings like {@code 25.0.7-2+deb13u1} must not reach the URL raw).
     *
     * @param baseReleaseUrl The base release URL, terminated by {@code "/"}.
     * @param fileName       The raw .deb file name (may contain {@code +}).
     * @return The full URL, with every {@code +} in the file name replaced by {@code %2B}.
     */
    @NonNull
    public static String debUrlFor(@NonNull String baseReleaseUrl, @NonNull String fileName) {
        return baseReleaseUrl + fileName.replace("+", "%2B");
    }

    /**
     * Compare two hex digests in a null-safe, case-insensitive way (spec 011, RF-7).
     *
     * <p>Both arguments must be well-formed SHA-256 hex strings (see
     * {@link #isWellFormedSha256(String)}): a corrupt or missing value on either side means
     * «no verification possible» and returns {@code false}, never an accidental match.</p>
     *
     * @param computedHex The digest computed while streaming the download; may be {@code null}.
     * @param expectedHex The pinned digest from {@link TermuxConstants}; may be {@code null}.
     * @return {@code true} if and only if both are well-formed 64-hex SHA-256 strings whose
     *         value matches ignoring case.
     */
    public static boolean verifyHex(String computedHex, String expectedHex) {
        if (computedHex == null || expectedHex == null)
            return false;
        if (!isWellFormedSha256(computedHex) || !isWellFormedSha256(expectedHex))
            return false;
        return computedHex.equalsIgnoreCase(expectedHex);
    }

    /**
     * Check the SHA-256 shape of a pinned digest: exactly 64 hexadecimal characters.
     *
     * @param sha256 The digest string to check; may be {@code null}.
     * @return {@code true} if and only if {@code sha256} is 64 hex characters (either case).
     */
    public static boolean isWellFormedSha256(String sha256) {
        if (sha256 == null || sha256.length() != 64)
            return false;
        for (int i = 0; i < sha256.length(); i++) {
            char c = sha256.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex)
                return false;
        }
        return true;
    }

}
