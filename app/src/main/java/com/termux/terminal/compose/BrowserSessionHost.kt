package com.termux.terminal.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.termux.R
import org.mozilla.geckoview.GeckoView

/**
 * Hosts a [GeckoView] as a browser session tab inside [com.termux.app.TermuxComposeActivity]
 * (spec 022). The controller lives in [BrowserTabRegistry] keyed by [sessionId] so a
 * recreated host (rotation/IME) re-binds the same live session; the tab closes through
 * the host callbacks without touching TermuxService (RF-9).
 *
 * The Compose chrome (URL bar, navigation, RF-6 error overlay) is driven by the
 * controller's StateFlow; the engine view itself is the plain Android interop leaf.
 *
 * @param sessionId Stable id for the browser session tab
 * @param initialUrl URL the tab loads on its first bind, or null for a blank tab
 * @param onCloseSession Callback to close the tab (removes it without touching the service)
 * @param onOpenLink RF-8 handoff for `target=_blank`/popup URIs (activity routes by preference)
 * @param isActivePane Whether this pane is the focused pane of a split view
 * @param onActivatePane Callback when the pane requests focus while inactive
 * @param modifier Modifier to apply
 */
@Composable
fun BrowserSessionHost(
    sessionId: String,
    initialUrl: String?,
    onCloseSession: () -> Unit,
    onOpenLink: (String) -> Unit,
    isActivePane: Boolean = true,
    onActivatePane: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val controller = remember(sessionId) {
        BrowserTabRegistry.controllerFor(
            sessionId = sessionId,
            initialUrl = initialUrl,
            context = context,
            onCloseRequested = onCloseSession,
            onNewSessionRequested = onOpenLink
        )
    }
    val state by controller.state.collectAsState()
    var urlText by remember { mutableStateOf(state.location) }
    LaunchedEffect(state.location) { urlText = state.location }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize()) {
        // RF-2 chrome row: back / forward / reload + editable URL bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(enabled = state.canGoBack, onClick = controller::goBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.browser_nav_back_desc)
                )
            }
            IconButton(enabled = state.canGoForward, onClick = controller::goForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.browser_nav_forward_desc)
                )
            }
            IconButton(enabled = true, onClick = controller::reload) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.browser_nav_reload_desc)
                )
            }
            TextField(
                value = urlText,
                onValueChange = { urlText = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.browser_urlbar_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        keyboard?.hide()
                        controller.go(urlText)
                    }
                )
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isActivePane) {
                        // Split parity: the first tap on an unfocused pane promotes it
                        // before the engine consumes anything (same contract as the
                        // terminal panes, RF-2 split note in the spec 014 host).
                        detectTapGestures {
                            if (!isActivePane) onActivatePane()
                        }
                    },
                factory = { ctx ->
                    GeckoView(ctx).also { view ->
                        view.setSession(controller.session)
                    }
                },
                update = { view ->
                    // Re-bind after recomposition deltas (the session identity is stable).
                    if (view.session !== controller.session) {
                        view.setSession(controller.session)
                    }
                }
            )

            // RF-6: the engine internal error page plus this overlay; the exit is
            // either a fresh reload or the external handoff.
            if (state.loadFailed) {
                BrowserErrorOverlay(
                    location = state.location,
                    onRetry = { controller.reload() },
                    onOpenExternal = {
                        val target = state.location.ifBlank { urlText }
                        if (target.isNotEmpty()) controller.openExternally(target)
                    }
                )
            }
        }
    }
}

/**
 * Fully-opaque error overlay (RF-6): message plus the two exits.
 *
 * @param location Current URL to offer externally (blank when the page never resolved)
 * @param onRetry Reload action
 * @param onOpenExternal ACTION_VIEW handoff
 */
@Composable
private fun BrowserErrorOverlay(
    location: String,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                stringResource(R.string.browser_error_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.browser_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.browser_error_retry))
                }
                Button(
                    onClick = onOpenExternal,
                    enabled = location.isNotBlank()
                ) {
                    Text(stringResource(R.string.browser_error_open_external))
                }
            }
        }
    }
}
