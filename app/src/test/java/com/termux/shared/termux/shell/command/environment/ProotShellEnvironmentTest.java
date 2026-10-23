package com.termux.shared.termux.shell.command.environment;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class ProotShellEnvironmentTest {

    @Test
    public void testBuildProotCommand_default() {
        String[] command = ProotShellEnvironment.buildProotCommand(null);
        String[] expected = {
            TermuxConstants.PROOT_BIN_PATH,
            "-r", TermuxConstants.DEBIAN_ROOTFS_DIR_PATH,
            "-0", "-w", "/root",
            "-b", "/dev", "-b", TermuxConstants.DEBIAN_SHM_DIR_PATH + ":/dev/shm", "-b", "/dev/pts",
            "-b", "/proc", "-b", "/sys",
            "-b", "/system", "-b", "/apex", "-b", "/linkerconfig", "-b", "/data/app",
            "-b", "/sdcard:/root/sdcard", "-b", "/storage:/root/storage",
            "/bin/bash", "--login"
        };
        Assert.assertArrayEquals(expected, command);
    }
    @Test
    public void testBuildProotCommand_sharedMemoryBindWithAndWithoutLinkfix() {
        for (boolean withLinkfix : new boolean[]{false, true}) {
            String[] command = ProotShellEnvironment.buildProotCommand(null, withLinkfix);
            Assert.assertEquals("-b", command[8]);
            Assert.assertEquals(TermuxConstants.DEBIAN_SHM_DIR_PATH + ":/dev/shm", command[9]);
            Assert.assertTrue(TermuxConstants.DEBIAN_SHM_DIR_PATH.startsWith(
                TermuxConstants.TERMUX_FILES_DIR_PATH + "/"));
            for (String argument : command) {
                Assert.assertNotEquals("/dev/shm", argument);
            }
        }
    }

    @Test
    public void testBuildProotCommand_extraArgsAppended() {
        String[] command = ProotShellEnvironment.buildProotCommand(new String[]{"-c", "echo hi"});
        Assert.assertEquals(32, command.length);
        Assert.assertEquals("--login", command[29]);
        Assert.assertEquals("-c", command[30]);
        Assert.assertEquals("echo hi", command[31]);
    }

    @Test
    public void testBuildProotCommand_editorScriptAfterLogin() {
        // File-manager "Edit" path: bash --login -c "<editor> '<guest>'; exec bash".
        String script = "nano '/root/sdcard/notes.txt'; exec bash";
        String[] command = ProotShellEnvironment.buildProotCommand(
            "/root/sdcard", new String[]{"-c", script});
        Assert.assertEquals("--login", command[29]);
        Assert.assertEquals("-c", command[30]);
        Assert.assertEquals(script, command[31]);
        Assert.assertEquals("/root/sdcard", command[5]);
    }

    @Test
    public void testBuildProotCommand_withLinkfix_wrapsGuestInEnv() {
        String[] command = ProotShellEnvironment.buildProotCommand(null, true);
        Assert.assertEquals(32, command.length);
        // LD_PRELOAD must reach the guest via argv (/usr/bin/env), never via
        // the host process environment (it would break the Bionic proot binary).
        Assert.assertEquals("/usr/bin/env", command[28]);
        Assert.assertEquals("LD_PRELOAD=" + TermuxConstants.LINKFIX_GUEST_SO_PATH, command[29]);
        Assert.assertEquals("/bin/bash", command[30]);
        Assert.assertEquals("--login", command[31]);
    }

    @Test
    public void testBuildProotCommand_guestWorkingDirectory() {
        String[] command = ProotShellEnvironment.buildProotCommand("/root/sdcard", null);
        // "-w" is replaced but the rest of the layout is unchanged.
        Assert.assertEquals("-w", command[4]);
        Assert.assertEquals("/root/sdcard", command[5]);
        Assert.assertEquals(30, command.length);
        Assert.assertEquals("--login", command[29]);
    }

    @Test
    public void testBuildProotCommand_guestWorkingDirectory_withExtraArgs() {
        String[] command = ProotShellEnvironment.buildProotCommand("/var/lib",
            new String[]{"-c", "echo hi"});
        Assert.assertEquals("-w", command[4]);
        Assert.assertEquals("/var/lib", command[5]);
        Assert.assertEquals("--login", command[29]);
        Assert.assertEquals("-c", command[30]);
        Assert.assertEquals("echo hi", command[31]);
    }

    @Test
    public void testHostPathToGuestPath_rootfsPaths() {
        // The rootfs root maps to guest "/".
        Assert.assertEquals("/",
            ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH));
        // The guest home host dir maps to guest /root.
        Assert.assertEquals("/root",
            ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH));
        // A proot bind destination inside the guest home.
        Assert.assertEquals("/root/sdcard",
            ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH + "/sdcard"));
        Assert.assertEquals("/usr/bin",
            ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/usr/bin"));
        Assert.assertEquals("/root/sdcard/Download",
            ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH + "/sdcard/Download"));
    }

    @Test
    public void testHostPathToGuestPath_sharedStorage() {
        Assert.assertEquals("/root/sdcard",
            ProotShellEnvironment.hostPathToGuestPath("/sdcard"));
        Assert.assertEquals("/root/sdcard/foo",
            ProotShellEnvironment.hostPathToGuestPath("/sdcard/foo"));
        Assert.assertEquals("/root/storage",
            ProotShellEnvironment.hostPathToGuestPath("/storage"));
        Assert.assertEquals("/root/storage/emulated/0/X",
            ProotShellEnvironment.hostPathToGuestPath("/storage/emulated/0/X"));
    }

    @Test
    public void testHostPathToGuestPath_unmappableFallsBackToHome() {
        // Host paths outside the rootfs and the storage binds (e.g. the Termux
        // home) cannot be a guest cwd: fall back to the guest home.
        Assert.assertEquals("/root",
            ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.TERMUX_HOME_DIR_PATH));
        Assert.assertEquals("/root",
            ProotShellEnvironment.hostPathToGuestPath("/"));
        Assert.assertEquals("/root",
            ProotShellEnvironment.hostPathToGuestPath("/data/local/tmp"));
    }

    @Test
    public void testIsHostPathMappable_rootfsAndStorage() {
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable(
            TermuxConstants.DEBIAN_ROOTFS_DIR_PATH));
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable(
            TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH + "/notes.txt"));
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable("/sdcard/Download/a.txt"));
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable("/storage/emulated/0/x"));
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable("/sdcard"));
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable("/storage"));
    }

    @Test
    public void testIsHostPathMappable_rejectsUnmappable() {
        // Same inputs hostPathToGuestPath silently maps to /root — must be rejected
        // before opening a file in a guest editor.
        Assert.assertFalse(ProotShellEnvironment.isHostPathMappable(
            TermuxConstants.TERMUX_HOME_DIR_PATH));
        Assert.assertFalse(ProotShellEnvironment.isHostPathMappable("/"));
        Assert.assertFalse(ProotShellEnvironment.isHostPathMappable("/data/local/tmp"));
        Assert.assertFalse(ProotShellEnvironment.isHostPathMappable("/sdcardfoo"));
        Assert.assertFalse(ProotShellEnvironment.isHostPathMappable("/storagefoo"));
    }

    @Test
    public void testGetNativeWorkingDirectoryPath_isGuestHomeHostDir() {
        // The native chdir target is the guest home on the host, so it always
        // exists after installation and never hits proot bind destinations.
        Assert.assertEquals(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH,
            ProotShellEnvironment.getNativeWorkingDirectoryPath());
    }

    @Test
    public void testBuildProotCommand_withLinkfix_extraArgsAfterLogin() {
        String[] command = ProotShellEnvironment.buildProotCommand(new String[]{"-c", "echo hi"}, true);
        Assert.assertEquals(34, command.length);
        Assert.assertEquals("--login", command[31]);
        Assert.assertEquals("-c", command[32]);
        Assert.assertEquals("echo hi", command[33]);
    }

    // ---- spec 010: proc-stat overlay binds ----

    @Test
    public void testBuildProotCommand_overlayBinds_insertedAfterProcBind() {
        List<String[]> binds = Arrays.asList(
            new String[]{"/x/host-stat", "/proc/stat"},
            new String[]{"/x/host-meminfo", "/proc/meminfo"});

        String[] command = ProotShellEnvironment.buildProotCommand("/root", null, false, binds);

        int procIndex = indexOf(command, "/proc");
        Assert.assertTrue(procIndex >= 0);
        // Overlay binds sit right after "-b /proc" and before "-b /sys".
        Assert.assertEquals("-b", command[procIndex + 1]);
        Assert.assertEquals("/x/host-stat:/proc/stat", command[procIndex + 2]);
        Assert.assertEquals("-b", command[procIndex + 3]);
        Assert.assertEquals("/x/host-meminfo:/proc/meminfo", command[procIndex + 4]);
        Assert.assertEquals("-b", command[procIndex + 5]);
        Assert.assertEquals("/sys", command[procIndex + 6]);
    }

    @Test
    public void testBuildProotCommand_nullOverlayBinds_identicalToCurrentLayout() {
        String[] command = ProotShellEnvironment.buildProotCommand("/root", null, false, null);
        Assert.assertEquals(32 - 2, command.length); // 30 without linkfix, no extra args
        Assert.assertEquals("-w", command[4]);
        Assert.assertEquals("/root", command[5]);
        Assert.assertEquals("-b", command[8]);
        Assert.assertEquals("/proc", command[13]);
        Assert.assertEquals("-b", command[14]);
        Assert.assertEquals("/sys", command[15]);
        Assert.assertEquals("--login", command[29]);
    }

    @Test
    public void testDebianProcOverlayConstants_underFilesDirectory() {
        Assert.assertTrue(TermuxConstants.DEBIAN_PROC_OVERLAY_DIR_PATH.startsWith(
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/"));
        for (String path : new String[]{
            TermuxConstants.DEBIAN_PROC_OVERLAY_STAT_FILE_PATH,
            TermuxConstants.DEBIAN_PROC_OVERLAY_MEMINFO_FILE_PATH,
            TermuxConstants.DEBIAN_PROC_OVERLAY_LOADAVG_FILE_PATH}) {
            Assert.assertTrue(path.startsWith(TermuxConstants.DEBIAN_PROC_OVERLAY_DIR_PATH + "/"));
        }
    }

    private static int indexOf(String[] command, String needle) {
        for (int i = 0; i < command.length; i++)
            if (needle.equals(command[i])) return i;
        return -1;
    }

    @Test
    public void testIsGuestTimeZoneAvailable_presentZone() {
        // Toy rootfs: the guest tzdata contains the device zone → exportable.
        File rootfs = new File(System.getProperty("java.io.tmpdir"),
            "terminal-test-rootfs-" + System.nanoTime());
        File zoneinfo = new File(rootfs, "usr/share/zoneinfo/America");
        try {
            Assert.assertTrue(zoneinfo.mkdirs());
            Assert.assertTrue(new File(zoneinfo, "Caracas").createNewFile());
            Assert.assertTrue(ProotShellEnvironment.isGuestTimeZoneAvailable(
                rootfs.getPath(), "America/Caracas"));
        } catch (IOException e) {
            Assert.fail("Failed to build toy rootfs: " + e.getMessage());
        } finally {
            ProotShellEnvironmentTest.deleteToolRootfs(rootfs);
        }
    }

    @Test
    public void testIsGuestTimeZoneAvailable_rejections() {
        // Missing zone, unknown id and traversal attempts must never export TZ.
        File rootfsWithoutZones = new File(System.getProperty("java.io.tmpdir"),
            "terminal-test-rootfs-" + System.nanoTime());
        try {
            Assert.assertFalse(ProotShellEnvironment.isGuestTimeZoneAvailable(
                rootfsWithoutZones.getPath(), "America/Caracas"));
            Assert.assertFalse(ProotShellEnvironment.isGuestTimeZoneAvailable(
                rootfsWithoutZones.getPath(), "Etc/Unknown"));
            // Path traversal must never cure a missing zone file.
            Assert.assertFalse(ProotShellEnvironment.isGuestTimeZoneAvailable(
                rootfsWithoutZones.getPath(), "America/../../etc"));
            // POSIX-style offsets invert the sign in glibc: never export them.
            Assert.assertFalse(ProotShellEnvironment.isGuestTimeZoneAvailable(
                rootfsWithoutZones.getPath(), "GMT+04:00"));
            Assert.assertFalse(ProotShellEnvironment.isGuestTimeZoneAvailable(
                rootfsWithoutZones.getPath(), ""));
        } finally {
            ProotShellEnvironmentTest.deleteToolRootfs(rootfsWithoutZones);
        }
    }

    /** Delete the toy rootfs tree created by a test (tmpdir cleanup). */
    private static void deleteToolRootfs(File rootfs) {
        File[] files = rootfs.listFiles() != null ? rootfs.listFiles() : new File[0];
        for (File file : files) {
            ProotShellEnvironmentTest.deleteToolRootfs(file);
        }
        // Best effort: ignore failures, tmpdir tracks.
        rootfs.delete();
    }

    @Test
    public void testGetEnvironment_tzNotSetWithoutRootfs() {
        // The unit-test host has no rootfs: the guest zone cannot be probed, so
        // TZ must stay unset and the guest keeps its UTC default.
        HashMap<String, String> env = new ProotShellEnvironment().getEnvironment(null, false);
        Assert.assertFalse(env.containsKey("TZ"));
    }

    @Test
    public void testGetEnvironment_guestValues() {
        // Implementation does not dereference the context.
        HashMap<String, String> env = new ProotShellEnvironment().getEnvironment(null, false);
        Assert.assertEquals("/root", env.get("HOME"));
        Assert.assertEquals("root", env.get("USER"));
        Assert.assertEquals("/bin/bash", env.get("SHELL"));
        Assert.assertEquals("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", env.get("PATH"));
        Assert.assertEquals("/tmp", env.get("TMPDIR"));
        Assert.assertEquals(TermuxConstants.TERMUX_FILES_DIR_PATH, env.get("PROOT_TMP_DIR"));
        Assert.assertEquals("C.UTF-8", env.get("LANG"));
        Assert.assertEquals("C.UTF-8", env.get("LC_ALL"));
        Assert.assertEquals("noninteractive", env.get("DEBIAN_FRONTEND"));
        Assert.assertFalse(env.containsKey("LD_PRELOAD"));
        Assert.assertFalse(env.containsKey("LD_LIBRARY_PATH"));
    }

    @Test
    public void testGetEnvironment_noLinkfixPreloadWithoutRootfs() {
        // No rootfs (and no shim) on the unit-test host: LD_PRELOAD must stay
        // unset, otherwise every guest process would fail to start.
        HashMap<String, String> env = new ProotShellEnvironment().getEnvironment(null, false);
        Assert.assertFalse(env.containsKey("LD_PRELOAD"));
    }

    @Test
    public void testSetupShellCommandArguments_passthrough() {
        ProotShellEnvironment shellEnvironment = new ProotShellEnvironment();
        String[] args = shellEnvironment.setupShellCommandArguments("/x/proot", new String[]{"-r", "fs"});
        Assert.assertArrayEquals(new String[]{"/x/proot", "-r", "fs"}, args);
        Assert.assertArrayEquals(new String[]{"/x/proot"},
            shellEnvironment.setupShellCommandArguments("/x/proot", null));
    }

    @Test
    public void testSetupShellCommandEnvironment_guestHomeKept() {
        ProotShellEnvironment shellEnvironment = new ProotShellEnvironment();
        ExecutionCommand executionCommand = new ExecutionCommand(1, null, null, null,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(), false);
        executionCommand.setShellCommandShellEnvironment = false;
        // Must not throw with null context and must keep guest HOME (no host mkdir).
        HashMap<String, String> env = shellEnvironment.setupShellCommandEnvironment(null, executionCommand);
        Assert.assertEquals("/root", env.get("HOME"));
        Assert.assertEquals(TermuxConstants.TERMUX_HOME_DIR_PATH, env.get("PWD"));
    }

    // ---- spec 011: GPU acceleration guest environment (RF-4, NFR-3) ----

    @Test
    public void testApplyGpuEnv_active_addsKgslOverride() {
        HashMap<String, String> map = new HashMap<>();
        map.put("HOME", "/root");
        map.put("PATH", ProotShellEnvironment.GUEST_PATH);

        ProotShellEnvironment.applyGpuEnv(map, true);

        Assert.assertEquals("kgsl", map.get("MESA_LOADER_DRIVER_OVERRIDE"));
        // Pre-existing entries are untouched, nothing else is added.
        Assert.assertEquals(3, map.size());
        Assert.assertEquals("/root", map.get("HOME"));
        Assert.assertEquals(ProotShellEnvironment.GUEST_PATH, map.get("PATH"));
    }

    @Test
    public void testApplyGpuEnv_inactive_mapIdentical() {
        HashMap<String, String> map = new HashMap<>();
        map.put("HOME", "/root");
        map.put("PATH", ProotShellEnvironment.GUEST_PATH);
        map.put("TZ", "Europe/Madrid");
        HashMap<String, String> before = new HashMap<>(map);

        ProotShellEnvironment.applyGpuEnv(map, false);

        // Byte-to-byte identical to the pre-spec map: no key added or removed.
        Assert.assertEquals(before, map);
        Assert.assertEquals(before.size(), map.size());
        Assert.assertFalse(map.containsKey("MESA_LOADER_DRIVER_OVERRIDE"));
    }

    @Test
    public void testApplyGpuEnv_activeOnMapWithExistingValue_overwritesAndKeysStable() {
        HashMap<String, String> map = new HashMap<>();
        map.put("MESA_LOADER_DRIVER_OVERRIDE", "llvmpipe");

        ProotShellEnvironment.applyGpuEnv(map, true);

        Assert.assertEquals("kgsl", map.get("MESA_LOADER_DRIVER_OVERRIDE"));
        Assert.assertEquals(1, map.size());
    }

    @Test
    public void testGetEnvironment_noGpuOverrideWithoutContext() {
        // No pref context available (unit-test host): solution is inert and the
        // guest env stays identical to the pre-spec baseline (NFR-3).
        HashMap<String, String> env = new ProotShellEnvironment().getEnvironment(null, false);
        Assert.assertFalse(env.containsKey("MESA_LOADER_DRIVER_OVERRIDE"));
    }

    @Test
    public void testGetEnvironment_kittyBrandingForKgp() {
        HashMap<String, String> env = new ProotShellEnvironment().getEnvironment(null, false);
        Assert.assertEquals("1", env.get("KITTY_WINDOW_ID"));
        Assert.assertEquals("kitty", env.get("TERM_PROGRAM"));
    }

    // ---- spec 012: llvmpipe multithread guest environment (RF-1, RF-2, RF-4) ----

    @Test
    public void testApplyLlvmpipeEnv_onNoGpu_addsBothVarsWithMinCoresAndPref() {
        HashMap<String, String> map = new HashMap<>();
        map.put("HOME", "/root");

        ProotShellEnvironment.applyLlvmpipeEnv(map, true, false, 8, 8);

        Assert.assertEquals("8", map.get("LP_NUM_THREADS"));
        Assert.assertEquals("8", map.get("GALLIUM_NUM_THREADS"));
        Assert.assertEquals(3, map.size());
        Assert.assertEquals("/root", map.get("HOME"));
    }

    @Test
    public void testApplyLlvmpipeEnv_prefN4_gives4() {
        HashMap<String, String> map = new HashMap<>();
        ProotShellEnvironment.applyLlvmpipeEnv(map, true, false, 8, 4);

        Assert.assertEquals("4", map.get("LP_NUM_THREADS"));
        Assert.assertEquals("4", map.get("GALLIUM_NUM_THREADS"));
        Assert.assertEquals(2, map.size());
    }

    @Test
    public void testApplyLlvmpipeEnv_off_mapIdentical() {
        HashMap<String, String> map = new HashMap<>();
        map.put("HOME", "/root");
        map.put("PATH", ProotShellEnvironment.GUEST_PATH);
        HashMap<String, String> before = new HashMap<>(map);

        ProotShellEnvironment.applyLlvmpipeEnv(map, false, false, 8, 8);

        Assert.assertEquals(before, map);
        Assert.assertFalse(map.containsKey("LP_NUM_THREADS"));
        Assert.assertFalse(map.containsKey("GALLIUM_NUM_THREADS"));
    }

    @Test
    public void testApplyLlvmpipeEnv_gpuAccelerated_noVars() {
        HashMap<String, String> map = new HashMap<>();
        map.put("HOME", "/root");
        HashMap<String, String> before = new HashMap<>(map);

        ProotShellEnvironment.applyLlvmpipeEnv(map, true, true, 8, 8);

        Assert.assertEquals(before, map);
        Assert.assertFalse(map.containsKey("LP_NUM_THREADS"));
        Assert.assertFalse(map.containsKey("GALLIUM_NUM_THREADS"));
    }

    @Test
    public void testGetEnvironment_noLlvmpipeWithoutContext() {
        // Without an Android context, preference stays inert (RF-2 regression zero).
        HashMap<String, String> env = new ProotShellEnvironment().getEnvironment(null, false);
        Assert.assertFalse(env.containsKey("LP_NUM_THREADS"));
        Assert.assertFalse(env.containsKey("GALLIUM_NUM_THREADS"));
    }
}
