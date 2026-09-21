package com.termux.terminal.compose

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.termux.shared.logger.Logger
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.terminal.bridge.TerminalKeyHandler
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Experimental native terminal canvas (Fase 3.6): paints a [TerminalSession] with a
 * Compose [Canvas] instead of the legacy {@link com.termux.view.TerminalView}.
 *
 * Key input is delegated to [HiddenTerminalInputHost], a full-size non-drawing view composed
 * below this canvas that owns the IME pipeline, hardware keys and focus. Tapping the canvas
 * focuses that view and shows the soft keyboard. The scroll offset, however, is owned here
 * (gestures land on this canvas, so the hidden view stays at top row 0): dragging moves a
 * row offset clamped to the transcript, and new output keeps following only while the
 * offset is 0.
 *
 * Still deferred: text selection, cursor blinking (painted statically when visible),
 * pinch-zoom and fling inertia.
 * The legacy view set via [TerminalViewHost] stays as the default fallback behind the
 * {@code native_compose_renderer} feature flag.
 *
 * Sizing mirrors {@link com.termux.view.TerminalView#updateSize()}: the canvas [onSizeChanged]
 * (post-layout, never during measure) derives columns/rows from the font metrics and calls
 * [TerminalSession.updateSize]. Repaints are driven by [TerminalViewRegistry] frame listeners
 * bumped from [ComposeTerminalSessionClient] on every emulator screen update.
 *
 * @param session The terminal session to paint
 * @param fontSize Font size in pixels (same raw unit the legacy renderer receives)
 * @param typeface The [Typeface] for the terminal text, or null for the default
 * @param enableLigatures Whether OpenType ligature shaping is enabled
 * @param palette Colors applied to the emulator and the canvas background
 * @param isActivePane Whether this canvas belongs to the focused (active) pane of a split view
 * @param onActivatePane Callback when the canvas is tapped while it is not the active pane
 * @param modifier Modifier to apply to the canvas
 */
@Composable
fun ComposeTerminalCanvas(
    session: TerminalSession,
    fontSize: Float,
    typeface: Typeface?,
    enableLigatures: Boolean,
    palette: TerminalPalette,
    isActivePane: Boolean = true,
    onActivatePane: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Repaint tick bumped on every emulator screen update (see ComposeTerminalSessionClient).
    // Read during composition so each tick recomposes and repaints this session only.
    var frameTick by remember(session) { mutableIntStateOf(0) }
    DisposableEffect(session) {
        val listener = TerminalViewRegistry.FrameListener { frameTick++ }
        TerminalViewRegistry.addFrameListener(session, listener)
        onDispose { TerminalViewRegistry.removeFrameListener(session, listener) }
    }

    // Canvas-owned scroll offset in rows (0 = following live output, negative = scrolled
    // back). Gestures land on this canvas, so the hidden input view underneath stays at
    // top row 0 and keeps following output internally.
    var scrollRows by remember(session) { mutableIntStateOf(0) }
    // Sub-row drag leftovers, carried across drag events.
    var dragRemainder by remember(session) { mutableFloatStateOf(0f) }

    // New output while scrolled back: keep the offset, re-clamped in case the transcript
    // shrank. Following (0) needs no work. State writes stay out of the draw scope.
    LaunchedEffect(frameTick) {
        val transcript = session.emulator?.screen?.activeTranscriptRows ?: 0
        scrollRows = ComposeTerminalFrame.clampScrollOffset(scrollRows, transcript)
    }

    // The canvas itself is never focusable: taps forward focus plus the soft keyboard to
    // the hidden input view below (see HiddenTerminalInputHost).
    val context = LocalContext.current

    val paint = remember { Paint().apply { isAntiAlias = true } }
    val resolvedTypeface = typeface ?: Typeface.MONOSPACE
    val metrics = remember(resolvedTypeface, fontSize) {
        measureCanvasMetrics(paint, resolvedTypeface, fontSize)
    }

    // Last laid-out size, used to derive the grid before first paint.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(palette.background))
            .onSizeChanged { size ->
                canvasSize = size
                val columns = (size.width / metrics.fontWidth).toInt().coerceAtLeast(1)
                val rows = ((size.height - metrics.lineSpacingAndAscent) / metrics.lineSpacing)
                    .toInt().coerceAtLeast(1)
                try {
                    session.updateSize(columns, rows, metrics.fontWidth.toInt(), metrics.lineSpacing)
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "updateSize failed", e)
                }
            }
            .pointerInput(session, isActivePane) {
                detectTapGestures(
                    onTap = {
                        // Promote a secondary split pane first, then hand focus and the
                        // soft keyboard to the hidden input view owning this session.
                        if (!isActivePane) onActivatePane?.invoke()
                        val inputView = TerminalViewRegistry.getViewForSession(session)
                        if (inputView != null) {
                            inputView.requestFocus()
                            KeyboardUtils.showSoftKeyboard(context, inputView)
                        } else {
                            Logger.logWarn(LOG_TAG, "Tap with no hidden input view for session")
                        }
                    }
                )
            }
            .pointerInput(session, metrics) {
                // Anchor for wheel events sent to mouse-tracking apps: legacy TerminalView
                // anchors them at the touch down position, so remember it at drag start.
                var dragAnchor = androidx.compose.ui.geometry.Offset.Zero
                detectVerticalDragGestures(
                    onDragStart = {
                        dragRemainder = 0f
                        dragAnchor = it
                    },
                    onVerticalDrag = { _, dragAmount ->
                        val emulator = session.emulator
                        if (emulator != null) {
                            val (rows, remainder) = ComposeTerminalFrame.accumulateDragRows(
                                dragRemainder, dragAmount, metrics.lineSpacing
                            )
                            dragRemainder = remainder
                            if (rows != 0) {
                                when {
                                    emulator.isMouseTrackingActive -> {
                                        // Mouse-tracking apps (opencode TUI, vim mouse mode, ...)
                                        // consume wheel events instead of the transcript or the
                                        // alt screen. Mirror legacy doScroll(): a swipe down
                                        // (rows > 0) reports wheel-up.
                                        val column = (dragAnchor.x / metrics.fontWidth).toInt() + 1
                                        val row = ((dragAnchor.y - metrics.lineSpacingAndAscent) / metrics.lineSpacing)
                                            .toInt() + 1
                                        val button = if (rows > 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON
                                            else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                                        repeat(abs(rows)) {
                                            emulator.sendMouseEvent(button, column, row, true)
                                        }
                                    }
                                    emulator.isAlternateBufferActive -> {
                                        // Full-screen apps (vim, less) have no transcript:
                                        // drive the cursor with arrow keys instead. Legacy
                                        // doScroll() parity: swipe down moves up.
                                        repeat(abs(rows)) {
                                            session.write(
                                                TerminalKeyHandler.getKeySequence(
                                                    if (rows > 0) "UP" else "DOWN"
                                                )
                                            )
                                        }
                                    }
                                    else -> {
                                        // Legacy doScroll() parity: swipe down scrolls back
                                        // toward older output, swipe up toward live (0).
                                        scrollRows = ComposeTerminalFrame.scrollByDrag(
                                            scrollRows, rows, emulator.screen.activeTranscriptRows
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
    ) {
        if (canvasSize == IntSize.Zero) return@Canvas
        val emulator = session.emulator ?: return@Canvas
        // State read inside the draw scope subscribes this canvas: every frame tick
        // schedules a redraw of this session only.
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        // Display-time clamp covers transcript resizes between frames; the stored offset
        // is re-clamped in LaunchedEffect(frameTick) above.
        val topRow = ComposeTerminalFrame.clampScrollOffset(
            scrollRows, emulator.screen.activeTranscriptRows
        )
        drawIntoCanvas { drawCanvas ->
            renderComposeFrame(
                drawCanvas.nativeCanvas, emulator, topRow, paint, metrics, palette, enableLigatures
            )
        }
    }
}

/**
 * Font metrics cached per (typeface, size), mirroring the legacy [TerminalRenderer]
 * constructor so grid geometry matches the fallback view.
 */
private data class CanvasFontMetrics(
    val fontWidth: Float,
    val lineSpacing: Int,
    val ascent: Int,
    val lineSpacingAndAscent: Int,
    val asciiMeasures: FloatArray
)

/**
 * Measure monospace metrics into [paint] and snapshot them.
 *
 * @param paint The paint to configure (typeface, size) and measure with
 * @param typeface The typeface to measure
 * @param fontSize The text size in pixels
 * @return The snapshotted metrics
 */
private fun measureCanvasMetrics(paint: Paint, typeface: Typeface, fontSize: Float): CanvasFontMetrics {
    paint.typeface = typeface
    paint.textSize = fontSize
    val lineSpacing = ceil(paint.fontSpacing).toInt()
    val ascent = ceil(paint.ascent()).toInt()
    val fontWidth = paint.measureText("X")
    val asciiMeasures = FloatArray(127)
    val scratch = StringBuilder(" ")
    for (i in asciiMeasures.indices) {
        scratch.setCharAt(0, i.toChar())
        asciiMeasures[i] = paint.measureText(scratch, 0, 1)
    }
    return CanvasFontMetrics(fontWidth, lineSpacing, ascent, lineSpacing + ascent, asciiMeasures)
}

/**
 * Paint one emulator frame onto a native canvas, mirroring the legacy render loop
 * (reverse video, per-row runs, cursor rect, text run).
 *
 * Still deferred: text selection (TODO(spike): selection) and cursor blinking
 * (TODO(spike): blink — painted statically when visible).
 *
 * @param topRow Scroll offset owned by the hidden input view (same semantics as the
 * legacy `mTopRow`); rows paint from `topRow` to `topRow + mRows`
 */
private fun renderComposeFrame(
    canvas: android.graphics.Canvas,
    emulator: TerminalEmulator,
    topRow: Int,
    paint: Paint,
    metrics: CanvasFontMetrics,
    palette: TerminalPalette,
    enableLigatures: Boolean
) {
    val colors = emulator.mColors.mCurrentColors
    val reverseVideo = emulator.isReverseVideo
    if (reverseVideo) canvas.drawColor(colors[TextStyle.COLOR_INDEX_FOREGROUND])

    val rows = emulator.mRows
    val columns = emulator.mColumns
    val cursorCol = emulator.cursorCol
    val cursorRow = emulator.cursorRow
    val cursorVisible = emulator.shouldCursorBeVisible()
    val cursorStyle = emulator.cursorStyle
    val screen = emulator.screen
    val defaultBackground = colors[TextStyle.COLOR_INDEX_BACKGROUND]

    var heightOffset = metrics.lineSpacingAndAscent.toFloat()
    for (row in 0 until rows) {
        heightOffset += metrics.lineSpacing
        val externalRow = topRow + row
        val cursorX = if (externalRow == cursorRow && cursorVisible) cursorCol else -1
        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow))
        val runs = ComposeTerminalFrame.buildLineRuns(
            line, columns, cursorX, -1, -1, enableLigatures
        ) { codePoint ->
            val measured = if (codePoint < metrics.asciiMeasures.size) {
                metrics.asciiMeasures[codePoint]
            } else {
                paint.measureText(String(Character.toChars(codePoint)))
            }
            abs(measured / metrics.fontWidth - WcWidth.width(codePoint)) > 0.01f
        }
        for (run in runs) {
            drawComposeRun(
                canvas, line.mText, run, colors, defaultBackground, reverseVideo,
                cursorStyle, heightOffset, paint, metrics
            )
        }
    }
}

/**
 * Paint one [ComposeTerminalFrame.TextRun]: optional background, cursor rect and text.
 *
 * The run text slice is scaled onto its grid columns when its measured width mismatches
 * wcwidth (non-monospace glyphs), exactly like the legacy renderer scales mismatched runs.
 */
private fun drawComposeRun(
    canvas: android.graphics.Canvas,
    text: CharArray,
    run: ComposeTerminalFrame.TextRun,
    paletteColors: IntArray,
    defaultBackground: Int,
    emulatorReverseVideo: Boolean,
    cursorStyle: Int,
    y: Float,
    paint: Paint,
    metrics: CanvasFontMetrics
) {
    val resolved = ComposeTerminalFrame.resolveRunColors(
        run.style, paletteColors, defaultBackground, emulatorReverseVideo, run.inCursor, cursorStyle
    )

    var left = run.startColumn * metrics.fontWidth
    var right = left + run.columnWidth * metrics.fontWidth

    var savedMatrix = false
    // Clamp the slice defensively: the row buffer can be reshaped by the emulator between
    // frames, and measureText/drawTextRun throw on out-of-range slices (fatal in draw).
    val safeStart = run.startCharIndex.coerceIn(0, text.size)
    val safeCount = run.charCount.coerceAtLeast(0).coerceAtMost(text.size - safeStart)
    if (safeCount > 0) {
        val measured = paint.measureText(text, safeStart, safeCount)
        if (abs(measured / metrics.fontWidth - run.columnWidth) > 0.01f && measured > 0f) {
            canvas.save()
            canvas.scale(run.columnWidth * metrics.fontWidth / measured, 1f)
            left *= measured / (run.columnWidth * metrics.fontWidth)
            right *= measured / (run.columnWidth * metrics.fontWidth)
            savedMatrix = true
        }
    }

    if (resolved.drawBackground) {
        paint.color = resolved.backColor
        canvas.drawRect(left, y - metrics.lineSpacingAndAscent + metrics.ascent, right, y, paint)
    }

    if (resolved.cursorColor != 0) {
        paint.color = resolved.cursorColor
        val cursorHeight = (metrics.lineSpacingAndAscent - metrics.ascent).toFloat()
        val cursorBottom = y
        var cursorTop = y - cursorHeight
        var cursorLeft = left
        val cursorRight = right
        if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
            cursorTop = y - cursorHeight / 4f
        } else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
            cursorLeft = right - (right - left) / 4f
        }
        canvas.drawRect(cursorLeft, cursorTop, cursorRight, cursorBottom, paint)
    }

    if (resolved.drawText && safeCount > 0) {
        val effect = resolved.effect
        paint.isFakeBoldText = effect and
            (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        paint.isUnderlineText = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        paint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.35f else 0f
        paint.isStrikeThruText = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        paint.color = resolved.foreColor
        canvas.drawTextRun(
            text, safeStart, safeCount,
            safeStart, safeCount, left, y - metrics.lineSpacingAndAscent, false, paint
        )
    }

    if (savedMatrix) canvas.restore()
}

private const val LOG_TAG = "ComposeTerminalCanvas"
