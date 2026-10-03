package com.termux.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

/**
 * Scroll and text-selection state shared between the [ComposeTerminalCanvas] and its
 * [ComposeTerminalSelectionOverlay], owned by the pane that hosts both (one instance per
 * session pane).
 *
 * The canvas owns the scroll offset (gestures land there; the hidden input view underneath
 * stays at top row 0) and the overlay reads it to keep handles glued to the text. The canvas
 * also starts text selection (long press); the overlay drags its handles, refines the
 * selection and paints the Copy/Paste/More toolbar. Both write through the same state so no
 * explicit coordination is needed.
 *
 * It also carries the pane geometry published by the hidden input view (see
 * [HiddenTerminalInputHost]). Nothing in the pane derives it from a Compose measure pass:
 * writing snapshot state from inside one is what makes Compose re-enter measurement and throw
 * {@code IllegalStateException: layout state is not idle before measure starts}, and it leaves
 * a pane that never gets a size painting nothing at all when a second pane shares the screen.
 */
class ComposeTerminalViewState {

    /**
     * The pane size in pixels as reported by the hidden [com.termux.view.TerminalView] layout,
     * or [IntSize.Zero] before the first layout. The hidden view owns the terminal grid
     * ([com.termux.view.TerminalView.updateSize]), so this is the one source of truth for the
     * pane geometry: the canvas repaints when it changes and the selection overlay clamps its
     * toolbar and handle flips against it.
     */
    var paneSize by mutableStateOf(IntSize.Zero)

    /**
     * The canvas scroll offset in rows, with the same semantics as the legacy `mTopRow`:
     * 0 while following live output, negative when scrolled back through the transcript.
     */
    var scrollRows by mutableIntStateOf(0)

    /**
     * The active text selection in external (transcript-aware) row coordinates, or null when
     * no text is selected. Cleared by a tap, by pressing the toolbar's Copy/Paste/More, by
     * the system back button, and by new output reaching the transcript end.
     */
    var selection by mutableStateOf<ComposeTerminalFrame.TextSelection?>(null)

    /**
     * System uptime millis ([android.os.SystemClock.uptimeMillis]) when the current selection
     * started, mirroring the legacy `mShowStartTime` of
     * [com.termux.view.textselection.TextSelectionCursorController] so a canvas tap within the
     * first 300 ms does not dismiss a freshly started selection (legacy `hide()` guard).
     */
    var selectionStartedAt by mutableLongStateOf(0L)

    /**
     * Bumped on hardware/IME input so the canvas can re-show the cursor phase immediately,
     * mirroring legacy `TerminalEmulator.setCursorBlinkState(true)` from
     * `inputCodePoint`/`handleKeyCode` without treating ordinary screen output as input.
     */
    var blinkResetTick by mutableIntStateOf(0)
}
