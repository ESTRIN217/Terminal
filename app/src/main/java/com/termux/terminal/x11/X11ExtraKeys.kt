package com.termux.terminal.x11

/**
 * Pure classification of the Compose extra keys bar keys for the X11 tab
 * (spec 016, RF-03). Mirrors lorie's [com.termux.x11.utils.TermuxX11ExtraKeys]:
 * standalone special buttons (KEYBOARD, PASTE, ZOOM_*) are differentiated from
 * key presses, and macro strings ("CTRL ALT f d") expand into per-key steps
 * where modifier tokens apply to the following key only.
 *
 * Steps carry the key **name** (never an Android keycode): the device-side
 * mapping lives in `ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS`, which
 * needs `android.view.KeyEvent` and must stay out of these tests.
 */
object X11ExtraKeys {

    /** Standalone main-screen buttons that do not map to a regular key press. */
    data class Special(val key: String) {
        companion object {
            val KEYBOARD = Special("KEYBOARD")
            val DRAWER = Special("DRAWER")
            val PREFERENCES = Special("PREFERENCES")
            val EXIT = Special("EXIT")
            val PASTE = Special("PASTE")
            val MOUSE_HELPER = Special("MOUSE_HELPER")
            val STYLUS_HELPER = Special("STYLUS_HELPER")
            val ZOOM_IN = Special("ZOOM_IN")
            val ZOOM_OUT = Special("ZOOM_OUT")
            val ZOOM_RESET = Special("ZOOM_RESET")

            /** All standalone specials, keyed by their button name. */
            val ALL: Map<String, Special> = listOf(
                KEYBOARD, DRAWER, PREFERENCES, EXIT, PASTE,
                MOUSE_HELPER, STYLUS_HELPER, ZOOM_IN, ZOOM_OUT, ZOOM_RESET
            ).associateBy { it.key }
        }
    }

    /**
     * A single key press of an extra key, with the modifiers that were active
     * for it (terminology shared with [com.termux.terminal.bridge.TerminalKeyHandler.MacroStep]).
     */
    data class KeyStep(
        val key: String,
        val ctrlActive: Boolean = false,
        val altActive: Boolean = false,
        val shiftActive: Boolean = false
    )

    /**
     * The classified outcome of a bar key: either a standalone special button
     * (device side applies the app action) or a list of key presses (device
     * side injects them into [com.termux.x11.LorieView]).
     */
    data class Result(
        val special: Special?,
        val presses: List<KeyStep>
    )

    /**
     * Classify a bar key for injection.
     *
     * @param key Button name or macro string from the extra keys config
     * @param isMacro Whether the bar marked the button as a multi-key macro
     * @param stickyModifiers Currently sticky modifier names (CTRL/ALT/SHIFT)
     *        from the Compose bar; they apply only when the input is not
     *        already a macro, exactly like the terminal path semantics
     * @return The classification result
     */
    fun parse(key: String, isMacro: Boolean, stickyModifiers: Set<String> = emptySet()): Result {
        // Standalone specials never take modifiers, matching the upstream dispatch
        // that filters them before reaching the terminal click handler.
        if (!isMacro && !key.contains(" ")) {
            val special = Special.ALL[key]
            if (special != null)
                return Result(special, emptyList())
        }

        val macro = buildString {
            val prefix = stickyModifiers.filter { it in MODIFIER_TOKENS }.joinToString(" ")
            if (prefix.isNotEmpty()) append(prefix).append(" ")
            append(key)
        }

        val presses = com.termux.terminal.bridge.TerminalKeyHandler.parseMacro(macro).map { step ->
            KeyStep(step.key, step.ctrlActive, step.altActive, step.shiftActive)
        }
        // A lone expanded special keeps its special classification.
        if (presses.size == 1) {
            val special = Special.ALL[presses[0].key]
            if (special != null && !presses[0].ctrlActive && !presses[0].altActive && !presses[0].shiftActive)
                return Result(special, emptyList())
        }
        return Result(null, presses)
    }

    // Compose's sticky bar toggles CTRL/ALT/SHIFT (META exists in the config
    // parser but TerminalKeyHandler.parseMacro does not accept it as a token,
    // so merging it would end up as a bogus text step — mirror parseMacro).
    private val MODIFIER_TOKENS = setOf("CTRL", "ALT", "SHIFT")
}
