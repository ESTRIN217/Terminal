package com.estrin217.filemanager.compose

/**
 * Presentation mode of the file listing (spec 008, RF-7): a row list or a
 * tile grid. Both render the same sorted/filtered data.
 */
enum class FileViewMode {
    /** Classic single-column rows (spec 001-007 look). */
    LIST,

    /** Adaptive tile grid (icon + truncated name per tile). */
    GRID
}
