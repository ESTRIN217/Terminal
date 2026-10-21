package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [PastePolicy] (spec 009, RF-4): the clipboard is cleared in
 * cut mode only when at least one source moved, and a paste where nothing
 * moved (missing sources, unreadable targets) is reported as a failure
 * instead of a success with count 0.
 *
 * <p>The previous behavior cleared the cut clipboard unconditionally and had
 * no failure branch at all: the UI showed "N pasted" with N=0. Both cases are
 * pinned here as regression contracts for the RF-4 fix.
 *
 * @param movedCount How many sources completed the move/copy.
 * @param total How many sources the clipboard held.
 * @param isCut Whether the clipboard operation is CUT (copy never clears).
 */
class PastePolicyTest {

    @Test
    fun cutWithAllSourcesMovedClearsTheClipboard() {
        assertEquals(
            PasteOutcome(clearClipboard = true, allFailed = false),
            PastePolicy.outcome(movedCount = 2, total = 2, isCut = true)
        )
    }

    @Test
    fun cutWithNothingMovedKeepsTheClipboardAndSignalsFailure() {
        assertEquals(
            PasteOutcome(clearClipboard = false, allFailed = true),
            PastePolicy.outcome(movedCount = 0, total = 3, isCut = true)
        )
    }

    @Test
    fun cutWithPartialMoveStillClearsTheClipboard() {
        // Moved sources are gone from their origin: keeping half the clipboard
        // would re-paste already-moved entries again.
        assertEquals(
            PasteOutcome(clearClipboard = true, allFailed = false),
            PastePolicy.outcome(movedCount = 1, total = 2, isCut = true)
        )
    }

    @Test
    fun copyNeverClearsTheClipboard() {
        assertEquals(
            PasteOutcome(clearClipboard = false, allFailed = false),
            PastePolicy.outcome(movedCount = 2, total = 2, isCut = false)
        )
        // A fully failed copy keeps the clipboard too (retrying is still valid).
        assertEquals(
            PasteOutcome(clearClipboard = false, allFailed = true),
            PastePolicy.outcome(movedCount = 0, total = 1, isCut = false)
        )
    }

    @Test
    fun emptyClipboardIsNeitherFailureNorClearing() {
        // Guarded upstream by hasClipboard, but the policy stays total.
        val outcome = PastePolicy.outcome(movedCount = 0, total = 0, isCut = true)
        assertFalse(outcome.clearClipboard)
        assertFalse(outcome.allFailed)
    }
}
