package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SPEECH = 0.2f

class SilenceTest {
    @get:Rule val folder = TemporaryFolder()

    private fun levels(vararg loudness: Float) =
        loudness.mapIndexed { index, level -> Level(index * 100L, level) }.asSequence()

    private fun pause(loudness: Float, count: Int = 20) = FloatArray(count) { loudness }

    @Test
    fun longPausesAreFoundWithSomeQuietKeptAtEachEnd() {
        val found = silences(levels(SPEECH, *pause(0f), SPEECH)).toList()

        assertEquals(listOf(350L..1850L), found)
    }

    @Test
    fun pausesOverBackgroundNoiseAreFound() {
        val noise = 0.02f
        val talk = floatArrayOf(noise, SPEECH, SPEECH, noise, SPEECH)
        val found = silences(levels(*talk, *pause(noise), SPEECH)).toList()

        assertEquals(listOf(750L..2250L), found)
    }

    @Test
    fun shortPausesAreKept() {
        val found = silences(levels(SPEECH, *pause(0f, 3), SPEECH)).toList()

        assertEquals(emptyList<LongRange>(), found)
    }

    @Test
    fun silenceAtTheEndIsSkippedToTheEnd() {
        val found = silences(levels(SPEECH, *pause(0f))).toList()

        assertEquals(listOf(350L..2000L), found)
    }

    @Test
    fun loudnessIsTheRootMeanSquareOfAllSamples() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        listOf<Short>(16384, -16384, 16384, -16384).forEach { pcm.putShort(it) }
        pcm.flip()

        assertEquals(0.5f, loudness(pcm), 0.0001f)
    }

    @Test
    fun streamCacheFetchesEachBlockOnceAndStopsAtTheEnd() {
        val content = ByteArray(600_000) { (it % 251).toByte() }
        val fetched = mutableListOf<Long>()
        val cache = StreamCache(folder.newFile()) { from, to ->
            fetched += from
            val end = minOf(to + 1, content.size.toLong()).toInt()
            Chunk(content.copyOfRange(from.toInt(), end), content.size.toLong())
        }
        val buffer = ByteArray(100)

        repeat(2) { cache.readAt(599_950, buffer, 0, 100) }
        val read = cache.readAt(300_000, buffer, 0, 100)

        assertEquals(100, read)
        assertArrayEquals(content.copyOfRange(300_000, 300_100), buffer)
        assertEquals(50, cache.readAt(599_950, buffer, 0, 100))
        assertEquals(-1, cache.readAt(600_000, buffer, 0, 100))
        assertEquals(listOf(0L, 524_288L, 262_144L), fetched)
        cache.close()
    }
}
