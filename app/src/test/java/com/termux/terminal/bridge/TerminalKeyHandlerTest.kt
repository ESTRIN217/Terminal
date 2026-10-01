package com.termux.terminal.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the key-name to escape-sequence mapping with cursor/keypad application-mode
 * flags, mirroring the legacy `TerminalView.handleKeyCode` which forwards the emulator's
 * DECCKM/DECKPAM mode to {@link com.termux.terminal.KeyHandler}.
 */
class TerminalKeyHandlerTest {

    @Test
    fun getKeySequence_arrowsRespectCursorAppMode() {
        // CSI forms by default.
        assertEquals("\u001B[A", TerminalKeyHandler.getKeySequence("UP"))
        assertEquals("\u001B[B", TerminalKeyHandler.getKeySequence("DOWN"))
        assertEquals("\u001B[C", TerminalKeyHandler.getKeySequence("RIGHT"))
        assertEquals("\u001B[D", TerminalKeyHandler.getKeySequence("LEFT"))
        // Application (SS3) forms when the cursor application mode is set (DECCKM).
        assertEquals("\u001BOA", TerminalKeyHandler.getKeySequence("UP", cursorAppMode = true))
        assertEquals("\u001BOB", TerminalKeyHandler.getKeySequence("DOWN", cursorAppMode = true))
        assertEquals("\u001BOC", TerminalKeyHandler.getKeySequence("RIGHT", cursorAppMode = true))
        assertEquals("\u001BOD", TerminalKeyHandler.getKeySequence("LEFT", cursorAppMode = true))
    }

    @Test
    fun getKeySequence_appModeIgnoredByTransformWithModifiers() {
        // With modifiers KeyHandler emits the transformed modifier form and does not look at
        // the application-mode flag (legacy handleKeyCode parity).
        assertEquals("\u001B[1;2A", TerminalKeyHandler.getKeySequence("UP", shiftActive = true))
        assertEquals(
            "\u001B[1;2A",
            TerminalKeyHandler.getKeySequence("UP", shiftActive = true, cursorAppMode = true)
        )
        assertEquals("\u001B[1;5A", TerminalKeyHandler.getKeySequence("UP", ctrlActive = true))
    }

    @Test
    fun getKeySequence_otherKeysUnaffected() {
        assertEquals("\u0001", TerminalKeyHandler.getKeySequence("A", ctrlActive = true))
        assertEquals("\r", TerminalKeyHandler.getKeySequence("ENTER"))
        assertEquals("\u001B[D", TerminalKeyHandler.getKeySequence("LEFT"))
        assertEquals(
            "\u001B[D",
            TerminalKeyHandler.getKeySequence("LEFT", keypadAppMode = true)
        )
    }

    @Test
    fun parseMacro_modifiersApplyToTheNextKeyOnly() {
        val steps = TerminalKeyHandler.parseMacro("CTRL ALT f g")
        assertEquals(2, steps.size)
        assertEquals("f", steps[0].key)
        assertTrue(steps[0].ctrlActive)
        assertTrue(steps[0].altActive)
        assertFalse(steps[0].shiftActive)
        assertEquals("g", steps[1].key)
        assertFalse(steps[1].ctrlActive)
        assertFalse(steps[1].altActive)
    }

    @Test
    fun parseMacro_singleStepAndFnIsIgnored() {
        val steps = TerminalKeyHandler.parseMacro("FN UP")
        assertEquals(1, steps.size)
        assertEquals("UP", steps[0].key)
        assertFalse(steps[0].ctrlActive)
        assertFalse(steps[0].altActive)
        assertFalse(steps[0].shiftActive)
    }

    @Test
    fun parseMacro_blankMacroHasNoSteps() {
        assertTrue(TerminalKeyHandler.parseMacro("").isEmpty())
    }

    @Test
    fun macroStepProducesTheSameSequenceAsADirectSend() {
        val step = TerminalKeyHandler.parseMacro("CTRL c").single()
        assertEquals(
            TerminalKeyHandler.getKeySequence("c", ctrlActive = true),
            TerminalKeyHandler.getKeySequence(step.key, step.ctrlActive)
        )
    }
}
