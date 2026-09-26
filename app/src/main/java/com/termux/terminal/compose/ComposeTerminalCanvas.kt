package com.termux.terminal.compose

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
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
import kotlin.math.roundToInt
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
 * Beyond the earlier spike this canvas also owns, with full legacy parity:
 * - **Text selection** (long-press word select, per-row selection bounds feeding
 *   [ComposeTerminalFrame.buildLineRuns] and reverse-video painting, drag-to-scroll while
 *   selecting, shift selection up when new output scrolls the screen). The draggable handles
 *   and the Copy/Paste/More toolbar live in [ComposeTerminalSelectionOverlay], composed on
 *   top by [TermuxMainScreen], and share the [ComposeTerminalViewState] scroll/selection.
 * - **Pinch-zoom**: a two-pointer transform gesture steps the font size by ±2 (mirroring
 *   [ComposeTerminalViewClient.onScale]) through [onFontSizeStep] when the cumulative scale
 *   leaves `[0.9, 1.1]`.
 * - **Fling inertia**: a `Modifier.draggable` vertical gesture feeds an exponential decay of
 *   the scroll offset (transcript) or of a synthetic wheel value (mouse-tracking apps),
 *   mirroring the legacy `Scroller.fling` damping of 0.25.
 * - **onScreenUpdated parity**: new output snaps the scroll back to live (legacy
 *   `mTopRow = 0`) unless auto-scroll is disabled or text is being selected; selecting
 *   shifts the selection up with the scrolled rows. Grid sizing clamps to a minimum of
 *   4 columns and 4 rows like the legacy `updateSize()`.
 * - **Input parity**: soft-IME and hardware input re-show the cursor immediately via
 *   [ComposeTerminalViewState.blinkResetTick] (legacy `setCursorBlinkState(true)`), alt-buffer
 *   wheel arrows respect the emulator cursor/keypad application modes (DECCKM/DECKPAM),
 *   long-press honors the scale-gesture and client vetoes, and a tap right after starting a
 *   selection is ignored for 300 ms (legacy `TextSelectionCursorController.hide()` guard).
 *
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
 * @param metrics The glyph metrics snapshot (see [measureCanvasMetrics]); hoisted here so the
 * selection overlay from the same pane positions its handles on identical geometry
 * @param state The shared scroll/selection state, owned by the pane so the selection overlay
 * above this canvas can render handles and the toolbar from the same coordinates
 * @param isActivePane Whether this canvas belongs to the focused (active) pane of a split view
 * @param onActivatePane Callback when the canvas is tapped while it is not the active pane
 * @param onLongPressConsumed Client veto for the long-press gesture, mirroring the legacy
 * `mClient.onLongPress(event)`: when it returns true the canvas does not start a text
 * selection
 * @param onFontSizeStep Callback with a signed font-size step in pixels for pinch-zoom
 * @param hyperlinksEnabled Whether OSC 8 hyperlinks underline and open on tap
 * @param imagesEnabled Whether inline terminal images (OSC 1337 / kitty) are painted
 * @param modifier Modifier to apply to the canvas
 */
@Composable
internal fun ComposeTerminalCanvas(
    session: TerminalSession,
    fontSize: Float,
    typeface: Typeface?,
    enableLigatures: Boolean,
    palette: TerminalPalette,
    metrics: CanvasFontMetrics,
    state: ComposeTerminalViewState,
    isActivePane: Boolean = true,
    onActivatePane: (() -> Unit)? = null,
    onLongPressConsumed: () -> Boolean = { false },
    onFontSizeStep: (Float) -> Unit = {},
    onClientTap: (() -> Unit)? = null,
    hyperlinksEnabled: Boolean = true,
    imagesEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Repaint tick bumped on every emulator screen update (see ComposeTerminalSessionClient).
    // Read during composition so each tick recomposes and repaints this session only.
    var frameTick by remember(session) { mutableIntStateOf(0) }
    // Scroll-counter rows captured by the session client for this update (before the hidden
    // input view clears the counter). Drives the selection up-shift when new output scrolls
    // the screen while selecting.
    var frameScrollCount by remember(session) { mutableIntStateOf(0) }
    DisposableEffect(session) {
        val listener = TerminalViewRegistry.FrameListener { scrollCount ->
            frameTick++
            frameScrollCount = scrollCount
        }
        TerminalViewRegistry.addFrameListener(session, listener)
        onDispose { TerminalViewRegistry.removeFrameListener(session, listener) }
    }

    // The canvas and the selection overlay share this scroll offset (0 = following live
    // output, negative = scrolled back). Gestures land on this canvas, so the hidden input
    // view underneath stays at top row 0 and keeps following output internally.
    var dragRemainder by remember(session) { mutableFloatStateOf(0f) }

    // Cursor blinking is owned by this canvas: the hidden input view's blinker stays inert
    // at the default rate 0, so its blink state never toggles (legacy TerminalViewHost sets
    // the rate for the fallback path). The phase only ticks while this pane is active, and
    // input re-shows the cursor via blinkResetTick. Reading both states in the draw scope
    // repaints on every phase flip.
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
    // Input re-shows the cursor immediately (legacy setCursorBlinkState(true) from
    // inputCodePoint/handleKeyCode), without treating background output as input.
    LaunchedEffect(state.blinkResetTick) {
        if (blinkEnabled) blinkOn = true
    }

    // Leaving touch mode (hardware keyboard attached) dismisses an active selection, like
    // legacy TextSelectionCursorController.onTouchModeChanged → stopTextSelectionMode().
    val touchModeView = LocalView.current
    DisposableEffect(touchModeView, state) {
        val observer = touchModeView.viewTreeObserver
        val listener = ViewTreeObserver.OnTouchModeChangeListener { isInTouchMode ->
            if (!isInTouchMode) state.selection = null
        }
        observer.addOnTouchModeChangeListener(listener)
        onDispose {
            // The observer instance can be swapped on attach; re-resolve for removal.
            touchModeView.viewTreeObserver.removeOnTouchModeChangeListener(listener)
        }
    }
    val density = LocalDensity.current
    val scrollBarWidthPx = with(density) { 3.dp.toPx() }

    // New output while scrolled back: mirror legacy onScreenUpdated(). While selecting, shift
    // the selection up with the scrolled rows so it stays glued to its text, aborting at the
    // transcript end (pinning at the oldest row when auto-scroll is disabled). Without a
    // selection, snap the offset back to live unless auto-scroll is disabled. Output does not
    // re-show the cursor; input bumps blinkResetTick instead. State writes stay out of the
    // draw scope.
    LaunchedEffect(frameTick) {
        val emulator = session.emulator
        val transcript = emulator?.screen?.activeTranscriptRows ?: 0
        val selection = state.selection
        if (emulator != null) {
            // Output does not force the cursor visible (legacy setCursorBlinkState runs only
            // from inputCodePoint/handleKeyCode); the input host bumps blink via onUserKeyInput
            // wiring in the pane. Keep the phase here only when already on.
            if (selection != null) {
                val (shiftedScroll, shiftedSelection) =
                    ComposeTerminalFrame.shiftSelectionForNewOutput(
                        selection, state.scrollRows, frameScrollCount, transcript,
                        isAutoScrollDisabled = emulator.isAutoScrollDisabled()
                    )
                state.scrollRows = ComposeTerminalFrame.clampScrollOffset(shiftedScroll, transcript)
                state.selection = shiftedSelection
            } else {
                state.scrollRows = ComposeTerminalFrame.scrollOffsetForNewOutput(
                    state.scrollRows, frameScrollCount, transcript,
                    isAutoScrollDisabled = emulator.isAutoScrollDisabled()
                )
            }
        }
    }

    // The canvas itself is never focusable: taps forward focus plus the soft keyboard to
    // the hidden input view below (see HiddenTerminalInputHost).
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val resolvedTypeface = typeface ?: Typeface.MONOSPACE
    // Measure and paint at the integer size the hidden TerminalView receives via
    // setTextSize(int) so both grids stay identical if a fractional fontSize ever arrives.
    val paintFontSize = fontSize.toInt().toFloat()
    val paint = remember(resolvedTypeface, paintFontSize) {
        Paint().apply {
            isAntiAlias = true
            this.typeface = resolvedTypeface
            textSize = paintFontSize
        }
    }

    // Last laid-out size, used to derive the grid before first paint.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // A layout or font-size / glyph metrics change must re-derive the grid even when the
    // pixel size is unchanged, mirroring the legacy updateSize() on both onSizeChanged and
    // setTextSize(). Runs after the layout pass, so canvasSize is populated before first use.
    LaunchedEffect(canvasSize, metrics) {
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        // Grid sizing mirrors TerminalView.updateSize(), including the 4x4 minimum clamp.
        val (columns, rows) = ComposeTerminalFrame.gridSize(
            canvasSize.width, canvasSize.height,
            metrics.fontWidth, metrics.lineSpacing, metrics.lineSpacingAndAscent
        )
        try {
            val emulatorBefore = session.emulator
            val gridChanged = emulatorBefore == null ||
                emulatorBefore.mColumns != columns || emulatorBefore.mRows != rows
            session.updateSize(columns, rows, metrics.fontWidth.toInt(), metrics.lineSpacing)
            // Legacy updateSize() snaps mTopRow to 0 whenever the grid changes so the view
            // never stays scrolled into a reflowed transcript at a stale offset.
            if (gridChanged) state.scrollRows = 0
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "updateSize failed on metrics change", e)
        }
    }

    // Convert a point in canvas pixels to a 1-based terminal grid cell, mirroring the
    // legacy getColumnAndRow() used for mouse-tracking coordinates.
    fun columnAndRow(x: Float, y: Float): Pair<Int, Int> =
        ((x / metrics.fontWidth).toInt() + 1) to
            (((y - metrics.lineSpacingAndAscent) / metrics.lineSpacing).toInt() + 1)

    // Convert a point to a 0-based grid cell in external row coordinates (visible row plus the
    // scroll offset), used for long-press word selection like the legacy
    // getColumnAndRow(event, true).
    fun gridColumnAndRow(x: Float, y: Float): Pair<Int, Int> =
        (x / metrics.fontWidth).toInt() to
            (((y - metrics.lineSpacingAndAscent) / metrics.lineSpacing).toInt() + state.scrollRows)

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
                // arrow keys instead. Legacy doScroll() parity: rows > 0 moves up, and the
                // sequences respect the emulator cursor/keypad application modes (DECCKM /
                // DECKPAM) like TerminalView.handleKeyCode does.
                repeat(abs(rows)) {
                    session.write(
                        TerminalKeyHandler.getKeySequence(
                            if (rows > 0) "UP" else "DOWN",
                            cursorAppMode = emulator.isCursorKeysApplicationMode(),
                            keypadAppMode = emulator.isKeypadApplicationMode()
                        )
                    )
                }
            }
            else -> {
                // Legacy doScroll() parity: rows > 0 (swipe down / wheel up) scrolls back
                // toward older output, negative toward live (0).
                state.scrollRows = ComposeTerminalFrame.scrollByDrag(
                    state.scrollRows, rows, emulator.screen.activeTranscriptRows
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

    // Fling inertia mirroring the legacy onFling: damp the fling velocity by 0.25 and animate
    // either the transcript scroll offset (no mouse tracking) or a synthetic wheel position
    // sent to the app (mouse tracking active). The legacy view aborts a fling that is still
    // running and when the mouse-tracking state toggles mid-fling; both are mirrored.
    val flingAnim = remember(session) { Animatable(0f) }
    var flingRunning by remember(session) { mutableStateOf(false) }
    // Anchor for wheel events sent to mouse-tracking apps: legacy TerminalView anchors them
    // at the touch down position, so remember it at drag start.
    var dragAnchor by remember(session) { mutableStateOf(Offset.Zero) }
    var fingerScrolled by remember(session) { mutableStateOf(false) }
    val doFling: suspend (Float) -> Unit = fling@ { rawVelocity ->
        val emulator = session.emulator ?: return@fling
        if (flingRunning) return@fling
        val velocity = -rawVelocity * ComposeTerminalFrame.FLING_VELOCITY_SCALE
        if (velocity == 0f) return@fling
        val mouseTrackingAtStart = emulator.isMouseTrackingActive
        flingRunning = true
        try {
            if (mouseTrackingAtStart) {
                // Virtual wheel value bounded like the legacy Scroller.fling over mRows / 2;
                // every accumulated row goes through routeScroll so the app sees wheel events.
                // Negative travel (down-fling) must map to positive scroll-back rows (wheel-up),
                // so the remainder accumulates the negated travel like the legacy diff carry.
                val bounds = ComposeTerminalFrame.flingBounds(-emulator.mRows / 2, emulator.mRows / 2)
                flingAnim.snapTo(0f)
                var wheelRemainder = 0f
                var lastValue = 0f
                flingAnim.animateDecay(velocity, exponentialDecay()) {
                    // Early-return instead of Animatable.stop(): the decay block is not a
                    // suspend context. The decay keeps running inertly until it damps out,
                    // which matches stopping consumption of the fling's scroll.
                    if (emulator.isMouseTrackingActive != mouseTrackingAtStart) {
                        return@animateDecay
                    }
                    val value = this.value.coerceIn(bounds.first.toFloat(), bounds.last.toFloat())
                    wheelRemainder -= value - lastValue
                    lastValue = value
                    val rows = wheelRemainder.toInt()
                    if (rows != 0) {
                        routeScroll(rows, emulator, dragAnchor.x, dragAnchor.y)
                        wheelRemainder -= rows
                    }
                }
            } else {
                // Transcript mode: decay the scroll offset within [-transcript, 0], matching
                // the legacy Scroller.fling over mTopRow.
                val bounds = ComposeTerminalFrame.flingBounds(
                    -maxOf(emulator.screen.activeTranscriptRows, 0), 0
                )
                flingAnim.snapTo(state.scrollRows.toFloat())
                flingAnim.animateDecay(velocity, exponentialDecay()) {
                    // Early-return instead of Animatable.stop(): the decay block is not a
                    // suspend context (see above).
                    if (emulator.isMouseTrackingActive != mouseTrackingAtStart) {
                        return@animateDecay
                    }
                    state.scrollRows = this.value.roundToInt().coerceIn(bounds)
                }
            }
        } finally {
            flingRunning = false
        }
    }

    // Cumulative pinch scale, reset whenever a font-size step is applied (mirrors the legacy
    // mScaleFactor *= onScale() + client returning 1.0f after a step).
    var pinchScale by remember(session) { mutableFloatStateOf(1f) }

    // Whether two or more pointers are currently down. detectTapGestures does not cancel its
    // long-press when a second finger lands, so this mirrors the legacy
    // mGestureRecognizer.isInProgress() guard that skips starting a text selection mid-pinch.
    var isPinching by remember(session) { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            // Touch gesture arbitration: mouse events (SOURCE_MOUSE) are consumed by the raw
            // pointer block below so touch detectors never see them; touch drags feed the
            // draggable (scroll + fling), taps/long-presses the tap detector, and two-finger
            // pinches the transform detector (legacy GestureDetector + ScaleDetector coexist,
            // a two-finger drag both scrolls and zooms).
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
                            // Legacy onGenericMotionEvent(): wheel up (AXIS_VSCROLL > 0) views
                            // older output and reports wheel-up, three rows per notch.
                            val rows = if (raw.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f) 3 else -3
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
                                            // Middle click pastes the clipboard, like the legacy
                                            // view, which null-checks the clip and skips empty text
                                            // (TerminalEmulator.paste dereferences unconditionally).
                                            val clipText =
                                                ShareUtils.getTextStringFromClipboardIfSet(context, true)
                                            if (!clipText.isNullOrEmpty()) {
                                                session.emulator?.paste(clipText)
                                            }
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
            .pointerInput(session) {
                // Track when multiple pointers are down (a pinch). Never consumes: the tap
                // and transform detectors below keep their own arbitration, and the tap
                // long-press reads this to mirror the legacy scale-in-progress guard.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        isPinching = event.changes.size >= 2
                    }
                }
            }
            .pointerInput(session, state) {
                detectTapGestures(
                    onTap = { offset ->
                        if (state.selection != null) {
                            // Legacy onSingleTapUp stops the text selection mode on a tap, but
                            // the 300ms hide() guard blocks a tap right after the selection was
                            // started so it is not dismissed instantly (legacy
                            // TextSelectionCursorController.hide() parity). Toolbar actions and
                            // the back button clear it unconditionally.
                            if (SystemClock.uptimeMillis() - state.selectionStartedAt >= SelectionHideGuardMs) {
                                state.selection = null
                            }
                        } else {
                            // OSC 8: open the hyperlink under the finger before the normal
                            // tap path (parity with TerminalView.onSingleTapUp). Mouse-tracking
                            // apps keep the click; selection is handled above.
                            val emulator = session.emulator
                            if (hyperlinksEnabled && emulator != null &&
                                !emulator.isMouseTrackingActive
                            ) {
                                val (linkCol, linkRow) = gridColumnAndRow(offset.x, offset.y)
                                val uri = emulator.getHyperlinkUriAt(linkRow, linkCol)
                                if (TerminalEmulator.isAllowedHyperlinkUri(uri)) {
                                    ShareUtils.openUrl(context, uri)
                                    return@detectTapGestures
                                }
                            }
                            activateSession()
                            // Legacy onSingleTapUp contract: notify the client so any tap
                            // side effects (soft keyboard, etc.) still run. The canvas also
                            // focuses the hidden view above so focus order matches.
                            onClientTap?.invoke()
                            // Legacy onUp quick-tap parity: when mouse tracking is active a
                            // quick touch tap reports the left button press/release to the app.
                            if (emulator?.isMouseTrackingActive == true && !fingerScrolled) {
                                val (column, row) = columnAndRow(offset.x, offset.y)
                                emulator.sendMouseEvent(
                                    TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, true
                                )
                                emulator.sendMouseEvent(
                                    TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, false
                                )
                            }
                        }
                    },
                    onLongPress = { offset ->
                        // Legacy onLongPress starts the text selection mode (word-expanded)
                        // unless a scale gesture is in progress, the client consumed the
                        // event, or the mode is already active.
                        if (isPinching) return@detectTapGestures
                        if (onLongPressConsumed()) return@detectTapGestures
                        if (state.selection != null) return@detectTapGestures
                        if (!isActivePane) onActivatePane?.invoke()
                        val emulator = session.emulator ?: return@detectTapGestures
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val (column, row) = gridColumnAndRow(offset.x, offset.y)
                        state.selection =
                            ComposeTerminalFrame.selectWord(emulator.screen, column, row, emulator.mColumns)
                        state.selectionStartedAt = SystemClock.uptimeMillis()
                    }
                )
            }
            .pointerInput(session, state) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (state.selection != null || zoom == 1f) {
                        pinchScale = 1f
                        return@detectTransformGestures
                    }
                    pinchScale *= zoom
                    when {
                        pinchScale >= 1.1f -> {
                            onFontSizeStep(+2f)
                            pinchScale = 1f
                        }
                        pinchScale <= 0.9f -> {
                            onFontSizeStep(-2f)
                            pinchScale = 1f
                        }
                    }
                }
            }
            .draggable(
                state = remember(session, metrics) {
                    DraggableState { delta ->
                        val emulator = session.emulator
                        if (emulator != null) {
                            val (rows, remainder) = ComposeTerminalFrame.accumulateDragRows(
                                dragRemainder, delta, metrics.lineSpacing
                            )
                            dragRemainder = remainder
                            if (rows != 0) {
                                fingerScrolled = true
                                routeScroll(rows, emulator, dragAnchor.x, dragAnchor.y)
                            }
                        }
                    }
                },
                orientation = Orientation.Vertical,
                onDragStarted = {
                    dragRemainder = 0f
                    fingerScrolled = false
                    dragAnchor = it
                },
                onDragStopped = { velocity ->
                    dragRemainder = 0f
                    doFling(velocity)
                }
            )
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
            state.scrollRows, emulator.screen.activeTranscriptRows
        )
        // Cursor phase: null hands visibility decision back to the emulator (blink off);
        // when blinking, the phase decides but DECSET 25 (?25l) still wins so the cursor
        // never shows while disabled (legacy TerminalRenderer ANDs blink with
        // shouldCursorBeVisible). Read inside the draw scope to subscribe to phase flips.
        val cursorVisibleOverride =
            if (blinkEnabled) blinkOn && emulator.isCursorEnabled else null
        drawIntoCanvas { drawCanvas ->
            renderComposeFrame(
                drawCanvas.nativeCanvas, emulator, topRow, paint, metrics, palette, enableLigatures,
                cursorVisibleOverride, state.selection, hyperlinksEnabled, imagesEnabled
            )
        }
        // Vertical scrollbar (legacy setVerticalScrollBarEnabled + computeVerticalScroll*):
        // thumb over the full range when the transcript makes range > extent; only drawn
        // while scrolled back (parity: no bar during normal typing at live).
        val scrollRange = emulator.screen.activeRows
        val scrollExtent = emulator.mRows
        if (scrollRange > scrollExtent && topRow < 0) {
            // Legacy computeVerticalScrollOffset: activeRows + mTopRow - mRows.
            val scrollOffset = (scrollRange + topRow - scrollExtent)
                .coerceIn(0, scrollRange - scrollExtent)
            val viewHeight = canvasSize.height.toFloat()
            val thumbHeight = (scrollExtent.toFloat() / scrollRange * viewHeight)
                .coerceAtLeast(16f)
            val trackHeight = viewHeight - thumbHeight
            val thumbTop = trackHeight * scrollOffset / (scrollRange - scrollExtent)
            val barWidthPx = scrollBarWidthPx
            drawIntoCanvas { drawCanvas ->
                val c = drawCanvas.nativeCanvas
                val scrollPaint = Paint().apply {
                    color = android.graphics.Color.argb(115, 255, 255, 255)
                    isAntiAlias = true
                }
                val right = canvasSize.width.toFloat()
                c.drawRoundRect(
                    right - barWidthPx, thumbTop, right, thumbTop + thumbHeight,
                    barWidthPx / 2f, barWidthPx / 2f, scrollPaint
                )
            }
        }
    }
}

/**
 * Font metrics cached per (typeface, size), mirroring the legacy [TerminalRenderer]
 * constructor so grid geometry matches the fallback view.
 */
internal data class CanvasFontMetrics(
    val fontWidth: Float,
    val lineSpacing: Int,
    val ascent: Int,
    val lineSpacingAndAscent: Int,
    val asciiMeasures: FloatArray
)

/**
 * Measure monospace metrics into [paint] and snapshot them.
 *
 * The size is floored to an integer first so the canvas grid matches the legacy
 * [com.termux.view.TerminalView.setTextSize] path, which only accepts `int`.
 *
 * @param paint The paint to configure (typeface, size) and measure with
 * @param typeface The typeface to measure
 * @param fontSize The text size in pixels (may be fractional; floored before measure)
 * @return The snapshotted metrics
 */
internal fun measureCanvasMetrics(paint: Paint, typeface: Typeface, fontSize: Float): CanvasFontMetrics {
    paint.typeface = typeface
    paint.textSize = fontSize.toInt().toFloat()
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
 * (reverse video, per-row runs, cursor rect, text run, selection reverse video).
 * The frame starts with a full fill of the default background color so the canvas is
 * opaque: default-background cells are never painted per-run (the legacy renderer relied
 * on the view background color), and a transparent canvas would show the hidden input
 * view underneath as a static second copy of the text while the canvas scrolls.
 *
 * @param topRow Scroll offset owned by the canvas (same semantics as the legacy `mTopRow`);
 * rows paint from `topRow` to `topRow + mRows`
 * @param cursorVisibleOverride When null the emulator decides (blink off); otherwise it
 * overrides [TerminalEmulator.shouldCursorBeVisible] so the canvas blink phase toggles
 * the cursor here only
 * @param selection The active text selection in external row coordinates, or null. Selected
 * cells paint with swapped colors exactly like the legacy renderer.
 * @param hyperlinksEnabled When false, OSC 8 hyperlink runs do not force an underline
 * @param imagesEnabled When false, inline image placements are not painted
 */
private fun renderComposeFrame(
    canvas: android.graphics.Canvas,
    emulator: TerminalEmulator,
    topRow: Int,
    paint: Paint,
    metrics: CanvasFontMetrics,
    palette: TerminalPalette,
    enableLigatures: Boolean,
    cursorVisibleOverride: Boolean? = null,
    selection: ComposeTerminalFrame.TextSelection? = null,
    hyperlinksEnabled: Boolean = true,
    imagesEnabled: Boolean = true
) {
    val colors = emulator.mColors.mCurrentColors
    val reverseVideo = emulator.isReverseVideo
    canvas.drawColor(colors[TextStyle.COLOR_INDEX_BACKGROUND])
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
    // Per-row inheritance state for unicode-placeholder cells (reset per logical line).
    val placeholderState = com.termux.terminal.KittyPlaceholderDecoder.RowState()
    for (row in 0 until rows) {
        heightOffset += metrics.lineSpacing
        val externalRow = topRow + row
        val cursorX = if (externalRow == cursorRow && cursorVisible) cursorCol else -1
        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow))
        val (selX1, selX2) = ComposeTerminalFrame.selectionBoundsForRow(selection, externalRow, columns)
        val runs = ComposeTerminalFrame.buildLineRuns(
            line, columns, cursorX, selX1, selX2, enableLigatures
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
                cursorStyle, heightOffset, paint, metrics, hyperlinksEnabled
            )
        }
        // Images paint after text so previews sit above leftover cell glyphs.
        if (imagesEnabled) {
            drawComposeImages(
                canvas, emulator, screen, externalRow, heightOffset, metrics, paint,
                line, colors, defaultBackground, reverseVideo, selX1, selX2
            )
            // Unicode placeholders (kitty U+10EEEE): decode + paint after text so the
            // image covers the placeholder glyph; inheritance spans wrapped lines.
            if (!(externalRow > topRow && screen.getLineWrap(externalRow - 1))) placeholderState.reset()
            com.termux.terminal.KittyPlaceholderDecoder.collectRow(line, columns, placeholderState) { column, target ->
                drawComposePlaceholderCell(
                    canvas, emulator, column, target, heightOffset, metrics, paint,
                    line, colors, defaultBackground, reverseVideo, selX1, selX2
                )
            }
        }
    }
}

/**
  * Paint one unicode-placeholder cell as its grid slice of the virtual placement.
  * Silently skips cells whose id or grid position does not resolve — the plain
  * placeholder glyph drawn by the text run stays visible as fallback. Resolved
  * cells blank the cell with its effective background first (kitty spec:
  * transparent image regions show the cell background, never the glyph).
  *
  * @param canvas target canvas
  * @param emulator the emulator (virtual placements + image registry)
  * @param column screen column of the cell
  * @param target decoded image id + grid position
  * @param yBottom bottom of the row (baseline convention shared with text runs)
  * @param metrics font metrics for cell geometry
  * @param paint paint used to blank the cell background
  * @param line the row being painted (cell style for blanking)
  * @param paletteColors the emulator indexed colors
  * @param defaultBackground the default background color (ARGB)
  * @param reverseVideo whether the emulator is in reverse-video mode
  * @param selX1 selection left bound for the row (or -1)
  * @param selX2 selection right bound for the row (or -1)
  */
private fun drawComposePlaceholderCell(
    canvas: android.graphics.Canvas,
    emulator: TerminalEmulator,
    column: Int,
    target: com.termux.terminal.KittyPlaceholderDecoder.Target,
    yBottom: Float,
    metrics: CanvasFontMetrics,
    paint: Paint,
    line: com.termux.terminal.TerminalRow,
    paletteColors: IntArray,
    defaultBackground: Int,
    reverseVideo: Boolean,
    selX1: Int,
    selX2: Int
) {
    val vp = emulator.resolveVirtualPlacement(target.imageId) ?: return
    if (target.gridRow < 0 || target.gridRow >= vp.rows ||
        target.gridCol < 0 || target.gridCol >= vp.cols
    ) return
    val data = emulator.getImageData(vp.registryId) ?: return
    val bitmap = composeBitmapFor(data) ?: return
    val bmpW = bitmap.width
    val bmpH = bitmap.height
    val top = yBottom - metrics.lineSpacing
    val bottom = yBottom
    val srcTop = target.gridRow.toLong() * bmpH / vp.rows
    val srcBottom = (target.gridRow + 1).toLong() * bmpH / vp.rows
    val srcLeft = target.gridCol.toLong() * bmpW / vp.cols
    val srcRight = (target.gridCol + 1).toLong() * bmpW / vp.cols
    val left = column * metrics.fontWidth
    val right = left + metrics.fontWidth
    // Blank the cell with its effective background before painting the slice: the
    // placeholder glyph is drawn by the text run underneath and transparent image
    // pixels must show the cell background, never the glyph.
    paint.color = cellBlankColor(
        line, column, paletteColors, defaultBackground, reverseVideo, selX1, selX2
    )
    canvas.drawRect(left, top, right, bottom, paint)
    val src = android.graphics.Rect(
        minOf(srcLeft.toInt(), bmpW - 1), minOf(srcTop.toInt(), bmpH - 1),
        maxOf(srcLeft.toInt() + 1, minOf(srcRight.toInt(), bmpW)),
        maxOf(srcTop.toInt() + 1, minOf(srcBottom.toInt(), bmpH))
    )
    val dst = android.graphics.RectF(left, top, right, bottom)
    canvas.drawBitmap(bitmap, src, dst, null)
}

/**
 * Resolve a cell's effective background for image blanking: mirrors
 * [ComposeTerminalFrame.resolveRunColors] (palette lookup, bold-bright foreground,
 * reverse-video swap) including the cell's selection state.
 *
 * @param line the row being painted
 * @param column screen column of the cell
 * @param paletteColors the emulator indexed colors
 * @param defaultBackground the default background color (ARGB)
 * @param reverseVideo whether the emulator is in reverse-video mode
 * @param selX1 selection left bound for the row (or -1)
 * @param selX2 selection right bound for the row (or -1)
 * @return the resolved background color as ARGB
 */
private fun cellBlankColor(
    line: com.termux.terminal.TerminalRow,
    column: Int,
    paletteColors: IntArray,
    defaultBackground: Int,
    reverseVideo: Boolean,
    selX1: Int,
    selX2: Int
): Int = ComposeTerminalFrame.resolveRunColors(
    line.getStyle(column), paletteColors, defaultBackground, reverseVideo,
    inSelection = column >= selX1 && column <= selX2
).backColor

/**
 * Paint every inline image placement intersecting [externalRow], mirroring the legacy
 * [com.termux.view.TerminalRenderer] image strip logic. Covered cells are blanked with
 * their effective background first (kitty spec: transparent image regions show the cell
 * background, never the leftover glyph).
 *
 * @param canvas target canvas
 * @param emulator the emulator holding the image registry
 * @param screen the buffer being drawn
 * @param externalRow external (transcript-aware) row
 * @param yBottom bottom of the row (same baseline convention as text runs)
 * @param metrics font metrics for cell geometry
 * @param paint paint used to blank the cell backgrounds
 * @param line the row being painted (per-cell styles for blanking)
 * @param paletteColors the emulator indexed colors
 * @param defaultBackground the default background color (ARGB)
 * @param reverseVideo whether the emulator is in reverse-video mode
 * @param selX1 selection left bound for the row (or -1)
 * @param selX2 selection right bound for the row (or -1)
 */
private fun drawComposeImages(
    canvas: android.graphics.Canvas,
    emulator: TerminalEmulator,
    screen: com.termux.terminal.TerminalBuffer,
    externalRow: Int,
    yBottom: Float,
    metrics: CanvasFontMetrics,
    paint: Paint,
    line: com.termux.terminal.TerminalRow,
    paletteColors: IntArray,
    defaultBackground: Int,
    reverseVideo: Boolean,
    selX1: Int,
    selX2: Int
) {
    val top = yBottom - metrics.lineSpacing
    val bottom = yBottom
    var col = 0
    val columns = emulator.mColumns
    while (col < columns) {
        val imageId = screen.getImageAt(externalRow, col)
        if (imageId == 0) {
            col++
            continue
        }
        var spanEnd = col + 1
        while (spanEnd < columns && screen.getImageAt(externalRow, spanEnd) == imageId) spanEnd++
        val data = emulator.getImageData(imageId)
        if (data != null && data.intersectsRow(externalRow)) {
            drawComposeImageStrip(
                canvas, data, externalRow, col, spanEnd - col, top, bottom, metrics,
                paint, line, paletteColors, defaultBackground, reverseVideo, selX1, selX2
            )
        }
        col = spanEnd
    }
}

/**
 * Cached decode bound to the [com.termux.terminal.TerminalImageData] instance it was
 * decoded from: the registry can re-transmit under the same id (payload replaced with a
 * new object) — the identity check makes the stale bitmap miss instead of showing old pixels.
 */
private class ComposeCachedImageBitmap(
    val source: com.termux.terminal.TerminalImageData,
    val bitmap: android.graphics.Bitmap
)

private val composeImageBitmaps = android.util.LruCache<Int, ComposeCachedImageBitmap>(16)

/**
 * Draw one horizontal strip of an image placement. Every covered cell is blanked
 * with its own effective background first (kitty spec: transparent image regions
 * show the cell background, never the leftover glyph underneath).
 *
 * @param canvas target canvas
 * @param data registry entry
 * @param externalRow the row being painted
 * @param startColumn first screen column of the strip
 * @param widthCells strip width in cells
 * @param top strip top in canvas Y
 * @param bottom strip bottom in canvas Y
 * @param metrics font metrics for cell geometry
 * @param paint paint used to blank the cell backgrounds
 * @param line the row being painted (per-cell styles for blanking)
 * @param paletteColors the emulator indexed colors
 * @param defaultBackground the default background color (ARGB)
 * @param reverseVideo whether the emulator is in reverse-video mode
 * @param selX1 selection left bound for the row (or -1)
 * @param selX2 selection right bound for the row (or -1)
 */
private fun drawComposeImageStrip(
    canvas: android.graphics.Canvas,
    data: com.termux.terminal.TerminalImageData,
    externalRow: Int,
    startColumn: Int,
    widthCells: Int,
    top: Float,
    bottom: Float,
    metrics: CanvasFontMetrics,
    paint: Paint,
    line: com.termux.terminal.TerminalRow,
    paletteColors: IntArray,
    defaultBackground: Int,
    reverseVideo: Boolean,
    selX1: Int,
    selX2: Int
) {
    val bitmap = composeBitmapFor(data) ?: return
    val bmpW = bitmap.width
    val bmpH = bitmap.height
    val localRow = externalRow - data.startRow
    if (localRow < 0 || localRow >= data.cellsH) return
    val srcTop = (localRow.toLong() * bmpH / data.cellsH).toInt()
    val srcBottom = ((localRow + 1).toLong() * bmpH / data.cellsH).toInt()
    val srcH = maxOf(1, srcBottom - srcTop)
    val localCol = maxOf(0, startColumn - data.startCol)
    val srcLeft = (localCol.toLong() * bmpW / data.cellsW).toInt()
    val srcRight = ((localCol + widthCells).toLong() * bmpW / data.cellsW).toInt()
    val left = startColumn * metrics.fontWidth
    val right = left + widthCells * metrics.fontWidth
    // Blank every covered cell with its own effective background before painting:
    // text runs (filename text etc.) are drawn underneath and transparent image
    // pixels must show the cell background, never the leftover glyph.
    for (c in startColumn until startColumn + widthCells) {
        paint.color = cellBlankColor(
            line, c, paletteColors, defaultBackground, reverseVideo, selX1, selX2
        )
        val cellLeft = c * metrics.fontWidth
        canvas.drawRect(cellLeft, top, cellLeft + metrics.fontWidth, bottom, paint)
    }
    val src = android.graphics.Rect(
        minOf(srcLeft, bmpW - 1), srcTop,
        maxOf(srcLeft + 1, minOf(srcRight, bmpW)), minOf(bmpH, srcTop + srcH)
    )
    val dst = android.graphics.RectF(left, top, right, bottom)
    canvas.drawBitmap(bitmap, src, dst, null)
}

/**
 * Decode (or fetch from the identity-checked cache) the bitmap backing [data].
 * Shared by the strip painter and the unicode-placeholder painter.
 *
 * @param data the registry image entry
 * @return the bitmap, or null when the payload is empty or decoding failed
 */
private fun composeBitmapFor(
    data: com.termux.terminal.TerminalImageData
): android.graphics.Bitmap? {
    val encoded = data.encoded
    if (encoded == null || encoded.isEmpty()) return null
    var bitmap = composeImageBitmaps.get(data.id)
        ?.takeIf { it.source === data && !it.bitmap.isRecycled }
        ?.bitmap
    if (bitmap == null || bitmap.isRecycled) {
        bitmap = try {
            when (data.pixelFormat) {
                com.termux.terminal.TerminalImageData.FORMAT_RGB_24,
                com.termux.terminal.TerminalImageData.FORMAT_RGBA_32 -> {
                    val argb = data.decodeRawArgb()
                    if (argb == null || data.pixelWidth <= 0 || data.pixelHeight <= 0) null
                    else android.graphics.Bitmap.createBitmap(
                        argb, data.pixelWidth, data.pixelHeight, android.graphics.Bitmap.Config.ARGB_8888
                    )
                }
                else -> android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        composeImageBitmaps.put(data.id, ComposeCachedImageBitmap(data, bitmap))
    }
    return bitmap
}

/**
 * Paint one [ComposeTerminalFrame.TextRun]: optional background, cursor rect and text.
 *
 * The run text slice is scaled onto its grid columns when its measured width mismatches
 * wcwidth (non-monospace glyphs), exactly like the legacy renderer scales mismatched runs.
 *
 * @param hyperlinksEnabled When the run carries an OSC 8 hyperlink and this is true, force
 * an underline (legacy [com.termux.view.TerminalRenderer.drawTextRun] parity)
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
    metrics: CanvasFontMetrics,
    hyperlinksEnabled: Boolean = true
) {
    val resolved = ComposeTerminalFrame.resolveRunColors(
        run.style, paletteColors, defaultBackground, emulatorReverseVideo,
        run.inSelection, run.inCursor, cursorStyle
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
        val isHyperlink = hyperlinksEnabled && run.hyperlinkIndex != 0
        paint.isUnderlineText =
            effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0 || isHyperlink
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

/**
 * Minimum time in milliseconds a text selection must live before a canvas tap dismisses it,
 * mirroring the legacy `TextSelectionCursorController.hide()` guard against cancelling a
 * selection right after it was started.
 */
private const val SelectionHideGuardMs = 300L
