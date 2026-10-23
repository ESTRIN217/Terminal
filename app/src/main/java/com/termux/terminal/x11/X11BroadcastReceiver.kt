package com.termux.terminal.x11

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest receiver (spec 014): forwards the companion X server broadcasts to
 * [X11SessionBus], which routes them to the embedded X11 session tab controllers.
 */
class X11BroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        X11SessionBus.onBroadcastReceive(intent)
    }
}
