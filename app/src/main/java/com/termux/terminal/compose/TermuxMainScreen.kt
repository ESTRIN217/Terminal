package com.termux.terminal.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.termux.app.TermuxComposeActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.bridge.TerminalKeyHandler
import com.termux.view.TerminalViewClient
import com.termux.R

/**
 * Main screen composable for the Termux app.
 *
 * @param viewModel The TermuxViewModel instance
 * @param viewClient The [TerminalViewClient] implementation to attach to the terminal view
 * @param palette Colors applied to the terminal emulator and view background
 * @param isKeepScreenOnEnabled Whether the keep-screen-on preference is currently enabled
 * @param onSetKeepScreenOn Callback to persist and apply a new keep-screen-on state
 * @param onOpenHelp Callback to open the help activity
 * @param onCreateSession Callback to create a new terminal session
 * @param onRemoveSession Callback to remove a terminal session
 * @param onToggleKeyboard Callback to toggle the soft keyboard
 * @param onOpenFileManager Callback to open the file manager
 * @param onOpenSettings Callback to open settings
 * @param modifier Modifier to apply
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermuxMainScreen(
    viewModel: TermuxViewModel,
    viewClient: TerminalViewClient,
    palette: TerminalPalette,
    isKeepScreenOnEnabled: Boolean,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
    onCreateSession: () -> Unit,
    onRemoveSession: (TerminalSession) -> Unit,
    onToggleKeyboard: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Track soft keyboard visibility
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        viewModel.setSoftKeyboardVisible(isImeVisible)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        TermuxNavigationDrawer(
            isOpen = uiState.isDrawerOpen,
            onOpenChange = { viewModel.setDrawerOpen(it) },
            onFileManagerClick = {
                viewModel.setDrawerOpen(false)
                onOpenFileManager()
            },
            onSettingsClick = {
                viewModel.setDrawerOpen(false)
                onOpenSettings()
            },
            onToggleKeyboardClick = {
                viewModel.setDrawerOpen(false)
                onToggleKeyboard()
            },
            modifier = Modifier.weight(1f)
        ) {
            Scaffold(
                topBar = {
                    if (uiState.sessions.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Hamburger to open the navigation drawer, which is otherwise only
                            // reachable through an edge swipe or keyboard shortcuts.
                            IconButton(onClick = { viewModel.setDrawerOpen(true) }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.open_navigation_drawer),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            SessionTabs(
                                sessions = uiState.sessions,
                                activeSessionIndex = uiState.activeSessionIndex,
                                onSessionSelected = { viewModel.switchSession(it) },
                                onCloseSessionClick = { session ->
                                    onRemoveSession(session)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            // New session Button
                            IconButton(
                               onClick = onCreateSession
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.action_new_session),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            ) { paddingValues ->
                // Terminal content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (uiState.hasSessions) {
                        uiState.sessions.forEachIndexed { index, sessionModel ->
                            if (index == uiState.activeSessionIndex) {
                                TerminalViewHost(
                                    session = sessionModel.session,
                                    fontSize = uiState.fontSize,
                                    viewClient = viewClient,
                                    palette = palette,
                                    modifier = Modifier
                                        .fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
        // Extra keys bar: outside the drawer so it is never dimmed by the drawer
        // scrim, and inside the imePadding column so it rides above the keyboard.
        if (uiState.isExtraKeysVisible) {
            ExtraKeysBar(
                config = uiState.extraKeysConfig,
                activeModifiers = uiState.extraKeysModifiers,
                onToggleModifier = viewModel::toggleExtraKeysModifier,
                callback = object : ExtraKeysCallback {
                    override fun onKeyClick(key: String, isMacro: Boolean) {
                        val session = uiState.activeSession ?: return@onKeyClick
                        if (isMacro) {
                            val keys = key.split(" ")
                            var ctrlActive = false
                            var altActive = false
                            var shiftActive = false
                            for (k in keys) {
                                when (k) {
                                    "CTRL" -> ctrlActive = true
                                    "ALT" -> altActive = true
                                    "SHIFT" -> shiftActive = true
                                    // Consumed: FN only modifies hardware key events
                                    // (legacy parity), never bar-to-bar combos.
                                    "FN" -> Unit
                                    else -> {
                                        sendKeyToSession(session, k, ctrlActive, altActive, shiftActive)
                                        ctrlActive = false
                                        altActive = false
                                        shiftActive = false
                                    }
                                }
                            }
                        } else {
                            sendKeyToSession(session, key)
                        }
                    }
                },
                modifier = Modifier
            )
        }
    }
}

/**
 * Send a key to a terminal session using proper escape sequences.
 *
 * @param session The terminal session
 * @param key The key identifier (e.g., "UP", "ESC", "TAB")
 * @param ctrlActive Whether Ctrl modifier is active
 * @param altActive Whether Alt modifier is active
 * @param shiftActive Whether Shift modifier is active
 */
private fun sendKeyToSession(
    session: TerminalSession,
    key: String,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    shiftActive: Boolean = false
) {
    val sequence = TerminalKeyHandler.getKeySequence(key, ctrlActive, altActive, shiftActive)
    session.write(sequence)
}
