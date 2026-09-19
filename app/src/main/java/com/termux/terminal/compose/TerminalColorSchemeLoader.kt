package com.termux.terminal.compose

import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalColorScheme
import java.io.FileInputStream
import java.util.Properties

/**
 * Loads a custom terminal color scheme from the {@code colors.properties} file (Termux styling
 * format: {@code foreground}, {@code background}, {@code cursor} and {@code color0}..{@code color15}
 * keys, where colors are 24-bit {@code #RRGGBB} values).
 *
 * The file location is {@link TermuxConstants#TERMUX_COLOR_PROPERTIES_FILE_PATH}. Parsing reuses
 * {@link TerminalColorScheme} so behaviour matches the classic Termux styling: an invalid or missing
 * file yields {@code null} and the theme-derived palette is kept.
 */
object TerminalColorSchemeLoader {

    private const val LOG_TAG = "TerminalColorSchemeLoader"

    /**
     * Load and parse the custom color scheme.
     *
     * @return The full indexed color scheme ({@code TextStyle.NUM_INDEXED_COLORS} entries), or
     * {@code null} if the file is missing or invalid.
     */
    fun load(): IntArray? {
        val file = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
        if (!file.isFile) return null
        val props = Properties()
        return try {
            FileInputStream(file).use { props.load(it) }
            val colorScheme = TerminalColorScheme()
            colorScheme.updateWith(props)
            colorScheme.mDefaultColors.clone()
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(
                LOG_TAG,
                "Custom color scheme ignored (invalid file): " + file.absolutePath,
                e
            )
            null
        }
    }
}