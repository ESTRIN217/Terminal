package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Kitty graphics placement identity ({@code i=}, {@code p=}) and deletes: one
 * placement per (image, {@code p=}) pair, anonymous placements, per-placement
 * deletion, retransmission and the per-image placement cap.
 * <p>
 * Real client sequences (opencode / OpenTUI): transmit an image once
 * ({@code a=t}) and then place several runs of it ({@code a=p}) with different
 * {@code p=}, each run with its own cell rectangle and its own source crop
 * ({@code x=}/{@code y=}/{@code w=}/{@code h=}). Covers RF-1, RF-2, RF-3,
 * RF-4, RF-5, RF-6, RF-7, RF-8, RF-9, RF-10, RF-11, RF-12, RF-13, RF-14,
 * RF-15, RF-16, RF-18, RF-19, RF-20, RF-21, RF-24, RF-25, RF-26, RF-27,
 * RF-28, RF-29, RF-30, RF-31, RF-32, RF-36, RF-37, RF-38 and RNF-7.
 * <p>
 * Includes the full §7.3 OpenTUI sequences (transmit, place two cropped runs,
 * delete one run and then the image, quiet-mode sweep) and the row-by-row
 * {@code collectPlacementsForRow} view of the result (plan §7.4: geometry and
 * reservations, never pixels).
 */
public class KittyPlacementRunsTest extends TerminalTestCase {

	/** 2×2 PNG (red/blue checkerboard); the emulator sniffs 2×2 pixels from its IHDR. */
	private static final String PNG_2x2_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP4z8AAQkAMJEAsAEPOB/kTdAflAAAAAElFTkSuQmCC";

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	/** {@code a=t}: register the payload under {@code i=} without displaying it (silent). */
	private static String transmit(int i) {
		return kg("a=t,f=100,i=" + i + ",m=0,q=2", PNG_2x2_B64);
	}

	/** {@code a=p}: place image {@code i} at the cursor with placement id {@code p} (silent). */
	private static String place(int i, int p, String cells) {
		return kg("a=p,i=" + i + ",p=" + p + "," + cells + ",q=2", "");
	}

	/** {@code a=p} without {@code p=}: an anonymous placement (RF-3). */
	private static String placeAnonymous(int i, String cells) {
		return kg("a=p,i=" + i + "," + cells + ",q=2", "");
	}

	/**
	 * {@code a=p} in the exact shape OpenTUI emits: cell rectangle ({@code c=}/{@code r=}), source
	 * crop in pixels ({@code x=}/{@code y=}/{@code w=}/{@code h=}), the ignored {@code C=1} and a
	 * signed {@code z=} (RF-6, RF-11, RF-18).
	 *
	 * @param i     client {@code i=} of the transmitted image
	 * @param p     client {@code p=} placement id
	 * @param cells {@code c=}/{@code r=} list, or null to omit the rectangle
	 * @param crop  {@code x=}/{@code y=}/{@code w=}/{@code h=} list, or null to omit the crop
	 * @param z     {@code z=} value, or null to omit it (absent means 0)
	 * @param q     {@code q=} response suppression mode
	 * @return the full APC sequence, ready for {@link #enterString(String)}
	 */
	private static String placeRun(int i, int p, String cells, String crop, Integer z, int q) {
		final StringBuilder control = new StringBuilder("a=p,i=").append(i).append(",p=").append(p);
		if (cells != null) control.append(',').append(cells);
		if (crop != null) control.append(',').append(crop);
		control.append(",C=1");
		if (z != null) control.append(",z=").append(z);
		return kg(control.append(",q=").append(q).toString(), "");
	}

	/**
	 * Open the §7.3 OpenTUI scenario: a 20×10 terminal with the 2×2 PNG transmitted once and two
	 * cropped runs of it — {@code p=1} (rows 2-3, cols 4-6, left pixel column, {@code z=-1}) and
	 * {@code p=2} (rows 5-7, cols 10-11, bottom-right pixel, {@code z=0}).
	 *
	 * @return {@code {first, second}}: the two placements, in creation order
	 */
	private TerminalImagePlacement[] withTwoCroppedRuns() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(2, 4);
		enterString(placeRun(5, 1, "c=3,r=2", "x=0,y=0,w=1,h=2", -1, 2));
		moveTo(5, 10);
		enterString(placeRun(5, 2, "c=2,r=3", "x=1,y=1,w=1,h=1", 0, 2));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(2, 4);
		final TerminalImagePlacement second = mTerminal.getPlacementAt(5, 10);
		assertNotNull("the first run must be placed", first);
		assertNotNull("the second run must be placed", second);
		return new TerminalImagePlacement[]{first, second};
	}

	/**
	 * Base64 payload of {@code chunks} × 3 decoded bytes, built without padding so it stays under
	 * the emulator's {@code MAX_IMAGE_STRING_LENGTH} while being big enough for the registry byte
	 * budget: the same shape {@link ImagePlacementShiftTest} uses to force an eviction.
	 *
	 * @param chunks number of {@code "QUJD"} (3-byte) chunks
	 * @return the base64 text
	 */
	private static String evictionPayload(int chunks) {
		final StringBuilder sb = new StringBuilder(chunks * 4);
		for (int i = 0; i < chunks; i++) sb.append("QUJD");
		return sb.toString();
	}

	/** Move the cursor (1-based row;col) before placing: {@code a=p} itself never moves it (RF-6). */
	private void moveTo(int row, int col) {
		enterString("\033[" + (row + 1) + ";" + (col + 1) + "H");
	}

	/** 1-based registry id of the payload reserving a cell, or 0 when the cell is free. */
	private int registryIdAt(int row, int col) {
		final TerminalImageData data = mTerminal.getImageDataAt(row, col);
		return (data == null) ? 0 : data.id;
	}

	/**
	 * RF-29: no reachable cell (visible or transcript) may carry the key of a
	 * placement that no longer exists.
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

	/** RF-1/RF-4/RF-36: two runs of one image coexist, each in its own rectangle, one payload. */
	public void testTwoPlacementIdsOfOneImageCoexistWithOwnRectangles() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(0, 0);
		assertNotNull("first run must be placed", first);
		assertEquals(1, first.placementId);
		assertEquals(0, first.startRow);
		assertEquals(0, first.startCol);
		assertEquals(4, first.cellsW);
		assertEquals(2, first.cellsH);

		moveTo(3, 5);
		enterString(place(5, 2, "c=3,r=3"));
		final TerminalImagePlacement second = mTerminal.getPlacementAt(3, 5);
		assertNotNull("second run must be placed", second);
		assertEquals(2, second.placementId);
		assertEquals(3, second.startRow);
		assertEquals(5, second.startCol);
		assertEquals(3, second.cellsW);
		assertEquals(3, second.cellsH);

		assertEquals("both runs reference the same payload", first.registryId, second.registryId);
		assertSame(mTerminal.getImageData(first.registryId), mTerminal.getImageData(second.registryId));
		assertEquals(2, mTerminal.getPlacementsForImage(first.registryId).size());
		assertEquals("placing never moves the cursor (RF-6)", 3, mTerminal.getCursorRow());
		assertEquals(5, mTerminal.getCursorCol());

		// Each run reserves exactly its own rectangle (RF-22).
		assertEquals(first.key, mTerminal.getPlacementKeyAt(0, 3));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 4));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(3, 6));
		assertEquals(0, mTerminal.getPlacementKeyAt(3, 4));
		assertEquals(0, mTerminal.getPlacementKeyAt(3, 8));
		assertEquals(0, mTerminal.getPlacementKeyAt(2, 5));
		assertNoOrphanReservations();
	}

	/** RF-2: the same (image, p=) pair replaces its placement in place and leaves siblings alone. */
	public void testSamePlacementIdReplacesGeometryInPlace() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(0, 0);
		assertNotNull(first);
		final int key = first.key;
		final long seq = first.creationSeq;

		moveTo(3, 0);
		enterString(place(5, 2, "c=4,r=2"));
		final TerminalImagePlacement sibling = mTerminal.getPlacementAt(3, 0);
		assertNotNull(sibling);
		final int siblingKey = sibling.key;

		// Same pair with another rectangle: one placement, new geometry, same key.
		moveTo(6, 2);
		enterString(place(5, 1, "c=2,r=2"));
		assertEquals(2, mTerminal.getPlacementsForImage(first.registryId).size());
		final TerminalImagePlacement replaced = mTerminal.getPlacement(key);
		assertNotNull("a replacement keeps the placement key", replaced);
		assertSame(replaced, mTerminal.getPlacementAt(6, 2));
		assertEquals(6, replaced.startRow);
		assertEquals(2, replaced.startCol);
		assertEquals(2, replaced.cellsW);
		assertEquals(2, replaced.cellsH);
		assertTrue("a replacement becomes the most recent (RF-20)", replaced.creationSeq > seq);

		// Old cells released, new ones reserved, sibling untouched.
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(1, 3));
		assertEquals(key, mTerminal.getScreen().getImageAt(6, 2));
		assertEquals(key, mTerminal.getScreen().getImageAt(7, 3));
		assertEquals(0, mTerminal.getScreen().getImageAt(6, 4));
		assertSame(sibling, mTerminal.getPlacement(siblingKey));
		assertEquals(3, sibling.startRow);
		assertEquals(4, sibling.cellsW);
		assertEquals(siblingKey, mTerminal.getScreen().getImageAt(3, 0));
		assertEquals(siblingKey, mTerminal.getScreen().getImageAt(4, 3));
		assertNoOrphanReservations();
	}

	/** RF-3: {@code p=} absent or 0 is anonymous: never replaces anything, not registered by id. */
	public void testAnonymousPlacementsAreIndependent() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=2,r=2"));
		final int regId = registryIdAt(0, 0);
		moveTo(3, 0);
		enterString(placeAnonymous(5, "c=2,r=2"));
		moveTo(6, 0);
		enterString(place(5, 0, "c=2,r=2"));

		final List<TerminalImagePlacement> placements = mTerminal.getPlacementsForImage(regId);
		assertEquals("two anonymous runs plus the named one", 3, placements.size());
		assertEquals(1, placements.get(0).placementId);
		assertEquals(0, placements.get(1).placementId);
		assertEquals(0, placements.get(2).placementId);
		assertEquals(3, placements.get(1).startRow);
		assertEquals(6, placements.get(2).startRow);
		assertEquals(placements.get(0).key, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(placements.get(1).key, mTerminal.getPlacementKeyAt(3, 0));
		assertEquals(placements.get(2).key, mTerminal.getPlacementKeyAt(6, 0));
		assertNoOrphanReservations();
	}

	/** RF-4: the placement identity is the pair (image, p=), not {@code p=} alone. */
	public void testSamePlacementIdOnTwoImagesIsIndependent() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		enterString(transmit(6));
		moveTo(0, 0);
		enterString(place(5, 7, "c=3,r=1"));
		moveTo(1, 0);
		enterString(place(6, 7, "c=3,r=1"));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(0, 0);
		final TerminalImagePlacement second = mTerminal.getPlacementAt(1, 0);
		assertNotNull(first);
		assertNotNull(second);
		assertEquals(7, first.placementId);
		assertEquals(7, second.placementId);
		assertTrue(first.registryId != second.registryId);
		assertEquals(2, mTerminal.getPlacementsForImage(first.registryId).size()
			+ mTerminal.getPlacementsForImage(second.registryId).size());

		// Deleting i=5's p=7 must leave image 6's p=7 alone (RF-4/RF-25).
		enterString(kg("a=d,d=p,i=5,p=7", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(0, mTerminal.getPlacementsForImage(first.registryId).size());
		assertEquals(1, mTerminal.getPlacementsForImage(second.registryId).size());
		assertNotNull(mTerminal.getImageDataAt(1, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
		assertNoOrphanReservations();
	}

	/** RF-25/RF-29: {@code d=p} drops one placement, its cells and nothing else. */
	public void testDeletePlacementKeepsPayloadAndSiblings() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		moveTo(3, 0);
		enterString(place(5, 2, "c=4,r=2"));
		final int regId = registryIdAt(0, 0);
		final int firstKey = mTerminal.getPlacementKeyAt(0, 0);
		final int secondKey = mTerminal.getPlacementKeyAt(3, 0);

		enterString(kg("a=d,d=p,i=5,p=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertEquals(secondKey, mTerminal.getPlacementsForImage(regId).get(0).key);
		assertNull("the discarded placement must be gone", mTerminal.getPlacement(firstKey));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(1, 3));
		// Only the deleted run released cells; the sibling keeps its rectangle (RF-22).
		assertEquals(secondKey, mTerminal.getScreen().getImageAt(3, 0));
		assertEquals(secondKey, mTerminal.getScreen().getImageAt(4, 3));
		assertNotNull("the payload survives a placement delete", mTerminal.getImageData(regId));
		assertNoOrphanReservations();
	}

	/** RF-26: a {@code d=p} that matches nothing is an idempotent OK. */
	public void testDeleteMissingPlacementIsIdempotent() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final int regId = registryIdAt(0, 0);
		final int key = mTerminal.getPlacementKeyAt(0, 0);

		// Unknown p= of a known image.
		enterString(kg("a=d,d=p,i=5,p=99", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		// Unknown image.
		enterString(kg("a=d,d=p,i=77,p=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		// No p= and no x=/y=: nothing to address.
		enterString(kg("a=d,d=p,i=5", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertEquals(key, mTerminal.getPlacementKeyAt(0, 0));

		// Deleting the same placement twice: the second call changes nothing.
		enterString(kg("a=d,d=p,i=5,p=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		enterString(kg("a=d,d=p,i=5,p=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertNull(mTerminal.getPlacement(key));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertNoOrphanReservations();
	}

	/** RF-24: {@code d=i}/{@code d=I} drop the payload and every placement of the image. */
	public void testDeleteImageRemovesPayloadAndEveryPlacement() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		moveTo(3, 0);
		enterString(place(5, 2, "c=4,r=2"));
		final int regId = registryIdAt(0, 0);
		final int key = mTerminal.getPlacementKeyAt(0, 0);

		enterString(kg("a=d,d=i,i=5", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertNull(mTerminal.getImageData(regId));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertNull(mTerminal.getPlacement(key));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(3, 0));
		// The popup id is gone, so placing it fails with the existing error.
		enterString(kg("a=p,i=5,c=2,r=2", ""));
		assertTrue(mOutput.getOutputAndClear().contains("ENOENT"));
		assertNoOrphanReservations();

		// d=I is equivalent to d=i.
		enterString(transmit(6));
		moveTo(5, 0);
		enterString(place(6, 1, "c=2,r=2"));
		enterString(kg("a=d,d=I,i=6", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(0, mTerminal.getPlacementsForImage(2).size());
		assertEquals(0, mTerminal.getScreen().getImageAt(5, 0));
		assertNoOrphanReservations();
	}

	/** RF-28: {@code d=a}/{@code d=A} clear the reservations but keep the payloads. */
	public void testDeleteAllKeepsPayloadsAndAllowsReplace() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		enterString(transmit(6));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		moveTo(3, 0);
		enterString(place(6, 1, "c=4,r=2"));
		final int firstReg = registryIdAt(0, 0);
		final int secondReg = registryIdAt(3, 0);
		final int firstKey = mTerminal.getPlacementKeyAt(0, 0);

		enterString(kg("a=d,d=a,i=5", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(0, mTerminal.getPlacementsForImage(firstReg).size());
		assertEquals(0, mTerminal.getPlacementsForImage(secondReg).size());
		assertNull(mTerminal.getPlacement(firstKey));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(3, 0));
		assertNoOrphanReservations();

		// Both payloads survive and the same p= can be placed again with a fresh identity.
		assertNotNull(mTerminal.getImageData(firstReg));
		assertNotNull(mTerminal.getImageData(secondReg));
		enterString(place(5, 1, "c=2,r=1"));
		assertEquals(1, mTerminal.getPlacementsForImage(firstReg).size());
		final TerminalImagePlacement replaced = mTerminal.getPlacementAt(3, 0);
		assertNotNull(replaced);
		assertEquals(1, replaced.placementId);
		assertTrue("keys are never reused", replaced.key != firstKey);
		assertNotNull(mTerminal.getImageDataAt(3, 0));
		assertEquals(0, mTerminal.getPlacementsForImage(secondReg).size());
		assertNoOrphanReservations();
	}

	/** RF-27: {@code d=p} without {@code p=} but with {@code x=}/{@code y=} drops the topmost cell placement. */
	public void testDeleteByCellCoordinatesRemovesTopmostPlacement() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final int olderKey = mTerminal.getPlacementKeyAt(0, 0);
		moveTo(0, 0);
		enterString(place(5, 2, "c=2,r=1"));
		final int regId = registryIdAt(0, 0);
		final int topmostKey = mTerminal.getPlacementKeyAt(0, 0);
		assertEquals(2, mTerminal.getPlacementsForImage(regId).size());
		assertTrue(topmostKey != olderKey);

		// 1-based x=/y= address cell (0,0), which holds the newer placement (RF-20/RF-21).
		enterString(kg("a=d,d=p,i=5,x=1,y=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertEquals(1, mTerminal.getPlacementsForImage(regId).get(0).placementId);
		assertNull(mTerminal.getPlacement(topmostKey));
		assertEquals(olderKey, mTerminal.getScreen().getImageAt(1, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
		assertNoOrphanReservations();

		// A cell with no placement: OK and no effect (RF-26).
		enterString(kg("a=d,d=p,i=5,x=20,y=10", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertNoOrphanReservations();
	}

	/** RF-28/RF-29: {@code d=c}/{@code d=C} discard the placement of the cursor cell, not just its cells. */
	public void testDeleteAtCursorDiscardsThePlacement() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final int regId = registryIdAt(0, 0);
		final int key = mTerminal.getPlacementKeyAt(0, 0);

		enterString(kg("a=d,d=c,i=5", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals("the placement itself must be gone", 0, mTerminal.getPlacementsForImage(regId).size());
		assertNull(mTerminal.getPlacement(key));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(1, 3));
		assertNoOrphanReservations();

		// The payload survives; the p= identity is gone, so its delete is a no-op…
		assertNotNull(mTerminal.getImageData(regId));
		enterString(kg("a=d,d=p,i=5,p=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		// …and the same p= can be placed again.
		enterString(place(5, 1, "c=2,r=2"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		enterString(kg("a=d,d=C,i=5", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertNoOrphanReservations();
	}

	/** RF-5/RF-29: retransmitting an {@code i=} drops the old payload placements and their identity. */
	public void testRetransmitSameImageDropsItsPlacements() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		moveTo(3, 0);
		enterString(place(5, 2, "c=4,r=2"));
		final int regId = registryIdAt(0, 0);
		final int key = mTerminal.getPlacementKeyAt(0, 0);
		assertEquals(2, mTerminal.getPlacementsForImage(regId).size());

		enterString(transmit(5));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertNull(mTerminal.getPlacement(key));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(3, 0));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertNotNull("the registry slot is reused for the new payload", mTerminal.getImageData(regId));
		assertNoOrphanReservations();

		// The identity table was dropped too: the same p= starts a new placement.
		enterString(place(5, 1, "c=2,r=2"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertNoOrphanReservations();
	}

	/** RF-37: the 257th placement of an image evicts the oldest one and frees its cells. */
	public void testPlacementCapEvictsOldest() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=2,r=1"));
		final int regId = registryIdAt(0, 0);
		final int oldestKey = mTerminal.getPlacementKeyAt(0, 0);
		for (int p = 2; p <= 257; p++) {
			enterString(place(5, p, "c=2,r=1"));
		}

		final List<TerminalImagePlacement> placements = mTerminal.getPlacementsForImage(regId);
		assertEquals(256, placements.size());
		assertEquals("the oldest placement (p=1) must be evicted", 2, placements.get(0).placementId);
		assertEquals(257, placements.get(placements.size() - 1).placementId);
		assertNull(mTerminal.getPlacement(oldestKey));
		assertEquals(257, mTerminal.getPlacementAt(0, 0).placementId);
		// The evicted identity is gone, so deleting it is an idempotent OK (RF-26).
		enterString(kg("a=d,d=p,i=5,p=1", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals(256, mTerminal.getPlacementsForImage(regId).size());
		assertNoOrphanReservations();
	}

	/** RF-6: {@code C=} is ignored: placing never moves the cursor. */
	public void testCursorMovementKeyIsIgnoredOnPlace() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(3, 4);
		enterString(kg("a=p,i=5,p=1,c=2,r=1,C=1,q=0", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		assertEquals("a=p must not move the cursor (RF-6)", 3, mTerminal.getCursorRow());
		assertEquals(4, mTerminal.getCursorCol());
		assertNotNull(mTerminal.getPlacementAt(3, 4));
	}

	/** RF-11: {@code x=}/{@code y=}/{@code w=}/{@code h=} are a pixel crop stored on the placement. */
	public void testPlaceStoresSourceCrop() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(4, 7);
		enterString(place(5, 1, "c=2,r=1,x=0,y=1,w=2,h=1"));
		final TerminalImagePlacement placement = mTerminal.getPlacementAt(4, 7);
		assertNotNull(placement);
		assertEquals(0, placement.cropX);
		assertEquals(1, placement.cropY);
		assertEquals(2, placement.cropW);
		assertEquals(1, placement.cropH);
		assertEquals(2, placement.cellsW);
		assertEquals(1, placement.cellsH);
		assertEquals(placement.key, mTerminal.getPlacementKeyAt(4, 8));
		assertEquals(0, mTerminal.getPlacementKeyAt(4, 9));
		assertNoOrphanReservations();
	}

	/** RF-12: a crop omitting one side runs from the origin to the far edge of the image. */
	public void testCropMissingSideRunsToTheImageEdge() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=1,r=1,x=1,y=0,w=1"));
		final TerminalImagePlacement placement = mTerminal.getPlacementAt(0, 0);
		assertNotNull(placement);
		assertEquals(1, placement.cropX);
		assertEquals(0, placement.cropY);
		assertEquals(1, placement.cropW);
		assertEquals("h= omitted: the crop reaches the bottom edge", 2, placement.cropH);
	}

	/** RF-13/RF-14: negative origins clamp to 0 and an overrun clips to the image, never enlarging it. */
	public void testCropOriginClampsToZeroAndOverrunClipsToTheImage() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=1,r=1,x=-3,y=-4,w=99,h=99"));
		final TerminalImagePlacement clamped = mTerminal.getPlacementAt(0, 0);
		assertNotNull(clamped);
		assertEquals(0, clamped.cropX);
		assertEquals(0, clamped.cropY);
		assertEquals(2, clamped.cropW);
		assertEquals(2, clamped.cropH);

		moveTo(3, 0);
		enterString(place(5, 2, "c=1,r=1,x=1,y=1,w=99,h=99"));
		final TerminalImagePlacement clipped = mTerminal.getPlacementAt(3, 0);
		assertNotNull(clipped);
		assertEquals(1, clipped.cropX);
		assertEquals(1, clipped.cropY);
		assertEquals("only the last pixel column/row is left", 1, clipped.cropW);
		assertEquals(1, clipped.cropH);
	}

	/** RF-16: no crop key at all keeps the whole-image sentinel ({@code cropW}/{@code cropH} = 0). */
	public void testPlaceWithoutCropStoresWholeImageSentinel() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=2,r=2"));
		final TerminalImagePlacement placement = mTerminal.getPlacementAt(0, 0);
		assertNotNull(placement);
		assertEquals(0, placement.cropX);
		assertEquals(0, placement.cropY);
		assertEquals(0, placement.cropW);
		assertEquals(0, placement.cropH);
	}

	/** RF-15: a crop that intersects nothing registers an invisible placement and replies OK. */
	public void testEmptyCropRegistersInvisiblePlacement() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		// x=5 is past the 2 px wide image: nothing to scale.
		enterString(kg("a=p,i=5,p=1,c=2,r=2,x=5,y=0,w=2,h=2,q=0", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		final int regId = 1;
		final List<TerminalImagePlacement> placements = mTerminal.getPlacementsForImage(regId);
		assertEquals("the placement is registered", 1, placements.size());
		assertEquals(0, placements.get(0).cellsW);
		assertEquals(0, placements.get(0).cellsH);
		assertEquals("no cell is reserved", 0, mTerminal.getPlacementKeyAt(0, 0));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertNoOrphanReservations();

		// The same p= is substitutable by a visible crop (RF-2).
		enterString(place(5, 1, "c=2,r=2,x=0,y=0,w=2,h=2"));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		final TerminalImagePlacement visible = mTerminal.getPlacementAt(0, 0);
		assertNotNull(visible);
		assertEquals(2, visible.cellsW);
		assertEquals(2, visible.cellsH);
	}

	/** RF-10: without {@code c=}/{@code r=} and with unknown pixel size there is nothing to derive. */
	public void testPlaceWithoutCellsAndUnknownPixelsIsInvalid() {
		withTerminalSized(20, 10);
		// f=24 without s=/v=: raw bytes, so no intrinsic size can be sniffed.
		enterString(kg("a=t,f=24,i=8,q=2", "AAAA"));
		mOutput.getOutputAndClear();
		final int regId = 1;
		assertNotNull("the payload is registered", mTerminal.getImageData(regId));
		moveTo(0, 0);
		enterString(kg("a=p,i=8,q=0", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected INVALID, got: " + reply, reply.contains("INVALID"));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
	}

	/** RF-9: with only one cell count, the other is derived from the crop aspect ratio. */
	public void testMissingCellCountIsDerivedFromTheCropAspect() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		// 2×2 px into 4 columns (4 * 13 px wide) → 52 px tall → ceil(52 / 15) = 4 rows.
		enterString(place(5, 1, "c=4"));
		final TerminalImagePlacement byColumns = mTerminal.getPlacementAt(0, 0);
		assertNotNull(byColumns);
		assertEquals(4, byColumns.cellsW);
		assertEquals(4, byColumns.cellsH);

		moveTo(5, 0);
		// 2 rows (30 px tall) → 30 px wide → ceil(30 / 13) = 3 columns.
		enterString(place(5, 2, "r=2"));
		final TerminalImagePlacement byRows = mTerminal.getPlacementAt(5, 0);
		assertNotNull(byRows);
		assertEquals(3, byRows.cellsW);
		assertEquals(2, byRows.cellsH);
	}

	/** RF-8/RF-31: a rectangle overrunning the grid is kept whole and only on-screen cells are stamped. */
	public void testRectangleOverrunningTheGridIsKeptWhole() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(8, 18);
		enterString(place(5, 1, "c=5,r=5"));
		final TerminalImagePlacement overrun = mTerminal.getPlacementAt(8, 18);
		assertNotNull(overrun);
		assertEquals("clipping happens when painting, not when storing", 5, overrun.cellsW);
		assertEquals(5, overrun.cellsH);
		assertEquals(overrun.key, mTerminal.getPlacementKeyAt(9, 19));
		assertEquals(0, mTerminal.getPlacementKeyAt(7, 18));
		assertNoOrphanReservations();

		// Absurd counts must neither throw nor wrap around while stamping.
		moveTo(0, 0);
		enterString(place(5, 2, "c=100000,r=100000"));
		final TerminalImagePlacement huge = mTerminal.getPlacementAt(0, 0);
		assertNotNull(huge);
		assertEquals(100000, huge.cellsW);
		assertEquals(100000, huge.cellsH);
		assertEquals(2, mTerminal.getPlacementsForImage(overrun.registryId).size());
		assertEquals(huge.key, mTerminal.getPlacementKeyAt(9, 19));
		assertNoOrphanReservations();
	}

	/** RF-18: {@code z=} is kept with its sign; absent means 0. */
	public void testPlacementZIsStoredSigned() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=2,r=1,z=-3"));
		assertEquals(-3, mTerminal.getPlacementAt(0, 0).z);

		moveTo(2, 0);
		enterString(place(5, 2, "c=2,r=1"));
		assertEquals("an absent z= is 0", 0, mTerminal.getPlacementAt(2, 0).z);

		moveTo(4, 0);
		enterString(place(5, 3, "c=2,r=1,z=5"));
		assertEquals(5, mTerminal.getPlacementAt(4, 0).z);
	}

	/** RF-18/RF-19/RF-20: a row pass only sees its side of the text, ordered by ascending {@code z}. */
	public void testPlacementsForRowSplitsThePassesAndOrdersByZ() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(3, 0);
		enterString(place(5, 1, "c=2,r=1,z=-1"));
		moveTo(3, 2);
		enterString(place(5, 2, "c=2,r=1,z=2"));
		moveTo(3, 4);
		enterString(place(5, 3, "c=2,r=1"));
		moveTo(3, 6);
		enterString(place(5, 4, "c=2,r=1,z=-5"));

		final List<TerminalImagePlacement> below = mTerminal.getPlacementsForRow(3, true);
		assertEquals("only the z<0 placements paint below the glyphs (RF-18)", 2, below.size());
		assertEquals("ascending z: the most negative paints first (RF-20)", 4, below.get(0).placementId);
		assertEquals(1, below.get(1).placementId);

		final List<TerminalImagePlacement> above = mTerminal.getPlacementsForRow(3, false);
		assertEquals("z=0 (absent) and z>0 paint above the glyphs (RF-18)", 2, above.size());
		assertEquals("an absent z= is 0 and paints before z=2", 3, above.get(0).placementId);
		assertEquals(2, above.get(1).placementId);

		assertEquals("a row without placements yields an empty list", 0,
			mTerminal.getPlacementsForRow(9, false).size());
	}

	/** RF-20: on equal {@code z} the older placement paints first, so the newer one ends on top. */
	public void testPlacementsForRowBreakZTiesByCreationAge() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(2, 0);
		enterString(place(5, 1, "c=2,r=1,z=-1"));
		moveTo(2, 0);
		enterString(place(5, 2, "c=2,r=1,z=-1"));

		final List<TerminalImagePlacement> below = mTerminal.getPlacementsForRow(2, true);
		assertEquals(2, below.size());
		assertTrue(below.get(0).creationSeq < below.get(1).creationSeq);
		assertEquals("the older placement paints first", 1, below.get(0).placementId);
		assertEquals("the newer placement paints last", 2, below.get(1).placementId);

		moveTo(2, 4);
		enterString(place(5, 3, "c=1,r=1"));
		moveTo(2, 4);
		enterString(place(5, 4, "c=1,r=1"));
		final List<TerminalImagePlacement> above = mTerminal.getPlacementsForRow(2, false);
		assertEquals(2, above.size());
		assertEquals(3, above.get(0).placementId);
		assertEquals(4, above.get(1).placementId);
	}

	/** RF-19: a row pass only returns the placements whose vertical span covers that row. */
	public void testPlacementsForRowOnlyCoversItsOwnRow() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=2,r=1"));
		moveTo(4, 0);
		enterString(place(5, 2, "c=3,r=2"));

		final List<TerminalImagePlacement> firstRow = mTerminal.getPlacementsForRow(0, false);
		assertEquals(1, firstRow.size());
		assertEquals(1, firstRow.get(0).placementId);
		final List<TerminalImagePlacement> secondRow = mTerminal.getPlacementsForRow(5, false);
		assertEquals("the second placement spans rows 4 and 5", 1, secondRow.size());
		assertEquals(2, secondRow.get(0).placementId);
		assertEquals("row 2 falls between both rectangles", 0, mTerminal.getPlacementsForRow(2, false).size());
		assertEquals("row 6 is past both rectangles", 0, mTerminal.getPlacementsForRow(6, false).size());
	}

	/** RF-32: a row pass never mixes placements of the other screen buffer. */
	public void testPlacementsForRowRespectsTheActiveBuffer() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(2, 2);
		enterString(place(5, 1, "c=2,r=1,z=-1"));
		final TerminalImagePlacement main = mTerminal.getPlacementsForRow(2, true).get(0);
		assertFalse(main.onAltScreen);

		enterString("\033[?1049h"); // alternate screen
		enterString(transmit(6));
		moveTo(2, 2);
		enterString(place(6, 1, "c=2,r=1,z=-1"));
		final List<TerminalImagePlacement> onAlt = mTerminal.getPlacementsForRow(2, true);
		assertEquals("the main placement must not show on the alt screen", 1, onAlt.size());
		assertTrue(onAlt.get(0).onAltScreen);

		enterString("\033[?1049l"); // back to the main buffer
		final List<TerminalImagePlacement> onMain = mTerminal.getPlacementsForRow(2, true);
		assertEquals("the alt placement must not show on the main screen", 1, onMain.size());
		assertEquals(main.key, onMain.get(0).key);
	}

	/** RF-15: an invisible placement (empty crop) never reaches a pass. */
	public void testInvisiblePlacementNeverReachesARowPass() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		// x=5 is past the 2 px wide image: registered, but with no cells.
		enterString(place(5, 1, "c=2,r=2,x=5,y=0,w=2,h=2"));
		assertEquals("the placement is registered", 1, mTerminal.getPlacementsForImage(1).size());
		assertEquals(0, mTerminal.getPlacementsForRow(0, true).size());
		assertEquals(0, mTerminal.getPlacementsForRow(0, false).size());
	}

	/** The row API reuses the caller's list: stale entries are dropped (RNF-3). */
	public void testCollectPlacementsForRowRefillsTheCallerList() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(1, 0);
		enterString(place(5, 1, "c=2,r=1,z=-2"));
		moveTo(1, 4);
		enterString(place(5, 2, "c=2,r=1,z=-1"));

		final List<TerminalImagePlacement> out = new ArrayList<>();
		out.add(mTerminal.getPlacementAt(1, 0));
		out.add(mTerminal.getPlacementAt(1, 0));
		mTerminal.collectPlacementsForRow(1, true, out);
		assertEquals("the caller's list is refilled, not appended to", 2, out.size());
		assertEquals(1, out.get(0).placementId);
		assertEquals(2, out.get(1).placementId);
		assertSame(out.get(0), mTerminal.getPlacementAt(1, 0));

		mTerminal.collectPlacementsForRow(1, false, out);
		assertEquals("an empty pass empties the list too", 0, out.size());
	}

	/** RF-21: overlapping placements leave in the cell the key of the one painted on top. */
	public void testOverlappingCellsKeepTheTopmostPlacementKey() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final TerminalImagePlacement older = mTerminal.getPlacementAt(0, 0);
		assertNotNull(older);

		// Same z, newer creation: it wins the tie (RF-20) and takes the cells it covers.
		moveTo(0, 0);
		enterString(place(5, 2, "c=2,r=1"));
		assertEquals(2, mTerminal.getPlacementsForImage(older.registryId).size());
		assertEquals(2, mTerminal.getPlacementAt(0, 0).placementId);
		assertSame(mTerminal.getPlacementAt(0, 1), mTerminal.getPlacementAt(0, 0));
		assertEquals("cells only the older placement covers keep its key", older.key,
			mTerminal.getPlacementKeyAt(0, 2));
		assertEquals(older.key, mTerminal.getPlacementKeyAt(1, 3));
		assertSame(older, mTerminal.getPlacementAt(1, 0));
		assertNoOrphanReservations();
	}

	/** RF-20/RF-21: a newer placement with a lower {@code z} must not steal an occupied cell. */
	public void testHigherZKeepsTheCellAgainstANewerLowerZ() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2,z=5"));
		final TerminalImagePlacement above = mTerminal.getPlacementAt(0, 0);
		assertNotNull(above);

		moveTo(0, 0);
		enterString(place(5, 2, "c=2,r=1,z=-1"));
		final List<TerminalImagePlacement> both = mTerminal.getPlacementsForImage(above.registryId);
		assertEquals(2, both.size());
		final TerminalImagePlacement below = (both.get(0) == above) ? both.get(1) : both.get(0);
		assertTrue("the lower z placement is the newer one", below.creationSeq > above.creationSeq);
		assertSame("the higher z placement keeps the cell", above, mTerminal.getPlacementAt(0, 0));
		assertEquals(above.key, mTerminal.getPlacementKeyAt(0, 1));
		final List<TerminalImagePlacement> belowPass = mTerminal.getPlacementsForRow(0, true);
		assertEquals("the lower placement is still painted, below the glyphs", 1, belowPass.size());
		assertEquals(below.key, belowPass.get(0).key);
		assertNoOrphanReservations();
	}

	/** RF-2/RF-21: replacing a placement in place makes it win the contested cells again. */
	public void testReplacingInPlaceWinsBackTheContestedCell() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final int replacedKey = mTerminal.getPlacementAt(0, 0).key;

		moveTo(0, 0);
		enterString(place(5, 2, "c=1,r=1"));
		assertEquals("the newer sibling owns the contested cell", 2,
			mTerminal.getPlacementAt(0, 0).placementId);

		// Same p=: replaced in place with a fresh creation age (RF-2/RF-20) and its old
		// cells released first, so it wins the tie again.
		moveTo(0, 0);
		enterString(place(5, 1, "c=4,r=2"));
		final TerminalImagePlacement replaced = mTerminal.getPlacement(replacedKey);
		assertNotNull("a replacement keeps the placement key", replaced);
		assertEquals(replacedKey, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(replacedKey, mTerminal.getPlacementKeyAt(0, 3));
		assertEquals(replacedKey, mTerminal.getPlacementKeyAt(1, 3));
		assertEquals("no cell outside the rectangle is reserved", 0, mTerminal.getPlacementKeyAt(0, 4));
		assertNoOrphanReservations();
	}

	// --- T7: full OpenTUI run sequences (§7.3) and boundary cases ---

	/**
	 * §7.3 steps 1-3 (RF-1, RF-4, RF-6, RF-7, RF-11, RF-18, RF-20, RF-21, RF-36): the PNG is
	 * transmitted once without showing it, then two cropped runs of that image are placed from
	 * repositioned cursors, each with its own rectangle and its own crop, and a third run ties
	 * with the second one.
	 */
	public void testOpenTuiSequencePlacesTwoCroppedRunsOfOneImage() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		assertNotNull("a=t registers the payload", mTerminal.getImageData(1));
		assertEquals("a=t reserves no cells", 0, mTerminal.getPlacementKeyAt(0, 0));

		// First run: the cursor is moved by the client, a=p only takes its origin (RF-6).
		moveTo(2, 4);
		enterString(placeRun(5, 1, "c=3,r=2", "x=0,y=0,w=1,h=2", -1, 2));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(2, 4);
		assertNotNull(first);
		assertEquals("a=p never moves the cursor", 2, mTerminal.getCursorRow());
		assertEquals(4, mTerminal.getCursorCol());
		assertEquals("c=/r= are used verbatim (RF-7)", 3, first.cellsW);
		assertEquals(2, first.cellsH);
		assertEquals(0, first.cropX);
		assertEquals(0, first.cropY);
		assertEquals("x/y/w/h are the source crop in pixels (RF-11)", 1, first.cropW);
		assertEquals(2, first.cropH);
		assertEquals(-1, first.z);

		// Second run: same image, another p=, another crop and other cells.
		moveTo(5, 10);
		enterString(placeRun(5, 2, "c=2,r=3", "x=1,y=1,w=1,h=1", 0, 2));
		final TerminalImagePlacement second = mTerminal.getPlacementAt(5, 10);
		assertNotNull(second);
		assertEquals(5, mTerminal.getCursorRow());
		assertEquals(10, mTerminal.getCursorCol());
		assertEquals(2, second.cellsW);
		assertEquals(3, second.cellsH);
		assertEquals(1, second.cropX);
		assertEquals(1, second.cropY);
		assertEquals(1, second.cropW);
		assertEquals(1, second.cropH);
		assertEquals("an explicit z=0 paints above the glyphs (RF-18)", 0, second.z);

		// Both runs coexist with their own identity and share one payload (RF-1/RF-4/RF-36).
		assertTrue(first.key != second.key);
		assertEquals(1, first.placementId);
		assertEquals(2, second.placementId);
		assertEquals(first.registryId, second.registryId);
		assertSame(mTerminal.getImageData(first.registryId), mTerminal.getImageData(second.registryId));
		assertEquals(2, mTerminal.getPlacementsForImage(first.registryId).size());
		assertSame(mTerminal.getImageData(first.registryId), mTerminal.getImageDataAt(3, 6));
		assertSame(mTerminal.getImageData(first.registryId), mTerminal.getImageDataAt(5, 10));

		// Each run reserves exactly its own rectangle (RF-22).
		assertEquals(first.key, mTerminal.getPlacementKeyAt(2, 4));
		assertEquals(first.key, mTerminal.getPlacementKeyAt(3, 6));
		assertEquals(0, mTerminal.getPlacementKeyAt(4, 4));
		assertEquals(0, mTerminal.getPlacementKeyAt(2, 7));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(5, 11));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(7, 10));
		assertEquals(0, mTerminal.getPlacementKeyAt(5, 9));
		assertEquals(0, mTerminal.getPlacementKeyAt(8, 10));

		// The pass split follows z: the z<0 run paints below the glyphs, the z=0 one above.
		assertEquals(1, mTerminal.getPlacementsForRow(2, true).size());
		assertEquals(first.key, mTerminal.getPlacementsForRow(2, true).get(0).key);
		assertEquals(0, mTerminal.getPlacementsForRow(2, false).size());
		assertEquals(0, mTerminal.getPlacementsForRow(5, true).size());
		assertEquals(1, mTerminal.getPlacementsForRow(5, false).size());
		assertEquals(second.key, mTerminal.getPlacementsForRow(5, false).get(0).key);

		// A third cropped run over the second one with the same z: age breaks the tie (RF-20/RF-21).
		moveTo(5, 10);
		enterString(placeRun(5, 3, "c=2,r=1", "x=0,y=1,w=1,h=1", 0, 2));
		final TerminalImagePlacement third = mTerminal.getPlacementAt(5, 10);
		assertNotNull(third);
		assertEquals(3, third.placementId);
		assertEquals("the newer run with the same z keeps the contested cell", third.key,
			mTerminal.getPlacementKeyAt(5, 10));
		assertSame(third, mTerminal.getPlacementAt(5, 11));
		assertEquals("cells only the second run covers stay with it", second.key,
			mTerminal.getPlacementKeyAt(6, 11));
		final List<TerminalImagePlacement> above = mTerminal.getPlacementsForRow(5, false);
		assertEquals(2, above.size());
		assertEquals("on equal z the older run paints first (RF-20)", 2, above.get(0).placementId);
		assertEquals(3, above.get(1).placementId);
		assertNoOrphanReservations();
	}

	/**
	 * §7.3 step 4 (RF-24, RF-25, RF-26, RF-29): {@code d=p} drops only its run and keeps the
	 * payload and the sibling, {@code d=i} drops the payload and the remaining run, and after the
	 * delete the same {@code p=} starts a fresh run.
	 */
	public void testOpenTuiSequenceDeletesOneRunThenTheImage() {
		final TerminalImagePlacement[] runs = withTwoCroppedRuns();
		final TerminalImagePlacement first = runs[0];
		final TerminalImagePlacement second = runs[1];
		final int regId = first.registryId;

		enterString(kg("a=d,d=p,i=5,p=1,q=2", ""));
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertNull("the deleted run is gone", mTerminal.getPlacement(first.key));
		assertEquals("the sibling keeps its own cells", second.key, mTerminal.getPlacementKeyAt(5, 10));
		assertEquals(second.key, mTerminal.getPlacementKeyAt(7, 11));
		assertEquals("the deleted run releases exactly its rectangle", 0, mTerminal.getPlacementKeyAt(2, 4));
		assertEquals(0, mTerminal.getPlacementKeyAt(3, 6));
		assertEquals("the sibling keeps its crop untouched", 1, second.cropW);
		assertEquals(1, second.cropH);
		assertNotNull("the payload survives d=p", mTerminal.getImageDataAt(5, 10));
		assertNull(mTerminal.getImageDataAt(2, 4));
		assertEquals(0, mTerminal.getPlacementsForRow(2, true).size());
		assertSame(second, mTerminal.getPlacementsForRow(5, false).get(0));
		assertNoOrphanReservations();

		// d=i takes the payload and the remaining run with it (RF-24).
		enterString(kg("a=d,d=i,i=5,q=2", ""));
		assertNull(mTerminal.getImageData(regId));
		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertNull(mTerminal.getPlacement(second.key));
		assertEquals(0, mTerminal.getPlacementKeyAt(5, 10));
		assertEquals(0, mTerminal.getPlacementKeyAt(7, 11));
		assertNull(mTerminal.getImageDataAt(5, 10));
		assertNoOrphanReservations();

		// The image is gone with its payload: after re-transmitting it the same p= is a fresh
		// run with its own key and geometry (RF-24/RF-26).
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(placeRun(5, 1, "c=1,r=1", "x=1,y=0,w=1,h=1", 3, 2));
		final TerminalImagePlacement again = mTerminal.getPlacementAt(0, 0);
		assertNotNull(again);
		assertEquals(1, again.placementId);
		assertTrue("keys are never reused", again.key != first.key);
		assertEquals(1, again.cropX);
		assertEquals(3, again.z);
		assertNoOrphanReservations();
	}

	/**
	 * RF-5/RF-29: retransmitting the same {@code i=} while two cropped runs are live drops the old
	 * payload's runs, their cells and their identity, and the same {@code p=} starts fresh.
	 */
	public void testRetransmitDropsBothCroppedRunsAndTheirIdentity() {
		final TerminalImagePlacement[] runs = withTwoCroppedRuns();
		final TerminalImagePlacement first = runs[0];
		final TerminalImagePlacement second = runs[1];
		final int regId = first.registryId;
		final TerminalImageData payload = mTerminal.getImageData(regId);

		enterString(transmit(5));

		assertEquals(0, mTerminal.getPlacementsForImage(regId).size());
		assertNull(mTerminal.getPlacement(first.key));
		assertNull(mTerminal.getPlacement(second.key));
		assertEquals(0, mTerminal.getPlacementKeyAt(2, 4));
		assertEquals(0, mTerminal.getPlacementKeyAt(5, 10));
		assertEquals(0, mTerminal.getPlacementsForRow(2, true).size());
		assertEquals(0, mTerminal.getPlacementsForRow(5, false).size());
		assertNull(mTerminal.getImageDataAt(2, 4));
		assertNotNull("the registry slot holds the new payload", mTerminal.getImageData(regId));
		assertTrue("the old payload is replaced", payload != mTerminal.getImageData(regId));
		assertNoOrphanReservations();

		// The old identity table is gone: p=1 is a new run with its own key and crop.
		moveTo(0, 0);
		enterString(placeRun(5, 1, "c=1,r=1", "x=1,y=0,w=1,h=1", -2, 2));
		final TerminalImagePlacement again = mTerminal.getPlacementAt(0, 0);
		assertNotNull(again);
		assertEquals(1, again.placementId);
		assertTrue(again.key != first.key && again.key != second.key);
		assertEquals(1, again.cropX);
		assertEquals(-2, again.z);
		assertEquals(1, mTerminal.getPlacementsForImage(regId).size());
		assertNoOrphanReservations();
	}

	/**
	 * RF-8/RF-31: a run crossing the right border keeps its rectangle whole and is clipped by the
	 * painting geometry, never rescaled, and it only reserves and paints its own rows.
	 */
	public void testRunCrossingTheRightBorderIsClippedButNotRescaled() {
		withTerminalSized(20, 6);
		enterString(transmit(5));
		moveTo(2, 18);
		enterString(placeRun(5, 1, "c=5,r=2", "x=0,y=0,w=2,h=2", -1, 2));
		final TerminalImagePlacement crossing = mTerminal.getPlacementAt(2, 18);
		assertNotNull(crossing);
		assertEquals("the rectangle is stored whole", 5, crossing.cellsW);
		assertEquals(2, crossing.cellsH);
		assertEquals(2, crossing.cropW);
		assertEquals(2, crossing.cropH);

		final int[] visible = KittyPlacementGeometry.clipToVisibleArea(crossing.startRow, crossing.startCol,
			crossing.cellsW, crossing.cellsH, 0, 6, 20);
		assertNotNull(visible);
		assertEquals(2, visible[0]);
		assertEquals(4, visible[1]);
		assertEquals(18, visible[2]);
		assertEquals(20, visible[3]);
		assertEquals("only the visible part is painted", 2, visible[3] - visible[2]);
		assertTrue("no rescale: the clip is smaller than the rectangle", visible[3] - visible[2] < crossing.cellsW);

		assertEquals(crossing.key, mTerminal.getPlacementKeyAt(2, 18));
		assertEquals(crossing.key, mTerminal.getPlacementKeyAt(3, 19));
		assertEquals("reservations stop at the border", 0, mTerminal.getPlacementKeyAt(2, 17));
		assertEquals(1, mTerminal.getPlacementsForRow(2, true).size());
		assertEquals(1, mTerminal.getPlacementsForRow(3, true).size());
		assertEquals(0, mTerminal.getPlacementsForRow(1, true).size());
		assertEquals(0, mTerminal.getPlacementsForRow(4, true).size());
		assertNoOrphanReservations();
	}

	/**
	 * RF-8/RF-30: two cropped runs follow their rows through IL/DL/RI and scroll; the one whose
	 * rectangle overruns the bottom comes back into the visible area when the edit is undone, and
	 * the one pushed out through the top stays live — its cells travel into the transcript, where
	 * the row pass still returns it (it is painted again as soon as the view scrolls back).
	 */
	public void testScrollAndLineEditsMoveBothCroppedRunsAndTheyComeBack() {
		withTerminalSized(20, 6);
		enterString(transmit(5));
		moveTo(1, 2);
		enterString(placeRun(5, 1, "c=2,r=1", "x=0,y=0,w=1,h=1", -1, 2));
		moveTo(4, 5);
		enterString(placeRun(5, 2, "c=2,r=2", "x=1,y=1,w=1,h=1", -1, 2));
		final TerminalImagePlacement top = mTerminal.getPlacementAt(1, 2);
		final TerminalImagePlacement bottom = mTerminal.getPlacementAt(4, 5);
		assertNotNull(top);
		assertNotNull(bottom);

		// IL at the top pushes both rectangles one row down.
		enterString("\033[1;1H\033[1L");
		assertEquals(2, top.startRow);
		assertEquals(5, bottom.startRow);
		assertEquals(top.key, mTerminal.getPlacementKeyAt(2, 3));
		assertEquals(bottom.key, mTerminal.getPlacementKeyAt(5, 6));
		// The bottom run now overruns the grid: stored whole, clipped only when painting.
		assertEquals(2, bottom.cellsH);
		final int[] overrun = KittyPlacementGeometry.clipToVisibleArea(bottom.startRow, bottom.startCol,
			bottom.cellsW, bottom.cellsH, 0, 6, 20);
		assertNotNull(overrun);
		assertEquals(5, overrun[0]);
		assertEquals(6, overrun[1]);
		assertEquals(1, mTerminal.getPlacementsForRow(5, true).size());

		// DL at the top pulls both back up: the run that overran re-enters the visible area.
		enterString("\033[1;1H\033[1M");
		assertEquals(1, top.startRow);
		assertEquals(4, bottom.startRow);
		final int[] backInside = KittyPlacementGeometry.clipToVisibleArea(bottom.startRow, bottom.startCol,
			bottom.cellsW, bottom.cellsH, 0, 6, 20);
		assertNotNull(backInside);
		assertEquals(4, backInside[0]);
		assertEquals(6, backInside[1]);
		assertEquals(bottom.key, mTerminal.getPlacementKeyAt(4, 5));
		// DL blanks the row that fell off the bottom and never re-stamps: the reservation of the
		// new blank row is gone, but the run still paints its whole rectangle (the painter walks
		// placements, not cells, RF-30).
		assertEquals(0, mTerminal.getPlacementKeyAt(5, 6));
		assertSame(bottom, mTerminal.getPlacementsForRow(4, true).get(0));
		assertSame(bottom, mTerminal.getPlacementsForRow(5, true).get(0));

		// RI at the top pushes them down again, exactly like the IL did.
		enterString("\033[1;1H\033M");
		assertEquals(2, top.startRow);
		assertEquals(5, bottom.startRow);

		// Three scrolls at the foot take the top run out through the top: it is not destroyed.
		enterString("\033[6;1H\n\n\n");
		assertEquals(-1, top.startRow);
		assertEquals(2, bottom.startRow);
		assertTrue(top.isVisible());
		assertSame(top, mTerminal.getPlacement(top.key));
		assertEquals("the reservation travels into the transcript", top.key,
			mTerminal.getPlacementKeyAt(-1, 2));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 2));
		assertNull("a rectangle completely outside the visible area clips to nothing",
			KittyPlacementGeometry.clipToVisibleArea(top.startRow, top.startCol, top.cellsW, top.cellsH,
				0, 6, 20));
		assertEquals("it is not painted on the visible rows", 0, mTerminal.getPlacementsForRow(0, true).size());
		assertEquals("but it is painted again from its transcript row", 1,
			mTerminal.getPlacementsForRow(-1, true).size());
		assertSame(top, mTerminal.getPlacementsForRow(-1, true).get(0));
		assertSame(bottom, mTerminal.getPlacementsForRow(2, true).get(0));
		assertSame(bottom, mTerminal.getPlacementsForRow(3, true).get(0));
		assertNoOrphanReservations();
	}

	/**
	 * RF-31: resizing to fewer columns neither discards, moves nor rescales a cropped run; one
	 * whose origin falls outside the new grid stays live (clipped when painting) and both keep
	 * being collected for their rows.
	 */
	public void testResizeToFewerColumnsKeepsBothCroppedRunsAndTheirCrops() {
		withTerminalSized(20, 6);
		enterString(transmit(5));
		moveTo(1, 2);
		enterString(placeRun(5, 1, "c=4,r=2", "x=0,y=0,w=1,h=2", -1, 2));
		moveTo(3, 15);
		enterString(placeRun(5, 2, "c=3,r=2", "x=1,y=0,w=1,h=2", 1, 2));
		final TerminalImagePlacement inside = mTerminal.getPlacementAt(1, 2);
		final TerminalImagePlacement outside = mTerminal.getPlacementAt(3, 15);
		assertNotNull(inside);
		assertNotNull(outside);
		final long creationSeq = outside.creationSeq;

		resize(10, 6);

		assertSame("resize must not discard a run", inside, mTerminal.getPlacement(inside.key));
		assertSame(outside, mTerminal.getPlacement(outside.key));
		assertEquals("resize must not move the origin", 1, inside.startRow);
		assertEquals(2, inside.startCol);
		assertEquals("resize must not rescale the rectangle", 4, inside.cellsW);
		assertEquals(2, inside.cellsH);
		assertEquals("the crop survives the resize", 1, inside.cropW);
		assertEquals(2, inside.cropH);
		assertEquals(-1, inside.z);
		assertEquals(15, outside.startCol);
		assertEquals(3, outside.cellsW);
		assertEquals("resize must not re-create a run", creationSeq, outside.creationSeq);
		assertTrue(inside.isVisible() && outside.isVisible());

		// Both are still painted on their rows (clipped to the new grid when painting).
		assertSame(inside, mTerminal.getPlacementsForRow(1, true).get(0));
		assertSame(inside, mTerminal.getPlacementsForRow(2, true).get(0));
		assertSame(outside, mTerminal.getPlacementsForRow(3, false).get(0));
		assertSame(outside, mTerminal.getPlacementsForRow(4, false).get(0));
		assertEquals("an origin past the new right border reserves nothing there", 0,
			mTerminal.getPlacementKeyAt(3, 0));
		assertNoOrphanReservations();
	}

	/**
	 * RF-32: each screen buffer owns its runs: the alternate buffer neither shows nor wipes the
	 * main ones, and re-entering it discards only its own, leaving no ghost copy behind.
	 */
	public void testAltScreenKeepsCroppedRunsPerBufferWithoutGhostCopy() {
		withTerminalSized(20, 6);
		enterString(transmit(5));
		moveTo(1, 1);
		enterString(placeRun(5, 1, "c=3,r=2", "x=0,y=0,w=1,h=2", -1, 2));
		final TerminalImagePlacement main = mTerminal.getPlacementAt(1, 1);
		assertNotNull(main);
		assertFalse(main.onAltScreen);

		enterString("\033[?1049h");
		assertEquals("the main run must not paint on the alt screen", 0, mTerminal.getPlacementsForRow(1, true).size());
		assertEquals(0, mTerminal.getPlacementKeyAt(1, 1));
		assertNull(mTerminal.getImageDataAt(1, 1));

		enterString(transmit(6));
		moveTo(1, 1);
		enterString(placeRun(6, 1, "c=3,r=2", "x=1,y=0,w=1,h=2", -1, 2));
		final TerminalImagePlacement alt = mTerminal.getPlacementAt(1, 1);
		assertNotNull(alt);
		assertTrue(alt.onAltScreen);
		assertTrue(alt.registryId != main.registryId);
		assertSame(alt, mTerminal.getPlacementsForRow(1, true).get(0));

		enterString("\033[?1049l");
		assertSame("no ghost copy of the alt run in the main buffer", main, mTerminal.getPlacementAt(1, 1));
		assertSame(main, mTerminal.getPlacementsForRow(1, true).get(0));
		final TerminalImageData onMain = mTerminal.getImageDataAt(1, 1);
		assertNotNull(onMain);
		assertEquals("the cell resolves the main payload, not the alt one", main.registryId, onMain.id);

		enterString("\033[?1049h"); // re-entry wipes the alt buffer and its runs
		assertNull("only the alt run is discarded", mTerminal.getPlacement(alt.key));
		assertNotNull(mTerminal.getPlacement(main.key));
		assertEquals(0, mTerminal.getPlacementsForRow(1, true).size());

		enterString("\033[?1049l");
		assertSame(main, mTerminal.getPlacementAt(1, 1));
		assertEquals(main.key, mTerminal.getPlacementKeyAt(1, 2));
		assertNoOrphanReservations();
	}

	/**
	 * RF-29/RF-36/RF-37/RNF-7: the per-image cap evicts the oldest run — freeing the cells of its
	 * own rectangle, not just the shared ones — keeps a single payload and forgets the evicted
	 * identity, so the same {@code p=} starts over while the cap still bounds the live runs.
	 */
	public void testPlacementCapEvictsTheOldestRunAndFreesItsOwnCells() {
		withTerminalSized(20, 10);
		enterString(transmit(5));
		moveTo(0, 0);
		enterString(placeRun(5, 1, "c=2,r=1", "x=0,y=0,w=1,h=1", -1, 2));
		final TerminalImagePlacement oldest = mTerminal.getPlacementAt(0, 0);
		assertNotNull(oldest);
		final int regId = oldest.registryId;
		final TerminalImageData payload = mTerminal.getImageData(regId);
		assertNotNull(payload);

		// Fill the cap with runs somewhere else, so the oldest rectangle stays its own.
		moveTo(5, 0);
		for (int p = 2; p <= 257; p++) {
			enterString(placeRun(5, p, "c=2,r=1", "x=1,y=0,w=1,h=1", -1, 2));
		}

		final List<TerminalImagePlacement> placements = mTerminal.getPlacementsForImage(regId);
		assertEquals(256, placements.size());
		assertEquals("the oldest run (p=1) must be evicted", 2, placements.get(0).placementId);
		assertEquals(257, placements.get(placements.size() - 1).placementId);
		assertNull(mTerminal.getPlacement(oldest.key));
		assertEquals("the evicted run frees the cells of its own rectangle", 0,
			mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 1));
		assertSame("all runs keep sharing one payload, counted once (RF-36)", payload,
			mTerminal.getImageData(regId));
		assertSame(payload, mTerminal.getImageDataAt(5, 0));

		// The evicted identity is forgotten: p=1 is a new run and the cap still bounds the count.
		moveTo(8, 0);
		enterString(placeRun(5, 1, "c=2,r=1", "x=0,y=1,w=1,h=1", -1, 2));
		final TerminalImagePlacement reused = mTerminal.getPlacementAt(8, 0);
		assertNotNull(reused);
		assertEquals(1, reused.placementId);
		assertTrue("keys are never reused", reused.key != oldest.key);
		assertEquals(256, mTerminal.getPlacementsForImage(regId).size());
		assertSame(payload, mTerminal.getImageData(reused.registryId));
		assertNoOrphanReservations();
	}

	/**
	 * RF-38/RF-29: when the registry budget evicts an image, every cropped run of it goes — both
	 * rectangles release their cells and nothing points at a discarded placement any more.
	 */
	public void testBudgetEvictionDropsBothCroppedRunsOfTheEvictedImage() {
		withTerminalSized(20, 6);
		// 1,000,000 unpadded base64 chunks = 3,000,000 decoded bytes: just under the per-image cap
		// (3 MiB) and just above a third of the registry budget (24 MiB), so nine payloads evict.
		final String payload = evictionPayload(1_000_000);
		enterString(kg("a=t,i=1,f=100,q=2", payload));
		moveTo(0, 0);
		enterString(placeRun(1, 1, "c=2,r=1", "x=0,y=0,w=2,h=1", -1, 2));
		moveTo(3, 0);
		enterString(placeRun(1, 2, "c=2,r=1", "x=1,y=0,w=1,h=1", 0, 2));
		final TerminalImagePlacement first = mTerminal.getPlacementAt(0, 0);
		final TerminalImagePlacement second = mTerminal.getPlacementAt(3, 0);
		assertNotNull(first);
		assertNotNull(second);
		assertEquals(1, first.registryId);
		assertEquals(2, mTerminal.getPlacementsForImage(1).size());

		for (int id = 2; id <= 9; id++) {
			enterString(kg("a=t,i=" + id + ",f=100,q=2", payload));
		}

		assertNull("the evicted payload is gone", mTerminal.getImageData(1));
		assertTrue("no run survives its payload", mTerminal.getPlacementsForImage(1).isEmpty());
		assertNull(mTerminal.getPlacement(first.key));
		assertNull(mTerminal.getPlacement(second.key));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(0, 1));
		assertEquals(0, mTerminal.getPlacementKeyAt(3, 0));
		assertEquals(0, mTerminal.getPlacementKeyAt(3, 1));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertNull(mTerminal.getImageDataAt(3, 0));
		assertEquals(0, mTerminal.getPlacementsForRow(0, true).size());
		assertEquals(0, mTerminal.getPlacementsForRow(3, false).size());
		assertNoOrphanReservations();
		assertNotNull("the surviving payloads are still addressable", mTerminal.getImageData(9));
	}

	/**
	 * §7.3 step 5 / the {@code q=} rules: the placement outcome is the same with {@code q=0},
	 * {@code q=1} and {@code q=2}; only the reply emission changes ({@code OK} is hidden from
	 * {@code q>=1}, failures from {@code q>=2}).
	 */
	public void testPlaceRepliesFollowQuietModeButTheOutcomeDoesNotChange() {
		withTerminalSized(20, 10);
		enterString(transmit(5));

		// q=0: OK is replied and the run is placed.
		moveTo(0, 0);
		enterString(kg("a=p,i=5,p=1,c=2,r=1,x=0,y=0,w=1,h=1,C=1,z=0,q=0", ""));
		String reply = mOutput.getOutputAndClear();
		assertTrue("q=0 must reply OK, got: " + reply, reply.contains("OK"));
		assertEquals(1, mTerminal.getPlacementsForImage(1).size());

		// q=1: no reply, same outcome.
		moveTo(3, 0);
		enterString(kg("a=p,i=5,p=2,c=2,r=1,x=0,y=0,w=1,h=1,q=1", ""));
		assertEquals("q=1 hides OK but must not change the outcome", "", mOutput.getOutputAndClear());
		assertEquals(2, mTerminal.getPlacementsForImage(1).size());
		assertEquals(3, mTerminal.getPlacementAt(3, 0).startRow);

		// q=2: silent again, same outcome.
		moveTo(6, 0);
		enterString(kg("a=p,i=5,p=3,c=2,r=1,q=2", ""));
		assertEquals("", mOutput.getOutputAndClear());
		assertEquals(3, mTerminal.getPlacementsForImage(1).size());
		assertEquals("no crop key at all means the whole image (RF-16)", 0,
			mTerminal.getPlacementAt(6, 0).cropW);

		// ENOENT: q=0 replies, q=1 keeps errors, q=2 hides them — never a placement.
		moveTo(9, 0);
		enterString(kg("a=p,i=77,p=1,c=2,r=1,q=0", ""));
		reply = mOutput.getOutputAndClear();
		assertTrue("q=0 must reply ENOENT, got: " + reply, reply.contains("ENOENT"));
		enterString(kg("a=p,i=77,p=1,c=2,r=1,q=1", ""));
		reply = mOutput.getOutputAndClear();
		assertTrue("q=1 must keep errors, got: " + reply, reply.contains("ENOENT"));
		enterString(kg("a=p,i=77,p=1,c=2,r=1,q=2", ""));
		assertEquals("q=2 must hide errors", "", mOutput.getOutputAndClear());
		assertEquals(3, mTerminal.getPlacementsForImage(1).size());

		// INVALID (no cells and no known pixel size) follows the same rules and places nothing.
		enterString(kg("a=t,f=24,i=8,q=2", "AAAA"));
		mOutput.getOutputAndClear();
		moveTo(9, 0);
		enterString(kg("a=p,i=8,p=1,q=0", ""));
		reply = mOutput.getOutputAndClear();
		assertTrue("q=0 must reply INVALID, got: " + reply, reply.contains("INVALID"));
		enterString(kg("a=p,i=8,p=1,q=1", ""));
		reply = mOutput.getOutputAndClear();
		assertTrue("q=1 must keep INVALID, got: " + reply, reply.contains("INVALID"));
		enterString(kg("a=p,i=8,p=1,q=2", ""));
		assertEquals("q=2 must hide INVALID", "", mOutput.getOutputAndClear());
		assertEquals(0, mTerminal.getPlacementsForImage(2).size());
		assertEquals(0, mTerminal.getPlacementKeyAt(9, 0));
		assertNoOrphanReservations();
	}
}
