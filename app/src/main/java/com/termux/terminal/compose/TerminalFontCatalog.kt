package com.termux.terminal.compose

/**
 * Catalog of terminal fonts selectable in the app.
 *
 * The bundled fonts are a curated subset of the {@code termux-styling} font collection, copied
 * into {@code app/src/main/assets/fonts/}. Their {@code hasLigatures} flag describes whether the
 * OpenType font carries ligature tables (GSUB {@code liga}/{@code clig}): only then does the
 * "Ligaduras" setting have a visible effect.
 */
object TerminalFontCatalog {

    /** A selectable terminal font entry. */
    data class FontEntry(
        /** Preference value stored in {@code KEY_TERMINAL_FONT}. Empty means the default font. */
        val id: String,
        /** Asset path within the app assets, or {@code null} for the default/custom font. */
        val assetPath: String?,
        /** Resource id of the display name. */
        val labelRes: Int,
        /** Whether the font carries OpenType ligature tables. */
        val hasLigatures: Boolean
    )

    /** The palette of bundled fonts, in the order they appear in the settings selector. */
    val bundledFonts: List<FontEntry> = listOf(
        FontEntry("fonts/Fira-Code.ttf", "fonts/Fira-Code.ttf", com.termux.R.string.font_fira_code, true),
        FontEntry("fonts/CascadiaCode.ttf", "fonts/CascadiaCode.ttf", com.termux.R.string.font_cascadia_code, true),
        FontEntry("fonts/JetBrains-Mono.ttf", "fonts/JetBrains-Mono.ttf", com.termux.R.string.font_jetbrains_mono, false),
        FontEntry("fonts/D2-Coding.ttf", "fonts/D2-Coding.ttf", com.termux.R.string.font_d2_coding, true),
        FontEntry("fonts/Hack.ttf", "fonts/Hack.ttf", com.termux.R.string.font_hack, false)
    )

    /** Identifier used to reference the custom {@code ~/.termux/font.ttf} file. */
    const val CUSTOM_FONT_ID: String = "custom"

    /**
     * Whether a font actually supports ligatures.
     *
     * @param fontId The terminal font identifier.
     * @return {@code true} if the font has ligature tables (bundled fonts with ligatures), or
     * {@code false} for the default, custom and bundled non-ligature fonts.
     */
    fun supportsLigatures(fontId: String): Boolean =
        bundledFonts.any { it.id == fontId && it.hasLigatures }
}