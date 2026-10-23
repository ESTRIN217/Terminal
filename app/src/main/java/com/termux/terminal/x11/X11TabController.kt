package com.termux.terminal.x11

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.KeyEvent
import com.termux.shared.logger.Logger
import com.termux.x11.ICmdEntryInterface
import com.termux.x11.LorieView
import com.termux.x11.Prefs
import com.termux.x11.utils.TermuxX11ExtraKeys
import kotlinx.coroutines.flow.MutableStateFlow

/** Connection state surfaced by [X11TabController.stateFlow]. */
enum class X11ConnectionState {
    /** No server offered itself yet; the companion start command is the way out. */
    NO_SERVER,
    /** LorieView holds an X connection fd and is rendering the display. */
    CONNECTED,
    /** The binder died after a working connection; falls back to no-server UI. */
    DISCONNECTED,
}

/**
 * Per-tab surrogate of MainActivity's host role (spec 014): implements
 * [LorieView.Client] so an embedded [LorieView] works inside a Compose session
 * tab, and [X11SessionBus.Handler] so the companion X server process (started with
 * the copy-paste `app_process` command) binds this tab when it broadcasts
 * `ACTION_START`.
 *
 * UI-free except for the state flow the Compose host observes; connection flow
 * mirrors lorie's MainActivity.tryConnect loop (retry every 250 ms until the
 * server answers getXConnection()).
 */
class X11TabController(
    private val view: LorieView,
    val tag: String,
    private val context: Context,
    private val prefsSettings: Prefs,
    /** Invoked when the companion asks the display window to stop (ACTION_STOP). */
    private val onStopRequested: () -> Unit,
) : X11SessionBus.Handler, LorieView.Client {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: ICmdEntryInterface? = null
    private val retryRunnable = Runnable { tryConnect() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { view.reloadPreferences(prefsSettings) }
    }

    val connectionState = MutableStateFlow(X11ConnectionState.NO_SERVER)

    /** Attach: set the view client and join the bus. Call from the Compose host. */
    fun attach() {
        view.setClient(this)
        prefsSettings.get().registerOnSharedPreferenceChangeListener(prefsListener)
        X11SessionBus.registerHandler(this)
    }

    /** Detach: leave the bus and stop retrying. Call on tab disposal. */
    fun detach() {
        X11SessionBus.unregisterHandler(this)
        prefsSettings.get().unregisterOnSharedPreferenceChangeListener(prefsListener)
        mainHandler.removeCallbacks(retryRunnable)
        disconnect()
    }

    /**
     * Paste-ready companion launch command for the "no server" banner.
     * @see X11CompanionCommand.buildLaunchCommand
     */
    fun companionCommand(display: String = ":0"): String? =
        X11CompanionCommand.buildLaunchCommand(
            resolveApplicationId(context),
            display,
            tag.ifEmpty { null }
        )

    override fun onServerBinder(tag: String, binder: IBinder): Boolean {
        val current = service
        val sameBinder = current != null && current.asBinder() === binder
        if (current != null && current.asBinder().isBinderAlive && !sameBinder) {
            try {
                ICmdEntryInterface.Stub.asInterface(binder)
                    .reportFatalError("Terminal already has an active X server connection.")
            } catch (ignored: RemoteException) {}
            return true
        }
        if (sameBinder) return true

        service = ICmdEntryInterface.Stub.asInterface(binder)
        startConnecting()
        return true
    }

    override fun onServerDead(tag: String) {
        disconnect()
    }

    override fun onStopRequested(tag: String) {
        if (!matches(tag)) return
        onMainThreadSave { onStopRequested() }
    }

    override fun onPreferencesChanged(tag: String, key: String?) {
        if (!matches(tag)) return
        onMainThreadSave { view.reloadPreferences(prefsSettings) }
    }

    override fun onCustomAction(tag: String, what: String?) {
        // The notification-driven custom actions live in the standalone flow;
        // embedded tabs only need the connection lifecycle.
    }

    // ---- LorieView.Client (also mirrors MainActivity behavior) ----

    override fun getPrefs(): Prefs = prefsSettings

    override fun getExtraKeys(): TermuxX11ExtraKeys? {
        // The extra keys bar of embedded tabs is hosted by the Compose screen;
        // no Termux-style modifier controller is attached in this iteration.
        return null
    }

    override fun useTermuxEKBarBehaviour(): Boolean = false

    override fun handleKey(event: KeyEvent): Boolean {
        // The Compose host forwards hardware keys itself; LorieView events fall
        // through to native when no host handler is interested.
        return false
    }

    override fun refreshInputDevices() {
        // The input handler is owned by the standalone MainActivity; embedded
        // tabs connect devices implicitly through the view.
    }

    override fun isCharBasedInputEnforced(): Boolean = prefsSettings.enforceCharBasedInput.get()

    override fun postOnUiThread(runnable: Runnable) {
        mainHandler.post(runnable)
    }

    override fun getClientContext(): Context = context

    // ---- connection mechanics (mirrors MainActivity) ----

    private fun startConnecting() {
        val s = service ?: return
        try {
            if (s.asBinder().isBinderAlive) {
                val logcatOutput: ParcelFileDescriptor? = s.logcatOutput
                if (logcatOutput != null) view.startLogcat(logcatOutput.detachFd())
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Logcat fd extraction skipped", e)
        }
        cancelRetry()
        tryConnect()
    }

    private fun tryConnect() {
        if (view.connected()) {
            cancelRetry()
            setConnectionState(X11ConnectionState.CONNECTED)
            return
        }
        val s = service
        if (s == null) {
            view.requestConnection()
            scheduleConnect()
            return
        }
        try {
            val fd = s.xConnection
            if (fd != null) {
                view.connect(fd.detachFd())
                view.triggerCallback()
                view.reloadPreferences(prefsSettings)
                setConnectionState(X11ConnectionState.CONNECTED)
                cancelRetry()
            } else {
                scheduleConnect()
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "getXConnection failed; server may still be starting", e)
            service = null
            setConnectionState(X11ConnectionState.DISCONNECTED)
            scheduleConnect()
        }
    }

    private fun scheduleConnect() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, CONNECT_RETRY_MS)
    }

    private fun cancelRetry() {
        mainHandler.removeCallbacks(retryRunnable)
    }

    private fun disconnect() {
        service = null
        onMainThreadSave {
            view.connect(-1)
            setConnectionState(X11ConnectionState.DISCONNECTED)
        }
    }

    private fun setConnectionState(state: X11ConnectionState) {
        if (Looper.myLooper() == Looper.getMainLooper())
            connectionState.value = state
        else
            mainHandler.post { connectionState.value = state }
    }

    private fun matches(tag: String): Boolean {
        // Untagged tabs follow the shared default channel; named tabs exact-match.
        return this.tag.isEmpty() || tag.isEmpty() || this.tag == tag
    }

    private fun onMainThreadSave(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val LOG_TAG = "X11TabController"
        private const val CONNECT_RETRY_MS = 250L

        /**
         * Application id used by the companion command; the shared-UID placeholder
         * resolution makes `packageName` the fork's install id.
         */
        fun resolveApplicationId(context: Context): String {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.packageName
            } catch (e: PackageManager.NameNotFoundException) {
                context.packageName
            }
        }
    }
}
