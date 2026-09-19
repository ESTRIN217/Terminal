package com.termux.terminal.compose

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed extra keys configuration from termux.properties.
 *
 * @param pages The pages of extra key rows; each page is a list of rows of buttons
 */
data class ExtraKeysConfig(
    val pages: List<List<List<ExtraKeyConfig>>>
) {

    /** The first page of extra key rows, as a convenience accessor. */
    val rows: List<List<ExtraKeyConfig>>
        get() = pages.firstOrNull() ?: emptyList()

    companion object {
        /** Empty extra keys configuration (fallback when parsing fails). */
        val EMPTY = ExtraKeysConfig(pages = emptyList())

        /** Default extra keys configuration: main keys + a special keys page. */
        val DEFAULT = parse(
            "[[[\"ESC\",\"/\",{\"key\":\"-\",\"popup\":\"|\"},\"HOME\",\"UP\",\"END\",\"PGUP\"]," +
            "[\"TAB\",\"CTRL\",\"ALT\",\"LEFT\",\"DOWN\",\"RIGHT\",\"PGDN\"]]," +
            "[[\"F1\",\"F2\",\"F3\",\"F4\",\"F5\",\"F6\",\"F7\",\"F8\"]," +
            "[\"F9\",\"F10\",\"F11\",\"F12\",\"INS\",\"DEL\"]]]"
        )

        /** Keys that support long-press repeat. */
        val REPETITIVE_KEYS = setOf(
            "UP", "DOWN", "LEFT", "RIGHT",
            "BKSP", "DEL",
            "PGUP", "PGDN"
        )

        /** Display text mapping for common keys. */
        private val DISPLAY_MAP = mapOf(
            "ESC" to "ESC",
            "TAB" to "TAB",
            "ENTER" to "ENTER",
            "BKSP" to "BKSP",
            "DEL" to "DEL",
            "SPACE" to "SPC",
            "HOME" to "HOME",
            "END" to "END",
            "PGUP" to "PGU",
            "PGDN" to "PGD",
            "INS" to "INS",
            "UP" to "\u2191",
            "DOWN" to "\u2193",
            "LEFT" to "\u2190",
            "RIGHT" to "\u2192",
            "CTRL" to "CTRL",
            "ALT" to "ALT",
            "SHIFT" to "SHIFT",
            "FN" to "FN",
            "F1" to "F1",
            "F2" to "F2",
            "F3" to "F3",
            "F4" to "F4",
            "F5" to "F5",
            "F6" to "F6",
            "F7" to "F7",
            "F8" to "F8",
            "F9" to "F9",
            "F10" to "F10",
            "F11" to "F11",
            "F12" to "F12"
        )

        /**
         * Parse an extra keys JSON string into a config.
         *
         * Supports both the legacy flat layout (a single page of rows, e.g.
         * `[["ESC","/"],["TAB",...]]`) and a paginated layout (an array of pages,
         * each an array of rows, e.g. `[[["ESC",...],["TAB",...]],[["F1",...]]]`).
         *
         * @param jsonString The JSON string from termux.properties
         * @return The parsed config, or a partial config / [EMPTY] on parse error
         */
        fun parse(jsonString: String): ExtraKeysConfig {
            val pages = mutableListOf<List<List<ExtraKeyConfig>>>()
            return try {
                val outerArray = JSONArray(jsonString)
                val isPaginated = outerArray.length() > 0 &&
                    outerArray.get(0) is JSONArray &&
                    outerArray.getJSONArray(0).length() > 0 &&
                    outerArray.getJSONArray(0).get(0) is JSONArray
                if (isPaginated) {
                    for (i in 0 until outerArray.length()) {
                        val page = parseRows(outerArray.getJSONArray(i))
                        if (page.isNotEmpty()) pages.add(page)
                    }
                } else {
                    val page = parseRows(outerArray)
                    if (page.isNotEmpty()) pages.add(page)
                }
                ExtraKeysConfig(pages)
            } catch (e: Exception) {
                if (pages.isNotEmpty()) ExtraKeysConfig(pages) else ExtraKeysConfig(pages = emptyList())
            }
        }

        private fun parseRows(rowsArray: JSONArray): List<List<ExtraKeyConfig>> {
            val rows = mutableListOf<List<ExtraKeyConfig>>()
            for (i in 0 until rowsArray.length()) {
                val rowArray = rowsArray.getJSONArray(i)
                val row = mutableListOf<ExtraKeyConfig>()
                for (j in 0 until rowArray.length()) {
                    val element = rowArray.get(j)
                    val config = parseKeyElement(element)
                    if (config != null) {
                        row.add(config)
                    }
                }
                if (row.isNotEmpty()) {
                    rows.add(row)
                }
            }
            return rows
        }

        private fun parseKeyElement(element: Any?): ExtraKeyConfig? {
            return when (element) {
                is String -> ExtraKeyConfig(
                    key = resolveAlias(element),
                    display = DISPLAY_MAP[resolveAlias(element)] ?: element,
                    isMacro = false
                )
                is JSONObject -> {
                    val key = element.optString("key", "")
                    val macro = element.optString("macro", "")
                    val display = element.optString("display", "")
                    val popupElement = element.opt("popup")

                    val actualKey = when {
                        key.isNotEmpty() -> resolveAlias(key)
                        macro.isNotEmpty() -> macro
                        else -> return null
                    }

                    val actualDisplay = display.ifEmpty {
                        when {
                            key.isNotEmpty() -> DISPLAY_MAP[resolveAlias(key)] ?: key
                            macro.isNotEmpty() -> macro.split(" ").firstOrNull()?.let { 
                                DISPLAY_MAP[resolveAlias(it)] ?: it 
                            } ?: macro
                            else -> actualKey
                        }
                    }

                    val popup = if (popupElement != null && popupElement != org.json.JSONObject.NULL) {
                        parseKeyElement(popupElement)
                    } else null

                    ExtraKeyConfig(
                        key = actualKey,
                        display = actualDisplay,
                        isMacro = macro != null,
                        popup = popup
                    )
                }
                else -> null
            }
        }

        private fun resolveAlias(key: String): String {
            return when (key.uppercase()) {
                "ESCAPE" -> "ESC"
                "CONTROL" -> "CTRL"
                "FUNCTION" -> "FN"
                "RETURN" -> "ENTER"
                "DELETE" -> "DEL"
                "BACKSPACE" -> "BKSP"
                "PAGEUP", "PAGE_UP", "PAGE-UP" -> "PGUP"
                "PAGEDOWN", "PAGE_DOWN", "PAGE-DOWN" -> "PGDN"
                "LT" -> "LEFT"
                "RT" -> "RIGHT"
                "DN" -> "DOWN"
                else -> key
            }
        }
    }
}

/**
 * Configuration for a single extra key button.
 *
 * @param key The key identifier sent to the terminal
 * @param display The text to display on the button
 * @param isMacro Whether this is a macro (space-separated key sequence)
 * @param popup Optional popup configuration (triggered by swipe up)
 */
data class ExtraKeyConfig(
    val key: String,
    val display: String,
    val isMacro: Boolean = false,
    val popup: ExtraKeyConfig? = null
) {
    val isRepetitive: Boolean
        get() = key in ExtraKeysConfig.REPETITIVE_KEYS

    val isModifier: Boolean
        get() = key in listOf("CTRL", "ALT", "SHIFT", "FN")
}
