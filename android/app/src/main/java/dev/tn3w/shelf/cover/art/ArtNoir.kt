package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import dev.tn3w.shelf.cover.Anchor
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.Rule
import dev.tn3w.shelf.cover.TAU
import dev.tn3w.shelf.cover.Typeset
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.randomHarmonics
import dev.tn3w.shelf.cover.scanlines
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import dev.tn3w.shelf.cover.wobblyRingPath
import kotlin.math.cos
import kotlin.math.sin

private fun noirType(cover: CoverCanvas, accent: Int, authorFont: CoverFont): Typeset =
    with(cover) {
        Typeset(
            ink = Color.rgb(240, 236, 228),
            authorInk = accent,
            titleFont = random.pick(listOf(CoverFont.Sans, CoverFont.Condensed)),
            titleWeight = random.pick(listOf(800, 900)),
            titleTracking = random.range(0f, 0.04f),
            titleLeading = 1.02f,
            titleSize = random.range(0.10f, 0.125f),
            authorFont = authorFont,
            authorWeight = 400,
            authorTracking = 0.26f,
            rule = Rule.Bar,
            shadow = 0.8f,
            scrim = 0.35f,
        )
    }

internal fun mysteryStreet(cover: CoverCanvas): Typeset =
    with(cover) {
        val fog = hsv(random.range(0.08f, 0.14f), 0.10f, random.range(0.55f, 0.72f))
        val night = hsv(random.range(0.58f, 0.66f), random.range(0.30f, 0.55f),
            random.range(0.06f, 0.12f))
        val accent = hsv(random.range(0.09f, 0.13f), 0.70f, 0.95f)
        verticalGradient(night, mix(night, fog, 0.55f), random.range(1.6f, 2.4f))

        val lampX = random.range(0.22f, 0.78f) * width
        val lampY = random.range(0.18f, 0.30f) * height
        radialGlow(lampX, lampY, width * random.range(0.6f, 0.9f), accent, 0.55f, 1.8f)
        val spread = width * random.range(0.28f, 0.42f)
        val beam = Path()
        beam.moveTo(lampX, lampY)
        beam.lineTo(lampX - spread, height)
        beam.lineTo(lampX + spread, height)
        beam.close()
        canvas.drawPath(beam, glowPaint(shade(accent, 0.28f), unit(0.05f)))

        val ground = height * random.range(0.88f, 0.95f)
        val personX = lampX + random.range(-0.18f, 0.18f) * width
        val personHeight = random.range(0.20f, 0.30f) * height
        val shoulder = personHeight * 0.22f
        val figure = Path()
        figure.moveTo(personX - shoulder, ground)
        figure.lineTo(personX - shoulder * 0.8f, ground - personHeight * 0.72f)
        figure.lineTo(personX, ground - personHeight)
        figure.lineTo(personX + shoulder * 0.8f, ground - personHeight * 0.72f)
        figure.lineTo(personX + shoulder, ground)
        figure.close()
        val hat = personHeight * 0.20f
        figure.addOval(personX - hat, ground - personHeight - hat * 0.5f, personX + hat,
            ground - personHeight + hat * 0.4f, Path.Direction.CW)
        figure.addRect(0f, ground, width, height, Path.Direction.CW)
        canvas.drawPath(figure, fillPaint(Color.rgb(5, 5, 8)))

        if (random.chance(0.5f)) scanlines(0.5f, height / random.range(26f, 40f))
        vignette(0.72f, 0.42f)
        grain(0.06f)
        frame(CoverFrame.None, accent)
        noirType(cover, accent, CoverFont.Sans)
    }

internal fun mysteryKeyhole(cover: CoverCanvas): Typeset =
    with(cover) {
        val wall = hsv(random.range(0.55f, 0.65f), random.range(0.18f, 0.35f),
            random.range(0.10f, 0.16f))
        val warm = hsv(random.range(0.09f, 0.13f), random.range(0.35f, 0.60f),
            random.range(0.92f, 1f))
        verticalGradient(shade(wall, 1.5f), wall, 1.2f)
        texture(0.18f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.56f, 0.64f)
        val head = width * random.range(0.11f, 0.15f)
        val shaft = head * random.range(1.5f, 2.1f)
        val keyhole = Path()
        keyhole.addCircle(centerX, centerY, head, Path.Direction.CW)
        val stem = Path()
        stem.moveTo(centerX - head * 0.36f, centerY + head * 0.55f)
        stem.lineTo(centerX + head * 0.36f, centerY + head * 0.55f)
        stem.lineTo(centerX + head * 0.88f, centerY + shaft)
        stem.lineTo(centerX - head * 0.88f, centerY + shaft)
        stem.close()
        keyhole.addPath(stem)

        canvas.save()
        canvas.clipPath(keyhole)
        verticalGradient(mix(warm, Color.WHITE, 0.25f), shade(warm, 0.35f), 1.6f)
        if (random.chance(0.7f)) {
            val base = centerY + shaft * 0.92f
            val personHeight = head * random.range(1.0f, 1.5f)
            val inside = fillPaint(Color.rgb(20, 16, 14))
            canvas.drawCircle(centerX, base - personHeight + head * 0.1f, head * 0.22f, inside)
            val body = Path()
            body.moveTo(centerX - head * 0.42f, base)
            body.lineTo(centerX - head * 0.28f, base - personHeight * 0.66f)
            body.lineTo(centerX + head * 0.28f, base - personHeight * 0.66f)
            body.lineTo(centerX + head * 0.42f, base)
            body.close()
            canvas.drawPath(body, inside)
        }
        canvas.restore()

        canvas.drawPath(keyhole, glowPaint(warm, unit(0.03f), unit(0.010f)))
        val floor = Path()
        floor.moveTo(centerX - head * 0.9f, centerY + shaft)
        floor.lineTo(centerX + head * 0.9f, centerY + shaft)
        floor.lineTo(centerX + width * 0.42f, height)
        floor.lineTo(centerX - width * 0.42f, height)
        floor.close()
        canvas.drawPath(floor, glowPaint(shade(warm, 0.22f), unit(0.035f)))

        vignette(0.78f, 0.40f)
        grain(0.065f)
        noirType(cover, warm, CoverFont.Sans)
    }

internal fun mysteryPrint(cover: CoverCanvas): Typeset =
    with(cover) {
        val charcoal = hsv(random.range(0.55f, 0.68f), random.range(0.10f, 0.25f),
            random.range(0.10f, 0.15f))
        val accent = hsv(random.pick(listOf(0f, 0.02f, 0.12f, 0.55f)),
            random.range(0.55f, 0.85f), random.range(0.75f, 0.95f))
        verticalGradient(shade(charcoal, 1.6f), charcoal, 1.3f)
        texture(0.14f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.58f, 0.66f)
        val harmonics = randomHarmonics(random, 3, random.range(0.030f, 0.055f))
        val rings = random.between(20, 28)
        val step = random.range(0.0115f, 0.0155f)
        for (index in 0 until rings) {
            val radius = width * (0.018f + index * step)
            val ring = wobblyRingPath(centerX, centerY, radius, harmonics, 300, 1.22f)
            val tone = mix(accent, charcoal, 0.15f + 0.5f * (index.toFloat() / rings))
            val paint = strokePaint(tone, unit(0.004f))
            if (index == 0) {
                canvas.drawPath(ring, paint)
                continue
            }
            canvas.save()
            val gap = Path()
            val angle = random.range(0f, TAU)
            val reach = radius * 3f
            gap.moveTo(centerX, centerY)
            gap.lineTo(centerX + cos(angle) * reach, centerY + sin(angle) * reach)
            gap.lineTo(centerX + cos(angle + 1.1f) * reach, centerY + sin(angle + 1.1f) * reach)
            gap.close()
            canvas.clipOutPath(gap)
            canvas.drawPath(ring, paint)
            canvas.restore()
        }

        vignette(0.70f, 0.46f)
        grain(0.06f)
        frame(CoverFrame.Rule, accent)
        noirType(cover, accent, CoverFont.Mono)
    }

internal fun horrorCracks(cover: CoverCanvas): Typeset =
    with(cover) {
        val blood = hsv(random.range(0.98f, 1.02f), random.range(0.75f, 0.95f),
            random.range(0.45f, 0.65f))
        verticalGradient(Color.rgb(10, 8, 9), Color.rgb(26, 14, 16), 1f)
        val centerX = width * random.range(0.4f, 0.6f)
        val centerY = height * random.range(0.40f, 0.55f)
        radialGlow(centerX, centerY, width * random.range(0.55f, 0.8f), blood, 0.65f, 2.6f)

        val cracks = Path()
        for (index in 0 until random.between(3, 6)) {
            branch(cover, cracks, centerX, centerY, random.range(0f, TAU),
                height * random.range(0.10f, 0.18f), 6)
        }
        val ink = mix(blood, Color.rgb(255, 210, 200), 0.25f)
        canvas.drawPath(cracks, glowPaint(ink, unit(0.006f), unit(0.003f)))
        canvas.drawPath(cracks, strokePaint(ink, unit(0.0025f)))

        val scratch = strokePaint(Color.rgb(60, 60, 60), 1f)
        for (index in 0 until random.between(40, 90)) {
            val x = random.range(0f, width)
            val y = random.range(0f, height)
            val length = random.range(0.01f, 0.07f) * height
            val angle = random.range(-0.4f, 0.4f) + if (random.chance(0.5f)) 0f else TAU / 4f
            canvas.drawLine(x, y, x + cos(angle) * length, y + sin(angle) * length, scratch)
        }

        vignette(0.85f, 0.30f)
        grain(0.09f)
        frame(CoverFrame.Rule, shade(blood, 0.8f))
        horrorType(cover, blood)
    }

private fun branch(
    cover: CoverCanvas,
    path: Path,
    x: Float,
    y: Float,
    angle: Float,
    length: Float,
    depth: Int,
) {
    if (depth == 0 || length < cover.width * 0.01f) return
    val endX = x + cos(angle) * length
    val endY = y + sin(angle) * length
    path.moveTo(x, y)
    path.lineTo(endX, endY)
    for (index in 0 until cover.random.between(1, 2)) {
        branch(cover, path, endX, endY, angle + cover.random.range(-0.8f, 0.8f),
            length * cover.random.range(0.5f, 0.78f), depth - 1)
    }
}

private fun horrorType(cover: CoverCanvas, blood: Int): Typeset =
    with(cover) {
        Typeset(
            ink = mix(blood, Color.rgb(255, 245, 240), 0.55f),
            authorInk = Color.rgb(190, 178, 175),
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(600, 800)),
            titleTracking = random.range(0.02f, 0.12f),
            titleSize = random.range(0.10f, 0.13f),
            authorFont = CoverFont.Serif,
            authorWeight = 400,
            authorTracking = 0.28f,
            anchor = random.pick(listOf(Anchor.Top, Anchor.Bottom)),
            shadow = 1f,
        )
    }

internal fun horrorMoon(cover: CoverCanvas): Typeset =
    with(cover) {
        val blood = hsv(random.range(0.97f, 1.03f), random.range(0.55f, 0.85f),
            random.range(0.60f, 0.80f))
        val night = hsv(random.range(0.62f, 0.72f), random.range(0.45f, 0.70f),
            random.range(0.06f, 0.11f))
        verticalGradient(night, shade(night, 0.5f), 0.9f)

        val moonX = width * random.range(0.38f, 0.62f)
        val moonY = height * random.range(0.48f, 0.58f)
        val moonRadius = width * random.range(0.20f, 0.27f)
        radialGlow(moonX, moonY, moonRadius * 3.4f, blood, 0.42f, 2.4f)
        canvas.drawCircle(moonX, moonY, moonRadius,
            fillPaint(mix(blood, Color.rgb(255, 210, 190), 0.30f)))

        val branches = Path()
        val roots = random.between(2, 3)
        for (index in 0 until roots) {
            val baseX = width * (index + 0.5f) / roots + random.range(-0.08f, 0.08f) * width
            branch(cover, branches, baseX, height * 1.02f,
                -TAU / 4f + random.range(-0.25f, 0.25f), height * random.range(0.16f, 0.24f), 7)
        }
        canvas.drawPath(branches, strokePaint(Color.rgb(8, 6, 9), unit(0.006f)))

        val birds = strokePaint(Color.rgb(30, 24, 26), unit(0.003f))
        for (index in 0 until random.between(3, 8)) {
            val x = random.range(0.1f, 0.9f) * width
            val y = random.range(0.18f, 0.45f) * height
            val span = width * random.range(0.010f, 0.022f)
            canvas.drawLine(x - span, y, x, y - span * 0.5f, birds)
            canvas.drawLine(x, y - span * 0.5f, x + span, y, birds)
        }

        vignette(0.80f, 0.36f)
        grain(0.08f)
        frame(CoverFrame.Rule, shade(blood, 0.7f))
        horrorType(cover, blood)
    }
