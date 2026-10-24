package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pure JUnit tests for the persisted {@code gpu_solution} preference semantics
 * (spec 013 T2): round-trip with the new VIRGL value, tolerant parsing and
 * unchanged shared constants (gotcha spec 008 — no existing key moves).
 */
public class GpuSolutionPrefTest {

    private static final String EXPECTED_KEY = "gpu_solution";
    private static final String EXPECTED_DEFAULT = "NONE";

    @Test
    public void testParse_roundTripAllSolutions() {
        for (GpuDetectionLibrary.Solution solution : GpuDetectionLibrary.Solution.values()) {
            GpuDetectionLibrary.Solution parsed =
                GpuDetectionLibrary.parseSolutionName(solution.name());

            Assert.assertEquals(solution, parsed);
        }
    }

    @Test
    public void testParse_virglRoundTrips() {
        Assert.assertEquals(GpuDetectionLibrary.Solution.VIRGL,
            GpuDetectionLibrary.parseSolutionName("VIRGL"));
    }

    @Test
    public void testParse_unknownValueIsNone() {
        // Values written by an older/newer build must degrade to NONE, never crash.
        Assert.assertEquals(GpuDetectionLibrary.Solution.NONE,
            GpuDetectionLibrary.parseSolutionName("FUTURE_VALUE"));
    }

    @Test
    public void testParse_nullBlankIsNone() {
        Assert.assertEquals(GpuDetectionLibrary.Solution.NONE,
            GpuDetectionLibrary.parseSolutionName(null));
        Assert.assertEquals(GpuDetectionLibrary.Solution.NONE,
            GpuDetectionLibrary.parseSolutionName("  "));
    }

    @Test
    public void testPersistedConstants_unchanged_gotcha008() {
        // Renaming the shared preference keys silently would orphan the user's
        // stored value: freeze them here (constitución #5 spirit).
        Assert.assertEquals(EXPECTED_KEY,
            com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants
                .TERMUX_APP.KEY_GPU_SOLUTION);
        Assert.assertEquals(EXPECTED_DEFAULT,
            com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants
                .TERMUX_APP.DEFAULT_VALUE_GPU_SOLUTION);
    }
}
