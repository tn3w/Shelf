package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files

class IccProfileTest {
    private val signature =
        "\u0000\u0000\u0000\u000CjP  \r\n\u0087\n".toByteArray(Charsets.ISO_8859_1)

    private fun jpeg2000WithIccProfile() = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(signature)
            writeInt(8 + 22 + 27)
            writeBytes("jp2h")
            writeInt(22)
            writeBytes("ihdr")
            writeInt(1)
            writeInt(1)
            writeShort(3)
            write(ByteArray(4))
            writeInt(27)
            writeBytes("colr")
            write(byteArrayOf(2, 0, 0))
            write(ByteArray(16))
        }
    }.toByteArray()

    @Test
    fun restoredPdfHasIccProfileReplaced() {
        val directory = Files.createTempDirectory("icc").toFile()
        val original = jpeg2000WithIccProfile()

        extractBook(directory, "books/-3.pdf", original.inputStream())

        val stored = directory.resolve("-3.pdf").readBytes()
        assertEquals(original.size, stored.size)
        assertFalse(original.contentEquals(stored))
        directory.deleteRecursively()
    }

    @Test
    fun restoredNonPdfIsLeftUntouched() {
        val directory = Files.createTempDirectory("icc").toFile()
        val original = jpeg2000WithIccProfile()

        extractBook(directory, "books/-3.epub", original.inputStream())

        assertArrayEquals(original, directory.resolve("-3.epub").readBytes())
        directory.deleteRecursively()
    }
}
