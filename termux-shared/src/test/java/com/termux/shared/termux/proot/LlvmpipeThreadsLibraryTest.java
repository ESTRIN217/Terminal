package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * Pure JUnit tests for {@link LlvmpipeThreadsLibrary} (spec 012, RF-1).
 * All inputs injected; no Android, no disk, no time.
 */
public class LlvmpipeThreadsLibraryTest {

    // ---- computeThreads: normal pairs ----

    @Test
    public void testComputeThreads_8CoresPref8Gives8() {
        Assert.assertEquals(8, LlvmpipeThreadsLibrary.computeThreads(8, 8));
    }

    @Test
    public void testComputeThreads_4CoresPref8Gives4() {
        Assert.assertEquals(4, LlvmpipeThreadsLibrary.computeThreads(4, 8));
    }

    @Test
    public void testComputeThreads_16CoresPref8Gives8() {
        Assert.assertEquals(8, LlvmpipeThreadsLibrary.computeThreads(16, 8));
    }

    @Test
    public void testComputeThreads_1CorePref16Gives1() {
        // Fixed on purpose: the benchmark comparison point of RF-3.
        Assert.assertEquals(1, LlvmpipeThreadsLibrary.computeThreads(1, 16));
    }

    @Test
    public void testComputeThreads_32CoresPref16Gives16() {
        Assert.assertEquals(16, LlvmpipeThreadsLibrary.computeThreads(32, 16));
    }

    // ---- computeThreads: clamps (coresRaw floor 1, ceiling 32 before min) ----

    @Test
    public void testComputeThreads_coresRawZeroFallsBackTo1() {
        Assert.assertEquals(1, LlvmpipeThreadsLibrary.computeThreads(0, 8));
    }

    @Test
    public void testComputeThreads_coresRawLyingHighClampedTo32BeforeMin() {
        // ROMs lying about cores: clamp to 32 happens before the min with N.
        Assert.assertEquals(8, LlvmpipeThreadsLibrary.computeThreads(99, 8));
    }

    @Test
    public void testComputeThreads_prefNZeroClampedToFloor1() {
        // clamp(prefN, 1, 16) floor 1, then min(cores, 1).
        Assert.assertEquals(1, LlvmpipeThreadsLibrary.computeThreads(8, 0));
        Assert.assertEquals(1, LlvmpipeThreadsLibrary.computeThreads(4, 0));
    }

    @Test
    public void testComputeThreads_prefNHighClampedToCeiling16BeforeMin() {
        // clamp(prefN, 1, 16) ceiling 16, then min(cores, 16).
        Assert.assertEquals(8, LlvmpipeThreadsLibrary.computeThreads(8, 99));
        Assert.assertEquals(16, LlvmpipeThreadsLibrary.computeThreads(32, 99));
    }

    // ---- llvmpipeEnvEntries ----

    @Test
    public void testLlvmpipeEnvEntries_positiveThreadsGivesBothVarsWithSameValue() {
        Map<String, String> env = LlvmpipeThreadsLibrary.llvmpipeEnvEntries(8);

        Assert.assertEquals(2, env.size());
        Assert.assertEquals("8", env.get(LlvmpipeThreadsLibrary.ENV_LP_NUM_THREADS));
        Assert.assertEquals("8", env.get(LlvmpipeThreadsLibrary.ENV_GALLIUM_NUM_THREADS));
        // Same value string in both variables.
        Assert.assertEquals(env.get(LlvmpipeThreadsLibrary.ENV_LP_NUM_THREADS),
            env.get(LlvmpipeThreadsLibrary.ENV_GALLIUM_NUM_THREADS));
    }

    @Test
    public void testLlvmpipeEnvEntries_zeroThreadsGivesEmptyMap() {
        Assert.assertTrue(LlvmpipeThreadsLibrary.llvmpipeEnvEntries(0).isEmpty());
    }

    @Test
    public void testLlvmpipeEnvEntries_negativeThreadsGivesEmptyMap() {
        Assert.assertTrue(LlvmpipeThreadsLibrary.llvmpipeEnvEntries(-3).isEmpty());
    }

    @Test
    public void testLlvmpipeEnvEntries_threadsWithClampedCeilingValue() {
        Map<String, String> env = LlvmpipeThreadsLibrary.llvmpipeEnvEntries(16);
        Assert.assertEquals("16", env.get(LlvmpipeThreadsLibrary.ENV_LP_NUM_THREADS));
        Assert.assertEquals("16", env.get(LlvmpipeThreadsLibrary.ENV_GALLIUM_NUM_THREADS));
    }

    // ---- shouldInject ----

    @Test
    public void testShouldInject_offNoGpuGivesFalse() {
        Assert.assertFalse(LlvmpipeThreadsLibrary.shouldInject(false, false));
    }

    @Test
    public void testShouldInject_offGpuAcceleratedGivesFalse() {
        Assert.assertFalse(LlvmpipeThreadsLibrary.shouldInject(false, true));
    }

    @Test
    public void testShouldInject_onNoGpuGivesTrue() {
        Assert.assertTrue(LlvmpipeThreadsLibrary.shouldInject(true, false));
    }

    @Test
    public void testShouldInject_onGpuAcceleratedGivesFalse_SF_4() {
        // With the GPU accelerated, the rest of the calculation is never read.
        Assert.assertFalse(LlvmpipeThreadsLibrary.shouldInject(true, true));
    }
}
