package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Unicode placeholders (kitty {@code U=1} virtual placements + {@code U+10EEEE}
 * cell decoding): creation semantics, id resolution, diacritic table and the
 * left-to-right inheritance rules (post-INFORME F3).
 */
public class KittyPlaceholderTest extends TerminalTestCase {

	private static final String PNG_1x1_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	/** {@code U+10EEEE} as a UTF-16 surrogate pair (Java {@code \\u} escapes are 4 hex digits). */
	private static final String PH = "\uDBFB\uDEEE";

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	private static class Found {
		final int column;
		final KittyPlaceholderDecoder.Target target;

		Found(int column, KittyPlaceholderDecoder.Target target) {
			this.column = column;
			this.target = target;
		}
	}

	private List<Found> collectRow(int externalRow) {
		final TerminalBuffer screen = mTerminal.getScreen();
		final TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow));
		final KittyPlaceholderDecoder.RowState state = new KittyPlaceholderDecoder.RowState();
		final List<Found> found = new ArrayList<>();
		KittyPlaceholderDecoder.collectRow(line, mTerminal.mColumns, state, (column, target) ->
			found.add(new Found(column, target)));
		return found;
	}

	// --- virtual placement creation ---

	public void testVirtualPlacementCreatesNoStampAndNoCursorMove() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=7,f=100,s=1,v=1", PNG_1x1_B64));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		enterString("\033[3;4H");
		enterString(kg("a=p,U=1,i=7,c=3,r=2", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		// No cells stamped, cursor untouched: virtual placements are invisible.
		assertEquals(0, mTerminal.getScreen().getImageAt(2, 3));
		assertCursorAt(2, 3);
		final KittyVirtualPlacement vp = mTerminal.resolveVirtualPlacement(7);
		assertNotNull(vp);
		assertEquals(3, vp.cols);
		assertEquals(2, vp.rows);
		assertNotNull(mTerminal.getImageData(vp.registryId));
	}

	public void testTransmitAndDisplayWithVirtualPlacement() {
		withTerminalSized(20, 6);
		enterString("\033[1;1H");
		enterString(kg("a=T,U=1,i=9,c=2,r=2,f=100,s=1,v=1", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		// Combined transmit+virtual: never stamps physical cells.
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertCursorAt(0, 0);
		final KittyVirtualPlacement vp = mTerminal.resolveVirtualPlacement(9);
		assertNotNull(vp);
		assertEquals(2, vp.cols);
		assertEquals(2, vp.rows);
	}

	public void testVirtualPlacementMissingImageRepliesEnoent() {
		withTerminalSized(20, 6);
		enterString(kg("a=p,U=1,i=99,c=2,r=2", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected ENOENT, got: " + reply, reply.contains("ENOENT"));
		assertNull(mTerminal.resolveVirtualPlacement(99));
	}

	public void testVirtualPlacementDimsFallbackToPixels() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=7,f=100,s=26,v=30", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=7", "")); // no c=/r= → derived from pixel size
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
		final KittyVirtualPlacement vp = mTerminal.resolveVirtualPlacement(7);
		assertNotNull(vp);
		assertTrue(vp.cols >= 1);
		assertTrue(vp.rows >= 1);
	}

	public void testDeleteImageRemovesVirtualPlacement() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=7,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=7,c=2,r=2", ""));
		mOutput.getOutputAndClear();
		assertNotNull(mTerminal.resolveVirtualPlacement(7));
		enterString(kg("a=d,d=i,i=7", ""));
		mOutput.getOutputAndClear();
		assertNull(mTerminal.resolveVirtualPlacement(7));
	}

	public void testKillSwitchHidesVirtualPlacements() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=7,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=7,c=2,r=2", ""));
		mOutput.getOutputAndClear();
		assertNotNull(mTerminal.resolveVirtualPlacement(7));
		mTerminal.setTerminalImagesEnabled(false);
		assertNull(mTerminal.resolveVirtualPlacement(7));
	}

	// --- placeholder cell decoding ---

	public void testDiacriticTableIndices() {
		// Spec: U+0305 = 0, U+030D = 1, U+030E = 2 (msb slot in the examples).
		assertEquals(0, KittyPlaceholderDecoder.diacriticIndex(0x0305));
		assertEquals(1, KittyPlaceholderDecoder.diacriticIndex(0x030D));
		assertEquals(2, KittyPlaceholderDecoder.diacriticIndex(0x030E));
		assertEquals(296, KittyPlaceholderDecoder.diacriticIndex(0x1D244));
		// Not a row/column diacritic (and an accent clients must not confuse).
		assertEquals(-1, KittyPlaceholderDecoder.diacriticIndex(0x0301));
		assertEquals(-1, KittyPlaceholderDecoder.diacriticIndex('a'));
	}

	public void testFullDiacriticPairDecodesRowColumnAndId() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=42,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=42,c=4,r=5", ""));
		mOutput.getOutputAndClear();
		// Cell (row 1, col 0): fg 38;5;42 + placeholder + U+030D(row=1) + U+0305(col=0).
		enterString("\033[38;5;42m" + PH + "\u030D\u0305");
		final List<Found> found = collectRow(0);
		assertEquals(1, found.size());
		assertEquals(0, found.get(0).column);
		assertEquals(42, found.get(0).target.imageId);
		assertEquals(1, found.get(0).target.gridRow);
		assertEquals(0, found.get(0).target.gridCol);
	}

	public void testTruecolorForegroundEncodesTwentyFourBitId() {
		withTerminalSized(20, 6);
		// SGR 38;2;0;15;16 → low-24 id = 0x000FF0 = 4080.
		enterString("\033[38;2;0;15;16m" + PH + "\u0305\u0305");
		final List<Found> found = collectRow(0);
		assertEquals(1, found.size());
		assertEquals((0 << 16) | (15 << 8) | 16, found.get(0).target.imageId);
	}

	public void testMsbDiacriticExtendsImageId() {
		withTerminalSized(20, 6);
		final int fullId = 42 + (2 << 24);
		enterString(kg("a=t,i=" + fullId + ",f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=" + fullId + ",c=2,r=2", ""));
		mOutput.getOutputAndClear();
		// Third diacritic U+030E = 2 → most significant id byte.
		enterString("\033[38;5;42m" + PH + "\u0305\u0305\u030E");
		final List<Found> found = collectRow(0);
		assertEquals(1, found.size());
		assertEquals(fullId, found.get(0).target.imageId);
		assertNotNull(mTerminal.resolveVirtualPlacement(found.get(0).target.imageId));
	}

	public void testInheritanceFromLeftCell() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=42,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=42,c=4,r=4", ""));
		mOutput.getOutputAndClear();
		// Row 0: only the first cell carries the row diacritic (spec 2x3 example shape).
		enterString("\033[38;5;42m" + PH + "\u0305" + PH + PH);
		final List<Found> found = collectRow(0);
		assertEquals(3, found.size());
		assertEquals(0, found.get(0).target.gridCol);
		assertEquals(0, found.get(0).target.gridRow);
		assertEquals(1, found.get(1).target.gridCol);
		assertEquals(0, found.get(1).target.gridRow);
		assertEquals(2, found.get(2).target.gridCol);
		assertEquals(0, found.get(2).target.gridRow);
		for (Found f : found) assertEquals(42, f.target.imageId);
	}

	public void testDefaultForegroundIsNotAResolvableId() {
		withTerminalSized(20, 6);
		enterString(PH + "\u0305\u0305"); // no SGR: default fg (index 256) ≠ image id
		assertTrue(collectRow(0).isEmpty());
	}

	public void testUnresolvableCellDoesNotPoisonInheritance() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=42,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=42,c=4,r=4", ""));
		mOutput.getOutputAndClear();
		// Default-fg placeholder (unresolvable), then a valid explicit cell.
		enterString(PH + "\u0305\u0305");
		enterString("\033[38;5;42m" + PH + "\u0305\u0305");
		final List<Found> found = collectRow(0);
		assertEquals(1, found.size());
		assertEquals(0, found.get(0).target.gridRow);
		assertEquals(0, found.get(0).target.gridCol);
		assertEquals(42, found.get(0).target.imageId);
	}
}
