package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

private const val BLOCK = 10

class StreamCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private val content = ByteArray(55) { it.toByte() }
    private val fetched = mutableListOf<Long>()

    private fun cache(file: File = folder.newFile(), maxBlocks: Int = 3, cut: Int = 0) =
        StreamCache(file, BLOCK, maxBlocks) { from, to ->
            fetched += from
            val end = minOf(to + 1 - cut, content.size.toLong()).toInt()
            Chunk(content.copyOfRange(from.toInt(), end), content.size.toLong())
        }

    private fun StreamCache.read(position: Int, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val count = readAt(position.toLong(), buffer, 0, length)
        return buffer.copyOf(maxOf(count, 0))
    }

    @Test
    fun readsStopAtBlockEndsAndTheEndOfTheStream() {
        val cache = cache()

        assertArrayEquals(content.copyOfRange(25, 30), cache.read(25, 20))
        assertArrayEquals(content.copyOfRange(50, 55), cache.read(50, 20))
        assertEquals(-1, cache.readAt(55, ByteArray(1), 0, 1))
    }

    @Test
    fun eachBlockIsFetchedOnceWhileItIsKept() {
        val cache = cache()

        repeat(3) { cache.read(12, 4) }

        assertEquals(listOf(0L, 10L), fetched)
    }

    @Test
    fun theLeastRecentlyReadBlockMakesRoomAndTheFileStaysSmall() {
        val file = folder.newFile()
        val cache = cache(file, maxBlocks = 2)

        cache.read(10, 1)
        cache.read(0, 1)
        cache.read(20, 1)
        cache.read(0, 1)
        val again = cache.read(10, 10)

        assertArrayEquals(content.copyOfRange(10, 20), again)
        assertEquals(listOf(0L, 10L, 20L, 10L), fetched)
        assertTrue(file.length() <= 2L * BLOCK)
        cache.close()
        assertFalse(file.exists())
    }

    @Test
    fun shortBlocksAreRefused() {
        assertThrows(IOException::class.java) { cache(cut = 1) }
    }
}
