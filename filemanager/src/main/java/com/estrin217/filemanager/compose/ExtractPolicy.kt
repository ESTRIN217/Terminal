package com.estrin217.filemanager.compose

/**
 * Extraction formats the item-menu "Extract" action supports (spec 023, RF-5).
 * The first group are multi-entry containers extracted into a new folder;
 * the second are single-file compressors extracted into one file. Same API
 * (commons-compress + `org.tukaani:xz`) the compress stack already uses, so
 * `rar` and friends are simply absent (plan decision 3).
 *
 * @param isContainer Whether this format expands into a folder target.
 */
enum class ExtractFormat(val isContainer: Boolean) {
    ZIP(true),
    TAR(true),
    TAR_GZ(true),
    TGZ(true),
    TAR_BZ2(true),
    TAR_XZ(true),
    SEVEN_Z(true),
    GZ(false),
    BZ2(false),
    XZ(false)
}

/**
 * Where an extraction of [parse]d name must land (spec 023, RF-6): a new
 * folder next to the archive for containers, or a single file in the open
 * folder for loose compressors. `name` carries no archive extension.
 */
sealed interface ExtractionTarget {
    /** New directory `<name>/` created in the open folder. */
    data class Folder(val name: String) : ExtractionTarget

    /** Single file `<name>` written in the open folder. */
    data class Single(val name: String) : ExtractionTarget
}

/**
 * Pure extension-to-(format, target) mapping for the "Extract" menu action
 * (spec 023, RF-5/RF-6). Only the raw file *name* is inspected, mirroring
 * [com.estrin217.filemanager.FileTypeDetector]; the disk work lives in
 * `ArchiveExtractor`, and whether the entry even qualifies (a real file, not
 * a folder) is the caller's decision.
 *
 * Recognition is case-insensitive. A <base> without a known format is never
 * extractable; a recognized suffix with an empty base (`.gz` alone) is not
 * either — there is nothing to name the destination.
 */
object ExtractPolicy {

    /** Multi-entry container suffixes, longest first matters little: the
     * parser checks the full suffix chain per format name. */
    private val containerSuffixes = listOf(
        "tar.gz", "tar.bz2", "tar.xz", "tgz", "tar", "zip", "7z"
    )

    /** Single-file compressor plain suffixes. */
    private val looseSuffixes = listOf("gz", "bz2", "xz")

    /**
     * Parses [name] into its extraction format and destination target.
     *
     * @param name Raw file name (may be null).
     * @return The (format, target) pair, or `null` when the name names no
     * supported archive (no extension, unknown extension, or a recognized
     * suffix with an empty base such as `.gz`).
     */
    fun parse(name: String?): Pair<ExtractFormat, ExtractionTarget>? {
        if (name == null) return null
        val lower = name.lowercase()
        // Containers first: they own the chains like `tar.gz`, so a loose
        // `gz` never misfires on an `.tar.gz` name. Longest-needed order
        // is irrelevant here because each suffix match is exact.
        for (suffix in containerSuffixes) {
            if (lower.endsWith(".$suffix") && lower.length > suffix.length + 1) {
                val format = when (suffix) {
                    "tar.gz" -> ExtractFormat.TAR_GZ
                    "tar.bz2" -> ExtractFormat.TAR_BZ2
                    "tar.xz" -> ExtractFormat.TAR_XZ
                    "tgz" -> ExtractFormat.TGZ
                    "tar" -> ExtractFormat.TAR
                    "zip" -> ExtractFormat.ZIP
                    else -> ExtractFormat.SEVEN_Z
                }
                // Keep the caller's casing: slice the original name.
                val base = name.take(name.length - suffix.length - 1)
                return format to ExtractionTarget.Folder(base)
            }
        }
        for (suffix in looseSuffixes) {
            if (lower.endsWith(".$suffix") && lower.length > suffix.length + 1) {
                val format = when (suffix) {
                    "gz" -> ExtractFormat.GZ
                    "bz2" -> ExtractFormat.BZ2
                    else -> ExtractFormat.XZ
                }
                val base = name.take(name.length - suffix.length - 1)
                return format to ExtractionTarget.Single(base)
            }
        }
        return null
    }
}
