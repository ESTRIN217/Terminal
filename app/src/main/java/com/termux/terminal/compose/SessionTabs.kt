package com.termux.terminal.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalSession

/**
 * A scrollable tab row for terminal sessions.
 *
 * @param sessions List of session UI models
 * @param activeSessionIndex Index of the currently active session
 * @param onSessionSelected Callback when a session tab is selected
 * @param onNewSessionClick Callback when the new session button is clicked
 * @param onCloseSessionClick Callback when a session close button is clicked
 * @param modifier Modifier to apply
 */
@Composable
fun SessionTabs(
    sessions: List<TerminalSessionUiModel>,
    activeSessionIndex: Int,
    onSessionSelected: (Int) -> Unit,
    onCloseSessionClick: (TerminalSession) -> Unit,
    modifier: Modifier = Modifier
) {
    ScrollableTabRow(
        selectedTabIndex = activeSessionIndex.coerceIn(0, (sessions.size + 1).coerceAtLeast(0)),
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        edgePadding = 0.dp,
        indicator = { tabPositions ->
            if (activeSessionIndex in tabPositions.indices) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopStart // 3. Forzamos la alineación ARRIBA
                ) {
                SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[activeSessionIndex]),
                    color = MaterialTheme.colorScheme.primary
                )
                }
            }
        },
        divider = {}
    ) {
        sessions.forEachIndexed { index, session ->
            SessionTab(
                session = session,
                isSelected = index == activeSessionIndex,
                onSelect = { onSessionSelected(index) },
                onClose = { onCloseSessionClick(session.session) }
            )
        }
    }
}

/**
 * A single session tab with a close button.
 *
 * @param session The session UI model
 * @param isSelected Whether this tab is currently selected
 * @param onSelect Callback when the tab is selected
 * @param onClose Callback when the close button is clicked
 * @param modifier Modifier to apply
 */
@Composable
private fun SessionTab(
    session: TerminalSessionUiModel,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Tab(
        selected = isSelected,
        onClick = onSelect,
        modifier = modifier,
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Session",
                    modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = session.name.ifEmpty { session.title.ifEmpty { "Terminal" } },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Session",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    )
}
