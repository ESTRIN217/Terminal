package com.termux.terminal.compose

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.termux.shared.errors.Errno
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Imports a terminal font from shared storage into the custom font slot
 * ({@code ~/.termux/font.ttf}, see [TerminalFontCatalog.CUSTOM_FONT_ID]).
 *
 * The import is a plain copy: once imported, the font survives reboots and does not
 * require any storage permission to load (see [TerminalFontLoader]).
 */
object TerminalFontImporter {

    private const val LOG_TAG = "TerminalFontImporter"

    /** MIME types accepted by the system file picker for a font import. */
    val PICKER_MIME_TYPES: Array<String> = arrayOf(
        "font/*",
        "application/x-font-ttf",
        "application/vnd.ms-opentype",
        "application/font-sfnt"
    )

    /** Hard cap for an imported font file (50 MiB); larger streams are rejected. */
    const val MAX_FONT_FILE_SIZE_BYTES: Int = 50 * 1024 * 1024

    /** sfnt magic numbers accepted as the first 4 bytes: TrueType, OpenType, "true", "typ1". */
    private val SFNT_MAGICS: List<ByteArray> = listOf(
        byteArrayOf(0x00, 0x01, 0x00, 0x00),
        byteArrayOf(0x4F, 0x54, 0x54, 0x4F.toByte()), // "OTTO"
        byteArrayOf(0x74, 0x72, 0x75, 0x65), // "true"
        byteArrayOf(0x74, 0x79, 0x70, 0x31) // "typ1"
    )

    /**
     * Import the font referenced by a Storage Access Framework Uri into the custom font slot.
     *
     * The copied file is validated with [Typeface.createFromFile]; when validation fails the
     * destination is removed so no corrupt custom font is left behind.
     *
     * @param context The [Context] used to resolve the Uri.
     * @param sourceUri The SAF [Uri] of the font file picked by the user.
     * @return {@code null} on success, an [Error] otherwise. Never throws.
     */
    fun importFont(context: Context, sourceUri: Uri): Error? {
        val destFile = TermuxConstants.TERMUX_FONT_FILE
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return Errno.ERRNO_FAILED.getError("Could not open the selected file.")
            inputStream.use { stream ->
                val copyError = importFont(stream, destFile)
                if (copyError != null) return copyError
            }
        } catch (e: SecurityException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "No permission to read the selected file: $sourceUri", e)
            return Errno.ERRNO_FAILED.getError(e, "No permission to read the selected file.")
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the selected file: $sourceUri", e)
            return Errno.ERRNO_FAILED.getError(e, "Failed to read the selected file.")
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import the selected file: $sourceUri", e)
            return Errno.ERRNO_FAILED.getError(e, "Failed to import the selected file.")
        }

        try {
            val typeface = Typeface.createFromFile(destFile)
            if (typeface == null) {
                destFile.delete()
                return Errno.ERRNO_FAILED.getError("The selected file is not a valid font.")
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "The selected file is not a valid font: $sourceUri", e)
            destFile.delete()
            return Errno.ERRNO_FAILED.getError(e, "The selected file is not a valid font.")
        }

        return null
    }

    /**
     * Copy a font stream to a destination file, enforcing the size cap and the sfnt magic.
     *
     * The write is atomic: bytes go to a sibling {@code .tmp} file first and are renamed
     * over the destination only after validation succeeds.
     *
     * @param inputStream The source stream of the font file. Not closed by this function.
     * @param destFile The destination file.
     * @param maxBytes Maximum accepted size in bytes (see [MAX_FONT_FILE_SIZE_BYTES]).
     * @return {@code null} on success, an [Error] otherwise. Never throws.
     */
    fun importFont(inputStream: InputStream, destFile: File, maxBytes: Int = MAX_FONT_FILE_SIZE_BYTES): Error? {
        try {
            val parent = destFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                return Errno.ERRNO_FAILED.getError("Could not create the font directory.")
            }

            val bytes = readCapped(inputStream, maxBytes)
                ?: return Errno.ERRNO_FAILED.getError("The selected file exceeds the size limit.")

            if (bytes.size < 12 || SFNT_MAGICS.none { magic -> bytes.startsWith(magic) }) {
                return Errno.ERRNO_FAILED.getError("The selected file is not a valid font.")
            }

            val tmpFile = File(parent, destFile.name + ".tmp")
            try {
                tmpFile.outputStream().use { out -> out.write(bytes) }
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.delete()
                    return Errno.ERRNO_FAILED.getError("Could not install the selected font.")
                }
            } catch (e: IOException) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write the font file: " + destFile.absolutePath, e)
                tmpFile.delete()
                return Errno.ERRNO_FAILED.getError(e, "Could not install the selected font.")
            }
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the selected font stream", e)
            return Errno.ERRNO_FAILED.getError(e, "Failed to read the selected file.")
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import the selected font stream", e)
            return Errno.ERRNO_FAILED.getError(e, "Failed to import the selected file.")
        }

        return null
    }

    /**
     * Read a stream up to {@code maxBytes + 1} bytes to detect overflow.
     *
     * @param inputStream The source stream.
     * @param maxBytes Maximum accepted size in bytes.
     * @return The bytes, or {@code null} when the stream exceeds {@code maxBytes}.
     * @throws IOException When the stream cannot be read.
     */
    @Throws(IOException::class)
    private fun readCapped(inputStream: InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Whether {@code bytes} start with {@code prefix}.
     *
     * @param bytes The bytes to inspect.
     * @param prefix The expected prefix.
     * @return {@code true} when {@code bytes} start with {@code prefix}.
     */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
