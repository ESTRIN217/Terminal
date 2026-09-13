package com.termux.terminal.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
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
 * @param session The terminal session to attach to the view
 * @param fontSize Font size in density-independent pixels
 * @param viewClient The [TerminalViewClient] implementation for view callbacks
 * @param palette Colors applied to the emulator and the view; reapplied when it changes, or when
 * the session emulator becomes available later (see [TerminalViewRegistry.reapplyPendingPalette])
 * @param modifier Modifier to apply to the composable
 */
@Composable
fun TerminalViewHost(
    session: TerminalSession,
    fontSize: Float,
    viewClient: TerminalViewClient,
    palette: TerminalPalette,
    modifier: Modifier = Modifier
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var appliedFontSize by remember { mutableStateOf(0f) }
    var appliedPalette by remember { mutableStateOf<TerminalPalette?>(null) }

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                // Match the android:focusableInTouchMode="true" set on the view in
                // activity_termux.xml; without it the view can never take focus in touch
                // mode, so neither key events nor the soft keyboard reach it.
                isFocusableInTouchMode = true
                // Register with the activity so that the "More" menu of the text selection
                // toolbar ("MORE" action in TextSelectionCursorController) shows a context
                // menu handled by TermuxComposeActivity.
                (context as? ComponentActivity)?.registerForContextMenu(this)
                setTerminalViewClient(viewClient)
                setTextSize(fontSize.toInt())
                appliedFontSize = fontSize
                attachSession(session)
                // The emulator is usually not created until the view gets its size from
                // layout, in which case applyPalette() fails and must be retried later.
                if (TerminalViewRegistry.applyPalette(this, session, palette)) {
                    appliedPalette = palette
                    TerminalViewRegistry.setPendingPalette(null)
                } else {
                    appliedPalette = null
                    TerminalViewRegistry.setPendingPalette(palette)
                }
                // Posted so that focus is taken after the view is attached and laid out,
                // otherwise showSoftInput() calls silently fail.
                post { requestFocus() }
                TerminalViewRegistry.activeView = this
                terminalView = this
            }
        },
        modifier = modifier,
        update = { view ->
            if (appliedFontSize != fontSize) {
                view.setTextSize(fontSize.toInt())
                appliedFontSize = fontSize
            }
            if (appliedPalette != palette) {
                if (TerminalViewRegistry.applyPalette(view, session, palette)) {
                    appliedPalette = palette
                    TerminalViewRegistry.setPendingPalette(null)
                } else {
                    appliedPalette = null
                    TerminalViewRegistry.setPendingPalette(palette)
                }
            }
            if (!view.hasFocus() && view.isAttachedToWindow) {
                view.requestFocus()
            }
            TerminalViewRegistry.activeView = view
        }
    )

    DisposableEffect(session) {
        onDispose {
            if (TerminalViewRegistry.activeView === terminalView)
                TerminalViewRegistry.activeView = null
        }
    }
}
