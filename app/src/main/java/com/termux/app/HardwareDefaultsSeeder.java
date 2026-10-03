package com.termux.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.shared.android.DeviceTierResolver;
import com.termux.shared.logger.Logger;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

/**
 * Seeds device-tier default settings on the first run of a fresh install (Fase 2.5, item 14).
 *
 * <p>Detects RAM, CPU cores and {@code Build.HARDWARE}/{@code Build.PRODUCT}, resolves a
 * {@link DeviceTierResolver.Tier} and persists the resulting defaults:</p>
 * <ul>
 *     <li>{@code terminal-transcript-rows} and {@code terminal-cursor-blink-rate} as
 *         SharedPreferences seeds, injected by {@code TermuxSharedProperties} as fallback
 *     defaults when the keys are absent from {@code termux.properties} — the user file is
 *     never written and explicit values always win;</li>
 *     <li>{@code native_compose_renderer} written directly (fresh installs only).</li>
 * </ul>
 *
 * <p>Existing installs only get the {@code hardware_defaults_seeded} marker so their
 * behaviour never changes. The marker is written last and synchronously; on any failure
 * it stays unset and the seed retries on the next launch. Seeding must run before any
 * other preference write (see {@link TermuxApplication#onCreate()}) because fresh-install
 * detection requires the preferences file to still be empty.</p>
 */
final class HardwareDefaultsSeeder {

    private static final String LOG_TAG = "HardwareDefaultsSeeder";

    private HardwareDefaultsSeeder() {
    }

    /**
     * Evaluate and persist device-tier defaults once per install. No-op when the marker
     * is already present.
     *
     * @param context The application {@link Context}.
     */
    static void seedIfFreshInstall(@NonNull Context context) {
        try {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, false);
            if (preferences == null) {
                Logger.logError(LOG_TAG, "SharedPreferences unavailable; skipping device-tier defaults seed");
                return;
            }
            if (preferences.isHardwareDefaultsSeeded()) return;

            boolean preferencesEmpty = SharedPreferenceUtils
                .getPrivateSharedPreferences(context,
                    TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
                .getAll().isEmpty();
            boolean rootfsInstalled = DebianInstaller.isInstalled();
            boolean propertiesFileExists = SharedProperties.getPropertiesFileFromList(
                TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, LOG_TAG) != null;

            if (isFreshInstall(preferencesEmpty, rootfsInstalled, propertiesFileExists)) {
                seed(context, preferences);
            } else {
                Logger.logInfo(LOG_TAG,
                    "Existing install detected; recording marker without seeding device-tier defaults");
            }

            // Marker last and synchronous: a failed seed retries on the next launch.
            preferences.setHardwareDefaultsSeeded(true);
        } catch (Exception e) {
            // Never crash at startup over seeding; the missing marker retries next launch.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to seed device-tier defaults", e);
        }
    }

    /**
     * Whether the install is fresh enough to receive seeded defaults. All three signals
     * must agree: no preference was ever written, the Debian rootfs has not been
     * installed and the user never created a {@code termux.properties} file.
     *
     * @param preferencesEmpty     {@code true} if the app preferences file has no entries.
     * @param rootfsInstalled      {@code true} if the Debian rootfs is installed.
     * @param propertiesFileExists {@code true} if a user {@code termux.properties} exists.
     * @return {@code true} only for a fresh install.
     */
    static boolean isFreshInstall(boolean preferencesEmpty, boolean rootfsInstalled, boolean propertiesFileExists) {
        return preferencesEmpty && !rootfsInstalled && !propertiesFileExists;
    }

    private static void seed(@NonNull Context context, @NonNull TermuxAppSharedPreferences preferences) {
        long totalRamBytes = 0;
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            totalRamBytes = memoryInfo.totalMem;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        DeviceTierResolver.Tier tier =
            DeviceTierResolver.resolveTier(totalRamBytes, cores, Build.HARDWARE, Build.PRODUCT);
        int transcriptRows = DeviceTierResolver.transcriptRowsForTier(tier);
        int blinkRate = DeviceTierResolver.cursorBlinkRateForTier(tier);
        boolean nativeRenderer = DeviceTierResolver.nativeRendererForTier(tier);

        preferences.setSeededTerminalTranscriptRows(transcriptRows);
        preferences.setSeededTerminalCursorBlinkRate(blinkRate);
        preferences.setNativeComposeRendererEnabled(nativeRenderer);

        Logger.logInfo(LOG_TAG, "Seeded device-tier defaults: tier=" + tier
            + ", ramBytes=" + totalRamBytes + ", cores=" + cores
            + ", hardware=" + Build.HARDWARE + ", transcriptRows=" + transcriptRows
            + ", blinkRate=" + blinkRate + ", nativeRenderer=" + nativeRenderer);
    }

}
