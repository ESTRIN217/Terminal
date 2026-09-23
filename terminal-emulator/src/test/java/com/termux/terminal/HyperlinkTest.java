package com.termux.terminal;

/** OSC 8 hyperlinks: open/close, cell stamping, clear-on-overwrite, terminators. */
public class HyperlinkTest extends TerminalTestCase {

	private static final String URL = "https://example.com/path";

	public void testOpenLinkStampsPrintedCells() {
		withTerminalSized(20, 4);
		enterString("\033]8;;" + URL + "\007");
		assertTrue(mTerminal.isHyperlinkActive());
		enterString("ab");
		enterString("\033]8;;\007");
		assertFalse(mTerminal.isHyperlinkActive());
		enterString("c");

		assertLineIs(0, "abc                 ");
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 0));
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 1));
		assertNull(mTerminal.getHyperlinkUriAt(0, 2));
		assertNull(mTerminal.getHyperlinkUriAt(0, 3));
	}

	public void testStringTerminatorEndsOsc() {
		withTerminalSized(20, 4);
		enterString("\033]8;;" + URL + "\033\\");
		enterString("x");
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 0));
		enterString("\033]8;;\033\\");
		enterString("y");
		assertNull(mTerminal.getHyperlinkUriAt(0, 1));
	}

	public void testOverwriteClearsHyperlink() {
		withTerminalSized(20, 4);
		enterString("\033]8;;" + URL + "\007");
		enterString("a");
		enterString("\033]8;;\007");
		// Overwrite the linked cell with plain text.
		enterString("\033[1;1H");
		enterString("z");
		assertNull(mTerminal.getHyperlinkUriAt(0, 0));
		assertEquals("z", String.valueOf(mTerminal.getScreen()
			.mLines[mTerminal.getScreen().externalToInternalRow(0)].mText[0]));
	}

	public void testParamsIgnoredUriStillOpens() {
		withTerminalSized(20, 4);
		enterString("\033]8;id=foo;" + URL + "\007");
		enterString("a");
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 0));
	}

	public void testSameUriReusesRegistryIndex() {
		withTerminalSized(20, 4);
		enterString("\033]8;;" + URL + "\007");
		enterString("a");
		enterString("\033]8;;\007");
		enterString("b");
		enterString("\033]8;;" + URL + "\007");
		enterString("c");
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 0));
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 2));
	}

	public void testResetClosesActiveLink() {
		withTerminalSized(20, 4);
		enterString("\033]8;;" + URL + "\007");
		assertTrue(mTerminal.isHyperlinkActive());
		mTerminal.reset();
		assertFalse(mTerminal.isHyperlinkActive());
		enterString("a");
		assertNull(mTerminal.getHyperlinkUriAt(0, 0));
	}

	public void testWideCharStampsBothColumns() {
		withTerminalSized(20, 4);
		enterString("\033]8;;" + URL + "\007");
		// CJK ideograph U+4E2D, display width 2.
		enterString("中");
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 0));
		assertEquals(URL, mTerminal.getHyperlinkUriAt(0, 1));
		assertNull(mTerminal.getHyperlinkUriAt(0, 2));
	}

	public void testUnknownOscStillNotBroken() {
		// Sanity: unknown OSC codes still finish without eating following text.
		withTerminalSized(20, 4);
		enterString("\033]99;whatever\007");
		enterString("ok");
		assertLineIs(0, "ok                  ");
	}
}
