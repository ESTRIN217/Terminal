package com.termux.terminal.compose.settings

/**
 * UI state for the Compose settings screen.
 *
 * Mirrors the keys previously managed by the XML PreferenceScreen
 * hierarchy (`root_preferences.xml`, `termux_*_preferences.xml`) on top
 * of [com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences].
 */
data class SettingsUiState(
    val showTerminalToolbar: Boolean = true,
    val terminalMarginAdjustment: Boolean = true,
    val softKeyboardEnabled: Boolean = true,
    val softKeyboardOnlyIfNoHardware: Boolean = false,
    val keepScreenOn: Boolean = false,
    val fontSize: Int = 0,
    val minFontSize: Int = 0,
    val maxFontSize: Int = 256,
    val terminalFontId: String = "",
    val terminalFontLigatures: Boolean = true,
    val useCustomColorScheme: Boolean = false,
    val logLevel: Int = 1,
    val terminalViewKeyLogging: Boolean = false,
    val pluginErrorNotifications: Boolean = true,
    val crashReportNotifications: Boolean = true,
    val apiInstalled: Boolean = false,
    val apiLogLevel: Int = 1,
    val floatInstalled: Boolean = false,
    val floatLogLevel: Int = 1,
    val taskerInstalled: Boolean = false,
    val taskerLogLevel: Int = 1,
    val widgetInstalled: Boolean = false,
    val widgetLogLevel: Int = 1
)
