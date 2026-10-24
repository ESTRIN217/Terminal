package com.termux.terminal.x11

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.KeyEvent
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.x11.ICmdEntryInterface
import com.termux.x11.LorieView
import com.termux.x11.Prefs
import com.termux.x11.input.InputEventSender
import com.termux.x11.utils.TermuxX11ExtraKeys
import kotlinx.coroutines.flow.MutableStateFlow

import com.termux.x11.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS
import java.util.function.Consumer

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
    val tag: String,
    private val context: Context,
    private val prefsSettings: Prefs,
    /** Invoked when the companion asks the display window to stop (ACTION_STOP). */
    private val onStopRequested: () -> Unit,
) : X11SessionBus.Handler, LorieView.Client {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: ICmdEntryInterface? = null
    private val retryRunnable = Runnable { tryConnect() }
    private var view: LorieView? = null

    /** Shared key processor (lorie's standalone implementation, spec 016). */
    private var keyInput: InputEventSender? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runWithView { it.reloadPreferences(prefsSettings) }
        syncKeyInputPrefs()
    }

    val connectionState = MutableStateFlow(X11ConnectionState.NO_SERVER)

    /** Attach: join the bus and listen for preference changes (view comes via [bindView]). */
    fun attach() {
        prefsSettings.get().registerOnSharedPreferenceChangeListener(prefsListener)
        X11SessionBus.registerHandler(this)
    }

    /** Detach: leave the bus and stop retrying. Call when the tab closes. */
    fun detach() {
        X11SessionBus.unregisterHandler(this)
        prefsSettings.get().unregisterOnSharedPreferenceChangeListener(prefsListener)
        mainHandler.removeCallbacks(retryRunnable)
        view = null
        service = null
        keyInput = null
        setConnectionState(X11ConnectionState.DISCONNECTED)
    }

    /**
     * Binds the view being composed for this tab (a fresh [LorieView] after every
     * rotation) and starts/restarts the connection flow, mirroring MainActivity's
     * activity recreation behavior.
     *
     * Also wires the standalone key path (spec 016, RF-01/RF-02): the view's
     * [LorieView.setOnKeyListener] (fed by hardware dispatch and the IME
     * `InputConnection.sendKeyEvent`) routes through lorie's [InputEventSender],
     * the same processor MainActivity uses via TouchInputHandler.
     */
    fun bindView(newView: LorieView) {
        view = newView
        newView.setClient(this)
        val sender = InputEventSender(newView, Consumer<Boolean> { /* capturing is standalone-only */ })
        keyInput = sender
        newView.setOnKeyListener { _, _, event -> sender.sendKeyEvent(event) }
        syncKeyInputPrefs()
        startConnecting()
    }

    /** Mirrors TouchInputHandler's preference sync onto the shared key processor. */
    private fun syncKeyInputPrefs() {
        keyInput?.preferScancodes = prefsSettings.preferScancodes.get()
    }

    private fun runWithView(block: (LorieView) -> Unit) {
        val v = view ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) block(v) else mainHandler.post { block(v) }
    }

    /**
     * Paste-ready companion launch command for the "no server" banner
     * (spec 014, enmienda A): prefers the guest loader command (`termux-x11 ...`)
     * when the wrapper script exists in the rootfs, and falls back to the
     * absolute-path app_process one-liner.
     * @see X11CompanionCommand.chooseLaunchCommand
     */
    fun companionCommand(display: String = ":0"): String? =
        X11CompanionCommand.chooseLaunchCommand(
            guestWrapperExists(),
            resolveApplicationId(context),
            display,
            tag.ifEmpty { null }
        )

    /** Host check for the idempotent guest wrapper written on app start. */
    private fun guestWrapperExists(): Boolean =
        java.io.File(TermuxConstants.TERMUX_X11.X11_GUEST_WRAPPER_HOST_PATH).isFile()

    /**
     * Start the companion X server from the app itself (spec 014, enmienda B).
     * Fire-and-forget: when the server comes up it announces the display via
     * broadcast and the tab flips out of the NO_SERVER state. Safe to tap again:
     * a live server gets re-announced instead of racing for the socket.
     *
     * @param displayArg display argument; defaults to the current tag-less display
     * @return null on spawn, otherwise the [Error] to show
     */
    fun startCompanion(displayArg: String = ":0"): Error? =
        X11CompanionSpawner.spawn(context, displayArg, tag.ifEmpty { null })

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
        runWithView { it.reloadPreferences(prefsSettings) }
    }

    override fun onCustomAction(tag: String, what: String?) {
        // The notification-driven custom actions live in the standalone flow;
        // embedded tabs only need the connection lifecycle.
    }

    // ---- LorieView.Client (also mirrors MainActivity behavior) ----

    override fun getPrefs(): Prefs = prefsSettings

    override fun clientConnectedStateChanged() {
        // The native side noticed a connection state change (e.g. server hangup);
        // resync the tab state for the "no server" overlay.
        runWithView { view ->
            connectionState.value =
                if (view.connected()) X11ConnectionState.CONNECTED
                else X11ConnectionState.DISCONNECTED
        }
    }

    override fun getExtraKeys(): TermuxX11ExtraKeys? {
        // The extra keys bar of embedded tabs is hosted by the Compose screen;
        // no Termux-style modifier controller is attached in this iteration.
        return null
    }

    override fun useTermuxEKBarBehaviour(): Boolean = false

    override fun handleKey(event: KeyEvent): Boolean {
        // Mirrors MainActivity.handleKey (spec 016, RF-01): win-key filter by
        // preference, then the shared key processor (text vs key event, AltGr,
        // autorepeat suppression, FLAG_CANCELED, lock-key sync).
        val sender = keyInput ?: return false
        if (prefsSettings.filterOutWinkey.get() && (event.keyCode == KeyEvent.KEYCODE_META_LEFT
                    || event.keyCode == KeyEvent.KEYCODE_META_RIGHT || event.isMetaPressed))
            return false
        return sender.sendKeyEvent(event)
    }

    override fun refreshInputDevices() {
        // The input handler is owned by the standalone MainActivity; embedded
        // tabs connect devices implicitly through the view.
    }

    override fun isCharBasedInputEnforced(): Boolean = prefsSettings.enforceCharBasedInput.get()

    // ---- extra keys of the Compose bar (spec 016, RF-03) ----

    /**
     * Blueprints of the last modifier press state sent to the X server, so each
     * extra key step only flips what actually changed (TermuxX11ExtraKeys
     * semantics: modifiers follow the delta between clicks).
     */
    private var extraCtrlDown = false
    private var extraAltDown = false
    private var extraShiftDown = false

    /**
     * Handles an extra keys bar tap for this tab: specializes standalone
     * specials (KEYBOARD/KEYBOARD zooms) and injects key presses/text mirroring
     * [TermuxX11ExtraKeys.onTerminalExtraKeyButtonClick].
     *
     * @param key Button name or macro string from the extra keys config
     * @param isMacro Whether the bar marked the button as a multi-key macro
     * @param stickyModifiers Sticky modifier names currently toggled in the bar
     */
    fun sendExtraKey(key: String, isMacro: Boolean, stickyModifiers: Set<String> = emptySet()) {
        val v = view ?: return
        val result = X11ExtraKeys.parse(key, isMacro, stickyModifiers)
        when (result.special?.key) {
            "KEYBOARD" -> runWithView { it.toggleKeyboardVisible() }
            "PASTE" -> sendExtraKeyPaste()
            "ZOOM_IN" -> runWithView { it.adjustRendererZoom(25) }
            "ZOOM_OUT" -> runWithView { it.adjustRendererZoom(-25) }
            "ZOOM_RESET" -> runWithView { it.resetRendererZoom() }
            // DRAWER/PREFERENCES/EXIT/MOUSE_HELPER/STYLUS_HELPER are standalone-only:
            // the embedded tab has no lorie drawer/preferences to open.
        }
        if (result.special != null) return

        for (step in result.presses) sendExtraKeyStep(step)
    }

    /** Mirrors the upstream click handler: modifier deltas, then key or raw text. */
    private fun sendExtraKeyStep(step: X11ExtraKeys.KeyStep) {
        val v = view ?: return
        if (extraCtrlDown != step.ctrlActive) {
            extraCtrlDown = step.ctrlActive
            v.sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, step.ctrlActive)
        }
        if (extraAltDown != step.altActive) {
            extraAltDown = step.altActive
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, step.altActive)
        }
        if (extraShiftDown != step.shiftActive) {
            extraShiftDown = step.shiftActive
            v.sendKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, step.shiftActive)
        }

        val keyCode = PRIMARY_KEY_CODES_FOR_STRINGS[step.key]
        if (keyCode != null) {
            v.sendKeyEvent(0, keyCode, true)
            v.sendKeyEvent(0, keyCode, false)
        } else {
            // Not a control key: upstream sends the bare text as a text event.
            v.sendTextEvent(step.key.toByteArray(Charsets.UTF_8))
        }
    }

    /** Mirrors the upstream PASTE special: clipboard text goes in as a text event. */
    private fun sendExtraKeyPaste() {
        val v = view ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val clipData = clipboard.primaryClip ?: return
        val pasted = clipData.getItemAt(0).coerceToText(context) ?: return
        if (!pasted.isEmpty())
            v.sendTextEvent(pasted.toString().toByteArray(Charsets.UTF_8))
    }

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
                if (logcatOutput != null) runWithView { it.startLogcat(logcatOutput.detachFd()) }
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Logcat fd extraction skipped", e)
        }
        cancelRetry()
        tryConnect()
    }

    private fun tryConnect() {
        var connected = false
        runWithView { v -> connected = v.connected() }
        if (connected) {
            cancelRetry()
            setConnectionState(X11ConnectionState.CONNECTED)
            return
        }
        val s = service
        if (s == null) {
            runWithView { it.requestConnection() }
            scheduleConnect()
            return
        }
        try {
            val fd = s.xConnection
            if (fd != null) {
                val fdNumber = fd.detachFd()
                runWithView { v ->
                    v.connect(fdNumber)
                    v.triggerCallback()
                    v.reloadPreferences(prefsSettings)
                }
                syncKeyInputPrefs()
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
            view?.connect(-1)
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
