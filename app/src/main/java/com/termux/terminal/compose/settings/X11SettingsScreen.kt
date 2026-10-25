package com.termux.terminal.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.terminal.compose.SettingsCardGroup
import com.termux.terminal.compose.SettingsDialogTile
import com.termux.terminal.compose.SettingsListTile
import com.termux.terminal.compose.SettingsSectionTitle
import com.termux.terminal.compose.SettingsSliderTile
import com.termux.terminal.compose.SettingsSwitchTile
import kotlin.math.roundToInt

/**
 * X11 settings screen (spec 017). Reads and dispatches through
 * [X11SettingsViewModel]; every value shown is the sanitized result of the
 * lorie preference store, and every change persists through it, keeping the
 * live-refresh wiring of the X11 tabs intact (RF-8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun X11SettingsScreen(
    viewModel: X11SettingsViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.x11_settings_screen_title)) },
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
            SettingsSectionTitle(title = stringResource(R.string.x11_settings_section_output))
            SettingsCardGroup {
                val modeLabels = listOf(
                    stringResource(R.string.x11_settings_resolution_mode_native),
                    stringResource(R.string.x11_settings_resolution_mode_scaled),
                    stringResource(R.string.x11_settings_resolution_mode_exact),
                    stringResource(R.string.x11_settings_resolution_mode_custom)
                )
                SettingsDialogTile(
                    leadingIcon = Icons.Default.Monitor,
                    title = stringResource(R.string.x11_settings_resolution_mode_title),
                    options = modeLabels,
                    selectedIndex = X11SettingsPolicy.selectedIndexFor(
                        X11SettingsPolicy.OUTPUT_MODES, state.resolutionMode
                    ),
                    onSelected = { viewModel.setResolutionMode(X11SettingsPolicy.OUTPUT_MODES[it]) }
                )
                if (X11SettingsPolicy.isExactResolutionVisible(state.resolutionMode)) {
                    val exactOptions = state.exactResolutionOptions.ifEmpty {
                        listOf(state.exactResolution)
                    }
                    SettingsDialogTile(
                        leadingIcon = Icons.Default.ZoomIn,
                        title = stringResource(R.string.x11_settings_resolution_exact_title),
                        options = exactOptions,
                        selectedIndex = X11SettingsPolicy.selectedIndexFor(
                            exactOptions, state.exactResolution
                        ),
                        onSelected = {
                            X11SettingsPolicy.sanitizeListValue(
                                exactOptions.getOrNull(it), exactOptions, state.exactResolution
                            ).let(viewModel::setExactResolution)
                        }
                    )
                }
                if (X11SettingsPolicy.isCustomResolutionVisible(state.resolutionMode)) {
                    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
                    SettingsListTile(
                        leadingIcon = Icons.Default.Edit,
                        title = stringResource(R.string.x11_settings_resolution_custom_title),
                        subtitle = state.customResolution,
                        onClick = { showCustomDialog = true }
                    )
                    if (showCustomDialog) {
                        X11CustomResolutionDialog(
                            state = state,
                            viewModel = viewModel,
                            onDismiss = {
                                showCustomDialog = false
                                // Restore the draft to the last valid value.
                                viewModel.setCustomResolutionDraft(state.customResolution)
                            },
                            onAccept = {
                                viewModel.applyCustomResolution()
                                if (!viewModel.uiState.value.customResolutionError)
                                    showCustomDialog = false
                            }
                        )
                    }
                }
                SettingsSliderTile(
                    title = stringResource(R.string.x11_settings_display_scale_title),
                    subtitle = stringResource(R.string.x11_settings_display_scale_subtitle),
                    icon = Icons.Default.ZoomIn,
                    value = state.scale.toFloat(),
                    valueRange = X11SettingsPolicy.SCALE_MIN.toFloat()
                        ..X11SettingsPolicy.SCALE_MAX.toFloat(),
                    steps = (X11SettingsPolicy.SCALE_MAX - X11SettingsPolicy.SCALE_MIN)
                        / X11SettingsPolicy.SCALE_STEP - 1,
                    valueLabel = state.scale.toString(),
                    onValueChangeFinished = { viewModel.setScale(it.roundToInt()) }
                )
                val filteringLabels = listOf(
                    stringResource(R.string.x11_settings_filtering_bilinear),
                    stringResource(R.string.x11_settings_filtering_nearest)
                )
                SettingsDialogTile(
                    leadingIcon = Icons.Default.BlurOn,
                    title = stringResource(R.string.x11_settings_filtering_title),
                    options = filteringLabels,
                    selectedIndex = X11SettingsPolicy.selectedIndexFor(
                        X11SettingsPolicy.FILTERING_MODES, state.filteringMode
                    ),
                    onSelected = { viewModel.setFilteringMode(X11SettingsPolicy.FILTERING_MODES[it]) }
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_fullscreen_title),
                    subtitle = stringResource(R.string.x11_settings_fullscreen_desc),
                    icon = Icons.Default.Fullscreen,
                    checked = state.fullscreen,
                    onCheckedChange = viewModel::setFullscreen
                )
                val orientationLabels = listOf(
                    stringResource(R.string.x11_settings_orientation_auto),
                    stringResource(R.string.x11_settings_orientation_portrait),
                    stringResource(R.string.x11_settings_orientation_landscape),
                    stringResource(R.string.x11_settings_orientation_reverse_portrait),
                    stringResource(R.string.x11_settings_orientation_reverse_landscape)
                )
                SettingsDialogTile(
                    leadingIcon = Icons.Default.ScreenRotation,
                    title = stringResource(R.string.x11_settings_orientation_title),
                    options = orientationLabels,
                    selectedIndex = X11SettingsPolicy.selectedIndexFor(
                        X11SettingsPolicy.ORIENTATION_MODES, state.orientation
                    ),
                    onSelected = { viewModel.setOrientation(X11SettingsPolicy.ORIENTATION_MODES[it]) }
                )
            }

            SettingsSectionTitle(title = stringResource(R.string.x11_settings_section_pointer))
            SettingsCardGroup {
                val touchModeLabels = listOf(
                    stringResource(R.string.x11_settings_touch_mode_trackpad),
                    stringResource(R.string.x11_settings_touch_mode_simulated),
                    stringResource(R.string.x11_settings_touch_mode_direct)
                )
                SettingsDialogTile(
                    leadingIcon = Icons.Default.TouchApp,
                    title = stringResource(R.string.x11_settings_touch_mode_title),
                    options = touchModeLabels,
                    selectedIndex = X11SettingsPolicy.selectedIndexFor(
                        X11SettingsPolicy.TOUCH_MODES, state.touchMode
                    ),
                    onSelected = { viewModel.setTouchMode(X11SettingsPolicy.TOUCH_MODES[it]) }
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_scale_touchpad_title),
                    subtitle = stringResource(R.string.x11_settings_scale_touchpad_desc),
                    icon = Icons.Default.Mouse,
                    checked = state.scaleTouchpad,
                    onCheckedChange = viewModel::setScaleTouchpad
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_tap_to_move_title),
                    subtitle = stringResource(R.string.x11_settings_tap_to_move_desc),
                    icon = Icons.Default.Mouse,
                    checked = state.tapToMove,
                    onCheckedChange = viewModel::setTapToMove
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_zoom_follows_cursor_title),
                    subtitle = stringResource(R.string.x11_settings_zoom_follows_cursor_desc),
                    icon = Icons.Default.ZoomIn,
                    checked = state.zoomFollowsCursor,
                    onCheckedChange = viewModel::setZoomFollowsCursor
                )
            }

            SettingsSectionTitle(title = stringResource(R.string.x11_settings_section_keyboard))
            SettingsCardGroup {
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_show_additional_kbd_title),
                    subtitle = stringResource(R.string.x11_settings_show_additional_kbd_desc),
                    icon = Icons.Default.Keyboard,
                    checked = state.showAdditionalKbd,
                    onCheckedChange = viewModel::setShowAdditionalKbd
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_show_ime_while_external_title),
                    subtitle = stringResource(R.string.x11_settings_show_ime_while_external_desc),
                    icon = Icons.Default.Keyboard,
                    checked = state.showImeWhileExternalConnected,
                    onCheckedChange = viewModel::setShowImeWhileExternalConnected
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_prefer_scancodes_title),
                    subtitle = stringResource(R.string.x11_settings_prefer_scancodes_desc),
                    icon = Icons.Default.Keyboard,
                    checked = state.preferScancodes,
                    onCheckedChange = viewModel::setPreferScancodes
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_scancodes_workaround_title),
                    subtitle = stringResource(R.string.x11_settings_scancodes_workaround_desc),
                    icon = Icons.Default.Keyboard,
                    checked = state.hardwareKbdScancodesWorkaround,
                    onCheckedChange = viewModel::setHardwareKbdScancodesWorkaround
                )
                SettingsSwitchTile(
                    title = stringResource(R.string.x11_settings_filter_winkey_title),
                    subtitle = stringResource(R.string.x11_settings_filter_winkey_desc),
                    icon = Icons.Default.Keyboard,
                    checked = state.filterOutWinkey,
                    onCheckedChange = viewModel::setFilterOutWinkey
                )
            }
        }
    }
}

/**
 * Text dialog for the custom resolution (RF-7): it only persists on accept,
 * and the error text comes from the resources while the ViewModel keeps the
 * draft through configuration changes.
 */
@Composable
private fun X11CustomResolutionDialog(
    state: X11SettingsUiState,
    viewModel: X11SettingsViewModel,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.x11_settings_resolution_custom_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.customResolutionDraft,
                    onValueChange = viewModel::setCustomResolutionDraft,
                    label = { Text(stringResource(R.string.x11_settings_resolution_custom_hint)) },
                    singleLine = true,
                    isError = state.customResolutionError,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAccept() }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.customResolutionError) {
                    Text(
                        text = stringResource(R.string.x11_settings_resolution_custom_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.action_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
