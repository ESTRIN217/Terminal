package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure JUnit tests for {@link VirglLibrary} (spec 013, T1).
 * All inputs injected; no Android, no disk, no time.
 */
public class VirglLibraryTest {

    // ---- resolveSolutionChange: exclusión mutua entre vías (RF-6) ----

    @Test
    public void testResolve_noneToVirgl_isOk() {
        VirglLibrary.SolutionGate g = VirglLibrary.resolveSolutionChange(
            GpuDetectionLibrary.Solution.NONE, GpuDetectionLibrary.Solution.VIRGL);

        Assert.assertEquals(VirglLibrary.SolutionGate.Status.OK, g.status);
        Assert.assertEquals(GpuDetectionLibrary.Solution.VIRGL, g.target);
        Assert.assertNull(g.reason);
    }

    @Test
    public void testResolve_kgslToVirgl_needsOffWithReason() {
        VirglLibrary.SolutionGate g = VirglLibrary.resolveSolutionChange(
            GpuDetectionLibrary.Solution.ADRENO_KGSL, GpuDetectionLibrary.Solution.VIRGL);

        Assert.assertEquals(VirglLibrary.SolutionGate.Status.NEEDS_OFF, g.status);
        Assert.assertEquals(VirglLibrary.REASON_KGSL_ACTIVE, g.reason);
    }

    @Test
    public void testResolve_virglToKgsl_needsOffWithReason() {
        VirglLibrary.SolutionGate g = VirglLibrary.resolveSolutionChange(
            GpuDetectionLibrary.Solution.VIRGL, GpuDetectionLibrary.Solution.ADRENO_KGSL);

        Assert.assertEquals(VirglLibrary.SolutionGate.Status.NEEDS_OFF, g.status);
        Assert.assertEquals(VirglLibrary.REASON_VIRGL_ACTIVE, g.reason);
    }

    @Test
    public void testResolve_anyToNone_isOk() {
        for (GpuDetectionLibrary.Solution current : GpuDetectionLibrary.Solution.values()) {
            VirglLibrary.SolutionGate g = VirglLibrary.resolveSolutionChange(
                current, GpuDetectionLibrary.Solution.NONE);

            Assert.assertEquals(VirglLibrary.SolutionGate.Status.OK, g.status);
            Assert.assertEquals(GpuDetectionLibrary.Solution.NONE, g.target);
            Assert.assertNull(g.reason);
        }
    }

    @Test
    public void testResolve_noneToKgsl_isOk_gate011NotBroken() {
        // The KGSL pipeline (spec 011) keeps its own activation path; the shared
        // gate must allow NONE -> ADRENO_KGSL exactly as today (NFR-3).
        VirglLibrary.SolutionGate g = VirglLibrary.resolveSolutionChange(
            GpuDetectionLibrary.Solution.NONE, GpuDetectionLibrary.Solution.ADRENO_KGSL);

        Assert.assertEquals(VirglLibrary.SolutionGate.Status.OK, g.status);
        Assert.assertEquals(GpuDetectionLibrary.Solution.ADRENO_KGSL, g.target);
    }

    @Test
    public void testResolve_sameSolution_isNoOp() {
        VirglLibrary.SolutionGate g = VirglLibrary.resolveSolutionChange(
            GpuDetectionLibrary.Solution.VIRGL, GpuDetectionLibrary.Solution.VIRGL);

        Assert.assertEquals(VirglLibrary.SolutionGate.Status.OK, g.status);
        Assert.assertEquals(GpuDetectionLibrary.Solution.VIRGL, g.target);
    }

    // ---- applyVirglEnv: injected guest environment (RF-3, RF-5, NFR-3) ----

    @Test
    public void testApplyVirglEnv_active_addsThreeVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");

        VirglLibrary.applyVirglEnv(env, true, "/data/rootfs/tmp");

        Assert.assertEquals("virpipe", env.get("GALLIUM_DRIVER"));
        Assert.assertEquals(VirglLibrary.GL_VERSION_OVERRIDE, env.get("MESA_GL_VERSION_OVERRIDE"));
        Assert.assertEquals("unix:path=/data/rootfs/tmp/" + VirglLibrary.SOCKET_NAME,
            env.get("VIRGL_TEST_SERVER_URI"));
        // Existing entries are preserved
        Assert.assertEquals("/usr/bin", env.get("PATH"));
    }

    @Test
    public void testApplyVirglEnv_inactive_mapIdentical() {
        Map<String, String> env = new HashMap<>();
        env.put("GALLIUM_DRIVER", "llvmpipe");
        env.put("LP_NUM_THREADS", "8");
        Map<String, String> expected = new HashMap<>(env);

        VirglLibrary.applyVirglEnv(env, false, "/data/rootfs/tmp");

        Assert.assertEquals(expected, env);
        Assert.assertFalse(env.containsKey("VIRGL_TEST_SERVER_URI"));
    }

    @Test
    public void testApplyVirglEnv_nullSocketPath_stillInjectsDriverWithoutUri() {
        // The socket path resolution is the glue's job; the pure builder must not
        // invent a URI. With no path, the driver override stays (pingable later).
        Map<String, String> env = new HashMap<>();

        VirglLibrary.applyVirglEnv(env, true, null);

        Assert.assertEquals("virpipe", env.get("GALLIUM_DRIVER"));
        Assert.assertFalse(env.containsKey("VIRGL_TEST_SERVER_URI"));
    }

    @Test
    public void testApplyVirglEnv_trailingSlashNormalized() {
        Map<String, String> env = new HashMap<>();

        VirglLibrary.applyVirglEnv(env, true, "/rootfs/tmp/");

        Assert.assertEquals("unix:path=/rootfs/tmp/" + VirglLibrary.SOCKET_NAME,
            env.get("VIRGL_TEST_SERVER_URI"));
    }

    // ---- buildServerEnv: native server environment (RF-2/RF-4) ----

    @Test
    public void testBuildServerEnv_rootfsTmpBecomesTmpdir() {
        Map<String, String> env = VirglLibrary.buildServerEnv("/rootfs/tmp");

        Assert.assertNotNull(env);
        Assert.assertEquals("/rootfs/tmp", env.get("TMPDIR"));
        // No accidental leaking of the guest-only virgl variables
        Assert.assertFalse(env.containsKey("GALLIUM_DRIVER"));
    }

    @Test
    public void testBuildServerEnv_blankPathIsNull() {
        Assert.assertNull(VirglLibrary.buildServerEnv(null));
        Assert.assertNull(VirglLibrary.buildServerEnv("   "));
    }

    // ---- buildServerArgs: lifecycle-owned process (RF-2/RF-4) ----

    @Test
    public void testBuildServerArgs_fullInvocation() {
        // Spec 013 T6 (closing the open doubt from the plan, learned from the
        // pinned vtest sources): the vtest default socket name is the absolute
        // /tmp/.virgl_test and TMPDIR does NOT move it, so the socket path must be
        // given explicitly (host view of the guest /tmp). The system EGL of the
        // Android host is GLES-only and has no DRM/GBM device, hence surfaceless.
        Assert.assertEquals(java.util.Arrays.asList(
                "--no-fork", "--multi-clients", "--use-egl-surfaceless", "--use-gles",
                "--socket-path", "/rootfs/tmp/" + VirglLibrary.SOCKET_NAME),
            VirglLibrary.buildServerArgs("/rootfs/tmp"));
    }

    @Test
    public void testBuildServerArgs_blankPathIsNull() {
        Assert.assertNull(VirglLibrary.buildServerArgs(null));
        Assert.assertNull(VirglLibrary.buildServerArgs(" "));
    }

    // ---- T4: pure server state machine (RF-2, RF-4, NFR-6) ----

    @Test
    public void testDecideServerAction_prefWithSessionsAndDeadServer_starts() {
        Assert.assertEquals(VirglLibrary.ServerAction.START,
            VirglLibrary.decideServerAction(false, true, true));
    }

    @Test
    public void testDecideServerAction_prefWithSessionsAndAliveServer_doesNothing() {
        Assert.assertEquals(VirglLibrary.ServerAction.NONE,
            VirglLibrary.decideServerAction(true, true, true));
    }

    @Test
    public void testDecideServerAction_lastSessionClosed_stops() {
        // NFR-6: inactive for real (process terminated), never dormant.
        Assert.assertEquals(VirglLibrary.ServerAction.STOP,
            VirglLibrary.decideServerAction(true, true, false));
    }

    @Test
    public void testDecideServerAction_solutionOff_stopsEvenWithSessions() {
        Assert.assertEquals(VirglLibrary.ServerAction.STOP,
            VirglLibrary.decideServerAction(true, false, true));
    }

    @Test
    public void testDecideServerAction_nothingToRestart_neverStartsWhenSolutionOff() {
        Assert.assertEquals(VirglLibrary.ServerAction.NONE,
            VirglLibrary.decideServerAction(false, false, true));
        Assert.assertEquals(VirglLibrary.ServerAction.NONE,
            VirglLibrary.decideServerAction(false, false, false));
        Assert.assertEquals(VirglLibrary.ServerAction.NONE,
            VirglLibrary.decideServerAction(false, true, false));
    }

    @Test
    public void testShouldKeepWaiting_socketWinsAndTimeoutStops() {
        // Socket present → ready, no waiting.
        Assert.assertFalse(VirglLibrary.shouldKeepWaiting(0, 3000, true));
        // Within the window and no socket yet → keep waiting.
        Assert.assertTrue(VirglLibrary.shouldKeepWaiting(100, 3000, false));
        // Window exhausted → stop (the glue reports the failure, never silent).
        Assert.assertFalse(VirglLibrary.shouldKeepWaiting(3000, 3000, false));
        Assert.assertFalse(VirglLibrary.shouldKeepWaiting(4000, 3000, false));
    }
}
