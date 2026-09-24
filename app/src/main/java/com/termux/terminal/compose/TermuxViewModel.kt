package com.termux.terminal.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the Termux main screen.
 *
 * Manages terminal sessions, UI state, and coordinates with TermuxService.
 */
class TermuxViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TermuxUiState())
    val uiState: StateFlow<TermuxUiState> = _uiState.asStateFlow()

    /**
     * Add a new terminal session to the UI state.
     *
     * @param session The terminal session to add
     * @param name Display name for the session
     */
    fun addSession(session: TerminalSession, name: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val newModel = TermuxSessionUiModel.Terminal(name = name, session = session)
                val newSessions = state.sessions + newModel
                sanitizeSplit(activateSession(state.copy(
                    sessions = newSessions
                ), newSessions.lastIndex))
            }
        }
    }

    /**
     * Add a new file manager session to the UI state.
     *
     * Each file manager tab gets its own stable id so its per-session state
     * (a [com.estrin217.filemanager.compose.FileManagerViewModel]) can be keyed.
     *
     * @param baseName Base display name for the session; numbered when multiple
     * file manager tabs exist (e.g. "File Manager 2").
     * @return The stable id of the new session
     */
    fun createFileManagerSession(baseName: String): String {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            _uiState.update { state ->
                val fmCount = state.sessions.count { it is TermuxSessionUiModel.FileManager }
                val name = if (fmCount == 0) baseName else "$baseName ${fmCount + 1}"
                val newModel = TermuxSessionUiModel.FileManager(id = id, name = name)
                val newSessions = state.sessions + newModel
                sanitizeSplit(activateSession(state.copy(
                    sessions = newSessions
                ), newSessions.lastIndex))
            }
        }
        return id
    }

    /**
     * Remove a session from the UI state.
     *
     * @param sessionModel The session model to remove
     */
    fun removeSession(sessionModel: TermuxSessionUiModel) {
        viewModelScope.launch {
            _uiState.update { state ->
                val index = state.sessions.indexOfFirst { it.id == sessionModel.id }
                if (index < 0) return@update state

                val newSessions = state.sessions.toMutableList().apply { removeAt(index) }
                val newActiveIndex = when {
                    newSessions.isEmpty() -> 0
                    index <= state.activeSessionIndex -> (state.activeSessionIndex - 1).coerceAtLeast(0)
                    else -> state.activeSessionIndex
                }

                sanitizeSplit(state.copy(
                    sessions = newSessions,
                    activeSessionIndex = newActiveIndex
                ))
            }
        }
    }

    /**
     * Switch to a different session.
     *
     * With a split active: when the target session is already visible in a pane it only
     * becomes the focused one (pane positions never move); when it is not visible, it
     * replaces the session in the currently focused pane slot.
     *
     * @param index Index of the session to switch to
     */
    fun switchSession(index: Int) {
        viewModelScope.launch {
            _uiState.update { state ->
                sanitizeSplit(activateSession(state, index))
            }
        }
    }

    /**
     * Switch to the next session.
     */
    fun nextSession() {
        viewModelScope.launch {
            _uiState.update { state ->
                if (state.sessions.isEmpty()) return@update state
                val newIndex = (state.activeSessionIndex + 1) % state.sessions.size
                sanitizeSplit(activateSession(state, newIndex))
            }
        }
    }

    /**
     * Switch to the previous session.
     */
    fun previousSession() {
        viewModelScope.launch {
            _uiState.update { state ->
                if (state.sessions.isEmpty()) return@update state
                val newIndex = if (state.activeSessionIndex <= 0) {
                    state.sessions.lastIndex
                } else {
                    state.activeSessionIndex - 1
                }
                sanitizeSplit(activateSession(state, newIndex))
            }
        }
    }

    /**
     * Set the session shown in the other pane of the split view.
     *
     * When no split is active, the active session stays as the left pane and [id] becomes the
     * right pane. With a split already open, the pane that does not hold the focused session
     * is replaced, keeping both positions stable. Passing null clears the split.
     *
     * @param id Stable id of the session to show in the other pane, or null to clear
     */
    fun setSplitSession(id: String?) {
        viewModelScope.launch {
            _uiState.update { state ->
                if (id == null) return@update state.copy(split = null)
                val activeId = state.activeSessionModel?.id ?: return@update state
                if (id == activeId) return@update state
                sanitizeSplit(state.copy(split = SplitState(paneOneId = activeId, paneTwoId = id)))
            }
        }
    }

    /**
     * Close the split view, keeping the active session full-screen.
     */
    fun clearSplit() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(split = null)
            }
        }
    }

    /**
     * Make the session with the given id the focused (active) session.
     *
     * When the session occupies a split pane it becomes the focused pane without moving: the
     * pane stays in its position. When no split is active this is equivalent to switching to
     * that session.
     *
     * @param id Stable id of the session to focus
     */
    fun focusSession(id: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val index = state.sessions.indexOfFirst { it.id == id }
                activateSession(state, index)
            }
        }
    }

    /**
     * Toggle the navigation drawer open/closed.
     */
    fun toggleDrawer() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(isDrawerOpen = !state.isDrawerOpen)
            }
        }
    }

    /**
     * Set the drawer open/closed state.
     *
     * @param open Whether the drawer should be open
     */
    fun setDrawerOpen(open: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(isDrawerOpen = open)
            }
        }
    }

    /**
     * Toggle the extra keys bar visibility.
     */
    fun toggleExtraKeys() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(isExtraKeysVisible = !state.isExtraKeysVisible)
            }
        }
    }

    /**
     * Set the extra keys bar visibility.
     *
     * @param visible Whether the extra keys should be visible
     */
    fun setExtraKeysVisible(visible: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(isExtraKeysVisible = visible)
            }
        }
    }

    /**
     * Toggle a sticky modifier key of the extra keys bar (CTRL, ALT, SHIFT, FN).
     *
     * The modifier stays active until toggled again, so it also applies to keys
     * typed on the system keyboard via [com.termux.view.TerminalViewClient.readControlKey]
     * and friends (matched to classic Termux behavior).
     *
     * @param modifier The modifier key name (e.g., "CTRL")
     */
    fun toggleExtraKeysModifier(modifier: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val newModifiers = if (modifier in state.extraKeysModifiers) {
                    state.extraKeysModifiers - modifier
                } else {
                    state.extraKeysModifiers + modifier
                }
                state.copy(extraKeysModifiers = newModifiers)
            }
        }
    }

    /**
     * Set the soft keyboard visibility.
     *
     * @param visible Whether the soft keyboard is visible
     */
    fun setSoftKeyboardVisible(visible: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(isSoftKeyboardVisible = visible)
            }
        }
    }

    /**
     * Update the font size.
     *
     * @param size The new font size in pixels
     */
    fun setFontSize(size: Float) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(fontSize = size)
            }
        }
    }

    /**
     * Enable or disable the experimental native Compose Canvas renderer.
     *
     * When false (default) terminal panes keep using the legacy TerminalView.
     *
     * @param enabled Whether the Compose Canvas spike should be used
     */
    fun setNativeRenderer(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(useNativeRenderer = enabled)
            }
        }
    }

    /**
     * Enable or disable OSC 8 hyperlink underlines and tap-to-open.
     *
     * @param enabled Whether hyperlinks are interactive
     */
    fun setHyperlinksEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(hyperlinksEnabled = enabled)
            }
        }
    }

    /**
     * Enable or disable inline terminal image painting.
     *
     * @param enabled Whether images are shown
     */
    fun setImagesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(imagesEnabled = enabled)
            }
        }
    }

    /**
     * Update the title for a session.
     *
     * @param session The session whose title changed
     * @param title The new title
     */
    fun updateSessionTitle(session: TerminalSession, title: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val newSessions = state.sessions.map { model ->
                    if (model is TermuxSessionUiModel.Terminal && model.session == session) {
                        model.copy(title = title)
                    } else {
                        model
                    }
                }
                state.copy(sessions = newSessions)
            }
        }
    }

    /**
     * Update the name for a session.
     *
     * @param session The session to rename
     * @param name The new name
     */
    fun renameSession(session: TerminalSession, name: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val newSessions = state.sessions.map { model ->
                    if (model is TermuxSessionUiModel.Terminal && model.session == session) {
                        model.copy(name = name)
                    } else {
                        model
                    }
                }
                state.copy(sessions = newSessions)
            }
        }
    }

    /**
     * Set the extra keys configuration.
     *
     * @param config The parsed extra keys configuration
     */
    fun setExtraKeysConfig(config: ExtraKeysConfig) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(extraKeysConfig = config)
            }
        }
    }

    /**
     * Show the Debian installer overlay.
     */
    fun showDebianInstaller() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(debianInstaller = DebianInstallerUiState(visible = true))
            }
        }
    }

    /**
     * Update Debian installer progress.
     *
     * @param progress Download progress in [0,1], or {@code null} when indeterminate
     * @param statusText Formatted status line
     */
    fun updateDebianInstallerProgress(progress: Float?, statusText: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    debianInstaller = state.debianInstaller.copy(
                        progress = progress,
                        statusText = statusText,
                        error = null
                    )
                )
            }
        }
    }

    /**
     * Show a Debian installer error with retry option.
     *
     * @param error The error message to display
     */
    fun setDebianInstallerError(error: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(debianInstaller = state.debianInstaller.copy(error = error))
            }
        }
    }

    /**
     * Hide the Debian installer overlay.
     */
    fun hideDebianInstaller() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(debianInstaller = state.debianInstaller.copy(visible = false))
            }
        }
    }
}

/**
 * Activate (focus) the session at [index].
 *
 * No split active: simply becomes the focused session.
 *
 * Split active: when the target session already occupies a pane it only becomes focused and
 * the pane positions never move. When it is not visible, it replaces the session in the pane
 * that currently holds the focused session, so the newly activated session is always visible
 * and focused.
 *
 * @param state The current UI state
 * @param index Index of the session to activate
 * @return The updated state
 */
internal fun activateSession(state: TermuxUiState, index: Int): TermuxUiState {
    if (index !in state.sessions.indices) return state
    val split = state.split
    if (split == null) return state.copy(activeSessionIndex = index)
    val targetId = state.sessions[index].id
    if (split.contains(targetId)) return state.copy(activeSessionIndex = index)
    val activeId = state.activeSessionModel?.id
    return if (split.paneOneId == activeId) {
        state.copy(
            activeSessionIndex = index,
            split = SplitState(paneOneId = targetId, paneTwoId = split.paneTwoId)
        )
    } else {
        state.copy(
            activeSessionIndex = index,
            split = SplitState(paneOneId = split.paneOneId, paneTwoId = targetId)
        )
    }
}

/**
 * Drop stale split panes and restore the invariant that the focused session is visible.
 *
 * Pane ids referencing removed sessions are dropped; when fewer than two panes remain valid
 * the split is cleared. When the focused session is not one of the panes (for example after a
 * session the split tracked was replaced), focus falls back to the left pane.
 *
 * @param state The current UI state
 * @return The state with a valid split and a focused session that is visible when split
 */
internal fun sanitizeSplit(state: TermuxUiState): TermuxUiState {
    val split = state.split ?: return state
    val validPaneIds = listOf(split.paneOneId, split.paneTwoId)
        .distinct()
        .filter { id -> state.sessions.any { it.id == id } }
    if (validPaneIds.size < 2) return state.copy(split = null)
    val validSplit = SplitState(paneOneId = validPaneIds[0], paneTwoId = validPaneIds[1])
    val focusedId = state.activeSessionModel?.id
    return if (focusedId != null && validSplit.contains(focusedId)) {
        state.copy(split = validSplit)
    } else {
        state.copy(
            split = validSplit,
            activeSessionIndex = state.sessions.indexOfFirst { it.id == validSplit.paneOneId }
        )
    }
}
