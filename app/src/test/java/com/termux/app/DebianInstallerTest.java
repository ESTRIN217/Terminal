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
}
