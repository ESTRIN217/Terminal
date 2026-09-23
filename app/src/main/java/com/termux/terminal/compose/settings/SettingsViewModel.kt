package com.termux.terminal.compose.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
                    ?: 1
            )
        }
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
}
