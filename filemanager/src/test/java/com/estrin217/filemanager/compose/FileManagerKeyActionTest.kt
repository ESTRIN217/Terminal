package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure extra-key/hardware-key mapping in
 * [FileManagerKeyAction].
 */
class FileManagerKeyActionTest {

    @Test
    fun arrowKeysNavigate() {
        assertEquals(FileManagerKeyAction.FOCUS_UP, FileManagerKeyAction.map("UP"))
        assertEquals(FileManagerKeyAction.FOCUS_DOWN, FileManagerKeyAction.map("DOWN"))
    }

    @Test
    fun homeEndAndPages() {
        assertEquals(FileManagerKeyAction.FOCUS_HOME, FileManagerKeyAction.map("HOME"))
        assertEquals(FileManagerKeyAction.FOCUS_END, FileManagerKeyAction.map("END"))
        assertEquals(FileManagerKeyAction.PAGE_UP, FileManagerKeyAction.map("PGUP"))
        assertEquals(FileManagerKeyAction.PAGE_DOWN, FileManagerKeyAction.map("PGDN"))
    }

    @Test
    fun openBackAndSelection() {
        assertEquals(FileManagerKeyAction.OPEN, FileManagerKeyAction.map("ENTER"))
        assertEquals(FileManagerKeyAction.OPEN, FileManagerKeyAction.map("RIGHT"))
        assertEquals(FileManagerKeyAction.BACK, FileManagerKeyAction.map("LEFT"))
        assertEquals(FileManagerKeyAction.BACK, FileManagerKeyAction.map("ESC"))
        assertEquals(FileManagerKeyAction.BACK, FileManagerKeyAction.map("BKSP"))
        assertEquals(FileManagerKeyAction.TOGGLE_SELECTION, FileManagerKeyAction.map("TAB"))
    }

    @Test
    fun macrosSkipModifierTokens() {
        assertEquals(FileManagerKeyAction.FOCUS_UP, FileManagerKeyAction.map("CTRL UP"))
        assertEquals(FileManagerKeyAction.BACK, FileManagerKeyAction.map("ALT LEFT"))
        assertEquals(FileManagerKeyAction.NONE, FileManagerKeyAction.map("CTRL ALT SHIFT"))
    }

    @Test
    fun lowercaseAndUnknownKeys() {
        assertEquals(FileManagerKeyAction.FOCUS_DOWN, FileManagerKeyAction.map("down"))
        assertEquals(FileManagerKeyAction.NONE, FileManagerKeyAction.map("A"))
        assertEquals(FileManagerKeyAction.NONE, FileManagerKeyAction.map(""))
    }
}
