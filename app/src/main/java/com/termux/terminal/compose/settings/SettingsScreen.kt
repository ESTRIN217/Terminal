package com.termux.terminal.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.proot.GpuDetectionLibrary
import com.termux.shared.termux.proot.LlvmpipeThreadsLibrary
import com.termux.shared.termux.proot.VirglLibrary
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
                SettingsSwitchTile(
                    title = stringResource(R.string.terminal_hyperlinks),
                    subtitle = stringResource(R.string.terminal_hyperlinks_desc),
                    icon = Icons.Default.Link,
                    checked = state.terminalHyperlinks,
                    onCheckedChange = viewModel::setTerminalHyperlinks
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.terminal_images),
                    subtitle = stringResource(R.string.terminal_images_desc),
                    icon = Icons.Default.Image,
                    checked = state.terminalImages,
                    onCheckedChange = viewModel::setTerminalImages
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.force_60hz),
                    subtitle = stringResource(R.string.force_60hz_desc),
                    icon = Icons.Default.Refresh,
                    checked = state.force60Hz,
                    onCheckedChange = viewModel::setForce60Hz
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

            // GPU status panel (spec 011): always visible, honest per family.
            SettingsSectionTitle(title = stringResource(R.string.gpu_section_title))
            SettingsCardGroup {
                GpuStatusPanel(state = state, viewModel = viewModel)
            }
            SettingsSectionTitle(title = stringResource(R.string.llvmpipe_threads_title))
            SettingsCardGroup {
                SettingsSwitchTile(
                    title = stringResource(R.string.llvmpipe_threads_enabled_title),
                    subtitle = stringResource(R.string.llvmpipe_threads_subtitle),
                    icon = Icons.Default.Tune,
                    checked = state.llvmpipeThreadsEnabled,
                    onCheckedChange = viewModel::setLlvmpipeThreadsEnabled
                )
                val configuredThreads = state.llvmpipeThreadsCount
                    .coerceIn(1, LlvmpipeThreadsLibrary.MAX_THREADS)
                val hostCores = state.hostCores.coerceIn(1, LlvmpipeThreadsLibrary.MAX_CORES)
                val effectiveThreads = LlvmpipeSettingsPolicy.computeEffectiveThreads(
                    hostCores,
                    configuredThreads
                )
                SettingsSliderTile(
                    title = stringResource(R.string.llvmpipe_threads_count_title),
                    subtitle = stringResource(
                        R.string.llvmpipe_threads_count_summary,
                        configuredThreads,
                        effectiveThreads,
                        hostCores
                    ),
                    icon = Icons.Default.Tune,
                    value = configuredThreads.toFloat(),
                    valueRange = 1f..LlvmpipeThreadsLibrary.MAX_THREADS.toFloat(),
                    steps = LlvmpipeThreadsLibrary.MAX_THREADS - 2,
                    valueLabel = configuredThreads.toString(),
                    onValueChangeFinished = {
                        viewModel.setLlvmpipeThreadsCount(it.toInt())
                    }
                )
                if (state.isGpuSolutionActive) {
                    Text(
                        text = stringResource(R.string.llvmpipe_threads_adreno_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * GPU status panel (spec 011, RF-2, RF-6): the honest state line of the
 * detected family, the only-info motive when the panel is not actionable, the
 * «currently active» marker when a revert (uninstall) is offered, the progress
 * bar while the install/uninstall runs, and the Install/Uninstall buttons ONLY
 * when the action gate offers exactly that action (the buttons are absent, not
 * disabled, in only-information mode).
 */
@Composable
private fun GpuStatusPanel(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(
            text = gpuStateText(stateKey = state.gpuStatusLine, model = state.gpuModel),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (state.gpuReasonKey != null) {
            Text(
                text = gpuReasonText(state.gpuReasonKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (state.gpuActionType == GpuPanelAction.UNINSTALL) {
            Text(
                text = stringResource(R.string.gpu_solution_active),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (state.gpuBusy) {
            Text(
                text = stringResource(R.string.gpu_action_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
        val buttonLabel = when (state.gpuActionType) {
            GpuPanelAction.INSTALL -> stringResource(R.string.gpu_install_button)
            GpuPanelAction.UNINSTALL -> stringResource(R.string.gpu_uninstall_button)
            else -> null
        }
        if (buttonLabel != null) {
            Button(
                enabled = !state.gpuBusy,
                onClick = {
                    if (state.gpuActionType == GpuPanelAction.INSTALL)
                        viewModel.onGpuInstallRequested()
                    else
                        viewModel.onGpuUninstallRequested()
                },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(text = buttonLabel)
            }
        }

        VirglSwitchRow(state = state, viewModel = viewModel)
    }
}

/**
 * VirGL lever (spec 013, RF-2/RF-6/RF-7): the switch plus the honest liveness
 * line (alive / not running / failed) and the exclusivity gate message when
 * the last toggle was refused. Available on every family — the KGSL route
 * stays marked as preferred by its own state line and install button.
 */
@Composable
private fun VirglSwitchRow(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.gpu_virgl_switch_title),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.gpu_virgl_switch_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(
                checked = state.virglSolutionActive,
                enabled = !state.virglBusy && !state.gpuBusy,
                onCheckedChange = { viewModel.onVirglSwitchRequested(it) }
            )
        }
        Text(
            text = virglLivenessText(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = stringResource(R.string.gpu_virgl_switch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (state.virglReasonKey != null) {
            Text(
                text = virglGateReasonText(state.virglReasonKey),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (state.virglBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

/** Honest liveness line of the VirGL server (RF-2(c)/RF-7, no guessing). */
@Composable
private fun virglLivenessText(state: SettingsUiState): String {
    if (!state.virglSolutionActive)
        return stringResource(R.string.gpu_virgl_switch_subtitle_inactive)
    return when {
        state.virglServerAlive -> stringResource(R.string.gpu_virgl_server_alive)
        state.virglReasonKey != null -> stringResource(R.string.gpu_virgl_server_failed)
        else -> stringResource(R.string.gpu_virgl_server_down)
    }
}

/** Map a [VirglLibrary.REASON_*] onto its gate message (RF-6). */
@Composable
private fun virglGateReasonText(reasonKey: String): String {
    return when (reasonKey) {
        VirglLibrary.REASON_VIRGL_ACTIVE ->
            stringResource(R.string.gpu_virgl_gate_virgl_active)
        else -> stringResource(R.string.gpu_virgl_gate_kgsl_active)
    }
}

/** Map a [GpuDetectionLibrary.STATE_KEY_*] onto the honest panel line (RF-2). */
@Composable
private fun gpuStateText(stateKey: String, model: String): String {
    if (stateKey.isEmpty())
        return stringResource(R.string.gpu_state_evaluating)
    return when (stateKey) {
        GpuDetectionLibrary.STATE_KEY_ADRENO_KGSL ->
            if (model.isEmpty()) stringResource(R.string.gpu_state_adreno_kgsl)
            else stringResource(R.string.gpu_state_adreno_kgsl_model, model)
        GpuDetectionLibrary.STATE_KEY_ADRENO_OTHER ->
            stringResource(R.string.gpu_state_adreno_other)
        GpuDetectionLibrary.STATE_KEY_MALI -> stringResource(R.string.gpu_state_mali)
        GpuDetectionLibrary.STATE_KEY_POWERVR -> stringResource(R.string.gpu_state_powervr)
        else -> stringResource(R.string.gpu_state_unknown)
    }
}

/** Map a [GpuDetectionLibrary.REASON_*] onto its motive line (RF-6). */
@Composable
private fun gpuReasonText(reasonKey: String?): String {
    if (reasonKey == null)
        return stringResource(R.string.gpu_reason_no_gpu_data)
    return when (reasonKey) {
        GpuDetectionLibrary.REASON_ROOTFS_NOT_INSTALLED ->
            stringResource(R.string.gpu_reason_rootfs_not_installed)
        GpuDetectionLibrary.REASON_MESA_VERSION_MISMATCH ->
            stringResource(R.string.gpu_reason_mesa_version_mismatch)
        GpuDetectionLibrary.REASON_FAMILY_UNSUPPORTED ->
            stringResource(R.string.gpu_reason_family_unsupported)
        GpuDetectionLibrary.REASON_UNRECOGNIZED_GPU ->
            stringResource(R.string.gpu_reason_unrecognized_gpu)
        else -> stringResource(R.string.gpu_reason_no_gpu_data)
    }
}
