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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import com.termux.app.TermuxComposeActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.bridge.TerminalKeyHandler
import com.termux.view.TerminalViewClient
import com.termux.R

/**
 * Minimum content width (in dp) for the two-pane split to be shown. Matches the Material 3
 * adaptive "medium" width class (600dp): below it (phones in portrait) the secondary pane is
 * not rendered and the app behaves as a single pane.
 */
private val MinSplitContentWidth = 600.dp

/** Font-size clamp for pinch-zoom, matching the legacy `mSizePx` range. */
private const val MinTerminalFontSizePx = 8f

/** Font-size clamp for pinch-zoom, matching the legacy `mSizePx` range. */
private const val MaxTerminalFontSizePx = 32f

/**
 * Main screen composable for the Termux app.
 *
 * @param viewModel The TermuxViewModel instance
 * @param viewClient The [TerminalViewClient] implementation to attach to the terminal view
 * @param palette Colors applied to the terminal emulator and view background
 * @param typeface The [Typeface] to use for the terminal text, or null for the default
 * @param enableLigatures Whether OpenType ligature shaping is enabled in the terminal renderer
 * @param isKeepScreenOnEnabled Whether the keep-screen-on preference is currently enabled
 * @param onSetKeepScreenOn Callback to persist and apply a new keep-screen-on state
 * @param onOpenHelp Callback to open the help activity
 * @param onCreateSession Callback to create a new terminal session
 * @param onRemoveSession Callback to remove a terminal session
 * @param onToggleKeyboard Callback to toggle the soft keyboard
 * @param onOpenFileManager Callback to open the file manager
 * @param onOpenInTerminal Callback to open a terminal in a directory (file manager panes)
 * @param onEditFile Callback to open a file in a rootfs editor (file manager panes)
 * @param onOpenSettings Callback to open settings
 * @param moreMenuState Non-null while the terminal "More" sheet is open (item flags/labels)
 * @param onShowMoreMenu Callback to open the terminal "More" sheet (top bar / selection toolbar)
 * @param onMoreMenuAction Callback with the [TerminalMoreAction] selected in the sheet
 * @param onDismissMoreMenu Callback to dismiss the "More" sheet and clear stored selection
 * @param modifier Modifier to apply
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermuxMainScreen(
    viewModel: TermuxViewModel,
    viewClient: TerminalViewClient,
    palette: TerminalPalette,
    typeface: Typeface?,
    enableLigatures: Boolean,
    isKeepScreenOnEnabled: Boolean,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
    onCreateSession: () -> Unit,
    onRemoveSession: (TermuxSessionUiModel) -> Unit,
    onToggleKeyboard: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenInTerminal: (String) -> Unit,
    onEditFile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    moreMenuState: TerminalMoreMenuUiState?,
    onShowMoreMenu: () -> Unit,
    onMoreMenuAction: (TerminalMoreAction) -> Unit,
    onDismissMoreMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Visibility of the two-pane split picker (selects the secondary session).
    var isSplitPickerVisible by remember { mutableStateOf(false) }

    // Track the available content width so the split button and the panes share the same
    // "is there room for two panes" gate (see MinSplitContentWidth). Updated by the content
    // Box's onSizeChanged below.
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val minSplitContentWidthPx = with(LocalDensity.current) {
        MinSplitContentWidth.roundToPx()
    }

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
                            // Split (two-pane) action: opens the picker for the other
                            // pane, or closes the split when one is active. Hidden when there
                            // is not enough width to actually show two panes, except while a
                            // split is active so it can always be closed.
                            val hasSplitSpace = contentWidthPx >= minSplitContentWidthPx
                            if (uiState.sessions.size > 1 && (uiState.isSplitActive || hasSplitSpace)) {
                                IconButton(
                                    onClick = {
                                        if (uiState.isSplitActive) {
                                            viewModel.clearSplit()
                                        } else {
                                            isSplitPickerVisible = true
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (uiState.isSplitActive) {
                                            Icons.Default.CloseFullscreen
                                        } else {
                                            Icons.AutoMirrored.Filled.CallSplit
                                        },
                                        contentDescription = stringResource(if (uiState.isSplitActive) {
                                            R.string.close_split
                                        } else {
                                            R.string.split_view
                                        }),
                                        modifier = Modifier.size(18.dp),
                                        tint = if (uiState.isSplitActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
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
                            // Terminal "More" menu (MD3 Expressive bottom sheet). Hidden
                            // while a non-terminal tab is focused (parity with the legacy
                            // context menu, which needed an active TerminalSession).
                            if (uiState.activeSessionModel is TermuxSessionUiModel.Terminal) {
                                IconButton(onClick = onShowMoreMenu) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.action_more_menu),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            ) { paddingValues ->
                // Terminal content: either a single pane (as before) or, on wide screens
                // when a split is active, the active (focused) session next to a secondary
                // session of any kind (terminal or file manager).
                // The width is tracked through onSizeChanged (post-layout) instead of a
                // BoxWithConstraints so that panes are only added/removed in a regular
                // recomposition. Removing a TerminalView inside BoxWithConstraints' measure
                // subcomposition triggered a focus re-parenting that forced a synchronous
                // remeasure during the measure pass ("performMeasureAndLayout called during
                // measure layout").
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .onSizeChanged { contentWidthPx = it.width }
                ) {
                    if (uiState.hasSessions) {
                        val activeModel = uiState.activeSessionModel
                        val paneOneModel = uiState.splitPaneOneModel
                        val paneTwoModel = uiState.splitPaneTwoModel
                        val canSplit = contentWidthPx >= minSplitContentWidthPx
                        if (canSplit && uiState.isSplitActive &&
                            paneOneModel != null && paneTwoModel != null
                        ) {
                            val focusedId = activeModel?.id
                            Row(modifier = Modifier.fillMaxSize()) {
                                // Panes keep their positions: focusing a pane never moves its
                                // session between halves (see TermuxViewModel.activateSession).
                                SessionPane(
                                    model = paneOneModel,
                                    isActivePane = paneOneModel.id == focusedId,
                                    fontSize = uiState.fontSize,
                                    typeface = typeface,
                                    enableLigatures = enableLigatures,
                                    viewClient = viewClient,
                                    palette = palette,
                                    useNativeRenderer = uiState.useNativeRenderer,
                                    hyperlinksEnabled = uiState.hyperlinksEnabled,
                                    onPaneFocused = { viewModel.focusSession(it) },
                                    onRemoveSession = onRemoveSession,
                                    onOpenInTerminal = onOpenInTerminal,
                                    onEditFile = onEditFile,
                                    onShowMoreMenu = onShowMoreMenu,
                                    onFontSizeStep = viewModel::setFontSize,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                                VerticalDivider(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight(),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                SessionPane(
                                    model = paneTwoModel,
                                    isActivePane = paneTwoModel.id == focusedId,
                                    fontSize = uiState.fontSize,
                                    typeface = typeface,
                                    enableLigatures = enableLigatures,
                                    viewClient = viewClient,
                                    palette = palette,
                                    useNativeRenderer = uiState.useNativeRenderer,
                                    hyperlinksEnabled = uiState.hyperlinksEnabled,
                                    onPaneFocused = { viewModel.focusSession(it) },
                                    onRemoveSession = onRemoveSession,
                                    onOpenInTerminal = onOpenInTerminal,
                                    onEditFile = onEditFile,
                                    onShowMoreMenu = onShowMoreMenu,
                                    onFontSizeStep = viewModel::setFontSize,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        } else {
                            activeModel?.let { model ->
                                SessionPane(
                                    model = model,
                                    isActivePane = true,
                                    fontSize = uiState.fontSize,
                                    typeface = typeface,
                                    enableLigatures = enableLigatures,
                                    viewClient = viewClient,
                                    palette = palette,
                                    useNativeRenderer = uiState.useNativeRenderer,
                                    hyperlinksEnabled = uiState.hyperlinksEnabled,
                                    onPaneFocused = { viewModel.focusSession(it) },
                                    onRemoveSession = onRemoveSession,
                                    onOpenInTerminal = onOpenInTerminal,
                                    onEditFile = onEditFile,
                                    onShowMoreMenu = onShowMoreMenu,
                                    onFontSizeStep = viewModel::setFontSize,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
        // Terminal "More" sheet (MD3 Expressive); open from the top-bar overflow, the
        // selection toolbar "More…", mouse right-click or the legacy ActionMode MORE.
        moreMenuState?.let { moreMenuStateValue ->
            TerminalMoreMenu(
                state = moreMenuStateValue,
                keepScreenOnChecked = isKeepScreenOnEnabled,
                onAction = onMoreMenuAction,
                onDismiss = onDismissMoreMenu
            )
        }
        // Extra keys bar: outside the drawer so it is never dimmed by the drawer
        // scrim, and inside the imePadding column so it rides above the keyboard.
        // Shown for terminal sessions and for the file manager tab, which uses it
        // for list navigation (see FileManagerKeyHandlerHolder).
        val activeModel = uiState.activeSessionModel
        if (uiState.isExtraKeysVisible && activeModel != null) {
            ExtraKeysBar(
                config = uiState.extraKeysConfig,
                activeModifiers = uiState.extraKeysModifiers,
                onToggleModifier = viewModel::toggleExtraKeysModifier,
                callback = object : ExtraKeysCallback {
                    override fun onKeyClick(key: String, isMacro: Boolean) {
                        when (activeModel) {
                            is TermuxSessionUiModel.Terminal -> {
                                val session = activeModel.session
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
                            is TermuxSessionUiModel.FileManager -> {
                                FileManagerKeyHandlerHolder.active?.invoke(key, isMacro)
                            }
                        }
                    }
                },
                modifier = Modifier
            )
        }

        if (isSplitPickerVisible) {
            SessionSplitPicker(
                sessions = uiState.sessions,
                activeSessionId = uiState.activeSessionModel?.id,
                onSelect = { id ->
                    isSplitPickerVisible = false
                    viewModel.setSplitSession(id)
                },
                onDismiss = { isSplitPickerVisible = false }
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

/**
 * Render a session as a pane of the main content area, keeping the active/focused semantics
 * of a two-pane split.
 *
 * @param model The session model to render
 * @param isActivePane Whether this pane is the focused (active) pane
 * @param fontSize Font size for terminal panes
 * @param typeface The [Typeface] to use for the terminal text, or null for the default
 * @param enableLigatures Whether OpenType ligature shaping is enabled
 * @param viewClient The [TerminalViewClient] for terminal panes
 * @param palette The terminal palette for terminal panes
 * @param useNativeRenderer Whether terminal panes use the native Canvas plus a hidden
 * input view instead of the legacy view
 * @param hyperlinksEnabled Whether OSC 8 hyperlinks underline and open on tap
 * @param onPaneFocused Callback with the session id when the pane requests focus
 * @param onRemoveSession Callback to remove the session
 * @param onOpenInTerminal Callback to open a terminal in a directory (file manager panes)
 * @param onEditFile Callback to open a file in a rootfs editor (file manager panes)
 * @param onShowMoreMenu Callback to open the terminal "More" sheet (selection toolbar)
 * @param onFontSizeStep Callback with a font size in pixels (pinch-zoom target, already clamped)
 * @param modifier Modifier to apply to the pane
 */
@Composable
private fun SessionPane(
    model: TermuxSessionUiModel,
    isActivePane: Boolean,
    fontSize: Float,
    typeface: Typeface?,
    enableLigatures: Boolean,
    viewClient: TerminalViewClient,
    palette: TerminalPalette,
    useNativeRenderer: Boolean,
    hyperlinksEnabled: Boolean,
    onPaneFocused: (String) -> Unit,
    onRemoveSession: (TermuxSessionUiModel) -> Unit,
    onOpenInTerminal: (String) -> Unit,
    onEditFile: (String) -> Unit,
    onShowMoreMenu: () -> Unit,
    onFontSizeStep: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    when (model) {
        is TermuxSessionUiModel.Terminal -> if (useNativeRenderer) {
            // Native path: the hidden view below owns IME/keys/focus/scroll state, the
            // opaque canvas above is the only renderer (it fills the default background
            // every frame; the hidden view is alpha 0 so it can never show through).
            // Both are full-size so geometry (and updateSize) stays consistent between them.
            // The pane shares its scroll/selection state and glyph metrics between the
            // canvas and the selection overlay so handles and highlight align with paint.
            val paneState = remember(model.id) { ComposeTerminalViewState() }
            val paneMetrics = remember(typeface, fontSize) {
                measureCanvasMetrics(Paint(), typeface ?: Typeface.MONOSPACE, fontSize)
            }
            // Legacy parity: TerminalView.start/stopTextSelectionMode notify
            // mClient.copyModeChanged(isSelectingText()). Observe the pane selection so every
            // clear path (tap, back, hardware key, toolbar action, transcript-end abort)
            // reports the same state, and flush a trailing false if the pane is disposed
            // while still selecting (legacy onDetachedFromWindow stops the mode).
            val selecting = paneState.selection != null
            var copyModeNotified by remember { mutableStateOf(false) }
            // Identity for registry hooks so a disposing pane cannot clobber another pane's.
            val paneHookToken = remember(paneState) { Any() }
            // Expose selection to the shared view client so BACK/IME code points can dismiss
            // it before the escape branch (legacy onKeyPreIme / sendTextToTerminal parity).
            // Only the active pane owns the registry hooks; a secondary pane's selection is
            // not reachable from the focused hidden view's key pipeline anyway.
            LaunchedEffect(selecting, isActivePane) {
                if (isActivePane) {
                    TerminalViewRegistry.activePaneHookToken = paneHookToken
                    TerminalViewRegistry.isActivePaneSelecting = selecting
                    TerminalViewRegistry.clearActivePaneSelection =
                        if (selecting) ({ paneState.selection = null }) else null
                    TerminalViewRegistry.nativeTextInputListener = { paneState.blinkResetTick++ }
                }
                if (copyModeNotified != selecting) {
                    copyModeNotified = selecting
                    viewClient.copyModeChanged(selecting)
                }
            }
            DisposableEffect(paneState) {
                onDispose {
                    TerminalViewRegistry.clearPaneHooks(paneHookToken)
                    if (copyModeNotified) {
                        copyModeNotified = false
                        viewClient.copyModeChanged(false)
                    }
                }
            }
            Box(modifier = modifier.background(Color(palette.background))) {
                // Back closes the selection toolbar like legacy TerminalView.onKeyDown
                // does for the BACK key while a text selection is active.
                BackHandler(enabled = paneState.selection != null) {
                    paneState.selection = null
                }
                HiddenTerminalInputHost(
                    session = model.session,
                    fontSize = fontSize,
                    typeface = typeface,
                    enableLigatures = enableLigatures,
                    viewClient = viewClient,
                    palette = palette,
                    isActivePane = isActivePane,
                    onActivatePane = { onPaneFocused(model.id) },
                    onUserKeyInput = {
                        // Hardware key path: dismiss selection and re-show the cursor phase
                        // like legacy setCursorBlinkState(true) from onKeyDown/inputCodePoint.
                        paneState.selection = null
                        paneState.blinkResetTick++
                    },
                    modifier = Modifier.fillMaxSize()
                )
                ComposeTerminalCanvas(
                    session = model.session,
                    fontSize = fontSize,
                    typeface = typeface,
                    enableLigatures = enableLigatures,
                    palette = palette,
                    metrics = paneMetrics,
                    state = paneState,
                    isActivePane = isActivePane,
                    onActivatePane = { onPaneFocused(model.id) },
                    onLongPressConsumed = { viewClient.onLongPress(null) },
                    onClientTap = { viewClient.onSingleTapUp(null) },
                    hyperlinksEnabled = hyperlinksEnabled,
                    onFontSizeStep = { step ->
                        val target = (fontSize + step).coerceIn(MinTerminalFontSizePx, MaxTerminalFontSizePx)
                        if (target != fontSize) onFontSizeStep(target)
                    },
                    modifier = Modifier.fillMaxSize()
                )
                ComposeTerminalSelectionOverlay(
                    session = model.session,
                    state = paneState,
                    metrics = paneMetrics,
                    onMore = {
                        // "More…" stops the toolbar, stores the selection and opens the
                        // Compose more-menu sheet, which reads the text back through
                        // TerminalViewRegistry (parity with the legacy context menu).
                        val selection = paneState.selection
                        if (selection != null) {
                            TerminalViewRegistry.setStoredSelectedText(
                                model.session.emulator?.getSelectedText(
                                    selection.x1, selection.y1, selection.x2, selection.y2
                                )
                            )
                        }
                        paneState.selection = null
                        onShowMoreMenu()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            TerminalViewHost(
                session = model.session,
                fontSize = fontSize,
                typeface = typeface,
                enableLigatures = enableLigatures,
                viewClient = viewClient,
                palette = palette,
                isActivePane = isActivePane,
                onActivatePane = { onPaneFocused(model.id) },
                hyperlinksEnabled = hyperlinksEnabled,
                modifier = modifier
            )
        }
        is TermuxSessionUiModel.FileManager -> FileManagerSessionHost(
            sessionId = model.id,
            onCloseSession = { onRemoveSession(model) },
            onOpenInTerminal = onOpenInTerminal,
            onEditFile = onEditFile,
            isActivePane = isActivePane,
            onActivatePane = { onPaneFocused(model.id) },
            modifier = modifier
        )
    }
}

/**
 * Dialog that picks the session shown as the secondary pane of a split view.
 *
 * @param sessions All open sessions
 * @param activeSessionId Id of the active (focused) session, excluded from the picker
 * @param onSelect Callback with the chosen secondary session id
 * @param onDismiss Callback to dismiss the dialog
 */
@Composable
private fun SessionSplitPicker(
    sessions: List<TermuxSessionUiModel>,
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val candidates = remember(sessions, activeSessionId) {
        sessions.filter { it.id != activeSessionId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_picker_title)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(candidates, key = { it.id }) { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(session.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (session is TermuxSessionUiModel.FileManager) {
                                Icons.Default.Folder
                            } else {
                                Icons.Default.Terminal
                            },
                            contentDescription = stringResource(R.string.session),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = session.name.ifEmpty {
                                session.title.ifEmpty { stringResource(R.string.application_name) }
                            },
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
