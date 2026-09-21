package com.termux.terminal.compose

import com.termux.terminal.TerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the scroll math shared by the native Compose canvas. Legacy
 * [com.termux.view.TerminalView.doScroll] parity is pinned here: a finger drag down
 * (positive pixel delta) scrolls back toward older output, a drag up toward live.
 */
class ComposeTerminalFrameTest {

    @Test
    fun scrollByDrag_dragDown_goesFartherBack() {
        assertEquals(-3, ComposeTerminalFrame.scrollByDrag(0, 3, 10))
        assertEquals(-5, ComposeTerminalFrame.scrollByDrag(-2, 3, 10))
    }

    @Test
    fun scrollByDrag_dragUp_returnsTowardLive() {
        assertEquals(0, ComposeTerminalFrame.scrollByDrag(-3, -3, 10))
        assertEquals(-1, ComposeTerminalFrame.scrollByDrag(-4, -3, 10))
        assertEquals(-5, ComposeTerminalFrame.scrollByDrag(-8, -3, 10))
    }

    @Test
    fun scrollByDrag_clampsToTranscript() {
        assertEquals(-10, ComposeTerminalFrame.scrollByDrag(0, 12, 10))
        assertEquals(-10, ComposeTerminalFrame.scrollByDrag(-12, 12, 10))
        assertEquals(0, ComposeTerminalFrame.scrollByDrag(0, 0, 0))
        assertEquals(0, ComposeTerminalFrame.scrollByDrag(-1, -2, 10))
    }

    @Test
    fun accumulateDragRows_carriesSubRowRemainder() {
        val (rows, remainder) = ComposeTerminalFrame.accumulateDragRows(0f, 25f, 10)
        assertEquals(2, rows)
        assertEquals(5f, remainder, 0f)

        val (next, nextRemainder) = ComposeTerminalFrame.accumulateDragRows(5f, 4f, 10)
        assertEquals(0, next)
        assertEquals(9f, nextRemainder, 0f)

        val (whole, wholeRemainder) = ComposeTerminalFrame.accumulateDragRows(9f, 1f, 10)
        assertEquals(1, whole)
        assertEquals(0f, wholeRemainder, 0f)
    }

    @Test
    fun accumulateDragRows_dragUpFoldsNegative() {
        val (rows, remainder) = ComposeTerminalFrame.accumulateDragRows(0f, -25f, 10)
        assertEquals(-2, rows)
        assertEquals(-5f, remainder, 0f)
    }

    @Test
    fun isValidCursorBlinkRate_acceptsRange() {
        assertTrue(ComposeTerminalFrame.isValidCursorBlinkRate(100))
        assertTrue(ComposeTerminalFrame.isValidCursorBlinkRate(2000))
        assertTrue(ComposeTerminalFrame.isValidCursorBlinkRate(500))
    }

    @Test
    fun isValidCursorBlinkRate_rejectsDisabledAndOutOfRange() {
        assertFalse(ComposeTerminalFrame.isValidCursorBlinkRate(0))
        assertFalse(ComposeTerminalFrame.isValidCursorBlinkRate(99))
        assertFalse(ComposeTerminalFrame.isValidCursorBlinkRate(2001))
        assertFalse(ComposeTerminalFrame.isValidCursorBlinkRate(-100))
    }

    @Test
    fun selectionBoundsForRow_honorsFirstAndLastRow() {
        val selection = ComposeTerminalFrame.TextSelection(x1 = 3, y1 = -2, x2 = 7, y2 = 0)
        // First selected row runs from x1 to the line end, last from 0 to x2, middle whole.
        assertEquals(3 to 11, ComposeTerminalFrame.selectionBoundsForRow(selection, -2, 12))
        assertEquals(0 to 11, ComposeTerminalFrame.selectionBoundsForRow(selection, -1, 12))
        assertEquals(0 to 7, ComposeTerminalFrame.selectionBoundsForRow(selection, 0, 12))
        // Rows outside the selection and a null selection yield no selection.
        assertEquals(-1 to -1, ComposeTerminalFrame.selectionBoundsForRow(selection, -3, 12))
        assertEquals(-1 to -1, ComposeTerminalFrame.selectionBoundsForRow(selection, 1, 12))
        assertEquals(-1 to -1, ComposeTerminalFrame.selectionBoundsForRow(null, 0, 12))
    }

    @Test
    fun selectWord_whitespaceCellStaysSingle() {
        val screen = TerminalBuffer(6, 6, 6)
        screen.setChar(0, 0, 'a'.code, 0)
        // A whitespace cell is selected alone (legacy setInitialTextSelectionPosition parity).
        assertEquals(
            ComposeTerminalFrame.TextSelection(5, 0, 5, 0),
            ComposeTerminalFrame.selectWord(screen, 5, 0, 6)
        )
    }

    @Test
    fun selectWord_nonWhitespaceExpands() {
        val screen = TerminalBuffer(6, 6, 6)
        screen.setChar(0, 0, 'l'.code, 0)
        screen.setChar(1, 0, 's'.code, 0)
        // Legacy parity: a non-space tap expands while neighbor cells read back non-empty
        // (getSelectedText returns "" past the used text, trimming trailing spaces).
        assertEquals(
            ComposeTerminalFrame.TextSelection(0, 0, 1, 0),
            ComposeTerminalFrame.selectWord(screen, 1, 0, 6)
        )
    }

    @Test
    fun clampSelectionHandle_clampsRowsAndOrder() {
        val screen = TerminalBuffer(6, 6, 6)
        val selection = ComposeTerminalFrame.TextSelection(1, 0, 4, 0)

        // Start handle dragged past the end handle clamps onto it.
        assertEquals(
            ComposeTerminalFrame.TextSelection(4, 0, 4, 0),
            ComposeTerminalFrame.clampSelectionHandle(
                6, 0, selection, isStart = true, mRows = 6, rowsInHistory = 0, columns = 6,
                screen = screen
            )
        )
        // End handle dragged before the start handle clamps onto it.
        assertEquals(
            ComposeTerminalFrame.TextSelection(1, 0, 1, 0),
            ComposeTerminalFrame.clampSelectionHandle(
                -1, 0, selection, isStart = false, mRows = 6, rowsInHistory = 0, columns = 6,
                screen = screen
            )
        )
        // Rows clamp into [-rowsInHistory, mRows - 1].
        assertEquals(
            ComposeTerminalFrame.TextSelection(1, -2, 4, 0),
            ComposeTerminalFrame.clampSelectionHandle(
                1, -9, selection, isStart = true, mRows = 6, rowsInHistory = 2, columns = 6,
                screen = screen
            )
        )
    }

    @Test
    fun validCurX_snapsWideCharSecondHalf() {
        val screen = TerminalBuffer(6, 6, 6)
        screen.setChar(1, 0, '\u4E2D'.code, 0)
        // Dropping inside the second half of a wide glyph lands past it (legacy getValidCurX).
        assertEquals(3, ComposeTerminalFrame.validCurX(screen, 0, 2, 6))
        // Dropping on the wide glyph itself keeps the same column.
        assertEquals(1, ComposeTerminalFrame.validCurX(screen, 0, 1, 6))
    }

    @Test
    fun shiftSelectionForNewOutput_shiftsAndAborts() {
        val selection = ComposeTerminalFrame.TextSelection(1, -2, 4, -1)

        // New output scrolls both the offset and the selection up.
        assertEquals(
            -5 to ComposeTerminalFrame.TextSelection(1, -4, 4, -3),
            ComposeTerminalFrame.shiftSelectionForNewOutput(selection, -3, 2, 10)
        )
        // End of history: abort the selection and snap the scroll to live.
        assertEquals(
            0 to null,
            ComposeTerminalFrame.shiftSelectionForNewOutput(selection, -3, 4, 5)
        )
        // No shift or no selection is a no-op.
        assertEquals(
            -3 to selection,
            ComposeTerminalFrame.shiftSelectionForNewOutput(selection, -3, 0, 10)
        )
        assertEquals(
            -3 to null,
            ComposeTerminalFrame.shiftSelectionForNewOutput(null, -3, 2, 10)
        )
    }

    @Test
    fun flingBounds_producesInclusiveRange() {
        assertEquals(-4..4, ComposeTerminalFrame.flingBounds(-4, 4))
        assertEquals(-10..0, ComposeTerminalFrame.flingBounds(-10, 0))
    }
}