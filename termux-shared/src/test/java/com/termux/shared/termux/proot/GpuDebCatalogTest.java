package com.termux.shared.termux.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;

/**
 * Shape tests for the Debian Mesa .deb catalog constants and pure helpers (spec 011, RF-3, NFR-2).
 * Nothing here touches Android APIs: {@link TermuxConstants} is a plain final constants class.
 */
public class GpuDebCatalogTest {

    @Test
    public void isWellFormedSha256_acceptsLowercase64Hex() {
        assertTrue(GpuDebCatalog.isWellFormedSha256(
            "1233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4"));
    }

    @Test
    public void isWellFormedSha256_rejectsWrongShapes() {
        assertFalse(GpuDebCatalog.isWellFormedSha256(null));
        assertFalse(GpuDebCatalog.isWellFormedSha256(""));
        assertFalse(GpuDebCatalog.isWellFormedSha256("abc"));
        // 63 hex chars: too short.
        assertFalse(GpuDebCatalog.isWellFormedSha256(
            "123345672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4"));
        // 65 chars: too long.
        assertFalse(GpuDebCatalog.isWellFormedSha256(
            "11233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4"));
        // Non-hex character.
        assertFalse(GpuDebCatalog.isWellFormedSha256(
            "g933334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4"));
    }

    @Test
    public void catalog_hasSixEntriesInInstallOrder() {
        assertEquals(6, GpuDebCatalog.PACKAGE_NAMES.length);
        StringBuilder all = new StringBuilder();
        for (String p : GpuDebCatalog.PACKAGE_NAMES) all.append(p).append('|');
        // The string order below is also the documented apt install order (spec 011).
        assertEquals("libegl-mesa0|libgbm1|libgl1-mesa-dri|libglx-mesa0|mesa-libgallium|mesa-vulkan-drivers|",
            all.toString());
    }

    @Test
    public void keys_and_paths_exist_with_expected_shape() {
        assertEquals("/data/data/com.estrin217.terminal/files/debian/gpu-debs",
            TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH);
    }

    @Test
    public void expectedMesaVersion_isExact() {
        assertEquals("25.0.7-2+deb13u1", TermuxConstants.DEBIAN_MESA_EXPECTED_VERSION);
    }

    @Test
    public void everyDebUrl_isWellFormed_and_matchesFileName() {
        for (int i = 0; i < GpuDebCatalog.DEB_URLS.length; i++) {
            String url = GpuDebCatalog.DEB_URLS[i];
            assertTrue("URL " + i + " must start with the base release URL",
                url.startsWith(TermuxConstants.DEBIAN_MESA_RELEASE_BASE_URL));
            assertTrue("URL " + i + " must be https", url.startsWith("https://"));
            assertTrue("URL " + i + " must end with an arm64 .deb", url.endsWith("_arm64.deb"));
            assertTrue("URL " + i + " must avoid raw '+'", !url.contains("+"));
            // Cross-check: the delegated URL builder reproduces the constant from the plain
            // file name, so file names and URL constants cannot drift apart.
            assertEquals(url, GpuDebCatalog.debUrlFor(TermuxConstants.DEBIAN_MESA_RELEASE_BASE_URL,
                GpuDebCatalog.DEB_FILE_NAMES[i]));
        }
    }

    @Test
    public void debUrlFor_encodesPlus_and_supportsCustomBase() {
        assertEquals("https://example.invalid/r/libgbm1_25.0.7-2%2Bdeb13u1_arm64.deb",
            GpuDebCatalog.debUrlFor("https://example.invalid/r/",
                "libgbm1_25.0.7-2+deb13u1_arm64.deb"));
    }

    @Test
    public void everyDebSha256_isWellFormed64Hex() {
        for (String sha : GpuDebCatalog.DEB_SHA256S) {
            assertTrue("SHA must be 64 lowercase hex chars, got: " + sha,
                GpuDebCatalog.isWellFormedSha256(sha));
        }
    }

    @Test
    public void verifyHex_nullArgumentsAreRejected() {
        assertFalse(GpuDebCatalog.verifyHex(null, "1233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4"));
        assertFalse(GpuDebCatalog.verifyHex("1233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4", null));
        assertFalse(GpuDebCatalog.verifyHex(null, null));
    }

    @Test
    public void verifyHex_matchesCaseInsensitively() {
        assertTrue(GpuDebCatalog.verifyHex(GpuDebCatalog.DEB_SHA256S[0].toUpperCase(),
            GpuDebCatalog.DEB_SHA256S[0]));
        assertTrue(GpuDebCatalog.verifyHex(GpuDebCatalog.DEB_SHA256S[0],
            GpuDebCatalog.DEB_SHA256S[0].toUpperCase()));
        assertTrue(GpuDebCatalog.verifyHex(GpuDebCatalog.DEB_SHA256S[0], GpuDebCatalog.DEB_SHA256S[0]));
    }

    @Test
    public void verifyHex_rejectsMismatch() {
        String other = "0000000000000000000000000000000000000000000000000000000000000000";
        assertFalse(GpuDebCatalog.verifyHex(GpuDebCatalog.DEB_SHA256S[1], other));
        assertFalse(GpuDebCatalog.verifyHex(other, GpuDebCatalog.DEB_SHA256S[1]));
        // Same value but one side corrupt-shaped (short SHA): must not match by accident.
        assertFalse(GpuDebCatalog.verifyHex("abc", GpuDebCatalog.DEB_SHA256S[1]));
    }

    @Test
    public void verifyHex_rejectsCorruptExpectedOrComputed() {
        String wellFormed = "1233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4";
        // Corrupt expected: bad shape (too short, non-hex char, wrong length).
        assertFalse(GpuDebCatalog.verifyHex(wellFormed, "deadbeef"));
        assertFalse(GpuDebCatalog.verifyHex(wellFormed, ""));
        assertFalse(GpuDebCatalog.verifyHex(wellFormed,
            "g233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4"));
        // Corrupt computed.
        assertFalse(GpuDebCatalog.verifyHex("deadbeef", wellFormed));
        assertFalse(GpuDebCatalog.verifyHex("", wellFormed));
    }

    @Test
    public void everySha_isVerifiedValue_fromreleasedFiles() {
        // Values verified with sha256sum on 2026-10-21 against the files downloaded from the
        // lfdevs "debian/25.0.7-2-adreno" release (see TermuxConstants javadoc).
        assertEquals("1233334672d064b902a96fd8033d703c4617c927961a602c7156026184eb5ae4",
            GpuDebCatalog.DEB_SHA256S[0]);
        assertEquals("619deb3938e89224fcde48eebcc8f08c0076708535871c7d2b1cea4c061bb45b",
            GpuDebCatalog.DEB_SHA256S[1]);
        assertEquals("8fcff5c23d35147dd46fbde0868d2a9450d30e2bc13e8a1986e527fda217d850",
            GpuDebCatalog.DEB_SHA256S[2]);
        assertEquals("7e07f9bcd625c3da6fd41eeed4dbb2449aef3fffb60a9585f9c625aaabdcee76",
            GpuDebCatalog.DEB_SHA256S[3]);
        assertEquals("9b618c92c8975050db98aac89d9c91619d66cda9e1b9075d2b66ba4f1f6b01b3",
            GpuDebCatalog.DEB_SHA256S[4]);
        assertEquals("a3677b306710f9ae20a122ad6b41fd43e2f47d3c10ba3b480ce12a83ab758d8c",
            GpuDebCatalog.DEB_SHA256S[5]);
    }
}
