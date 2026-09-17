package com.estrin217.filemanager.compose

/**
 * Result of mapping an extra key or hardware key press to a file manager
 * navigation action.
 */
enum class FileManagerKeyAction {
    /** Move the focused row up (arrow up). */
    FOCUS_UP,

    /** Move the focused row down (arrow down). */
    FOCUS_DOWN,

    /** Focus the first row. */
    FOCUS_HOME,

    /** Focus the last row. */
    FOCUS_END,

    /** Jump a page up. */
    PAGE_UP,

    /** Jump a page down. */
    PAGE_DOWN,

    /** Open the focused entry (enter a directory or open a file). */
    OPEN,

    /** Back: clear selection, walk history, then go up (Escape/arrow left). */
    BACK,

    /** Toggle selection of the focused entry. */
    TOGGLE_SELECTION,

    /** The key is not actionable by the file manager. */
    NONE;

    companion object {
        /** Modifier tokens on the extra keys bar: never actionable by themselves. */
        private val MODIFIER_TOKENS = setOf("CTRL", "ALT", "SHIFT", "FN")

        /**
         * Map an extra key identifier (as produced by the extra keys bar, e.g.
         * {@code "UP"}, {@code "ESC"} or the macro {@code "CTRL UP"}) to a
         * [FileManagerKeyAction]. Modifier tokens ({@code CTRL}, {@code ALT},
         * {@code SHIFT}, {@code FN}) are skipped, so macros like
         * {@code "CTRL UP"} still navigate; unknown keys yield [NONE].
         *
         * @param key The key identifier or macro string.
         * @return The mapped action, never {@code null}.
         */
        fun map(key: String): FileManagerKeyAction {
            for (token in key.split(" ")) {
                val upper = token.uppercase()
                if (upper in MODIFIER_TOKENS) continue
                return mapSingle(upper)
            }
            return NONE
        }

        private fun mapSingle(token: String): FileManagerKeyAction = when (token) {
            "UP" -> FOCUS_UP
            "DOWN" -> FOCUS_DOWN
            "HOME" -> FOCUS_HOME
            "END" -> FOCUS_END
            "PGUP", "PAGEUP" -> PAGE_UP
            "PGDN", "PAGEDN" -> PAGE_DOWN
            "ENTER", "RETURN", "RIGHT" -> OPEN
            "LEFT", "ESC", "ESCAPE", "BKSP", "BACKSPACE", "BACK" -> BACK
            "TAB" -> TOGGLE_SELECTION
            else -> NONE
        }
    }
}