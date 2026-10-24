package com.termux.terminal.x11

import android.content.Context
import com.termux.x11.LorieApp
import com.termux.x11.Prefs

/**
 * Keeps one [X11TabController] per X11 session tab (spec 014). The registry
 * survives rotation/process compositions (plain object) so a recreated Compose
 * host re-binds its fresh [LorieView] through [X11TabController.bindView] without
 * dropping the connection negotiation with the companion server.
 *
 * Entries are released when the tab closes (see X11SessionHost wiring).
 */
object X11SessionRegistry {

    private val controllers = HashMap<String, X11TabController>()

    /**
     * Gets or creates the controller for a session tab.
     *
     * @param sessionId stable session id
     * @param tag display tag the tab binds to ("" default)
     * @param context any context of the host application
     * @param onStopRequested invoked when the companion asks the display window to stop
     */
    @Synchronized
    fun controllerFor(sessionId: String, tag: String, context: Context, onStopRequested: () -> Unit): X11TabController {
        val existing = controllers[sessionId]
        if (existing != null) return existing

        val app = context.applicationContext as LorieApp
        val controller = X11TabController(
            tag = tag,
            context = context.applicationContext,
            prefsSettings = app.getPrefs(context),
            onStopRequested = onStopRequested,
        )
        controller.attach()
        controllers[sessionId] = controller
        return controller
    }

    /** Disposes the controller of a closed session (unregisters from the bus). */
    @Synchronized
    fun release(sessionId: String): Boolean {
        val removed = controllers.remove(sessionId) ?: return false
        removed.detach()
        return true
    }

    /**
     * Gets an existing controller without creating one (extra keys bar wiring,
     * spec 016): the bar only routes to tabs that were already composed.
     *
     * @param sessionId stable session id
     * @return The controller, or {@code null} when the tab has never been created
     */
    @Synchronized
    fun get(sessionId: String): X11TabController? = controllers[sessionId]

    @Synchronized
    fun activeSessionIds(): List<String> = controllers.keys.toList()
}
