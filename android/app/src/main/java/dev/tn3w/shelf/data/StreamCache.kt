package dev.tn3w.shelf.data

import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.BitSet

private const val BLOCK_BYTES = 256 * 1024

class Chunk(val bytes: ByteArray, val total: Long)

class StreamCache(file: File, private val fetch: (Long, Long) -> Chunk) : Closeable {
    private val data = RandomAccessFile(file, "rw")
    private val loaded = BitSet()
    val size = load(0)

    private fun load(block: Int): Long {
        val from = block.toLong() * BLOCK_BYTES
        val chunk = fetch(from, from + BLOCK_BYTES - 1)
        data.seek(from)
        data.write(chunk.bytes)
        loaded.set(block)
        return chunk.total
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val block = (position / BLOCK_BYTES).toInt()
        val blockEnd = (block + 1L) * BLOCK_BYTES
        val count = minOf(length.toLong(), size - position, blockEnd - position).toInt()
        if (!loaded[block]) load(block)
        data.seek(position)
        data.readFully(buffer, offset, count)
        return count
    }

    @Synchronized
    override fun close() = data.close()
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
