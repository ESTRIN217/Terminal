package com.termux.terminal.compose.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure X11 settings policy (spec 017, RF-4, RF-5, RF-6,
 * RF-7, RF-9). The raw values mirrored in the policy come from lorie's
 * `preferences.xml` + `arrays.xml`; keep them in sync if the submodule changes.
 */
class X11SettingsPolicyTest {

    // RF-7: custom resolution validation.
    @Test
    fun testValidateCustomResolution_acceptsWellFormedWxH() {
        val valid = X11SettingsPolicy.validateCustomResolution("1280x1024")
        assertTrue(valid is X11SettingsPolicy.X11ResolutionValidation.Valid)
        assertEquals("1280x1024", (valid as X11SettingsPolicy.X11ResolutionValidation.Valid).resolution)
    }

    @Test
    fun testValidateCustomResolution_trimsSurroundingWhitespace() {
        val valid = X11SettingsPolicy.validateCustomResolution("   800x600   ")
        assertTrue(valid is X11SettingsPolicy.X11ResolutionValidation.Valid)
        assertEquals("800x600", (valid as X11SettingsPolicy.X11ResolutionValidation.Valid).resolution)
    }

    @Test
    fun testValidateCustomResolution_rejectsMalformedInput() {
        val rejected = listOf(
            "",             // empty
            "   ",          // blank
            "0x0",          // both dimensions zero
            "0x800",        // zero width
            "800x0",        // zero height
            "1280",         // missing separator
            "1280x",        // missing height
            "x1024",        // missing width
            "-1x800",       // negative width
            "1280x-1",      // negative height
            "a x b",        // non numeric
            "12 80x1024"    // space inside a dimension
        )
        for (raw in rejected) {
            assertTrue(
                "Expected Invalid for \"$raw\"",
                X11SettingsPolicy.validateCustomResolution(raw) is X11SettingsPolicy.X11ResolutionValidation.Invalid
            )
        }
    }

    // RF-5, RF-9: scale sanitization (clamp to 30..300, nearest multiple of 10).
    @Test
    fun testSanitizeScale_keepsValidMultiplesOfTen() {
        assertEquals(30, X11SettingsPolicy.sanitizeScale(30))
        assertEquals(100, X11SettingsPolicy.sanitizeScale(100))
        assertEquals(300, X11SettingsPolicy.sanitizeScale(300))
    }

    @Test
    fun testSanitizeScale_roundsToNearestTenInsideRange() {
        assertEquals(30, X11SettingsPolicy.sanitizeScale(5))
        assertEquals(30, X11SettingsPolicy.sanitizeScale(25))
        assertEquals(30, X11SettingsPolicy.sanitizeScale(29))
        assertEquals(150, X11SettingsPolicy.sanitizeScale(147))
        assertEquals(110, X11SettingsPolicy.sanitizeScale(105))
        assertEquals(300, X11SettingsPolicy.sanitizeScale(299))
    }

    @Test
    fun testSanitizeScale_clampsOutOfRange() {
        assertEquals(30, X11SettingsPolicy.sanitizeScale(0))
        assertEquals(300, X11SettingsPolicy.sanitizeScale(301))
        assertEquals(300, X11SettingsPolicy.sanitizeScale(550))
        assertEquals(30, X11SettingsPolicy.sanitizeScale(-50))
    }

    // RF-9: unknown/missing list values fall back to the default.
    @Test
    fun testSanitizeListValue_fallsBackToDefault() {
        val cases = listOf(
            Triple(X11SettingsPolicy.OUTPUT_MODES, X11SettingsPolicy.DEFAULT_OUTPUT_MODE, null),
            Triple(X11SettingsPolicy.OUTPUT_MODES, X11SettingsPolicy.DEFAULT_OUTPUT_MODE, ""),
            Triple(X11SettingsPolicy.OUTPUT_MODES, X11SettingsPolicy.DEFAULT_OUTPUT_MODE, "bogus"),
            Triple(X11SettingsPolicy.TOUCH_MODES, X11SettingsPolicy.DEFAULT_TOUCH_MODE, "9"),
            Triple(X11SettingsPolicy.FILTERING_MODES, X11SettingsPolicy.DEFAULT_FILTERING_MODE, "sharp"),
            Triple(X11SettingsPolicy.ORIENTATION_MODES, X11SettingsPolicy.DEFAULT_ORIENTATION, "")
        )
        for ((values, default, raw) in cases) {
            assertEquals(default, X11SettingsPolicy.sanitizeListValue(raw, values, default))
        }
    }

    @Test
    fun testSanitizeListValue_keepsKnownValues() {
        assertEquals("exact", X11SettingsPolicy.sanitizeListValue("exact", X11SettingsPolicy.OUTPUT_MODES, X11SettingsPolicy.DEFAULT_OUTPUT_MODE))
        assertEquals("2", X11SettingsPolicy.sanitizeListValue("2", X11SettingsPolicy.TOUCH_MODES, X11SettingsPolicy.DEFAULT_TOUCH_MODE))
        assertEquals("reverse portrait", X11SettingsPolicy.sanitizeListValue("reverse portrait", X11SettingsPolicy.ORIENTATION_MODES, X11SettingsPolicy.DEFAULT_ORIENTATION))
        assertEquals("nearest", X11SettingsPolicy.sanitizeListValue("nearest", X11SettingsPolicy.FILTERING_MODES, X11SettingsPolicy.DEFAULT_FILTERING_MODE))
    }

    // RF-6: index of the current value; unknown value maps onto index 0.
    @Test
    fun testSelectedIndexFor_mapsUnknownToZero() {
        val exactList = listOf("320x200", "800x600", "1280x1024")
        assertEquals(1, X11SettingsPolicy.selectedIndexFor(exactList, "800x600"))
        assertEquals(2, X11SettingsPolicy.selectedIndexFor(exactList, "1280x1024"))
        assertEquals(0, X11SettingsPolicy.selectedIndexFor(exactList, "9999x9999"))
        assertEquals(1, X11SettingsPolicy.selectedIndexFor(X11SettingsPolicy.TOUCH_MODES, "2"))
    }

    // RF-4: conditional controls visibility.
    @Test
    fun testExactResolutionVisible_onlyInExactMode() {
        assertTrue(X11SettingsPolicy.isExactResolutionVisible("exact"))
        assertFalse(X11SettingsPolicy.isExactResolutionVisible("native"))
        assertFalse(X11SettingsPolicy.isExactResolutionVisible("scaled"))
        assertFalse(X11SettingsPolicy.isExactResolutionVisible("custom"))
        assertFalse(X11SettingsPolicy.isExactResolutionVisible(""))
    }

    @Test
    fun testCustomResolutionVisible_onlyInCustomMode() {
        assertTrue(X11SettingsPolicy.isCustomResolutionVisible("custom"))
        assertFalse(X11SettingsPolicy.isCustomResolutionVisible("native"))
        assertFalse(X11SettingsPolicy.isCustomResolutionVisible("scaled"))
        assertFalse(X11SettingsPolicy.isCustomResolutionVisible("exact"))
        assertFalse(X11SettingsPolicy.isCustomResolutionVisible(""))
    }
}
