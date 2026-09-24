package com.termux.shared.termux.settings.preferences;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.android.PackageUtils;
import com.termux.shared.settings.preferences.AppSharedPreferences;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

public class TermuxAppSharedPreferences extends AppSharedPreferences {

    private int MIN_FONTSIZE;
    private int MAX_FONTSIZE;
    private int DEFAULT_FONTSIZE;

    private static final String LOG_TAG = "TermuxAppSharedPreferences";

    private TermuxAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));

        setFontVariables(context);
    }

    /**
     * Get {@link TermuxAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     * @return Returns the {@link TermuxAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static TermuxAppSharedPreferences build(@NonNull final Context context) {
        Context termuxPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME);
        if (termuxPackageContext == null)
            return null;
        else
            return new TermuxAppSharedPreferences(termuxPackageContext);
    }

    /**
     * Get {@link TermuxAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link TermuxAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static TermuxAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context termuxPackageContext = TermuxUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_PACKAGE_NAME, exitAppOnError);
        if (termuxPackageContext == null)
            return null;
        else
            return new TermuxAppSharedPreferences(termuxPackageContext);
    }



    public boolean shouldShowTerminalToolbar() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR);
    }

    public void setShowTerminalToolbar(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, value, false);
    }

    public boolean toogleShowTerminalToolbar() {
        boolean currentValue = shouldShowTerminalToolbar();
        setShowTerminalToolbar(!currentValue);
        return !currentValue;
    }



    public boolean isTerminalMarginAdjustmentEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, TERMUX_APP.DEFAULT_TERMINAL_MARGIN_ADJUSTMENT);
    }

    public void setTerminalMarginAdjustment(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, value, false);
    }



    public boolean isSoftKeyboardEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED);
    }

    public void setSoftKeyboardEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, value, false);
    }

    public boolean isSoftKeyboardEnabledOnlyIfNoHardware() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE);
    }

    public void setSoftKeyboardEnabledOnlyIfNoHardware(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value, false);
    }



    public boolean shouldKeepScreenOn() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON);
    }

    public void setKeepScreenOn(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, value, false);
    }



    public static int[] getDefaultFontSizes(Context context) {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());

        int[] sizes = new int[3];

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
        // to prevent invisible text due to zoom be mistake:
        sizes[1] = (int) (4f * dipInPixels); // min

        // http://www.google.com/design/spec/style/typography.html#typography-line-height
        int defaultFontSize = Math.round(12 * dipInPixels);
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        sizes[0] = defaultFontSize; // default

        sizes[2] = 256; // max

        return sizes;
    }

    public void setFontVariables(Context context) {
        int[] sizes = getDefaultFontSizes(context);

        DEFAULT_FONTSIZE = sizes[0];
        MIN_FONTSIZE = sizes[1];
        MAX_FONTSIZE = sizes[2];
    }

    public int getFontSize() {
        int fontSize = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE);
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE);
    }

    public void setFontSize(int value) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, value, false);
    }

    public void changeFontSize(boolean increase) {
        int fontSize = getFontSize();

        fontSize += (increase ? 1 : -1) * 2;
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE));

        setFontSize(fontSize);
    }



    /**
     * Get the identifier of the terminal font. The empty string selects the default monospace
     * font, {@code "fonts/<name>.ttf"} a font bundled in the app assets, and {@code "custom"} the
     * {@code ~/.termux/font.ttf} file (custom font).
     *
     * @return The terminal font identifier.
     */
    public String getTerminalFont() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_FONT, TERMUX_APP.DEFAULT_VALUE_TERMINAL_FONT, false);
    }

    public void setTerminalFont(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_FONT, value, false);
    }



    /**
     * Whether OpenType ligature shaping is enabled in the terminal renderer.
     *
     * @return {@code true} if ligatures should be rendered when the selected font supports them.
     */
    public boolean isTerminalFontLigaturesEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_FONT_LIGATURES, TERMUX_APP.DEFAULT_VALUE_TERMINAL_FONT_LIGATURES);
    }

    public void setTerminalFontLigaturesEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_FONT_LIGATURES, value, false);
    }



    public String getCurrentSession() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, null, true);
    }

    public void setCurrentSession(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, value, false);
    }



    public int getLogLevel() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, logLevel, false);
    }



    public int getLastNotificationId() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID);
    }

    public void setLastNotificationId(int notificationId) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, notificationId, false);
    }


    public synchronized int getAndIncrementAppShellNumberSinceBoot() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true, Integer.MAX_VALUE);
    }

    public synchronized void resetAppShellNumberSinceBoot() {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true);
    }

    public synchronized int getAndIncrementTerminalSessionNumberSinceBoot() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true, Integer.MAX_VALUE);
    }

    public synchronized void resetTerminalSessionNumberSinceBoot() {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true);
    }


    public boolean isTerminalViewKeyLoggingEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TERMUX_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED);
    }

    public void setTerminalViewKeyLoggingEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, false);
    }


    /**
     * Whether a custom terminal color scheme from the {@code colors.properties} file is enabled.
     *
     * @return {@code true} if the custom color scheme should be applied.
     */
    public boolean shouldUseCustomColorScheme() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_CUSTOM_COLOR_SCHEME, TERMUX_APP.DEFAULT_VALUE_USE_CUSTOM_COLOR_SCHEME);
    }

    public void setUseCustomColorScheme(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_CUSTOM_COLOR_SCHEME, value, false);
    }



    /**
     * Whether the experimental native Compose Canvas terminal renderer is enabled.
     *
     * @return {@code true} if the Compose Canvas renderer should be used instead of the legacy view.
     */
    public boolean isNativeComposeRendererEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_NATIVE_COMPOSE_RENDERER, TERMUX_APP.DEFAULT_VALUE_NATIVE_COMPOSE_RENDERER);
    }

    public void setNativeComposeRendererEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_NATIVE_COMPOSE_RENDERER, value, false);
    }



    /**
     * Whether OSC 8 hyperlinks (underlined cells that open on tap) are enabled.
     *
     * @return {@code true} if hyperlinks should be underlined and open on tap.
     */
    public boolean isTerminalHyperlinksEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_HYPERLINKS, TERMUX_APP.DEFAULT_VALUE_TERMINAL_HYPERLINKS);
    }

    public void setTerminalHyperlinksEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_HYPERLINKS, value, false);
    }

    /**
     * Whether inline terminal images (OSC 1337 / kitty) are painted.
     *
     * @return true when images are enabled (default true)
     */
    public boolean isTerminalImagesEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_IMAGES, TERMUX_APP.DEFAULT_VALUE_TERMINAL_IMAGES);
    }

    /**
     * Persist the terminal images kill-switch.
     *
     * @param value whether inline images should be shown
     */
    public void setTerminalImagesEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_IMAGES, value, false);
    }



    /**
     * Whether the display is locked to 60 Hz instead of being switched to the maximum
     * supported refresh rate at the current resolution.
     *
     * @return {@code true} to force the 60 Hz display mode (battery saving).
     */
    public boolean shouldForce60Hz() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_FORCE_60HZ, TERMUX_APP.DEFAULT_VALUE_FORCE_60HZ);
    }

    public void setForce60Hz(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_FORCE_60HZ, value, false);
    }



    /**
     * Whether device-tier defaults were already evaluated for this install by the
     * hardware defaults seeder.
     *
     * @return {@code true} once the first-run seed has run (fresh or existing install).
     */
    public boolean isHardwareDefaultsSeeded() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_HARDWARE_DEFAULTS_SEEDED, TERMUX_APP.DEFAULT_VALUE_HARDWARE_DEFAULTS_SEEDED);
    }

    /**
     * Set the hardware defaults seeded marker. Committed synchronously so a crash cannot
     * cause the first-run seed to run twice.
     *
     * @param value Whether the seed has been evaluated.
     */
    public void setHardwareDefaultsSeeded(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_HARDWARE_DEFAULTS_SEEDED, value, true);
    }

    /**
     * Set the seeded fallback default for the {@code terminal-transcript-rows} property.
     *
     * @param value Scrollback rows computed for the device tier.
     */
    public void setSeededTerminalTranscriptRows(int value) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_SEEDED_TERMINAL_TRANSCRIPT_ROWS, value, false);
    }

    /**
     * Set the seeded fallback default for the {@code terminal-cursor-blink-rate} property.
     *
     * @param value Blink rate in milliseconds; {@code 0} disables blinking.
     */
    public void setSeededTerminalCursorBlinkRate(int value) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_SEEDED_TERMINAL_CURSOR_BLINK_RATE, value, false);
    }



    public boolean arePluginErrorNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
        else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
    }

    public void setPluginErrorNotificationsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, value, false);
    }



    public boolean areCrashReportNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
       else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
    }

    public void setCrashReportNotificationsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, value, false);
    }

}
