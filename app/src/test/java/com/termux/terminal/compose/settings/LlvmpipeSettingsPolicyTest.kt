package com.termux.terminal.compose.settings

import org.junit.Assert
import org.junit.Test

/**
 * Unit tests for the pure llvmpipe settings policy (spec 012, RF-1, RF-2).
 */
class LlvmpipeSettingsPolicyTest {

    @Test
    fun testComputeEffectiveThreads_standardPairs() {
        Assert.assertEquals(8, LlvmpipeSettingsPolicy.computeEffectiveThreads(8, 8))
        Assert.assertEquals(4, LlvmpipeSettingsPolicy.computeEffectiveThreads(4, 8))
        Assert.assertEquals(8, LlvmpipeSettingsPolicy.computeEffectiveThreads(16, 8))
        Assert.assertEquals(4, LlvmpipeSettingsPolicy.computeEffectiveThreads(8, 4))
        Assert.assertEquals(1, LlvmpipeSettingsPolicy.computeEffectiveThreads(1, 16))
    }

    @Test
    fun testComputeEffectiveThreads_clampsOutOfRangeInputs() {
        Assert.assertEquals(1, LlvmpipeSettingsPolicy.computeEffectiveThreads(0, 8))
        Assert.assertEquals(8, LlvmpipeSettingsPolicy.computeEffectiveThreads(99, 8))
        Assert.assertEquals(1, LlvmpipeSettingsPolicy.computeEffectiveThreads(8, 0))
        Assert.assertEquals(8, LlvmpipeSettingsPolicy.computeEffectiveThreads(8, 99))
    }

    @Test
    fun testIsApplicable_falseWhenGpuAccelerated() {
        Assert.assertTrue(LlvmpipeSettingsPolicy.isApplicable(isGpuSolutionActive = false))
        Assert.assertFalse(LlvmpipeSettingsPolicy.isApplicable(isGpuSolutionActive = true))
    }
}
