package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.sin

internal fun fantasyPeaks(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.60f, 0.66f, 0.71f, 0.76f, 0.53f))
    val accent = hsv(
        random.pick(listOf(0.10f, 0.12f, 0.08f, 0.45f)),
        random.range(0.30f, 0.50f),
        1f,
    )
    val horizon = random.range(0.50f, 0.58f)
    val skyTop = hsv(hue, random.range(0.70f, 0.88f), random.range(0.10f, 0.16f))
    val skyLow = hsv(hue - 0.09f, random.range(0.45f, 0.65f), random.range(0.38f, 0.55f))
    verticalGradient(skyTop, skyLow, 2f)

    val moonX = random.pick(listOf(0.28f, 0.72f)) * width
    val moonY = random.range(0.38f, 0.44f) * height
    canvas.drawCircle(moonX, moonY, width * random.range(0.07f, 0.10f), fillPaint(accent))

    for (index in 0 until 3) {
        val depth = index / 2f
        val tone = mix(
            mix(skyLow, skyTop, 0.30f + depth * 0.45f), Color.BLACK, depth * 0.40f,
        )
        val shape = ridge(
            horizon + depth * 0.14f,
            random.range(0.04f, 0.14f) * (1f - depth * 0.4f),
            random.range(0.03f, 0.07f),
        )
        canvas.drawPath(shape, fillPaint(tone))
    }

    Typeset(ink = mix(accent, Color.WHITE, 0.7f), authorInk = accent, upper = true)
}

internal fun fantasySigil(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.72f, 0.78f, 0.52f, 0.66f, 0.04f))
    val accent = hsv(
        random.pick(listOf(0.10f, 0.45f, 0.55f, 0.85f)),
        random.range(0.45f, 0.70f),
        random.range(0.92f, 1f),
    )
    canvas.drawColor(hsv(hue, random.range(0.70f, 0.90f), random.range(0.08f, 0.13f)))

    val centerX = width * 0.5f
    val centerY = height * 0.56f
    val radius = width * random.range(0.26f, 0.32f)
    val sigil = Path()
    sigil.addCircle(centerX, centerY, radius, Path.Direction.CW)
    val points = random.pick(listOf(5, 6, 7))
    val skip = if (points % 2 == 0) 1 else 2
    val corners = List(points) {
        val angle = TAU * it / points - TAU / 4f
        (centerX + cos(angle) * radius) to (centerY + sin(angle) * radius)
    }
    for (index in 0 until points) {
        val (fromX, fromY) = corners[index]
        val (toX, toY) = corners[(index + skip) % points]
        sigil.moveTo(fromX, fromY)
        sigil.lineTo(toX, toY)
    }
    canvas.drawPath(sigil, strokePaint(accent, unit(0.006f)))

    Typeset(ink = mix(accent, Color.WHITE, 0.7f), authorInk = accent, upper = true)
}

internal fun fantasyForest(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.38f, 0.44f, 0.30f, 0.52f))
    val canopy = hsv(hue, random.range(0.60f, 0.85f), random.range(0.10f, 0.16f))
    val haze = hsv(
        hue - random.range(0.04f, 0.10f),
        random.range(0.30f, 0.50f),
        random.range(0.45f, 0.62f),
    )
    val glow = hsv(random.pick(listOf(0.12f, 0.16f, 0.45f)), 0.40f, 1f)
    verticalGradient(canopy, haze, 2.6f)

    val trunks = List(random.between(5, 7)) {
        random.range(-0.05f, 1.05f) * width to
            random.float()
    }
        .sortedBy { it.second }
    for ((position, depth) in trunks) {
        val thickness = width * (0.012f + depth * depth * random.range(0.045f, 0.080f))
        val lean = random.range(-0.03f, 0.03f) * width
        val tone = mix(
            mix(haze, canopy, 0.45f), Color.rgb(5, 9, 7), 0.25f + depth * 0.70f,
        )
        val trunk = Path()
        trunk.moveTo(position - thickness, height)
        trunk.lineTo(position - thickness * 0.62f + lean, 0f)
        trunk.lineTo(position + thickness * 0.62f + lean, 0f)
        trunk.lineTo(position + thickness, height)
        trunk.close()
        canvas.drawPath(trunk, fillPaint(tone))
    }

    Typeset(
        ink = mix(glow, Color.WHITE, 0.75f),
        authorInk = glow,
        upper = true,
    )
}

internal fun fantasyDawn(cover: CoverCanvas): Typeset = with(cover) {
    val glow = hsv(random.pick(listOf(0.02f, 0.06f, 0.10f, 0.95f)), 0.45f, 1f)
    val deep = hsv(
        random.pick(listOf(0.62f, 0.70f, 0.78f, 0.85f)),
        random.range(0.35f, 0.55f),
        random.range(0.28f, 0.38f),
    )
    verticalGradient(hsv(0.60f, 0.16f, 0.96f), glow, 1.3f)

    val horizon = random.range(0.56f, 0.62f)
    val sunRadius = width * random.range(0.14f, 0.20f)
    canvas.drawCircle(
        width * random.range(0.3f, 0.7f),
        horizon * height - sunRadius * 0.3f,
        sunRadius,
        fillPaint(mix(glow, Color.WHITE, 0.65f)),
    )

    for (index in 0 until 3) {
        val depth = index / 2f
        val shape = ridge(
            horizon + depth * 0.18f,
            random.range(0.04f, 0.10f),
            random.range(0.03f, 0.06f),
        )
        canvas.drawPath(
            shape,
            fillPaint(mix(mix(glow, Color.WHITE, 0.3f), deep, 0.3f + depth * 0.7f)),
        )
    }

    Typeset(ink = shade(deep, 0.6f), italic = random.chance(0.3f))
}

internal fun fantasyIlluminated(cover: CoverCanvas): Typeset = with(cover) {
    val vellum = hsv(random.range(0.09f, 0.12f), random.range(0.12f, 0.20f), 0.95f)
    val gold = hsv(random.range(0.10f, 0.12f), 0.65f, random.range(0.78f, 0.86f))
    val blue = hsv(random.range(0.60f, 0.64f), 0.75f, random.range(0.45f, 0.58f))
    val red = hsv(random.range(0.98f, 1.01f), 0.75f, random.range(0.60f, 0.72f))
    canvas.drawColor(vellum)

    val centerX = width * 0.5f
    val centerY = height * 0.58f
    val radius = width * random.range(0.20f, 0.24f)
    canvas.drawCircle(centerX, centerY, radius, fillPaint(blue))
    canvas.drawCircle(centerX, centerY, radius, strokePaint(gold, unit(0.012f)))
    val petals = random.pick(listOf(6, 8))
    for (index in 0 until petals) {
        val angle = TAU * index / petals
        canvas.drawCircle(
            centerX + cos(angle) * radius * 0.45f,
            centerY + sin(angle) * radius * 0.45f,
            radius * 0.24f,
            fillPaint(red),
        )
    }
    canvas.drawCircle(centerX, centerY, radius * 0.25f, fillPaint(gold))

    Typeset(ink = shade(red, 0.55f), authorInk = shade(blue, 0.7f), upper = true)
}

private val CRYSTAL_HUES = listOf(0.33f, 0.98f, 0.78f, 0.58f, 0.12f, 0.48f)

internal fun fantasyCrystal(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(CRYSTAL_HUES)
    val gem = hsv(hue, random.range(0.60f, 0.80f), random.range(0.80f, 0.95f))
    verticalGradient(hsv(hue + 0.04f, 0.70f, 0.08f), hsv(hue, 0.60f, 0.26f), 1.2f)

    val baseY = height * 0.86f
    val shards = List(random.between(3, 5)) {
        Triple(
            width * random.range(0.38f, 0.62f),
            random.range(-22f, 22f),
            height * random.range(0.18f, 0.40f),
        )
    }.sortedByDescending { it.third }
    for ((x, tilt, length) in shards) {
        shard(cover, x, baseY, length, width * random.range(0.04f, 0.065f), tilt, gem)
    }
    canvas.drawRect(0f, baseY, width, height, fillPaint(hsv(hue, 0.35f, 0.07f)))

    Typeset(
        ink = mix(gem, Color.WHITE, 0.8f),
        authorInk = mix(gem, Color.WHITE, 0.4f),
        font = random.pick(listOf(CoverFont.Serif, CoverFont.Sans)),
        upper = true,
    )
}

private fun shard(
    cover: CoverCanvas,
    x: Float,
    baseY: Float,
    length: Float,
    breadth: Float,
    tilt: Float,
    gem: Int,
) = with(cover) {
    val tone = mix(gem, Color.WHITE, random.range(0f, 0.3f))
    canvas.save()
    canvas.rotate(tilt, x, baseY)
    val shoulder = baseY - length * 0.78f
    val tip = baseY - length
    val left = Path()
    left.moveTo(x - breadth, baseY)
    left.lineTo(x - breadth, shoulder)
    left.lineTo(x, tip)
    left.lineTo(x, baseY)
    left.close()
    val right = Path()
    right.moveTo(x + breadth, baseY)
    right.lineTo(x + breadth, shoulder)
    right.lineTo(x, tip)
    right.lineTo(x, baseY)
    right.close()
    canvas.drawPath(left, fillPaint(mix(tone, Color.WHITE, 0.35f)))
    canvas.drawPath(right, fillPaint(shade(tone, 0.55f)))
    canvas.restore()
}

private val SKIES = listOf(
    Triple(0.98f, 0.07f, 0.12f),
    Triple(0.75f, 0.03f, 0.10f),
    Triple(0.60f, 0.52f, 0.14f),
)

internal fun fantasySword(cover: CoverCanvas): Typeset = with(cover) {
    val (topHue, lowHue, sunHue) = random.pick(SKIES)
    val low = hsv(lowHue, random.range(0.55f, 0.75f), random.range(0.85f, 0.95f))
    verticalGradient(hsv(topHue, 0.75f, 0.22f), low, 0.9f)
    val sun = hsv(sunHue, 0.30f, 1f)
    canvas.drawCircle(
        width * 0.5f, height * 0.60f, width * random.range(0.24f, 0.30f), fillPaint(sun),
    )

    val ink = hsv(topHue, 0.5f, 0.08f)
    sword(cover, width * 0.5f, height * 0.56f, ink)
    canvas.drawRect(0f, height * 0.88f, width, height, fillPaint(ink))

    Typeset(ink = mix(sun, Color.WHITE, 0.6f), upper = true)
}

private fun sword(cover: CoverCanvas, x: Float, guardY: Float, ink: Int) = with(cover) {
    val paint = fillPaint(ink)
    val blade = Path()
    blade.moveTo(x - unit(0.034f), guardY)
    blade.lineTo(x + unit(0.034f), guardY)
    blade.lineTo(x + unit(0.024f), height * 0.86f)
    blade.lineTo(x, height * 0.89f)
    blade.lineTo(x - unit(0.024f), height * 0.86f)
    blade.close()
    canvas.drawPath(blade, paint)
    val guard = unit(0.012f)
    val reach = unit(0.14f)
    canvas.drawRect(x - reach, guardY - guard, x + reach, guardY + guard, paint)
    canvas.drawRect(
        x - unit(0.011f), guardY - unit(0.11f), x + unit(0.011f), guardY, paint,
    )
    canvas.drawCircle(x, guardY - unit(0.12f), unit(0.024f), paint)
}
