package dev.tn3w.shelf.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val LOWEST_FLOOR = 0.001f
private const val LOUDEST_QUIET = 0.03f
private const val QUIET_ABOVE_FLOOR = 3f
private const val FLOOR_RISE = 1.0005f
private const val MIN_SILENCE_MILLIS = 800L
private const val KEPT_MILLIS = 250L

data class Level(val millis: Long, val loudness: Float)

fun silences(levels: Sequence<Level>): Sequence<LongRange> = sequence {
    var floor = 1f
    var quietSince: Long? = null
    var last = 0L
    for (level in levels) {
        last = level.millis
        floor = minOf(level.loudness, floor * FLOOR_RISE).coerceAtLeast(LOWEST_FLOOR)
        val threshold = (floor * QUIET_ABOVE_FLOOR).coerceAtMost(LOUDEST_QUIET)
        if (level.loudness < threshold) {
            quietSince = quietSince ?: level.millis
            continue
        }
        val start = quietSince ?: continue
        quietSince = null
        silence(start, level.millis)?.let { yield(it) }
    }
    val start = quietSince ?: return@sequence
    silence(start, last)?.let { yield(it.first..last) }
}

private fun silence(start: Long, end: Long) =
    (start + KEPT_MILLIS..end - KEPT_MILLIS).takeIf { end - start >= MIN_SILENCE_MILLIS }

fun loudness(pcm: ByteBuffer): Float {
    val samples = pcm.order(ByteOrder.nativeOrder()).asShortBuffer()
    val count = samples.remaining()
    if (count == 0) return 0f
    var sum = 0.0
    while (samples.hasRemaining()) {
        val sample = samples.get() / 32768.0
        sum += sample * sample
    }
    return sqrt(sum / count).toFloat()
}
