package com.termux.terminal.compose

/**
 * Holds the extra keys handler of the currently visible file manager tab.
 *
 * The extra keys bar lives outside the file manager host, so it needs a way to
 * route key presses (e.g. {@code UP}, {@code ENTER}, {@code TAB}) into the
 * active session's navigation logic. Mirrors the singleton pattern used by
 * [TerminalViewRegistry]: the host sets [active] while composed and clears it
 * on dispose.
 *
 * The handler receives the raw key identifier from the extra keys bar and
 * returns {@code true} when it consumed the event.
 */
object FileManagerKeyHandlerHolder {
    @Volatile
    var active: ((key: String, isMacro: Boolean) -> Boolean)? = null
}
