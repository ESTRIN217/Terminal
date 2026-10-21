package com.estrin217.filemanager.compose

import java.io.File

/**
 * Pure counting for the "N folders · M files" subtitle of the file manager
 * top bar (spec 008, RF-1/RF-18).
 *
 * The count is derived from the listing alone: the hidden filter applies
 * here (via [counts]'s [showHidden] parameter) but the search filter never
 * does, so the subtitle stays stable while the user types. Symlinks are
 * counted with `File.isDirectory`, which follows the link: a healthy
 * symlink to a folder is a folder, a dangling link is a file — the same
 * criterion the row uses to pick its icon.
 */
object ListingCounts {

    /**
     * Visible-entry tallies for one listing.
     *
     * @param folders Visible directories (symlinks to directories included).
     * @param files Visible non-directories (regular files, dangling links).
     */
    data class Counts(val folders: Int, val files: Int)

    /**
     * Counts the visible entries of a listing, split by folders/files.
     *
     * @param files The raw listing entries (before any filtering).
     * @param showHidden Whether dot-hidden entries are currently shown.
     * @return The [Counts] tallies; entries filtered out by the hidden
     *         setting do not contribute.
     */
    fun counts(files: List<File>, showHidden: Boolean): Counts {
        var folders = 0
        var regular = 0
        for (f in files) {
            if (!showHidden && f.name.startsWith(".")) continue
            if (f.isDirectory) folders++ else regular++
        }
        return Counts(folders, regular)
    }
}
