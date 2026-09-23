package com.termux.terminal.compose

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.view.TerminalView

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
     * A maximal run of cells sharing style, cursor, selection membership and OSC 8
     * hyperlink.
     *
     * @param startColumn First grid column of the run
     * @param columnWidth Width of the run in grid columns (wide code points count 2)
     * @param startCharIndex Offset into [TerminalRow.mText] where the run text starts
     * @param charCount Number of Java chars of the run text (surrogates count 2, combining
     * chars included)
     * @param style The [TextStyle]-encoded style shared by the run
     * @param inCursor Whether the run holds the visible cursor cell
     * @param inSelection Whether the run is inside the text selection
     * @param hyperlinkIndex OSC 8 URI registry index for the run (0 = not a hyperlink)
     */
    data class TextRun(
        val startColumn: Int,
        val columnWidth: Int,
        val startCharIndex: Int,
        val charCount: Int,
        val style: Long,
        val inCursor: Boolean,
        val inSelection: Boolean,
        val hyperlinkIndex: Int = 0
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
     * A rectangular text selection in external (transcript-aware) row coordinates, mirroring
     * the legacy `mSelX1/mSelY1/mSelX2/mSelY2` of
     * {@link com.termux.view.textselection.TextSelectionCursorController}. Rows are external:
     * when the canvas is scrolled back by `topRow` rows, the top visible row is `topRow`.
     * Columns are grid-relative, `x2` inclusive.
     *
     * @param x1 First selected column
     * @param y1 First selected (external) row
     * @param x2 Last selected column (inclusive)
     * @param y2 Last selected (external) row
     */
    data class TextSelection(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int
    )

    /**
     * The selection column range of one line, mirroring the legacy render loop: the first
     * selected row starts at `selectionX1`, the last ends at `selectionX2`, and full middle
     * rows are selected entirely. Rows outside the selection yield `-1..-1`.
     *
     * @param selection The active selection, or null
     * @param row The external row to query
     * @param columns Number of grid columns of the line
     * @return The inclusive `(selX1, selX2)` for the row, or `(-1, -1)` when not selected
     */
    @JvmStatic
    fun selectionBoundsForRow(selection: TextSelection?, row: Int, columns: Int): Pair<Int, Int> {
        if (selection == null || row < selection.y1 || row > selection.y2) return -1 to -1
        val selX1 = if (row == selection.y1) selection.x1 else 0
        val selX2 = if (row == selection.y2) selection.x2 else columns - 1
        return selX1 to selX2
    }

    /**
     * Start a selection at a column/row of the buffer, expanding the cell to its word when it
     * is not whitespace, mirroring
     * {@link com.termux.view.textselection.TextSelectionCursorController#setInitialTextSelectionPosition}.
     *
     * @param screen The emulator screen buffer
     * @param column The tapped column
     * @param row The tapped (external) row
     * @param columns Number of grid columns
     * @return The word (or single cell) selection, or null when the cell is outside the buffer
     */
    @JvmStatic
    fun selectWord(screen: TerminalBuffer, column: Int, row: Int, columns: Int): TextSelection? {
        val cell = screen.getSelectedText(column, row, column, row) ?: return null
        var x1 = column
        var x2 = column
        if (cell != " ") {
            while (x1 > 0 && screen.getSelectedText(x1 - 1, row, x1 - 1, row)?.isNotEmpty() == true) {
                x1--
            }
            while (x2 < columns - 1 && screen.getSelectedText(x2 + 1, row, x2 + 1, row)?.isNotEmpty() == true) {
                x2++
            }
        }
        return TextSelection(x1, row, x2, row)
    }

    /**
     * Clamp a dragged selection handle endpoint to valid grid cells, mirroring
     * {@link com.termux.view.textselection.TextSelectionCursorController#updatePosition}:
     * columns are clamped to the grid, rows to `[-rowsInHistory, mRows - 1]`, the start handle
     * never crosses past the end one (and vice versa), and a wide glyph absorbs a drag landing
     * inside its second half ([validCurX], see [TerminalRow] wide cells).
     *
     * @param cx The dragged column in grid coordinates
     * @param cy The dragged (external) row
     * @param selection The current selection
     * @param isStart Whether the dragged handle is the start (left) one
     * @param mRows The number of screen rows
     * @param rowsInHistory The maximum scrolled-back history rows
     * (`screen.getActiveRows() - mRows`)
     * @param columns Number of grid columns
     * @param screen The emulator screen buffer used to correct wide-cell columns
     * @return The normalized selection
     */
    @JvmStatic
    fun clampSelectionHandle(
        cx: Int,
        cy: Int,
        selection: TextSelection,
        isStart: Boolean,
        mRows: Int,
        rowsInHistory: Int,
        columns: Int,
        screen: TerminalBuffer
    ): TextSelection {
        var x = cx
        var y = cy
        if (x < 0) x = 0
        if (y < -rowsInHistory) {
            y = -rowsInHistory
        } else if (y > mRows - 1) {
            y = mRows - 1
        }
        if (isStart) {
            if (y > selection.y2) y = selection.y2
            if (y == selection.y2 && x > selection.x2) x = selection.x2
        } else {
            if (y < selection.y1) y = selection.y1
            if (y == selection.y1 && x < selection.x1) x = selection.x1
        }
        x = validCurX(screen, y, x, columns)
        return if (isStart) selection.copy(x1 = x, y1 = y) else selection.copy(x2 = x, y2 = y)
    }

    /**
     * Whether a new emulator row shift (from the emulator scroll counter) should keep
     * or abort the selection, mirroring
     * {@link com.termux.view.TerminalView#onScreenUpdated}: while selecting, new output shifts
     * both the scroll offset and the selection up, unless the transcript end is reached, in
     * which case the selection is aborted and the scroll snaps to the bottom.
     *
     * @param selection The current selection, or null
     * @param scrollRows Current scroll offset in rows (0 or negative), the canvas `topRow`
     * @param rowShift Rows of new output, `emulator.getScrollCounter()`
     * @param transcriptRows Available transcript rows (`activeTranscriptRows`)
     * @param isAutoScrollDisabled Whether the emulator auto-scroll is disabled: at the
     * transcript end the offset pins at `-transcriptRows` instead of snapping to live
     * (legacy `onScreenUpdated` sets `mTopRow = -rowsInHistory` after stopping the mode)
     * @return The adjusted scroll offset plus selection, or a null selection to abort
     */
    @JvmStatic
    @JvmOverloads
    fun shiftSelectionForNewOutput(
        selection: TextSelection?,
        scrollRows: Int,
        rowShift: Int,
        transcriptRows: Int,
        isAutoScrollDisabled: Boolean = false
    ): Pair<Int, TextSelection?> {
        if (selection == null || rowShift <= 0) return scrollRows to selection
        val rowsInHistory = maxOf(transcriptRows, 0)
        if (-scrollRows + rowShift > rowsInHistory) {
            // End of history: abort the selection. With auto-scroll enabled the scroll snaps
            // to live (0); with auto-scroll disabled it pins at the oldest row like legacy
            // onScreenUpdated() sets mTopRow = -rowsInHistory after stopping the mode.
            return (if (isAutoScrollDisabled) -rowsInHistory else 0) to null
        }
        val shiftedSelection = selection.copy(
            y1 = selection.y1 - rowShift,
            y2 = selection.y2 - rowShift
        )
        return scrollRows - rowShift to shiftedSelection
    }

    /**
     * The canvas scroll offset after new output without an active selection, mirroring
     * {@link com.termux.view.TerminalView#onScreenUpdated}:
     * - With auto-scroll enabled, any screen update snaps the scroll back to live (legacy
     *   `mTopRow = 0`), so scrolled-back output always follows the newest line again.
     * - With auto-scroll disabled, the offset keeps its position relative to the transcript,
     *   shifted up by [rowShift], and pins at the transcript end (legacy `-rowsInHistory`).
     *
     * Selecting consumers should use [shiftSelectionForNewOutput] instead, which also shifts
     * the selection up and aborts it at the transcript end.
     *
     * @param scrollRows Current scroll offset in rows (0 or negative)
     * @param rowShift Rows of new output, `emulator.getScrollCounter()`
     * @param transcriptRows Available transcript rows (`activeTranscriptRows`)
     * @param isAutoScrollDisabled Whether the emulator auto-scroll is disabled, which keeps
     * the pinned transcript position instead of snapping to live
     * @return The new scroll offset, clamped to `[-transcriptRows, 0]`
     */
    @JvmStatic
    fun scrollOffsetForNewOutput(
        scrollRows: Int,
        rowShift: Int,
        transcriptRows: Int,
        isAutoScrollDisabled: Boolean
    ): Int {
        val rowsInHistory = maxOf(transcriptRows, 0)
        val clamped = scrollRows.coerceIn(-rowsInHistory, 0)
        if (!isAutoScrollDisabled) return 0
        return if (-clamped + rowShift > rowsInHistory) {
            -rowsInHistory
        } else {
            (clamped - rowShift).coerceIn(-rowsInHistory, 0)
        }
    }

    /** Snap a column into a valid cell when a wide glyph (e.g. a CJK char) absorbs it, so the
     * handle drag endpoint never lands on the unused second half of a wide cell. Mirror of
     * {@code TextSelectionCursorController.getValidCurX}. Columns are clamped to the grid. */
    @JvmStatic
    fun validCurX(screen: TerminalBuffer, cy: Int, cx: Int, columns: Int): Int {
        val line = screen.getSelectedText(0, cy, cx, cy)
        if (!line.isNullOrEmpty()) {
            var col = 0
            var i = 0
            val len = line.length
            while (i < len) {
                val ch1 = line[i]
                if (ch1 == '\u0000') break
                val wc = if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                    WcWidth.width(Character.toCodePoint(ch1, line[++i]))
                } else {
                    WcWidth.width(ch1.code)
                }
                val cend = col + wc
                if (cx > col && cx < cend) return cend.coerceAtMost(columns - 1)
                if (cend == col) return col.coerceAtMost(columns - 1)
                col = cend
                i++
            }
        }
        return cx.coerceIn(0, columns - 1)
    }

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
        var lastHyperlink = 0
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
            val hyperlink = line.getHyperlink(column)
            val mismatch = hasWidthMismatch(codePoint)

            if (!hasRun) {
                lastStyle = style
                lastInCursor = inCursor
                lastInSelection = inSelection
                lastHyperlink = hyperlink
                lastMismatch = mismatch
                runStartColumn = column
                runStartCharIndex = charIndex
                hasRun = true
            } else if (style != lastStyle || inCursor != lastInCursor ||
                inSelection != lastInSelection || hyperlink != lastHyperlink ||
                mismatch || lastMismatch || !enableLigatures
            ) {
                runs.add(
                    TextRun(
                        runStartColumn, column - runStartColumn,
                        runStartCharIndex, charIndex - runStartCharIndex,
                        lastStyle, lastInCursor, lastInSelection, lastHyperlink
                    )
                )
                lastStyle = style
                lastInCursor = inCursor
                lastInSelection = inSelection
                lastHyperlink = hyperlink
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
                    lastStyle, lastInCursor, lastInSelection, lastHyperlink
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
     * Derive the terminal grid from an available pixel size, mirroring
     * {@link com.termux.view.TerminalView#updateSize}: the grid is clamped to a minimum of
     * 4 columns and 4 rows like the legacy `Math.max(4, ...)`, so a very small canvas never
     * collapses the emulator to a single column/row.
     *
     * @param widthPx The available width in pixels
     * @param heightPx The available height in pixels
     * @param fontWidthPx The monospace glyph width in pixels (must be > 0)
     * @param lineSpacingPx Pixels per terminal row (must be > 0)
     * @param lineSpacingAndAscentPx [lineSpacingPx] plus the font ascent, the vertical pixel
     * offset of the first text baseline
     * @return The `(columns, rows)` grid
     */
    @JvmStatic
    fun gridSize(
        widthPx: Int,
        heightPx: Int,
        fontWidthPx: Float,
        lineSpacingPx: Int,
        lineSpacingAndAscentPx: Int
    ): Pair<Int, Int> {
        if (fontWidthPx <= 0f || lineSpacingPx <= 0) return 4 to 4
        val columns = (widthPx / fontWidthPx).toInt().coerceAtLeast(4)
        val rows = ((heightPx - lineSpacingAndAscentPx) / lineSpacingPx).coerceAtLeast(4)
        return columns to rows
    }

    /**
     * Whether a cursor blink rate in milliseconds is valid, mirroring
     * {@link TerminalView#setTerminalCursorBlinkerRate}: a wrong rate silently disables the
     * blinker. The canvas uses this instead of the legacy in-view blinker, which stays inert
     * at the default rate 0.
     *
     * @param blinkRateMs The rate read from the cursor-blink-rate property (0 = disabled)
     * @return Whether the rate is within
     * [TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN]..[TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX]
     */
    @JvmStatic
    fun isValidCursorBlinkRate(blinkRateMs: Int): Boolean =
        blinkRateMs in TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN..TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX

    /**
     * Fling velocity damping, mirroring the legacy `SCALE = 0.25f` in
     * {@link TerminalView}'s fling listener so a fast swipe decays within roughly the same
     * distance as the legacy view.
     */
    const val FLING_VELOCITY_SCALE = 0.25f

    /**
     * The fling animation range, mirroring the legacy `Scroller.fling` calls: transcript mode
     * flings the scroll offset within `[-transcriptRows, 0]`, mouse-tracking mode flings the
     * synthetic wheel value within `[-mRows / 2, mRows / 2]`.
     *
     * @param minRows The minimum animated position (negative for both modes)
     * @param maxRows The maximum animated position (0 for the transcript, positive for wheels)
     * @return The inclusive animatable range
     */
    @JvmStatic
    fun flingBounds(minRows: Int, maxRows: Int): IntRange = minRows..maxRows

    /**
     * Resolve the paint colors of a run from its style, mirroring the legacy color logic.
     *
     * @param style The [TextStyle]-encoded run style
     * @param paletteColors The emulator indexed colors
     * ([TextStyle.NUM_INDEXED_COLORS] entries)
     * @param defaultBackground The default background color (ARGB); only non-default
     * backgrounds are painted
     * @param emulatorReverseVideo Whether the emulator is in reverse-video mode
     * @param inSelection Whether the run is inside the text selection, which inverts the cell
     * colors the same way the legacy renderer does
     * @param inCursor Whether the run holds the visible cursor cell
     * @param cursorStyle One of the {@code TERMINAL_CURSOR_STYLE_*} constants of
     * [TerminalEmulator]
     * @return The resolved colors and effects
     */
    @JvmStatic
    @JvmOverloads
    fun resolveRunColors(
        style: Long,
        paletteColors: IntArray,
        defaultBackground: Int,
        emulatorReverseVideo: Boolean,
        inSelection: Boolean = false,
        inCursor: Boolean = false,
        cursorStyle: Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
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
        // cursor inverts the cell text the same way the legacy renderer does, and so does a
        // cell inside the selection (legacy drawTextRun receives `reverseVideo || invertCursor
        // || lastRunInsideSelection`).
        val invertCursorText = inCursor && cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        val reverseVideoHere = (emulatorReverseVideo || inSelection || invertCursorText) xor
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
