package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the {@code extra-keys} matrix parser.
 *
 * The expectations mirror the classic Termux parser so a `termux.properties` written for Termux
 * keeps behaving the same way here. Plain JUnit (no Robolectric): the real `org.json` comes from
 * the test classpath and `android.util.Log` returns defaults.
 */
class ExtraKeysConfigTest {

    @Test
    fun parse_flatLayout_isASinglePage() {
        val config = ExtraKeysConfig.parse("""[["ESC","UP"],["TAB","DOWN"]]""")
        assertEquals(1, config.pages.size)
        assertEquals(2, config.pages[0].size)
        assertEquals("ESC", config.rows[0][0].key)
        assertEquals("DOWN", config.rows[1][1].key)
    }

    @Test
    fun parse_paginatedLayout_keepsEveryPage() {
        val config = ExtraKeysConfig.parse("""[[["ESC","UP"]],[["F1","F2"]]]""")
        assertEquals(2, config.pages.size)
        assertEquals("F1", config.pages[1][0][0].key)
    }

    @Test
    fun parse_emptyMatrix_hidesTheBar() {
        val config = ExtraKeysConfig.parse("[]")
        assertTrue(config.pages.isEmpty())
    }

    @Test
    fun parse_invalidJson_fallsBackToTheDefaultLayout() {
        val config = ExtraKeysConfig.parse("not json at all")
        assertFalse(config.pages.isEmpty())
        assertEquals(ExtraKeysConfig.parse(ExtraKeysConfig.DEFAULT_JSON).pages, config.pages)
    }

    @Test
    fun parse_macro_isFlaggedAndKeepsTheWholeSequence() {
        val config = ExtraKeysConfig.parse(
            """[[{"macro":"CTRL f d","display":"tmux exit"}]]"""
        )
        val key = config.rows[0][0]
        assertTrue(key.isMacro)
        assertEquals("CTRL f d", key.key)
        assertEquals("tmux exit", key.display)
    }

    @Test
    fun parse_plainKeyIsNotAMacro() {
        val config = ExtraKeysConfig.parse("""[[{"key":"HOME","popup":"END"}]]""")
        val key = config.rows[0][0]
        assertFalse(key.isMacro)
        assertEquals("HOME", key.key)
    }

    @Test
    fun parse_macro_resolvesAliasesPerToken() {
        val config = ExtraKeysConfig.parse("""[[{"macro":"CONTROL f DELETE"}]]""")
        assertEquals("CTRL f DEL", config.rows[0][0].key)
    }

    @Test
    fun parse_aliasResolution_acceptsClassicAndLooseNames() {
        val config = ExtraKeysConfig.parse(
            """[["SHFT","ESCAPE","PAGE_UP","escape","BACKSLASH","QUOTE"]]""",
            style = "none"
        )
        assertEquals("SHIFT", config.rows[0][0].key)
        assertEquals("ESC", config.rows[0][1].key)
        assertEquals("PGUP", config.rows[0][2].key)
        // The case-insensitive fallback keeps hand-written lower case names working.
        assertEquals("ESC", config.rows[0][3].key)
        assertEquals("\\", config.rows[0][4].key)
        assertEquals("\"", config.rows[0][5].key)
    }

    @Test
    fun parse_popupIsParsed() {
        val config = ExtraKeysConfig.parse("""[[{"key":"-","popup":{"macro":"ALT SHIFT 1","display":"!"}}]]""")
        val popup = config.rows[0][0].popup
        assertNotNull(popup)
        assertEquals("ALT SHIFT 1", popup!!.key)
        assertEquals("!", popup.display)
        assertTrue(popup.isMacro)
    }

    @Test
    fun parse_styleNone_showsKeyNamesVerbatim() {
        val config = ExtraKeysConfig.parse("""[["ENTER","BKSP","DEL","TAB"]]""", style = "none")
        val row = config.rows[0]
        assertEquals("ENTER", row[0].display)
        assertEquals("BKSP", row[1].display)
        assertEquals("DEL", row[2].display)
        assertEquals("TAB", row[3].display)
    }

    @Test
    fun parse_styleDefault_showsIsoGlyphs() {
        val config = ExtraKeysConfig.parse("""[["ENTER","BKSP","DEL","LEFT"]]""", style = "default")
        val row = config.rows[0]
        assertEquals("↲", row[0].display)
        assertEquals("⌫", row[1].display)
        assertEquals("⌦", row[2].display)
        assertEquals("←", row[3].display)
    }

    @Test
    fun parse_styleArrowsOnly_leavesOtherKeysAsText() {
        val config = ExtraKeysConfig.parse("""[["ENTER","LEFT"]]""", style = "arrows-only")
        assertEquals("ENTER", config.rows[0][0].display)
        assertEquals("←", config.rows[0][1].display)
    }

    @Test
    fun parse_styleAll_usesTheFullIsoSet() {
        val config = ExtraKeysConfig.parse("""[["HOME","CTRL"]]""", style = "all")
        assertEquals("⇱", config.rows[0][0].display)
        assertEquals("⎈", config.rows[0][1].display)
    }

    @Test
    fun parse_unknownStyle_fallsBackToDefault() {
        val config = ExtraKeysConfig.parse("""[["ENTER"]]""", style = "nonsense")
        assertEquals("↲", config.rows[0][0].display)
    }

    @Test
    fun parse_specialKeys_getTheirGlyphs() {
        val config = ExtraKeysConfig.parse("""[["KEYBOARD","DRAWER","PASTE","SCROLL"]]""")
        val row = config.rows[0]
        assertEquals("⌨", row[0].display)
        assertEquals("☰", row[1].display)
        assertEquals("⎘", row[2].display)
        assertEquals("⇳", row[3].display)
    }

    @Test
    fun modifierAndRepetitiveKeys_areClassified() {
        val config = ExtraKeysConfig.parse("""[["CTRL","ALT","SHIFT","FN","UP","BKSP","ESC"]]""")
        val row = config.rows[0]
        assertTrue(row[0].isModifier)
        assertTrue(row[3].isModifier)
        assertFalse(row[0].isRepetitive)
        assertTrue(row[4].isRepetitive)
        assertTrue(row[5].isRepetitive)
        assertFalse(row[6].isRepetitive)
    }

    @Test
    fun parse_defaultConstant_isTheCompiledInLayout() {
        val config = ExtraKeysConfig.parse(ExtraKeysConfig.DEFAULT_JSON)
        assertEquals(2, config.pages.size)
        assertNull(config.rows[0][0].popup)
    }
}
