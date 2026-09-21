package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.Anchor
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.Rule
import dev.tn3w.shelf.cover.TAU
import dev.tn3w.shelf.cover.Typeset
import dev.tn3w.shelf.cover.alpha
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.scanlines
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.stars
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.cos
import kotlin.math.sin

internal fun scifiPlanet(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.52f, 0.58f, 0.62f, 0.78f, 0.86f))
        val accent = hsv(hue + random.range(0.25f, 0.45f), 0.75f, 1f)
        verticalGradient(hsv(hue, 0.85f, 0.06f), hsv(hue, 0.70f, 0.22f), 1.4f)
        stars((width * 0.9f).toInt(), height, Color.WHITE, unit(0.0025f))

        val planetX = random.range(0.3f, 0.7f) * width
        val planetY = random.range(0.52f, 0.66f) * height
        val planetRadius = random.range(0.28f, 0.40f) * width
        radialGlow(planetX, planetY, planetRadius * 2f, accent, 0.30f, 2.4f)
        canvas.drawCircle(planetX, planetY, planetRadius,
            fillPaint(hsv(hue + 0.04f, 0.55f, 0.16f)))

        val bands = random.between(5, 9)
        for (index in 0 until bands) {
            val offset = random.range(-planetRadius, planetRadius)
            val thickness = planetRadius * random.range(0.04f, 0.14f)
            canvas.save()
            canvas.clipPath(Path().apply {
                addCircle(planetX, planetY, planetRadius, Path.Direction.CW)
            })
            canvas.drawRect(planetX - planetRadius, planetY + offset, planetX + planetRadius,
                planetY + offset + thickness,
                fillPaint(alpha(mix(accent, Color.WHITE, 0.3f), random.range(0.05f, 0.16f))))
            canvas.restore()
        }

        val limb = RectF(planetX - planetRadius, planetY - planetRadius,
            planetX + planetRadius, planetY + planetRadius)
        canvas.drawArc(limb, random.range(180f, 240f), random.range(90f, 150f), false,
            glowPaint(mix(accent, Color.WHITE, 0.4f), unit(0.012f), unit(0.012f)))

        horizonGrid(cover, accent)
        vignette(0.45f, 0.7f)
        grain(0.03f)
        frame(CoverFrame.Corners, accent)
        Typeset(
            ink = Color.rgb(240, 244, 250),
            authorInk = accent,
            titleFont = random.pick(listOf(CoverFont.Sans, CoverFont.Mono)),
            titleWeight = random.pick(listOf(300, 500)),
            titleTracking = random.range(0.16f, 0.26f),
            titleSize = random.range(0.065f, 0.082f),
            authorFont = CoverFont.Mono,
            authorWeight = 400,
            authorTracking = 0.22f,
            shadow = 0.6f,
        )
    }

private fun horizonGrid(cover: CoverCanvas, accent: Int) =
    with(cover) {
        val top = height * random.range(0.78f, 0.86f)
        val paint = strokePaint(mix(accent, Color.BLACK, 0.45f), unit(0.0016f))
        for (index in 1..13) {
            val fraction = index / 13f
            val y = top + (height - top) * fraction * fraction
            canvas.drawLine(0f, y, width, y, paint)
        }
        for (index in -9..9) {
            canvas.drawLine(width / 2f + index * width * 0.055f, top,
                width / 2f + index * width * 0.5f, height, paint)
        }
    }

internal fun scifiCircuit(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.48f, 0.52f, 0.78f, 0.86f, 0.33f))
        val accent = hsv(hue, random.range(0.70f, 0.95f), random.range(0.85f, 1f))
        val second = hsv(hue + random.range(0.3f, 0.5f), 0.85f, 0.95f)
        verticalGradient(Color.rgb(6, 7, 11), hsv(hue, 0.60f, 0.14f), 1.2f)

        val traces = Path()
        val pitch = width / random.between(10, 16)
        val pads = mutableListOf<Pair<Float, Float>>()
        for (run in 0 until random.between(14, 24)) {
            var x = Math.round(random.range(0f, width) / pitch) * pitch
            var y = Math.round(random.range(0f, height) / pitch) * pitch
            traces.moveTo(x, y)
            for (step in 0 until random.between(3, 8)) {
                val span = pitch * random.between(1, 4)
                if (random.chance(0.5f)) x += span * random.sign() else y += span * random.sign()
                traces.lineTo(x, y)
            }
            pads += x to y
        }
        canvas.drawPath(traces, glowPaint(accent, unit(0.012f), unit(0.0035f)))
        canvas.drawPath(traces, strokePaint(accent, unit(0.0035f)))
        val padPaint = fillPaint(second)
        for ((x, y) in pads) canvas.drawCircle(x, y, unit(0.006f), padPaint)

        hud(cover, accent, second)
        scanlines(0.18f, height / 300f)
        vignette(0.55f, 0.55f)
        grain(0.04f)
        frame(CoverFrame.Corners, accent)
        Typeset(
            ink = Color.rgb(236, 244, 252),
            authorInk = accent,
            titleFont = CoverFont.Mono,
            titleWeight = random.pick(listOf(400, 700)),
            titleTracking = random.range(0.04f, 0.14f),
            titleSize = random.range(0.070f, 0.088f),
            authorFont = CoverFont.Mono,
            authorWeight = 400,
            authorTracking = 0.18f,
            anchor = random.pick(listOf(Anchor.Top, Anchor.Bottom)),
            rule = Rule.Bar,
            shadow = 0.6f,
            scrim = 0.3f,
        )
    }

private fun hud(cover: CoverCanvas, accent: Int, second: Int) =
    with(cover) {
        val centerX = width * 0.5f
        val centerY = height * random.range(0.56f, 0.66f)
        val radius = width * random.range(0.22f, 0.30f)
        val arcs = listOf(Triple(1f, 0f, 250f), Triple(0.82f, 200f, 300f),
            Triple(0.6f, 40f, 160f))
        val rings = Path()
        for ((scale, start, sweep) in arcs) {
            val ring = radius * scale
            rings.addArc(RectF(centerX - ring, centerY - ring, centerX + ring, centerY + ring),
                start, sweep)
        }
        canvas.drawPath(rings, glowPaint(accent, unit(0.02f), unit(0.004f)))
        canvas.drawPath(rings, strokePaint(accent, unit(0.004f)))

        val ticks = strokePaint(second, unit(0.002f))
        for (index in 0 until 36) {
            val angle = TAU * index / 36
            val inner = radius * if (index % 3 == 0) 1.10f else 1.06f
            canvas.drawLine(centerX + cos(angle) * radius * 1.02f,
                centerY + sin(angle) * radius * 1.02f, centerX + cos(angle) * inner,
                centerY + sin(angle) * inner, ticks)
        }
        canvas.drawCircle(centerX, centerY, radius * 0.16f,
            glowPaint(mix(accent, Color.WHITE, 0.5f), unit(0.01f)))
    }

internal fun scifiOrbit(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.60f, 0.66f, 0.72f, 0.04f, 0.55f))
        val base = hsv(hue, random.range(0.55f, 0.80f), random.range(0.12f, 0.20f))
        val accent = hsv(random.pick(listOf(0.05f, 0.10f, 0.14f, 0.50f)),
            random.range(0.70f, 0.90f), random.range(0.88f, 1f))
        val pale = hsv(hue - 0.06f, 0.25f, 0.92f)
        verticalGradient(shade(base, 0.6f), base, 1f)
        stars((width * 0.35f).toInt(), height, pale, unit(0.0018f))

        val centerX = width * 0.5f
        val centerY = height * random.range(0.58f, 0.66f)
        val starRadius = width * random.range(0.10f, 0.15f)
        canvas.drawCircle(centerX, centerY, starRadius, fillPaint(accent))

        val tilt = random.range(0.28f, 0.48f)
        val orbits = random.between(3, 5)
        val orbitPaint = strokePaint(alpha(pale, 0.67f), unit(0.0028f))
        for (index in 0 until orbits) {
            val radius = starRadius * (1.9f + index * random.range(0.55f, 0.85f))
            canvas.drawOval(RectF(centerX - radius, centerY - radius * tilt, centerX + radius,
                centerY + radius * tilt), orbitPaint)
            val angle = random.range(0f, TAU)
            canvas.drawCircle(centerX + cos(angle) * radius,
                centerY + sin(angle) * radius * tilt, width * random.range(0.012f, 0.030f),
                fillPaint(mix(pale, accent, random.range(0f, 0.8f))))
        }

        val barY = height * random.range(0.38f, 0.44f)
        canvas.drawRect(0f, barY, width, barY + height * 0.004f, fillPaint(alpha(accent, 0.86f)))

        vignette(0.40f, 0.66f)
        grain(0.03f)
        Typeset(
            ink = pale,
            authorInk = accent,
            titleFont = CoverFont.Sans,
            titleWeight = random.pick(listOf(300, 400)),
            titleTracking = random.range(0.18f, 0.32f),
            titleSize = random.range(0.058f, 0.072f),
            authorFont = CoverFont.Sans,
            authorWeight = 400,
            authorTracking = 0.26f,
            rule = Rule.None,
            shadow = 0.4f,
        )
    }

internal fun scifiTunnel(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.50f, 0.55f, 0.80f, 0.88f, 0.08f))
        val accent = hsv(hue, random.range(0.60f, 0.85f), 1f)
        verticalGradient(Color.rgb(4, 4, 10), hsv(hue, 0.7f, 0.12f), 1f)
        val centerX = width * random.range(0.42f, 0.58f)
        val centerY = height * random.range(0.58f, 0.64f)
        radialGlow(centerX, centerY, width * 0.5f, accent, 0.6f, 2.6f)

        val sides = random.pick(listOf(4, 6, 0))
        val spokes = if (sides == 0) 16 else sides * 2
        val line = strokePaint(alpha(accent, 0.25f), unit(0.002f))
        for (index in 0 until spokes) {
            val angle = TAU * index / spokes + if (sides == 4) TAU / 8 else 0f
            canvas.drawLine(centerX, centerY, centerX + cos(angle) * width * 1.5f,
                centerY + sin(angle) * width * 1.5f, line)
        }
        var reach = width * 1.1f
        var ring = 0
        while (reach > width * 0.02f) {
            val strength = (0.25f + ring * 0.07f).coerceAtMost(0.95f)
            val paint = strokePaint(alpha(accent, strength), unit(0.004f))
            if (sides == 0) canvas.drawCircle(centerX, centerY, reach, paint)
            else canvas.drawPath(polygon(centerX, centerY, reach, sides), paint)
            reach *= random.range(0.72f, 0.80f)
            ring++
        }
        canvas.drawCircle(centerX, centerY, unit(0.02f),
            glowPaint(mix(accent, Color.WHITE, 0.7f), unit(0.02f)))

        vignette(0.5f, 0.5f)
        grain(0.03f)
        Typeset(
            ink = Color.rgb(240, 244, 250),
            authorInk = accent,
            titleFont = random.pick(listOf(CoverFont.Sans, CoverFont.Mono)),
            titleWeight = random.pick(listOf(300, 700)),
            titleTracking = random.range(0.14f, 0.26f),
            titleSize = random.range(0.065f, 0.085f),
            authorFont = CoverFont.Mono,
            authorWeight = 400,
            authorTracking = 0.22f,
            rule = Rule.None,
            shadow = 0.6f,
            scrim = 0.3f,
        )
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
