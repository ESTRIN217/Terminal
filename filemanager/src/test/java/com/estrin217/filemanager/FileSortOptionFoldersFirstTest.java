package com.estrin217.filemanager;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contract for the folders-first toggle of {@link FileSortOption}
 * (spec 008, RF-7/RF-8): the new
 * {@link FileSortOption#getComparator(boolean, boolean) two-argument}
 * comparator must place directories first (or last in descending) when the
 * toggle is on, and merge them into the plain criteria order when it is off.
 *
 * <p>The one-argument API (spec 001-007 callers) must keep behaving like the
 * toggle-on variant; that legacy contract is pinned by
 * {@link FileSortOptionExtensionTest}.
 */
public class FileSortOptionFoldersFirstTest {

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private List<String> sortNames(boolean ascending, boolean foldersFirst, File... items) {
        List<File> sorted = new ArrayList<>(Arrays.asList(items));
        sorted.sort(FileSortOption.NAME.getComparator(ascending, foldersFirst));
        List<String> names = new ArrayList<>();
        for (File f : sorted) names.add(f.getName());
        return names;
    }

    private List<String> sortedNames(FileSortOption option, boolean ascending,
                                     boolean foldersFirst, File... items) {
        List<File> sorted = new ArrayList<>(Arrays.asList(items));
        sorted.sort(option.getComparator(ascending, foldersFirst));
        List<String> names = new ArrayList<>();
        for (File f : sorted) names.add(f.getName());
        return names;
    }

    @Test
    public void testNameAscendingMergesFolderIntoCriteriaOrder() throws IOException {
        File dir = mTemp.newFolder("zdir");
        File fileA = mTemp.newFile("aaa.txt");
        File fileB = mTemp.newFile("mmm.txt");

        Assert.assertEquals(Arrays.asList("aaa.txt", "mmm.txt", "zdir"),
            sortNames(true, false, dir, fileA, fileB));
        // The toggle-on behaviour keeps the historical folders-first placement.
        Assert.assertEquals(Arrays.asList("zdir", "aaa.txt", "mmm.txt"),
            sortNames(true, true, dir, fileA, fileB));
    }

    @Test
    public void testNameDescendingMergesFolderIntoCriteriaOrder() throws IOException {
        File dirFile = mTemp.newFolder("zdir");
        File fileA = mTemp.newFile("aaa.txt");
        File fileB = mTemp.newFile("mmm.txt");
        // Distinct modification times so DATE is not a full tie pool.
        Assert.assertTrue(fileA.setLastModified(1_000_000L));
        Assert.assertTrue(fileB.setLastModified(2_000_000L));
        Assert.assertTrue(dirFile.setLastModified(3_000_000L));

        Assert.assertEquals(Arrays.asList("zdir", "mmm.txt", "aaa.txt"),
            sortedNames(FileSortOption.DATE, false, false, dirFile, fileA, fileB));
        // Toggle-on: directories go last in descending (historical rebound).
        Assert.assertEquals(Arrays.asList("mmm.txt", "aaa.txt", "zdir"),
            sortedNames(FileSortOption.DATE, false, true, dirFile, fileA, fileB));
    }

    @Test
    public void testDateAscendingMergesFolderIntoCriteriaOrder() throws IOException {
        File dir = mTemp.newFolder("newest_dir");
        File fileA = mTemp.newFile("file1.txt");
        File fileB = mTemp.newFile("file2.txt");
        Assert.assertTrue(fileA.setLastModified(1_000_000L));
        Assert.assertTrue(fileB.setLastModified(2_000_000L));
        Assert.assertTrue(dir.setLastModified(3_000_000L));

        Assert.assertEquals(Arrays.asList("file1.txt", "file2.txt", "newest_dir"),
            sortedNames(FileSortOption.DATE, true, false, dir, fileA, fileB));
        Assert.assertEquals(Arrays.asList("newest_dir", "file1.txt", "file2.txt"),
            sortedNames(FileSortOption.DATE, true, true, dir, fileA, fileB));
    }

    @Test
    public void testTypeMergesFolderLastIntoExtensionOrder() throws IOException {
        File dir = mTemp.newFolder("last.txt"); // directory named like a .txt file
        File csv = mTemp.newFile("mmm.csv");
        File txt = mTemp.newFile("aaa.txt");

        Assert.assertEquals(Arrays.asList("mmm.csv", "aaa.txt", "last.txt"),
            sortedNames(FileSortOption.TYPE, true, false, dir, csv, txt));
        Assert.assertEquals(Arrays.asList("last.txt", "mmm.csv", "aaa.txt"),
            sortedNames(FileSortOption.TYPE, true, true, dir, csv, txt));
    }

    @Test
    public void testSizeKeepsZeroSizeFoldersByCriteriaOrder() throws IOException {
        File dir = mTemp.newFolder("zdir");
        File fileA = mTemp.newFile("file1.bin");
        File fileB = mTemp.newFile("file3.bin");
        // The comparator reports directories as size 0, so folders-first and
        // plain size order coincide; pin both to keep the invariant honest.
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(fileA)) { out.write(new byte[1]); }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(fileB)) { out.write(new byte[3]); }

        Assert.assertEquals(Arrays.asList("zdir", "file1.bin", "file3.bin"),
            sortedNames(FileSortOption.SIZE, true, false, dir, fileA, fileB));
        Assert.assertEquals(Arrays.asList("file3.bin", "file1.bin", "zdir"),
            sortedNames(FileSortOption.SIZE, false, false, dir, fileA, fileB));
    }
}
