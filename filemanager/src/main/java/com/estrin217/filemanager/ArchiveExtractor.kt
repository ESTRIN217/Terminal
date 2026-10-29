package com.estrin217.filemanager

import com.estrin217.filemanager.compose.ExtractFormat
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Outcome of an extraction that did *not* succeed (spec 023). A `null` return
 * from [ArchiveExtractor.extract] means success; the collision and
 * invalid-name checks live in the caller (ViewModel + ExtractPolicy) so no
 * disk read happens for them (RNF-5).
 */
sealed interface ExtractionOutcome {
    /** The caller-found destination already existed: refuse untouched (RNF-5). */
    data class Collision(val destination: String) : ExtractionOutcome

    /** An entry name would have escaped the destination (zip-slip, RF-7). */
    data class UnsafeEntry(val entry: String) : ExtractionOutcome

    /** The archive is password-protected and none was supplied (RF-8). */
    data object PasswordRequired : ExtractionOutcome

    /** Anything else: malformed archive, wrong password, I/O failure (RF-6). */
    data object Failed : ExtractionOutcome
}

/**
 * Disk reader for the item-menu "Extract" (spec 023, RF-6..RF-7): mirrors
 * {@link ArchiveCreator} on the plain `commons-compress` stack it already
 * owns — `.zip` (`ZipFile`), the tar family with an optional compression
 * layer (`tar`, `tar.gz`/`tgz`, `tar.bz2`, `tar.xz`), `.7z` with optional
 * password (`SevenZFile`) and loose `gz`/`bz2`/`xz` via
 * `CompressorStreamFactory`.
 *
 * <p>Stays on `java.io` so JVM unit tests execute it without Android
 * (constitution 3). The caller creates the empty destination; every failure
 * path deletes that destination subtree so no half-written result is left
 * behind, and an unsafe entry name aborts the whole operation (RF-7).
 */
object ArchiveExtractor {

    /**
     * Extracts [archive] (of [format]) into the empty [destination] the
     * caller created.
     *
     * @param archive Archive file to read.
     * @param destination Empty folder (containers) or the target file
     * (loose compressors) that must receive the content.
     * @param format One of the formats [com.estrin217.filemanager.compose.ExtractPolicy]
     * recognized for the archive name.
     * @param password Optional password; honored by `.7z` only.
     * @return `null` on success, otherwise the [ExtractionOutcome] describing
     * the refusal; on failure the destination is gone (RNF-5).
     */
    fun extract(
        archive: File,
        destination: File,
        format: ExtractFormat,
        password: CharArray?
    ): ExtractionOutcome? {
        val outcome = try {
            when (format) {
                ExtractFormat.ZIP -> extractZip(archive, destination)
                ExtractFormat.TAR, ExtractFormat.TAR_GZ, ExtractFormat.TGZ,
                ExtractFormat.TAR_BZ2, ExtractFormat.TAR_XZ ->
                    extractTar(archive, destination, format)
                ExtractFormat.SEVEN_Z -> extractSevenZ(archive, destination, password)
                ExtractFormat.GZ, ExtractFormat.BZ2, ExtractFormat.XZ ->
                    extractLoose(archive, destination, format)
            }
        } catch (e: PasswordRequiredException) {
            // Targeted: RF-8's single-retry flow keys on this exact refusal.
            ExtractionOutcome.PasswordRequired
        } catch (e: IOException) {
            // Targeted: decode/read/write faults above end as Failed, and the
            // caller wipes the partial destination (RNF-5).
            ExtractionOutcome.Failed
        } catch (e: IllegalArgumentException) {
            // Targeted: builder factories surface malformed inputs as IAE.
            ExtractionOutcome.Failed
        }
        // RNF-5: nothing half-written survives a failure; the destination is
        // freshly created by the caller and never held pre-existing data.
        if (outcome != null) destination.deleteRecursively()
        return outcome
    }

    /** True when [entryName] would escape the destination (zip-slip, RF-7). */
    private fun isUnsafeEntry(entryName: String): Boolean =
        entryName.isBlank() || entryName.startsWith('/') ||
            entryName.contains("..") || entryName.contains('\\')

    /** Wraps the [ExtractionOutcome.UnsafeEntry] refusal (RF-7). */
    private fun unsafeOrNull(entryName: String): ExtractionOutcome.UnsafeEntry? =
        if (isUnsafeEntry(entryName)) ExtractionOutcome.UnsafeEntry(entryName) else null

    /** Creates parent folders for [entryName] and opens its output stream. */
    private fun entryOutput(destination: File, entryName: String): FileOutputStream {
        val target = File(destination, entryName)
        target.parentFile?.mkdirs()
        return FileOutputStream(target)
    }

    /** Copies the entry stream [input] to [entryName] under [destination]. */
    private fun copyEntry(input: InputStream, destination: File, entryName: String) {
        entryOutput(destination, entryName).use { output ->
            input.copyTo(output)
        }
    }

    /** zip: entries are safe-name-checked, directories made, files streamed. */
    private fun extractZip(archive: File, destination: File): ExtractionOutcome? {
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                unsafeOrNull(entry.name)?.let { return it }
                if (entry.isDirectory) {
                    File(destination, entry.name).mkdirs()
                } else {
                    zip.getInputStream(entry).use { input ->
                        copyEntry(input, destination, entry.name)
                    }
                }
            }
        }
        return null
    }

    /**
     * tar family; [format] picks the decompressor layer (plain `tar` reads
     * raw). The decompressor wraps the open file stream, so everything runs
     * nested inside `fileInputStream.use {}`.
     */
    private fun extractTar(
        archive: File,
        destination: File,
        format: ExtractFormat
    ): ExtractionOutcome? {
        val compressorType = compressorTypeOf(format)
        FileInputStream(archive).use { raw ->
            val decoded = if (compressorType == null) {
                BufferedInputStream(raw)
            } else {
                CompressorStreamFactory().createCompressorInputStream(compressorType, raw)
            }
            decoded.use {
                TarArchiveInputStream(it).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextEntry
                    while (entry != null) {
                        unsafeOrNull(entry.name)?.let { return it }
                        if (entry.isDirectory) {
                            File(destination, entry.name).mkdirs()
                        } else {
                            copyEntry(tar, destination, entry.name)
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
        return null
    }

    /** Maps a tar-family format to its compressor type, or null (raw tar). */
    private fun compressorTypeOf(format: ExtractFormat): String? = when (format) {
        ExtractFormat.TAR_GZ, ExtractFormat.TGZ -> CompressorStreamFactory.GZIP
        ExtractFormat.TAR_BZ2 -> CompressorStreamFactory.BZIP2
        ExtractFormat.TAR_XZ -> CompressorStreamFactory.XZ
        else -> null
    }

    /** 7z with optional password (the builder switches entries to AES reads). */
    private fun extractSevenZ(
        archive: File,
        destination: File,
        password: CharArray?
    ): ExtractionOutcome? {
        val builder = SevenZFile.builder().setFile(archive)
        if (password != null) builder.setPassword(password)
        builder.get().use { seven ->
            var entry = seven.nextEntry
            while (entry != null) {
                unsafeOrNull(entry.name)?.let { return it }
                if (entry.isDirectory) {
                    File(destination, entry.name).mkdirs()
                } else {
                    seven.getInputStream(entry).use { input ->
                        copyEntry(input, destination, entry.name)
                    }
                }
                entry = seven.nextEntry
            }
        }
        return null
    }

    /** Loose single-file compressor: one decoded stream onto [destination]. */
    private fun extractLoose(
        archive: File,
        destination: File,
        format: ExtractFormat
    ): ExtractionOutcome? {
        val compressorType = when (format) {
            ExtractFormat.GZ -> CompressorStreamFactory.GZIP
            ExtractFormat.BZ2 -> CompressorStreamFactory.BZIP2
            else -> CompressorStreamFactory.XZ
        }
        FileInputStream(archive).use { raw ->
            CompressorStreamFactory()
                .createCompressorInputStream(compressorType, raw).use { decoded ->
                    FileOutputStream(destination).use { output ->
                        decoded.copyTo(output)
                    }
                }
        }
        return null
    }
}
