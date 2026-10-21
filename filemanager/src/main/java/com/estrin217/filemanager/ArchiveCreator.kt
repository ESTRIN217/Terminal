package com.estrin217.filemanager

import com.estrin217.filemanager.compose.ArchiveFormat
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Disk writer for the item-menu "Compress…" (spec 009, RF-7): one entry point
 * for the three supported formats on plain `commons-compress` — `.zip`
 * (`ZipArchiveOutputStream`), `.tar.xz` (`TarArchiveOutputStream` over the
 * `org.tukaani:xz` backend) and `.7z` (`SevenZOutputFile`, whose password
 * constructor switches entries to AES-256). Folders archive recursively with
 * the folder itself as the archive root.
 *
 * <p>Stays on `java.io` so JVM unit tests execute it without Android
 * (constitution 3); failures delete the half-written destination so no
 * broken archive is left behind (RNF-5).
 */
object ArchiveCreator {

    /**
     * Creates [outputFile] with the content of [source].
     *
     * @param outputFile Destination inside the open folder; must not exist
     * (double-check of the plan's RNF-5 refusal).
     * @param source File or folder to pack; folders pack recursively.
     * @param format One of the three dialog options.
     * @param password Optional password; only honored for [ArchiveFormat.SEVEN_Z].
     * @return {@code true} on success; {@code false} when the destination
     * existed or the write failed (partial output removed).
     */
    fun create(outputFile: File, source: File, format: ArchiveFormat,
               password: CharArray?): Boolean {
        if (outputFile.exists()) return false
        val entries = entriesOf(source, source.name)
        return try {
            when (format) {
                ArchiveFormat.ZIP -> writeZip(outputFile, entries)
                ArchiveFormat.TAR_XZ -> writeTarXz(outputFile, entries)
                ArchiveFormat.SEVEN_Z -> writeSevenZ(outputFile, entries, password)
            }
            true
        } catch (e: IOException) {
            // Targeted: commons-compress writes surface as IOException; other
            // throwables (bugs) propagate instead of being masked.
            outputFile.delete()
            false
        }
    }

    /**
     * Walks [file] depth-first, pairing every node with its archive name:
     * the source itself as the root entry and descendants as
     * {@code <root>/<relative path>}, directories carrying a trailing slash.
     *
     * @param file Node being visited.
     * @param nameInArchive Entry name it should carry.
     * @return All (file, entry name) pairs for the subtree, in archive order.
     */
    private fun entriesOf(file: File, nameInArchive: String): List<Pair<File, String>> {
        if (!file.isDirectory) return listOf(file to nameInArchive)
        val pairs = mutableListOf(file to "$nameInArchive/")
        val children = file.listFiles()
            ?.sortedBy { it.name }
            ?: return pairs
        for (child in children) {
            pairs.addAll(entriesOf(child, "$nameInArchive/${child.name}"))
        }
        return pairs
    }

    /** Deflate-zips every entry; directory entries carry no content. */
    private fun writeZip(outputFile: File, entries: List<Pair<File, String>>) {
        ZipArchiveOutputStream(outputFile).use { zip ->
            for ((file, name) in entries) {
                val entry = ZipArchiveEntry(file, name)
                zip.putArchiveEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).use { it.copyTo(zip) }
                }
                zip.closeArchiveEntry()
            }
        }
    }

    /** xz-compressed tar stream; POSIX long-file names for deep trees. */
    private fun writeTarXz(outputFile: File, entries: List<Pair<File, String>>) {
        FileOutputStream(outputFile).use { fos ->
            XZCompressorOutputStream(fos).use { xz ->
                TarArchiveOutputStream(xz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    for ((file, name) in entries) {
                        val entry = TarArchiveEntry(file, name)
                        tar.putArchiveEntry(entry)
                        if (file.isFile) {
                            FileInputStream(file).use { it.copyTo(tar) }
                        }
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
    }

    /**
     * 7z archive; a non-empty password switches entries to AES-256 encrypted
     * content without further configuration. Passwords are only honored for
     * this format (zip and tar.xz have no encryption in this stack).
     */
    private fun writeSevenZ(outputFile: File, entries: List<Pair<File, String>>,
                            password: CharArray?) {
        val hasPassword = password != null && password.isNotEmpty()
        val output = if (hasPassword) SevenZOutputFile(outputFile, password)
        else SevenZOutputFile(outputFile)
        output.use { seven ->
            for ((file, name) in entries) {
                val entry = SevenZArchiveEntry()
                entry.setName(name.trimEnd('/'))
                if (file.isDirectory) {
                    entry.setDirectory(true)
                } else {
                    entry.setSize(file.length())
                }
                seven.putArchiveEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).use { seven.write(it) }
                }
                seven.closeArchiveEntry()
            }
        }
    }
}
