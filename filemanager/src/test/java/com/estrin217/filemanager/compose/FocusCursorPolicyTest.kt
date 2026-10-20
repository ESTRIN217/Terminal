package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit tests for the pure keyboard-cursor rules in
 * [FocusCursorPolicy]; no Android runtime needed (constitution principle 3).
 */
class FocusCursorPolicyTest {

    // region normalizedIndex (cursor after a listing is published)

    @Test
    fun directoryChangeClearsTheCursor() {
        assertEquals(
            -1,
            FocusCursorPolicy.normalizedIndex(
                previous = 9, previousPath = "/a", listedPath = "/b", size = 30
            )
        )
    }

    @Test
    fun unsetCursorStaysUnsetOnSameDirectoryRelist() {
        assertEquals(
            -1,
            FocusCursorPolicy.normalizedIndex(
                previous = -1, previousPath = "/a", listedPath = "/a", size = 20
            )
        )
    }

    @Test
    fun sameDirectoryKeepsCursorWhenItStillFits() {
        assertEquals(
            3,
            FocusCursorPolicy.normalizedIndex(
                previous = 3, previousPath = "/a", listedPath = "/a", size = 10
            )
        )
    }

    @Test
    fun sameDirectoryClampsCursorToLastIndexWhenListShrank() {
        assertEquals(
            4,
            FocusCursorPolicy.normalizedIndex(
                previous = 9, previousPath = "/a", listedPath = "/a", size = 5
            )
        )
    }

    @Test
    fun emptyListingAlwaysUnsetsTheCursor() {
        assertEquals(
            -1,
            FocusCursorPolicy.normalizedIndex(
                previous = 3, previousPath = "/a", listedPath = "/a", size = 0
            )
        )
    }

    // endregion

    // region movedIndex (cursor after a keyboard step)

    @Test
    fun firstDownFromUnsetCursorLandsOnRow1() {
        assertEquals(0, FocusCursorPolicy.movedIndex(current = -1, delta = 1, size = 30))
    }

    @Test
    fun firstPageDownFromUnsetCursorLandsOnIndexedRow10() {
        assertEquals(9, FocusCursorPolicy.movedIndex(current = -1, delta = 10, size = 30))
    }

    @Test
    fun upFromUnsetCursorAlsoLandsOnRow1() {
        assertEquals(0, FocusCursorPolicy.movedIndex(current = -1, delta = -1, size = 30))
    }

    @Test
    fun moveClampsToLastIndex() {
        assertEquals(9, FocusCursorPolicy.movedIndex(current = 8, delta = 5, size = 10))
    }

    @Test
    fun moveClampsToFirstIndex() {
        assertEquals(0, FocusCursorPolicy.movedIndex(current = 1, delta = -5, size = 10))
    }

    @Test
    fun moveOnEmptyListIsANoOp() {
        assertNull(FocusCursorPolicy.movedIndex(current = -1, delta = 1, size = 0))
    }

    // endregion
}
