package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 023, RF-5/RF-6: parsing of archive names into (format, target) pairs
 * — containers land in a new folder, loose compressors in a single file;
 * unknown, baseless or extension-less names are not extractable at all.
 */
class ExtractPolicyTest {

    // Containers → Folder, with the format's full chain of extensions stripped.

    @Test
    fun `zip folder`() {
        assertEquals(ExtractFormat.ZIP to ExtractionTarget.Folder("docs"), ExtractPolicy.parse("docs.zip"))
    }

    @Test
    fun `plain tar folder`() {
        assertEquals(ExtractFormat.TAR to ExtractionTarget.Folder("app"), ExtractPolicy.parse("app.tar"))
    }

    @Test
    fun `tar gz folder strips both suffixes`() {
        assertEquals(ExtractFormat.TAR_GZ to ExtractionTarget.Folder("app"), ExtractPolicy.parse("app.tar.gz"))
    }

    @Test
    fun `tgz folder`() {
        assertEquals(ExtractFormat.TGZ to ExtractionTarget.Folder("backup"), ExtractPolicy.parse("backup.tgz"))
    }

    @Test
    fun `tar bz2 folder`() {
        assertEquals(ExtractFormat.TAR_BZ2 to ExtractionTarget.Folder("src"), ExtractPolicy.parse("src.tar.bz2"))
    }

    @Test
    fun `tar xz folder`() {
        assertEquals(ExtractFormat.TAR_XZ to ExtractionTarget.Folder("rootfs"), ExtractPolicy.parse("rootfs.tar.xz"))
    }

    @Test
    fun `seven z folder`() {
        assertEquals(ExtractFormat.SEVEN_Z to ExtractionTarget.Folder("archive"), ExtractPolicy.parse("archive.7z"))
    }

    // Loose single-file compressors → Single, keeping the base name (which may
    // itself carry a "real" extension).

    @Test
    fun `loose gz name keeps base`() {
        assertEquals(ExtractFormat.GZ to ExtractionTarget.Single("contacto.txt"), ExtractPolicy.parse("contacto.txt.gz"))
    }

    @Test
    fun `loose bz2`() {
        assertEquals(ExtractFormat.BZ2 to ExtractionTarget.Single("data"), ExtractPolicy.parse("data.bz2"))
    }

    @Test
    fun `loose xz with non-tar base`() {
        assertEquals(ExtractFormat.XZ to ExtractionTarget.Single("d.rom"), ExtractPolicy.parse("d.rom.xz"))
    }

    // Case-insensitivity (spec, edge cases).

    @Test
    fun `uppercase zip`() {
        assertEquals(ExtractFormat.ZIP to ExtractionTarget.Folder("FOTO"), ExtractPolicy.parse("FOTO.ZIP"))
    }

    @Test
    fun `mixed case tar gz`() {
        assertEquals(ExtractFormat.TAR_GZ to ExtractionTarget.Folder("WebSite"), ExtractPolicy.parse("WebSite.TAR.Gz"))
    }

    // Not extractable.

    @Test
    fun `no extension null`() {
        assertNull(ExtractPolicy.parse("README"))
    }

    @Test
    fun `unknown extension null`() {
        assertNull(ExtractPolicy.parse("paciente.rar"))
    }

    @Test
    fun `baseless gz null`() {
        assertNull(ExtractPolicy.parse(".gz"))
    }

    @Test
    fun `baseless zip null`() {
        assertNull(ExtractPolicy.parse(".ZIP"))
    }

    @Test
    fun `trailing dot null`() {
        assertNull(ExtractPolicy.parse("archive."))
    }

    @Test
    fun `null name null`() {
        assertNull(ExtractPolicy.parse(null))
    }
}
