package com.termux.terminal.compose

import com.termux.shared.interact.ShareUtils
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession

/**
 * [TermuxTerminalSessionClientBase] implementation for the Compose UI.
 *
 * Forwards session events to the currently composed [com.termux.view.TerminalView] (looked
 * up in [TerminalViewRegistry]) and to the [TermuxViewModel] state. It is registered on
 * {@link com.termux.app.TermuxService} when {@link com.termux.app.TermuxComposeActivity}
 * binds, replacing the service client so that the view gets screen update notifications.
 */
class ComposeTerminalSessionClient(
    private val mViewModel: TermuxViewModel,
    private val mOnRemoveSession: (TerminalSession) -> Unit
) : TermuxTerminalSessionClientBase() {

    override fun onTextChanged(changedSession: TerminalSession) {
        // Repaint the composed view rendering this session, whether it is the focused
        // pane or a secondary pane of a split view.
        TerminalViewRegistry.getViewForSession(changedSession)?.onScreenUpdated()
    }

    override fun onTitleChanged(updatedSession: TerminalSession) {
        mViewModel.updateSessionTitle(updatedSession, updatedSession.title ?: "")
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // A clean exit (0) or Ctrl+C (130) removes the session right away.
        val exitCode = finishedSession.exitStatus
        if (exitCode == 0 || exitCode == 130) {
            mOnRemoveSession(finishedSession)
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val view = TerminalViewRegistry.activeView ?: return
        ShareUtils.copyTextToClipboard(view.context, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val view = TerminalViewRegistry.activeView ?: return
        val text = ShareUtils.getTextStringFromClipboardIfSet(view.context, true) ?: return
        (session ?: view.mTermSession)?.getEmulator()?.paste(text)
    }

    override fun onColorsChanged(changedSession: TerminalSession) {
        TerminalViewRegistry.getViewForSession(changedSession)?.onScreenUpdated()
    }
}
