package com.termux.terminal;

/**
 * Placement lifecycle while rows move: scroll (ring buffer), reverse index,
 * insert/delete lines, scroll-down, alt-screen isolation, resize and budget
 * eviction, plus registry id non-recycling after reset (post-INFORME F2).
 * <p>
 * Covers RF-29 (no orphan cell reservations), RF-30 (scroll moves every
 * placement rect and its cells), RF-31 (resize never repositions) and RF-32
 * (alternate buffer owns its own placements).
 */
public class ImagePlacementShiftTest extends TerminalTestCase {

	private static final String PNG_1x1_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	/**
	 * Chunks of the synthetic payload used to fill the registry budget: 1,000,000 unpadded
	 * base64 chunks = 3,000,000 decoded bytes. That is just under the per-image cap
	 * ({@code MAX_IMAGE_ENCODED_BYTES}, 3 MiB) and just above a third of the registry budget
	 * ({@code MAX_IMAGE_REGISTRY_BYTES}, 24 MiB), so nine payloads are the smallest sequence
	 * that forces an eviction.
	 */
	private static final int LARGE_PAYLOAD_CHUNKS = 1_000_000;

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	/** Transmit+display a 2x1 image at the current cursor; returns its placement. */
	private TerminalImagePlacement displayAtCursor(int protocolId) {
		return displayAtCursor(protocolId, 2, 1);
	}

	/**
	 * Transmit+display an image of the given size in cells at the current cursor.
	 *
	 * @param protocolId client {@code i=} of the payload
	 * @param widthCells destination width in cells
	 * @param heightCells destination height in cells
	 * @return the placement created at the cursor
	 */
	private TerminalImagePlacement displayAtCursor(int protocolId, int widthCells, int heightCells) {
		enterString(kg("a=T,i=" + protocolId + ",f=100,s=1,v=1,w=" + widthCells + ",h=" + heightCells, PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		final TerminalImagePlacement placement = mTerminal.getPlacementAt(mTerminal.getCursorRow(), mTerminal.getCursorCol());
		assertNotNull(placement);
		return placement;
	}

	/**
	 * RF-29: no reachable cell (visible or transcript) may carry the key of a placement
	 * that no longer exists.
	 */
	private void assertNoOrphanReservations() {
		final TerminalBuffer screen = mTerminal.getScreen();
		for (int row = -screen.getActiveTranscriptRows(); row < screen.mScreenRows; row++) {
			for (int col = 0; col < screen.mColumns; col++) {
				final int key = screen.getPlacementKeyAt(row, col);
				if (key == 0) continue;
				assertNotNull("cell (" + row + "," + col + ") points at discarded placement key " + key,
					mTerminal.getPlacement(key));
			}
		}
	}

	public void testRingScrollShiftsPlacementRect() {
		withTerminalSized(20, 6);
		final TerminalImagePlacement placement = displayAtCursor(1);
		assertEquals(0, placement.startRow);
		// Two linefeeds at the bottom row scroll the screen twice; the placement
		// must track its cells into the transcript.
		enterString("\033[6;1H\n\n");
		assertEquals(-2, placement.startRow);
		assertTrue("rect must stay live while partly in the transcript", placement.intersectsRow(-2));
		assertEquals(1, mTerminal.getScreen().getImageAt(-2, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(-2, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(-1, 0));
		// Screen row 0 now holds what was row 2 (blank, no image).
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
	}

	public void testScrollDownShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[2;1H");
		final TerminalImagePlacement placement = displayAtCursor(1);
		assertEquals(1, placement.startRow);
		enterString("\033[1T"); // CSI T — scroll down one line
		assertEquals(2, placement.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(2, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(2, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(1, 0));
	}

	public void testReverseIndexShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[2;1H");
		final TerminalImagePlacement placement = displayAtCursor(1);
		enterString("\033[1;1H"); // cursor to top margin so RI scrolls
		enterString("\033M");     // reverse index
		assertEquals(2, placement.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(2, 0));
	}

	public void testDeleteLinesShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[3;1H");
		final TerminalImagePlacement placement = displayAtCursor(1);
		assertEquals(2, placement.startRow);
		enterString("\033[1;1H\033[1M"); // DL at row 0: rows below move up one
		assertEquals(1, placement.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(2, 0));
	}

	public void testInsertLinesShiftsPlacementRect() {
		withTerminalSized(20, 6);
		enterString("\033[3;1H");
		final TerminalImagePlacement placement = displayAtCursor(1);
		enterString("\033[1;1H\033[1L"); // IL at row 0: rows below move down one
		assertEquals(3, placement.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(3, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(2, 0));
	}

	public void testAltScreenScrollKeepsMainPlacement() {
		withTerminalSized(20, 6);
		final TerminalImagePlacement placement = displayAtCursor(1);
		assertEquals(0, placement.startRow);
		enterString("\033[?1049h");       // alternate screen (cleared on entry)
		enterString("\033[5;1H\n\n");     // scroll twice while on alt
		enterString("\033[?1049l");       // back to main
		assertEquals("alt scrolls must not move main-screen rects", 0, placement.startRow);
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 1));
	}

	public void testAltEntryDropsAltPlacements() {
		withTerminalSized(20, 6);
		enterString("\033[?1049h");
		final TerminalImagePlacement placement = displayAtCursor(1);
		assertTrue(placement.onAltScreen);
		enterString("\033[?1049l"); // leave alt (alt cells survive until re-entry)
		enterString("\033[?1049h"); // re-entry wipes the alt buffer
		assertNull("wiped alt buffer must not keep a live rect", mTerminal.getPlacement(placement.key));
	}

	public void testSoftResetDoesNotRecycleRegistryIds() {
		withTerminalSized(20, 6);
		final TerminalImagePlacement first = displayAtCursor(1);
		assertEquals(1, first.registryId);
		// Soft terminal reset (DECSTR) resets the image registry but keeps screen
		// cells: a surviving side-band id must never resolve to a newly
		// registered, unrelated image.
		enterString("\033[!p");
		enterString("\033[1;4H");
		final TerminalImagePlacement second = displayAtCursor(1);
		assertEquals(2, second.registryId);
		assertNull("old id must stay dead", mTerminal.getImageData(1));
		// The stale id-1 cells from before the reset resolve to nothing.
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 0));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	// --- T3: placement lifecycle (RF-29, RF-30, RF-31, RF-32, RF-38) ---

	public void testRingScrollAtTheFootShiftsBothPlacementRects() {
		withTerminalSized(20, 6);
		enterString("\033[1;1H");
		final TerminalImagePlacement first = displayAtCursor(1);   // row 0, cols 0-1
		enterString("\033[3;6H");
		final TerminalImagePlacement second = displayAtCursor(2);  // row 2, cols 5-6
		assertEquals(0, first.startRow);
		assertEquals(2, second.startRow);

		// Two linefeeds at the foot scroll the ring buffer twice.
		enterString("\033[6;1H\n\n");

		assertEquals("both rects follow their rows", -2, first.startRow);
		assertEquals(0, second.startRow);
		// The reservations travel with the rows they belong to.
		assertEquals(first.key, mTerminal.getPlacementKeyAt(-2, 0));
		assertEquals(first.key, mTerminal.getPlacementKeyAt(-2, 1));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(0, 5));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(0, 6));
		assertEquals(0, mTerminal.getPlacementKeyAt(-1, 0));
		// Both placements are alive and are still the top of the cells they cover.
		assertSame(first, mTerminal.getPlacement(first.key));
		assertSame(second, mTerminal.getPlacement(second.key));
		assertSame(first, mTerminal.getPlacementAt(-2, 0));
		assertSame(second, mTerminal.getPlacementAt(0, 5));
		assertNotNull(mTerminal.getImageDataAt(-2, 0));
		assertNotNull(mTerminal.getImageDataAt(0, 5));
		assertNoOrphanReservations();
	}

	public void testPlacementPushedOverTheTopStaysLiveAndReserved() {
		withTerminalSized(20, 6);
		enterString("\033[1;1H");
		final TerminalImagePlacement placement = displayAtCursor(1); // row 0, cols 0-1

		enterString("\033[6;1H\n"); // one scroll pushes the rect out through the top

		assertEquals(-1, placement.startRow);
		assertTrue("the rect must not be destroyed by the scroll", placement.isVisible());
		assertSame(placement, mTerminal.getPlacement(placement.key));
		assertTrue(placement.intersectsRow(-1));
		// Its cells travelled with the row, so it is painted again as soon as that row
		// is on screen (the view scrolls back over the transcript): rect alive + reservation.
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(-1, 0));
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(-1, 1));
		assertSame(placement, mTerminal.getPlacementAt(-1, 0));
		assertNotNull(mTerminal.getImageDataAt(-1, 0));
		// Nothing was anchored nor re-stamped on the screen rows.
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
		assertNoOrphanReservations();

		// A taller placement keeps painting the rows of its rectangle that are still visible.
		enterString("\033[1;1H");
		final TerminalImagePlacement tall = displayAtCursor(2, 2, 3); // rows 0-2
		enterString("\033[6;1H\n\n"); // two scrolls: its rect now spans rows -2..0
		assertEquals(-2, tall.startRow);
		assertSame(tall, mTerminal.getPlacementAt(0, 0));
		assertEquals(tall.key, mTerminal.getPlacementKeyAt(-1, 0));
		assertEquals(tall.key, mTerminal.getPlacementKeyAt(-2, 0));
		assertTrue(tall.intersectsRow(0));
		assertNoOrphanReservations();
	}

	public void testInsertDeleteAndReverseIndexShiftBothPlacementRects() {
		withTerminalSized(20, 6);
		enterString("\033[1;1H");
		final TerminalImagePlacement first = displayAtCursor(1);  // row 0, cols 0-1
		enterString("\033[3;6H");
		final TerminalImagePlacement second = displayAtCursor(2); // row 2, cols 5-6

		// IL at the top pushes both rects (and their cells) one row down.
		enterString("\033[1;1H\033[1L");
		assertEquals(1, first.startRow);
		assertEquals(3, second.startRow);
		assertEquals(first.key, mTerminal.getPlacementKeyAt(1, 0));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(3, 5));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));

		// DL at the top pulls both back up.
		enterString("\033[1;1H\033[1M");
		assertEquals(0, first.startRow);
		assertEquals(2, second.startRow);
		assertEquals(first.key, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(2, 5));
		assertEquals(0, mTerminal.getPlacementKeyAt(3, 5));

		// RI at the top margin scrolls the page down one row.
		enterString("\033[1;1H\033M");
		assertEquals(1, first.startRow);
		assertEquals(3, second.startRow);
		assertEquals(first.key, mTerminal.getPlacementKeyAt(1, 0));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(3, 5));
		assertNoOrphanReservations();
	}

	public void testResizeNarrowerThanPlacementOriginKeepsRect() {
		withTerminalSized(20, 6);
		enterString("\033[2;16H"); // origin column 15, outside a 10-column grid
		final TerminalImagePlacement placement = displayAtCursor(1);
		assertEquals(1, placement.startRow);
		assertEquals(15, placement.startCol);
		assertEquals(2, placement.cellsW);
		assertEquals(1, placement.cellsH);

		resize(10, 6); // fewer columns than the placement origin

		assertSame("resize must not destroy the placement", placement, mTerminal.getPlacement(placement.key));
		assertEquals(1, placement.startRow);
		assertEquals("resize must not reposition the origin", 15, placement.startCol);
		assertEquals("resize must not rescale the rectangle", 2, placement.cellsW);
		assertEquals(1, placement.cellsH);
		assertTrue(placement.isVisible());
		// The rectangle is simply outside the new grid; it reserves nothing there.
		assertEquals(0, mTerminal.getPlacementKeyAt(1, 0));
		assertNoOrphanReservations();
	}

	public void testAltScreenPlacementsStayPerBufferAndOnlyAltIsWiped() {
		withTerminalSized(20, 6);
		enterString("\033[1;1H");
		final TerminalImagePlacement main = displayAtCursor(1); // main row 0, cols 0-1

		enterString("\033[?1049h"); // alternate screen (cleared on entry)
		enterString("\033[3;4H");
		final TerminalImagePlacement alt = displayAtCursor(2);  // alt row 2, cols 3-4
		assertTrue(alt.onAltScreen);
		assertEquals(alt.key, mTerminal.getPlacementKeyAt(2, 3));

		enterString("\033[?1049l"); // back to main
		assertEquals("the alt placement must not show in the main buffer", 0, mTerminal.getPlacementKeyAt(2, 3));
		assertNull(mTerminal.getImageDataAt(2, 3));
		assertNull(mTerminal.getPlacementAt(2, 3));
		assertSame("the main placement is untouched", main, mTerminal.getPlacement(main.key));
		assertSame(main, mTerminal.getPlacementAt(0, 0));
		assertEquals(0, main.startRow);

		enterString("\033[?1049h"); // re-entry wipes the alt buffer
		assertNull("only the alt placement is discarded", mTerminal.getPlacement(alt.key));
		assertNotNull(mTerminal.getPlacement(main.key));

		enterString("\033[?1049l");
		assertEquals(main.key, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(main.key, mTerminal.getPlacementKeyAt(0, 1));
		assertNoOrphanReservations();
	}

	public void testDeletingAtCursorLeavesNoOrphanReservation() {
		withTerminalSized(20, 6);
		final TerminalImagePlacement placement = displayAtCursor(1); // cursor stays at (0,0)
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(0, 0));

		enterString(kg("a=d,d=c,q=2", "")); // delete what covers the cursor cell

		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		// The old rectangle keeps no reservation at all (RF-29).
		assertNoOrphanReservations();
	}

	public void testBudgetEvictionDropsEveryPlacementOfTheEvictedImage() {
		withTerminalSized(20, 6);
		// Placed image; its payload will be the oldest one evicted by the budget.
		enterString(kg("a=T,i=1,f=100,w=2,h=1,q=2", largePayload(LARGE_PAYLOAD_CHUNKS)));
		final TerminalImagePlacement placement = mTerminal.getPlacementAt(0, 0);
		assertNotNull(placement);
		assertEquals(1, placement.registryId);
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(0, 1));

		// Fill the 24 MiB registry budget with no-display payloads: the ninth one
		// has to evict the oldest entry (the placed image) to make room.
		for (int id = 2; id <= 9; id++) {
			enterString(kg("a=t,i=" + id + ",f=100,q=2", largePayload(LARGE_PAYLOAD_CHUNKS)));
		}

		assertNull("the evicted payload is gone", mTerminal.getImageData(1));
		assertTrue("no placement survives its payload", mTerminal.getPlacementsForImage(1).isEmpty());
		assertNull(mTerminal.getPlacement(placement.key));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 1));
		assertNull(mTerminal.getImageDataAt(0, 0));
		// The old rectangle keeps no reservation pointing at the discarded placement.
		assertNoOrphanReservations();

		// The surviving payloads are still addressable.
		assertNotNull(mTerminal.getImageData(9));
	}

	/**
	 * Base64 payload of {@code chunks} × 3 decoded bytes, built without padding so it
	 * stays under the emulator's {@code MAX_IMAGE_STRING_LENGTH} while being large
	 * enough to matter for the registry byte budget.
	 *
	 * @param chunks number of {@code "QUJD"} (3-byte) chunks
	 * @return the base64 text
	 */
	private static String largePayload(int chunks) {
		final StringBuilder sb = new StringBuilder(chunks * 4);
		for (int i = 0; i < chunks; i++) sb.append("QUJD");
		return sb.toString();
	}
}
