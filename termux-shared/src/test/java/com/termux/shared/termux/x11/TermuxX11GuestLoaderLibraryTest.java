package com.termux.shared.termux.x11;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure JUnit tests for {@link TermuxX11GuestLoaderLibrary} (spec 014, enmienda A).
 * All inputs injected; no Android, no disk.
 */
public class TermuxX11GuestLoaderLibraryTest {

    // ---- applyApkPathEnv ----

    @Test
    public void testApplyApkPathEnv_setsWhenApkPathPresent() {
        Map<String, String> env = new HashMap<>();
        TermuxX11GuestLoaderLibrary.applyApkPathEnv(env, "/data/app/base.apk");
        Assert.assertEquals("/data/app/base.apk", env.get("TERMUX_X11_APK"));
    }

    @Test
    public void testApplyApkPathEnv_skipsWhenNullOrBlank() {
        Map<String, String> env = new HashMap<>();
        TermuxX11GuestLoaderLibrary.applyApkPathEnv(env, null);
        TermuxX11GuestLoaderLibrary.applyApkPathEnv(env, "");
        TermuxX11GuestLoaderLibrary.applyApkPathEnv(env, "   ");
        Assert.assertFalse(env.containsKey("TERMUX_X11_APK"));
        Assert.assertTrue(env.isEmpty());
    }

    @Test
    public void testApplyApkPathEnv_overwritesStaleValue() {
        Map<String, String> env = new HashMap<>();
        env.put("TERMUX_X11_APK", "/old/path.apk");
        TermuxX11GuestLoaderLibrary.applyApkPathEnv(env, "/new/path.apk");
        Assert.assertEquals("/new/path.apk", env.get("TERMUX_X11_APK"));
    }

    @Test
    public void testApplyApkPathEnv_trimsSurroundingWhitespace() {
        Map<String, String> env = new HashMap<>();
        TermuxX11GuestLoaderLibrary.applyApkPathEnv(env, " /data/app/base.apk ");
        Assert.assertEquals("/data/app/base.apk", env.get("TERMUX_X11_APK"));
    }

    // ---- buildWrapperScript ----

    @Test
    public void testBuildWrapperScript_isBashWithStrictClasspath() {
        String script = TermuxX11GuestLoaderLibrary.buildWrapperScript();
        Assert.assertTrue(script, script.startsWith("#!/bin/bash"));
        Assert.assertTrue(script, script.contains("CLASSPATH=${TERMUX_X11_APK:?}"));
    }

    @Test
    public void testBuildWrapperScript_execsCmdEntryPointWithArgs() {
        String script = TermuxX11GuestLoaderLibrary.buildWrapperScript();
        Assert.assertTrue(script, script.contains("/system/bin/app_process /"));
        Assert.assertTrue(script, script.contains("--nice-name=termux-x11"));
        Assert.assertTrue(script, script.contains("com.termux.x11.CmdEntryPoint"));
        Assert.assertTrue(script, script.contains("\"$@\""));
    }

    @Test
    public void testBuildWrapperScript_isDeterministic() {
        Assert.assertEquals(TermuxX11GuestLoaderLibrary.buildWrapperScript(),
            TermuxX11GuestLoaderLibrary.buildWrapperScript());
    }

    // ---- equalsIgnoringTrailingNewline (idempotence key) ----

    @Test
    public void testContentMatches_sameContent() {
        Assert.assertTrue(TermuxX11GuestLoaderLibrary.contentMatches("#!/bin/bash\n", "#!/bin/bash\n"));
    }

    @Test
    public void testContentMatches_ignoresTrailingNewlineOfExisting() {
        Assert.assertTrue(TermuxX11GuestLoaderLibrary.contentMatches("#!/bin/bash\n\n", "#!/bin/bash\n"));
        Assert.assertTrue(TermuxX11GuestLoaderLibrary.contentMatches("#!/bin/bash", "#!/bin/bash\n"));
    }

    @Test
    public void testContentMatches_nullOrDifferentContent() {
        Assert.assertFalse(TermuxX11GuestLoaderLibrary.contentMatches(null, "#!/bin/bash\n"));
        Assert.assertFalse(TermuxX11GuestLoaderLibrary.contentMatches("#!/bin/sh\n", "#!/bin/bash\n"));
    }
}
