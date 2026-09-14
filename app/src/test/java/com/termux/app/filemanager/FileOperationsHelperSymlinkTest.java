package com.termux.app.filemanager;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

/**
 * Unit tests for the symlink support in {@link FileOperationsHelper}.
 *
 * <p>Links are created with {@code java.nio} (JVM) while the helper itself
 * prefers {@code android.system.Os} via reflection on devices, falling back
 * to {@code java.nio} here.
 */
public class FileOperationsHelperSymlinkTest {

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();

    @Test
    public void testIsSymlink() throws Exception {
        File real = mTempFolder.newFile("real.txt");
        File link = new File(mTempFolder.getRoot(), "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());

        Assert.assertFalse(FileOperationsHelper.isSymlink(real));
        Assert.assertTrue(FileOperationsHelper.isSymlink(link));
        Assert.assertFalse(FileOperationsHelper.isSymlink(new File(mTempFolder.getRoot(), "missing")));
    }

    @Test
    public void testReadAndResolveAbsoluteTarget() throws Exception {
        File real = mTempFolder.newFile("real.txt");
        File link = new File(mTempFolder.getRoot(), "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());

        Assert.assertEquals(real.getAbsolutePath(), FileOperationsHelper.readSymlinkTargetRaw(link));
        Assert.assertEquals(real.getAbsolutePath(),
            FileOperationsHelper.resolveSymlinkTarget(link).getAbsolutePath());
    }

    @Test
    public void testResolveRelativeTarget() throws Exception {
        File real = mTempFolder.newFile("real.txt");
        File link = new File(mTempFolder.getRoot(), "link.txt");
        Files.createSymbolicLink(link.toPath(), new File("real.txt").toPath());

        Assert.assertEquals("real.txt", FileOperationsHelper.readSymlinkTargetRaw(link));
        Assert.assertEquals(real.getAbsolutePath(),
            FileOperationsHelper.resolveSymlinkTarget(link).getAbsolutePath());
    }

    @Test
    public void testBrokenSymlink() throws Exception {
        File link = new File(mTempFolder.getRoot(), "dangling.txt");
        Files.createSymbolicLink(link.toPath(), new File("nowhere.txt").toPath());

        Assert.assertTrue(FileOperationsHelper.isSymlink(link));
        Assert.assertTrue(FileOperationsHelper.isBrokenSymlink(link));
        Assert.assertFalse(FileOperationsHelper.isBrokenSymlink(mTempFolder.newFile("ok.txt")));
    }

    @Test
    public void testResolvesToDirectory() throws Exception {
        File dir = mTempFolder.newFolder("dir");
        File dirLink = new File(mTempFolder.getRoot(), "dirLink");
        Files.createSymbolicLink(dirLink.toPath(), dir.toPath());
        File fileLink = new File(mTempFolder.getRoot(), "fileLink");
        Files.createSymbolicLink(fileLink.toPath(), mTempFolder.newFile("f.txt").toPath());

        Assert.assertTrue(FileOperationsHelper.resolvesToDirectory(dirLink));
        Assert.assertFalse(FileOperationsHelper.resolvesToDirectory(fileLink));
    }

    @Test
    public void testResolveFileForOpen() throws Exception {
        File real = mTempFolder.newFile("real.txt");
        File link = new File(mTempFolder.getRoot(), "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());

        Assert.assertEquals(real.getCanonicalFile(), FileOperationsHelper.resolveFileForOpen(link));
        Assert.assertEquals(real, FileOperationsHelper.resolveFileForOpen(real));
    }

    @Test
    public void testDeleteSymlinkToDirKeepsTarget() throws Exception {
        File dir = mTempFolder.newFolder("dir");
        File inner = new File(dir, "inner.txt");
        Assert.assertTrue(inner.createNewFile());
        File link = new File(mTempFolder.getRoot(), "dirLink");
        Files.createSymbolicLink(link.toPath(), dir.toPath());

        Assert.assertTrue(FileOperationsHelper.deleteFile(link));
        Assert.assertFalse(link.exists());
        Assert.assertTrue("link target must survive", inner.exists());
    }

    @Test
    public void testCopySymlinkRecreatesLink() throws Exception {
        File real = mTempFolder.newFile("real.txt");
        File link = new File(mTempFolder.getRoot(), "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());
        File dest = new File(mTempFolder.getRoot(), "copy.txt");

        Assert.assertTrue(FileOperationsHelper.copyFile(link, dest));
        Assert.assertTrue(FileOperationsHelper.isSymlink(dest));
        Assert.assertEquals(real.getAbsolutePath(), FileOperationsHelper.readSymlinkTargetRaw(dest));
    }

    @Test
    public void testCreateSymlink() {
        Assert.assertTrue(FileOperationsHelper.createSymlink(
            mTempFolder.getRoot(), "newLink", "/nonexistent-target"));
        Assert.assertTrue(FileOperationsHelper.isSymlink(new File(mTempFolder.getRoot(), "newLink")));
        Assert.assertTrue(FileOperationsHelper.isBrokenSymlink(new File(mTempFolder.getRoot(), "newLink")));
        Assert.assertFalse("must not overwrite",
            FileOperationsHelper.createSymlink(mTempFolder.getRoot(), "newLink", "/other"));
    }

    @Test
    public void testSharedStorageSymlinkNeverBroken() throws Exception {
        // /sdcard -> /storage/emulated/0 like on a device; the FUSE-accessible
        // spelling is valid even though the intermediate chain may not be
        // statable from java.io.File. Never classify it as broken.
        Assert.assertTrue(FileOperationsHelper.isSharedStoragePath(new File("/sdcard")));
        Assert.assertTrue(FileOperationsHelper.isSharedStoragePath(new File("/storage/emulated/0")));
        Assert.assertTrue(FileOperationsHelper.isSharedStoragePath(new File("/storage/emulated/0/Download")));
        Assert.assertFalse(FileOperationsHelper.isSharedStoragePath(new File("/data/data/x")));

        File storageDir = new File(mTempFolder.getRoot(), "storage");
        Assert.assertTrue(storageDir.mkdirs());
        File link = new File(mTempFolder.getRoot(), "sdcard");
        Files.createSymbolicLink(link.toPath(), storageDir.toPath());

        Assert.assertTrue(FileOperationsHelper.isSymlink(link));
        // Target inside a "storage" tree on the test host is reachable, so it
        // must not be flagged broken either.
        Assert.assertFalse(FileOperationsHelper.isBrokenSymlink(link));

        // A dangling link to an unreachable storage-style path IS broken.
        Files.delete(link.toPath());
        File self = new File(mTempFolder.getRoot(), "self");
        Files.createSymbolicLink(link.toPath(), new File(self, "primary").toPath());
        Assert.assertTrue(FileOperationsHelper.isBrokenSymlink(link));
    }

    @Test
    public void testResolveFileForOpenOnSharedStorageIsStable() throws Exception {
        // When a real shared-storage root exists on this host, resolving a link
        // that points at it must keep the raw path (no canonicalization into
        // /storage/self/primary or /mnt/user/0/...). On CI/JVM hosts without
        // such a path there is nothing to exercise.
        File storageRoot = new File("/storage/emulated/0");
        if (!storageRoot.isDirectory()) return;

        File link = new File(mTempFolder.getRoot(), "storageLink");
        Files.createSymbolicLink(link.toPath(), storageRoot.toPath());
        Assert.assertEquals("must keep the FUSE-accessible spelling",
            storageRoot.getAbsolutePath(),
            FileOperationsHelper.resolveFileForOpen(link).getAbsolutePath());
        Assert.assertFalse(FileOperationsHelper.isBrokenSymlink(link));
    }
}
