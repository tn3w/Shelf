package dev.tn3w.shelf.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private const val LINK_BYTES = 8

class StarDict(folder: File) {
    private val files = folder.listFiles().orEmpty()
    private val definitions = files.first { it.extension == "dict" }
    private val index = RandomAccessFile(files.first { it.extension == "idx" }, "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    private val starts = entryStarts(index)

    fun define(text: String): String? {
        val word = text.trim { !it.isLetterOrDigit() }
        if (word.isEmpty()) return null
        return listOf(word, word.lowercase()).distinct().firstNotNullOfOrNull(::find)
    }

    private fun find(word: String): String? {
        val key = word.toByteArray()
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compare(starts[middle], key) < 0) low = middle + 1 else high = middle
        }
        val start = starts.getOrNull(low)?.takeIf { compare(it, key) == 0 } ?: return null
        val link = start + key.size + 1
        return read(index.getInt(link).toUInt().toLong(), index.getInt(link + 4))
    }

    private fun compare(start: Int, key: ByteArray): Int {
        for (position in 0..key.size) {
            val left = folded(index.get(start + position))
            val right = key.getOrNull(position)?.let(::folded) ?: 0
            if (left != right || left == 0) return left - right
        }
        return 0
    }

    private fun read(offset: Long, size: Int) = RandomAccessFile(definitions, "r").use {
        val bytes = ByteArray(size)
        it.seek(offset)
        it.readFully(bytes)
        bytes.decodeToString()
    }
}

private fun folded(byte: Byte): Int {
    val code = byte.toInt() and 0xFF
    return if (code.toChar() in 'A'..'Z') code + 32 else code
}

private fun entryStarts(index: ByteBuffer): IntArray {
    val starts = mutableListOf<Int>()
    var position = 0
    while (position < index.limit()) {
        starts += position
        while (index.get(position) != 0.toByte()) position++
        position += 1 + LINK_BYTES
    }
    return starts.toIntArray()
}
