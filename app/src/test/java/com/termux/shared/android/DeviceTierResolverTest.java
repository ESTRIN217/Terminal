package com.termux.shared.android;

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

import org.junit.Assert;
import org.junit.Test;

public class DeviceTierResolverTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    public void testResolveTier_ramBelow4GiBIsLow() {
        Assert.assertEquals(DeviceTierResolver.Tier.LOW,
            DeviceTierResolver.resolveTier(3 * GIB, 8, "qcom", "phone"));
    }

    @Test
    public void testResolveTier_coresAtMost4IsLow() {
        Assert.assertEquals(DeviceTierResolver.Tier.LOW,
            DeviceTierResolver.resolveTier(16 * GIB, 4, "qcom", "phone"));
    }

    @Test
    public void testResolveTier_exact4GiBWithEnoughCoresIsMedium() {
        Assert.assertEquals(DeviceTierResolver.Tier.MEDIUM,
            DeviceTierResolver.resolveTier(4 * GIB, 8, "qcom", "phone"));
    }

    @Test
    public void testResolveTier_8GiBAnd8CoresIsHigh() {
        Assert.assertEquals(DeviceTierResolver.Tier.HIGH,
            DeviceTierResolver.resolveTier(8 * GIB, 8, "qcom", "phone"));
    }

    @Test
    public void testResolveTier_highEndRequiresBothConstraints() {
        Assert.assertEquals(DeviceTierResolver.Tier.MEDIUM,
            DeviceTierResolver.resolveTier(8 * GIB, 7, "qcom", "phone"));
        Assert.assertEquals(DeviceTierResolver.Tier.MEDIUM,
            DeviceTierResolver.resolveTier(7 * GIB, 16, "qcom", "phone"));
    }

    @Test
    public void testResolveTier_unknownValuesFallBackToMedium() {
        Assert.assertEquals(DeviceTierResolver.Tier.MEDIUM,
            DeviceTierResolver.resolveTier(0, 0, null, null));
    }

    @Test
    public void testResolveTier_emulatorIsAlwaysLow() {
        Assert.assertEquals(DeviceTierResolver.Tier.LOW,
            DeviceTierResolver.resolveTier(16 * GIB, 16, "ranchu", "sdk_gphone64_arm64"));
        Assert.assertEquals(DeviceTierResolver.Tier.LOW,
            DeviceTierResolver.resolveTier(16 * GIB, 16, "qcom", "emulator"));
        Assert.assertEquals(DeviceTierResolver.Tier.LOW,
            DeviceTierResolver.resolveTier(16 * GIB, 16, "goldfish", "sdk"));
    }

    @Test
    public void testIsEmulator_realHardwareIsNotEmulator() {
        Assert.assertFalse(DeviceTierResolver.isEmulator("qcom", "phone"));
        Assert.assertFalse(DeviceTierResolver.isEmulator(null, null));
    }

    @Test
    public void testTranscriptRowsForTier_withinValidPropertyRange() {
        Assert.assertEquals(1000, DeviceTierResolver.transcriptRowsForTier(DeviceTierResolver.Tier.LOW));
        Assert.assertEquals(TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS,
            DeviceTierResolver.transcriptRowsForTier(DeviceTierResolver.Tier.MEDIUM));
        Assert.assertEquals(5000, DeviceTierResolver.transcriptRowsForTier(DeviceTierResolver.Tier.HIGH));
        for (DeviceTierResolver.Tier tier : DeviceTierResolver.Tier.values()) {
            int rows = DeviceTierResolver.transcriptRowsForTier(tier);
            Assert.assertTrue(rows >= TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN);
            Assert.assertTrue(rows <= TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX);
        }
    }

    @Test
    public void testCursorBlinkRateForTier() {
        Assert.assertEquals(0, DeviceTierResolver.cursorBlinkRateForTier(DeviceTierResolver.Tier.LOW));
        Assert.assertEquals(TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE,
            DeviceTierResolver.cursorBlinkRateForTier(DeviceTierResolver.Tier.MEDIUM));
        Assert.assertEquals(500, DeviceTierResolver.cursorBlinkRateForTier(DeviceTierResolver.Tier.HIGH));
        int high = DeviceTierResolver.cursorBlinkRateForTier(DeviceTierResolver.Tier.HIGH);
        Assert.assertTrue(high >= TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN);
        Assert.assertTrue(high <= TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX);
    }

    @Test
    public void testNativeRendererForTier() {
        Assert.assertFalse(DeviceTierResolver.nativeRendererForTier(DeviceTierResolver.Tier.LOW));
        Assert.assertEquals(TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_NATIVE_COMPOSE_RENDERER,
            DeviceTierResolver.nativeRendererForTier(DeviceTierResolver.Tier.MEDIUM));
        Assert.assertTrue(DeviceTierResolver.nativeRendererForTier(DeviceTierResolver.Tier.HIGH));
    }

}
