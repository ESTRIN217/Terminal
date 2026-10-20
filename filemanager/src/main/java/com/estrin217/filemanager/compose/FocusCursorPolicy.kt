package com.estrin217.filemanager.compose

/**
 * Pure rules for the file list keyboard/extra-keys cursor
 * ([FileManagerUiState.focusedIndex]).
 *
 * The cursor is index-based and must never survive a directory change: the
 * carried-over highlight of the previous folder made new folders auto-scroll
 * so the inherited row landed at the top of the viewport (spec 007). Only
 * same-directory re-listings (search/sort/hidden toggles, file mutations)
 * may keep it.
 */
object FocusCursorPolicy {

    /**
     * Resolves the list cursor after a listing is published.
     *
     * @param previous Cursor index from the previous state, `-1` when unset.
     * @param previousPath Directory path the cursor was set against.
     * @param listedPath Directory the new listing belongs to.
     * @param size Number of entries in the new listing.
     * @return `-1` for an empty listing, after a directory change or when no
     * cursor was set; otherwise the previous index clamped to the listing.
     */
    fun normalizedIndex(previous: Int, previousPath: String, listedPath: String, size: Int): Int =
        when {
            size == 0 -> -1
            listedPath != previousPath -> -1
            previous < 0 -> -1
            else -> previous.coerceIn(0, size - 1)
        }

    /**
     * Resolves the cursor index after stepping [delta] rows. An unset cursor
     * counts as one step before the first row, so the first Down lands on
     * row 1 instead of skipping it.
     *
     * @param current The current cursor index, `-1` when unset.
     * @param delta How many rows to move (negative moves up).
     * @param size Number of entries in the visible listing.
     * @return The clamped target index, or `null` when there is nothing to cursor.
     */
    fun movedIndex(current: Int, delta: Int, size: Int): Int? {
        if (size == 0) return null
        return (current + delta).coerceIn(0, size - 1)
    }
}
