package com.termux.terminal.compose;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

/**
 * Unit tests for {@link TerminalFontImporter#importFont(java.io.InputStream, File, int)}.
 *
 * Only the pure-JVM stream overload is exercised here; the {@code Context} overload
 * requires the Android framework (content resolver, {@code Typeface}).
 */
public class TerminalFontImporterTest {

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private static byte[] fontBytes(byte b0, byte b1, byte b2, byte b3, int size) {
        byte[] bytes = new byte[size];
        bytes[0] = b0;
        bytes[1] = b1;
        bytes[2] = b2;
        bytes[3] = b3;
        return bytes;
    }

    @Test
    public void testImportFont_validTrueTypeFont_copiesFile() throws Exception {
        File destFile = new File(mTemporaryFolder.getRoot(), "font.ttf");
        byte[] bytes = fontBytes((byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, 64);

        Assert.assertNull(TerminalFontImporter.INSTANCE.importFont(
            new ByteArrayInputStream(bytes), destFile, 1024));

        Assert.assertArrayEquals(bytes, Files.readAllBytes(destFile.toPath()));
        Assert.assertFalse(new File(destFile.getParent(), "font.ttf.tmp").exists());
    }

    @Test
    public void testImportFont_validOpenTypeFont_copiesFile() throws Exception {
        File destFile = new File(mTemporaryFolder.getRoot(), "font.otf");
        byte[] bytes = fontBytes((byte) 'O', (byte) 'T', (byte) 'T', (byte) 'O', 64);

        Assert.assertNull(TerminalFontImporter.INSTANCE.importFont(
            new ByteArrayInputStream(bytes), destFile, 1024));

        Assert.assertTrue(destFile.isFile());
    }

    @Test
    public void testImportFont_invalidMagic_returnsErrorAndLeavesNoFile() throws Exception {
        File destFile = new File(mTemporaryFolder.getRoot(), "font.ttf");
        byte[] bytes = fontBytes((byte) 'w', (byte) 'O', (byte) 'F', (byte) '2', 64);

        Assert.assertNotNull(TerminalFontImporter.INSTANCE.importFont(
            new ByteArrayInputStream(bytes), destFile, 1024));

        Assert.assertFalse(destFile.exists());
        Assert.assertFalse(new File(destFile.getParent(), "font.ttf.tmp").exists());
    }

    @Test
    public void testImportFont_emptyStream_returnsError() throws Exception {
        File destFile = new File(mTemporaryFolder.getRoot(), "font.ttf");

        Assert.assertNotNull(TerminalFontImporter.INSTANCE.importFont(
            new ByteArrayInputStream(new byte[0]), destFile, 1024));

        Assert.assertFalse(destFile.exists());
    }

    @Test
    public void testImportFont_oversizedStream_returnsErrorAndLeavesNoFile() throws Exception {
        File destFile = new File(mTemporaryFolder.getRoot(), "font.ttf");

        Assert.assertNotNull(TerminalFontImporter.INSTANCE.importFont(
            new ByteArrayInputStream(new byte[64]), destFile, 10));

        Assert.assertFalse(destFile.exists());
    }

    @Test
    public void testImportFont_missingParentDirs_createsThem() throws Exception {
        File destFile = new File(mTemporaryFolder.getRoot(), "sub/dir/font.ttf");
        byte[] bytes = fontBytes((byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, 64);

        Assert.assertNull(TerminalFontImporter.INSTANCE.importFont(
            new ByteArrayInputStream(bytes), destFile, 1024));

        Assert.assertArrayEquals(bytes, Files.readAllBytes(destFile.toPath()));
    }
}
