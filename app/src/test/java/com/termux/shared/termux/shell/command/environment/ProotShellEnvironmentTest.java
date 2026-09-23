package com.termux.shared.termux.shell.command.environment;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;

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
        Assert.assertEquals(24, command.length);
        Assert.assertEquals("--login", command[21]);
        Assert.assertEquals("-c", command[22]);
        Assert.assertEquals("echo hi", command[23]);
    }

    @Test
    public void testBuildProotCommand_editorScriptAfterLogin() {
        // File-manager "Edit" path: bash --login -c "<editor> '<guest>'; exec bash".
        String script = "nano '/root/sdcard/notes.txt'; exec bash";
        String[] command = ProotShellEnvironment.buildProotCommand(
            "/root/sdcard", new String[]{"-c", script});
        Assert.assertEquals("--login", command[21]);
        Assert.assertEquals("-c", command[22]);
        Assert.assertEquals(script, command[23]);
        Assert.assertEquals("/root/sdcard", command[5]);
    }

    @Test
    public void testBuildProotCommand_withLinkfix_wrapsGuestInEnv() {
        String[] command = ProotShellEnvironment.buildProotCommand(null, true);
        Assert.assertEquals(24, command.length);
        // LD_PRELOAD must reach the guest via argv (/usr/bin/env), never via
        // the host process environment (it would break the Bionic proot binary).
        Assert.assertEquals("/usr/bin/env", command[20]);
        Assert.assertEquals("LD_PRELOAD=" + TermuxConstants.LINKFIX_GUEST_SO_PATH, command[21]);
        Assert.assertEquals("/bin/bash", command[22]);
        Assert.assertEquals("--login", command[23]);
    }

    @Test
    public void testBuildProotCommand_guestWorkingDirectory() {
        String[] command = ProotShellEnvironment.buildProotCommand("/root/sdcard", null);
        // "-w" is replaced but the rest of the layout is unchanged.
        Assert.assertEquals("-w", command[4]);
        Assert.assertEquals("/root/sdcard", command[5]);
        Assert.assertEquals(22, command.length);
        Assert.assertEquals("--login", command[21]);
    }

    @Test
    public void testBuildProotCommand_guestWorkingDirectory_withExtraArgs() {
        String[] command = ProotShellEnvironment.buildProotCommand("/var/lib",
            new String[]{"-c", "echo hi"});
        Assert.assertEquals("-w", command[4]);
        Assert.assertEquals("/var/lib", command[5]);
        Assert.assertEquals("--login", command[21]);
        Assert.assertEquals("-c", command[22]);
        Assert.assertEquals("echo hi", command[23]);
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
        Assert.assertEquals(26, command.length);
        Assert.assertEquals("--login", command[23]);
        Assert.assertEquals("-c", command[24]);
        Assert.assertEquals("echo hi", command[25]);
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
}
