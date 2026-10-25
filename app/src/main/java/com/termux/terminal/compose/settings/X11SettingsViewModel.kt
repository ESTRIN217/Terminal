package com.termux.terminal.compose.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.termux.shared.logger.Logger
import com.termux.x11.LorieApp
import com.termux.x11.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel backing the Compose X11 settings screen (spec 017).
 *
 * Only reads and dispatches: the value of every access goes through
 * [X11SettingsPolicy] decisions, and persistence happens exclusively through
 * the lorie [Prefs] store obtained from [LorieApp.getPrefs] — the same store
 * the X server tabs consume, so their live-refresh wiring applies every change.
 */
class X11SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(X11SettingsUiState())
    val uiState: StateFlow<X11SettingsUiState> = _uiState.asStateFlow()

    /**
     * The lorie preference store for the default display. Null when this
     * process is not hosted by a [LorieApp] subclass (defensive: build
     * variants); every setter no-ops and logs instead of writing raw keys.
     */
    private val mPrefs: Prefs? =
        (application as? LorieApp)?.getPrefs((application as LorieApp))

    init {
        reload()
    }

    /** Re-read every core preference from the store (RF-2, RF-9). */
    fun reload() {
        val p = mPrefs ?: return
        val resolutions = runCatching { p.displayResolutionExact.getValues() }
            .onFailure { Logger.logErrorExtended(LOG_TAG, "Failed to read exact resolutions\n" + it) }
            .getOrDefault(emptyArray())
        _uiState.update {
            it.copy(
                resolutionMode = X11SettingsPolicy.sanitizeListValue(
                    p.displayResolutionMode.get(), X11SettingsPolicy.OUTPUT_MODES,
                    X11SettingsPolicy.DEFAULT_OUTPUT_MODE
                ),
                exactResolution = X11SettingsPolicy.sanitizeListValue(
                    p.displayResolutionExact.get(), resolutions.toList(),
                    if (resolutions.isNotEmpty()) resolutions[0] else "1280x1024"
                ),
                exactResolutionOptions = resolutions.toList(),
                customResolutionDraft = p.displayResolutionCustom.get() ?: "",
                customResolution = p.displayResolutionCustom.get() ?: "",
                scale = X11SettingsPolicy.sanitizeScale(p.displayScale.get()),
                filteringMode = X11SettingsPolicy.sanitizeListValue(
                    p.displayFilteringMode.get(), X11SettingsPolicy.FILTERING_MODES,
                    X11SettingsPolicy.DEFAULT_FILTERING_MODE
                ),
                fullscreen = p.fullscreen.get(),
                orientation = X11SettingsPolicy.sanitizeListValue(
                    p.forceOrientation.get(), X11SettingsPolicy.ORIENTATION_MODES,
                    X11SettingsPolicy.DEFAULT_ORIENTATION
                ),
                touchMode = X11SettingsPolicy.sanitizeListValue(
                    p.touchMode.get(), X11SettingsPolicy.TOUCH_MODES,
                    X11SettingsPolicy.DEFAULT_TOUCH_MODE
                ),
                scaleTouchpad = p.scaleTouchpad.get(),
                tapToMove = p.tapToMove.get(),
                zoomFollowsCursor = p.zoomFollowsCursor.get(),
                showAdditionalKbd = p.showAdditionalKbd.get(),
                showImeWhileExternalConnected = p.showIMEWhileExternalConnected.get(),
                preferScancodes = p.preferScancodes.get(),
                hardwareKbdScancodesWorkaround = p.hardwareKbdScancodesWorkaround.get(),
                filterOutWinkey = p.filterOutWinkey.get()
            )
        }
    }

    // Output setters (RF-3..RF-7).

    fun setResolutionMode(value: String) {
        val mode = X11SettingsPolicy.sanitizeListValue(
            value, X11SettingsPolicy.OUTPUT_MODES, X11SettingsPolicy.DEFAULT_OUTPUT_MODE
        )
        withPrefs { it.displayResolutionMode.put(mode) }
        _uiState.update { it.copy(resolutionMode = mode, customResolutionError = false) }
    }

    fun setExactResolution(value: String) {
        val sanitized = X11SettingsPolicy.sanitizeListValue(
            value, _uiState.value.exactResolutionOptions, _uiState.value.exactResolution
        )
        withPrefs { it.displayResolutionExact.put(sanitized) }
        _uiState.update { it.copy(exactResolution = sanitized) }
    }

    /** Edit the custom-resolution field: validates, never persists (RF-7). */
    fun setCustomResolutionDraft(value: String) {
        val valid = X11SettingsPolicy.validateCustomResolution(value)
        _uiState.update {
            it.copy(
                customResolutionDraft = value,
                customResolutionError = valid is X11SettingsPolicy.X11ResolutionValidation.Invalid
            )
        }
    }

    /** Commit the custom resolution: only persisted when well formed (RF-7). */
    fun applyCustomResolution() {
        when (val result = X11SettingsPolicy.validateCustomResolution(_uiState.value.customResolutionDraft)) {
            is X11SettingsPolicy.X11ResolutionValidation.Valid -> {
                withPrefs { it.displayResolutionCustom.put(result.resolution) }
                _uiState.update {
                    it.copy(
                        customResolution = result.resolution,
                        customResolutionDraft = result.resolution,
                        customResolutionError = false
                    )
                }
            }
            is X11SettingsPolicy.X11ResolutionValidation.Invalid -> {
                // Nothing persisted: the screen keeps showing the error text.
            }
        }
    }

    fun setScale(value: Int) {
        val sanitized = X11SettingsPolicy.sanitizeScale(value)
        withPrefs { it.displayScale.put(sanitized) }
        _uiState.update { it.copy(scale = sanitized) }
    }

    fun setFilteringMode(value: String) {
        val mode = X11SettingsPolicy.sanitizeListValue(
            value, X11SettingsPolicy.FILTERING_MODES, X11SettingsPolicy.DEFAULT_FILTERING_MODE
        )
        withPrefs { it.displayFilteringMode.put(mode) }
        _uiState.update { it.copy(filteringMode = mode) }
    }

    fun setFullscreen(value: Boolean) {
        withPrefs { it.fullscreen.put(value) }
        _uiState.update { it.copy(fullscreen = value) }
    }

    fun setOrientation(value: String) {
        val orientation = X11SettingsPolicy.sanitizeListValue(
            value, X11SettingsPolicy.ORIENTATION_MODES, X11SettingsPolicy.DEFAULT_ORIENTATION
        )
        withPrefs { it.forceOrientation.put(orientation) }
        _uiState.update { it.copy(orientation = orientation) }
    }

    // Pointer setters (RF-3).

    fun setTouchMode(value: String) {
        val mode = X11SettingsPolicy.sanitizeListValue(
            value, X11SettingsPolicy.TOUCH_MODES, X11SettingsPolicy.DEFAULT_TOUCH_MODE
        )
        withPrefs { it.touchMode.put(mode) }
        _uiState.update { it.copy(touchMode = mode) }
    }

    fun setScaleTouchpad(value: Boolean) {
        withPrefs { it.scaleTouchpad.put(value) }
        _uiState.update { it.copy(scaleTouchpad = value) }
    }

    fun setTapToMove(value: Boolean) {
        withPrefs { it.tapToMove.put(value) }
        _uiState.update { it.copy(tapToMove = value) }
    }

    fun setZoomFollowsCursor(value: Boolean) {
        withPrefs { it.zoomFollowsCursor.put(value) }
        _uiState.update { it.copy(zoomFollowsCursor = value) }
    }

    // Keyboard setters (RF-3).

    fun setShowAdditionalKbd(value: Boolean) {
        withPrefs { it.showAdditionalKbd.put(value) }
        _uiState.update { it.copy(showAdditionalKbd = value) }
    }

    fun setShowImeWhileExternalConnected(value: Boolean) {
        withPrefs { it.showIMEWhileExternalConnected.put(value) }
        _uiState.update { it.copy(showImeWhileExternalConnected = value) }
    }

    fun setPreferScancodes(value: Boolean) {
        withPrefs { it.preferScancodes.put(value) }
        _uiState.update { it.copy(preferScancodes = value) }
    }

    fun setHardwareKbdScancodesWorkaround(value: Boolean) {
        withPrefs { it.hardwareKbdScancodesWorkaround.put(value) }
        _uiState.update { it.copy(hardwareKbdScancodesWorkaround = value) }
    }

    fun setFilterOutWinkey(value: Boolean) {
        withPrefs { it.filterOutWinkey.put(value) }
        _uiState.update { it.copy(filterOutWinkey = value) }
    }

    /**
     * Run [block] against the store when available; otherwise log (the state
     * keeps its value so the screen stays coherent).
     */
    private inline fun withPrefs(block: (Prefs) -> Unit) {
        val p = mPrefs
        if (p == null) {
            Logger.logError(LOG_TAG, "Preference store unavailable; change not persisted")
            return
        }
        try {
            block(p)
        } catch (e: Exception) {
            Logger.logErrorExtended(LOG_TAG, "Failed to persist X11 preference\n" + e)
        }
    }

    companion object {
        private const val LOG_TAG = "X11SettingsViewModel"
    }
}
