package com.termux.terminal.compose

import android.graphics.Typeface
import android.view.View
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
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Composable that hosts the native [TerminalView] inside a Compose hierarchy via
 * [AndroidView].
 *
 * The terminal emulation and rendering pipeline (JNI pty -> TerminalEmulator ->
 * TerminalRenderer) is reused untouched; Compose only provides the surrounding UI.
 *
 * Note that the renderer does not paint cells carrying the default background color, so the
 * palette background is also set as the view background color.
 *
 * When [isActivePane] is false (secondary pane of a split view) the view is registered in
 * [TerminalViewRegistry] but does not take focus, so keyboard input, extra keys and the
 * more menu keep targeting the focused pane. Gaining Android focus while the pane is not
 * active triggers [onActivatePane] so the pane can be promoted to the focused one.
 *
 * @param session The terminal session to attach to the view
 * @param fontSize Font size in density-independent pixels
 * @param typeface The [Typeface] to use for the terminal text, or null for the default
 * @param enableLigatures Whether OpenType ligature shaping is enabled
 * @param viewClient The [TerminalViewClient] implementation for view callbacks
 * @param palette Colors applied to the emulator and the view; reapplied when it changes, or when
 * the session emulator becomes available later (see [TerminalViewRegistry.reapplyPendingPalette])
 * @param isActivePane Whether this view belongs to the focused (active) pane of a split view
 * @param onActivatePane Callback when the view gains focus while it is not the active pane
 * @param hyperlinksEnabled Whether OSC 8 hyperlinks underline and open on tap
 * @param imagesEnabled Whether inline terminal images are painted
 * @param modifier Modifier to apply to the composable
 */
@Composable
fun TerminalViewHost(
    session: TerminalSession,
    fontSize: Float,
    typeface: Typeface?,
    enableLigatures: Boolean,
    viewClient: TerminalViewClient,
    palette: TerminalPalette,
    isActivePane: Boolean = true,
    onActivatePane: (() -> Unit)? = null,
    hyperlinksEnabled: Boolean = true,
    imagesEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var appliedSession by remember { mutableStateOf<TerminalSession?>(null) }
    var appliedFontSize by remember { mutableFloatStateOf(0f) }
    var appliedTypeface by remember { mutableStateOf<Typeface?>(null) }
    var appliedLigatures by remember { mutableStateOf(true) }
    var appliedHyperlinks by remember { mutableStateOf(true) }
    var appliedImages by remember { mutableStateOf(true) }
    var appliedPalette by remember { mutableStateOf<TerminalPalette?>(null) }

    val focusListener = remember(session, isActivePane, onActivatePane) {
        View.OnFocusChangeListener { view, hasFocus ->
            // A secondary pane that gains Android focus (user tapped it) requests to become
            // the active pane through the focus change callback. Only the gain event counts:
            // firing on focus loss too would re-activate the pane right after a role swap
            // promoted a different pane, making both panes (and the tab indicator) bounce.
            if (hasFocus && !isActivePane) view.post { onActivatePane?.invoke() }
        }
    }

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                // Match the android:focusableInTouchMode="true" historically set on the
                // legacy activity_termux.xml root view; without it the view can never take
                // focus in touch mode, so neither key events nor the soft keyboard reach it.
                isFocusableInTouchMode = true
                setTerminalViewClient(viewClient)
                // Honor terminal-cursor-blink-rate on the legacy path too: without an explicit
                // rate the blinker stays inert at the default 0 even when setTerminalCursorBlinkerState
                // is later called from onEmulatorSet (parity with the native canvas blink).
                setTerminalCursorBlinkerRate(
                    TermuxAppSharedProperties.getProperties()?.getTerminalCursorBlinkRate() ?: 0
                )
                setTextSize(fontSize.toInt())
                appliedFontSize = fontSize
                val resolvedTypeface = typeface ?: android.graphics.Typeface.MONOSPACE
                setTypeface(resolvedTypeface)
                appliedTypeface = resolvedTypeface
                setLigaturesEnabled(enableLigatures)
                appliedLigatures = enableLigatures
                setHyperlinksEnabled(hyperlinksEnabled)
                appliedHyperlinks = hyperlinksEnabled
                setImagesEnabled(imagesEnabled)
                appliedImages = imagesEnabled
                attachSession(session)
                appliedSession = session
                // The emulator is usually not created until the view gets its size from
                // layout, in which case applyPalette() fails and must be retried later.
                if (TerminalViewRegistry.applyPalette(this, session, palette)) {
                    appliedPalette = palette
                    TerminalViewRegistry.setPendingPalette(this, null)
                } else {
                    appliedPalette = null
                    TerminalViewRegistry.setPendingPalette(this, palette)
                }
                // Posted so that focus is taken after the view is attached and laid out,
                // otherwise showSoftInput() calls silently fail.
                post { if (isActivePane) requestFocus() }
                setOnFocusChangeListener(focusListener)
                TerminalViewRegistry.registerView(session, this, isActivePane)
                terminalView = this
            }
        },
        modifier = modifier,
        update = { view ->
            // A pane slot renders whatever session is assigned to it at any moment (tab
            // switches, split role swaps), so re-attach when it changed; the factory runs
            // only once per AndroidView instance.
            if (appliedSession !== session) {
                view.attachSession(session)
                appliedSession = session
                // The emulator was recreated by the attach, so force a palette re-apply
                // against the new session and drop any stale pending palette.
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
            if (appliedHyperlinks != hyperlinksEnabled) {
                view.setHyperlinksEnabled(hyperlinksEnabled)
                appliedHyperlinks = hyperlinksEnabled
            }
            if (appliedImages != imagesEnabled) {
                view.setImagesEnabled(imagesEnabled)
                appliedImages = imagesEnabled
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