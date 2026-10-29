package com.estrin217.filemanager

import com.estrin217.filemanager.compose.ExtractFormat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Disk contract for [ArchiveExtractor] (spec 023, RF-6..RF-8): every
 * supported container lands in [destination] with its relative tree rebuilt;
 * a loose compressor expands to exactly one file; an encrypted `.7z` demands
 * the password (correct opens, wrong fails, absent asks); an unsafe entry
 * name aborts the whole extraction and leaves no half-written destination.
 */
class ArchiveExtractorTest {

    @JvmField
    @Rule
    val mTemp = TemporaryFolder()

    /** A small tree the archives below carry: root.txt + sub/nested.txt. */
    private fun buildZipTree(): File {
        val root = mTemp.newFolder("apptar")
        File(root, "root.txt").writeText("top level")
        val sub = File(root, "sub")
        sub.mkdir()
        File(sub, "nested.txt").writeText("nested content")
        return root
    }

    private fun createZip(source: File): File {
        val out = File(mTemp.root, source.name + ".zip")
        assertTrue(ArchiveCreator.create(out, source, com.estrin217.filemanager.compose.ArchiveFormat.ZIP, null))
        return out
    }

    @Test
    fun zipExtractsIntoTheNewFolder() {
        val source = buildZipTree()
        val archive = createZip(source)
        val destination = mTemp.newFolder("extract_dest_zip")
        assertNull(ArchiveExtractor.extract(archive, destination, ExtractFormat.ZIP, null))
        assertEquals("top level", File(destination, "apptar/root.txt").readText())
        assertEquals("nested content", File(destination, "apptar/sub/nested.txt").readText())
        assertTrue(File(destination, "apptar/sub").isDirectory)
    }

    @Test
    fun tarXzExtractsIntoTheNewFolder() {
        val source = buildZipTree()
        val archive = File(mTemp.root, source.name + ".tar.xz")
        assertTrue(ArchiveCreator.create(archive, source, com.estrin217.filemanager.compose.ArchiveFormat.TAR_XZ, null))
        val destination = mTemp.newFolder("extract_dest_txz")
        assertNull(ArchiveExtractor.extract(archive, destination, ExtractFormat.TAR_XZ, null))
        assertEquals("nested content", File(destination, "apptar/sub/nested.txt").readText())
    }

    @Test
    fun ordinaryTarExtractsRaw() {
        // A plain .tar (no compression layer), built by hand.
        val archive = File(mTemp.root, "tree.tar")
        FileOutputStream(archive).use { fos ->
            TarArchiveOutputStream(fos).use { tar ->
                val entry = TarArchiveEntry("piecemeal.txt")
                entry.size = 15
                tar.putArchiveEntry(entry)
                tar.write("plain tar bytes".toByteArray())
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        val destination = mTemp.newFolder("extract_dest_tar")
        assertNull(ArchiveExtractor.extract(archive, destination, ExtractFormat.TAR, null))
        assertEquals("plain tar bytes", File(destination, "piecemeal.txt").readText())
    }

    private fun createTarGz(): File {
        val archive = File(mTemp.root, "tree.tar.gz")
        FileOutputStream(archive).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    val entry = TarArchiveEntry("gzinner.txt")
                    entry.size = 8
                    tar.putArchiveEntry(entry)
                    tar.write("gz'd tar".toByteArray())
                    tar.closeArchiveEntry()
                    tar.finish()
                }
            }
        }
        return archive
    }

    @Test
    fun tarGzExtractsCompressedStream() {
        val archive = createTarGz()
        val destination = mTemp.newFolder("extract_dest_tgz")
        assertNull(ArchiveExtractor.extract(archive, destination, ExtractFormat.TAR_GZ, null))
        assertEquals("gz'd tar", File(destination, "gzinner.txt").readText())
    }

    @Test
    fun looseGzExtractsTheSingleFile() {
        val modulo = File(mTemp.root, "contacto.txt.gz")
        FileOutputStream(modulo).use { fos ->
            java.util.zip.GZIPOutputStream(fos).use { gz -> gz.write("just plain damped".toByteArray()) }
        }
        val destination = mTemp.newFolder("extract_dest_gz")
        val single = ExtractFormat.GZ.let { File(destination, "contacto.txt") }
        assertNull(ArchiveExtractor.extract(modulo, single, ExtractFormat.GZ, null))
        assertEquals("just plain damped", single.readText())
    }

    @Test
    fun sevenZipWithoutPasswordAsksForIt() {
        val source = File(mTemp.root, "notes.txt").apply { writeText("encrypted body") }
        val archive = File(mTemp.root, "notes.7z")
        assertTrue(ArchiveCreator.create(archive, source, com.estrin217.filemanager.compose.ArchiveFormat.SEVEN_Z, "secret".toCharArray()))
        val destination = mTemp.newFolder("extract_dest_7z")
        assertEquals(
            ExtractionOutcome.PasswordRequired,
            ArchiveExtractor.extract(archive, File(destination, "notes"), ExtractFormat.SEVEN_Z, null)
        )
    }

    @Test
    fun sevenZipWithCorrectPasswordExtracts() {
        val source = File(mTemp.root, "notes.txt").apply { writeText("encrypted body") }
        val archive = File(mTemp.root, "notes.7z")
        assertTrue(ArchiveCreator.create(archive, source, com.estrin217.filemanager.compose.ArchiveFormat.SEVEN_Z, "secret".toCharArray()))
        val destination = mTemp.newFolder("extract_dest_7z_ok")
        assertNull(ArchiveExtractor.extract(archive, File(destination, "notes"), ExtractFormat.SEVEN_Z, "secret".toCharArray()))
        assertEquals("encrypted body", File(destination, "notes/notes.txt").readText())
    }

    @Test
    fun sevenZipWithWrongPasswordFailsClean() {
        val source = File(mTemp.root, "notes.txt").apply { writeText("encrypted body") }
        val archive = File(mTemp.root, "notes.7z")
        assertTrue(ArchiveCreator.create(archive, source, com.estrin217.filemanager.compose.ArchiveFormat.SEVEN_Z, "secret".toCharArray()))
        val destination = mTemp.newFolder("extract_dest_7z_bad")
        assertEquals(
            ExtractionOutcome.Failed,
            ArchiveExtractor.extract(archive, File(destination, "notes"), ExtractFormat.SEVEN_Z, "wrong".toCharArray())
        )
        assertFalse(File(destination, "notes").exists())
    }

    @Test
    fun zipSlipAbortsAndCleansTheDestination() {
        val archive = File(mTemp.root, "evil.zip")
        ZipArchiveOutputStream(archive).use { zip ->
            val entry = ZipArchiveEntry("../evil.txt")
            zip.putArchiveEntry(entry)
            zip.write("escape".toByteArray())
            zip.closeArchiveEntry()
        }
        val destination = mTemp.newFolder("extract_dest_slip")
        assertEquals(
            ExtractionOutcome.UnsafeEntry("../evil.txt"),
            ArchiveExtractor.extract(archive, destination, ExtractFormat.ZIP, null)
        )
        assertFalse(File(destination, "evil.txt").exists())
        assertFalse(File(mTemp.root, "evil.txt").exists())
    }

    @Test
    fun absoluteEntryIsUnsafe() {
        val archive = File(mTemp.root, "abs.zip")
        ZipArchiveOutputStream(archive).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("/etc/evil"))
            zip.write("escape".toByteArray())
            zip.closeArchiveEntry()
        }
        val destination = mTemp.newFolder("extract_dest_abs")
        assertEquals(
            ExtractionOutcome.UnsafeEntry("/etc/evil"),
            ArchiveExtractor.extract(archive, destination, ExtractFormat.ZIP, null)
        )
    }

    @Test
    fun malformedArchiveFails() {
        val archive = mTemp.newFile("notareal.zip")
        val destination = mTemp.newFolder("extract_dest_fake")
        // zip magic is checked lazily by ZipFile; either an IllegalArgumentException
        // path (NotAZipException) or plain Failed is acceptable, but nothing
        // silently succeeds.
        val outcome = ArchiveExtractor.extract(archive, destination, ExtractFormat.ZIP, null)
        assertTrue(outcome == ExtractionOutcome.Failed || outcome != null)
    }
}
