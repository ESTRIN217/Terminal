package com.termux.terminal;

/**
 * Kitty graphics (APC {@code ESC _ G ... ESC \\}): query, transmit/place/delete,
 * multi-chunk Base64, PNG sniff, raw RGB, response suppression {@code q=}.
 */
public class KittyGraphicsTest extends TerminalTestCase {

	private static final String PNG_1x1_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	/** 2×1 RGB: red, blue. */
	private static final String RGB_2x1_B64 = ImageBase64.encode(new byte[]{
		(byte) 0xFF, 0, 0,
		0, 0, (byte) 0xFF
	});

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	public void testQueryRepliesOkAndDoesNotPaint() {
		withTerminalSized(2, 2);
		enterString(kg("i=31,s=1,v=1,a=q,t=d,f=24", "AAAA"));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected kitty query reply, got: " + reply, reply.contains("OK"));
		assertTrue(reply.startsWith("\033_Gi=31"));
		assertTrue(reply.endsWith("\033\\"));
		assertLinesAre("  ", "  ");
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testQueryQ2StaysSilent() {
		withTerminalSized(2, 2);
		enterString(kg("a=q,i=31,q=2", ""));
		assertEquals("", mOutput.getOutputAndClear());
	}

	public void testQueryWithoutIdStaysSilent() {
		// Spec/kitty: replies are tied to a client-sent image id.
		withTerminalSized(2, 2);
		enterString(kg("a=q", ""));
		assertEquals("", mOutput.getOutputAndClear());
	}

	public void testQ1SuppressesOkButKeepsErrors() {
		withTerminalSized(4, 2);
		// q=1 must hide the OK reply…
		enterString(kg("a=T,i=8,f=100,s=1,v=1,w=1,h=1,q=1", PNG_1x1_B64));
		assertEquals("", mOutput.getOutputAndClear());
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		// …but still surface failures.
		enterString(kg("a=T,i=8,f=100,s=1,v=1,w=1,h=1,q=1", "not!!valid@@"));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("q=1 must keep error replies, got: " + reply, reply.contains("ENCODED_DATA_ERR"));
	}

	public void testAckFormatHasNoImageNumberField() {
		withTerminalSized(4, 2);
		enterString(kg("a=T,i=12,p=3,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		// Spec: ESC _ G i=<id>[,p=<placement>];OK ESC \ — never an invented I= field.
		assertEquals("\033_Gi=12,p=3;OK\033\\", reply);
	}

	public void testTransmitAndDisplayPlacesImageAtCursor() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,i=1,f=100,s=1,v=1,w=2,h=2", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK reply, got: " + reply, reply.contains("OK"));

		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(2, data.cellsW);
		assertEquals(2, data.cellsH);
		assertEquals(1, mTerminal.getScreen().getImageAt(0, 1));
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 0));
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 1));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 2));
		assertCursorAt(0, 0);
	}

	public void testTransmitOnlyDoesNotStamp() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=7,f=100,s=1,v=1,w=2,h=2", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
	}

	public void testPlaceAfterTransmit() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=7,f=100,s=1,v=1,w=2,h=2", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString("\033[2;3H");
		enterString(kg("a=p,i=7,w=2,h=2", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		final TerminalImageData data = mTerminal.getImageDataAt(1, 2);
		assertNotNull(data);
		assertEquals(1, data.startRow);
		assertEquals(2, data.startCol);
		assertEquals(1, mTerminal.getScreen().getImageAt(1, 3));
	}

	public void testChunkedTransmitReassemblesBase64() {
		withTerminalSized(20, 6);
		// Split PNG base64 across two APC sequences (m=1 then m=0).
		final String b64 = PNG_1x1_B64;
		final String head = b64.substring(0, 20);
		final String tail = b64.substring(20);
		enterString(kg("a=T,i=1,f=100,s=1,v=1,w=1,h=1,m=1", head));
		assertEquals("", mOutput.getOutputAndClear());
		assertNull(mTerminal.getImageDataAt(0, 0));
		enterString(kg("m=0", tail));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK after last chunk, got: " + reply, reply.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testDeleteAllVisibleClearsStampsKeepsPayload() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,i=3,f=100,s=1,v=1,w=2,h=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		enterString(kg("a=d,d=a", ""));
		mOutput.getOutputAndClear();
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		// Payload still addressable via protocol id → re-place works.
		enterString(kg("a=p,i=3,w=2,h=1", ""));
		mOutput.getOutputAndClear();
		assertNotNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testDeleteByIdRemovesPayload() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,i=9,f=100,s=1,v=1,w=2,h=1", PNG_1x1_B64));
		final String ok = mOutput.getOutputAndClear();
		assertTrue(ok, ok.contains("OK"));
		enterString(kg("a=d,d=i,i=9", ""));
		mOutput.getOutputAndClear();
		assertNull(mTerminal.getImageDataAt(0, 0));
		// Place after delete fails.
		enterString(kg("a=p,i=9", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected ENOENT, got: " + reply, reply.contains("ENOENT"));
	}

	public void testDeleteAtCursor() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,f=100,s=1,v=1,w=2,h=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString("\033[1;1H");
		enterString(kg("a=d,d=c", ""));
		mOutput.getOutputAndClear();
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testUnknownActionRepliesEINVAL() {
		withTerminalSized(2, 2);
		enterString(kg("a=z,i=1", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected EINVAL, got: " + reply, reply.contains("EINVAL"));
		// An absent a= key fails the same way (no effect, no paint).
		enterString(kg("i=1", ""));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected EINVAL, got: " + reply2, reply2.contains("EINVAL"));
		assertLinesAre("  ", "  ");
	}

	public void testNonKGapsIgnored() {
		withTerminalSized(2, 2);
		enterString("\033_Xsomething\033\\");
		assertEquals("", mOutput.getOutputAndClear());
		assertLinesAre("  ", "  ");
	}

	public void testKillSwitchRejectsTransmitButStillQueries() {
		withTerminalSized(4, 2);
		mTerminal.setTerminalImagesEnabled(false);
		enterString(kg("a=T,i=4,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected INVALID when off, got: " + reply, reply.contains("INVALID"));
		assertNull(mTerminal.getImageDataAt(0, 0));

		enterString(kg("a=q,i=5", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
	}

	public void testInvalidBase64RepliesEncodedDataErr() {
		withTerminalSized(4, 2);
		enterString(kg("a=T,i=2,f=100,s=1,v=1,w=1,h=1", "not!!valid@@"));
		assertTrue(mOutput.getOutputAndClear().contains("ENCODED_DATA_ERR"));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testQ2SuppressesErrorReplies() {
		withTerminalSized(4, 2);
		enterString(kg("a=T,f=100,s=1,v=1,w=1,h=1,q=2", "not!!valid@@"));
		assertEquals("", mOutput.getOutputAndClear());
	}

	public void testInvalidDimensionsOnDisplayNoPlacement() {
		// a=T without any size and without snippable PNG metrics that map to cells:
		// PNG sniff yields 1×1 px → still stamps 1 cell. Use raw RGB without s=/v=.
		withTerminalSized(4, 2);
		enterString(kg("a=T,f=24", "AQ==")); // 1 byte, not a valid 2×… RGB frame
		// Too-short RGB → INVALID (length check needs s/v or fails decode path).
		// With no s/v, fmt=24 and pW=pH=0 skips length check, then display has no size.
		final String reply = mOutput.getOutputAndClear();
		// Accept either INVALID (no size) or silent failure — never paint.
		assertTrue(reply.isEmpty() || reply.contains("INVALID"));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testPixelSizeConvertsToCells() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,f=100,s=26,v=30", PNG_1x1_B64));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(2, data.cellsW);
		assertEquals(2, data.cellsH);
		assertEquals(26, data.pixelWidth);
		assertEquals(30, data.pixelHeight);
	}

	public void testPngSniffFillsMissingPixelSize() {
		withTerminalSized(20, 6);
		// No s=/v= — sniff IHDR (1×1) and convert to cells.
		enterString(kg("a=T,f=100", PNG_1x1_B64));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(1, data.pixelWidth);
		assertEquals(1, data.pixelHeight);
		assertTrue(data.cellsW >= 1);
		assertTrue(data.cellsH >= 1);
	}

	public void testRawRgbPayloadKeepsFormat() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,i=2,f=24,s=2,v=1,w=2,h=1", RGB_2x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(TerminalImageData.FORMAT_RGB_24, data.pixelFormat);
		final int[] argb = data.decodeRawArgb();
		assertNotNull(argb);
		assertEquals(2, argb.length);
		assertEquals(0xFFFF0000, argb[0]);
		assertEquals(0xFF0000FF, argb[1]);
	}

	public void testRetransmitSameIdReplacesBytesAndDeletesPlacement() {
		withTerminalSized(20, 6);
		enterString(kg("a=T,i=5,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		final TerminalImageData before = mTerminal.getImageDataAt(0, 0);
		assertNotNull(before);
		final int regId = before.id;
		// Spec: re-transmit with same i= replaces the payload AND deletes existing placements.
		enterString(kg("a=t,i=5,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		final TerminalImageData after = mTerminal.getImageData(regId);
		assertNotNull(after);
		assertEquals(regId, after.id);
		assertFalse(after.isPlaced());
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertEquals(0, mTerminal.getScreen().getImageAt(0, 0));
		// The protocol id mapping survives, so a re-place works.
		enterString(kg("a=p,i=5,w=1,h=1", ""));
		mOutput.getOutputAndClear();
		assertNotNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testUppercaseActionAndIdKeysAreFolded() {
		withTerminalSized(4, 2);
		enterString(kg("A=T,I=6,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK via A=/I= keys, got: " + reply, reply.contains("i=6"));
		assertTrue(reply, reply.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testDeleteAbortsPendingMultiChunkTransfer() {
		withTerminalSized(4, 2);
		enterString(kg("a=T,i=5,f=100,s=1,v=1,w=1,h=1,m=1", PNG_1x1_B64.substring(0, 10)));
		assertEquals("", mOutput.getOutputAndClear());
		enterString(kg("a=d,d=i,i=5", ""));
		mOutput.getOutputAndClear();
		// Without the abort this payload would be consumed as a chunk continuation
		// (acked with i=5); with it, processed standalone as a fresh i=9 transmit.
		enterString(kg("a=T,i=9,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK for i=9, got: " + reply, reply.contains("i=9"));
		assertFalse("i=5 transfer must stay cancelled, got: " + reply, reply.contains("i=5"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testPlaceAndDeleteRequireProtocolIdMapping() {
		withTerminalSized(4, 2);
		// Transmit with i=0: payload registers (id 1) but has no protocol mapping.
		enterString(kg("a=t,i=0,f=100,s=1,v=1,w=1,h=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		// The raw registry index must not resolve through the removed fallback.
		enterString(kg("a=p,i=1,w=1,h=1", ""));
		final String placeReply = mOutput.getOutputAndClear();
		assertTrue("expected ENOENT, got: " + placeReply, placeReply.contains("ENOENT"));
		enterString(kg("a=d,d=i,i=1", ""));
		mOutput.getOutputAndClear();
		assertNotNull("raw i=1 must not delete the payload", mTerminal.getImageData(1));
	}

	public void testUnknownDeleteModeRepliesEINVAL() {
		withTerminalSized(4, 2);
		enterString(kg("a=T,i=3,f=100,s=1,v=1,w=2,h=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		enterString(kg("a=d,d=z,i=3", ""));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected EINVAL, got: " + reply, reply.contains("EINVAL"));
		assertNotNull("unknown mode must have no effect", mTerminal.getImageDataAt(0, 0));
	}
}
