package com.estrin217.filemanager;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * Unit tests for {@link FileOperationsHelper#getDirectorySize(File)}.
 */
public class FileOperationsHelperDirSizeTest {

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();

    private static void writeByteCount(File file, long count) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] chunk = new byte[4096];
            while (count > 0) {
                int n = (int) Math.min(chunk.length, count);
                out.write(chunk, 0, n);
                count -= n;
            }
        }
    }

    @Test
    public void testEmptyDirectory() {
        Assert.assertEquals(0L,
            FileOperationsHelper.getDirectorySize(mTempFolder.getRoot()));
    }

    @Test
    public void testNonDirectoryAndNull() throws Exception {
        File f = mTempFolder.newFile("f.txt");
        Assert.assertEquals(0L, FileOperationsHelper.getDirectorySize(f));
        Assert.assertEquals(0L, FileOperationsHelper.getDirectorySize(null));
    }

    @Test
    public void testSumsNestedFiles() throws Exception {
        writeByteCount(new File(mTempFolder.getRoot(), "a.txt"), 1000);
        File sub = mTempFolder.newFolder("sub");
        writeByteCount(new File(sub, "b.txt"), 500);
        File deep = new File(sub, "deep");
        Assert.assertTrue(deep.mkdirs());
        writeByteCount(new File(deep, "c.txt"), 1024);

        Assert.assertEquals(1000 + 500 + 1024,
            FileOperationsHelper.getDirectorySize(mTempFolder.getRoot()));
    }

    @Test
    public void testIgnoresRootLength() throws Exception {
        // The measured directory itself must not contribute its File.length():
        // only its children count towards the total.
        File outer = mTempFolder.newFolder("outer");
        writeByteCount(new File(outer, "x"), 512);
        Assert.assertEquals(512L, FileOperationsHelper.getDirectorySize(outer));
    }

    @Test
    public void testSkipsSymlinks() throws Exception {
        File real = new File(mTempFolder.getRoot(), "real.txt");
        writeByteCount(real, 1024);
        File link = new File(mTempFolder.getRoot(), "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());

        // A symlink to the real file must not double-count it, and a symlink
        // to the root would create an infinite loop if followed.
        File loopRoot = mTempFolder.newFolder("loop");
        File toRoot = new File(loopRoot, "self");
        Files.createSymbolicLink(toRoot.toPath(), loopRoot.toPath());

        Assert.assertEquals(1024L, FileOperationsHelper.getDirectorySize(mTempFolder.getRoot()));
        Assert.assertEquals(0L, FileOperationsHelper.getDirectorySize(loopRoot));
    }
}