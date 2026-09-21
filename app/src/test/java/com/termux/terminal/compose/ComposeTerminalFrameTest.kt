package com.termux.terminal.compose

import org.junit.Assert.assertEquals
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
}