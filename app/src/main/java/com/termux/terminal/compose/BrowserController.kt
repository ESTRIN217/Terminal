package com.termux.terminal.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.termux.shared.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * Holds the [GeckoRuntime] singleton (spec 022). The engine docs require at most one
 * runtime per process; it is created lazily with an Activity context on the first
 * browser tab so the startup of users that never open a link pays nothing.
 */
object GeckoRuntimeHolder {

    private const val LOG_TAG = "GeckoRuntimeHolder"

    @Volatile
    private var mRuntime: GeckoRuntime? = null

    /**
     * Get the process-wide runtime, creating it on first use.
     *
     * @param context an Activity context of the host application (GeckoRuntime.create)
     */
    fun runtime(context: Context): GeckoRuntime {
        mRuntime?.let { return it }
        synchronized(this) {
            mRuntime?.let { return it }
            val runtime = GeckoRuntime.create(context)
            Logger.logDebug(LOG_TAG, "GeckoRuntime created")
            mRuntime = runtime
            return runtime
        }
    }
}

/**
 * Immutable snapshot of a browser tab's engine state (spec 022, RF-2/RF-3/RF-6).
 *
 * The GeckoView paint state itself is never here: this is what the Compose
 * chrome (URL bar, navigation buttons, error overlay) collects per tab.
 *
 * @param location Current URL as reported by the engine (URL bar text)
 * @param title Document title, empty until the engine reports one
 * @param canGoBack Whether the engine has backwards history for this tab
 * @param canGoForward Whether the engine has forwards history for this tab
 * @param loading Whether a page load is currently in flight
 * @param loadFailed Whether the last load ended in an unrecoverable failure
 * (RF-6: the host shows the error overlay with the external exit)
 */
data class BrowserTabState(
    val location: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false
)

private const val LOG_TAG = "BrowserTabController"

/**
 * Owns the [GeckoSession] of one browser tab (spec 022).
 *
 * Instances live in [BrowserTabRegistry] keyed by the tab id, surviving rotations like
 * the X11 counterparts: a recreated Compose host re-binds a fresh [GeckoView] to the
 * same session without losing the page. Closing the tab (RF-9) runs [dispose], which
 * closes the session and unregisters it without touching other tabs.
 *
 * Delegates only push engine events into [state]; they never touch the UI directly.
 *
 * @property id Stable tab id (matches TermuxSessionUiModel.Browser.id)
 */
class BrowserTabController internal constructor(val id: String) {

    private val mState = MutableStateFlow(BrowserTabState())

    /** Collected by the host's chrome; never drives the engine directly. */
    val state: StateFlow<BrowserTabState> = mState.asStateFlow()

    /**
     * The engine session of this tab. Created open (opened on [bind]); closed in
     * [dispose]. The host sets it on its GeckoView with [GeckoView.setSession].
     */
    val session: GeckoSession = GeckoSession()

    /** Whether [bind] has run (a session can only be opened once). */
    private var mBound = false

    /** Invoked on `window.close()` requests from the page (tab removes itself). */
    var closeRequested: (() -> Unit)? = null

    /**
     * Invoked when a page asks for a new top-level window (`target=_blank`, RF-8).
     * The activity owns the intern/extern policy (it reads the preference); the
     * delegate only hands over the URI.
     */
    var newSessionListener: ((String) -> Unit)? = null

    /** Context captured at bind time for the external fallback (RF-6/RF-8). */
    private var mBoundContext: Context? = null

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                location: String?,
                permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                update { it.copy(location = location ?: "") }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                update { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                update { it.copy(canGoForward = canGoForward) }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // RF-8: the activity routes (internal tab or external) against the
                // preference; the engine popup itself is consumed (resolved null):
                // either our own tab loads the URI or the system browser gets it.
                newSessionListener?.invoke(uri) ?: openExternally(uri)
                return GeckoResult.fromValue(null)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                update { it.copy(title = title ?: "") }
            }

            override fun onCloseRequest(session: GeckoSession) {
                closeRequested?.invoke()
            }

            override fun onCrash(session: GeckoSession) {
                // RF-6: a content crash marks the overlay without a dry reopen (a dead
                // session re-opened blind leaves a zombie); the host offers the exit.
                update { it.copy(loading = false, loadFailed = true) }
            }

            override fun onKill(session: GeckoSession) {
                // Same exit as RF-6 for an engine kill (OOM, OS decision).
                update { it.copy(loading = false, loadFailed = true) }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                update { it.copy(loading = true, loadFailed = false) }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                update { it.copy(loading = false, loadFailed = !success) }
            }
        }
    }

    /**
     * Open the session against the process runtime and optionally load [initialUrl].
     * Idempotent: a re-bound host (rotation) calls again and nothing happens.
     *
     * @param context Activity context of the host composable
     * @param initialUrl URL of the tab factory (term link), else null for blank
     */
    fun bind(context: Context, initialUrl: String?) {
        if (mBound) return
        mBound = true
        mBoundContext = context
        session.open(GeckoRuntimeHolder.runtime(context))
        initialUrl?.let {
            Logger.logDebug(LOG_TAG, "Tab $id opening $it")
            session.loadUri(it)
        }
    }

    /**
     * Load the normalized URL the user typed in the bar (RF-2).
     *
     * @param rawUrl Bar text; blank strings are ignored
     */
    fun go(rawUrl: String) {
        val url = BrowserLogic.normalizeUrlInput(rawUrl)
        if (url.isEmpty()) return
        session.loadUri(url)
    }

    /** Go back in this tab's history (no-op without history, RF-2). */
    fun goBack() {
        if (mState.value.canGoBack) session.goBack()
    }

    /** Go forward in this tab's history (no-op without history, RF-2). */
    fun goForward() {
        if (mState.value.canGoForward) session.goForward()
    }

    /** Reload the current page (RF-2). */
    fun reload() {
        session.reload()
    }

    /**
     * Hand a URI to the system browser (RF-6 exit / RF-7 non-web / RF-8 external).
     *
     * @param uri target URI (should already be routed by the caller's policy)
     */
    fun openExternally(uri: String) {
        val context = mBoundContext ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        } catch (e: Exception) {
            Logger.logWarn(LOG_TAG, "No external handler for $uri")
        }
    }

    /**
     * Close the engine session. The registry (`BrowserTabRegistry.release`) owns the
     * unregistering; call that from the tab-removal path (RF-9), not this.
     */
    fun dispose() {
        if (mBound) {
            session.close()
        }
    }

    private fun update(transform: (BrowserTabState) -> BrowserTabState) {
        mState.update { transform(it) }
    }
}

/**
 * Keeps one [BrowserTabController] per browser session tab (spec 022, same registry
 * pattern as [com.termux.terminal.x11.X11SessionRegistry]): the map survives
 * rotations so a recreated host re-binds the same live session.
 *
 * Entries are released when the tab closes (see the host wiring).
 */
object BrowserTabRegistry {

    private val mControllers = HashMap<String, BrowserTabController>()

    /**
     * Gets or creates the controller of a browser tab and binds its session.
     *
     * @param sessionId stable session id
     * @param initialUrl URL from the tab factory (therminal link), null for blank
     * @param context Activity context of the host composable
     * @param onCloseRequested tab-removal callback (page `window.close()`, RF-9)
     * @param onNewSessionRequested RF-8 handoff of `target=_blank` URIs to the activity
     */
    @Synchronized
    fun controllerFor(
        sessionId: String,
        initialUrl: String?,
        context: Context,
        onCloseRequested: () -> Unit,
        onNewSessionRequested: (String) -> Unit
    ): BrowserTabController {
        mControllers[sessionId]?.let { existing ->
            existing.bind(context, null)
            return existing
        }
        val controller = BrowserTabController(sessionId)
        controller.closeRequested = onCloseRequested
        controller.newSessionListener = onNewSessionRequested
        controller.bind(context, initialUrl)
        mControllers[sessionId] = controller
        return controller
    }

    /**
     * Gets an existing controller without creating one (host recompositions).
     *
     * @param sessionId stable session id
     * @return the controller, or null when this tab has never been composed
     */
    @Synchronized
    fun get(sessionId: String): BrowserTabController? = mControllers[sessionId]

    /** Disposes the controller of a removed tab (closes its engine session). */
    @Synchronized
    fun release(sessionId: String): Boolean {
        val removed = mControllers.remove(sessionId) ?: return false
        removed.dispose()
        return true
    }
}
