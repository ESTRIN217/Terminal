package com.termux.terminal.compose

import com.termux.terminal.TerminalSession

/**
 * UI state for the Termux main screen.
 *
 * @param sessions List of active terminal sessions
 * @param activeSessionIndex Index of the currently active session
 * @param isDrawerOpen Whether the navigation drawer is open
 * @param isExtraKeysVisible Whether the extra keys bar is visible
 * @param extraKeysModifiers Sticky modifier keys active on the extra keys bar
 * @param isSoftKeyboardVisible Whether the soft keyboard is visible
 * @param fontSize Font size for the terminal, in density-independent pixels
 * @param debianInstaller Debian rootfs installer overlay state
 */
data class TermuxUiState(
    val sessions: List<TermuxSessionUiModel> = emptyList(),
    val activeSessionIndex: Int = 0,
    val isDrawerOpen: Boolean = false,
    val isExtraKeysVisible: Boolean = true,
    val extraKeysModifiers: Set<String> = emptySet(),
    val isSoftKeyboardVisible: Boolean = false,
    val fontSize: Float = 14f,
    val extraKeysConfig: ExtraKeysConfig = ExtraKeysConfig(pages = emptyList()),
    val debianInstaller: DebianInstallerUiState = DebianInstallerUiState()
) {
    /**
     * Get the currently active session model, or null if no sessions exist.
     */
    val activeSessionModel: TermuxSessionUiModel?
        get() = sessions.getOrNull(activeSessionIndex)

    /**
     * Get the currently active terminal session, or null if no terminal session is active.
     *
     * Returns null when the active tab is not a terminal session (e.g. a file manager session).
     */
    val activeSession: TerminalSession?
        get() = (sessions.getOrNull(activeSessionIndex) as? TermuxSessionUiModel.Terminal)?.session

    /**
     * Whether there are any sessions.
     */
    val hasSessions: Boolean
        get() = sessions.isNotEmpty()
}

/**
 * Session tab UI model: either a real terminal session or a file manager session.
 *
 * @param id Stable id used to identify the tab and key per-session state
 * @param name Display name for the tab
 * @param title Secondary title (terminal escape-sequence title)
 */
sealed class TermuxSessionUiModel {
    abstract val id: String
    abstract val name: String
    abstract val title: String

    /**
     * A real pty terminal session.
     *
     * @param name Display name for the session
     * @param session The underlying terminal session
     * @param title Current terminal title
     */
    data class Terminal(
        override val name: String,
        val session: TerminalSession,
        override val title: String = ""
    ) : TermuxSessionUiModel() {
        override val id: String
            get() = session.mHandle
    }

    /**
     * A file manager session, purely UI backed.
     *
     * @param id Stable id for the tab
     * @param name Display name for the session
     */
    data class FileManager(
        override val id: String,
        override val name: String
    ) : TermuxSessionUiModel() {
        override val title: String = ""
    }
}

/**
 * UI state for the Debian rootfs installer overlay (Fase 3).
 *
 * @param visible Whether the installer overlay is shown instead of the terminal
 * @param progress Download progress in [0,1], or {@code null} when indeterminate
 * @param statusText Human-readable status line (already formatted by the activity)
 * @param error Error message when installation failed, {@code null} otherwise
 */
data class DebianInstallerUiState(
    val visible: Boolean = false,
    val progress: Float? = null,
    val statusText: String = "",
    val error: String? = null
)
