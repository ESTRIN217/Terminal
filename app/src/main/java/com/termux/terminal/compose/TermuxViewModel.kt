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
                val newModel = TerminalSessionUiModel(session = session, name = name)
                val newSessions = state.sessions + newModel
                state.copy(
                    sessions = newSessions,
                    activeSessionIndex = newSessions.lastIndex
                )
            }
        }
    }

    /**
     * Remove a terminal session from the UI state.
     *
     * @param session The terminal session to remove
     */
    fun removeSession(session: TerminalSession) {
        viewModelScope.launch {
            _uiState.update { state ->
                val index = state.sessions.indexOfFirst { it.session == session }
                if (index < 0) return@update state

                val newSessions = state.sessions.toMutableList().apply { removeAt(index) }
                val newActiveIndex = when {
                    newSessions.isEmpty() -> 0
                    index <= state.activeSessionIndex -> (state.activeSessionIndex - 1).coerceAtLeast(0)
                    else -> state.activeSessionIndex
                }

                state.copy(
                    sessions = newSessions,
                    activeSessionIndex = newActiveIndex
                )
            }
        }
    }

    /**
     * Switch to a different session.
     *
     * @param index Index of the session to switch to
     */
    fun switchSession(index: Int) {
        viewModelScope.launch {
            _uiState.update { state ->
                if (index in state.sessions.indices) {
                    state.copy(activeSessionIndex = index)
                } else {
                    state
                }
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
                state.copy(activeSessionIndex = newIndex)
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
                state.copy(activeSessionIndex = newIndex)
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
     * Update the title for a session.
     *
     * @param session The session whose title changed
     * @param title The new title
     */
    fun updateSessionTitle(session: TerminalSession, title: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val newSessions = state.sessions.map { model ->
                    if (model.session == session) {
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
                    if (model.session == session) {
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
