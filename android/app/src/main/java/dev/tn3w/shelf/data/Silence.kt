package dev.tn3w.shelf.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val SILENT_PEAK = 0.015f
private const val MIN_SILENCE_MILLIS = 800L
private const val KEPT_MILLIS = 250L

data class Level(val millis: Long, val peak: Float)

fun silences(levels: Sequence<Level>): Sequence<LongRange> = sequence {
    var quietSince: Long? = null
    for (level in levels) {
        if (level.peak < SILENT_PEAK) {
            quietSince = quietSince ?: level.millis
            continue
        }
        val start = quietSince ?: continue
        quietSince = null
        if (level.millis - start >= MIN_SILENCE_MILLIS) {
            yield(start + KEPT_MILLIS..level.millis - KEPT_MILLIS)
        }
    }
}

fun peak(pcm: ByteBuffer): Float {
    val samples = pcm.order(ByteOrder.nativeOrder()).asShortBuffer()
    var peak = 0
    while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
    return peak / 32768f
}
