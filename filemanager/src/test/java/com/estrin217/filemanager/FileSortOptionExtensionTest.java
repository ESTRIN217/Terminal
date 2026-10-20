package com.estrin217.filemanager;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Regression contract for {@link FileSortOption}'s extension handling
 * (spec 006, T-4): the comparator's private extension parsing is being
 * delegated to {@link FileTypeDetector}, and its public behavior must not
 * change.
 *
 * <p>The full-order cases pin down the exact current semantics of the private
 * {@code getExtension} (dotted extension, {@code ""} without a dot, a trailing
 * dot forms its own {@code "."} group, hidden files group under their full
 * {@code ".name"}, last dot wins, case-insensitive grouping with the name as
 * the tie-breaker).
 */
public class FileSortOptionExtensionTest {

    private final Comparator<File> mAscending = FileSortOption.TYPE.getComparator(true);

    @Test
    public void testFullAscendingOrderIsStable() {
        List<String> input = Arrays.asList(
            "Makefile", "b.zip", "notes.txt", "app.tar.gz", "a.TXT", ".bashrc",
            "dir", "archive.tar", "file.");

        List<File> sorted = new ArrayList<>();
        for (String name : input) sorted.add(new File(name));
        sorted.sort(mAscending);

        List<String> sortedNames = new ArrayList<>();
        for (File f : sorted) sortedNames.add(f.getName());

        // Folders first (in both directions), then extension group ascending,
        // then file name ignoring case.
        Assert.assertEquals(Arrays.asList(
            "dir", "Makefile", "file.", ".bashrc", "app.tar.gz", "archive.tar",
            "a.TXT", "notes.txt", "b.zip"), sortedNames);
    }

    @Test
    public void testFullDescendingOrderIsStable() {
        List<String> input = Arrays.asList(
            "Makefile", "b.zip", "notes.txt", "app.tar.gz", "a.TXT", ".bashrc",
            "dir", "archive.tar", "file.");

        List<File> sorted = new ArrayList<>();
        for (String name : input) sorted.add(new File(name));
        sorted.sort(FileSortOption.TYPE.getComparator(false));

        List<String> sortedNames = new ArrayList<>();
        for (File f : sorted) sortedNames.add(f.getName());

        // Folders follow the sort direction too (last when descending); files
        // reverse group and name order.
        Assert.assertEquals(Arrays.asList(
            "b.zip", "notes.txt", "a.TXT", "archive.tar", "app.tar.gz",
            ".bashrc", "file.", "Makefile", "dir"), sortedNames);
    }
}
