package com.estrin217.filemanager.compose

import com.termux.shared.termux.TermuxConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [EditorPolicy.chooseEditor] (spec 009, RF-2): "Abrir con ›
 * Rootfs" runs the first *available* editor of the given order (nano falls
 * back to vim); when none of the candidates is available there is no editor
 * and the caller reports the snack-bar warning instead of opening a session.
 *
 * <p>The policy stays pure: availability is a boolean the caller derives from
 * the rootfs binary paths (canExecute), never probed inside the policy.
 */
class EditorPolicyTest {

    private fun nano(available: Boolean) = EditorCandidate("nano", available)
    private fun vim(available: Boolean) = EditorCandidate("vim", available)

    @Test
    fun firstAvailableEditorWins() {
        assertEquals(nano(true), EditorPolicy.chooseEditor(listOf(nano(true), vim(true))))
        assertEquals(nano(true), EditorPolicy.chooseEditor(listOf(nano(true), vim(false))))
    }

    @Test
    fun fallsBackToVimWhenNanoIsMissing() {
        assertEquals(vim(true), EditorPolicy.chooseEditor(listOf(nano(false), vim(true))))
    }

    @Test
    fun noAvailableEditorYieldsNull() {
        assertNull(EditorPolicy.chooseEditor(listOf(nano(false), vim(false))))
        assertNull(EditorPolicy.chooseEditor(emptyList()))
    }

    @Test
    fun respectsTheGivenOrderBeyondTheDefaultPair() {
        // The order is the caller's: whatever the host wires is honored.
        assertEquals(vim(true), EditorPolicy.chooseEditor(listOf(vim(true), nano(true))))
    }

    @Test
    fun debianCandidatesIncludeNvim() {
        // Spec 009 RF-2 amendment (2026-10-29): `apt install neovim` provides
        // usr/bin/nvim, which both consumers (FileManagerScreen pre-check and
        // TermuxComposeActivity.openFileInEditor) must detect via the shared
        // candidate list. Nano keeps its first place from the original contract.
        assertTrue(TermuxConstants.DEBIAN_EDITOR_CANDIDATES.contains("nvim"))
        assertEquals("nano", TermuxConstants.DEBIAN_EDITOR_CANDIDATES.first())
    }
}
