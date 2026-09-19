package dev.tn3w.shelf.cover

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

internal const val TAU = (Math.PI * 2).toFloat()

internal class CoverCanvas(val bitmap: Bitmap, val random: CoverRandom) {
    val canvas = Canvas(bitmap)
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()

    fun unit(fraction: Float) = width * fraction
}

internal fun hsv(hue: Float, saturation: Float, value: Float): Int {
    val wrapped = ((hue % 1f) + 1f) % 1f
    return Color.HSVToColor(floatArrayOf(wrapped * 360f, saturation, value))
}

internal fun mix(first: Int, second: Int, amount: Float): Int {
    val weight = amount.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(first) + (Color.red(second) - Color.red(first)) * weight).toInt(),
        (Color.green(first) + (Color.green(second) - Color.green(first)) * weight).toInt(),
        (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * weight).toInt(),
    )
}

internal fun shade(color: Int, factor: Float) =
    Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

internal fun luminance(color: Int) =
    (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) /
        255f

internal fun alpha(color: Int, amount: Float) =
    Color.argb(
        (amount.coerceIn(0f, 1f) * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

internal fun fillPaint(color: Int) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

internal fun strokePaint(color: Int, thickness: Float) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = thickness.coerceAtLeast(1f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

internal fun glowPaint(color: Int, blur: Float, thickness: Float = 0f): Paint {
    val paint = if (thickness > 0f) strokePaint(color, thickness) else fillPaint(color)
    paint.maskFilter = BlurMaskFilter(blur.coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    return paint
}

private fun rampStops(count: Int) = FloatArray(count) { it / (count - 1f) }

internal fun CoverCanvas.verticalGradient(top: Int, bottom: Int, gamma: Float = 1f) {
    val stops = rampStops(12)
    val colors = IntArray(stops.size) { mix(top, bottom, stops[it].pow(gamma)) }
    val paint = fillPaint(Color.BLACK)
    paint.shader = LinearGradient(0f, 0f, 0f, height, colors, stops, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
}

internal fun CoverCanvas.radialGlow(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Int,
    strength: Float,
    falloff: Float = 2.2f,
) {
    val stops = rampStops(10)
    val colors = IntArray(stops.size) { alpha(color, (1f - stops[it]).pow(falloff) * strength) }
    val paint = fillPaint(Color.BLACK)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    paint.shader =
        RadialGradient(centerX, centerY, radius.coerceAtLeast(1f), colors, stops,
            Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
}

internal fun CoverCanvas.vignette(strength: Float, start: Float) {
    val extent = hypot(width, height) / 2f
    val stops = rampStops(9)
    val colors =
        IntArray(stops.size) {
            val ramp = ((stops[it] - start) / (1.05f - start)).coerceIn(0f, 1f).pow(1.5f)
            Color.argb((ramp * strength * 255).toInt(), 0, 0, 0)
        }
    val paint = fillPaint(Color.BLACK)
    paint.shader =
        RadialGradient(width / 2f, height / 2f, extent, colors, stops, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
}

private object CoverNoise {
    val sharp = tile(128, 1f)
    val smooth = tile(24, 0.75f)

    private fun tile(size: Int, spread: Float): Bitmap {
        val random = CoverRandom(0x5EEDL)
        val pixels = IntArray(size * size)
        for (index in pixels.indices) {
            val value = (128 + (random.float() - 0.5f) * 255f * spread).toInt().coerceIn(0, 255)
            pixels[index] = Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

private fun CoverCanvas.noise(tile: Bitmap, strength: Float, scale: Float, filter: Boolean) {
    val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    shader.setLocalMatrix(
        Matrix().apply {
            setScale(scale, scale)
            postTranslate(random.range(0f, 512f), random.range(0f, 512f))
        }
    )
    val paint = fillPaint(Color.BLACK)
    paint.shader = shader
    paint.isFilterBitmap = filter
    paint.alpha = (strength * 255).toInt().coerceIn(0, 255)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    canvas.drawRect(0f, 0f, width, height, paint)
}

internal fun CoverCanvas.grain(strength: Float) = noise(CoverNoise.sharp, strength, 1f, false)

internal fun CoverCanvas.texture(strength: Float, scale: Float = 12f) =
    noise(CoverNoise.smooth, strength, scale, true)

internal fun CoverCanvas.scrimBand(top: Float, bottom: Float, toward: Int, strength: Float) {
    if (strength <= 0.01f) return
    val stops = floatArrayOf(0f, 0.5f, 1f)
    val colors =
        intArrayOf(alpha(toward, 0f), alpha(toward, strength.coerceIn(0f, 1f)),
            alpha(toward, 0f))
    val paint = fillPaint(Color.BLACK)
    paint.shader = LinearGradient(0f, top, 0f, bottom, colors, stops, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, top, width, bottom, paint)
}

internal fun CoverCanvas.scanlines(strength: Float, period: Float) {
    val paint = fillPaint(Color.argb((strength * 255).toInt().coerceIn(0, 255), 0, 0, 0))
    var y = 0f
    while (y < height) {
        canvas.drawRect(0f, y, width, y + period * 0.4f, paint)
        y += period
    }
}

internal fun CoverCanvas.stars(count: Int, until: Float, color: Int, largest: Float) {
    val paint = fillPaint(color)
    for (index in 0 until count) {
        val y = random.range(0f, until)
        val radius = random.range(largest * 0.25f, largest)
        paint.alpha = random.between(60, 255)
        canvas.drawCircle(random.range(0f, width), y, radius, paint)
    }
}

internal fun displacedLine(
    random: CoverRandom,
    start: Float,
    end: Float,
    roughness: Float,
    depth: Int = 7,
): FloatArray {
    var points = floatArrayOf(start, end)
    var scale = roughness
    for (round in 0 until depth) {
        val stepped = FloatArray(points.size * 2 - 1)
        for (index in 0 until points.size - 1) {
            val middle = (points[index] + points[index + 1]) / 2f
            stepped[index * 2] = points[index]
            stepped[index * 2 + 1] = middle + random.range(-scale, scale)
        }
        stepped[stepped.size - 1] = points[points.size - 1]
        points = stepped
        scale *= 0.52f
    }
    return points
}

internal class Ridge(val fill: Path, val crest: Path)

internal fun CoverCanvas.ridge(baseline: Float, rise: Float, roughness: Float): Ridge {
    val profile = displacedLine(random, baseline + random.range(-0.03f, 0.03f), baseline,
        roughness)
    val step = width / (profile.size - 1)
    val fill = Path()
    val crest = Path()
    fill.moveTo(0f, height)
    for (index in profile.indices) {
        val x = index * step
        val y = (profile[index] - rise) * height
        fill.lineTo(x, y)
        if (index == 0) crest.moveTo(x, y) else crest.lineTo(x, y)
    }
    fill.lineTo(width, height)
    fill.close()
    return Ridge(fill, crest)
}

internal enum class CoverFrame {
    None,
    Ornate,
    Double,
    Hairline,
    Corners,
    Rule,
}

internal fun CoverCanvas.frame(style: CoverFrame, accent: Int) {
    if (style == CoverFrame.None) return
    val inset = unit(random.range(0.045f, 0.06f))
    val tint = alpha(accent, random.range(0.45f, 0.75f))
    val thin = unit(0.0025f)
    val right = width - inset
    val bottom = height - inset
    if (style == CoverFrame.Rule) {
        canvas.drawRect(inset, inset, right, inset + thin * 2f, fillPaint(tint))
        canvas.drawRect(inset, bottom, right, bottom + thin * 2f, fillPaint(tint))
        return
    }
    if (style == CoverFrame.Corners) {
        val arm = unit(0.06f)
        val paint = strokePaint(tint, thin)
        for (corner in listOf(inset to inset, right to inset, inset to bottom, right to bottom)) {
            val stepX = if (corner.first == inset) arm else -arm
            val stepY = if (corner.second == inset) arm else -arm
            canvas.drawLine(corner.first, corner.second, corner.first + stepX, corner.second,
                paint)
            canvas.drawLine(corner.first, corner.second, corner.first, corner.second + stepY,
                paint)
        }
        return
    }
    canvas.drawRect(inset, inset, right, bottom, strokePaint(tint, thin))
    if (style == CoverFrame.Double) {
        val gap = unit(0.012f)
        canvas.drawRect(inset + gap, inset + gap, right - gap, bottom - gap,
            strokePaint(tint, thin * 0.5f))
    }
    if (style != CoverFrame.Ornate) return
    val mark = unit(0.016f)
    for (corner in listOf(inset to inset, right to inset, inset to bottom, right to bottom)) {
        canvas.drawPath(
            starPath(corner.first, corner.second, mark, mark * 0.35f, 4, 0f),
            fillPaint(alpha(accent, 0.86f)),
        )
    }
}

internal class Harmonic(val amplitude: Float, val frequency: Int, val phase: Float)

internal fun randomHarmonics(random: CoverRandom, count: Int, strength: Float): List<Harmonic> {
    val frequencies = mutableListOf(2, 3, 4, 5, 7)
    return List(count) {
        val frequency = frequencies.removeAt(random.index(frequencies.size))
        Harmonic(random.range(strength * 0.4f, strength), frequency, random.range(0f, TAU))
    }
}

internal fun wobblyRingPath(
    centerX: Float,
    centerY: Float,
    radius: Float,
    harmonics: List<Harmonic>,
    points: Int = 160,
    squash: Float = 1f,
): Path {
    val path = Path()
    for (index in 0..points) {
        val angle = TAU * index / points
        var offset = 0f
        for (harmonic in harmonics) {
            offset += harmonic.amplitude * sin(harmonic.frequency * angle + harmonic.phase)
        }
        val distance = radius * (1f + offset)
        val x = centerX + cos(angle) * distance
        val y = centerY + sin(angle) * distance * squash
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

internal fun starPath(
    centerX: Float,
    centerY: Float,
    outer: Float,
    inner: Float,
    points: Int,
    rotation: Float = 0f,
): Path {
    val path = Path()
    for (index in 0 until points * 2) {
        val radius = if (index % 2 == 0) outer else inner
        val angle = TAU * index / (points * 2) + rotation
        val x = centerX + cos(angle) * radius
        val y = centerY + sin(angle) * radius
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
