package com.estrin217.filemanager.compose

import com.termux.shared.file.filesystem.FilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [PosixModeText] (spec 009, RF-12): the dialog's permissions
 * tab renders one `<symbolic> (<octal>)` row from the raw POSIX mode plus the
 * permission set already derived by `FileAttributes` (termux-shared). The
 * symbolic part deliberately delegates to `FilePermissions.toString`; only
 * the octal suffix is added here (no bit re-implementation).
 *
 * <p>Kotlin has no octal literals, so every raw mode below is written as an
 * OR of its hex groups with the reference octal in a comment (0o644 = 0x1A4,
 * 0o755 = 0x1ED, 0o777 = 0x1FF, regular-file bit 0o100000 = 0x8000, setgid
 * 0o2000 = 0x400).
 */
class PosixModeTextTest {

    /** Regular-file type bit (0o100000). */
    private val mRegular = 0x8000

    /** Group of permission bits below the file-type prefix. */
    private fun format(mode: Int, symbolic: String): String =
        PosixModeText.format(mode, FilePermissions.fromString(symbolic))

    @Test
    fun regularFileSix44() {
        // 0o100644 = regular file + rw-r--r--.
        assertEquals("rw-r--r-- (644)", format(mRegular or 0x1A4, "rw-r--r--"))
    }

    @Test
    fun executableSeven55() {
        // 0o100755 = regular file + rwxr-xr-x.
        assertEquals("rwxr-xr-x (755)", format(mRegular or 0x1ED, "rwxr-xr-x"))
    }

    @Test
    fun noBitsAtAllYieldDashesAndZero() {
        // 0o100000 = regular file, permission bits empty.
        assertEquals("--------- (0)", format(mRegular, "---------"))
    }

    @Test
    fun allBitsYieldSeven77() {
        // 0o100777 = regular file + rwxrwxrwx.
        assertEquals("rwxrwxrwx (777)", format(mRegular or 0x1FF, "rwxrwxrwx"))
    }

    @Test
    fun setidBitsSurviveInTheOctal() {
        // 0o202755 = setgid + rwxr-xr-x: FilePermissions only models the 9
        // POSIX bits (no `s`), so the symbolic part reads rwxr-xr-x; the
        // octal suffix keeps the special bits via the 07777 mask.
        val permissions = PosixModeText.format(0x400 or 0x1ED, FilePermissions.fromString("rwxr-xr-x"))
        assertEquals("rwxr-xr-x (2755)", permissions)
    }

    @Test
    fun octalMatchesTheLowerTwelveBitsOnly() {
        val text = PosixModeText.format(mRegular or 0x1A4, FilePermissions.fromString("rw-r--r--"))
        assertTrue(text.endsWith("(644)"))
    }
}
