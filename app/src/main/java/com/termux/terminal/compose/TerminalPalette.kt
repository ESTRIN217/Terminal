package com.termux.terminal.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle

/**
 * Color palette applied to a terminal session emulator.
 *
 * By default it is derived from the active Material color scheme so that the terminal follows the
 * system light/dark mode and Material You dynamic colors. When a custom [scheme] is provided (a
 * full indexed color scheme, e.g. parsed from a `colors.properties` file), its 24-bit truecolor
 * values are applied to every indexed color and overwrite the theme-derived colors.
 *
 * @property background Terminal background color (ARGB)
 * @property foreground Default text color (ARGB)
 * @property cursor Cursor color with guaranteed contrast against [background] (ARGB)
 * @property scheme Optional full indexed color scheme used by the emulator
 *   ([TextStyle.NUM_INDEXED_COLORS] entries: 256 indexed colors + default foreground/background/
 *   cursor), or null to keep the theme-derived colors
 */
data class TerminalPalette(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val scheme: IntArray? = null
) {
    companion object {

        /** Backgrounds perceived darker than this threshold get a white cursor. */
        private const val CURSOR_BRIGHTNESS_THRESHOLD = 130

        /**
         * Build a palette from the current {@link MaterialTheme} color scheme.
         *
         * When [scheme] is not null its indexed colors are used as-is; the foreground, background
         * and cursor colors are taken from their slots in the scheme so the whole palette stays
         * coherent. Otherwise the surface/onSurface colors of the active scheme are used.
         *
         * @param scheme Optional full indexed color scheme to use instead of the theme colors
         * @return The palette to apply to the terminal
         */
        @Composable
        fun fromTheme(scheme: IntArray? = null): TerminalPalette {
            val themeBackground = MaterialTheme.colorScheme.surface.toArgb()
            val themeForeground = MaterialTheme.colorScheme.onSurface.toArgb()
            if (scheme != null && scheme.size > TextStyle.COLOR_INDEX_CURSOR) {
                val background = scheme[TextStyle.COLOR_INDEX_BACKGROUND]
                val foreground = scheme[TextStyle.COLOR_INDEX_FOREGROUND]
                val cursor = scheme[TextStyle.COLOR_INDEX_CURSOR]
                return TerminalPalette(
                    background = background,
                    foreground = foreground,
                    cursor = cursor,
                    scheme = scheme.clone()
                )
            }
            return TerminalPalette(
                background = themeBackground,
                foreground = themeForeground,
                cursor = cursorColorForBackground(themeBackground)
            )
        }

        /**
         * Pick a cursor color visible on top of the given background, mirroring
         * {@link com.termux.terminal.TerminalColorScheme#setCursorColorForBackground()}.
         *
         * @param background The terminal background color
         * @return White on dark backgrounds, black on bright ones
         */
        fun cursorColorForBackground(background: Int): Int {
            val brightness = TerminalColors.getPerceivedBrightnessOfColor(background)
            return if (brightness < CURSOR_BRIGHTNESS_THRESHOLD) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
    }
}