package com.estrin217.filemanager

import com.estrin217.filemanager.compose.ArchiveFormat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

/**
 * Disk contract for [ArchiveCreator] (spec 009, RF-7): the item-menu
 * "Compress…" produces a readable `.zip`, `.tar.xz` or `.7z` (optional
 * password, AES-256) of a file or a whole folder tree in the destination
 * folder. commons-compress itself verifies the output; the test asserts the
 * archive is openable and carries the expected entries with the original
 * bytes.
 *
 * <p>RNF-5 double-check: an already-existing destination is refused without
 * touching it.
 */
class ArchiveCreatorTest {

    @JvmField
    @Rule
    val mTemp = TemporaryFolder()

    private fun newSourceFile(): File {
        val file = mTemp.newFile("notes.txt")
        file.writeBytes("compress me please".toByteArray(Charsets.UTF_8))
        return file
    }

    private fun newSourceTree(): File {
        val root = mTemp.newFolder("proj")
        File(root, "root.txt").writeText("top level")
        val sub = File(root, "sub")
        sub.mkdir()
        File(sub, "nested.txt").writeText("nested content")
        return root
    }

    private fun entryNamesZip(out: File): Set<String> =
        ZipFile(out).use { zip ->
            zip.entries.asSequence().map { it.name }.toSet()
        }

    private fun entryNamesTarXz(out: File): Set<String> {
        FileInputStream(out).use { fis ->
            XZCompressorInputStream(fis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    val names = LinkedHashSet<String>()
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        names.add(entry.name)
                        entry = tar.nextTarEntry
                    }
                    return names
                }
            }
        }
    }

    @Test
    fun zipContainsTheSingleFileContent() {
        val source = newSourceFile()
        val out = File(mTemp.root, "notes.zip")
        assertTrue(ArchiveCreator.create(out, source, ArchiveFormat.ZIP, null))
        assertEquals(setOf("notes.txt"), entryNamesZip(out))
        assertEquals("compress me please", mTemp.root.let {
            ZipFile(out).use { zip -> String(zip.getInputStream(zip.getEntry("notes.txt")).readBytes()) }
        })
    }

    @Test
    fun zipStoresTheFolderTreeRecursively() {
        val source = newSourceTree()
        val out = File(mTemp.root, "proj.zip")
        assertTrue(ArchiveCreator.create(out, source, ArchiveFormat.ZIP, null))
        val names = entryNamesZip(out)
        assertTrue(names.contains("proj/root.txt"))
        assertTrue(names.contains("proj/sub/nested.txt"))
    }

    @Test
    fun tarXzStoresReadableEntries() {
        val source = newSourceTree()
        val out = File(mTemp.root, "proj.tar.xz")
        assertTrue(ArchiveCreator.create(out, source, ArchiveFormat.TAR_XZ, null))
        val names = entryNamesTarXz(out)
        assertTrue(names.contains("proj/root.txt"))
        assertTrue(names.contains("proj/sub/nested.txt"))
    }

    @Test
    fun sevenZipWithoutPasswordIsOpenable() {
        val source = newSourceFile()
        val out = File(mTemp.root, "notes.7z")
        assertTrue(ArchiveCreator.create(out, source, ArchiveFormat.SEVEN_Z, null))
        SevenZFile(out).use { seven ->
            val entry = seven.entries.first { it.name == "notes.txt" && !it.isDirectory }
            assertEquals("compress me please", String(seven.getInputStream(entry).readBytes()))
        }
    }

    @Test
    fun sevenZipWithPasswordRoundTrips() {
        val source = newSourceFile()
        val out = File(mTemp.root, "notes.7z")
        assertTrue(ArchiveCreator.create(out, source, ArchiveFormat.SEVEN_Z, "secret".toCharArray()))
        SevenZFile(out, "secret".toCharArray()).use { seven ->
            val entry = seven.entries.first { it.name == "notes.txt" && !it.isDirectory }
            assertEquals("compress me please", String(seven.getInputStream(entry).readBytes()))
        }
    }

    @Test
    fun refusesToOverwriteAnExistingDestinationImmediately() {
        val source = newSourceFile()
        val existing = File(mTemp.root, "there.zip").apply { writeText("precious") }
        val bondsBefore = existing.readText()
        assertFalse(ArchiveCreator.create(existing, source, ArchiveFormat.ZIP, null))
        assertEquals(bondsBefore, existing.readText())
    }
}
