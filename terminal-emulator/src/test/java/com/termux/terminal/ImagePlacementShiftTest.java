package com.termux.terminal;

/**
 * Placement-rect alignment after row content moves: scroll (ring buffer),
 * reverse index, insert/delete lines, scroll-down, alt-screen isolation and
 * registry id non-recycling after reset (post-INFORME F2).
 */
public class ImagePlacementShiftTest extends TerminalTestCase {

	private static final String PNG_1x1_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	/** Transmit+display a 2x1 image at the current cursor; returns the registry entry. */
	private TerminalImageData displayAtCursor(int protocolId) {
		enterString(kg("a=T,i=" + protocolId + ",f=100,s=1,v=1,w=2,h=1", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		final TerminalImageData data = mTerminal.getImageDataAt(mTerminal.getCursorRow(), mTerminal.getCursorCol());
		assertNotNull(data);
		return data;
	}

	public void testRingScrollShiftsPlacementRect() {
		withTerminalSized(20, 6);
		final TerminalImageData data = displayAtCursor(1);
		assertEquals(0, data.startRow);
		// Two linefeeds at the bottom row scroll the screen twice; the placement
		// must track its cells into the transcript.
		enterString("\033[6;1H\n\n");
		assertEquals(-2, data.startRow);
		assertTrue("rect must stay live while partly in the transcript", data.intersectsRow(-2));
		assertEquals(1, mTerminal.getScreen().getImageAt(-2, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(-2, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(-1, 0));
		// Screen row 0 now holds what was row 2 (blank, no image).
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
	}

	public void testScrollDownShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[2;1H");
		final TerminalImageData data = displayAtCursor(1);
		assertEquals(1, data.startRow);
		enterString("\033[1T"); // CSI T — scroll down one line
		assertEquals(2, data.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(2, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(2, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(1, 0));
	}

	public void testReverseIndexShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[2;1H");
		final TerminalImageData data = displayAtCursor(1);
		enterString("\033[1;1H"); // cursor to top margin so RI scrolls
		enterString("\033M");     // reverse index
		assertEquals(2, data.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(2, 0));
	}

	public void testDeleteLinesShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[3;1H");
		final TerminalImageData data = displayAtCursor(1);
		assertEquals(2, data.startRow);
		enterString("\033[1;1H\033[1M"); // DL at row 0: rows below move up one
		assertEquals(1, data.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(2, 0));
	}

	public void testInsertLinesShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[3;1H");
		final TerminalImageData data = displayAtCursor(1);
		enterString("\033[1;1H\033[1L"); // IL at row 0: rows below move down one
		assertEquals(3, data.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(3, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(2, 0));
	}

	public void testAltScreenScrollKeepsMainPlacement() {
		withTerminalSized(20, 6);
		final TerminalImageData data = displayAtCursor(1);
		assertEquals(0, data.startRow);
		enterString("\033[?1049h");       // alternate screen (cleared on entry)
		enterString("\033[5;1H\n\n");     // scroll twice while on alt
		enterString("\033[?1049l");       // back to main
		assertEquals("alt scrolls must not move main-screen rects", 0, data.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 1));
	}

	public void testAltEntryDropsAltPlacements() {
		withTerminalSized(20, 6);
		enterString("\033[?1049h");
		final TerminalImageData data = displayAtCursor(1);
		assertTrue(data.onAltScreen);
		enterString("\033[?1049l"); // leave alt (alt cells survive until re-entry)
		enterString("\033[?1049h"); // re-entry wipes the alt buffer
		assertFalse("wiped alt buffer must not keep a live rect", data.isPlaced());
	}

	public void testSoftResetDoesNotRecycleRegistryIds() {
		withTerminalSized(20, 6);
		final TerminalImageData first = displayAtCursor(1);
		assertEquals(1, first.id);
		// Soft terminal reset (DECSTR) resets the image registry but keeps screen
		// cells: a surviving side-band id must never resolve to a newly
		// registered, unrelated image.
		enterString("\033[!p");
		enterString("\033[1;4H");
		final TerminalImageData second = displayAtCursor(1);
		assertEquals(2, second.id);
		assertNull("old id must stay dead", mTerminal.getImageData(1));
		// The stale id-1 cells from before the reset resolve to nothing.
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 0));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}
}
