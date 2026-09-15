package com.termux.terminal.bridge

import android.view.KeyEvent

/**
 * Maps Android key events and extra key button names to terminal escape sequences.
 *
 * Uses [com.termux.terminal.KeyHandler] for keycode-to-escape-sequence mapping
 * when a full Android KeyEvent is available, and provides direct string mapping
 * for extra key button names.
 */
object TerminalKeyHandler {

    /** Modifier bit flags matching KeyHandler constants. */
    const val KEYMOD_CTRL = 0x40000000
    val KEYMOD_ALT = 0x80000000.toInt()
    const val KEYMOD_SHIFT = 0x20000000

    /**
     * Get the escape sequence for an Android [KeyEvent].
     *
     * @param keyCode Android keycode
     * @param event The key event (for modifier state), or null
     * @param ctrlActive Whether Ctrl is active (from extra keys)
     * @param altActive Whether Alt is active (from extra keys)
     * @param shiftActive Whether Shift is active (from extra keys)
     * @param fnActive Whether Fn is active (from extra keys)
     * @param cursorAppMode Whether the terminal is in cursor application mode
     * @param keypadAppMode Whether the terminal is in keypad application mode
     * @return The escape sequence string, or null if the key should be handled as a character
     */
    fun getKeyCode(
        keyCode: Int,
        event: KeyEvent? = null,
        ctrlActive: Boolean = false,
        altActive: Boolean = false,
        shiftActive: Boolean = false,
        fnActive: Boolean = false,
        cursorAppMode: Boolean = false,
        keypadAppMode: Boolean = false
    ): String? {
        var keyMod = 0
        if (ctrlActive || event?.isCtrlPressed == true) keyMod = keyMod or KEYMOD_CTRL
        if (altActive || event?.isAltPressed == true) keyMod = keyMod or KEYMOD_ALT
        if (shiftActive || event?.isShiftPressed == true) keyMod = keyMod or KEYMOD_SHIFT

        return com.termux.terminal.KeyHandler.getCode(
            keyCode, keyMod, cursorAppMode, keypadAppMode
        )
    }

    /**
     * Get the escape sequence for an extra key button name.
     *
     * Control keys in [KEY_CODE_MAP] go through [com.termux.terminal.KeyHandler.getCode]
     * with the active modifiers. A single character with Ctrl active is mapped with
     * [applyCtrl] (mirrors `TerminalView.inputCodePoint(..., controlDown=true)` from the
     * legacy path), and with Alt active it is prefixed with ESC.
     *
     * @param key The key name (e.g., "UP", "ESC", "TAB", "ENTER", "C")
     * @param ctrlActive Whether Ctrl modifier is active
     * @param altActive Whether Alt modifier is active
     * @param shiftActive Whether Shift modifier is active
     * @return The escape sequence string, or the raw character if not a control key
     */
    fun getKeySequence(
        key: String,
        ctrlActive: Boolean = false,
        altActive: Boolean = false,
        shiftActive: Boolean = false
    ): String {
        val keyMod = (if (ctrlActive) KEYMOD_CTRL else 0) or
                (if (altActive) KEYMOD_ALT else 0) or
                (if (shiftActive) KEYMOD_SHIFT else 0)

        val keyCode = KEY_CODE_MAP[key]
        if (keyCode != null) {
            val seq = com.termux.terminal.KeyHandler.getCode(
                keyCode, keyMod, false, false
            )
            if (seq != null) return seq
        }

        val base = when (key) {
            "ESC" -> "\u001B"
            "TAB" -> "\u0009"
            "ENTER" -> "\r"
            "BKSP" -> "\u007F"
            "DEL" -> "\u001B[3~"
            "SPACE" -> " "
            "INS" -> "\u001B[2~"
            else -> key
        }

        // Single character with modifiers: apply the Ctrl mapping and the Alt
        // ESC-prefix. Without this CTRL+C sent a literal "C" (modifiers ignored).
        if (base.length == 1 && (ctrlActive || altActive)) {
            var codePoint = base[0].code
            if (ctrlActive) codePoint = applyCtrl(codePoint)
            val result = codePoint.toChar().toString()
            return if (altActive) "\u001B$result" else result
        }

        return base
    }

    /**
     * Apply Ctrl modifier to a character code point.
     *
     * @param codePoint The character code point
     * @return The modified code point
     */
    fun applyCtrl(codePoint: Int): Int {
        return when {
            codePoint in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            codePoint in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            codePoint == ' '.code || codePoint == '2'.code -> 0
            codePoint == '['.code || codePoint == '3'.code -> 27
            codePoint == '\\'.code || codePoint == '4'.code -> 28
            codePoint == ']'.code || codePoint == '5'.code -> 29
            codePoint == '^'.code || codePoint == '6'.code -> 30
            codePoint == '_'.code || codePoint == '7'.code || codePoint == '/'.code -> 31
            codePoint == '8'.code -> 127
            else -> codePoint
        }
    }

    /**
     * Handle Fn + letter key remapping (virtual Fn key from Volume Up).
     *
     * @param letter The letter key pressed while Fn is active
     * @return A pair of (keyCode, altActive) or null if not a recognized Fn combo
     */
    fun getFnKeyCode(letter: String): FnMapping? {
        return when (letter.lowercase()) {
            "w" -> FnMapping(KeyEvent.KEYCODE_DPAD_UP, false)
            "a" -> FnMapping(KeyEvent.KEYCODE_DPAD_LEFT, false)
            "s" -> FnMapping(KeyEvent.KEYCODE_DPAD_DOWN, false)
            "d" -> FnMapping(KeyEvent.KEYCODE_DPAD_RIGHT, false)
            "p" -> FnMapping(KeyEvent.KEYCODE_PAGE_UP, false)
            "n" -> FnMapping(KeyEvent.KEYCODE_PAGE_DOWN, false)
            "t" -> FnMapping(KeyEvent.KEYCODE_TAB, false)
            "i" -> FnMapping(KeyEvent.KEYCODE_INSERT, false)
            "h" -> null // codepoint ~ (126)
            "u" -> null // codepoint _ (95)
            "l" -> null // codepoint | (124)
            "e" -> null // codepoint ESC (27)
            "b" -> null // Alt+B
            "f" -> null // Alt+F
            "x" -> null // Alt+X
            "1" -> FnMapping(KeyEvent.KEYCODE_F1, false)
            "2" -> FnMapping(KeyEvent.KEYCODE_F2, false)
            "3" -> FnMapping(KeyEvent.KEYCODE_F3, false)
            "4" -> FnMapping(KeyEvent.KEYCODE_F4, false)
            "5" -> FnMapping(KeyEvent.KEYCODE_F5, false)
            "6" -> FnMapping(KeyEvent.KEYCODE_F6, false)
            "7" -> FnMapping(KeyEvent.KEYCODE_F7, false)
            "8" -> FnMapping(KeyEvent.KEYCODE_F8, false)
            "9" -> FnMapping(KeyEvent.KEYCODE_F9, false)
            "0" -> FnMapping(KeyEvent.KEYCODE_F10, false)
            else -> null
        }
    }

    /**
     * Get the codepoint for an Fn + letter key that maps to a literal character.
     *
     * @param letter The letter key pressed while Fn is active
     * @return The codepoint, or null if not a recognized Fn character mapping
     */
    fun getFnCodePoint(letter: String): Int? {
        return when (letter.lowercase()) {
            "h" -> '~'.code
            "u" -> '_'.code
            "l" -> '|'.code
            "e" -> 27 // ESC
            "." -> 28 // Ctrl+.
            else -> null
        }
    }

    /**
     * Get the Alt+letter codepoint for Fn combos that use Alt.
     *
     * @param letter The letter key
     * @return The codepoint with altActive=true, or null
     */
    fun getFnAltCodePoint(letter: String): Pair<Int, Boolean>? {
        return when (letter.lowercase()) {
            "b" -> Pair('b'.code, true)
            "f" -> Pair('f'.code, true)
            "x" -> Pair('x'.code, true)
            else -> null
        }
    }

    data class FnMapping(val keyCode: Int, val altActive: Boolean)

    private val KEY_CODE_MAP = mapOf(
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END,
        "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
        "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "INS" to KeyEvent.KEYCODE_INSERT,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
        "BKSP" to KeyEvent.KEYCODE_DEL,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "F1" to KeyEvent.KEYCODE_F1,
        "F2" to KeyEvent.KEYCODE_F2,
        "F3" to KeyEvent.KEYCODE_F3,
        "F4" to KeyEvent.KEYCODE_F4,
        "F5" to KeyEvent.KEYCODE_F5,
        "F6" to KeyEvent.KEYCODE_F6,
        "F7" to KeyEvent.KEYCODE_F7,
        "F8" to KeyEvent.KEYCODE_F8,
        "F9" to KeyEvent.KEYCODE_F9,
        "F10" to KeyEvent.KEYCODE_F10,
        "F11" to KeyEvent.KEYCODE_F11,
        "F12" to KeyEvent.KEYCODE_F12
    )
}
