package com.termux.terminal.x11

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.x11.ICmdEntryInterface

/**
 * Bridges the companion X server process broadcasts with embedded X11 session tabs
 * (spec 014). Mirrors the pendingConnections logic of lorie's {@code LorieApp}:
 * binders offered by a starting server are delivered to registered handlers when
 * one exists for the display tag, or kept until a handler registers later.
 *
 * Kept free of Android UI dependencies so it can be unit-tested as plain logic.
 */
object X11SessionBus {

    private const val LOG_TAG = "X11SessionBus"

    /** A connected X11 session handler (one per embedded tab controller). */
    interface Handler {
        /**
         * A server offered its binder for the given display tag.
         * @param tag display tag ("" when the server runs untitled)
         * @param binder the [ICmdEntryInterface.Stub] binder token of the companion process
         * @return true to consume the binder and stop the delivery chain
         */
        fun onServerBinder(tag: String, binder: IBinder): Boolean = false

        /** Companion process death or explicit stop intent received. */
        fun onServerDead(tag: String) {}

        /** Stop request (action used by standalone to close the display window). */
        fun onStopRequested(tag: String) {}

        /** An X11 preference changed; controllers refresh their views. */
        fun onPreferencesChanged(tag: String, key: String?) {}

        /** A custom helper action (e.g. from the notification). */
        fun onCustomAction(tag: String, what: String?) {}
    }

    private val handlers = LinkedHashSet<Handler>()
    private val pending = HashMap<String, IBinder>()

    @Volatile
    private var lastEventForTesting: String? = null

    /** Registers a handler; pending binders for its tag (or unclaimed tags) are offered immediately. */
    @Synchronized
    fun registerHandler(handler: Handler) {
        handlers.add(handler)
        deliverPending(handler)
    }

    @Synchronized
    fun unregisterHandler(handler: Handler) {
        handlers.remove(handler)
    }

    /**
     * Entry built from a broadcast; routes [Intent] actions to handlers.
     * @param intent the received broadcast intent (may be malformed, never throws)
     */
    @Synchronized
    fun onBroadcastReceive(intent: Intent?) {
        lastEventForTesting = intent?.action
        when (intent?.action) {
            TermuxConstants.TERMUX_X11.ACTION_X11_START -> {
                val binder = extractBinder(intent) ?: return
                val tag = intent.getStringExtra(TermuxConstants.TERMUX_X11.EXTRA_X11_DOCUMENT_TAG) ?: ""
                deliverOrHold(tag, binder)
            }
            TermuxConstants.TERMUX_X11.ACTION_X11_STOP ->
                dispatchTagged(intent) { handler, tag -> handler.onStopRequested(tag) }
            TermuxConstants.TERMUX_X11.ACTION_X11_PREFERENCES_CHANGED ->
                dispatchTagged(intent) { handler, tag ->
                    handler.onPreferencesChanged(tag, intent.getStringExtra("key"))
                }
            TermuxConstants.TERMUX_X11.ACTION_X11_CUSTOM ->
                dispatchTagged(intent) { handler, tag ->
                    handler.onCustomAction(tag, intent.getStringExtra("what"))
                }
            else -> Logger.logDebug(LOG_TAG, "Ignoring intent with action " + intent?.action)
        }
    }

    @Synchronized
    fun pendingBindersSnapshot(): Map<String, IBinder> = pending.toMap()

    /** Test/introspection aid: event last routed by the bus. */
    fun lastEvent(): String? = lastEventForTesting

    /** Extracts the null-keyed bundle carrying the ICmdEntryInterface binder. */
    private fun extractBinder(intent: Intent): IBinder? {
        return try {
            val bundle: Bundle? = intent.getBundleExtra(null as String?)
            bundle?.getBinder(null as String?)
        } catch (e: Exception) {
            Logger.logWarn(LOG_TAG, "Malformed ACTION_X11_START intent")
            null
        }
    }

    private fun dispatchTagged(intent: Intent, block: (Handler, String) -> Unit) {
        val tag = intent.getStringExtra(TermuxConstants.TERMUX_X11.EXTRA_X11_DOCUMENT_TAG) ?: ""
        for (handler in handlers.toTypedArray()) block(handler, tag)
    }

    /** Stores the binder as pending if no handler consumes it, watching for death. */
    private fun deliverOrHold(tag: String, binder: IBinder) {
        for ((_, live) in pending) if (live === binder) return

        watchDeath(tag, binder)
        if (!offer(tag, binder)) pending[tag] = binder
    }

    private fun offer(tag: String, binder: IBinder): Boolean {
        for (handler in handlers.toTypedArray()) {
            if (handler.onServerBinder(tag, binder)) return true
        }
        return false
    }

    /** Watches the binder so dead servers release the pending claim and notify handlers. */
    private fun watchDeath(tag: String, binder: IBinder) {
        try {
            binder.linkToDeath({
                synchronized(this@X11SessionBus) {
                    if (pending[tag] === binder) pending.remove(tag)
                    for (handler in handlers.toTypedArray()) handler.onServerDead(tag)
                }
            }, 0)
        } catch (e: RemoteException) {
            Logger.logWarn(LOG_TAG, "Binder died before watch could be installed")
        }
    }

    /** Late-registered handlers consume pending binders for their tag or unclaimed ones. */
    private fun deliverPending(handler: Handler) {
        for (tag in pending.keys.toTypedArray()) {
            val binder = pending[tag] ?: continue
            if (handler.onServerBinder(tag, binder)) pending.remove(tag)
        }
    }
}
