package com.termux.terminal.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure classification/expansion of the Compose extra keys bar for the X11 tab
 * (spec 016, RF-03). Mirrors lorie's `TermuxX11ExtraKeys` semantics.
 */
class X11ExtraKeysTest {

    @Test
    fun parse_controlKeyNoSpecial() {
        val result = X11ExtraKeys.parse("ESC", isMacro = false)
        assertNull(result.special)
        assertEquals(1, result.presses.size)
        val step = result.presses[0]
        assertEquals("ESC", step.key)
        assertFalse(step.ctrlActive)
        assertFalse(step.altActive)
        assertFalse(step.shiftActive)
    }

    @Test
    fun parse_macroExpandsModifiersPerKey() {
        // Same semantics as TerminalKeyHandler.parseMacro: CTRL/ALT/SHIFT tokens
        // apply to the next key and then clear.
        val result = X11ExtraKeys.parse("CTRL ALT f d", isMacro = true)
        assertNull(result.special)
        val presses = result.presses
        assertEquals(2, presses.size)
        assertEquals("f", presses[0].key)
        assertTrue(presses[0].ctrlActive)
        assertTrue(presses[0].altActive)
        assertFalse(presses[0].shiftActive)
        assertEquals("d", presses[1].key)
        assertFalse(presses[1].ctrlActive)
        assertFalse(presses[1].altActive)
        assertFalse(presses[1].shiftActive)
    }

    @Test
    fun parse_stickyPrefixMergedIntoSingleKey() {
        val result = X11ExtraKeys.parse("c", isMacro = false, stickyModifiers = setOf("CTRL"))
        assertNull(result.special)
        assertEquals(1, result.presses.size)
        val step = result.presses[0]
        assertEquals("c", step.key)
        assertTrue(step.ctrlActive)
        assertFalse(step.altActive)
    }

    @Test
    fun parse_stickyPrefixOnMacro() {
        val result = X11ExtraKeys.parse("f d", isMacro = true, stickyModifiers = setOf("CTRL", "SHIFT"))
        assertEquals(2, result.presses.size)
        assertTrue(result.presses[0].ctrlActive)
        assertTrue(result.presses[0].shiftActive)
        assertEquals("d", result.presses[1].key)
        assertFalse(result.presses[1].ctrlActive)
    }

    @Test
    fun parse_specialsDetected() {
        val specialNames = listOf("KEYBOARD", "DRAWER", "PREFERENCES", "EXIT",
            "PASTE", "MOUSE_HELPER", "STYLUS_HELPER", "ZOOM_IN", "ZOOM_OUT", "ZOOM_RESET")
        for (name in specialNames) {
            val result = X11ExtraKeys.parse(name, isMacro = false)
            assertNotNull("missing special: $name", result.special)
            assertEquals(name, result.special!!.key)
            assertTrue("specials carry no presses: $name", result.presses.isEmpty())
        }
    }

    @Test
    fun parse_specialIgnoredInsideExpandedMacro() {
        // A multi-key macro expands; only a lone special key is classified.
        val result = X11ExtraKeys.parse("CTRL f d", isMacro = true)
        assertNull(result.special)
        assertEquals(2, result.presses.size)
    }

    @Test
    fun parse_specialWithStickyModifiersIsStillSpecial() {
        // Upstream specials (KEYBOARD/PASTE/...) take no modifiers.
        val result = X11ExtraKeys.parse("PASTE", isMacro = false, stickyModifiers = setOf("CTRL"))
        assertNotNull(result.special)
        assertEquals("PASTE", result.special!!.key)
        assertTrue(result.presses.isEmpty())
    }

    @Test
    fun parse_unknownTextKeyPassesThrough() {
        // Bar text keys (single chars or short names) pass through untouched.
        val result = X11ExtraKeys.parse("-", isMacro = false)
        assertNull(result.special)
        assertEquals("-", result.presses[0].key)
    }
}
