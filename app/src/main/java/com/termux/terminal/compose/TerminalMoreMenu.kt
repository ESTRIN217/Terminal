package com.termux.terminal.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Title
import com.termux.R

/**
 * Actions of the terminal "More" menu (MD3 Expressive replacement of the legacy Android
 * `ContextMenu` built by `TermuxComposeActivity.onCreateContextMenu`).
 *
 * Every value maps 1:1 to a former context-menu item id so the handler dispatch keeps
 * parity with `onContextItemSelected`.
 */
enum class TerminalMoreAction {
    /** Extract URLs from the transcript and offer copy/open. */
    SELECT_URL,
    /** Share the full session transcript. */
    SHARE_TRANSCRIPT,
    /** Share the text stored by the selection toolbar "More…" button. */
    SHARE_SELECTED_TEXT,
    /** Request autofill of the username into the focused view. */
    AUTOFILL_USERNAME,
    /** Request autofill of the password into the focused view. */
    AUTOFILL_PASSWORD,
    /** Reset (clear) the active terminal session. */
    RESET_TERMINAL,
    /** Confirm and kill the process running in the active session. */
    KILL_PROCESS,
    /** Open the terminal font ("Style") dialog. */
    STYLE,
    /** Toggle the keep-screen-on preference. */
    TOGGLE_KEEP_SCREEN_ON,
    /** Open the help screen. */
    HELP,
    /** Open the settings screen. */
    SETTINGS,
    /** Start a "report issue" flow from the transcript. */
    REPORT
}

/**
 * Visibility/label flags of the "More" menu, computed once when the sheet opens
 * (parity with the menu-item conditions of `onCreateContextMenu`).
 *
 * @property showShareSelectedText Whether "Share selected text" is listed (stored selection non-empty)
 * @property showAutofill Whether the autofill username/password items are listed
 * @property killProcessLabel Preformatted "Kill process (pid)" label for the active session
 * @property killProcessEnabled Whether the kill item is enabled (session still running)
 */
data class TerminalMoreMenuUiState(
    val showShareSelectedText: Boolean,
    val showAutofill: Boolean,
    val killProcessLabel: String,
    val killProcessEnabled: Boolean
)

/**
 * Material 3 Expressive bottom sheet with the terminal "More" actions.
 *
 * Replaces the legacy Android `ContextMenu` shared by the selection toolbar "More…" button,
 * the mouse right-click path and the top-bar overflow entry.
 *
 * @param state Item visibility/labels computed when the sheet opened
 * @param keepScreenOnChecked Whether the keep-screen-on item shows a checkmark
 * @param onAction Callback with the selected [TerminalMoreAction]
 * @param onDismiss Callback when the sheet is dismissed (also clears stored selection text)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalMoreMenu(
    state: TerminalMoreMenuUiState,
    keepScreenOnChecked: Boolean,
    onAction: (TerminalMoreAction) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        TerminalMoreMenuItem(
            icon = Icons.Default.Link,
            label = stringResource(R.string.action_select_url),
            onClick = { onAction(TerminalMoreAction.SELECT_URL) }
        )
        TerminalMoreMenuItem(
            icon = Icons.Default.Share,
            label = stringResource(R.string.action_share_transcript),
            onClick = { onAction(TerminalMoreAction.SHARE_TRANSCRIPT) }
        )
        if (state.showShareSelectedText) {
            TerminalMoreMenuItem(
                icon = Icons.Default.Title,
                label = stringResource(R.string.action_share_selected_text),
                onClick = { onAction(TerminalMoreAction.SHARE_SELECTED_TEXT) }
            )
        }
        if (state.showAutofill) {
            TerminalMoreMenuItem(
                icon = Icons.Default.Person,
                label = stringResource(R.string.action_autofill_username),
                onClick = { onAction(TerminalMoreAction.AUTOFILL_USERNAME) }
            )
            TerminalMoreMenuItem(
                icon = Icons.Default.Person,
                label = stringResource(R.string.action_autofill_password),
                onClick = { onAction(TerminalMoreAction.AUTOFILL_PASSWORD) }
            )
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        TerminalMoreMenuItem(
            icon = Icons.Default.Refresh,
            label = stringResource(R.string.action_reset_terminal),
            onClick = { onAction(TerminalMoreAction.RESET_TERMINAL) }
        )
        TerminalMoreMenuItem(
            icon = Icons.Default.StopCircle,
            label = state.killProcessLabel,
            enabled = state.killProcessEnabled,
            onClick = { onAction(TerminalMoreAction.KILL_PROCESS) }
        )
        TerminalMoreMenuItem(
            icon = Icons.Default.Style,
            label = stringResource(R.string.action_style_terminal),
            onClick = { onAction(TerminalMoreAction.STYLE) }
        )
        TerminalMoreMenuItem(
            icon = Icons.Default.LightMode,
            label = stringResource(R.string.action_toggle_keep_screen_on),
            checked = keepScreenOnChecked,
            onClick = { onAction(TerminalMoreAction.TOGGLE_KEEP_SCREEN_ON) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        TerminalMoreMenuItem(
            icon = Icons.Default.HelpOutline,
            label = stringResource(R.string.action_open_help),
            onClick = { onAction(TerminalMoreAction.HELP) }
        )
        TerminalMoreMenuItem(
            icon = Icons.Default.Settings,
            label = stringResource(R.string.action_open_settings),
            onClick = { onAction(TerminalMoreAction.SETTINGS) }
        )
        TerminalMoreMenuItem(
            icon = Icons.Default.BugReport,
            label = stringResource(R.string.action_report_issue),
            onClick = { onAction(TerminalMoreAction.REPORT) }
        )
        // Keep the sheet content clear of the navigation-bar gesture area.
        Spacer(modifier = Modifier.padding(bottom = 16.dp))
    }
}

/**
 * Single row of the terminal "More" sheet.
 *
 * @param icon Leading icon
 * @param label Visible item label
 * @param onClick Callback when the row is tapped
 * @param enabled Whether the row is interactive (parity with `MenuItem.setEnabled`)
 * @param checked Whether a trailing checkmark is shown (keep-screen-on parity)
 */
@Composable
private fun TerminalMoreMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checked: Boolean? = null
) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = if (checked == true) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            null
        },
        enabled = enabled,
        onClick = onClick
    )
}
