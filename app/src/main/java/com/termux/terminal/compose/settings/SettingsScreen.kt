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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Smartphone
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
import com.termux.shared.logger.Logger
import com.termux.terminal.compose.SettingsCardGroup
import com.termux.terminal.compose.SettingsDialogTile
import com.termux.terminal.compose.SettingsListTile
import com.termux.terminal.compose.SettingsSectionTitle
import com.termux.terminal.compose.SettingsSliderTile
import com.termux.terminal.compose.SettingsSwitchTile

private val LOG_LEVEL_LABELS = listOf("Off", "Normal", "Debug", "Verbose")

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
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back"
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
            SettingsSectionTitle(title = "Terminal")
            SettingsCardGroup {
                SettingsSwitchTile(
                    title = "Show extra keys",
                    subtitle = "Toolbar with extra keys above the keyboard",
                    icon = Icons.Default.Keyboard,
                    checked = state.showTerminalToolbar,
                    onCheckedChange = viewModel::setShowTerminalToolbar
                )
                SettingsSwitchTile(
                    title = "Terminal margin adjustment",
                    subtitle = "Prevent keyboard from covering the terminal",
                    icon = Icons.Default.Monitor,
                    checked = state.terminalMarginAdjustment,
                    onCheckedChange = viewModel::setTerminalMarginAdjustment
                )
                SettingsSwitchTile(
                    title = "Keep screen on",
                    subtitle = "Never turn the screen off while in a session",
                    icon = Icons.Default.Visibility,
                    checked = state.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn
                )
                SettingsSliderTile(
                    title = "Font size",
                    subtitle = "Terminal text size in pixels",
                    icon = Icons.Default.TextFields,
                    value = state.fontSize.toFloat(),
                    valueRange = state.minFontSize.toFloat()..state.maxFontSize.toFloat(),
                    valueLabel = state.fontSize.toString(),
                    onValueChangeFinished = { viewModel.setFontSize(it.toInt()) }
                )
            }

            SettingsSectionTitle(title = "Keyboard")
            SettingsCardGroup {
                SettingsSwitchTile(
                    title = "Soft keyboard enabled",
                    subtitle = "Show the on-screen keyboard",
                    icon = Icons.Default.Keyboard,
                    checked = state.softKeyboardEnabled,
                    onCheckedChange = viewModel::setSoftKeyboardEnabled
                )
                SettingsSwitchTile(
                    title = "Only if no hardware keyboard",
                    subtitle = "Skip soft keyboard when hardware is attached",
                    icon = Icons.Default.Smartphone,
                    checked = state.softKeyboardOnlyIfNoHardware,
                    onCheckedChange = viewModel::setSoftKeyboardOnlyIfNoHardware
                )
            }

            SettingsSectionTitle(title = "Debugging")
            SettingsCardGroup {
                SettingsDialogTile(
                    leadingIcon = Icons.Default.BugReport,
                    title = "Log level",
                    options = LOG_LEVEL_LABELS,
                    selectedIndex = state.logLevel.coerceIn(0, 3),
                    onSelected = viewModel::setLogLevel
                )
                SettingsSwitchTile(
                    title = "Terminal view key logging",
                    subtitle = "Log hardware key events for debugging",
                    icon = Icons.Default.Tune,
                    checked = state.terminalViewKeyLogging,
                    onCheckedChange = viewModel::setTerminalViewKeyLogging
                )
                SettingsSwitchTile(
                    title = "Plugin error notifications",
                    subtitle = "Notify when a plugin command fails",
                    icon = Icons.Default.ErrorOutline,
                    checked = state.pluginErrorNotifications,
                    onCheckedChange = viewModel::setPluginErrorNotifications
                )
                SettingsSwitchTile(
                    title = "Crash report notifications",
                    subtitle = "Notify when the app crashes",
                    icon = Icons.Default.BugReport,
                    checked = state.crashReportNotifications,
                    onCheckedChange = viewModel::setCrashReportNotifications
                )
            }

            if (state.apiInstalled || state.floatInstalled ||
                state.taskerInstalled || state.widgetInstalled
            ) {
                SettingsSectionTitle(title = "Plugins")
                SettingsCardGroup {
                    if (state.apiInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Extension,
                            title = "Terminal:API log level",
                            options = LOG_LEVEL_LABELS,
                            selectedIndex = state.apiLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("api", it) }
                        )
                    }
                    if (state.floatInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Widgets,
                            title = "Terminal:Float log level",
                            options = LOG_LEVEL_LABELS,
                            selectedIndex = state.floatLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("float", it) }
                        )
                    }
                    if (state.taskerInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Tune,
                            title = "Terminal:Tasker log level",
                            options = LOG_LEVEL_LABELS,
                            selectedIndex = state.taskerLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("tasker", it) }
                        )
                    }
                    if (state.widgetInstalled) {
                        SettingsDialogTile(
                            leadingIcon = Icons.Default.Extension,
                            title = "Terminal:Widget log level",
                            options = LOG_LEVEL_LABELS,
                            selectedIndex = state.widgetLogLevel.coerceIn(0, 3),
                            onSelected = { viewModel.setPluginLogLevel("widget", it) }
                        )
                    }
                }
            }

            SettingsSectionTitle(title = "About")
            SettingsCardGroup {
                SettingsListTile(
                    leadingIcon = Icons.Default.Info,
                    title = "About",
                    subtitle = "App, device and important links",
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
