package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.rules.TemporaryFolder
import org.junit.Rule

/**
 * Contract for [buildCompressionPlan] (spec 009, RF-7): the compress dialog
 * asks for a name, one of the three supported formats and an optional
 * password; the destination policy stays a pure decision the ViewModel
 * executes on disk.
 *
 * <p>Behavior under contract: the output is always
 * {@code <trimmed-name>.<format extension>} inside [dir]; a blank name (empty
 * or whitespace only) and a name containing a path separator are invalid; an
 * existing destination is a collision and must never be touched (RNF-5: user
 * data is never overwritten silently).
 */
class ArchiveNamePolicyTest {

    @JvmField
    @Rule
    val mTemp = TemporaryFolder()

    private fun plan(
        name: String,
        format: ArchiveFormat,
        destinationExists: Boolean = false
    ): CompressionPlan = buildCompressionPlan(
        dir = mTemp.root,
        name = name,
        format = format,
        destinationExists = destinationExists
    )

    @Test
    fun normalNameIsReadyWithFormatExtension() {
        assertEquals(
            CompressionPlan.Ready(File(mTemp.root, "notes.zip")),
            plan("notes", ArchiveFormat.ZIP)
        )
        assertEquals(
            CompressionPlan.Ready(File(mTemp.root, "backup.tar.xz")),
            plan("backup", ArchiveFormat.TAR_XZ)
        )
        assertEquals(
            CompressionPlan.Ready(File(mTemp.root, "data.7z")),
            plan("data", ArchiveFormat.SEVEN_Z)
        )
    }

    @Test
    fun nameIsTrimmedBeforeBuildingTheOutput() {
        assertEquals(
            CompressionPlan.Ready(File(mTemp.root, "run.zip")),
            plan("  run  ", ArchiveFormat.ZIP)
        )
    }

    @Test
    fun blankNameIsInvalid() {
        assertTrue(plan("", ArchiveFormat.ZIP) is CompressionPlan.Invalid)
        assertTrue(plan("   ", ArchiveFormat.SEVEN_Z) is CompressionPlan.Invalid)
    }

    @Test
    fun nameWithPathSeparatorIsInvalid() {
        assertTrue(plan("a/b", ArchiveFormat.ZIP) is CompressionPlan.Invalid)
        assertTrue(
            plan("/etc/passwd", ArchiveFormat.TAR_XZ) is CompressionPlan.Invalid
        )
    }

    @Test
    fun existingDestinationIsCollision() {
        assertEquals(
            CompressionPlan.Collision(File(mTemp.root, "notes.zip")),
            plan("notes", ArchiveFormat.ZIP, destinationExists = true)
        )
    }

    @Test
    fun dotsInTheNameAreKept() {
        // The dot belongs to the user's name; only the format extension is
        // appended ("a.tar" -> "a.tar.zip", never "a.zip").
        assertEquals(
            CompressionPlan.Ready(File(mTemp.root, "a.tar.zip")),
            plan("a.tar", ArchiveFormat.ZIP)
        )
    }

    @Test
    fun invalidAndCollisionStillCarryTheOutputFile() {
        // The message rows need the output path even when the plan refuses it.
        val collision = plan("notes", ArchiveFormat.ZIP, destinationExists = true)
        assertEquals(File(mTemp.root, "notes.zip"), (collision as? CompressionPlan.Collision)?.outputFile)
        val invalid = plan("a/b", ArchiveFormat.ZIP)
        assertEquals(File(mTemp.root, "a/b.zip"), (invalid as? CompressionPlan.Invalid)?.outputFile)
    }
}
