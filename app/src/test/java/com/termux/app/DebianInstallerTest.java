package com.termux.app;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

public class DebianInstallerTest {

    @Test
    public void testSanitizeEntryName_rejectsTraversal() {
        Assert.assertNull(DebianInstaller.sanitizeEntryName(null));
        Assert.assertNull(DebianInstaller.sanitizeEntryName(""));
        Assert.assertNull(DebianInstaller.sanitizeEntryName(".."));
        Assert.assertNull(DebianInstaller.sanitizeEntryName("../etc/passwd"));
        Assert.assertNull(DebianInstaller.sanitizeEntryName("a/../../b"));
        Assert.assertNull(DebianInstaller.sanitizeEntryName("/"));
    }

    @Test
    public void testSanitizeEntryName_stripsPrefixes() {
        Assert.assertEquals("etc/passwd", DebianInstaller.sanitizeEntryName("/etc/passwd"));
        Assert.assertEquals("bin/bash", DebianInstaller.sanitizeEntryName("./bin/bash"));
        Assert.assertEquals("usr/bin/env", DebianInstaller.sanitizeEntryName("usr/bin/env"));
        Assert.assertEquals("etc/locale.gen", DebianInstaller.sanitizeEntryName("./etc/locale.gen"));
    }

    @Test
    public void testTarballConstants_matchOfficialOciLayer() {
        // Official Debian OCI layer (debuerreotype, trixie arm64, pinned commit).
        Assert.assertEquals(
            "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/f73bd086e8d0e5e1c8b838ccc442bf24eb3ea205/stable/oci/blobs/rootfs.tar.gz",
            TermuxConstants.DEBIAN_ROOTFS_TARBALL_URL);
        Assert.assertEquals(
            "ae72a46cc255fceffec50296e43a871d478aa35ebb7beb568f214c0b9d3051f6",
            TermuxConstants.DEBIAN_ROOTFS_TARBALL_SHA256);
        Assert.assertEquals(49704853L, TermuxConstants.DEBIAN_ROOTFS_TARBALL_SIZE);
    }

    @Test
    public void testIsWhiteoutEntry() {
        Assert.assertTrue(DebianInstaller.isWhiteoutEntry(".wh.bash"));
        Assert.assertTrue(DebianInstaller.isWhiteoutEntry("etc/.wh.hostname"));
        Assert.assertTrue(DebianInstaller.isWhiteoutEntry("var/log/.wh..wh..opq"));
        Assert.assertFalse(DebianInstaller.isWhiteoutEntry("bin/bash"));
        Assert.assertFalse(DebianInstaller.isWhiteoutEntry("etc/whiteout.conf"));
        Assert.assertFalse(DebianInstaller.isWhiteoutEntry(null));
    }

    @Test
    public void testIsInstalled_missingRootfs() {
        // Fresh checkout has no rootfs; must be false (path is package-derived).
        Assert.assertFalse(DebianInstaller.isInstalled());
    }

    @Test
    public void testNormalizeMode_masksToPermissionBits() {
        Assert.assertEquals(0755, DebianInstaller.normalizeMode(0100755));
        Assert.assertEquals(0644, DebianInstaller.normalizeMode(0100644));
        Assert.assertEquals(01777, DebianInstaller.normalizeMode(0401777));
        Assert.assertEquals(0755, DebianInstaller.normalizeMode(0755));
    }

    @Test
    public void testRepairInstalledRootfsPermissions_missingRootfs() {
        // No rootfs on host test machine: must report an error, not throw.
        // Context is unused on this path (checked after isInstalled()).
        Assert.assertNotNull(DebianInstaller.repairInstalledRootfsPermissions(null));
    }

    @Test
    public void testLinkfixConstants_pathsConsistent() {
        Assert.assertEquals("debian/termux-linkfix.so",
            TermuxConstants.LINKFIX_SO_ASSET_PATH);
        Assert.assertEquals("/usr/libexec/termux-linkfix.so",
            TermuxConstants.LINKFIX_GUEST_SO_PATH);
        Assert.assertFalse(DebianInstaller.isLinkfixInstalled());
    }

    @Test
    public void testWelcomeConstants_pathAndScript_consistent() {
        Assert.assertEquals("etc/profile.d/00-termux-welcome.sh",
            TermuxConstants.DEBIAN_WELCOME_PROFILE_RELATIVE_PATH);
        Assert.assertTrue(TermuxConstants.DEBIAN_WELCOME_SHELL_SCRIPT.startsWith("echo \"-> "));
        Assert.assertTrue(TermuxConstants.DEBIAN_WELCOME_SHELL_SCRIPT.contains("\u00a1Bienvenido a Debian Linux en la terminal!"));
        Assert.assertTrue(TermuxConstants.DEBIAN_WELCOME_SHELL_SCRIPT.contains("apt search <consulta>"));
        Assert.assertTrue(TermuxConstants.DEBIAN_WELCOME_SHELL_SCRIPT.contains("apt update && apt upgrade"));
        Assert.assertFalse(DebianInstaller.isWelcomeMessageInstalled());
    }
}
