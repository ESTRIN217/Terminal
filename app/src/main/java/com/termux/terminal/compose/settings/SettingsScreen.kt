package com.termux.terminal.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.terminal.compose.SettingsCardGroup
import com.termux.terminal.compose.SettingsDialogTile
import com.termux.terminal.compose.SettingsListTile
import com.termux.terminal.compose.SettingsSectionTitle
import com.termux.terminal.compose.SettingsSliderTile
import com.termux.terminal.compose.SettingsSwitchTile

@Composable
private fun logLevelLabels(): List<String> = listOf(
    stringResource(R.string.log_level_off),
    stringResource(R.string.log_level_normal),
    stringResource(R.string.log_level_debug),
    stringResource(R.string.log_level_verbose)
)

/**
 * Root settings screen: Termux + terminal I/O + debugging + plugins + about.
 *
 * Replaces `SettingsActivity` + all XML preference fragments. No Donate entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateUp: () -> Unit,
    onAboutClick: () -> Unit,
    onImportFont: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_activity_termux_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.action_go_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionTitle(title = stringResource(R.string.application_name))
            SettingsCardGroup {
                SettingsSwitchTile(
                    title = stringResource(R.string.show_extra_keys),
                    subtitle = stringResource(R.string.show_extra_keys_desc),
                    icon = Icons.Default.Keyboard,
                    checked = state.showTerminalToolbar,
                    onCheckedChange = viewModel::setShowTerminalToolbar
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.termux_terminal_view_terminal_margin_adjustment_title),
                    subtitle = if (state.terminalMarginAdjustment) stringResource(R.string.termux_terminal_view_terminal_margin_adjustment_on) else stringResource(R.string.termux_terminal_view_terminal_margin_adjustment_off),
                    icon = Icons.Default.Monitor,
                    checked = state.terminalMarginAdjustment,
                    onCheckedChange = viewModel::setTerminalMarginAdjustment
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.action_toggle_keep_screen_on),
                    subtitle = stringResource(R.string.action_toggle_keep_screen_on_desc),
                    icon = Icons.Default.Visibility,
                    checked = state.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn
                )
                SettingsSliderTile(
                    title = stringResource(R.string.font_size),
                    subtitle = stringResource(R.string.font_size_desc),
                    icon = Icons.Default.TextFields,
                    value = state.fontSize.toFloat(),
                    valueRange = state.minFontSize.toFloat()..state.maxFontSize.toFloat(),
                    valueLabel = state.fontSize.toString(),
                    onValueChangeFinished = { viewModel.setFontSize(it.toInt()) }
                )
                // Terminal font selector: default, bundled fonts, and the custom ~/.termux/font.ttf
                // (custom ~/.termux/font.ttf) when present.
                val customFontFile = com.termux.shared.termux.TermuxConstants.TERMUX_FONT_FILE
                val fontOptionLabels = buildList {
                    add(stringResource(R.string.font_default))
                    com.termux.terminal.compose.TerminalFontCatalog.bundledFonts.forEach { add(stringResource(it.labelRes)) }
                    if (customFontFile.isFile) add(stringResource(R.string.font_custom))
                }
                val fontOptionIds = buildList {
                    add("")
                    com.termux.terminal.compose.TerminalFontCatalog.bundledFonts.forEach { add(it.id) }
                    if (customFontFile.isFile) add(com.termux.terminal.compose.TerminalFontCatalog.CUSTOM_FONT_ID)
                }
                val selectedFontIndex = fontOptionIds.indexOf(state.terminalFontId).coerceAtLeast(0)
                SettingsDialogTile(
                    leadingIcon = Icons.Default.Code,
                    title = stringResource(R.string.terminal_font),
                    options = fontOptionLabels,
                    selectedIndex = selectedFontIndex,
                    onSelected = { viewModel.setTerminalFont(fontOptionIds[it]) }
                )
                SettingsListTile(
                    leadingIcon = Icons.Default.FileUpload,
                    title = stringResource(R.string.font_import),
                    subtitle = stringResource(R.string.font_import_desc),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = onImportFont
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.terminal_font_ligatures),
                    subtitle = stringResource(R.string.terminal_font_ligatures_desc),
                    icon = Icons.Default.Code,
                    checked = state.terminalFontLigatures,
                    onCheckedChange = viewModel::setTerminalFontLigatures
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.use_custom_color_scheme),
                    subtitle = stringResource(R.string.use_custom_color_scheme_desc),
                    icon = Icons.Default.Palette,
                    checked = state.useCustomColorScheme,
                    onCheckedChange = viewModel::setUseCustomColorScheme
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.native_compose_renderer),
                    subtitle = stringResource(R.string.native_compose_renderer_desc),
                    icon = Icons.Default.Terminal,
                    checked = state.nativeComposeRenderer,
                    onCheckedChange = viewModel::setNativeComposeRenderer
                )
            }

            SettingsSectionTitle(title = stringResource(R.string.action_toggle_soft_keyboard))
            SettingsCardGroup {
                SettingsSwitchTile(
                    title = stringResource(R.string.termux_soft_keyboard_enabled_title),
                    subtitle = if (state.softKeyboardEnabled) stringResource(R.string.termux_soft_keyboard_enabled_on) else stringResource(R.string.termux_soft_keyboard_enabled_off),
                    icon = Icons.Default.Keyboard,
                    checked = state.softKeyboardEnabled,
                    onCheckedChange = viewModel::setSoftKeyboardEnabled
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_title),
                    subtitle = if (state.softKeyboardOnlyIfNoHardware) stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_on) else stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_off),
                    icon = Icons.Default.Smartphone,
                    checked = state.softKeyboardOnlyIfNoHardware,
                    onCheckedChange = viewModel::setSoftKeyboardOnlyIfNoHardware
                )
            }

            SettingsSectionTitle(title = stringResource(R.string.termux_debugging_preferences_title))
            SettingsCardGroup {
                SettingsDialogTile(
                    leadingIcon = Icons.Default.BugReport,
                    title = stringResource(R.string.termux_log_level_title),
                    options = logLevelLabels(),
                    selectedIndex = state.logLevel.coerceIn(0, 3),
                    onSelected = viewModel::setLogLevel
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.termux_terminal_view_key_logging_enabled_title),
                    subtitle = if (state.terminalViewKeyLogging) stringResource(R.string.termux_terminal_view_key_logging_enabled_on) else stringResource(R.string.termux_terminal_view_key_logging_enabled_off),
                    icon = Icons.Default.Tune,
                    checked = state.terminalViewKeyLogging,
                    onCheckedChange = viewModel::setTerminalViewKeyLogging
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.termux_plugin_error_notifications_enabled_title),
                    subtitle = if (state.pluginErrorNotifications) stringResource(R.string.termux_plugin_error_notifications_enabled_on) else stringResource(R.string.termux_plugin_error_notifications_enabled_off),
                    icon = Icons.Default.ErrorOutline,
                    checked = state.pluginErrorNotifications,
                    onCheckedChange = viewModel::setPluginErrorNotifications
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.termux_crash_report_notifications_enabled_title),
                    subtitle = if (state.crashReportNotifications) stringResource(R.string.termux_crash_report_notifications_enabled_on) else stringResource(R.string.termux_crash_report_notifications_enabled_off),
                    icon = Icons.Default.BugReport,
                    checked = state.crashReportNotifications,
                    onCheckedChange = viewModel::setCrashReportNotifications
                )
            }

            if (state.apiInstalled || state.floatInstalled ||
                state.taskerInstalled || state.widgetInstalled
            ) {
                SettingsSectionTitle(title = stringResource(R.string.settings_plugins))
                SettingsCardGroup {
                    if (state.apiInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Extension,
                            title = stringResource(R.string.settings_plugin_api_log_level),
                            options = logLevelLabels(),
                            selectedIndex = state.apiLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("api", it) }
                        )
                    }
                    if (state.floatInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Widgets,
                            title = stringResource(R.string.settings_plugin_float_log_level),
                            options = logLevelLabels(),
                            selectedIndex = state.floatLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("float", it) }
                        )
                    }
                    if (state.taskerInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Tune,
                            title = stringResource(R.string.settings_plugin_tasker_log_level),
                            options = logLevelLabels(),
                            selectedIndex = state.taskerLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("tasker", it) }
                        )
                    }
                    if (state.widgetInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Extension,
                            title = stringResource(R.string.settings_plugin_widget_log_level),
                            options = logLevelLabels(),
                            selectedIndex = state.widgetLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("widget", it) }
                        )
                    }
                }
            }

            SettingsSectionTitle(title = stringResource(R.string.about_preference_title))
            SettingsCardGroup {
                SettingsListTile(
                    leadingIcon = Icons.Default.Info,
                    title = stringResource(R.string.about_preference_title),
                    subtitle = stringResource(R.string.about_preference_desc),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = {
                        try {
                            onAboutClick()
                        } catch (e: Exception) {
                            Logger.logStackTraceWithMessage(
                                "SettingsScreen",
                                "About click failed", e
                            )
                        }
                    }
                )
            }
        }
    }
}
