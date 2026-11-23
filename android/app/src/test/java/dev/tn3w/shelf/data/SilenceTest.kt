package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SilenceTest {
    private fun levels(vararg peaks: Float) =
        peaks.mapIndexed { index, peak -> Level(index * 100L, peak) }.asSequence()

    @Test
    fun longPausesAreFoundWithSomeQuietKeptAtEachEnd() {
        val quiet = FloatArray(20) { 0.001f }
        val found = silences(levels(0.5f, *quiet, 0.5f)).toList()

        assertEquals(listOf(350L..1850L), found)
    }

    @Test
    fun shortPausesAndTrailingQuietAreKept() {
        val found = silences(levels(0.5f, 0.001f, 0.001f, 0.5f, 0.001f, 0.001f)).toList()

        assertEquals(emptyList<LongRange>(), found)
    }

    @Test
    fun peakIsTheLoudestSampleOfAnyChannel() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        listOf<Short>(100, -16384, 0, 8192).forEach { pcm.putShort(it) }
        pcm.flip()

        assertEquals(0.5f, peak(pcm), 0.0001f)
    }
}
