package com.termux.terminal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Kitty graphics file media ({@code t=f}, {@code t=t}, {@code t=s}) with
 * guest→host path mapping, the uniform {@code EBADF} failure rule, {@code S/O}
 * slicing, read-only {@code a=q} probes and the shm/temp unlink rules.
 */
public class KittyFileMediaTest extends TerminalTestCase {

	private File mRootfs;
	private File mShm;
	private File mSdcard;
	private File mStorage;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		mRootfs = mkTempDir("kittyrootfs");
		mShm = mkTempDir("kittyshm");
		mSdcard = mkTempDir("kittysdcard");
		mStorage = mkTempDir("kittystorage");
		assertTrue(new File(mRootfs, "tmp").mkdirs() || new File(mRootfs, "tmp").isDirectory());
	}

	@Override
	protected void tearDown() throws Exception {
		deleteRecursively(mRootfs);
		deleteRecursively(mShm);
		deleteRecursively(mSdcard);
		deleteRecursively(mStorage);
		super.tearDown();
	}

	private static File mkTempDir(String prefix) throws IOException {
		final File dir = File.createTempFile(prefix, "");
		assertTrue(dir.delete());
		assertTrue(dir.mkdirs());
		return dir;
	}

	private static void deleteRecursively(File file) {
		final File[] children = file == null ? null : file.listFiles();
		if (children != null) for (File child : children) deleteRecursively(child);
		if (file != null) file.delete();
	}

	private static void writeBytes(File file, byte[] bytes) throws IOException {
		final File parent = file.getParentFile();
		if (parent != null) parent.mkdirs();
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(bytes);
		}
	}

	private static String pathPayload(String path) {
		return ImageBase64.encode(path.getBytes(StandardCharsets.UTF_8));
	}

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	private void withMedia() {
		withTerminalSized(20, 6);
		mTerminal.configureImageMedia(
			mRootfs.getPath(), mShm.getPath(), mSdcard.getPath(), mStorage.getPath());
	}

	public void testTransmitFromFileMapsGuestRootfsPath() throws IOException {
		withMedia();
		final byte[] rgb = {(byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF}; // red, blue
		writeBytes(new File(mRootfs, "root/img.raw"), rgb);
		enterString(kg("a=T,i=1,f=24,s=2,v=1,w=2,h=1,t=f", pathPayload("/root/img.raw")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		final TerminalImageData data = mTerminal.getImageDataAt(0, 0);
		assertNotNull(data);
		assertEquals(2, data.cellsW);
		assertEquals(1, data.cellsH);
		final int[] argb = data.decodeRawArgb();
		assertNotNull(argb);
		assertEquals(0xFFFF0000, argb[0]);
		assertEquals(0xFF0000FF, argb[1]);
	}

	public void testTransmitFromSdcardAndStorageBinds() throws IOException {
		withMedia();
		final byte[] rgb = {(byte) 0xFF, 0, 0};
		writeBytes(new File(mSdcard, "pic.raw"), rgb);
		writeBytes(new File(mStorage, "pic.raw"), rgb);
		enterString(kg("a=T,i=1,f=24,s=1,v=1,w=1,h=1,t=f", pathPayload("/root/sdcard/pic.raw")));
		final String reply1 = mOutput.getOutputAndClear();
		assertTrue("expected OK for sdcard, got: " + reply1, reply1.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		enterString("\033[2;1H");
		enterString(kg("a=T,i=2,f=24,s=1,v=1,w=1,h=1,t=f", pathPayload("/root/storage/pic.raw")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected OK for storage, got: " + reply2, reply2.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(1, 0));
	}

	public void testSensitiveGuestPathsRefusedUniformly() {
		withMedia();
		enterString(kg("a=T,i=3,f=24,s=1,v=1,t=f", pathPayload("/proc/1/environ")));
		final String reply1 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply1, reply1.contains("EBADF:Failed to read image file"));
		enterString(kg("a=T,i=3,f=24,s=1,v=1,t=f", pathPayload("/sys/kernel/mm/transparent_hugepage/enabled")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply2, reply2.contains("EBADF:Failed to read image file"));
		enterString(kg("a=T,i=3,f=24,s=1,v=1,t=f", pathPayload("/dev/null")));
		final String reply3 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply3, reply3.contains("EBADF:Failed to read image file"));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testTraversalOutsideConfiguredRootsRefused() throws IOException {
		withMedia();
		final File outside = new File(mSdcard.getParentFile(), "kitty-outside.raw");
		writeBytes(outside, new byte[]{(byte) 0xFF, 0, 0});
		try {
			enterString(kg("a=T,i=4,f=24,s=1,v=1,w=1,h=1,t=f",
				pathPayload("/root/sdcard/../" + outside.getName())));
			final String reply = mOutput.getOutputAndClear();
			assertTrue("expected EBADF, got: " + reply, reply.contains("EBADF:Failed to read image file"));
			assertNull(mTerminal.getImageDataAt(0, 0));
		} finally {
			outside.delete();
		}
	}

	public void testNonRegularFileRefused() throws IOException {
		withMedia();
		final File dir = new File(mRootfs, "somedir");
		assertTrue(dir.mkdirs() || dir.isDirectory());
		enterString(kg("a=T,i=5,f=24,s=1,v=1,t=f", pathPayload("/somedir")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply, reply.contains("EBADF:Failed to read image file"));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testSharedMemoryMediumReadsAndUnlinks() throws IOException {
		withMedia();
		final File seg = new File(mShm, "seg1");
		writeBytes(seg, new byte[]{(byte) 0xFF, 0, 0});
		enterString(kg("a=T,i=6,f=24,s=1,v=1,w=1,h=1,t=s", pathPayload("/seg1")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply, reply.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		assertFalse("shm object must be unlinked after reading", seg.exists());
	}

	public void testSharedMemoryNameValidation() {
		withMedia();
		enterString(kg("a=T,i=7,f=24,s=1,v=1,t=s", pathPayload("/foo/bar")));
		final String reply1 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply1, reply1.contains("EBADF:Failed to read image file"));
		enterString(kg("a=T,i=7,f=24,s=1,v=1,t=s", pathPayload("noleading-slash")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply2, reply2.contains("EBADF:Failed to read image file"));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testSliceHonorsOffsetAndSize() throws IOException {
		withMedia();
		final File file = new File(mRootfs, "root/slice.raw");
		writeBytes(file, new byte[]{0, 0, 0, (byte) 0xFF, 0, 0}); // black, red
		enterString(kg("a=T,i=8,f=24,s=1,v=1,w=1,h=1,t=f,S=3,O=3", pathPayload("/root/slice.raw")));
		final String reply1 = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply1, reply1.contains("OK"));
		int[] argb = mTerminal.getImageDataAt(0, 0).decodeRawArgb();
		assertEquals(0xFFFF0000, argb[0]);
		enterString("\033[2;1H");
		enterString(kg("a=T,i=9,f=24,s=1,v=1,w=1,h=1,t=f,S=3,O=0", pathPayload("/root/slice.raw")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply2, reply2.contains("OK"));
		argb = mTerminal.getImageDataAt(1, 0).decodeRawArgb();
		assertEquals(0xFF000000, argb[0]);
	}

	public void testClaimLargerThanFileFails() throws IOException {
		withMedia();
		final File file = new File(mRootfs, "root/small.raw");
		writeBytes(file, new byte[]{(byte) 0xFF, 0, 0});
		enterString(kg("a=T,i=10,f=24,s=3,v=1,t=f,S=7", pathPayload("/root/small.raw")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply, reply.contains("EBADF:Failed to read image file"));
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testQueryFileMediaReadsWithoutStoring() throws IOException {
		withMedia();
		final File file = new File(mRootfs, "root/probe.raw");
		writeBytes(file, new byte[]{(byte) 0xFF, 0, 0});
		enterString(kg("a=q,i=42,f=24,t=f", pathPayload("/root/probe.raw")));
		final String reply1 = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply1, reply1.contains("OK"));
		enterString(kg("a=q,i=43,f=24,t=f", pathPayload("/root/missing.raw")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply2, reply2.contains("EBADF:Failed to read image file"));
		// Probes must not store anything.
		assertNull(mTerminal.getImageDataAt(0, 0));
		assertNull(mTerminal.getImageData(1));
	}

	public void testTempFileUnlinkFollowsSpecRule() throws IOException {
		withMedia();
		final File marker = new File(mRootfs, "tmp/tty-graphics-protocol-a");
		final File plain = new File(mRootfs, "tmp/ordinary-file");
		writeBytes(marker, new byte[]{(byte) 0xFF, 0, 0});
		writeBytes(plain, new byte[]{(byte) 0xFF, 0, 0});
		enterString(kg("a=T,i=11,f=24,s=1,v=1,w=1,h=1,t=t", pathPayload("/tmp/tty-graphics-protocol-a")));
		final String reply1 = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply1, reply1.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(0, 0));
		assertFalse("marker file in temp dir must be deleted", marker.exists());
		enterString("\033[2;1H");
		enterString(kg("a=T,i=12,f=24,s=1,v=1,w=1,h=1,t=t", pathPayload("/tmp/ordinary-file")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected OK, got: " + reply2, reply2.contains("OK"));
		assertNotNull(mTerminal.getImageDataAt(1, 0));
		assertTrue("file without the marker must survive", plain.exists());
	}

	public void testUnknownMediumRejected() {
		withMedia();
		enterString(kg("a=T,i=13,f=24,s=1,v=1,t=x", pathPayload("/root/img.raw")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected INVALID, got: " + reply, reply.contains("INVALID"));
	}

	public void testFileMediaUnconfiguredFailsUniformly() throws IOException {
		withTerminalSized(20, 6);
		final File file = new File(mRootfs, "root/img.raw");
		writeBytes(file, new byte[]{(byte) 0xFF, 0, 0});
		enterString(kg("a=T,i=14,f=24,s=1,v=1,t=f", pathPayload("/root/img.raw")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply, reply.contains("EBADF:Failed to read image file"));
		enterString(kg("a=q,i=15,f=24,t=f", pathPayload("/root/img.raw")));
		final String reply2 = mOutput.getOutputAndClear();
		assertTrue("expected EBADF, got: " + reply2, reply2.contains("EBADF:Failed to read image file"));
	}

	public void testFileProbeRejectedWhileImagesDisabled() throws IOException {
		withMedia();
		final File file = new File(mRootfs, "root/img.raw");
		writeBytes(file, new byte[]{(byte) 0xFF, 0, 0});
		mTerminal.setTerminalImagesEnabled(false);
		enterString(kg("a=q,i=16,f=24,t=f", pathPayload("/root/img.raw")));
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected INVALID, got: " + reply, reply.contains("INVALID"));
		// Direct probe still answers while disabled (protocol support detection).
		enterString(kg("a=q,i=17", ""));
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
	}
}
