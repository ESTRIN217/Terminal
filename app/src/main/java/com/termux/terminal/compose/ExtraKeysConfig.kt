package com.termux.terminal.compose

import androidx.compose.runtime.Immutable
import com.termux.shared.logger.Logger
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed extra keys configuration from termux.properties.
 *
 * Mirrors the classic Termux parser ({@link ExtraKeysInfo}) so a configuration written for
 * Termux keeps working here: each entry is either a key name or an object with
 * {@code key}/{@code macro}, an optional {@code display} label and an optional {@code popup}
 * key shown on swipe up.
 *
 * On top of that this parser accepts a **paginated** layout (an array of pages, each an array
 * of rows) so the bar can hold more keys than fit on one screen:
 * `[[["ESC","UP"],["TAB","DOWN"]],[["F1","F2"]]]`. A flat array of rows
 * (`[["ESC","UP"],["TAB","DOWN"]]`) is still accepted and treated as a single page.
 *
 * @param pages The pages of extra key rows; each page is a list of rows of buttons
 */
@Immutable
data class ExtraKeysConfig(
    val pages: List<List<List<ExtraKeyConfig>>>
) {
    /** The first page of extra key rows, as a convenience accessor. */
    val rows: List<List<ExtraKeyConfig>>
        get() = pages.firstOrNull() ?: emptyList()

    companion object {
        private const val LOG_TAG = "ExtraKeysConfig"

        /** Empty extra keys configuration (fallback when even the default cannot be parsed). */
        val EMPTY = ExtraKeysConfig(pages = emptyList())

        /** Default value of the {@code extra-keys-style} property. */
        const val DEFAULT_STYLE = "default"

        /**
         * Compiled-in default configuration, shared with
         * {@link TermuxPropertyConstants#DEFAULT_IVALUE_EXTRA_KEYS} so there is a single source
         * of truth for the default layout.
         */
        const val DEFAULT_JSON = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS

        /** Keys that support long-press repeat. */
        val REPETITIVE_KEYS = setOf(
            "UP", "DOWN", "LEFT", "RIGHT",
            "BKSP", "DEL",
            "PGUP", "PGDN"
        )

        /** Modifier keys, which toggle a sticky modifier instead of being sent to the terminal. */
        val MODIFIER_KEYS = setOf("CTRL", "ALT", "SHIFT", "FN")

        /** Styles accepted by {@code extra-keys-style}. */
        private val VALID_STYLES = setOf("default", "arrows-only", "arrows-all", "all", "none")

        /**
         * Key aliases shared with the classic Termux implementation. Kept as a [Map] so the
         * package-private {@code CleverMap} supertype is never touched.
         */
        private val ALIASES: Map<String, String> = ExtraKeysConstants.CONTROL_CHARS_ALIASES

        /**
         * Resolve an {@code extra-keys-style} value to the display map that maps a key name to
         * the text shown on its button. Unknown values fall back to {@link #DEFAULT_STYLE} with
         * an error logged, matching what classic Termux does.
         *
         * @param style The raw property value
         * @return The display map, never {@code null}
         */
        fun displayMapForStyle(style: String): Map<String, String> {
            val normalized = style.trim().lowercase()
            if (normalized !in VALID_STYLES) {
                Logger.logError(
                    LOG_TAG,
                    "The style \"" + style + "\" for the key \"" +
                        TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE +
                        "\" is invalid. Using default style instead."
                )
                return ExtraKeysInfo.getCharDisplayMapForStyle(DEFAULT_STYLE)
            }
            return ExtraKeysInfo.getCharDisplayMapForStyle(normalized)
        }

        /**
         * Parse an extra keys JSON string into a config.
         *
         * Supports both the legacy flat layout (a single page of rows, e.g.
         * `[["ESC","/"],["TAB",...]]`) and a paginated layout (an array of pages,
         * each an array of rows, e.g. `[[["ESC",...],["TAB",...]],[["F1",...]]]`).
         *
         * A malformed value falls back to [DEFAULT_JSON] (logged) instead of yielding an empty
         * bar, as classic Termux does. An explicitly empty value (`[]`) is honoured as "no keys"
         * and still hides the bar.
         *
         * @param jsonString The JSON string from termux.properties
         * @param style The {@code extra-keys-style} value used to map key names to button labels
         * @return The parsed config, never {@code null}
         */
        fun parse(jsonString: String, style: String = DEFAULT_STYLE): ExtraKeysConfig {
            val displayMap = displayMapForStyle(style)

            val pages = try {
                parsePages(jsonString, displayMap)
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse extra keys config", e)
                null
            }
            if (pages != null) return ExtraKeysConfig(pages)

            val fallback = try {
                parsePages(DEFAULT_JSON, displayMap)
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse default extra keys", e)
                null
            }
            return if (fallback != null) ExtraKeysConfig(fallback) else EMPTY
        }

        private fun parsePages(
            jsonString: String,
            displayMap: Map<String, String>
        ): List<List<List<ExtraKeyConfig>>> {
            val pages = mutableListOf<List<List<ExtraKeyConfig>>>()
            val outerArray = JSONArray(jsonString)
            val isPaginated = outerArray.length() > 0 &&
                outerArray.get(0) is JSONArray &&
                outerArray.getJSONArray(0).length() > 0 &&
                outerArray.getJSONArray(0).get(0) is JSONArray
            if (isPaginated) {
                for (i in 0 until outerArray.length()) {
                    val page = parseRows(outerArray.getJSONArray(i), displayMap)
                    if (page.isNotEmpty()) pages.add(page)
                }
            } else {
                val page = parseRows(outerArray, displayMap)
                if (page.isNotEmpty()) pages.add(page)
            }
            return pages
        }

        private fun parseRows(
            rowsArray: JSONArray,
            displayMap: Map<String, String>
        ): List<List<ExtraKeyConfig>> {
            val rows = mutableListOf<List<ExtraKeyConfig>>()
            for (i in 0 until rowsArray.length()) {
                val rowArray = rowsArray.getJSONArray(i)
                val row = mutableListOf<ExtraKeyConfig>()
                for (j in 0 until rowArray.length()) {
                    val element = rowArray.get(j)
                    val config = parseKeyElement(element, displayMap)
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

        private fun parseKeyElement(
            element: Any?,
            displayMap: Map<String, String>
        ): ExtraKeyConfig? {
            return when (element) {
                is String -> {
                    val key = resolveAlias(element)
                    ExtraKeyConfig(
                        key = key,
                        display = displayOf(listOf(key), displayMap),
                        isMacro = false
                    )
                }
                is JSONObject -> {
                    val key = element.optString("key", "")
                    val macro = element.optString("macro", "")
                    val display = element.optString("display", "")
                    val popupElement = element.opt("popup")

                    // Classic Termux rejects a key defining both; here "key" simply wins, so a
                    // typo in one field still yields a working button.
                    val isMacro = key.isEmpty() && macro.isNotEmpty()
                    val keys: List<String> = if (isMacro) {
                        macro.split(" ").filter { it.isNotEmpty() }.map { resolveAlias(it) }
                    } else if (key.isNotEmpty()) {
                        listOf(resolveAlias(key))
                    } else {
                        return null
                    }
                    if (keys.isEmpty()) return null

                    val actualKey = keys.joinToString(" ")
                    val actualDisplay = display.ifEmpty { displayOf(keys, displayMap) }
                    val popup = if (popupElement != null && popupElement != JSONObject.NULL) {
                        parseKeyElement(popupElement, displayMap)
                    } else null

                    ExtraKeyConfig(
                        key = actualKey,
                        display = actualDisplay,
                        isMacro = isMacro,
                        popup = popup
                    )
                }
                else -> null
            }
        }

        /**
         * Build the button label for one or more key names, mapping each through the style
         * display map and falling back to the key name itself.
         */
        private fun displayOf(keys: List<String>, displayMap: Map<String, String>): String =
            keys.joinToString(" ") { key -> displayMap[key] ?: key }

        /**
         * Resolve a key name through the classic Termux alias map, falling back to the
         * case-insensitive resolution so hand-written lower case names keep working.
         */
        private fun resolveAlias(key: String): String {
            ALIASES[key]?.let { return it }
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
 * @param key The key identifier sent to the terminal; for a macro this is the whole
 * space-separated sequence (e.g. {@code "CTRL f d"})
 * @param display The text to display on the button
 * @param isMacro Whether [key] is a macro (space-separated key sequence)
 * @param popup Optional popup configuration (triggered by swipe up)
 */
@Immutable
data class ExtraKeyConfig(
    val key: String,
    val display: String,
    val isMacro: Boolean = false,
    val popup: ExtraKeyConfig? = null
) {
    val isRepetitive: Boolean
        get() = !isMacro && key in ExtraKeysConfig.REPETITIVE_KEYS

    val isModifier: Boolean
        get() = key in ExtraKeysConfig.MODIFIER_KEYS
}
