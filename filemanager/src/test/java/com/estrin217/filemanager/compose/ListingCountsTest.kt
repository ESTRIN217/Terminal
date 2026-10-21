package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Contract for [ListingCounts] (spec 008, RF-1/RF-18): the "N folders · M
 * files" subtitle counts the visible listing entries only (hidden filter is
 * the caller's [showHidden] flag); the search filter must never be involved.
 *
 * <p>Symlinks to directories count as folders, matching what the row renders
 * ({@code isDirectory} follows the link).
 */
class ListingCountsTest {

    @JvmField
    @Rule
    val mTemp = TemporaryFolder()

    @Test
    fun countsEmptyListAsZeroZero() {
        assertEquals(ListingCounts.Counts(0, 0), ListingCounts.counts(emptyList(), false))
        assertEquals(ListingCounts.Counts(0, 0), ListingCounts.counts(emptyList(), true))
    }

    @Test
    fun countsVisibleEntriesOnlyWhenHiddenAreOff() {
        val dirs = listOf(mTemp.newFolder(".invisible"), mTemp.newFolder("real"))
        val files = listOf(mTemp.newFile(".hidden.txt"), mTemp.newFile("note.txt"))
        assertEquals(ListingCounts.Counts(1, 1), ListingCounts.counts(dirs + files, false))
    }

    @Test
    fun countsEverythingWhenHiddenAreOn() {
        val dirs = listOf(mTemp.newFolder(".invisible"), mTemp.newFolder("real"))
        val files = listOf(mTemp.newFile(".hidden.txt"), mTemp.newFile("note.txt"))
        assertEquals(ListingCounts.Counts(2, 2), ListingCounts.counts(dirs + files, true))
    }

    @Test
    fun countsSymlinkToDirectoryAsFolder() {
        val target = mTemp.newFolder("target")
        val link = File(mTemp.root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(ListingCounts.Counts(1, 1),
            ListingCounts.counts(listOf(link, mTemp.newFile("f.txt")), false))
    }

    @Test
    fun countsDanglingSymlinkAsFile() {
        val link = File(mTemp.root, "dangling")
        Files.createSymbolicLink(link.toPath(), java.nio.file.Path.of(mTemp.root.absolutePath, "gone"))
        assertEquals(ListingCounts.Counts(0, 1), ListingCounts.counts(listOf(link), false))
    }
}
