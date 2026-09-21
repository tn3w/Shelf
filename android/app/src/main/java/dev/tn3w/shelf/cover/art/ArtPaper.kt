package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.Anchor
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.LetterCase
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
import dev.tn3w.shelf.cover.randomHarmonics
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.starPath
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import dev.tn3w.shelf.cover.wobblyRingPath
import kotlin.math.cos
import kotlin.math.sin

internal fun historyEmblem(cover: CoverCanvas): Typeset =
    with(cover) {
        val parchment = hsv(random.range(0.08f, 0.11f), random.range(0.18f, 0.30f),
            random.range(0.88f, 0.95f))
        val sepia = hsv(random.range(0.06f, 0.09f), random.range(0.55f, 0.70f),
            random.range(0.30f, 0.42f))
        verticalGradient(parchment, mix(parchment, sepia, 0.35f), 1.5f)
        texture(0.14f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.50f, 0.60f)
        val radius = width * random.range(0.18f, 0.26f)
        for (index in 0 until random.between(2, 4)) {
            val ring = radius * (1f - index * 0.09f)
            canvas.drawCircle(centerX, centerY, ring,
                strokePaint(sepia, unit(if (index == 0) 0.006f else 0.002f)))
        }
        val spokes = random.pick(listOf(8, 12, 16, 24))
        val spokePaint = strokePaint(sepia, unit(0.0025f))
        for (index in 0 until spokes) {
            val angle = TAU * index / spokes
            val inner = radius * random.range(0.45f, 0.62f)
            canvas.drawLine(centerX + cos(angle) * inner, centerY + sin(angle) * inner,
                centerX + cos(angle) * radius * 0.88f, centerY + sin(angle) * radius * 0.88f,
                spokePaint)
        }
        canvas.drawCircle(centerX, centerY, radius * random.range(0.18f, 0.30f),
            fillPaint(sepia))

        val rule = strokePaint(sepia, unit(0.002f))
        for (fraction in listOf(0.30f, 0.315f, 0.80f, 0.815f)) {
            canvas.drawLine(width * 0.12f, height * fraction, width * 0.88f, height * fraction,
                rule)
        }

        vignette(0.45f, 0.58f)
        grain(0.045f)
        frame(CoverFrame.Double, sepia)
        Typeset(
            ink = shade(sepia, 0.65f),
            authorInk = sepia,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 700)),
            titleTracking = random.range(0.08f, 0.16f),
            titleSize = random.range(0.065f, 0.085f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
        )
    }

internal fun vintageBands(cover: CoverCanvas): Typeset =
    with(cover) {
        val shell = hsv(random.range(0.09f, 0.12f), random.range(0.10f, 0.18f),
            random.range(0.92f, 0.97f))
        val band = random.pick(listOf(hsv(0.055f, 0.85f, 0.92f), hsv(0.33f, 0.55f, 0.45f),
            hsv(0.60f, 0.55f, 0.45f), hsv(0.98f, 0.65f, 0.60f), hsv(0.12f, 0.75f, 0.85f)))
        val ink = hsv(random.range(0.05f, 0.10f), 0.25f, random.range(0.12f, 0.18f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(band))

        val panelTop = height * random.range(0.26f, 0.32f)
        val panelBottom = height * random.range(0.68f, 0.74f)
        val inset = width * 0.075f
        canvas.drawRect(inset, panelTop, width - inset, panelBottom, fillPaint(shell))
        canvas.drawRect(inset, panelTop, width - inset, panelBottom,
            strokePaint(ink, unit(0.003f)))
        val hairline = strokePaint(shell, unit(0.004f))
        for (offset in listOf(0.03f, 0.045f)) {
            canvas.drawLine(inset, height * offset, width - inset, height * offset, hairline)
            canvas.drawLine(inset, height * (1f - offset), width - inset,
                height * (1f - offset), hairline)
        }

        val emblem = width * random.range(0.05f, 0.075f)
        val centerY = panelBottom - emblem * 1.6f
        canvas.drawCircle(width * 0.5f, centerY, emblem, strokePaint(ink, unit(0.0035f)))
        canvas.drawPath(starPath(width * 0.5f, centerY, emblem * 0.62f, emblem * 0.26f,
            random.pick(listOf(5, 6, 8))), fillPaint(band))

        texture(0.10f)
        grain(0.035f)
        Typeset(
            ink = ink,
            authorInk = shell,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(600, 700)),
            titleTracking = random.range(0.04f, 0.10f),
            titleLeading = 1.10f,
            titleSize = random.range(0.060f, 0.072f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            anchor = Anchor.Center,
        )
    }

internal fun adventureMap(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.09f, 0.12f), random.range(0.20f, 0.32f),
            random.range(0.86f, 0.94f))
        val ink = hsv(random.range(0.05f, 0.09f), random.range(0.55f, 0.75f),
            random.range(0.24f, 0.34f))
        val sea = hsv(random.range(0.50f, 0.56f), random.range(0.30f, 0.45f),
            random.range(0.55f, 0.70f))
        verticalGradient(paper, mix(paper, ink, 0.22f), 1.3f)
        texture(0.16f)

        val centerX = width * random.range(0.40f, 0.60f)
        val centerY = height * random.range(0.52f, 0.64f)
        val harmonics = randomHarmonics(random, 3, random.range(0.14f, 0.26f))
        val island = width * random.range(0.22f, 0.30f)
        for (step in 0 until random.between(3, 5)) {
            canvas.drawPath(
                wobblyRingPath(centerX, centerY, island * (1f + step * 0.16f), harmonics),
                strokePaint(mix(sea, paper, step / 6f), unit(0.003f)),
            )
        }
        val coast = wobblyRingPath(centerX, centerY, island, harmonics)
        canvas.drawPath(coast, fillPaint(mix(paper, ink, 0.18f)))
        canvas.drawPath(coast, strokePaint(ink, unit(0.003f)))

        val startX = width * random.range(0.10f, 0.22f)
        val startY = height * random.range(0.78f, 0.90f)
        val dash = strokePaint(ink, unit(0.004f))
        for (step in 0 until 8) {
            if (step % 2 != 0) continue
            val from = step / 8f
            val to = (step + 1) / 8f
            canvas.drawLine(
                startX + (centerX - startX) * from + sin(from * 6f) * width * 0.05f,
                startY + (centerY - startY) * from,
                startX + (centerX - startX) * to + sin(to * 6f) * width * 0.05f,
                startY + (centerY - startY) * to,
                dash,
            )
        }

        val mark = width * 0.022f
        val cross = strokePaint(hsv(0f, 0.7f, 0.55f), unit(0.006f))
        for (angle in listOf(0.7f, -0.7f)) {
            canvas.drawLine(centerX - cos(angle) * mark, centerY - sin(angle) * mark,
                centerX + cos(angle) * mark, centerY + sin(angle) * mark, cross)
        }

        val roseX = width * random.range(0.72f, 0.84f)
        val roseY = height * random.range(0.36f, 0.46f)
        val roseRadius = width * random.range(0.055f, 0.080f)
        canvas.drawCircle(roseX, roseY, roseRadius, strokePaint(ink, unit(0.0025f)))
        canvas.drawPath(starPath(roseX, roseY, roseRadius * 0.92f, roseRadius * 0.20f, 4),
            fillPaint(ink))
        canvas.drawPath(
            starPath(roseX, roseY, roseRadius * 0.55f, roseRadius * 0.14f, 4, TAU / 8f),
            fillPaint(mix(ink, paper, 0.45f)),
        )

        vignette(0.42f, 0.60f)
        grain(0.04f)
        frame(CoverFrame.Double, ink)
        Typeset(
            ink = shade(ink, 0.75f),
            authorInk = ink,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(700, 800)),
            titleTracking = random.range(0.04f, 0.10f),
            titleSize = random.range(0.085f, 0.105f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
            rule = Rule.Ornament,
        )
    }

internal fun poetryWash(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.05f, 0.14f), random.range(0.03f, 0.10f),
            random.range(0.94f, 0.99f))
        val accent = hsv(random.float(), random.range(0.25f, 0.55f), random.range(0.40f, 0.70f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
        texture(0.07f)

        val centerX = width * random.range(0.42f, 0.58f)
        val centerY = height * random.range(0.58f, 0.68f)
        when (random.index(3)) {
            0 ->
                for (layer in 0 until random.between(3, 6)) {
                    val radius = width * random.range(0.10f, 0.26f)
                    val spotX = centerX + random.range(-0.12f, 0.12f) * width
                    val spotY = centerY + random.range(-0.10f, 0.10f) * height
                    canvas.drawPath(
                        wobblyRingPath(spotX, spotY, radius, randomHarmonics(random, 3, 0.22f),
                            120),
                        fillPaint(alpha(accent, random.range(0.11f, 0.23f))),
                    )
                }
            1 -> {
                val top = height * random.range(0.44f, 0.52f)
                canvas.drawLine(centerX, height * 0.94f, centerX, top,
                    strokePaint(accent, unit(0.004f)))
                val twig = strokePaint(alpha(accent, 0.78f), unit(0.003f))
                for (index in 0 until random.between(5, 9)) {
                    val fraction = index / 9f
                    val side = if (index % 2 == 0) 1f else -1f
                    val length = width * random.range(0.06f, 0.13f) * (1f - fraction * 0.4f)
                    val anchorY = height * 0.94f - (height * 0.94f - top) * (fraction + 0.1f)
                    canvas.drawLine(centerX, anchorY, centerX + side * length,
                        anchorY - length * 0.55f, twig)
                }
            }
            else ->
                for (index in 0 until random.between(4, 7)) {
                    val radius = width * (0.10f + index * random.range(0.045f, 0.065f))
                    canvas.drawOval(RectF(centerX - radius, centerY - radius * 0.62f,
                        centerX + radius, centerY + radius * 0.62f),
                        strokePaint(alpha(accent, 0.85f), unit(0.004f)))
                }
        }

        vignette(0.14f, 0.85f)
        grain(0.025f)
        Typeset(
            ink = mix(accent, Color.rgb(25, 22, 30), 0.65f),
            authorInk = shade(accent, 0.85f),
            titleFont = CoverFont.Serif,
            titleWeight = 400,
            titleItalic = true,
            titleCase = LetterCase.Title,
            titleTracking = 0f,
            titleLeading = 1.22f,
            titleSize = random.range(0.065f, 0.082f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.24f,
        )
    }

internal fun natureContours(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.27f, 0.32f, 0.38f, 0.14f, 0.48f))
        val paper = hsv(random.range(0.09f, 0.13f), random.range(0.06f, 0.14f),
            random.range(0.92f, 0.97f))
        val deep = hsv(hue, random.range(0.50f, 0.75f), random.range(0.28f, 0.45f))
        verticalGradient(paper, mix(paper, deep, 0.18f), 1.4f)
        texture(0.08f)

        val centerX = width * random.range(0.40f, 0.60f)
        val centerY = height * random.range(0.55f, 0.68f)
        val harmonics = randomHarmonics(random, 3, random.range(0.10f, 0.20f))
        val rings = random.between(8, 14)
        for (index in 0 until rings) {
            val radius = width * (0.05f + index * random.range(0.042f, 0.058f))
            val fraction = index.toFloat() / rings
            canvas.drawPath(
                wobblyRingPath(centerX, centerY, radius, harmonics),
                strokePaint(mix(deep, paper, fraction * fraction * 0.85f), unit(0.005f)),
            )
        }
        canvas.drawPath(wobblyRingPath(centerX, centerY, width * 0.04f, harmonics),
            fillPaint(deep))

        vignette(0.22f, 0.78f)
        grain(0.03f)
        Typeset(
            ink = shade(deep, 0.7f),
            authorInk = deep,
            titleFont = CoverFont.Sans,
            titleWeight = random.pick(listOf(300, 400, 500)),
            titleTracking = random.range(0.18f, 0.30f),
            titleSize = random.range(0.055f, 0.070f),
            authorFont = CoverFont.Sans,
            authorWeight = 400,
            authorTracking = 0.22f,
        )
    }

internal fun spiritualRays(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.62f, 0.72f, 0.06f, 0.52f))
        val deep = hsv(hue, random.range(0.55f, 0.80f), random.range(0.08f, 0.14f))
        val gold = hsv(random.range(0.09f, 0.13f), random.range(0.55f, 0.75f),
            random.range(0.88f, 1f))
        verticalGradient(deep, shade(deep, 0.45f), 0.8f)
        val centerX = width * 0.5f
        val centerY = height * random.range(0.52f, 0.62f)
        radialGlow(centerX, centerY, width * random.range(0.7f, 1f), gold, 0.32f, 2.2f)

        val count = random.pick(listOf(12, 16, 24, 32))
        for (index in 0 until count) {
            val angle = TAU * index / count
            val ray = Path()
            ray.moveTo(centerX, centerY)
            ray.lineTo(centerX + cos(angle - 0.02f) * width, centerY + sin(angle - 0.02f) * width)
            ray.lineTo(centerX + cos(angle + 0.02f) * width, centerY + sin(angle + 0.02f) * width)
            ray.close()
            canvas.drawPath(ray, glowPaint(shade(gold, random.range(0.18f, 0.34f)),
                unit(0.004f)))
        }

        val base = width * random.range(0.16f, 0.24f)
        val mandala = Path()
        for (index in 0 until random.between(3, 5)) {
            mandala.addCircle(centerX, centerY, base * (1f - index * 0.18f), Path.Direction.CW)
        }
        val petals = random.pick(listOf(6, 8, 12))
        for (index in 0 until petals) {
            val angle = TAU * index / petals
            mandala.addCircle(centerX + cos(angle) * base * 0.62f,
                centerY + sin(angle) * base * 0.62f, base * 0.24f, Path.Direction.CW)
        }
        canvas.drawPath(mandala, strokePaint(gold, unit(0.003f)))

        vignette(0.62f, 0.48f)
        grain(0.035f)
        frame(CoverFrame.Double, gold)
        Typeset(
            ink = mix(gold, Color.WHITE, 0.55f),
            authorInk = gold,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(400, 600)),
            titleTracking = random.range(0.12f, 0.22f),
            titleSize = random.range(0.062f, 0.078f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
            shadow = 0.5f,
        )
    }

internal fun biographyPortrait(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.float()
        val light = hsv(hue, random.range(0.10f, 0.22f), random.range(0.90f, 0.96f))
        val dark = hsv(hue + random.range(0.35f, 0.6f), random.range(0.45f, 0.70f),
            random.range(0.20f, 0.32f))
        verticalGradient(light, mix(light, dark, 0.45f), 1.5f)

        val headRadius = width * random.range(0.16f, 0.21f)
        val headX = width * random.range(0.44f, 0.56f)
        val headY = height * random.range(0.54f, 0.62f)
        val bust = Path()
        bust.addOval(headX - headRadius * 0.86f, headY - headRadius,
            headX + headRadius * 0.86f, headY + headRadius, Path.Direction.CW)
        val shoulder = headRadius * random.range(2.1f, 2.7f)
        bust.addOval(headX - shoulder, headY + headRadius * 0.85f, headX + shoulder,
            height * 1.06f, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(bust)
        val pitch = unit(0.018f).coerceAtLeast(3f)
        val dot = fillPaint(shade(dark, 0.7f))
        var y = 0f
        while (y < height) {
            var x = 0f
            val fade = 0.55f + 0.45f * (1f - y / height)
            while (x < width) {
                canvas.drawCircle(x, y, pitch * 0.62f * fade, dot)
                x += pitch
            }
            y += pitch
        }
        canvas.restore()

        vignette(0.30f, 0.72f)
        grain(0.03f)
        Typeset(
            ink = shade(dark, 0.75f),
            authorInk = shade(dark, 0.85f),
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 700)),
            titleTracking = random.range(0.06f, 0.14f),
            titleSize = random.range(0.062f, 0.080f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
        )
    }

internal fun spiritualEnso(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.08f, 0.12f), random.range(0.06f, 0.14f),
            random.range(0.92f, 0.96f))
        val sumi = Color.rgb(24, 22, 24)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
        texture(0.16f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.60f, 0.64f)
        val radius = width * random.range(0.25f, 0.30f)
        val brush = unit(random.range(0.035f, 0.05f))
        val start = random.range(0f, TAU)
        val sweep = TAU * random.range(0.82f, 0.94f)
        val paint = fillPaint(sumi)
        for (step in 0..500) {
            val progress = step / 500f
            val angle = start + sweep * progress
            val swell = 1f - 0.75f * progress * progress
            val wobble = radius * (1f + 0.03f * sin(progress * TAU * 2f))
            paint.alpha = if (random.chance(0.15f * progress)) 90 else 235
            canvas.drawCircle(centerX + cos(angle) * wobble + random.range(-1f, 1f),
                centerY + sin(angle) * wobble + random.range(-1f, 1f),
                brush * swell * random.range(0.85f, 1f), paint)
        }

        val seal = unit(0.05f)
        val sealX = centerX + radius * 1.1f
        val sealY = centerY + radius * 1.05f
        canvas.drawRect(sealX - seal, sealY - seal, sealX + seal, sealY + seal,
            fillPaint(hsv(0.99f, 0.80f, 0.72f)))
        canvas.drawRect(sealX - seal * 0.6f, sealY - seal * 0.6f, sealX + seal * 0.6f,
            sealY + seal * 0.6f, strokePaint(paper, seal * 0.14f))

        grain(0.03f)
        Typeset(
            ink = sumi,
            authorInk = Color.rgb(80, 74, 70),
            titleFont = CoverFont.Serif,
            titleWeight = 400,
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.06f, 0.14f),
            titleSize = random.range(0.07f, 0.09f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.None,
        )
    }

internal fun technicalBlueprint(cover: CoverCanvas): Typeset =
    with(cover) {
        val blue = hsv(random.range(0.58f, 0.62f), random.range(0.70f, 0.85f),
            random.range(0.45f, 0.58f))
        val chalk = Color.rgb(236, 242, 250)
        verticalGradient(shade(blue, 1.1f), shade(blue, 0.8f), 1f)
        val fine = strokePaint(alpha(chalk, 0.10f), 1f)
        val coarse = strokePaint(alpha(chalk, 0.22f), 1f)
        val pitch = width / 24f
        for (index in 0..48) {
            val paint = if (index % 4 == 0) coarse else fine
            canvas.drawLine(index * pitch, 0f, index * pitch, height, paint)
            canvas.drawLine(0f, index * pitch, width, index * pitch, paint)
        }

        val centerX = width * random.range(0.42f, 0.58f)
        val centerY = height * random.range(0.60f, 0.64f)
        val radius = width * random.range(0.22f, 0.28f)
        val line = strokePaint(chalk, unit(0.005f))
        canvas.drawPath(gear(centerX, centerY, radius, random.between(10, 16)), line)
        canvas.drawCircle(centerX, centerY, radius * 0.55f, line)
        canvas.drawCircle(centerX, centerY, radius * 0.18f, line)
        val dashed = strokePaint(alpha(chalk, 0.6f), unit(0.002f))
        val axis = radius * 1.3f
        canvas.drawLine(centerX - axis, centerY, centerX + axis, centerY, dashed)
        canvas.drawLine(centerX, centerY - axis, centerX, centerY + axis, dashed)
        val smallX = centerX + radius * random.pick(listOf(-1.5f, 1.5f))
        val smallY = centerY + radius * 0.9f
        canvas.drawPath(gear(smallX, smallY, radius * 0.45f, 8), line)
        canvas.drawCircle(smallX, smallY, radius * 0.12f, line)

        val dimensionY = centerY - radius * 1.35f
        val left = centerX - radius
        canvas.drawLine(left, dimensionY, centerX + radius, dimensionY, dashed)
        for (side in listOf(-1f, 1f)) {
            val tip = centerX + side * radius
            canvas.drawLine(tip, dimensionY - unit(0.02f), tip, dimensionY + unit(0.02f),
                dashed)
        }

        grain(0.03f)
        frame(CoverFrame.Hairline, chalk)
        Typeset(
            ink = chalk,
            titleFont = CoverFont.Mono,
            titleWeight = 700,
            titleTracking = random.range(0.02f, 0.08f),
            titleLeading = 1.05f,
            titleSize = random.range(0.08f, 0.10f),
            authorFont = CoverFont.Mono,
            authorWeight = 400,
            authorTracking = 0.16f,
            rule = Rule.Line,
        )
    }

private fun gear(centerX: Float, centerY: Float, radius: Float, teeth: Int): Path {
    val path = Path()
    for (index in 0 until teeth * 4) {
        val angle = TAU * index / (teeth * 4)
        val reach = if (index % 4 == 1 || index % 4 == 2) radius else radius * 0.84f
        val x = centerX + cos(angle) * reach
        val y = centerY + sin(angle) * reach
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private val TYPE_PALETTES =
    listOf(
        Color.rgb(242, 236, 224) to Color.rgb(28, 26, 24),
        Color.rgb(28, 40, 64) to Color.rgb(240, 230, 208),
        Color.rgb(36, 64, 50) to Color.rgb(236, 228, 206),
        Color.rgb(176, 84, 58) to Color.rgb(250, 238, 222),
        Color.rgb(214, 170, 76) to Color.rgb(30, 26, 22),
        Color.rgb(110, 30, 44) to Color.rgb(244, 214, 214),
        Color.rgb(222, 226, 222) to Color.rgb(40, 60, 90),
    )

internal fun literaryType(cover: CoverCanvas): Typeset =
    with(cover) {
        val (paper, ink) = random.pick(TYPE_PALETTES)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
        texture(0.10f)
        val rule = fillPaint(alpha(ink, 0.85f))
        for (y in listOf(0.29f, 0.71f)) {
            val lineY = height * y
            canvas.drawRect(width * 0.2f, lineY, width * 0.8f, lineY + unit(0.004f), rule)
        }
        canvas.drawRect(width * 0.2f, height * 0.29f - unit(0.012f), width * 0.8f,
            height * 0.29f - unit(0.010f), rule)
        canvas.drawRect(width * 0.2f, height * 0.71f + unit(0.012f), width * 0.8f,
            height * 0.71f + unit(0.014f), rule)
        val mark = unit(0.02f)
        canvas.drawPath(starPath(width / 2f, height * 0.82f, mark, mark * 0.35f, 4),
            fillPaint(ink))

        grain(0.03f)
        Typeset(
            ink = ink,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(400, 600, 700)),
            titleItalic = random.chance(0.3f),
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.02f, 0.10f),
            titleLeading = 1.12f,
            titleSize = random.range(0.085f, 0.11f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.24f,
            anchor = Anchor.Center,
            rule = Rule.None,
        )
    }
