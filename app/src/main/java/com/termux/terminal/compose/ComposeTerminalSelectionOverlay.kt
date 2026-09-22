package com.termux.terminal.compose

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.R
import kotlin.math.roundToInt

/**
 * Text-selection overlay for the experimental native terminal canvas (Fase 3.6): two draggable
 * handles and a floating Copy/Paste/More toolbar, mirroring the legacy
 * {@link com.termux.view.textselection.TextSelectionCursorController} and its action mode.
 *
 * The overlay is composed on top of [ComposeTerminalCanvas] by [TermuxMainScreen] and shares
 * the pane's [ComposeTerminalViewState], so handle geometry is derived from the same scroll
 * offset and [CanvasFontMetrics] the canvas paints with. The anchoring mirrors the legacy
 * `TextSelectionHandleView.positionAtCursor`: the start handle anchors at the selection start
 * column, the end one at `x2 + 1`; pixel coordinates come from `getPointX/getPointY`
 * (`x = round(cx * fontWidth)`, `y = (cy + 1 - topRow) * lineSpacing`). Each handle is drawn
 * offset by the legacy hotspot (`0.75 * width` for the start, `0.25 * width` for the end,
 * `mHotspotX`) so the droplet tip lands exactly on the bottom corner of its cell, and the
 * drawable flips LEFT/RIGHT near the screen edges like the legacy `checkChangedOrientation`.
 *
 * Dragging a handle updates the selection through [ComposeTerminalFrame.clampSelectionHandle]
 * (rows clamped to `[-rowsInHistory, mRows-1]`, start/end ordering, wide-glyph column
 * snapping) and scrolls the transcript one row per event when the dragged cell crosses a
 * viewport edge (legacy `updatePosition` parity). The drag grabs on touch-down with zero
 * touch-slop (legacy handle `onTouchEvent` parity) and keeps following the pointer until
 * release: the gesture is keyed on stable inputs and reads anchors/metrics through
 * [rememberUpdatedState], so a selection change mid-drag never restarts and cancels it.
 *
 * Pointer events starting on a handle are consumed here from touch-down, so handle drags
 * (and a plain tap on a handle, which must not dismiss the selection) never reach the canvas
 * scroll/fling/tap detectors below; touches landing anywhere else fall through to the
 * canvas, because this composable only consumes on its own child targets.
 *
 * @param session The terminal session being selected
 * @param state The pane scroll/selection state shared with the canvas
 * @param metrics The glyph metrics snapshot shared with the canvas
 * @param onMore Invoked when "More…" is pressed: the app stores the selected text, stops the
 * selection and shows the legacy context menu (reads [TerminalViewRegistry.storedSelectedText])
 * @param modifier Modifier to apply to the overlay
 */
@Composable
internal fun ComposeTerminalSelectionOverlay(
    session: TerminalSession,
    state: ComposeTerminalViewState,
    metrics: CanvasFontMetrics,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selection = state.selection ?: return
    val emulator = session.emulator ?: return
    val context = LocalContext.current
    val density = LocalDensity.current

    val rows = emulator.mRows
    val columns = emulator.mColumns
    val rowsInHistory = maxOf(emulator.screen.getActiveRows() - rows, 0)
    val topRow = state.scrollRows

    // Legacy handle drawables have a ~48dp intrinsic height.
    val handleSize: Dp = 48.dp
    val handleSizePx = with(density) { handleSize.toPx() }

    // Toolbar offset above the selection start (legacy action mode anchors its toolbar near
    // the selection content rect).
    val toolbarGapPx = with(density) { 12.dp.toPx() }

    // Handle anchors in overlay pixels (legacy positionAtCursor parity).
    val startAnchor = Offset(
        x = (selection.x1 * metrics.fontWidth).roundToInt().toFloat(),
        y = ((selection.y1 + 1 - topRow) * metrics.lineSpacing).toFloat()
    )
    val endAnchor = Offset(
        x = ((selection.x2 + 1) * metrics.fontWidth).roundToInt().toFloat(),
        y = ((selection.y2 + 1 - topRow) * metrics.lineSpacing).toFloat()
    )

    // Toolbar size measured on first layout, used to clamp its position onto the canvas.
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }

    // Set while either handle is being dragged: legacy updateFloatingToolbarVisibility hides
    // the floating ActionMode during handle drags (ACTION_MOVE) and restores it on release,
    // so the toolbar never sits under the dragging finger.
    var isDraggingHandle by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canvasWidth = with(density) { maxWidth.toPx() }
        val canvasHeight = with(density) { maxHeight.toPx() }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            SelectionHandle(
                isStart = true,
                anchor = startAnchor,
                handleSize = handleSize,
                state = state,
                emulator = emulator,
                rows = rows,
                metrics = metrics,
                viewWidthPx = canvasWidth,
                onDraggingChange = { isDraggingHandle = it },
                onDrag = { cx, cy, newTopRow ->
                    state.selection = ComposeTerminalFrame.clampSelectionHandle(
                        cx, cy, state.selection ?: return@SelectionHandle,
                        isStart = true, mRows = rows, rowsInHistory = rowsInHistory,
                        columns = columns, screen = emulator.screen
                    )
                    state.scrollRows = newTopRow
                }
            )
            SelectionHandle(
                isStart = false,
                anchor = endAnchor,
                handleSize = handleSize,
                state = state,
                emulator = emulator,
                rows = rows,
                metrics = metrics,
                viewWidthPx = canvasWidth,
                onDraggingChange = { isDraggingHandle = it },
                onDrag = { cx, cy, newTopRow ->
                    state.selection = ComposeTerminalFrame.clampSelectionHandle(
                        cx, cy, state.selection ?: return@SelectionHandle,
                        isStart = false, mRows = rows, rowsInHistory = rowsInHistory,
                        columns = columns, screen = emulator.screen
                    )
                    state.scrollRows = newTopRow
                }
            )

            // Floating toolbar above the selection start, clamped inside the canvas (the legacy
            // action mode anchors its toolbar near the selection content rect). Hidden while a
            // handle is dragged, like the legacy floating ActionMode.
            if (!isDraggingHandle) {
                val toolbarX = (startAnchor.x).coerceIn(0f, (canvasWidth - toolbarSize.width).coerceAtLeast(0f))
                val toolbarY = (startAnchor.y - handleSizePx - toolbarGapPx)
                    .coerceIn(0f, (canvasHeight - toolbarSize.height).coerceAtLeast(0f))
                Surface(
                    modifier = Modifier
                        .offset { IntOffset(toolbarX.roundToInt(), toolbarY.roundToInt()) }
                        .onSizeChanged { toolbarSize = it }
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val selectedText = emulator.getSelectedText(
                        selection.x1, selection.y1, selection.x2, selection.y2
                    )
                    TextButton(
                        onClick = {
                            // Legacy ACTION_COPY: copy to clipboard, then stop the selection
                            // mode (which clears the reverse-video highlight).
                            session.onCopyTextToClipboard(selectedText)
                            state.selection = null
                        },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(text = context.getString(R.string.copy_text))
                    }
                    TextButton(
                        onClick = {
                            // Legacy ACTION_PASTE: stop the selection mode, then paste.
                            state.selection = null
                            session.onPasteTextFromClipboard()
                        },
                        // Legacy parity: Paste is only enabled when the clipboard holds a
                        // primary clip (TextSelectionCursorController onCreateActionMode).
                        enabled = (context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager)?.hasPrimaryClip() == true,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(text = context.getString(R.string.paste_text))
                    }
                    TextButton(
                        onClick = onMore,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(text = context.getString(R.string.text_selection_more))
                    }
                }
                }
            }
        }
    }
}

/**
 * One draggable selection handle: the legacy handle drawable anchored at its grid point,
 * moved by finger drags. The pixel position is tracked as state, re-synced to the anchor
 * whenever the selection or scroll moves externally (edge scroll), and re-mapped to grid
 * coordinates with the inverse of the anchoring formula on every drag event.
 *
 * The Box is offset by the legacy hotspot (`mHotspotX`: `0.75 * width` when the drawable
 * points right i.e. the handle sits left of its tip, `0.25 * width` when it points left) so
 * the droplet tip lands on the anchor. The drawable flips near the screen edges like the
 * legacy `TextSelectionHandleView.checkChangedOrientation` (throttled to 50 ms while
 * dragging, forced once at show).
 *
 * The gesture is keyed only on [isStart]: anchors, metrics, rows and callbacks are read
 * through [rememberUpdatedState], so a selection change mid-drag does not restart (and
 * cancel) the gesture — the handle keeps following the finger until release.
 *
 * @param isStart Whether this is the start (left) handle
 * @param anchor The current anchor in overlay pixels (re-synced on external selection/scroll
 * changes, not at drag start — mid-drag re-syncs follow the text, not the finger)
 * @param handleSize The handle drawable size in dp
 * @param state The pane scroll/selection state shared with the canvas
 * @param emulator The session emulator (edge-scroll rows and alternate-buffer check)
 * @param rows The number of screen rows
 * @param metrics The glyph metrics snapshot shared with the canvas
 * @param viewWidthPx The overlay/canvas width in px (edge-orientation flip bounds)
 * @param onDraggingChange Notified with true on handle touch-down (zero-slop grab, which also
 * hides the floating toolbar like the legacy `onTouchEvent`) and false when the pointer
 * releases or the gesture is cancelled
 * @param onDrag Notified per drag event with the grid column/row and the possibly edge-scrolled
 * viewport offset
 */
@Composable
private fun SelectionHandle(
    isStart: Boolean,
    anchor: Offset,
    handleSize: Dp,
    state: ComposeTerminalViewState,
    emulator: TerminalEmulator,
    rows: Int,
    metrics: CanvasFontMetrics,
    viewWidthPx: Float,
    onDraggingChange: (Boolean) -> Unit,
    onDrag: (cx: Int, cy: Int, newTopRow: Int) -> Unit
) {
    val density = LocalDensity.current
    val handleSizePx = with(density) { handleSize.toPx() }

    // Legacy orientation: start handle draws LEFT (tip at its right edge), end handle draws
    // RIGHT (tip at its left edge); flips when the tip would leave the screen horizontally.
    var isLeft by remember { mutableStateOf(isStart) }
    val lastFlipUptime = remember { longArrayOf(0L) }

    // Legacy show() forces the orientation check once per handle appearance.
    fun flipIfNeeded(posX: Float, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastFlipUptime[0] < 50L) return
        lastFlipUptime[0] = now
        isLeft = if (posX - handleSizePx < 0f) false
            else if (posX + handleSizePx > viewWidthPx) true
            else isStart
    }
    LaunchedEffect(Unit) {
        flipIfNeeded(anchor.x, force = true)
    }

    // Reads inside the gesture: stable across recompositions, current at event time.
    val currentEmulator by rememberUpdatedState(emulator)
    val currentRows by rememberUpdatedState(rows)
    val currentMetrics by rememberUpdatedState(metrics)
    val currentState by rememberUpdatedState(state)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val currentOnDrag by rememberUpdatedState(onDrag)

    var handlePos: Offset by remember { mutableStateOf(anchor) }

    // The selection or the scroll offset changed through the state (edge scroll, new output):
    // stick the handle back onto its freshly computed anchor so it keeps gluing to the text.
    // Intentionally NOT at drag start: legacy detectDragGestures re-anchored on drag start,
    // but the zero-slop grab must not teleport the handle away from the finger.
    LaunchedEffect(anchor) {
        handlePos = anchor
    }

    Box(
        modifier = Modifier
            .offset {
                // Legacy hotspot: the drawn image is shifted so its tip sits on the anchor.
                val hotspotX = if (isLeft) handleSizePx * 0.75f else handleSizePx * 0.25f
                IntOffset((handlePos.x - hotspotX).roundToInt(), handlePos.y.roundToInt())
            }
            .width(handleSize)
            .heightIn(min = handleSize, max = handleSize)
            .pointerInput(isStart) {
                awaitEachGesture {
                    // Zero-slop grab: consume the DOWN immediately (legacy handle onTouchEvent
                    // returns true on ACTION_DOWN), so the canvas tap detector never sees it
                    // and a tap on the handle cannot dismiss the selection.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val pointerId = down.id
                    currentOnDraggingChange(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val drag = change.position - change.previousPosition
                            if (drag != Offset.Zero) {
                                change.consume()
                                handlePos = Offset(
                                    handlePos.x + drag.x, handlePos.y + drag.y
                                )
                                flipIfNeeded(handlePos.x, force = false)
                                val m = currentMetrics
                                val topRow = currentState.scrollRows
                                var cx = (handlePos.x / m.fontWidth).toInt()
                                var cy = (handlePos.y / m.lineSpacing).toInt() + topRow - 1
                                var newTopRow = topRow
                                if (!currentEmulator.isAlternateBufferActive) {
                                    // Edge scroll: crossing a viewport edge moves the transcript
                                    // one row per event (legacy updatePosition parity), and the
                                    // cell is re-mapped so the endpoint follows the text.
                                    newTopRow = if (cy <= topRow) topRow - 1
                                        else if (cy >= topRow + currentRows) topRow + 1
                                        else topRow
                                    if (newTopRow != topRow) {
                                        newTopRow = ComposeTerminalFrame.clampScrollOffset(
                                            newTopRow,
                                            currentEmulator.screen.activeTranscriptRows
                                        )
                                        cy = (handlePos.y / m.lineSpacing).toInt() +
                                            newTopRow - 1
                                    }
                                }
                                currentOnDrag(cx, cy, newTopRow)
                            }
                        }
                    } finally {
                        currentOnDraggingChange(false)
                    }
                }
            }
    ) {
        Image(
            painter = painterResource(
                if (isLeft) R.drawable.text_select_handle_left_material
                else R.drawable.text_select_handle_right_material
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}
