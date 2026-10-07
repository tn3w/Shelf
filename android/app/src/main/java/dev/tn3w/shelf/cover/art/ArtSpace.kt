package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.sin

private val SPACE_INK = Color.rgb(240, 244, 250)

internal fun scifiPlanet(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.52f, 0.58f, 0.62f, 0.78f, 0.86f))
    val accent = hsv(hue + random.range(0.25f, 0.45f), 0.55f, 1f)
    val night = hsv(hue, 0.80f, 0.09f)
    canvas.drawColor(night)

    val planetX = width * 0.5f
    val planetY = random.range(0.62f, 0.70f) * height
    val radius = random.range(0.30f, 0.38f) * width
    val shift = radius * random.range(0.30f, 0.42f)
    canvas.drawCircle(planetX, planetY, radius, fillPaint(accent))
    canvas.drawCircle(
        planetX + shift * random.sign(), planetY + shift * 0.6f, radius, fillPaint(night),
    )

    Typeset(ink = SPACE_INK, authorInk = accent, font = CoverFont.Sans, upper = true)
}

internal fun scifiCircuit(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.48f, 0.52f, 0.78f, 0.86f, 0.33f))
    val accent = hsv(hue, random.range(0.70f, 0.95f), random.range(0.85f, 1f))
    canvas.drawColor(hsv(hue, 0.55f, 0.10f))

    val pitch = width / 10f
    val traces = Path()
    val pads = mutableListOf<Pair<Float, Float>>()
    repeat(random.between(6, 9)) {
        var x = Math.round(random.range(0.15f, 0.85f) * width / pitch) * pitch
        var y = Math.round(random.range(0.44f, 0.72f) * height / pitch) * pitch
        traces.moveTo(x, y)
        repeat(random.between(2, 4)) {
            val span = pitch * random.between(1, 3)
            if (random.chance(0.5f)) {
                x += span * random.sign()
            } else {
                y +=
                    span * random.sign()
            }
            traces.lineTo(x, y)
        }
        pads += x to y
    }
    canvas.drawPath(traces, strokePaint(accent, unit(0.006f)))
    for ((x, y) in pads) canvas.drawCircle(x, y, unit(0.014f), fillPaint(accent))

    Typeset(
        ink = SPACE_INK,
        authorInk = accent,
        font = CoverFont.Sans,
        upper = true,
    )
}

internal fun scifiOrbit(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.60f, 0.66f, 0.72f, 0.04f, 0.55f))
    val accent = hsv(
        random.pick(listOf(0.05f, 0.10f, 0.14f, 0.50f)),
        random.range(0.70f, 0.90f),
        random.range(0.88f, 1f),
    )
    val pale = hsv(hue - 0.06f, 0.20f, 0.92f)
    canvas.drawColor(hsv(hue, random.range(0.55f, 0.80f), random.range(0.10f, 0.16f)))

    val centerX = width * 0.5f
    val centerY = height * 0.60f
    val sunRadius = width * random.range(0.08f, 0.11f)
    canvas.drawCircle(centerX, centerY, sunRadius, fillPaint(accent))

    val tilt = random.range(0.30f, 0.45f)
    val orbitPaint = strokePaint(alpha(pale, 0.5f), unit(0.003f))
    for (index in 0 until 3) {
        val radius = sunRadius * (2f + index * 1.1f)
        canvas.drawOval(
            RectF(
                centerX - radius, centerY - radius * tilt, centerX + radius,
                centerY + radius * tilt,
            ),
            orbitPaint,
        )
        val angle = random.range(0f, TAU)
        canvas.drawCircle(
            centerX + cos(angle) * radius,
            centerY + sin(angle) * radius * tilt,
            width * random.range(0.016f, 0.026f),
            fillPaint(pale),
        )
    }

    Typeset(ink = pale, authorInk = accent, font = CoverFont.Sans, upper = true)
}

internal fun scifiTunnel(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.50f, 0.55f, 0.80f, 0.88f, 0.08f))
    val accent = hsv(hue, random.range(0.60f, 0.85f), 1f)
    canvas.drawColor(hsv(hue, 0.7f, 0.08f))
    val centerX = width * 0.5f
    val centerY = height * 0.60f

    val sides = random.pick(listOf(4, 6, 0))
    var reach = width * 0.46f
    for (ring in 0 until 7) {
        val paint = strokePaint(alpha(accent, 0.3f + ring * 0.1f), unit(0.004f))
        if (sides == 0) {
            canvas.drawCircle(centerX, centerY, reach, paint)
        } else {
            canvas.drawPath(polygon(centerX, centerY, reach, sides), paint)
        }
        reach *= 0.74f
    }

    Typeset(ink = SPACE_INK, authorInk = accent, font = CoverFont.Sans, upper = true)
}

private fun polygon(centerX: Float, centerY: Float, radius: Float, sides: Int): Path {
    val path = Path()
    val turn = if (sides == 4) TAU / 8 else 0f
    for (index in 0 until sides) {
        val angle = TAU * index / sides + turn
        val x = centerX + cos(angle) * radius
        val y = centerY + sin(angle) * radius
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
