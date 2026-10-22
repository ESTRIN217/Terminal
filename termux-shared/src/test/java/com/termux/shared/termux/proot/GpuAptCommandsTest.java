package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure JUnit tests for {@link GpuGuestCommands} (spec 011, RF-3, RF-5, RF-6).
 * All inputs injected; no Android, no disk.
 *
 * <p>{@code debUrlFor} and the {@code +} → {@code %2B} encoding are covered by
 * {@link GpuDebCatalogTest} (T3) and deliberately NOT repeated here.</p>
 */
public class GpuAptCommandsTest {

    private static final String[] SIX_PACKAGES = {
        "libegl-mesa0", "libgbm1", "libgl1-mesa-dri",
        "libglx-mesa0", "mesa-libgallium", "mesa-vulkan-drivers",
    };

    private static final String[] SIX_DEB_FILES = {
        "libegl-mesa0_25.0.7-2+deb13u1_arm64.deb",
        "libgbm1_25.0.7-2+deb13u1_arm64.deb",
        "libgl1-mesa-dri_25.0.7-2+deb13u1_arm64.deb",
        "libglx-mesa0_25.0.7-2+deb13u1_arm64.deb",
        "mesa-libgallium_25.0.7-2+deb13u1_arm64.deb",
        "mesa-vulkan-drivers_25.0.7-2+deb13u1_arm64.deb",
    };

    // ---- buildInstallCommand (RF-3) ----

    @Test
    public void testBuildInstallCommand_exactShape_frontendUpdateReinstallHold() {
        // Only apt-lock/errors abort the chain: the three steps are joined with "&&";
        // the noninteractive export prefix ends with ";" (plan function 3).
        String expected =
            "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update && " +
            "apt install --reinstall -y " +
            "./libegl-mesa0_25.0.7-2+deb13u1_arm64.deb " +
            "./libgbm1_25.0.7-2+deb13u1_arm64.deb " +
            "./libgl1-mesa-dri_25.0.7-2+deb13u1_arm64.deb " +
            "./libglx-mesa0_25.0.7-2+deb13u1_arm64.deb " +
            "./mesa-libgallium_25.0.7-2+deb13u1_arm64.deb " +
            "./mesa-vulkan-drivers_25.0.7-2+deb13u1_arm64.deb && " +
            "apt-mark hold " +
            "libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 mesa-libgallium mesa-vulkan-drivers";

        String cmd = GpuGuestCommands.buildInstallCommand(SIX_DEB_FILES, SIX_PACKAGES);
        Assert.assertEquals(expected, cmd);

        // Shape invariants, pinned independently of the full string.
        Assert.assertTrue(cmd.contains("DEBIAN_FRONTEND=noninteractive"));
        for (String deb : SIX_DEB_FILES)
            Assert.assertTrue("must contain \"./\" + " + deb, cmd.contains("./" + deb));
        for (String pkg : SIX_PACKAGES)
            Assert.assertTrue("must contain package " + pkg, cmd.contains(pkg));
    }

    @Test
    public void testBuildInstallCommand_emptyArrays_noThrow_updateOnly() {
        // Decision (no-throw repo rule): empty arrays degrade to fewer "&&" segments;
        // the caller is expected to always pass the six real names.
        String cmd = GpuGuestCommands.buildInstallCommand(new String[0], new String[0]);
        Assert.assertEquals("export DEBIAN_FRONTEND=noninteractive; apt-get update", cmd);
    }

    @Test
    public void testBuildInstallCommand_singleElement_noThrow() {
        String cmd = GpuGuestCommands.buildInstallCommand(
            new String[]{"libgbm1_x.deb"}, new String[]{"libgbm1"});

        Assert.assertEquals(
            "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update && " +
            "apt install --reinstall -y ./libgbm1_x.deb && " +
            "apt-mark hold libgbm1",
            cmd);
    }

    // ---- buildUninstallCommand (RF-5) ----

    @Test
    public void testBuildUninstallCommand_exactShape_unholdReinstallFromMirror() {
        // Mirror Debian: reinstall from the repo, no local files, no "./" anywhere.
        String expected =
            "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update && " +
            "apt-mark unhold " +
            "libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 mesa-libgallium mesa-vulkan-drivers && " +
            "apt install --reinstall -y " +
            "libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 mesa-libgallium mesa-vulkan-drivers";

        String cmd = GpuGuestCommands.buildUninstallCommand(SIX_PACKAGES);
        Assert.assertEquals(expected, cmd);
        Assert.assertFalse("must not reference local files", cmd.contains("./"));
    }

    @Test
    public void testBuildUninstallCommand_emptyPackages_noThrow_updateOnly() {
        String cmd = GpuGuestCommands.buildUninstallCommand(new String[0]);
        Assert.assertEquals("export DEBIAN_FRONTEND=noninteractive; apt-get update", cmd);
    }

    // ---- parseDpkgVersions (RF-3, RF-6) ----

    @Test
    public void testParseDpkgVersions_wellFormedLines() {
        String out =
            "mesa-libgallium\t25.0.7-2+deb13u1\n" +
            "libgbm1\t25.0.7-2+deb13u1\n";
        Map<String, String> map = GpuGuestCommands.parseDpkgVersions(out);

        Assert.assertEquals("25.0.7-2+deb13u1", map.get("mesa-libgallium"));
        Assert.assertEquals("25.0.7-2+deb13u1", map.get("libgbm1"));
    }

    @Test
    public void testParseDpkgVersions_absentPackage_readsNull_withoutCrash() {
        // dpkg-query complains on stderr for absent packages; the caller (T5/T6)
        // decides set +e. Whichever way the complaint reaches the text, the absent
        // package must read as diverging: map.get(pkg) == null.
        String out =
            "mesa-libgallium\t25.0.7-2+deb13u1\n" +
            "dpkg-query: no packages found matching libgbm1\n";
        Map<String, String> map = GpuGuestCommands.parseDpkgVersions(out);

        Assert.assertEquals("25.0.7-2+deb13u1", map.get("mesa-libgallium"));
        Assert.assertNull(map.get("libgbm1"));
    }

    @Test
    public void testParseDpkgVersions_toleratesRaggedInput() {
        Map<String, String> map = GpuGuestCommands.parseDpkgVersions(
            "libegl-mesa0\t25.0.7-2+deb13u1\n" +       // trailing newline
            "\n" +                                      // blank line
            "libgbm1\t \n" +                            // empty version → reads absent
            "unexpected free text line\n" +             // no tab → ignored
            "dpkg-query: no packages found matching libgl1-mesa-dri\n" +
            "libglx-mesa0\t25.0.7-2+deb13u1\textra\n"); // second tab → splits only on first

        Assert.assertEquals("25.0.7-2+deb13u1", map.get("libegl-mesa0"));
        Assert.assertEquals("25.0.7-2+deb13u1", map.get("libglx-mesa0"));
        Assert.assertNull(map.get("libgbm1"));
        Assert.assertNull(map.get("libgl1-mesa-dri"));
        Assert.assertEquals(2, map.size());
    }

    @Test
    public void testParseDpkgVersions_nullAndEmptyInput_neverCrash() {
        Assert.assertEquals(new HashMap<String, String>(),
            GpuGuestCommands.parseDpkgVersions(null));
        Assert.assertEquals(new HashMap<String, String>(),
            GpuGuestCommands.parseDpkgVersions(""));
        Assert.assertEquals(new HashMap<String, String>(),
            GpuGuestCommands.parseDpkgVersions("   \n  \n"));
    }

    // ---- findCommonMesaVersion (T8 review fix: RF-3 needs ALL six packages to
    // ---- agree, not the first present one) ----

    @Test
    public void testFindCommonMesaVersion_allSixAgree() {
        Map<String, String> map = new HashMap<>();
        for (String pkg : GpuDebCatalog.PACKAGE_NAMES)
            map.put(pkg, "25.0.7-2+deb13u1");
        Assert.assertEquals("25.0.7-2+deb13u1",
            GpuGuestCommands.findCommonMesaVersion(map));
    }

    @Test
    public void testFindCommonMesaVersion_onePackageDiverges_returnsNull() {
        Map<String, String> map = new HashMap<>();
        for (String pkg : GpuDebCatalog.PACKAGE_NAMES)
            map.put(pkg, "25.0.7-2+deb13u1");
        map.put("mesa-libgallium", "25.0.7-3");
        Assert.assertNull(GpuGuestCommands.findCommonMesaVersion(map));
    }

    @Test
    public void testFindCommonMesaVersion_onePackageAbsent_returnsNull() {
        Map<String, String> map = new HashMap<>();
        for (String pkg : GpuDebCatalog.PACKAGE_NAMES)
            map.put(pkg, "25.0.7-2+deb13u1");
        map.remove("libgbm1");
        Assert.assertNull(GpuGuestCommands.findCommonMesaVersion(map));
    }

    @Test
    public void testFindCommonMesaVersion_emptyMapAndNull_returnNull() {
        Assert.assertNull(GpuGuestCommands.findCommonMesaVersion(new HashMap<String, String>()));
        Assert.assertNull(GpuGuestCommands.findCommonMesaVersion(null));
    }

    // ---- parseExitMarker (T6, RF-3/RF-5 guest-session exit capture) ----

    @Test
    public void testParseExitMarker_wellFormed() {
        String out =
            "(Reading database ... 100%\n" +
            "Setting up libgbm1 ...\n" +
            "GPU_APT_EXIT:0\n";
        Assert.assertEquals(0, GpuGuestCommands.parseExitMarker(out));
    }

    @Test
    public void testParseExitMarker_nonZeroAptFailure() {
        String out =
            "E: Could not get lock /var/lib/dpkg/lock-frontend ...\n" +
            "GPU_APT_EXIT:100\n";
        Assert.assertEquals(100, GpuGuestCommands.parseExitMarker(out));
    }

    @Test
    public void testParseExitMarker_codeBeforeTrailingText() {
        // echo GPU_APT_EXIT:$? then some late line; the digits run stops at the
        // first non-digit, trailing prose is ignored.
        Assert.assertEquals(0, GpuGuestCommands.parseExitMarker("GPU_APT_EXIT:0 (done)\n"));
        Assert.assertEquals(255, GpuGuestCommands.parseExitMarker("GPU_APT_EXIT:255 done"));
    }

    @Test
    public void testParseExitMarker_multipleMarkers_lastOneWins() {
        // A second echo (e.g. a stray command after the assembled one) must not
        // let an early failure leak as the exit code: the LAST marker decides.
        String out = "first: GPU_APT_EXIT:1\n...\nlast: GPU_APT_EXIT:0\n";
        Assert.assertEquals(0, GpuGuestCommands.parseExitMarker(out));
        Assert.assertEquals(2, GpuGuestCommands.parseExitMarker(
            "GPU_APT_EXIT:0\nGPU_APT_EXIT:1\nGPU_APT_EXIT:2\n"));
    }

    @Test
    public void testParseExitMarker_missingMarkerOrMalformed_returnsMinusOne() {
        Assert.assertEquals(-1, GpuGuestCommands.parseExitMarker(null));
        Assert.assertEquals(-1, GpuGuestCommands.parseExitMarker(""));
        Assert.assertEquals(-1, GpuGuestCommands.parseExitMarker("apt output without any marker"));
        Assert.assertEquals(-1, GpuGuestCommands.parseExitMarker("GPU_APT_EXIT:"));
        Assert.assertEquals(-1, GpuGuestCommands.parseExitMarker("GPU_APT_EXIT:abc"));
        // Non-decimal prefix shapes (e.g. "GPU_APT_EXITED:0") must not match.
        Assert.assertEquals(-1, GpuGuestCommands.parseExitMarker("GPU_APT_EXITED:0"));
    }

    // ---- computeNewSolution (T6, RF-3/RF-5 preference transition) ----

    @Test
    public void testComputeNewSolution_installSuccess_becomesAdrenoKgsl() {
        Assert.assertEquals(GpuDetectionLibrary.Solution.ADRENO_KGSL,
            GpuGuestCommands.computeNewSolution(GpuDetectionLibrary.Solution.NONE, 0, false));
        // Re-running install while already accelerated keeps the same target.
        Assert.assertEquals(GpuDetectionLibrary.Solution.ADRENO_KGSL,
            GpuGuestCommands.computeNewSolution(GpuDetectionLibrary.Solution.ADRENO_KGSL, 0, false));
    }

    @Test
    public void testComputeNewSolution_uninstallSuccess_becomesNone() {
        Assert.assertEquals(GpuDetectionLibrary.Solution.NONE,
            GpuGuestCommands.computeNewSolution(GpuDetectionLibrary.Solution.ADRENO_KGSL, 0, true));
    }

    @Test
    public void testComputeNewSolution_failureKeepsCurrent_returnsNull() {
        // RF-3/RF-5: ANY non-zero exit (apt lock, network, kill) leaves the pref
        // untouched; -1 stands for «unverifiable exit» (missing marker) and must
        // also be treated as a failure.
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.NONE, 100, false));
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.ADRENO_KGSL, 100, false));
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.ADRENO_KGSL, 255, true));
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.NONE, 1, true));
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.NONE, -1, false));
    }

    @Test
    public void testComputeNewSolution_nullCurrent_neverCrash() {
        // current is informational only; the target is decided by action+exit.
        Assert.assertEquals(GpuDetectionLibrary.Solution.ADRENO_KGSL,
            GpuGuestCommands.computeNewSolution(null, 0, false));
        Assert.assertEquals(GpuDetectionLibrary.Solution.NONE,
            GpuGuestCommands.computeNewSolution(null, 0, true));
        Assert.assertNull(GpuGuestCommands.computeNewSolution(null, -1, false));
    }

    // ---- assembleOneShotGuestCommand (T6, exit-marker plumbing) ----

    @Test
    public void testAssembleOneShotGuestCommand_appendsMarkerEcho() {
        String body = GpuGuestCommands.buildInstallCommand(SIX_DEB_FILES, SIX_PACKAGES);
        String assembled = GpuGuestCommands.assembleOneShotGuestCommand(body);
        // The echo runs unconditionally after the chain (";"), so the marker is
        // emitted even when apt fails; the marker carries the chain's exit code.
        Assert.assertEquals(body + "; echo GPU_APT_EXIT:$?", assembled);
        Assert.assertTrue(assembled.startsWith(body));
    }

    // ---- buildDpkgQueryCommand (T8: the panel's guest mesa version rule) ----

    @Test
    public void testBuildDpkgQueryCommand_exactShape() {
        // RF-6: the gate compares the GUEST package versions against
        // DEBIAN_MESA_EXPECTED_VERSION; this rule feeds parseDpkgVersions.
        String cmd = GpuGuestCommands.buildDpkgQueryCommand(SIX_PACKAGES);

        Assert.assertTrue(cmd.startsWith("dpkg-query -W -f='${Package}\\t${Version}\\n'"));
        for (String pkg : SIX_PACKAGES)
            Assert.assertTrue("Package missing from the rule: " + pkg, cmd.contains(" " + pkg));
        // No local .deb files, no install/hold steps: a read-only query.
        Assert.assertFalse(cmd.contains("apt"));
    }

    @Test
    public void testBuildDpkgQueryCommand_nullOrEmpty_neverCrash() {
        Assert.assertEquals("dpkg-query -W -f='${Package}\\t${Version}\\n'",
            GpuGuestCommands.buildDpkgQueryCommand(null));
        Assert.assertEquals("dpkg-query -W -f='${Package}\\t${Version}\\n'",
            GpuGuestCommands.buildDpkgQueryCommand(new String[0]));
    }
}
