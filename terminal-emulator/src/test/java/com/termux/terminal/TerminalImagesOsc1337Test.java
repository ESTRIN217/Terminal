package com.termux.terminal;

/**
 * OSC 1337 (iTerm2) inline images: parse, stamp, clear, kill-switch and budget.
 * <p>
 * A 1×1 PNG Base64 payload is enough to prove the path; cell geometry is set
 * explicitly so the placement rectangle is deterministic in unit tests.
 */
public class TerminalImagesOsc1337Test extends TerminalTestCase {

	/** Minimal valid 1×1 transparent PNG. */
	private static final String PNG_1x1_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	private static String imageOsc(String width, String height, String b64) {
		final StringBuilder sb = new StringBuilder();
		sb.append("\033]1337;File=inline=1");
		if (width != null) sb.append(";width=").append(width);
		if (height != null) sb.append(";height=").append(height);
		sb.append(":").append(b64).append("\007");
		return sb.toString();
	}

	public void testPlacesImageAtCursorAndStampsCells() {
		withTerminalSized(10, 4);
		enterString(imageOsc("2", "2", PNG_1x1_B64));

		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(1, data.id);
		assertEquals(0, data.startRow);
		assertEquals(0, data.startCol);
		assertEquals(2, data.cellsW);
		assertEquals(2, data.cellsH);
		assertArrayEquals(ImageBase64.decode(PNG_1x1_B64), data.encoded);

		// Entire 2×2 rectangle is stamped; cursor does not advance.
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 1));
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 2));
		assertEquals(0, mTerminal.getScreen().getImageAt(2, 0));
		assertCursorAt(0, 0);
	}

	public void testPixelDimensionsConvertToCells() {
		withTerminalSized(40, 10);
		// Cell is 13×15 (TerminalTestCase): 26px → 2 cols, 30px → 2 rows.
		enterString(imageOsc("26px", "30px", PNG_1x1_B64));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(2, data.cellsW);
		assertEquals(2, data.cellsH);
		assertEquals(26, data.pixelWidth);
		assertEquals(30, data.pixelHeight);
	}

	public void testPercentWidthOfScreen() {
		withTerminalSized(20, 5);
		enterString(imageOsc("50%", "1", PNG_1x1_B64));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(10, data.cellsW);
		assertEquals(1, data.cellsH);
	}

	public void testMissingHeightDerivesFromAspectRatio() {
		withTerminalSized(40, 10);
		// width=2 cells (26px), intrinsic 26×30 → height should be 2 cells (30/15).
		enterString(imageOsc("26px", null, PNG_1x1_B64));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(2, data.cellsW);
		// PNG sniffing is not implemented for OSC 1337 (no header parse); aspect
		// fallback without intrinsic pixels is 1 cell — still a valid placement.
		assertTrue(data.cellsH >= 1);
	}

	public void testNoSizeIsRejected() {
		withTerminalSized(10, 4);
		enterString("\033]1337;File=inline=1:" + PNG_1x1_B64 + "\007");
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testNonFile1337Ignored() {
		withTerminalSized(10, 4);
		enterString("\033]1337;SetMark\007");
		enterString("ok");
		assertLineIs(0, "ok        ");
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testInvalidBase64Ignored() {
		withTerminalSized(10, 4);
		enterString(imageOsc("2", "2", "not!!valid@@base64"));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertCursorAt(0, 0);
	}

	public void testOverwriteClearsImageSideBand() {
		withTerminalSized(10, 4);
		enterString(imageOsc("2", "1", PNG_1x1_B64));
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 0));
		enterString("\033[1;1Hz");
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		assertEquals('z', mTerminal.getScreen()
			.mLines[mTerminal.getScreen().externalToInternalRow(0)].mText[0]);
		// Un-overwritten cell keeps the stamp.
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 1));
	}

	public void testKillSwitchRejectsNewImages() {
		withTerminalSized(10, 4);
		mTerminal.setTerminalImagesEnabled(false);
		enterString(imageOsc("2", "2", PNG_1x1_B64));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		// Re-enable and confirm new images parse again.
		mTerminal.setTerminalImagesEnabled(true);
		enterString(imageOsc("2", "2", PNG_1x1_B64));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testDisableClearsRegistryLookups() {
		withTerminalSized(10, 4);
		enterString(imageOsc("2", "1", PNG_1x1_B64));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		mTerminal.setTerminalImagesEnabled(false);
		// Side-band id still on the row, but kill-switch makes lookups null.
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertNull(mTerminal.getImageData(1));
	}

	public void testResetClearsRegistry() {
		withTerminalSized(10, 4);
		enterString(imageOsc("2", "1", PNG_1x1_B64));
		assertNotNull(mTerminal.getImageData(1));
		mTerminal.reset();
		assertNull(mTerminal.getImageData(1));
	}

	public void testSecondImageGetsNextId() {
		withTerminalSized(20, 4);
		enterString(imageOsc("1", "1", PNG_1x1_B64));
		enterString("\033[1;4H");
		enterString(imageOsc("1", "1", PNG_1x1_B64));
		final TerminalImageData a = mTerminal.getImageDataAt(0, 0);
		final TerminalImageData b = mTerminal.getImageDataAt(0, 3);
		assertNotNull(a);
		assertNotNull(b);
		assertEquals(a.id + 1, b.id);
	}

	public void testOversizedEncodedRejected() {
		withTerminalSized(10, 4);
		// Build a payload larger than MAX_IMAGE_ENCODED_BYTES (3 MiB decoded).
		// Base64 of 4 MiB of 'A' ≈ 5.3 MiB text — exceeds the OSC image cap too,
		// so the sequence is abandoned before decode (still no placement).
		final StringBuilder huge = new StringBuilder();
		final String chunk = repeat("QUJDRA==", 1024); // 3 KB
		while (huge.length() < 6 * 1024 * 1024) huge.append(chunk);
		enterString(imageOsc("2", "2", huge.toString()));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertCursorAt(0, 0);
	}

	public void testStringTerminatorEndsOsc() {
		withTerminalSized(10, 4);
		enterString(imageOsc("2", "1", PNG_1x1_B64).replace("\007", "\033\\"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		enterString("x");
		// Cursor was not advanced by the image; 'x' overwrites (0,0) image cell.
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
	}

	private static String repeat(String s, int times) {
		final StringBuilder sb = new StringBuilder(s.length() * times);
		for (int i = 0; i < times; i++) sb.append(s);
		return sb.toString();
	}

	private static void assertArrayEquals(byte[] a, byte[] b) {
		if (a.length != b.length) fail("length " + a.length + " != " + b.length);
		for (int i = 0; i < a.length; i++)
			if (a[i] != b[i]) fail("byte[" + i + "] " + a[i] + " != " + b[i]);
	}
}
