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

    @Test
    public void testPs1Constants_pathAndScript_consistent() {
        Assert.assertEquals("etc/profile.d/10-termux-ps1.sh",
            TermuxConstants.DEBIAN_PS1_PROFILE_RELATIVE_PATH);
        Assert.assertTrue(TermuxConstants.DEBIAN_PS1_SHELL_SCRIPT.startsWith("PS1='"));
        Assert.assertTrue(TermuxConstants.DEBIAN_PS1_SHELL_SCRIPT.contains("\\u@\\h"));
        Assert.assertTrue(TermuxConstants.DEBIAN_PS1_SHELL_SCRIPT.contains("\\w"));
        Assert.assertTrue(TermuxConstants.DEBIAN_PS1_SHELL_SCRIPT.contains("\\$ "));
        Assert.assertTrue(TermuxConstants.DEBIAN_PS1_SHELL_SCRIPT.contains("\\[\\e[38;5;"));
        Assert.assertFalse(DebianInstaller.isPs1ConfigInstalled());
    }

    @Test
    public void testUsbToolsConstants_andInstall() throws Exception {
        Assert.assertEquals("usr/local/bin/lsusb", TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH);
        Assert.assertEquals("usr/local/bin/termux-usb", TermuxConstants.DEBIAN_TERMUX_USB_RELATIVE_PATH);
        Assert.assertEquals("usr/local/bin/terminal-usb", TermuxConstants.DEBIAN_TERMINAL_USB_RELATIVE_PATH);
        // Spec 021 constants (T5): guest paths for lsblk/terminal-usb-cp and the
        // file bridge abstract endpoint, without touching the spec 020 values.
        Assert.assertEquals("usr/local/bin/lsblk", TermuxConstants.DEBIAN_LSBLOCK_RELATIVE_PATH);
        Assert.assertEquals("usr/local/bin/terminal-usb-cp", TermuxConstants.DEBIAN_TERMINAL_USB_CP_RELATIVE_PATH);
        Assert.assertEquals("terminal_usb_files", TermuxConstants.USB_FILE_BRIDGE_ABSTRACT_ADDRESS);
        Assert.assertFalse(DebianInstaller.isUsbToolsInstalled());

        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            java.io.File lsusb = new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH);
            java.io.File termuxUsb = new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMUX_USB_RELATIVE_PATH);
            java.io.File terminalUsb = new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMINAL_USB_RELATIVE_PATH);
            Assert.assertTrue(lsusb.isFile());
            Assert.assertTrue(termuxUsb.isFile());
            Assert.assertTrue(terminalUsb.exists());
            // Regression (spec 020): the guest clients reach the bridge over the
            // Android abstract UNIX namespace, not a filesystem socket path.
            String script = new String(java.nio.file.Files.readAllBytes(termuxUsb.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            Assert.assertTrue(script.contains("BRIDGE_ADDR = b\"\\x00terminal_usb\""));
            Assert.assertFalse(script.contains("/tmp/terminal_usb.sock"));
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
        }
    }

    @Test
    public void testUsbToolsRefresh_detectsStaleOrMissing() throws Exception {
        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));

            // Stale content (e. g. the pre-fix filesystem path) must be detected
            java.io.File termuxUsb = new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMUX_USB_RELATIVE_PATH);
            java.nio.file.Files.write(termuxUsb.toPath(),
                "#!/usr/bin/env python3\nSOCK_PATH = \"/tmp/terminal_usb.sock\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Assert.assertTrue(DebianInstaller.usbToolsNeedRefresh(tempRoot));

            // Refresh fixes it (idempotent repair semantics, like linkfixNeedsRefresh)
            error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));

            // Missing file must be detected too
            java.io.File lsusb = new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH);
            java.io.File backup = new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH + ".bak");
            java.nio.file.Files.move(lsusb.toPath(), backup.toPath());
            Assert.assertTrue(DebianInstaller.usbToolsNeedRefresh(tempRoot));

            // Missing lsblk (spec 021 T7) must also trigger the refresh
            java.io.File lsblk = new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSBLOCK_RELATIVE_PATH);
            java.nio.file.Files.move(lsblk.toPath(), new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSBLOCK_RELATIVE_PATH + ".bak").toPath());
            Assert.assertTrue(DebianInstaller.usbToolsNeedRefresh(tempRoot));
            error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
        }
    }

    @Test
    public void testInstallUsbTools_installsLsblkWrapper() throws Exception {
        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            java.io.File lsblk = new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSBLOCK_RELATIVE_PATH);
            Assert.assertTrue(lsblk.isFile());
            byte[] diskBytes = java.nio.file.Files.readAllBytes(lsblk.toPath());
            Assert.assertTrue(diskBytes.length > 0);
            // Executable-ish: 0755 is applied by installUsbTools; in the JVM
            // unit-test environment the chmod is stubbed, so the shebang acts as
            // the self-contained executable marker, byte-compared on device.
            String script = new String(diskBytes, java.nio.charset.StandardCharsets.UTF_8);
            Assert.assertTrue(script.startsWith("#!/usr/bin/env python3"));
            // RF-1: reads the 021 snapshot (blockDevices section of /tmp/usb_devices.json)
            Assert.assertTrue(script.contains(TermuxConstants.USB_DEVICES_GUEST_SNAPSHOT_FILE_PATH));
            Assert.assertTrue(script.contains("blockDevices"));
            // RF-2: the SELinux/sysfs limitation notice, exactly as the spec says
            Assert.assertTrue(script.contains("sys/dev/block"));
            Assert.assertTrue(script.contains("SELinux"));
            // RF-2: exit 0 ALWAYS, even for a missing or corrupt snapshot
            Assert.assertTrue(script.contains("sys.exit(0)"));
            // Tolerant parse: survives non-existent or malformed JSON without crashing
            Assert.assertTrue(script.contains("except Exception"));
            // T8.2: mounted volumes are listed LIVE parsing the guest /proc/mounts
            // (pendrive AND internal microSD show up correctly and independently).
            Assert.assertTrue(script.contains("/proc/mounts"));
            Assert.assertTrue(script.contains("VOLUME_MOUNT_RE"));
            // Live rows use the type=volume label and the guest storage bind path
            Assert.assertTrue(script.contains("\"volume\""));
            Assert.assertTrue(script.contains("/root/storage"));
            // Deduplication: a volume already listed live is not repeated by the
            // snapshot (T8.2 final: live rows first, snapshot only un-listed)
            Assert.assertTrue(script.contains("seen_ids"));
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
        }
    }

    @Test
    public void testUsbToolsRefresh_020ToolsUnchangedByLsblk() throws Exception {
        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));
            byte[][] before = {
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH).toPath()),
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMUX_USB_RELATIVE_PATH).toPath()),
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMINAL_USB_RELATIVE_PATH).toPath()),
            };
            // Reinstalling (refresh path) must keep the 020 tools byte-identical
            error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            byte[][] after = {
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot, TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH).toPath()),
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMUX_USB_RELATIVE_PATH).toPath()),
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMINAL_USB_RELATIVE_PATH).toPath()),
            };
            for (int i = 0; i < before.length; i++) {
                Assert.assertArrayEquals(the020ToolName(i), before[i], after[i]);
            }
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
        }
    }

    @Test
    public void testInstallUsbTools_installsTerminalUsbCpWrapper() throws Exception {
        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            java.io.File usbCp = new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMINAL_USB_CP_RELATIVE_PATH);
            Assert.assertTrue(usbCp.isFile());
            byte[] diskBytes = java.nio.file.Files.readAllBytes(usbCp.toPath());
            String script = new String(diskBytes, java.nio.charset.StandardCharsets.UTF_8);
            // Self-contained python3 wrapper (same pattern as the lsblk wrapper)
            Assert.assertTrue(script.startsWith("#!/usr/bin/env python3"));
            // T8.2: volume discovery is LIVE from the guest /proc/mounts; the
            // bridge ping and the JSON fallback were removed (simplicity).
            Assert.assertTrue(script.contains("/proc/mounts"));
            Assert.assertTrue(script.contains("VOLUME_MOUNT_RE"));
            Assert.assertFalse(script.contains("ping_bridge"));
            Assert.assertFalse(script.contains("\\x00terminal_usb"));
            Assert.assertFalse(script.contains("usb_devices.json"));
            // RF-4 subcommands
            Assert.assertTrue(script.contains("\"list\""));
            Assert.assertTrue(script.contains("\"read\""));
            Assert.assertTrue(script.contains("\"write\""));
            // No silent overwrite: cp -n plus exit 3 when the destination existed
            Assert.assertTrue(script.contains("cp"));
            Assert.assertTrue(script.contains("EXIT_EXISTED = 3"));
            // Path safety: rejects '..' escaping the volume
            Assert.assertTrue(script.contains(".."));
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
        }
    }

    @Test
    public void testUsbToolsRefresh_detectsMissingOrStaleTerminalUsbCp() throws Exception {
        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));

            java.io.File usbCp = new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMINAL_USB_CP_RELATIVE_PATH);
            java.nio.file.Files.move(usbCp.toPath(),
                new java.io.File(tempRoot, TermuxConstants.DEBIAN_TERMINAL_USB_CP_RELATIVE_PATH + ".bak").toPath());
            Assert.assertTrue(DebianInstaller.usbToolsNeedRefresh(tempRoot));

            error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            Assert.assertFalse(DebianInstaller.usbToolsNeedRefresh(tempRoot));
            Assert.assertTrue(usbCp.isFile());
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
        }
    }

    /**
     * T8.2 functional smoke of the generated {@code lsblk} guest wrapper: run the
     * generated python3 against a fake snapshot and a fake guest /proc/mounts
     * (the scripts honour env-path overrides so the test never touches real
     * /tmp or /proc, mirroring the on-device smoke style).
     */
    @Test
    public void testLsblkWrapper_functionalWithFakeGuestProcMounts() throws Exception {
        String pythonBin = System.getenv("PYTHON3_BIN");
        if (pythonBin == null) pythonBin = "python3";
        org.junit.Assume.assumeTrue(pythonBin != null && looksLikeExecutable(pythonBin));

        java.io.File tempRoot = java.io.File.createTempFile("rootfs-test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        java.io.File workDir = java.io.File.createTempFile("lsblk-smoke", "");
        workDir.delete();
        workDir.mkdirs();
        try {
            com.termux.shared.errors.Error error = DebianInstaller.installUsbTools(tempRoot);
            Assert.assertNull(error);
            String rawScript = new String(
                java.nio.file.Files.readAllBytes(new java.io.File(tempRoot,
                    TermuxConstants.DEBIAN_LSBLOCK_RELATIVE_PATH).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

            // Fake guest snapshot: pendrive sda with volume 459E-170F (microSD absent)
            java.io.File fakeSnapshot = new java.io.File(workDir, "snapshot.json");
            String snapshotJson = "[{\"name\":\"Generic Flash Disk\",\"blockDevices\":["
                + "{\"name\":\"sda\",\"capacityBytes\":3909091328,\"massStorage\":true,"
                + "\"volumeId\":\"459E-170F\",\"mountpoint\":\"/storage/459E-170F\"}]}]";
            java.nio.file.Files.write(fakeSnapshot.toPath(),
                snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Fake guest /proc/mounts: pendrive + internal microSD (no snapshot row)
            java.io.File fakeMounts = new java.io.File(workDir, "proc_mounts");
            java.nio.file.Files.write(fakeMounts.toPath(),
                ("/dev/fuse /storage/459E-170F fuse rw,.... 0 0\n"
                    + "/dev/fuse /storage/emulated/0 fuse rw,.... 0 0\n"
                    + "/dev/fuse /storage/47A8-1024 fuse rw,.... 0 0\n"
                    + "/dev/block /data fuse datasync,.... 0 0\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            java.io.File scriptFile = new java.io.File(workDir, "lsblk_wrapper.py");
            java.nio.file.Files.write(scriptFile.toPath(), rawScript.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            ProcessBuilder pb = new ProcessBuilder(pythonBin, scriptFile.getAbsolutePath());
            pb.environment().put("USB_SNAPSHOT_PATH_OVERRIDE", fakeSnapshot.getAbsolutePath());
            pb.environment().put("USB_PROC_MOUNTS_OVERRIDE", fakeMounts.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            int exit = proc.waitFor();
            Assert.assertTrue("lsblk wrapper must always exit 0 (RF-2), out=" + out, exit == 0);

            // Live rows come first: BOTH volumes are type=volume rows bound into
            // the guest, with a "-" size (the fake volume dirs do not exist, so
            // the statvfs measurement is not possible).
            Assert.assertTrue(out.contains("459E-170F"));
            Assert.assertTrue(out.contains("/root/storage/459E-170F"));
            Assert.assertTrue(out.contains("-"));
            // The snapshot block for the pendrive volume is NOT repeated as a
            // mass-storage row (that would mislabel, e.g., the internal microSD;
            // the snapshot only carries hardware identity). Its merged row had
            // volumeId=459E-170F, already covered by the live row.
            Assert.assertFalse(out.contains("mass-storage"));
            // Live row: internal microSD, independent of the pendrive entry.
            Assert.assertTrue(out.contains("47A8-1024"));
            Assert.assertTrue(out.contains("volume"));
            Assert.assertTrue(out.contains("/root/storage/47A8-1024"));
            String[] lines47 = out.split("\n");
            int count47 = 0;
            int countBoth = 0;
            for (String line : lines47) {
                if (line.contains("47A8-1024")) count47++;
                if (line.contains("459E-170F")) countBoth++;
            }
            Assert.assertEquals(1, count47);
            Assert.assertEquals(1, countBoth);
            // A hardware row WITHOUT volumeId is still listed with the
            // mass-storage label: simulate the pendrive unmounted by pointing
            // the snapshot override to a fallback-only snapshot.
            java.io.File fakeSnapshotNomp = new java.io.File(workDir, "snapshot_nomp.json");
            String snapshotJsonNomp = "[{\"name\":\"Generic Flash Disk\",\"blockDevices\":["
                + "{\"name\":\"sda\",\"capacityBytes\":0,\"massStorage\":true}]}]";
            java.nio.file.Files.write(fakeSnapshotNomp.toPath(),
                snapshotJsonNomp.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            java.io.File scriptFile2 = new java.io.File(workDir, "lsblk_wrapper2.py");
            java.nio.file.Files.write(scriptFile2.toPath(), rawScript.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ProcessBuilder pb2 = new ProcessBuilder(pythonBin, scriptFile2.getAbsolutePath());
            pb2.environment().put("USB_SNAPSHOT_PATH_OVERRIDE", fakeSnapshotNomp.getAbsolutePath());
            pb2.environment().put("USB_PROC_MOUNTS_OVERRIDE", fakeMounts.getAbsolutePath());
            pb2.redirectErrorStream(true);
            Process proc2 = pb2.start();
            String out2 = new String(proc2.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            int exit2 = proc2.waitFor();
            Assert.assertTrue("lsblk wrapper must always exit 0 (RF-2), out=" + out2, exit2 == 0);
            // Unmounted pendrive: sda row with mass-storage label and "-" size.
            Assert.assertTrue(out2.contains("mass-storage"));
            Assert.assertTrue(out2.contains("sda"));
            Assert.assertFalse(out2.contains("0B"));
            // New live note, no lie about it being snapshot-only content
            Assert.assertTrue(out.contains("/proc/mounts"));
        } finally {
            com.termux.shared.file.FileUtils.deleteFile("tempRoot", tempRoot.getAbsolutePath(), true);
            com.termux.shared.file.FileUtils.deleteFile("workDir", workDir.getAbsolutePath(), true);
        }
    }

    private static boolean looksLikeExecutable(String bin) {
        try {
            return new ProcessBuilder(bin, "-c", "import sys").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String the020ToolName(int i) {        switch (i) {
            case 0: return TermuxConstants.DEBIAN_LSUSB_RELATIVE_PATH;
            case 1: return TermuxConstants.DEBIAN_TERMUX_USB_RELATIVE_PATH;
            default: return TermuxConstants.DEBIAN_TERMINAL_USB_RELATIVE_PATH;
        }
    }
}
