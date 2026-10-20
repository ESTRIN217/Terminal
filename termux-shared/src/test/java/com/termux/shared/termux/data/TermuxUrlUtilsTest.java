package com.termux.shared.termux.data;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalUrlScanner;

import org.junit.Test;

import java.util.regex.Matcher;

/**
 * Parity lock (Spec 003, RNF-4): the extract-URLs dialog, the terminal tap path and the
 * terminal underline path must share ONE recognizer; here the delegation itself is tested.
 */
public class TermuxUrlUtilsTest {

    @Test
    public void testDialogRecognizerDelegatesToTheTerminalScanner() {
        assertSame(TerminalUrlScanner.getUrlPattern(), TermuxUrlUtils.getUrlMatchRegex());
    }

    @Test
    public void testExtractUrlsStaysUntouched() {
        assertTrue(TermuxUrlUtils.extractUrls("go to https://example.com/path now")
            .contains("https://example.com/path"));
        // Recognizer limits documented in Spec 003 (mailto: is not 'scheme://...').
        assertFalse(TermuxUrlUtils.extractUrls("mailto:x@y.z").contains("mailto:x@y.z"));
    }

    @Test
    public void testMatchesAreStillIndexedFromThePattern() {
        Matcher matcher = TermuxUrlUtils.getUrlMatchRegex().matcher("x http://a.b c");
        assertTrue(matcher.find());
        assertEquals("http://a", matcher.group());
    }
}
