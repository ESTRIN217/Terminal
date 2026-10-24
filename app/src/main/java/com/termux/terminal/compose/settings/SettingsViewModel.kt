package com.termux.terminal.compose.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.app.GpuAccelerationInstaller
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.proot.GpuDetectionLibrary
import com.termux.shared.termux.proot.GpuDeviceReader
import com.termux.shared.termux.proot.VirglLibrary
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel backing the Compose settings screen.
 *
 * All writes go straight to the same SharedPreferences files the old
 * XML fragments used, so behaviour and persistence are unchanged.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val prefs: TermuxAppSharedPreferences? =
        TermuxAppSharedPreferences.build(application, false)

    /** In-flight GPU evaluation job; one evaluation per panel entry at a time. */
    private var mGpuLoadJob: Job? = null

    init {
        reload()
    }

    /** Reload all values from disk. */
    fun reload() {
        val context = getApplication<Application>()
        val p = prefs ?: return
        val sizes = TermuxAppSharedPreferences.getDefaultFontSizes(context)
        _uiState.update {
            it.copy(
                showTerminalToolbar = p.shouldShowTerminalToolbar(),
                terminalMarginAdjustment = p.isTerminalMarginAdjustmentEnabled(),
                softKeyboardEnabled = p.isSoftKeyboardEnabled(),
                softKeyboardOnlyIfNoHardware = p.isSoftKeyboardEnabledOnlyIfNoHardware(),
                keepScreenOn = p.shouldKeepScreenOn(),
                fontSize = p.getFontSize(),
                minFontSize = sizes[1],
                maxFontSize = sizes[2],
                terminalFontId = p.getTerminalFont(),
                terminalFontLigatures = p.isTerminalFontLigaturesEnabled(),
                useCustomColorScheme = p.shouldUseCustomColorScheme(),
                nativeComposeRenderer = p.isNativeComposeRendererEnabled(),
                terminalHyperlinks = p.isTerminalHyperlinksEnabled(),
                terminalImages = p.isTerminalImagesEnabled(),
                force60Hz = p.shouldForce60Hz(),
                logLevel = p.getLogLevel(),
                terminalViewKeyLogging = p.isTerminalViewKeyLoggingEnabled(),
                pluginErrorNotifications = p.arePluginErrorNotificationsEnabled(false),
                crashReportNotifications = p.areCrashReportNotificationsEnabled(false),
                apiInstalled = TermuxAPIAppSharedPreferences.build(context, false) != null,
                apiLogLevel = TermuxAPIAppSharedPreferences.build(context, false)?.getLogLevel(false)
                    ?: 1,
                floatInstalled = TermuxFloatAppSharedPreferences.build(context, false) != null,
                floatLogLevel = TermuxFloatAppSharedPreferences.build(context, false)?.getLogLevel(false)
                    ?: 1,
                taskerInstalled = TermuxTaskerAppSharedPreferences.build(context, false) != null,
                taskerLogLevel = TermuxTaskerAppSharedPreferences.build(context, false)?.getLogLevel(false)
                    ?: 1,
                widgetInstalled = TermuxWidgetAppSharedPreferences.build(context, false) != null,
                widgetLogLevel = TermuxWidgetAppSharedPreferences.build(context, false)?.getLogLevel(false)
                    ?: 1,
                llvmpipeThreadsEnabled = p.isLlvmpipeThreadsEnabled,
                llvmpipeThreadsCount = p.llvmpipeThreadsCount,
                hostCores = Runtime.getRuntime().availableProcessors(),
                isGpuSolutionActive = p.gpuSolution == GpuDetectionLibrary.Solution.ADRENO_KGSL
            )
        }
        // The GPU panel is not a plain preference row: it re-detects per entry.
        reloadGpu()
    }

    fun setShowTerminalToolbar(value: Boolean) {
        prefs?.setShowTerminalToolbar(value)
        _uiState.update { it.copy(showTerminalToolbar = value) }
    }

    fun setTerminalMarginAdjustment(value: Boolean) {
        prefs?.setTerminalMarginAdjustment(value)
        _uiState.update { it.copy(terminalMarginAdjustment = value) }
    }

    fun setSoftKeyboardEnabled(value: Boolean) {
        prefs?.setSoftKeyboardEnabled(value)
        _uiState.update { it.copy(softKeyboardEnabled = value) }
    }

    fun setSoftKeyboardOnlyIfNoHardware(value: Boolean) {
        prefs?.setSoftKeyboardEnabledOnlyIfNoHardware(value)
        _uiState.update { it.copy(softKeyboardOnlyIfNoHardware = value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        prefs?.setKeepScreenOn(value)
        _uiState.update { it.copy(keepScreenOn = value) }
    }

    fun setFontSize(value: Int) {
        prefs?.setFontSize(value)
        _uiState.update { it.copy(fontSize = value) }
    }

    fun setTerminalFont(value: String) {
        prefs?.setTerminalFont(value)
        _uiState.update { it.copy(terminalFontId = value) }
    }

    fun setTerminalFontLigatures(value: Boolean) {
        prefs?.setTerminalFontLigaturesEnabled(value)
        _uiState.update { it.copy(terminalFontLigatures = value) }
    }

    fun setUseCustomColorScheme(value: Boolean) {
        prefs?.setUseCustomColorScheme(value)
        _uiState.update { it.copy(useCustomColorScheme = value) }
    }

    fun setNativeComposeRenderer(value: Boolean) {
        prefs?.setNativeComposeRendererEnabled(value)
        _uiState.update { it.copy(nativeComposeRenderer = value) }
    }

    fun setTerminalHyperlinks(value: Boolean) {
        prefs?.setTerminalHyperlinksEnabled(value)
        _uiState.update { it.copy(terminalHyperlinks = value) }
    }

    fun setTerminalImages(value: Boolean) {
        prefs?.setTerminalImagesEnabled(value)
        _uiState.update { it.copy(terminalImages = value) }
    }

    fun setForce60Hz(value: Boolean) {
        prefs?.setForce60Hz(value)
        _uiState.update { it.copy(force60Hz = value) }
    }

    fun setLogLevel(value: Int) {
        val context = getApplication<Application>()
        prefs?.setLogLevel(context, value)
        _uiState.update { it.copy(logLevel = prefs?.getLogLevel() ?: value) }
    }

    fun setTerminalViewKeyLogging(value: Boolean) {
        prefs?.setTerminalViewKeyLoggingEnabled(value)
        _uiState.update { it.copy(terminalViewKeyLogging = value) }
    }

    fun setPluginErrorNotifications(value: Boolean) {
        prefs?.setPluginErrorNotificationsEnabled(value)
        _uiState.update { it.copy(pluginErrorNotifications = value) }
    }

    fun setCrashReportNotifications(value: Boolean) {
        prefs?.setCrashReportNotificationsEnabled(value)
        _uiState.update { it.copy(crashReportNotifications = value) }
    }

    fun setPluginLogLevel(plugin: String, value: Int) {
        val context = getApplication<Application>()
        when (plugin) {
            "api" -> {
                TermuxAPIAppSharedPreferences.build(context, false)?.setLogLevel(context, value, false)
                _uiState.update { it.copy(apiLogLevel = value) }
            }
            "float" -> {
                TermuxFloatAppSharedPreferences.build(context, false)?.setLogLevel(context, value, false)
                _uiState.update { it.copy(floatLogLevel = value) }
            }
            "tasker" -> {
                TermuxTaskerAppSharedPreferences.build(context, false)?.setLogLevel(context, value, false)
                _uiState.update { it.copy(taskerLogLevel = value) }
            }
            "widget" -> {
                TermuxWidgetAppSharedPreferences.build(context, false)?.setLogLevel(context, value, false)
                _uiState.update { it.copy(widgetLogLevel = value) }
            }
            else -> Logger.logWarn(LOG_TAG, "Unknown plugin for log level: $plugin")
        }
    }

    companion object {
        private const val LOG_TAG = "SettingsViewModel"
    }

    /**
     * Evaluate the GPU panel state (spec 011, RF-1, RF-2, RF-6): detect the GPU
     * family on the IO dispatcher, read the guest mesa version through the
     * one-shot dpkg-query runner (only when rootfs + family make it decisive
     * for the gate) and evaluate the action gate against the persisted
     * solution. While evaluating, the state shows the honest «consulting»
     * placeholder; every failure degrades to a stale/honest display via
     * [Logger], never a guessed family or a guessed action.
     *
     * Invoked on every panel entry ([reload]) and after each GPU operation.
     */
    fun reloadGpu() {
        if (mGpuLoadJob?.isActive == true) return
        _uiState.update {
            it.copy(gpuStatusLine = "", gpuActionType = GpuPanelAction.NONE, gpuReasonKey = null)
        }
        mGpuLoadJob = viewModelScope.launch {
            try {
                evaluateGpu()
            } catch (e: Exception) {
                // RF-2 honesty: a failed evaluation reopens the «consulting»
                // placeholder instead of showing a guessed state.
                Logger.logErrorExtended(LOG_TAG, "GPU panel evaluation failed\n" + e)
                _uiState.update {
                    it.copy(
                        gpuStatusLine = GpuDetectionLibrary.STATE_KEY_UNKNOWN,
                        gpuActionType = GpuPanelAction.INFO,
                        gpuReasonKey = GpuDetectionLibrary.REASON_NO_GPU_DATA
                    )
                }
            }
        }
    }

    /** The blocking body of [reloadGpu]; must run off the main thread. */
    private suspend fun evaluateGpu() {
        val context = getApplication<Application>()
        withContext(Dispatchers.IO) {
            val familyResult = GpuDetectionLibrary.detectFamily(GpuDeviceReader.read())
            val rootfsInstalled = GpuAccelerationInstaller.isRootfsInstalled()

            // A dpkg-query costs a guest session: ask only when the version can
            // be decisive for the gate (family passes and rootfs exists).
            val needsVersion = familyResult.family == GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL
                && rootfsInstalled
            val guestVersion = if (needsVersion)
                GpuAccelerationInstaller.queryGuestMesaVersionBlocking(context)
            else
                null

            val gate = GpuDetectionLibrary.evaluateAction(
                familyResult.family,
                rootfsInstalled,
                guestVersion,
                TermuxConstants.DEBIAN_MESA_EXPECTED_VERSION,
                prefs?.getGpuSolution()
            )
            val keys = GpuDetectionLibrary.toUiKeys(familyResult, gate)
            val solution = prefs?.getGpuSolution()
            _uiState.update {
                it.copy(
                    gpuFamily = familyResult.family.name,
                    gpuModel = familyResult.model ?: "",
                    gpuStatusLine = keys.stateKey,
                    gpuActionType = when (keys.action) {
                        GpuDetectionLibrary.Action.INSTALL_OFFERED -> GpuPanelAction.INSTALL
                        GpuDetectionLibrary.Action.UNINSTALL_OFFERED -> GpuPanelAction.UNINSTALL
                        else -> GpuPanelAction.INFO
                    },
                    gpuReasonKey = keys.reasonKey,
                    isGpuSolutionActive = solution != GpuDetectionLibrary.Solution.NONE,
                    // Spec 013 (RF-7): honest VirGL liveness, never guessed.
                    virglSolutionActive = solution == GpuDetectionLibrary.Solution.VIRGL,
                    virglServerAlive = solution == GpuDetectionLibrary.Solution.VIRGL
                        && com.termux.app.VirglServerManager.isAlive()
                )
            }
        }
    }

    fun setLlvmpipeThreadsEnabled(value: Boolean) {
        prefs?.isLlvmpipeThreadsEnabled = value
        _uiState.update { it.copy(llvmpipeThreadsEnabled = value) }
    }

    /**
     * VirGL switch (spec 013, RF-2/RF-5/RF-6): evaluates the pure exclusivity
     * gate first; on refusal the reason key lands in the state for the honest
     * message and the pref is NOT changed. On activation the server must start
     * successfully BEFORE the pref is persisted (RF-2(c): a failed start never
     * marks the acceleration active); errors are logged and toasted, never
     * silent. On deactivation the pref clears and the server stops (RF-5).
     */
    fun onVirglSwitchRequested(value: Boolean) {
        val currentPrefs = prefs ?: return
        val state = _uiState.value
        if (state.virglBusy || state.gpuBusy)
            return

        val requested = if (value) GpuDetectionLibrary.Solution.VIRGL else GpuDetectionLibrary.Solution.NONE
        val gate = VirglLibrary.resolveSolutionChange(currentPrefs.getGpuSolution(), requested)
        if (gate.status == VirglLibrary.SolutionGate.Status.NEEDS_OFF) {
            _uiState.update { it.copy(virglReasonKey = gate.reason) }
            return
        }

        _uiState.update { it.copy(virglBusy = true, virglReasonKey = null) }
        val context = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (value) {
                    val error = com.termux.app.VirglServerManager.ensureRunning(context)
                    if (error == null)
                        currentPrefs.setGpuSolution(GpuDetectionLibrary.Solution.VIRGL)
                    else {
                        // RF-2: the pref stays untouched; user sees the failure, log keeps the detail.
                        Logger.logErrorExtended(LOG_TAG, "VirGL activation failed (pref untouched)\n" + error.toString())
                        Logger.showToast(context, error.toString(), false)
                    }
                } else {
                    com.termux.app.VirglServerManager.stopNow(context)
                    currentPrefs.setGpuSolution(GpuDetectionLibrary.Solution.NONE)
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(virglBusy = false) }
                }
                reloadGpu()
            }
        }
    }

    fun setLlvmpipeThreadsCount(value: Int) {
        prefs?.llvmpipeThreadsCount = value
        _uiState.update { it.copy(llvmpipeThreadsCount = value) }
    }

    /** «Instalar aceleración GPU» button; only fires when the gate offers INSTALL (RF-6). */
    fun onGpuInstallRequested() {
        dispatchGpuOperation(false)
    }

    /** «Desinstalar aceleración GPU» button; only fires when the gate offers UNINSTALL (RF-6). */
    fun onGpuUninstallRequested() {
        dispatchGpuOperation(true)
    }

    /**
     * Launch the install/uninstall operation through the T6 executor and drive
     * the panel progress ([SettingsUiState.gpuBusy]). The executor already
     * composes [Error]+[Logger]+toast on any failure (T6 contract) and
     * persists the preference on success only — the panel just reflects the
     * result by re-evaluating. RF-6: the dispatch is refused unless the gate
     * is currently offering exactly this action.
     *
     * @param uninstall {@code true} for the uninstall operation.
     */
    private fun dispatchGpuOperation(uninstall: Boolean) {
        val state = _uiState.value
        val offered = state.gpuActionType == (if (uninstall) GpuPanelAction.UNINSTALL else GpuPanelAction.INSTALL)
        if (!offered || state.gpuBusy)
            return

        _uiState.update { it.copy(gpuBusy = true) }
        val context = getApplication<Application>()
        val callback = object : GpuAccelerationInstaller.GpuAccelerationCallback {
            override fun onProgress(message: String?) {
                Logger.logInfo(LOG_TAG, "GPU progress: " + message)
            }

            override fun onSuccess(newSolution: GpuDetectionLibrary.Solution?) {
                _uiState.update { it.copy(gpuBusy = false) }
                reloadGpu()
            }

            override fun onError(error: Error?) {
                // Already logged and toasted by the executor; the preference
                // did NOT change (RF-3/RF-5) — refresh the panel anyway.
                _uiState.update { it.copy(gpuBusy = false) }
            }
        }
        if (uninstall)
            GpuAccelerationInstaller.uninstallAcceleration(context, callback)
        else
            GpuAccelerationInstaller.installAcceleration(context, callback)
    }
}
