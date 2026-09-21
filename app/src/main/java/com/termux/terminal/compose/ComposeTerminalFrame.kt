package com.termux.terminal.compose

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/**
 * Pure (Android-free) model of one rendered terminal line for the experimental Compose Canvas
 * renderer (Fase 3.6 spike).
 *
 * Mirrors the run-grouping rules of {@link com.termux.view.TerminalRenderer}: consecutive cells
 * are merged into a single [TextRun] while style, cursor and selection membership stay constant.
 * The run additionally breaks on font width mismatches and — when ligatures are disabled — on
 * every code point, so the font ligature tables can never combine characters (same rationale as
 * the legacy renderer, which cannot disable GSUB {@code liga} on a {@code Paint} otherwise).
 *
 * Combining characters are absorbed into the preceding base code point run, wide code points
 * advance two columns, and surrogate pairs are decoded with [Character.toCodePoint].
 *
 * The input is the same [TerminalRow] buffer the legacy renderer consumes, so a zero-width base
 * code point behaves exactly as in the legacy loop.
 */
object ComposeTerminalFrame {

    private const val LOG_TAG = "ComposeTerminalFrame"

    /**
     * A maximal run of cells sharing style, cursor and selection membership.
     *
     * @param startColumn First grid column of the run
     * @param columnWidth Width of the run in grid columns (wide code points count 2)
     * @param startCharIndex Offset into [TerminalRow.mText] where the run text starts
     * @param charCount Number of Java chars of the run text (surrogates count 2, combining
     * chars included)
     * @param style The [TextStyle]-encoded style shared by the run
     * @param inCursor Whether the run holds the visible cursor cell
     * @param inSelection Whether the run is inside the text selection
     */
    data class TextRun(
        val startColumn: Int,
        val columnWidth: Int,
        val startCharIndex: Int,
        val charCount: Int,
        val style: Long,
        val inCursor: Boolean,
        val inSelection: Boolean
    )

    /**
     * Resolved ARGB colors and paint effects for a [TextRun], mirroring the legacy
     * {@code drawTextRun} color logic (bold-to-bright, reverse video, dim, invisible, cursor).
     *
     * @param foreColor Resolved foreground color (ARGB)
     * @param backColor Resolved background color (ARGB)
     * @param drawBackground Whether the background rect must be painted (non-default background)
     * @param cursorColor Cursor color (ARGB), or 0 when the run does not hold the cursor
     * @param effect The [TextStyle]-decoded effect bits (drives Paint flags in the canvas layer)
     * @param drawText Whether the text itself must be painted (false for invisible cells)
     */
    data class ResolvedRunColors(
        val foreColor: Int,
        val backColor: Int,
        val drawBackground: Boolean,
        val cursorColor: Int,
        val effect: Int,
        val drawText: Boolean
    )

    /**
     * Split one screen line into drawable runs.
     *
     * @param line The terminal row to split
     * @param columns Number of grid columns of the line
     * @param cursorX Cursor column for this row, or -1 when the cursor is not on it
     * @param selectionX1 First selected column of this row, or -1 when the row has no selection
     * @param selectionX2 Last selected column of this row (inclusive)
     * @param enableLigatures Whether ligature shaping is enabled; when false every code point
     * forms its own run
     * @param hasWidthMismatch Width-mismatch probe per code point (measured vs wcwidth); true
     * forces a run break, mirroring the legacy font-metrics check
     * @return The runs covering all [columns] columns, in order
     */
    @JvmStatic
    @JvmOverloads
    fun buildLineRuns(
        line: TerminalRow,
        columns: Int,
        cursorX: Int,
        selectionX1: Int,
        selectionX2: Int,
        enableLigatures: Boolean,
        hasWidthMismatch: (codePoint: Int) -> Boolean = { false }
    ): List<TextRun> {
        val runs = ArrayList<TextRun>()
        val text = line.mText
        val spaceUsed = line.spaceUsed

        var lastStyle = 0L
        var lastInCursor = false
        var lastInSelection = false
        var lastMismatch = false
        var runStartColumn = 0
        var runStartCharIndex = 0
        var hasRun = false

        var column = 0
        var charIndex = 0
        while (column < columns) {
            val charAtIndex = if (charIndex < text.size) text[charIndex] else ' '
            val charIsHighSurrogate = Character.isHighSurrogate(charAtIndex)
            val charsForCodePoint = if (charIsHighSurrogate) 2 else 1
            val codePoint = if (charIsHighSurrogate && charIndex + 1 < text.size) {
                Character.toCodePoint(charAtIndex, text[charIndex + 1])
            } else {
                charAtIndex.code
            }
            val codePointWcWidth = WcWidth.width(codePoint)
            val inCursor = cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1)
            val inSelection = column >= selectionX1 && column <= selectionX2
            val style = line.getStyle(column)
            val mismatch = hasWidthMismatch(codePoint)

            if (!hasRun) {
                lastStyle = style
                lastInCursor = inCursor
                lastInSelection = inSelection
                lastMismatch = mismatch
                runStartColumn = column
                runStartCharIndex = charIndex
                hasRun = true
            } else if (style != lastStyle || inCursor != lastInCursor ||
                inSelection != lastInSelection || mismatch || lastMismatch || !enableLigatures
            ) {
                runs.add(
                    TextRun(
                        runStartColumn, column - runStartColumn,
                        runStartCharIndex, charIndex - runStartCharIndex,
                        lastStyle, lastInCursor, lastInSelection
                    )
                )
                lastStyle = style
                lastInCursor = inCursor
                lastInSelection = inSelection
                lastMismatch = mismatch
                runStartColumn = column
                runStartCharIndex = charIndex
            }

            column += codePointWcWidth
            charIndex += charsForCodePoint
            while (charIndex < spaceUsed && charIndex < text.size &&
                WcWidth.width(text, charIndex) <= 0
            ) {
                // Eat combining chars so they belong to the last base code point run.
                charIndex += if (Character.isHighSurrogate(text[charIndex])) 2 else 1
            }
        }

        if (hasRun) {
            runs.add(
                TextRun(
                    runStartColumn, columns - runStartColumn,
                    runStartCharIndex, charIndex - runStartCharIndex,
                    lastStyle, lastInCursor, lastInSelection
                )
            )
        }
        return runs
    }

    /**
     * Clamp a canvas-owned scroll offset (rows, 0 = following output) to the available
     * transcript, mirroring the legacy `mTopRow` range `[-transcriptRows, 0]`.
     *
     * @param offsetRows The requested offset in rows (0 or negative)
     * @param transcriptRows The available transcript rows (`activeTranscriptRows`)
     * @return The clamped offset
     */
    @JvmStatic
    fun clampScrollOffset(offsetRows: Int, transcriptRows: Int): Int =
        offsetRows.coerceIn(-maxOf(transcriptRows, 0), 0)

    /**
     * Fold a vertical drag distance into whole rows, carrying the fractional remainder so
     * sub-row drags accumulate across events instead of being lost.
     *
     * @param remainderPx Leftover pixels from the previous drag event
     * @param dragPx Vertical drag distance in pixels (positive = finger moved down)
     * @param lineSpacingPx Pixels per terminal row (must be > 0)
     * @return The whole rows to move plus the new leftover pixels, signed like the legacy
     * `mScrollRemainder` carry (truncation toward zero, so up-drags stay exact). The caller
     * decides row semantics (legacy parity: a positive drag scrolls back toward older output).
     */
    @JvmStatic
    fun accumulateDragRows(remainderPx: Float, dragPx: Float, lineSpacingPx: Int): Pair<Int, Float> {
        if (lineSpacingPx <= 0) return 0 to remainderPx
        val total = remainderPx + dragPx
        // Truncate toward zero, matching the legacy `(int) (pixels / lineSpacing)` cast; an
        // up-drag then keeps a negative remainder instead of rounding to an extra row.
        val rows = (total / lineSpacingPx).toInt()
        return rows to (total - rows * lineSpacingPx)
    }

    /**
     * Apply a finger-drag delta to a canvas-owned scroll offset, with legacy direction parity
     * ([TerminalView.doScroll]: finger down moves toward older output, finger up toward live).
     *
     * @param offsetRows Current offset in rows (0 or negative)
     * @param dragRows Whole rows from [accumulateDragRows] (positive = finger moved down)
     * @param transcriptRows Available transcript rows (`activeTranscriptRows`)
     * @return The new clamped offset
     */
    @JvmStatic
    fun scrollByDrag(offsetRows: Int, dragRows: Int, transcriptRows: Int): Int =
        clampScrollOffset(offsetRows - dragRows, transcriptRows)

    /**
     * Resolve the paint colors of a run from its style, mirroring the legacy color logic.
     *
     * @param style The [TextStyle]-encoded run style
     * @param paletteColors The emulator indexed colors
     * ([TextStyle.NUM_INDEXED_COLORS] entries)
     * @param defaultBackground The default background color (ARGB); only non-default
     * backgrounds are painted
     * @param emulatorReverseVideo Whether the emulator is in reverse-video mode
     * @param inCursor Whether the run holds the visible cursor cell
     * @param cursorStyle One of the {@code TERMINAL_CURSOR_STYLE_*} constants of
     * [TerminalEmulator]
     * @return The resolved colors and effects
     */
    @JvmStatic
    fun resolveRunColors(
        style: Long,
        paletteColors: IntArray,
        defaultBackground: Int,
        emulatorReverseVideo: Boolean,
        inCursor: Boolean,
        cursorStyle: Int
    ): ResolvedRunColors {
        var foreColor = TextStyle.decodeForeColor(style)
        val effect = TextStyle.decodeEffect(style)
        var backColor = TextStyle.decodeBackColor(style)
        val bold = effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0

        if (foreColor and 0xff000000.toInt() != 0xff000000.toInt()) {
            // Let bold have bright colors if applicable (one of the first 8).
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8
            foreColor = paletteColors[foreColor]
        }

        if (backColor and 0xff000000.toInt() != 0xff000000.toInt()) {
            backColor = paletteColors[backColor]
        }

        // Reverse video here if _one and only one_ of the reverse flags are set. A block
        // cursor inverts the cell text the same way the legacy renderer does.
        val invertCursorText = inCursor && cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        val reverseVideoHere = (emulatorReverseVideo || invertCursorText) xor
            (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0)
        if (reverseVideoHere) {
            val tmp = foreColor
            foreColor = backColor
            backColor = tmp
        }

        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
            // Dim color handling used by libvte which in turn took it from xterm.
            val red = (0xFF and (foreColor shr 16)) * 2 / 3
            val green = (0xFF and (foreColor shr 8)) * 2 / 3
            val blue = (0xFF and foreColor) * 2 / 3
            foreColor = 0xFF000000.toInt() + (red shl 16) + (green shl 8) + blue
        }

        val cursorColor = if (inCursor) paletteColors[TextStyle.COLOR_INDEX_CURSOR] else 0
        return ResolvedRunColors(
            foreColor = foreColor,
            backColor = backColor,
            drawBackground = backColor != defaultBackground,
            cursorColor = cursorColor,
            effect = effect,
            drawText = effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0
        )
    }
}
