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
    val nativeComposeRenderer: Boolean = false,
    val terminalHyperlinks: Boolean = true,
    val terminalImages: Boolean = true,
    val force60Hz: Boolean = false,
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
    val widgetLogLevel: Int = 1,

    /**
     * GPU panel (spec 011, RF-2/RF-6). All fields are evaluated once per panel
     * entry ([com.termux.app.activities.SettingsComposeActivity] reloads on
     * resume) on the ViewModel's IO dispatcher: empty placeholder values
     * ([gpuStatusLine] `""`, [gpuActionType] NONE) render the honest
     * «consulting» line, never a guessed family.
     */
    val gpuFamily: String = "",
    /** Sanitized sysfs model for ADRENO_CON_KGSL, empty when none. */
    val gpuModel: String = "",
    /** Stable state-line key from GpuDetectionLibrary.STATE_KEY_*, empty until evaluated. */
    val gpuStatusLine: String = "",
    /** Panel action the gate allows; the buttons exist only for INSTALL/UNINSTALL (RF-6). */
    val gpuActionType: GpuPanelAction = GpuPanelAction.NONE,
    /** Stable reason key from GpuDetectionLibrary.REASON_* for only-info, null when none. */
    val gpuReasonKey: String? = null,
    /** Whether an install/uninstall operation is running (drives the progress bar). */
    val gpuBusy: Boolean = false,

    /**
     * Llvmpipe multithreading lever (spec 012, RF-1, RF-2).
     */
    val llvmpipeThreadsEnabled: Boolean = true,
    val llvmpipeThreadsCount: Int = 8,
    val hostCores: Int = Runtime.getRuntime().availableProcessors(),
    val isGpuSolutionActive: Boolean = false
)

/**
 * Composable panel action of the GPU section (spec 011 T8). Mirrors
 * [com.termux.shared.termux.proot.GpuDetectionLibrary.Action] minus the
 * installer-flavoured names: NONE = not evaluated yet, INFO = only-information
 * mode (no action offered, RF-6).
 */
enum class GpuPanelAction { NONE, INSTALL, UNINSTALL, INFO }
