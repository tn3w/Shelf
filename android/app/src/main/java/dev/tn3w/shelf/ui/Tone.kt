package dev.tn3w.shelf.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val HUE_BUCKETS = 12
private const val NEUTRAL = HUE_BUCKETS
private const val OPAQUE = 0xFF shl 24
private const val MIN_ALPHA = 128
private const val MIN_SATURATION = 0.18f
private val COLOURFUL_LIGHTNESS = 0.12f..0.9f
private const val DISTINCT_SHARE = 0.04f
const val READABLE_CONTRAST = 4.5f
private const val LIGHT_ACCENT_CONTRAST = 5.4f

data class CoverTone(val primary: Int, val accent: Int)

data class Tint(
    val paper: Int,
    val raised: Int,
    val sunken: Int,
    val line: Int,
    val glow: Int,
    val accent: Int,
    val onAccent: Int,
    val wash: Int,
)

class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun red(color: Int) = color shr 16 and 0xFF

private fun green(color: Int) = color shr 8 and 0xFF

private fun blue(color: Int) = color and 0xFF

private fun rgb(red: Int, green: Int, blue: Int) =
    OPAQUE or (red shl 16) or (green shl 8) or blue

fun hslOf(color: Int): Hsl {
    val red = red(color) / 255f
    val green = green(color) / 255f
    val blue = blue(color) / 255f
    val highest = max(red, max(green, blue))
    val lowest = min(red, min(green, blue))
    val lightness = (highest + lowest) / 2
    val spread = highest - lowest
    if (spread == 0f) return Hsl(0f, 0f, lightness)
    val saturation = spread / (1 - abs(2 * lightness - 1))
    val sector = when (highest) {
        red -> (green - blue) / spread % 6
        green -> (blue - red) / spread + 2
        else -> (red - green) / spread + 4
    }
    return Hsl((sector * 60 + 360) % 360, saturation.coerceIn(0f, 1f), lightness)
}

fun hsl(hue: Float, saturation: Float, lightness: Float): Int {
    val chroma = (1 - abs(2 * lightness - 1)) * saturation
    val sector = hue / 60
    val second = chroma * (1 - abs(sector % 2 - 1))
    val (red, green, blue) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    val base = lightness - chroma / 2
    fun channel(value: Float) = ((value + base) * 255).roundToInt().coerceIn(0, 255)
    return rgb(channel(red), channel(green), channel(blue))
}

private fun linear(channel: Int): Float {
    val value = channel / 255f
    return if (value <= 0.03928f) {
        value / 12.92f
    } else {
        ((value + 0.055f) / 1.055f).pow(2.4f)
    }
}

fun luminance(color: Int) =
    0.2126f * linear(red(color)) + 0.7152f * linear(green(color)) +
        0.0722f * linear(blue(color))

fun contrast(first: Int, second: Int): Float {
    val lighter = max(luminance(first), luminance(second))
    val darker = min(luminance(first), luminance(second))
    return (lighter + 0.05f) / (darker + 0.05f)
}

fun mix(from: Int, to: Int, share: Float): Int {
    fun channel(start: Int, end: Int) = (start + (end - start) * share).roundToInt()
    return rgb(
        channel(red(from), red(to)),
        channel(green(from), green(to)),
        channel(blue(from), blue(to)),
    )
}

/** [color] moved away from [background] until text in it is readable there. */
fun readable(color: Int, background: Int, target: Float = READABLE_CONTRAST): Int {
    val tone = hslOf(color)
    val lighten = luminance(background) < 0.4f
    var lightness = tone.lightness
    var result = color
    while (contrast(result, background) < target && lightness in 0f..1f) {
        lightness += if (lighten) 0.02f else -0.02f
        result = hsl(tone.hue, tone.saturation, lightness.coerceIn(0f, 1f))
    }
    return result
}

fun textOn(background: Int): Int {
    val white = rgb(255, 255, 255)
    val ink = rgb(22, 18, 15)
    return if (contrast(white, background) >= contrast(ink, background)) white else ink
}

private class Bucket {
    var count = 0
    var red = 0L
    var green = 0L
    var blue = 0L
    var saturation = 0f

    fun add(color: Int, tone: Hsl) {
        count++
        red += red(color)
        green += green(color)
        blue += blue(color)
        saturation += tone.saturation
    }

    fun color() =
        rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())

    fun vividness() = count * (0.3f + saturation / count)
}

/** Dominant and most vivid colour of a cover, or null when it has no opaque pixels. */
fun coverTone(pixels: IntArray): CoverTone? {
    val buckets = List(HUE_BUCKETS + 1) { Bucket() }
    for (pixel in pixels) {
        if (pixel ushr 24 < MIN_ALPHA) continue
        val tone = hslOf(pixel)
        val colourful =
            tone.saturation >= MIN_SATURATION && tone.lightness in COLOURFUL_LIGHTNESS
        val index = (tone.hue / 360 * HUE_BUCKETS).toInt().coerceAtMost(HUE_BUCKETS - 1)
        buckets[if (colourful) index else NEUTRAL].add(pixel, tone)
    }
    val total = buckets.sumOf { it.count }
    if (total == 0) return null
    val primary = buckets.maxBy { it.count }
    val colourful = buckets.take(HUE_BUCKETS).filter { it.count > 0 }
    val distinct = colourful.filter {
        it !== primary && it.count >= total * DISTINCT_SHARE
    }
    val accent = distinct.ifEmpty { colourful }.maxByOrNull { it.vividness() } ?: primary
    return CoverTone(primary.color(), accent.color())
}

/** Surface and accent colours that carry a cover's [tone] and stay readable. */
fun tintOf(tone: CoverTone, dark: Boolean, black: Boolean = false): Tint {
    val primary = hslOf(tone.primary)
    val hue = primary.hue
    val saturation = primary.saturation
    fun surface(lightness: Float, limit: Float) =
        hsl(hue, min(saturation, limit), lightness)
    val paper = when {
        black -> rgb(0, 0, 0)
        dark -> surface(0.085f, 0.4f)
        else -> surface(0.955f, 0.5f)
    }
    val target = if (dark) LIGHT_ACCENT_CONTRAST else READABLE_CONTRAST
    val accent = readable(vivid(tone.accent, dark), paper, target)
    return Tint(
        paper = paper,
        raised = if (dark) surface(0.13f, 0.34f) else surface(0.985f, 0.5f),
        sunken = if (dark) surface(0.17f, 0.3f) else surface(0.915f, 0.42f),
        line = if (dark) surface(0.22f, 0.26f) else surface(0.85f, 0.34f),
        glow = if (dark) surface(0.24f, 0.55f) else surface(0.84f, 0.6f),
        accent = accent,
        onAccent = textOn(accent),
        wash = mix(paper, accent, if (dark) 0.22f else 0.16f),
    )
}

private fun vivid(color: Int, dark: Boolean): Int {
    val tone = hslOf(color)
    val lightness = if (dark) max(tone.lightness, 0.62f) else min(tone.lightness, 0.42f)
    val grey = tone.saturation < MIN_SATURATION / 2
    val saturation = if (grey) tone.saturation else tone.saturation.coerceIn(0.35f, 0.85f)
    return hsl(tone.hue, saturation, lightness)
}
