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
            "-b", "/dev", "-b", "/dev/shm", "-b", "/dev/pts",
            "-b", "/proc", "-b", "/sys",
            "-b", "/sdcard:/root/sdcard", "-b", "/storage:/root/storage",
            "/bin/bash", "--login"
        };
        Assert.assertArrayEquals(expected, command);
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
