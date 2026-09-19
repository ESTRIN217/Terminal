package com.termux.terminal.compose

import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView

/**
 * Registry holding the currently active [TerminalView] instance and coordinating palette
 * application for it.
 *
 * The {@link com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase} callbacks
 * ({@code onTextChanged}, etc.) are global per session and need to reach whichever
 * [TerminalView] is currently composed, so the session client looks the view up here
 * instead of holding a reference itself.
 *
 * It also stores the last palette that could not be applied because the session emulator
 * was not initialized yet ({@code AndroidView} factories run before layout, so the emulator
 * may not exist when the view is first created). When the emulator gets created later,
 * {@link com.termux.terminal.compose.ComposeTerminalViewClient#onEmulatorSet()} calls
 * [reapplyPendingPalette] so the terminal never renders with mismatched default colors.
 */
object TerminalViewRegistry {

    /** The currently composed terminal view, or null when no terminal is displayed. */
    @Volatile
    var activeView: TerminalView? = null

    /** The last palette that failed to apply because no emulator existed yet, or null. */
    @Volatile
    private var pendingPalette: TerminalPalette? = null

    /**
     * Store a palette to be reapplied once an emulator becomes available.
     *
     * @param palette The palette that could not be applied yet, or null to clear it
     */
    @JvmStatic
    fun setPendingPalette(palette: TerminalPalette?) {
        pendingPalette = palette
    }

    /**
     * Try applying the stored palette to the active view and its session. A no-op when there
     * is no view, no pending palette or the session has no emulator yet.
     *
     * @return true if the pending palette was applied and cleared
     */
    @JvmStatic
    fun reapplyPendingPalette(): Boolean {
        val view = activeView ?: return false
        val palette = pendingPalette ?: return false
        val session = view.currentSession ?: return false
        val applied = applyPalette(view, session, palette)
        if (applied)
            pendingPalette = null
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
