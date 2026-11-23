package dev.tn3w.shelf.data

import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val BLOCK_BYTES = 256 * 1024
private const val MAX_BLOCKS = 128

class Chunk(val bytes: ByteArray, val total: Long)

class StreamCache(
    private val file: File,
    private val blockBytes: Int = BLOCK_BYTES,
    private val maxBlocks: Int = MAX_BLOCKS,
    private val fetch: (Long, Long) -> Chunk,
) : Closeable {
    private val data = RandomAccessFile(file, "rw")
    private val slots = LinkedHashMap<Long, Int>(maxBlocks, 0.75f, true)
    private var usedSlots = 0
    val size = runCatching { load(0).total }.getOrElse {
        close()
        throw it
    }

    private fun freeSlot(): Int {
        if (usedSlots < maxBlocks) return usedSlots++
        val oldest = slots.keys.first()
        return slots.remove(oldest)!!
    }

    private fun load(block: Long): Chunk {
        val from = block * blockBytes
        val chunk = fetch(from, from + blockBytes - 1)
        val expected = minOf(blockBytes.toLong(), chunk.total - from)
        if (chunk.bytes.size < expected) throw IOException("short block at $from")
        val slot = freeSlot()
        data.seek(slot.toLong() * blockBytes)
        data.write(chunk.bytes, 0, expected.toInt())
        slots[block] = slot
        return chunk
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val block = position / blockBytes
        val inBlock = position - block * blockBytes
        val count = minOf(length.toLong(), size - position, blockBytes - inBlock).toInt()
        if (block !in slots) load(block)
        val slot = slots.getValue(block)
        data.seek(slot.toLong() * blockBytes + inBlock)
        data.readFully(buffer, offset, count)
        return count
    }

    @Synchronized
    override fun close() {
        data.close()
        file.delete()
    }
}

fun httpChunks(url: String, headers: Map<String, String>) = { from: Long, to: Long ->
    val request = Request.Builder()
        .url(url)
        .headers(headers.toHeaders())
        .header("Range", "bytes=$from-$to")
        .build()
    http.newCall(request).execute().use { response ->
        if (response.code != 206) throw IOException("no byte ranges: ${response.code}")
        val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        Chunk(response.body.bytes(), total ?: throw IOException("unknown length"))
    }
}
