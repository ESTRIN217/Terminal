package com.termux.terminal.compose.settings

/**
 * UI state of the X11 settings screen (spec 017, RF-2). Every field mirrors a
 * core preference of the lorie `Prefs` store; the ViewModel fills it through
 * [X11SettingsPolicy] so the screen only paints and dispatches.
 */
data class X11SettingsUiState(
    // Output section (RF-4..RF-7).
    val resolutionMode: String = X11SettingsPolicy.DEFAULT_OUTPUT_MODE,
    val exactResolution: String = "1280x1024",
    val exactResolutionOptions: List<String> = emptyList(),
    val customResolutionDraft: String = "",
    val customResolutionError: Boolean = false,
    /** Last valid, persisted custom resolution (source for cancel-restore). */
    val customResolution: String = "1280x1024",
    val scale: Int = X11SettingsPolicy.DEFAULT_SCALE,
    val filteringMode: String = X11SettingsPolicy.DEFAULT_FILTERING_MODE,
    val fullscreen: Boolean = false,
    val orientation: String = X11SettingsPolicy.DEFAULT_ORIENTATION,
    // Pointer section (RF-3, RF-6).
    val touchMode: String = X11SettingsPolicy.DEFAULT_TOUCH_MODE,
    val scaleTouchpad: Boolean = true,
    val tapToMove: Boolean = true,
    val zoomFollowsCursor: Boolean = true,
    // Keyboard section (RF-3).
    val showAdditionalKbd: Boolean = true,
    val showImeWhileExternalConnected: Boolean = true,
    val preferScancodes: Boolean = false,
    val hardwareKbdScancodesWorkaround: Boolean = true,
    val filterOutWinkey: Boolean = false
)
