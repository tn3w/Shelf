package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SPEECH = 0.2f

class SilenceTest {
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
}
