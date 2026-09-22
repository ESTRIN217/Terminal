package com.termux.terminal.compose

import com.termux.shared.logger.Logger
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of the composed [TerminalView]s and coordination of palette/update routing for them.
 *
 * The app can display several sessions at once (two-pane split), and the
 * {@link com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase} callbacks
 * ({@code onTextChanged}, etc.) are global per session and need to reach the [TerminalView]
 * that renders that session. This registry maps each composed session handle to its view
 * instead of holding a single reference.
 *
 * [activeView] stays as the view of the focused (active) pane, so legacy consumers that need
 * "the" terminal (context menu, clipboard, soft keyboard) keep working.
 *
 * It also stores, per view, the last palette that could not be applied because the session
 * emulator was not initialized yet ({@code AndroidView} factories run before layout, so the
 * emulator may not exist when the view is first created). When the emulator gets created
 * later, {@link com.termux.terminal.compose.ComposeTerminalViewClient#onEmulatorSet()} calls
 * [reapplyPendingPalette] per view so the terminal never renders with mismatched default colors.
 */
object TerminalViewRegistry {

    private const val LOG_TAG = "TerminalViewRegistry"

    /** The view of the focused (active) pane, or null when no terminal is focused. */
    @Volatile
    var activeView: TerminalView? = null
        private set

    /**
     * Selected text stored before the "More…" toolbar button is pressed, mirroring
     * `mStoredSelectedText` of
     * {@link com.termux.view.textselection.TextSelectionCursorController}. The Compose
     * selection overlay stores the text here (the hidden input view has no selection of its
     * own), and the app context menu / share-selected-text reads it as a fallback. Cleared
     * when the context menu closes.
     */
    @Volatile
    var storedSelectedText: String? = null
        private set

    /** Store the selected text before the "More…" context menu is shown. */
    @JvmStatic
    fun setStoredSelectedText(text: String?) {
        storedSelectedText = text
    }

    /**
     * Whether the active native pane currently has a text selection (canvas selection
     * overlay, not the hidden view's own controller). Lets
     * [ComposeTerminalViewClient.shouldBackButtonBeMappedToEscape] keep BACK on the
     * selection-dismiss path instead of sending ESC while selecting (legacy
     * `TerminalView.onKeyPreIme` parity).
     */
    @Volatile
    @JvmStatic
    var isActivePaneSelecting: Boolean = false

    /**
     * Clear callback for the active native pane's selection, registered by the pane so
     * client hooks (IME code points, BACK) can dismiss it the same way legacy
     * `stopTextSelectionMode()` does from `sendTextToTerminal` / `onKeyDown`.
     */
    @Volatile
    @JvmStatic
    var clearActivePaneSelection: (() -> Unit)? = null

    /**
     * Notified on soft-IME / client code-point input so the native canvas can re-show the
     * cursor blink phase (legacy `setCursorBlinkState(true)` from `inputCodePoint`), which
     * the hardware-key [com.termux.view.TerminalView] OnKeyListener path does not cover.
     */
    @Volatile
    @JvmStatic
    var nativeTextInputListener: (() -> Unit)? = null

    /**
     * Identity token of the pane that currently owns [clearActivePaneSelection] /
     * [nativeTextInputListener], so a disposing inactive pane cannot clear another pane's hooks.
     */
    @Volatile
    @JvmStatic
    var activePaneHookToken: Any? = null

    /** Dismiss the active native pane's selection if any. Safe to call when none is set. */
    @JvmStatic
    fun dismissActivePaneSelection() {
        clearActivePaneSelection?.invoke()
    }

    /** Re-show the native canvas cursor after soft-IME input. No-op when no pane registered. */
    @JvmStatic
    fun fireNativeTextInput() {
        nativeTextInputListener?.invoke()
    }

    /**
     * Clear selection/text-input hooks owned by [token]. No-op when another pane has since
     * taken ownership.
     *
     * @param token The pane identity that registered the hooks
     */
    @JvmStatic
    fun clearPaneHooks(token: Any) {
        if (activePaneHookToken !== token) return
        activePaneHookToken = null
        isActivePaneSelecting = false
        clearActivePaneSelection = null
        nativeTextInputListener = null
    }

    /** Views currently composed, keyed by the session handle they render. */
    private val viewsBySessionHandle = ConcurrentHashMap<String, TerminalView>()

    /** Palettes that failed to apply (no emulator yet), per view. */
    private val pendingPalettes = ConcurrentHashMap<TerminalView, TerminalPalette>()

    /**
     * Listener invoked on the main thread whenever a session screen update arrives.
     * Used by the experimental Compose Canvas ([ComposeTerminalCanvas]) to repaint.
     *
     * The scroll count is passed here because the legacy hidden input view (whose own
     * [com.termux.view.TerminalView.onScreenUpdated] runs earlier in the same callback and
     * clears the emulator scroll counter) must not consume the value the canvas reads to shift
     * a text selection on new output.
     */
    fun interface FrameListener {
        /**
         * Called when the session frame changed and must be repainted.
         *
         * @param scrollCount Rows the emulator scrolled for this update, read before the hidden
         * input view cleared the counter
         */
        fun onFrame(scrollCount: Int)
    }

    /** Frame listeners per session handle. */
    private val frameListenersBySessionHandle = ConcurrentHashMap<String, MutableSet<FrameListener>>()

    /**
     * Register or refresh a composed view for a session.
     *
     * @param session The session rendered by the view
     * @param view The composed terminal view
     * @param isFocused Whether this view belongs to the focused (active) pane
     */
    @JvmStatic
    fun registerView(session: TerminalSession, view: TerminalView, isFocused: Boolean) {
        viewsBySessionHandle[session.mHandle] = view
        if (isFocused) activeView = view
    }

    /**
     * Unregister a view when it leaves composition.
     *
     * @param session The session rendered by the view
     * @param view The composed terminal view being removed
     */
    @JvmStatic
    fun unregisterView(session: TerminalSession, view: TerminalView) {
        if (viewsBySessionHandle[session.mHandle] === view) {
            viewsBySessionHandle.remove(session.mHandle)
        }
        pendingPalettes.remove(view)
        if (activeView === view) activeView = null
    }

    /**
     * Get the composed view rendering a session, or null when that session is not displayed.
     *
     * @param session The terminal session
     * @return The composed view for the session, or null
     */
    @JvmStatic
    fun getViewForSession(session: TerminalSession): TerminalView? =
        viewsBySessionHandle[session.mHandle]

    /**
     * Run an action for every currently composed terminal view.
     *
     * @param action The action to run per view
     */
    @JvmStatic
    fun forComposedViews(action: (TerminalView) -> Unit) {
        viewsBySessionHandle.values.forEach { action(it) }
    }

    /**
     * Register a frame listener repainted on every screen update of a session.
     *
     * @param session The terminal session to observe
     * @param listener The listener to invoke on the main thread per update
     */
    @JvmStatic
    fun addFrameListener(session: TerminalSession, listener: FrameListener) {
        frameListenersBySessionHandle
            .getOrPut(session.mHandle) { ConcurrentHashMap.newKeySet() }
            .add(listener)
    }

    /**
     * Unregister a frame listener when it leaves composition.
     *
     * @param session The terminal session that was observed
     * @param listener The listener to remove
     */
    @JvmStatic
    fun removeFrameListener(session: TerminalSession, listener: FrameListener) {
        frameListenersBySessionHandle[session.mHandle]?.remove(listener)
    }

    /**
     * Notify frame listeners that a session screen update arrived.
     *
     * Never throws: a failing listener must not break the legacy view invalidate path
     * running on the same callback.
     *
     * @param session The terminal session that changed
     * @param scrollCount Rows the emulator scrolled for this update; captured by the caller
     * before the hidden input view's [com.termux.view.TerminalView.onScreenUpdated] clears the
     * emulator scroll counter
     */
    @JvmStatic
    fun notifyFrameChanged(session: TerminalSession, scrollCount: Int = 0) {
        val listeners = frameListenersBySessionHandle[session.mHandle] ?: return
        listeners.toList().forEach { listener ->
            try {
                listener.onFrame(scrollCount)
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Frame listener failed", e)
            }
        }
    }

    /**
     * Store a palette to be reapplied once the view's session emulator becomes available.
     *
     * @param view The view that could not be painted with the palette yet
     * @param palette The palette that could not be applied yet, or null to clear it
     */
    @JvmStatic
    fun setPendingPalette(view: TerminalView, palette: TerminalPalette?) {
        if (palette == null) pendingPalettes.remove(view) else pendingPalettes[view] = palette
    }

    /**
     * Try applying the stored palette to a view and its session. A no-op when there is no
     * pending palette or the session has no emulator yet.
     *
     * @param view The view to paint
     * @return true if the pending palette was applied and cleared
     */
    @JvmStatic
    fun reapplyPendingPalette(view: TerminalView): Boolean {
        val palette = pendingPalettes[view] ?: return false
        val session = view.currentSession ?: return false
        val applied = applyPalette(view, session, palette)
        if (applied)
            pendingPalettes.remove(view)
        return applied
    }

    /**
     * Write the theme colors into the emulator indexed color palette and paint them on the view.
     *
     * Note that the renderer does not paint cells carrying the default background color, so the
     * palette background is also set as the view background color.
     *
     * @param view The terminal view whose background will be updated
     * @param session The session owning the emulator palette to update
     * @param palette The colors to apply
     * @return true if applied, or false when the session emulator is not initialized yet
     */
    @JvmStatic
    fun applyPalette(view: TerminalView, session: TerminalSession, palette: TerminalPalette): Boolean {
        val emulator = session.emulator ?: return false
        val colors = emulator.mColors.mCurrentColors
        if (palette.scheme != null) {
            System.arraycopy(palette.scheme, 0, colors, 0, minOf(palette.scheme.size, colors.size))
        }
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = palette.foreground
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = palette.background
        colors[TextStyle.COLOR_INDEX_CURSOR] = palette.cursor
        view.setBackgroundColor(palette.background)
        view.onScreenUpdated()
        return true
    }
}