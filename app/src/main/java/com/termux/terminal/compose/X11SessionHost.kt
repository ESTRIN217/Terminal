package com.termux.terminal.compose

import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.terminal.x11.X11ConnectionState
import com.termux.terminal.x11.X11SessionRegistry
import com.termux.terminal.x11.X11TabController
import com.termux.x11.LorieView

/**
 * Hosts a [LorieView] as an X11 session tab inside [com.termux.app.TermuxComposeActivity]
 * (spec 014). The controller lives in [X11SessionRegistry] keyed by [sessionId] so
 * rotations rebind a fresh view without losing the companion connection; the tab
 * closes through the host callbacks without touching TermuxService.
 *
 * Touch input is forwarded raw to the X server (XInput2 begin/update/end); the full
 * gesture emulation of the standalone lorie app stays in [MainActivity] for now.
 *
 * @param sessionId Stable id for the X11 session tab
 * @param tag Display tag the tab binds to ("" follows the default/shared server)
 * @param onCloseSession Callback to close the tab (companion ACTION_STOP or UI close)
 * @param isActivePane Whether this pane is the focused pane of a split view
 * @param onActivatePane Callback when the pane requests focus while inactive
 * @param modifier Modifier to apply
 */
@Composable
fun X11SessionHost(
    sessionId: String,
    tag: String,
    onCloseSession: () -> Unit,
    isActivePane: Boolean = true,
    onActivatePane: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val controller = remember(sessionId) {
        X11SessionRegistry.controllerFor(sessionId, tag, context) { onCloseSession() }
    }
    val state by controller.connectionState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                LorieView(ctx, null).also { view ->
                    installTouchForwarding(view)
                    controller.bindView(view)
                }
            }
        )

        if (state != X11ConnectionState.CONNECTED) {
            NoServerOverlay(state, controller)
        }
    }
}

/**
 * Raw touch forwarding to the X server: XInput2 touch begin (18), update (19) and
 * end (20) for every pointer, in view coordinates.
 */
private fun installTouchForwarding(view: LorieView) {
    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                view.requestFocus()
                view.sendTouchEvent(18, event.getPointerId(index), event.getX(index).toInt(), event.getY(index).toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount)
                    view.sendTouchEvent(19, event.getPointerId(p), event.getX(p).toInt(), event.getY(p).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                view.sendTouchEvent(19, id, event.getX(index).toInt(), event.getY(index).toInt())
                view.sendTouchEvent(20, id, event.getX(index).toInt(), event.getY(index).toInt())
            }
            MotionEvent.ACTION_CANCEL -> {
                for (p in 0 until event.pointerCount)
                    view.sendTouchEvent(20, event.getPointerId(p), event.getX(p).toInt(), event.getY(p).toInt())
            }
        }
        true
    }
}

@Composable
private fun NoServerOverlay(state: X11ConnectionState, controller: X11TabController) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (state == X11ConnectionState.DISCONNECTED)
                    stringResource(R.string.x11_disconnect_summary)
                else
                    stringResource(R.string.x11_no_server_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.x11_no_server_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = {
                val error = controller.startCompanion()
                if (error == null) {
                    Toast.makeText(context, context.getString(R.string.x11_start_server_sent), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context,
                        context.getString(R.string.x11_start_server_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show()
                }
            }) {
                Text(stringResource(R.string.x11_start_server))
            }
            Text(
                text = stringResource(R.string.x11_no_server_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
