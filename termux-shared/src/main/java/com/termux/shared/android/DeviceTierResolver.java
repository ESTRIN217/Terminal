package com.termux.shared.android;

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

import java.util.Locale;

/**
 * Resolves a device performance tier from RAM, CPU cores and hardware identity, and maps
 * the tier to the default settings seeded on the first run of a fresh install (Fase 2.5).
 *
 * <p>Pure functions without Android framework calls so the thresholds and mappings are
 * unit testable. Existing installs are never re-seeded: the values only act as fallback
 * defaults when the corresponding key is absent from {@code termux.properties}, and the
 * {@code native_compose_renderer} preference is only written when it was never set.</p>
 */
public final class DeviceTierResolver {

    /** Performance tier of the device, from constrained to high-end. */
    public enum Tier {
        /** Constrained device or emulator: smallest scrollback, no renderer experiment. */
        LOW,
        /** Typical device: same values as the compiled-in defaults. */
        MEDIUM,
        /** High-end device: larger scrollback, blinking cursor, native renderer on. */
        HIGH
    }

    /** Total RAM strictly below this (4 GiB) classifies the device as {@link Tier#LOW}. */
    public static final long LOW_END_RAM_BELOW_BYTES = 4L * 1024L * 1024L * 1024L;

    /** Total RAM of at least this (8 GiB) is required for {@link Tier#HIGH}. */
    public static final long HIGH_END_RAM_AT_LEAST_BYTES = 8L * 1024L * 1024L * 1024L;

    /** CPU cores at or below this classify the device as {@link Tier#LOW}. */
    public static final int LOW_END_CORES_AT_MOST = 4;

    /** CPU cores at or above this are required for {@link Tier#HIGH}. */
    public static final int HIGH_END_CORES_AT_LEAST = 8;

    /** Seeded scrollback (transcript rows) for {@link Tier#LOW}. */
    public static final int TRANSCRIPT_ROWS_LOW = 1000;

    /** Seeded scrollback (transcript rows) for {@link Tier#MEDIUM}: the compiled-in default. */
    public static final int TRANSCRIPT_ROWS_MEDIUM = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS;

    /** Seeded scrollback (transcript rows) for {@link Tier#HIGH}. */
    public static final int TRANSCRIPT_ROWS_HIGH = 5000;

    /** Seeded cursor blink rate in ms for {@link Tier#LOW}: disabled, the compiled-in default. */
    public static final int CURSOR_BLINK_RATE_LOW = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE;

    /** Seeded cursor blink rate in ms for {@link Tier#MEDIUM}: the compiled-in default. */
    public static final int CURSOR_BLINK_RATE_MEDIUM = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE;

    /** Seeded cursor blink rate in ms for {@link Tier#HIGH}: a standard blinking cursor. */
    public static final int CURSOR_BLINK_RATE_HIGH = 500;

    /** Seeded native Compose renderer default for {@link Tier#LOW}: the compiled-in default. */
    public static final boolean NATIVE_RENDERER_LOW = TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_NATIVE_COMPOSE_RENDERER;

    /** Seeded native Compose renderer default for {@link Tier#MEDIUM}: the compiled-in default. */
    public static final boolean NATIVE_RENDERER_MEDIUM = TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_NATIVE_COMPOSE_RENDERER;

    /** Seeded native Compose renderer default for {@link Tier#HIGH}: enabled. */
    public static final boolean NATIVE_RENDERER_HIGH = true;

    private DeviceTierResolver() {
    }

    /**
     * Resolve the performance tier for a device.
     *
     * @param totalRamBytes Total RAM in bytes, or {@code 0} if unknown.
     * @param cores         Number of CPU cores, or {@code 0} if unknown.
     * @param hardware      {@code Build.HARDWARE}, may be {@code null}.
     * @param product       {@code Build.PRODUCT}, may be {@code null}.
     * @return The resolved {@link Tier}; never {@code null}.
     */
    public static Tier resolveTier(long totalRamBytes, int cores, String hardware, String product) {
        if (isEmulator(hardware, product)) return Tier.LOW;
        if (totalRamBytes > 0 && totalRamBytes < LOW_END_RAM_BELOW_BYTES) return Tier.LOW;
        if (cores > 0 && cores <= LOW_END_CORES_AT_MOST) return Tier.LOW;
        if (totalRamBytes >= HIGH_END_RAM_AT_LEAST_BYTES && cores >= HIGH_END_CORES_AT_LEAST) return Tier.HIGH;
        return Tier.MEDIUM;
    }

    /**
     * Whether the build identifiers look like an emulator, which is always treated as
     * {@link Tier#LOW} so experiments are not enabled by default on virtual devices.
     *
     * @param hardware {@code Build.HARDWARE}, may be {@code null}.
     * @param product  {@code Build.PRODUCT}, may be {@code null}.
     * @return {@code true} if the build looks like an emulator.
     */
    public static boolean isEmulator(String hardware, String product) {
        String hw = hardware == null ? "" : hardware.toLowerCase(Locale.ROOT);
        String prod = product == null ? "" : product.toLowerCase(Locale.ROOT);
        return hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("emulator")
            || hw.contains("vbox") || hw.contains("gce")
            || prod.contains("emulator") || prod.contains("genymotion")
            || prod.contains("google_sdk") || prod.contains("gphone");
    }

    /**
     * Seeded {@code terminal-transcript-rows} value for a tier.
     *
     * @param tier The device tier.
     * @return The scrollback size in rows, within the valid property range.
     */
    public static int transcriptRowsForTier(Tier tier) {
        switch (tier) {
            case LOW:
                return TRANSCRIPT_ROWS_LOW;
            case HIGH:
                return TRANSCRIPT_ROWS_HIGH;
            case MEDIUM:
            default:
                return TRANSCRIPT_ROWS_MEDIUM;
        }
    }

    /**
     * Seeded {@code terminal-cursor-blink-rate} value for a tier.
     *
     * @param tier The device tier.
     * @return The blink rate in milliseconds; {@code 0} disables blinking.
     */
    public static int cursorBlinkRateForTier(Tier tier) {
        switch (tier) {
            case HIGH:
                return CURSOR_BLINK_RATE_HIGH;
            case LOW:
                return CURSOR_BLINK_RATE_LOW;
            case MEDIUM:
            default:
                return CURSOR_BLINK_RATE_MEDIUM;
        }
    }

    /**
     * Seeded {@code native_compose_renderer} default for a tier.
     *
     * @param tier The device tier.
     * @return {@code true} if the native renderer should be enabled by default.
     */
    public static boolean nativeRendererForTier(Tier tier) {
        switch (tier) {
            case HIGH:
                return NATIVE_RENDERER_HIGH;
            case LOW:
                return NATIVE_RENDERER_LOW;
            case MEDIUM:
            default:
                return NATIVE_RENDERER_MEDIUM;
        }
    }

}
