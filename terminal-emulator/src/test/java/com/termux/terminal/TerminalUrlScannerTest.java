package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Plain-text URL detection over logical (soft-wrapped) lines: recognition parity with the
 * extract-URLs dialog recognizer, cross-row spans, wide-glyph cell mapping, and the
 * content-fingerprint cache (the regexp only re-runs when a line's content changed).
 */
public class TerminalUrlScannerTest extends TerminalTestCase {

	/** https://" (8) + "example.com" (11) + "/path" (5) = 24 characters. */
	private static final String URL = "https://example.com/path";

	@Test
	public void testSingleRowLinkUriAndRanges() {
		withTerminalSized(40, 4);
		enterString("see " + URL + " now");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		TerminalUrlScanner.LogicalLine line = scanner.collectLogicalLine(mTerminal.getScreen(), 0);
		// The row is padded to its full width: 32 content chars + 8 trailing spaces.
		assertEquals("see " + URL + " now" +
			"        ", line.mText);
		TerminalUrlScanner.LinkSpan span = scanner.findUrlSpans(line).get(0);
		assertEquals(URL, span.mUri);

		// Cells 0..3 are "see ", the URL covers cells 4..27 (24 cells), 28+ is plain text.
		assertEquals(URL, scanner.getUriAt(mTerminal.getScreen(), 0, 4));
		assertEquals(URL, scanner.getUriAt(mTerminal.getScreen(), 0, 27));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 3));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 28));

		// Underline range for the row: inclusive start, exclusive end.
		TerminalUrlScanner.LogicalLineEntry entry = scanner.entryFor(mTerminal.getScreen(), 0);
		assertArrayEquals(new int[] {4, 28}, entry.columnRangeForRow(mTerminal.getScreen(), 0, span));
	}

	@Test
	public void testMailtoAndFtpAndGeminiOpen() {
		withTerminalSized(40, 6);
		enterString("a mailto:x@y.z b ftp://h.io c gemini://g.io");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		// mailto: is not recognizable: the recognizer only matches 'scheme://...' (dialog parity).
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 2));
		// ftp://h.io at cols 17..26, gemini://g.io starts at col 30.
		assertEquals("ftp://h.io", scanner.getUriAt(mTerminal.getScreen(), 0, 19));
		assertEquals("gemini://g.io", scanner.getUriAt(mTerminal.getScreen(), 0, 30));
	}

	@Test
	public void testNoSchemeNoLink() {
		withTerminalSized(40, 4);
		enterString("open example.com/a here");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		for (int col = 0; col < 23; col++) {
			assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, col));
		}
	}

	@Test
	public void testWrappedLinkTapOnSecondRowOpensFullUrl() {
		withTerminalSized(20, 4);
		String url = "https://very-long-domain.example.com/a1"; // 39 chars > 20 columns
		enterString(url);
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		// One logical line split over rows 0 and 1: any cell of either row opens the full URL.
		assertEquals(url, scanner.getUriAt(mTerminal.getScreen(), 0, 0));
		assertEquals(url, scanner.getUriAt(mTerminal.getScreen(), 1, 0));
		assertEquals(url, scanner.getUriAt(mTerminal.getScreen(), 1, 18));
		// Past the URL tail on the second row (row full at 39+pad) → plain text.
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 1, 19));
	}

	@Test
	public void testTwoLinksInOneRow() {
		withTerminalSized(60, 4);
		enterString("x " + URL + " y http://a.b c");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		// First link at cols 2..25; the second starts at col 29 but its single-label host ends
		// as "http://a": the recognizer needs a dotted TLD and does not match ".b" (dialog parity).
		assertEquals(URL, scanner.getUriAt(mTerminal.getScreen(), 0, 2));
		assertEquals("http://a", scanner.getUriAt(mTerminal.getScreen(), 0, 30));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 26));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 30 + 8));
	}

	@Test
	public void testWideGlyphInsideLinkBreaksTheMatch() {
		// The recognizer's path grammar does not include wide characters (dialog parity):
		// a wide glyph in the middle of the URL cuts it into "https://e.co/" and a bare tail.
		withTerminalSized(24, 4);
		enterString("https://e.co/\u4E2D/path");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		assertEquals("https://e.co/", scanner.getUriAt(mTerminal.getScreen(), 0, 12));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 13));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 14));
	}

	@Test
	public void testWideGlyphOutsideLinkMapsBothCells() {
		withTerminalSized(24, 4);
		// CJK ideograph U+4E2D, width 2, before the link: cells 0..1, link starts at cell 2.
		enterString("\u4E2D" + "https://e.co/path");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		// A tap on either half of the wide glyph lands outside the span...
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 0));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 1));
		// ...and the link itself is intact from its first cell.
		assertEquals("https://e.co/path", scanner.getUriAt(mTerminal.getScreen(), 0, 2));
	}

	@Test
	public void testTranscriptRowLinkOpens() {
		withTerminalSized(20, 4);
		// The URL (24 chars > 20 columns) soft-wraps, then one scroll pushes the whole logical
		// line into the transcript: rows -2 (head) and -1 ("path" tail).
		enterString(URL + "\r\nf1\r\nf2\r\nf3\r\nf4");
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		assertEquals(URL, scanner.getUriAt(mTerminal.getScreen(), -2, 0));
		assertEquals(URL, scanner.getUriAt(mTerminal.getScreen(), -2, 19));
		assertEquals(URL, scanner.getUriAt(mTerminal.getScreen(), -1, 0));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), -1, 4));
		// -3 is above the transcript boundary: nothing, without throwing.
		assertNull(scanner.getUriAt(mTerminal.getScreen(), -3, 0));
	}

	@Test
	public void testRowOutsideGridAnswersNullWithoutThrowing() {
		withTerminalSized(20, 4);
		enterString(URL);
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		// The stale-tap races: past the screen, onto the screen-end sentinel, above the transcript,
		// and a column at the very end of the grid.
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 4 + 11, 0));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 4, 0));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), -1, 0));
		assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, 20));
	}

	@Test
	public void testCacheDoesNotRescanUnchangedContent() {
		withTerminalSized(20, 4);
		enterString(URL);
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		// 24 chars in 20 columns: the line soft-wraps to "https://example.com/" + "path".
		assertNotNull(scanner.getUriAt(mTerminal.getScreen(), 0, 0));
		assertEquals(1, scanner.getRescanCount());
		assertNotNull(scanner.getUriAt(mTerminal.getScreen(), 0, 19));
		assertEquals("no rescan when the line content is unchanged", 1, scanner.getRescanCount());

		// A changed row inside the wrapped line rescans once: the tail cell is no longer a link.
		enterString("x");
		assertEquals("https://example.com/pathx", scanner.getUriAt(mTerminal.getScreen(), 1, 4));
		assertEquals(2, scanner.getRescanCount());
		assertNotNull(scanner.getUriAt(mTerminal.getScreen(), 0, 0));
		assertEquals("the hit is still served from the cache again", 2, scanner.getRescanCount());
	}

	@Test
	public void testCacheRescansWhenTheWrapFlagFlips() {
		withTerminalSized(20, 4);
		String url = "https://very-long-domain.example.com/a1"; // 39 chars > 20 columns
		enterString(url);
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		assertEquals(url, scanner.getUriAt(mTerminal.getScreen(), 0, 0));
		assertEquals(1, scanner.getRescanCount());

		// Same row texts, different wrap flag: the fingerprint changes (wrap bit included),
		// the lines stop being joined, and the row sees only its own URL segment.
		mTerminal.getScreen().clearLineWrap(0);
		assertEquals(url.substring(0, 20), scanner.getUriAt(mTerminal.getScreen(), 0, 0));
		assertEquals(2, scanner.getRescanCount());
	}

	@Test
	public void testOverwrittenRowStopsBeingALink() {
		withTerminalSized(20, 4);
		enterString(URL + "\r\n");
		enterString("\033[H");
		enterString("                    "); // blank the whole row with spaces
		TerminalUrlScanner scanner = new TerminalUrlScanner();
		for (int col = 0; col < 20; col++) {
			assertNull(scanner.getUriAt(mTerminal.getScreen(), 0, col));
		}
	}
}
