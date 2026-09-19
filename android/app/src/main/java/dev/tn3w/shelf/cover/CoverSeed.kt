package dev.tn3w.shelf.cover

import java.security.MessageDigest

private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL.toLong()
private val MIX_HIGH = 0xBF58476D1CE4E5B9uL.toLong()
private val MIX_LOW = 0x94D049BB133111EBuL.toLong()

internal class CoverRandom(seed: Long) {
    private var state = seed

    private fun next(): Long {
        state += GOLDEN_GAMMA
        var value = state
        value = (value xor (value ushr 30)) * MIX_HIGH
        value = (value xor (value ushr 27)) * MIX_LOW
        return value xor (value ushr 31)
    }

    fun float() = (next() ushr 11).toFloat() / (1L shl 53).toFloat()

    fun range(from: Float, until: Float) = from + float() * (until - from)

    fun index(count: Int) = (float() * count).toInt().coerceIn(0, count - 1)

    fun between(from: Int, to: Int) = from + index(to - from + 1)

    fun <T> pick(values: List<T>) = values[index(values.size)]

    fun chance(probability: Float) = float() < probability

    fun sign() = if (chance(0.5f)) 1f else -1f
}

internal fun coverSeed(work: Int, title: String, author: String): Long {
    val digest =
        MessageDigest.getInstance("SHA-256").digest("$work|$title|$author".toByteArray())
    var value = 0L
    for (index in 0 until 8) value = (value shl 8) or (digest[index].toLong() and 0xFF)
    return value
}
