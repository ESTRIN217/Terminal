package com.termux.terminal.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TermuxUiState.useNativeCanvasRenderer], the policy that decides whether a
 * terminal pane is painted by the experimental Compose canvas or by the legacy
 * [com.termux.view.TerminalView] host.
 */
class PaneRendererPolicyTest {

    private fun state(
        useNativeRenderer: Boolean,
        split: SplitState? = null
    ): TermuxUiState = TermuxUiState(
        sessions = listOf(
            TermuxSessionUiModel.FileManager(id = "a", name = "a"),
            TermuxSessionUiModel.FileManager(id = "b", name = "b")
        ),
        useNativeRenderer = useNativeRenderer,
        split = split
    )

    @Test
    fun flagOn_singlePane_usesCanvas() {
        assertTrue(state(useNativeRenderer = true).useNativeCanvasRenderer)
    }

    @Test
    fun flagOn_split_fallsBackToLegacyView() {
        val s = state(useNativeRenderer = true, split = SplitState("a", "b"))

        assertFalse(s.useNativeCanvasRenderer)
    }

    @Test
    fun flagOff_split_staysOnLegacyView() {
        val s = state(useNativeRenderer = false, split = SplitState("a", "b"))

        assertFalse(s.useNativeCanvasRenderer)
    }

    @Test
    fun flagOff_singlePane_staysOnLegacyView() {
        assertFalse(state(useNativeRenderer = false).useNativeCanvasRenderer)
    }
}
