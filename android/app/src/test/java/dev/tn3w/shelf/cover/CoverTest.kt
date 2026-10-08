package dev.tn3w.shelf.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
private const val MIX_HIGH = -0x40a7b892e31b1a47L
private const val MIX_LOW = -0x6b2fb644ecceee15L

private fun inverse(odd: Long) = (1..6).fold(odd) { guess, _ ->
    guess * (2 - odd * guess)
}

private fun unshift(value: Long, shift: Int) =
    generateSequence(value ushr shift) { it ushr shift }
        .takeWhile { it != 0L }
        .fold(value) { result, part -> result xor part }

private fun mix(state: Long): Long {
    var value = (state xor (state ushr 30)) * MIX_HIGH
    value = (value xor (value ushr 27)) * MIX_LOW
    return value xor (value ushr 31)
}

private fun unmix(output: Long): Long {
    var value = unshift(output, 31) * inverse(MIX_LOW)
    value = unshift(value, 27) * inverse(MIX_HIGH)
    return unshift(value, 30)
}

class CoverTest {
    @Test
    fun randomStaysBelowOneAtLargestOutput() {
        val largest = ((1L shl 53) - 1).toFloat() / (1L shl 53).toFloat()
        assertEquals(1f, largest)
        assertEquals(-1L, mix(unmix(-1L)))
        val expected = (mix(GOLDEN_GAMMA) ushr 11).toFloat() / (1L shl 53).toFloat()
        assertEquals(expected, CoverRandom(0).float())
        val random = CoverRandom(unmix(-1L) - GOLDEN_GAMMA)
        assertTrue(random.float() < 1f)
    }

    @Test
    fun randomIndexStaysInRange() {
        val random = CoverRandom(unmix(-1L) - GOLDEN_GAMMA)
        assertEquals(999_999, random.index(1_000_000))
        val values = CoverRandom(42)
        repeat(10_000) {
            assertTrue(values.float() in 0f..<1f)
            assertTrue(values.index(7) in 0..6)
            assertTrue(values.between(3, 5) in 3..5)
        }
    }

    @Test
    fun sameBookSameSeed() {
        val seed = coverSeed(1, "Dune", "Frank Herbert")
        assertEquals(seed, coverSeed(1, "Dune", "Frank Herbert"))
        assertNotEquals(seed, coverSeed(2, "Dune", "Frank Herbert"))
        assertNotEquals(seed, coverSeed(1, "Dune", "Brian Herbert"))
        val first = CoverRandom(7)
        val second = CoverRandom(7)
        repeat(100) { assertEquals(first.float(), second.float()) }
    }

    @Test
    fun genreFollowsTags() {
        assertEquals(CoverGenre.Literary, classify(emptyList()))
        assertEquals(CoverGenre.Fantasy, classify(listOf("fiction", "epic-fantasy")))
        assertEquals(CoverGenre.Biography, classify(listOf("biography", "history")))
        assertEquals(CoverGenre.Mystery, classify(listOf("fiction", "crime", "society")))
    }
}
