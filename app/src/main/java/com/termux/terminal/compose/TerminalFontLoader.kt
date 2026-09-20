package com.termux.terminal.compose

import android.content.Context
import android.graphics.Typeface
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants

/**
 * Resolves the terminal font identifier (see [TerminalFontCatalog]) into a [Typeface].
 *
 * Falls back to [Typeface.MONOSPACE] whenever the requested font cannot be loaded (missing asset,
 * unreadable or corrupt custom file), so the terminal never renders with an invalid typeface.
 */
object TerminalFontLoader {

    private const val LOG_TAG = "TerminalFontLoader"

    /**
     * Resolve a terminal font identifier into a [Typeface].
     *
     * @param context The [Context] used to read bundled assets.
     * @param fontId The terminal font identifier. Empty selects the default monospace font,
     * {@code "fonts/<name>.ttf"} a bundled font, and [TerminalFontCatalog.CUSTOM_FONT_ID] the
     * custom {@code ~/.termux/font.ttf} file when it exists.
     * @return The resolved [Typeface], or [Typeface.MONOSPACE] when unknown or unloadable.
     */
    fun resolve(context: Context, fontId: String): Typeface {
        if (fontId.isEmpty()) return Typeface.MONOSPACE

        if (fontId == TerminalFontCatalog.CUSTOM_FONT_ID) {
            val fontFile = TermuxConstants.TERMUX_FONT_FILE
            if (fontFile.isFile) {
                try {
                    return Typeface.createFromFile(fontFile)
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(
                        LOG_TAG,
                        "Custom font ignored (unreadable file): " + fontFile.absolutePath,
                        e
                    )
                }
            }
            return Typeface.MONOSPACE
        }

        try {
            val typeface = Typeface.createFromAsset(context.assets, fontId)
            if (typeface != null) return typeface
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(
                LOG_TAG,
                "Bundled font ignored (invalid font): $fontId",
                e
            )
        }
        return Typeface.MONOSPACE
    }
}