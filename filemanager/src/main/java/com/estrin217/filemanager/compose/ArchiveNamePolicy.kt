package com.estrin217.filemanager.compose

import java.io.File

/**
 * Formats the item-menu "Compress…" dialog offers (spec 009, RF-7). Only
 * SEVEN_Z supports an optional password (AES-256 via commons-compress); ZIP
 * and TAR_XZ are always created without encryption.
 */
enum class ArchiveFormat(val extension: String) {
    ZIP("zip"),
    TAR_XZ("tar.xz"),
    SEVEN_Z("7z")
}

/**
 * Outcome of validating a compress request before any disk write. The output
 * file is carried in every variant so the error rows can reference the exact
 * destination that was refused (RNF-5: never overwrite existing user data).
 */
sealed interface CompressionPlan {
    /** Create [outputFile]; no entry existed there. */
    data class Ready(val outputFile: File) : CompressionPlan

    /** [outputFile] existed: report the collision, touch nothing. */
    data class Collision(val outputFile: File) : CompressionPlan

    /** The name was blank or contained a path separator. */
    data class Invalid(val outputFile: File) : CompressionPlan
}

/**
 * Validates the compress dialog input and resolves the destination file
 * (spec 009, RF-7) without touching the disk: the caller checks
 * [destinationExists] (an IO step, lives in the ViewModel) and executes the
 * returned [CompressionPlan].
 *
 * The name is trimmed defensively inside the policy so a whitespace-only
 * name is treated exactly like an empty one; a name carrying a path
 * separator would escape the destination folder and is refused.
 *
 * @param dir Destination parent directory (the open folder).
 * @param name Raw dialog content; trimmed and joined with the format extension.
 * @param format One of the [ArchiveFormat] options of the dialog.
 * @param destinationExists Whether `dir/<trimmed name>.<ext>` already exists.
 * @return The [CompressionPlan] the caller must honor.
 */
fun buildCompressionPlan(
    dir: File,
    name: String,
    format: ArchiveFormat,
    destinationExists: Boolean
): CompressionPlan {
    val trimmed = name.trim()
    val outputFile = File(dir, "$trimmed.${format.extension}")
    // A blank name still produces a stable Invalid path for the error text.
    if (trimmed.isEmpty() || trimmed.contains('/')) return CompressionPlan.Invalid(outputFile)
    return if (destinationExists) CompressionPlan.Collision(outputFile)
    else CompressionPlan.Ready(outputFile)
}
