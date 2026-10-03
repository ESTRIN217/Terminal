package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the two-pane split navigation logic: activating (focusing) a session never
 * moves a pane from its position, third sessions replace the focused pane, and stale panes
 * are dropped by the sanitizer.
 */
class TermuxViewModelSplitTest {

    private fun fm(id: String): TermuxSessionUiModel =
        TermuxSessionUiModel.FileManager(id = id, name = "Session $id")

    private fun state(
        sessionIds: List<String>,
        activeIndex: Int = 0,
        split: SplitState? = null
    ): TermuxUiState =
        TermuxUiState(
            sessions = sessionIds.map(::fm),
            activeSessionIndex = activeIndex,
            split = split
        )

    @Test
    fun activateSession_toOtherPane_keepsPositions() {
        val split = SplitState(paneOneId = "a", paneTwoId = "b")
        val s = state(listOf("a", "b", "c"), activeIndex = 0, split = split)

        val result = activateSession(s, 1)

        assertEquals(1, result.activeSessionIndex)
        assertEquals(split, result.split)
    }

    @Test
    fun activateSession_thirdSession_replacesFocusedPane() {
        val s = state(listOf("a", "b", "c"), activeIndex = 0, split = SplitState("a", "b"))

        val result = activateSession(s, 2)

        assertEquals(2, result.activeSessionIndex)
        assertEquals("c", result.split?.paneOneId)
        assertEquals("b", result.split?.paneTwoId)
    }

    @Test
    fun activateSession_secondPaneFocused_replacesRightPane() {
        val s = state(listOf("a", "b", "c"), activeIndex = 1, split = SplitState("a", "b"))

        val result = activateSession(s, 2)

        assertEquals(2, result.activeSessionIndex)
        assertEquals("a", result.split?.paneOneId)
        assertEquals("c", result.split?.paneTwoId)
    }

    @Test
    fun activateSession_outOfRange_isNoOp() {
        val s = state(listOf("a", "b"), activeIndex = 0, split = SplitState("a", "b"))

        val result = activateSession(s, 5)

        assertEquals(s, result)
    }

    @Test
    fun activateSession_withoutSplit_switchesSinglePane() {
        val s = state(listOf("a", "b", "c"), activeIndex = 0)

        val result = activateSession(s, 2)

        assertEquals(2, result.activeSessionIndex)
        assertNull(result.split)
    }

    @Test
    fun sanitizeSplit_clearsWhenPaneSessionRemoved() {
        val s = state(listOf("a", "b"), activeIndex = 0, split = SplitState("a", "b"))

        val withoutPane = s.copy(sessions = listOf(fm("a")))
        val result = sanitizeSplit(withoutPane)

        assertNull(result.split)
    }

    @Test
    fun sanitizeSplit_dropsMissingPaneAndFocusesRemaining() {
        val s = state(listOf("a", "b"), activeIndex = 0, split = SplitState("a", "b"))

        val withoutLeft = s.copy(sessions = listOf(fm("b")))
        val result = sanitizeSplit(withoutLeft)

        assertNull(result.split)
        assertEquals("b", result.activeSessionModel?.id)
    }

    @Test
    fun sanitizeSplit_restoresFocusWhenActiveNotVisible() {
        val s = state(listOf("a", "b", "c"), activeIndex = 2, split = SplitState("a", "b"))

        val result = sanitizeSplit(s)

        assertTrue(result.isSplitActive)
        assertEquals("a", result.activeSessionModel?.id)
    }

    @Test
    fun sanitizeSplit_keepsValidSplit() {
        val s = state(listOf("a", "b", "c"), activeIndex = 0, split = SplitState("a", "b"))

        val result = sanitizeSplit(s)

        assertEquals(SplitState("a", "b"), result.split)
        assertEquals("a", result.activeSessionModel?.id)
    }

    @Test
    fun sanitizeSplit_withNoSplit_isNoOp() {
        val s = state(listOf("a", "b"), activeIndex = 0)

        val result = sanitizeSplit(s)

        assertEquals(s, result)
    }
}