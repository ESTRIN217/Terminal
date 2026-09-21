package com.termux.terminal.compose

import android.graphics.Paint
import android.graphics.Typeface
import android.view.InputDevice
import android.view.MotionEvent
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.terminal.bridge.TerminalKeyHandler
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * Experimental native terminal canvas (Fase 3.6): paints a [TerminalSession] with a
 * Compose [Canvas] instead of the legacy {@link com.termux.view.TerminalView}.
 *
 * Key input is delegated to [HiddenTerminalInputHost], a full-size non-drawing view composed
 * below this canvas that owns the IME pipeline, hardware keys and focus. Tapping the canvas
 * focuses that view and shows the soft keyboard. The scroll offset, however, is owned here
 * (gestures land on this canvas, so the hidden view stays at top row 0): dragging moves a
 * row offset clamped to the transcript, and new output keeps following only while the
 * offset is 0. The canvas also owns cursor blinking (the hidden view's blinker stays inert at
 * the default rate 0) and physical-mouse input (wheel, buttons and clipboard paste), fed
 * from the raw [MotionEvent] exposed on Compose [androidx.compose.ui.input.pointer.PointerEvent].
 *
 * Still deferred: text selection, pinch-zoom and fling inertia.
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

    // Cursor blinking is owned by this canvas: the hidden input view's blinker stays inert
    // at the default rate 0, so its blink state never toggles. The rate is the same property
    // the legacy TerminalView reads, and the phase only ticks while this pane is active.
    // Reading both states in the draw scope repaints on every phase flip.
    val blinkRate = remember { TermuxAppSharedProperties.getProperties()?.getTerminalCursorBlinkRate() ?: 0 }
    val blinkEnabled = isActivePane && ComposeTerminalFrame.isValidCursorBlinkRate(blinkRate)
    var blinkOn by remember(session, blinkEnabled) { mutableStateOf(true) }
    LaunchedEffect(session, blinkEnabled, blinkRate) {
        if (!blinkEnabled) {
            blinkOn = true
            return@LaunchedEffect
        }
        while (true) {
            delay(blinkRate.toLong())
            blinkOn = !blinkOn
        }
    }

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

    // Convert a point in canvas pixels to a 1-based terminal grid cell, mirroring the
    // legacy getColumnAndRow() used for mouse-tracking coordinates.
    fun columnAndRow(x: Float, y: Float): Pair<Int, Int> =
        ((x / metrics.fontWidth).toInt() + 1) to
            (((y - metrics.lineSpacingAndAscent) / metrics.lineSpacing).toInt() + 1)

    // Shared scroll router for finger drags and the mouse wheel, mirroring the legacy
    // doScroll(): raw signed rows go to mouse-tracking apps, arrow keys to the alt screen,
    // and the canvas-owned transcript offset otherwise.
    fun routeScroll(rows: Int, emulator: TerminalEmulator, anchorX: Float, anchorY: Float) {
        if (rows == 0) return
        when {
            emulator.isMouseTrackingActive -> {
                // Mouse-tracking apps (opencode TUI, vim mouse mode, ...) consume wheel
                // events instead of the transcript or the alt screen. Mirror legacy
                // doScroll(): rows > 0 reports wheel-up (a swipe down).
                val (column, row) = columnAndRow(anchorX, anchorY)
                val button = if (rows > 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                repeat(abs(rows)) {
                    emulator.sendMouseEvent(button, column, row, true)
                }
            }
            emulator.isAlternateBufferActive -> {
                // Full-screen apps (vim, less) have no transcript: drive the cursor with
                // arrow keys instead. Legacy doScroll() parity: rows > 0 moves up.
                repeat(abs(rows)) {
                    session.write(
                        TerminalKeyHandler.getKeySequence(if (rows > 0) "UP" else "DOWN")
                    )
                }
            }
            else -> {
                // Legacy doScroll() parity: rows > 0 (swipe down / wheel up) scrolls back
                // toward older output, negative toward live (0).
                scrollRows = ComposeTerminalFrame.scrollByDrag(
                    scrollRows, rows, emulator.screen.activeTranscriptRows
                )
            }
        }
    }

    // Tap or a physical-mouse click with no drag: promote a secondary split pane first,
    // then hand focus and the soft keyboard to the hidden input view owning this session.
    fun activateSession() {
        if (!isActivePane) onActivatePane?.invoke()
        val inputView = TerminalViewRegistry.getViewForSession(session)
        if (inputView != null) {
            inputView.requestFocus()
            KeyboardUtils.showSoftKeyboard(context, inputView)
        } else {
            Logger.logWarn(LOG_TAG, "Tap with no hidden input view for session")
        }
    }

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
            .pointerInput(session, metrics) {
                // Physical mouse (SOURCE_MOUSE). Mirror the legacy TerminalView mouse paths
                // (doScroll for the wheel, sendMouseEvent for buttons, clipboard paste for the
                // middle button). All mouse events are consumed here so the touch tap/drag
                // detectors below never see them and finger vs mouse gestures stay disjoint.
                awaitPointerEventScope {
                    var mouseDown = false
                    var mouseLeftDown = false
                    var mouseDragged = false
                    var mouseRemainder = 0f
                    var mouseLastY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val raw = event.motionEvent ?: continue
                        if (!raw.isFromSource(InputDevice.SOURCE_MOUSE)) continue
                        event.changes.forEach { it.consume() }
                        val emulator = session.emulator ?: continue
                        if (event.type == PointerEventType.Scroll) {
                            // Legacy doScroll(): wheel up (AXIS_VSCROLL > 0) reports wheel-up /
                            // DPAD_UP / scroll back toward older output, three rows per notch.
                            val rows = if (raw.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f) -3 else 3
                            routeScroll(rows, emulator, raw.x, raw.y)
                        } else {
                            val (column, row) = columnAndRow(raw.x, raw.y)
                            when (event.type) {
                                PointerEventType.Press -> {
                                    mouseDown = true
                                    mouseDragged = false
                                    mouseRemainder = 0f
                                    mouseLastY = raw.y
                                    val buttons = event.buttons
                                    when {
                                        buttons.isTertiaryPressed -> {
                                            // Middle click pastes the clipboard, like the legacy view.
                                            session.emulator?.paste(
                                                ShareUtils.getTextStringFromClipboardIfSet(context, true)
                                            )
                                            mouseDown = false
                                        }
                                        buttons.isSecondaryPressed -> {
                                            // Right click opens the legacy context menu; out of scope
                                            // here (still consumed so nothing else acts on it).
                                            mouseDown = false
                                        }
                                        buttons.isPrimaryPressed -> {
                                            mouseLeftDown = true
                                            if (emulator.isMouseTrackingActive) {
                                                emulator.sendMouseEvent(
                                                    TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, true
                                                )
                                            }
                                        }
                                        else -> mouseDown = false
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (!mouseDown) continue
                                    // Hover moves (no button) are skipped; only real drags act.
                                    val primary = event.buttons.isPrimaryPressed
                                    if (emulator.isMouseTrackingActive && primary) {
                                        emulator.sendMouseEvent(
                                            TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, column, row, true
                                        )
                                    } else if (!emulator.isMouseTrackingActive && primary) {
                                        // No mouse tracking: a drag scrolls the transcript like a
                                        // finger, accumulating sub-row remainders across events.
                                        val (rows, remainder) = ComposeTerminalFrame.accumulateDragRows(
                                            mouseRemainder, raw.y - mouseLastY, metrics.lineSpacing
                                        )
                                        mouseRemainder = remainder
                                        mouseLastY = raw.y
                                        if (rows != 0) {
                                            mouseDragged = true
                                            routeScroll(rows, emulator, raw.x, raw.y)
                                        }
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (mouseDown) {
                                        if (mouseLeftDown) {
                                            if (emulator.isMouseTrackingActive) {
                                                emulator.sendMouseEvent(
                                                    TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, false
                                                )
                                            }
                                            if (!mouseDragged) activateSession()
                                        }
                                        mouseDown = false
                                        mouseLeftDown = false
                                    }
                                }
                                else -> mouseDown = false
                            }
                        }
                    }
                }
            }
            .pointerInput(session, isActivePane) {
                detectTapGestures(onTap = { activateSession() })
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
                                routeScroll(rows, emulator, dragAnchor.x, dragAnchor.y)
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
        // Cursor phase: null hands visibility decision back to the emulator (blink off);
        // when blinking, the phase alone decides so the cursor toggles here only.
        // Read inside the draw scope subscribes the canvas to phase flips.
        val cursorVisibleOverride = if (blinkEnabled) blinkOn else null
        drawIntoCanvas { drawCanvas ->
            renderComposeFrame(
                drawCanvas.nativeCanvas, emulator, topRow, paint, metrics, palette, enableLigatures,
                cursorVisibleOverride
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
 * Still deferred: text selection (TODO(spike): selection).
 *
 * @param topRow Scroll offset owned by the hidden input view (same semantics as the
 * legacy `mTopRow`); rows paint from `topRow` to `topRow + mRows`
 * @param cursorVisibleOverride When null the emulator decides (blink off); otherwise it
 * overrides [TerminalEmulator.shouldCursorBeVisible] so the canvas blink phase toggles
 * the cursor here only
 */
private fun renderComposeFrame(
    canvas: android.graphics.Canvas,
    emulator: TerminalEmulator,
    topRow: Int,
    paint: Paint,
    metrics: CanvasFontMetrics,
    palette: TerminalPalette,
    enableLigatures: Boolean,
    cursorVisibleOverride: Boolean? = null
) {
    val colors = emulator.mColors.mCurrentColors
    val reverseVideo = emulator.isReverseVideo
    if (reverseVideo) canvas.drawColor(colors[TextStyle.COLOR_INDEX_FOREGROUND])

    val rows = emulator.mRows
    val columns = emulator.mColumns
    val cursorCol = emulator.cursorCol
    val cursorRow = emulator.cursorRow
    val cursorVisible = cursorVisibleOverride ?: emulator.shouldCursorBeVisible()
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
