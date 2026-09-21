package com.termux.terminal.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
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
 * (`x = round(cx * fontWidth)`, `y = (cy + 1 - topRow) * lineSpacing`).
 *
 * Dragging a handle updates the selection through [ComposeTerminalFrame.clampSelectionHandle]
 * (rows clamped to `[-rowsInHistory, mRows-1]`, start/end ordering, wide-glyph column
 * snapping) and scrolls the transcript one row per event when the dragged cell crosses a
 * viewport edge (legacy `updatePosition` parity).
 *
 * Pointer events starting on a handle target are consumed here, so handle drags never reach
 * the canvas scroll/fling detectors below; touches landing anywhere else fall through to the
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
            // action mode anchors its toolbar near the selection content rect).
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

/**
 * One draggable selection handle: the legacy handle drawable anchored at its grid point,
 * moved by finger drags. The pixel position is tracked as state, re-synced to the anchor
 * whenever the selection or scroll moves externally (edge scroll), and re-mapped to grid
 * coordinates with the inverse of the anchoring formula on every drag event.
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
    onDrag: (cx: Int, cy: Int, newTopRow: Int) -> Unit
) {
    var handlePos: Offset by remember { mutableStateOf(anchor) }

    // The selection or the scroll offset changed through the state (edge scroll, new output):
    // stick the handle back onto its freshly computed anchor so it keeps gluing to the text.
    LaunchedEffect(anchor) {
        handlePos = anchor
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(handlePos.x.roundToInt(), handlePos.y.roundToInt())
            }
            .width(handleSize)
            .heightIn(min = handleSize, max = handleSize)
            .pointerInput(isStart, anchor, rows, metrics) {
                detectDragGestures(
                    onDragStart = {
                        handlePos = anchor
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        handlePos = Offset(handlePos.x + dragAmount.x, handlePos.y + dragAmount.y)
                        val topRow = state.scrollRows
                        var cx = (handlePos.x / metrics.fontWidth).toInt()
                        var cy = (handlePos.y / metrics.lineSpacing).toInt() + topRow - 1
                        var newTopRow = topRow
                        if (!emulator.isAlternateBufferActive) {
                            // Edge scroll: crossing a viewport edge moves the transcript one
                            // row per event (legacy updatePosition parity), and the cell is
                            // re-mapped so the endpoint follows the text that moved.
                            newTopRow = if (cy <= topRow) topRow - 1
                                else if (cy >= topRow + rows) topRow + 1
                                else topRow
                            if (newTopRow != topRow) {
                                newTopRow = ComposeTerminalFrame.clampScrollOffset(
                                    newTopRow, emulator.screen.activeTranscriptRows
                                )
                                cy = (handlePos.y / metrics.lineSpacing).toInt() + newTopRow - 1
                            }
                        }
                        onDrag(cx, cy, newTopRow)
                    },
                    onDragEnd = { },
                    onDragCancel = { }
                )
            }
    ) {
        Image(
            painter = painterResource(
                if (isStart) R.drawable.text_select_handle_left_material
                else R.drawable.text_select_handle_right_material
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}