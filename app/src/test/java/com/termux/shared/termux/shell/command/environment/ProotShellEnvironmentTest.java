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
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/sdcard", "-b", "/storage",
            "/bin/bash", "--login"
        };
        Assert.assertArrayEquals(expected, command);
    }

    @Test
    public void testBuildProotCommand_extraArgsAppended() {
        String[] command = ProotShellEnvironment.buildProotCommand(new String[]{"-c", "echo hi"});
        Assert.assertEquals(20, command.length);
        Assert.assertEquals("--login", command[17]);
        Assert.assertEquals("-c", command[18]);
        Assert.assertEquals("echo hi", command[19]);
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
        Assert.assertFalse(env.containsKey("LD_PRELOAD"));
        Assert.assertFalse(env.containsKey("LD_LIBRARY_PATH"));
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
