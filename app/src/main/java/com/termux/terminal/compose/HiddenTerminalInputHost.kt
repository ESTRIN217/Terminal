package com.termux.terminal.compose

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Hidden input delegate for [ComposeTerminalCanvas].
 *
 * The Android IME requires a [View] ([TerminalView.onCreateInputConnection], hardware
 * `onKeyDown`, `KeyCharacterMap`, combining accents), which a pure Compose `Canvas` cannot
 * provide — and [TerminalView] is final, so it cannot be subclassed into a non-sizing stub.
 * Instead this host composes a full-size [TerminalView] with `willNotDraw` enabled and
 * alpha 0 **below** the opaque canvas: it lays out (so [TerminalSession.updateSize] runs with the
 * real geometry), takes focus and owns the IME/key pipeline, but never paints.
 *
 * It registers in [TerminalViewRegistry] exactly like [TerminalViewHost], so the active view,
 * clipboard, context menu, `onTextChanged → onScreenUpdated` and `showSoftKeyboard` keep
 * working unchanged. Scroll ownership (`mTopRow`) also stays in the view; the canvas reads
 * it back per frame. Taps on the canvas forward focus plus the soft keyboard here.
 *
 * @param session The terminal session to attach to the hidden view
 * @param fontSize Font size in density-independent pixels
 * @param typeface The [Typeface] to use for the terminal text, or null for the default
 * @param enableLigatures Whether OpenType ligature shaping is enabled
 * @param viewClient The [TerminalViewClient] implementation for view callbacks
 * @param palette Colors applied to the emulator and the view; reapplied when it changes, or when
 * the session emulator becomes available later (see [TerminalViewRegistry.reapplyPendingPalette])
 * @param isActivePane Whether this view belongs to the focused (active) pane of a split view
 * @param onActivatePane Callback when the view gains focus while it is not the active pane
 * @param onUserKeyInput Invoked before any hardware key down reaches the session write path,
 * mirroring legacy `TerminalView.onKeyDown` which stops an active text selection mode first.
 * The pane uses it to clear its own selection state (the hidden view holds no selection of
 * its own). KEYCODE_BACK is excluded: the Compose `BackHandler` and the view client's
 * `onKeyDown` own that path with the same legacy parity (`TerminalView.onKeyPreIme`).
 * Soft-IME text input clears the pane selection through
 * [TerminalViewClient.onCodePoint] → [TerminalViewRegistry.dismissActivePaneSelection],
 * mirroring legacy `sendTextToTerminal()` → `stopTextSelectionMode()`.
 * @param modifier Modifier to apply to the composable
 */
@Composable
fun HiddenTerminalInputHost(
    session: TerminalSession,
    fontSize: Float,
    typeface: Typeface?,
    enableLigatures: Boolean,
    viewClient: TerminalViewClient,
    palette: TerminalPalette,
    isActivePane: Boolean = true,
    onActivatePane: (() -> Unit)? = null,
    onUserKeyInput: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var appliedSession by remember { mutableStateOf<TerminalSession?>(null) }
    var appliedFontSize by remember { mutableFloatStateOf(0f) }
    var appliedTypeface by remember { mutableStateOf<Typeface?>(null) }
    var appliedLigatures by remember { mutableStateOf(true) }
    var appliedPalette by remember { mutableStateOf<TerminalPalette?>(null) }

    val focusListener = remember(session, isActivePane, onActivatePane) {
        View.OnFocusChangeListener { view, hasFocus ->
            // Same promotion rule as TerminalViewHost: only the gain event counts, so a
            // role swap cannot bounce the focus back right after losing it.
            if (hasFocus && !isActivePane) view.post { onActivatePane?.invoke() }
        }
    }

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                // Never paint: the Compose canvas above us is the only renderer. Layout,
                // focus, InputConnection and key dispatch keep working normally.
                setWillNotDraw(true)
                alpha = 0f
                isFocusableInTouchMode = true
                (context as? ComponentActivity)?.registerForContextMenu(this)
                setTerminalViewClient(viewClient)
                setTextSize(fontSize.toInt())
                appliedFontSize = fontSize
                val resolvedTypeface = typeface ?: android.graphics.Typeface.MONOSPACE
                setTypeface(resolvedTypeface)
                appliedTypeface = resolvedTypeface
                setLigaturesEnabled(enableLigatures)
                appliedLigatures = enableLigatures
                attachSession(session)
                appliedSession = session
                // Same pending-palette dance as TerminalViewHost: the emulator usually does
                // not exist until layout assigns a size.
                if (TerminalViewRegistry.applyPalette(this, session, palette)) {
                    appliedPalette = palette
                    TerminalViewRegistry.setPendingPalette(this, null)
                } else {
                    appliedPalette = null
                    TerminalViewRegistry.setPendingPalette(this, palette)
                }
                post { if (isActivePane) requestFocus() }
                setOnFocusChangeListener(focusListener)
                TerminalViewRegistry.registerView(session, this, isActivePane)
                terminalView = this
            }
        },
        modifier = modifier,
        update = { view ->
            if (appliedSession !== session) {
                view.attachSession(session)
                appliedSession = session
                appliedPalette = null
                TerminalViewRegistry.setPendingPalette(view, null)
            }
            if (appliedFontSize != fontSize) {
                view.setTextSize(fontSize.toInt())
                appliedFontSize = fontSize
            }
            val resolvedTypeface = typeface ?: android.graphics.Typeface.MONOSPACE
            if (appliedTypeface !== resolvedTypeface) {
                view.setTypeface(resolvedTypeface)
                appliedTypeface = resolvedTypeface
            }
            if (appliedLigatures != enableLigatures) {
                view.setLigaturesEnabled(enableLigatures)
                appliedLigatures = enableLigatures
            }
            if (appliedPalette != palette) {
                if (TerminalViewRegistry.applyPalette(view, session, palette)) {
                    appliedPalette = palette
                    TerminalViewRegistry.setPendingPalette(view, null)
                } else {
                    appliedPalette = null
                    TerminalViewRegistry.setPendingPalette(view, palette)
                }
            }
            view.setOnFocusChangeListener(focusListener)
            // Hardware key down observed before TerminalView.onKeyDown writes it to the
            // session (View.dispatchKeyEvent runs the OnKeyListener first). Returning false
            // keeps the normal pipeline untouched; BACK is left to the pane BackHandler so a
            // selection-dismiss cannot swallow the back event.
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                    onUserKeyInput()
                }
                false
            }
            if (isActivePane && !view.hasFocus() && view.isAttachedToWindow) {
                view.requestFocus()
            }
            TerminalViewRegistry.registerView(session, view, isActivePane)
        }
    )

    DisposableEffect(session) {
        onDispose {
            terminalView?.let { view ->
                TerminalViewRegistry.unregisterView(session, view)
            }
        }
    }
}
