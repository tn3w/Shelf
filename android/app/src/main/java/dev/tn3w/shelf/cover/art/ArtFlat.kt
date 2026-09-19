package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.LetterCase
import dev.tn3w.shelf.cover.Rule
import dev.tn3w.shelf.cover.TAU
import dev.tn3w.shelf.cover.Typeset
import dev.tn3w.shelf.cover.alpha
import dev.tn3w.shelf.cover.displacedLine
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.starPath
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal fun technicalGeometry(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.float()
        val paper = hsv(hue, random.range(0.04f, 0.10f), random.range(0.93f, 0.98f))
        val accent = hsv(hue, random.range(0.70f, 0.95f), random.range(0.75f, 0.92f))
        val ink = hsv(hue, 0.30f, random.range(0.10f, 0.16f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

        val bandTop = height * 0.42f
        val bandBottom = height * random.range(0.80f, 0.90f)
        when (random.index(3)) {
            0 -> {
                val centerX = width * random.range(0.35f, 0.65f)
                val centerY = (bandTop + bandBottom) / 2f
                val biggest = minOf(width * 0.42f, (bandBottom - bandTop) / 2f)
                for (index in 0 until random.between(5, 9)) {
                    canvas.drawCircle(centerX, centerY, biggest * (1f - index / 9f),
                        fillPaint(if (index % 2 == 0) accent else paper))
                }
            }
            1 -> {
                val count = random.between(5, 9)
                val gap = width * 0.02f
                val barWidth = (width * 0.76f - gap * (count - 1)) / count
                for (index in 0 until count) {
                    val x = width * 0.12f + index * (barWidth + gap)
                    val barHeight = (bandBottom - bandTop) * random.range(0.25f, 1f)
                    canvas.drawRect(x, bandBottom - barHeight, x + barWidth, bandBottom,
                        fillPaint(mix(accent, ink, index / (count * 1.4f))))
                }
            }
            else -> {
                val columns = random.between(4, 7)
                val rows = random.between(4, 7)
                val cellWidth = width * 0.76f / columns
                val cellHeight = (bandBottom - bandTop) / rows
                for (column in 0 until columns) {
                    for (row in 0 until rows) {
                        val x = width * 0.12f + cellWidth * column
                        val y = bandTop + cellHeight * row
                        val box = RectF(x, y, x + cellWidth * 0.86f, y + cellHeight * 0.86f)
                        val choice = random.float()
                        when {
                            choice < 0.35f -> canvas.drawOval(box, fillPaint(accent))
                            choice < 0.6f -> canvas.drawRect(box, fillPaint(ink))
                            choice < 0.8f ->
                                canvas.drawArc(box, random.pick(listOf(0f, 90f, 180f, 270f)),
                                    90f, true, fillPaint(accent))
                        }
                    }
                }
            }
        }

        canvas.drawRect(0f, 0f, width, height * random.range(0.035f, 0.06f), fillPaint(accent))
        grain(0.02f)
        Typeset(
            ink = ink,
            authorInk = shade(accent, 0.75f),
            titleFont = random.pick(listOf(CoverFont.Sans, CoverFont.Condensed)),
            titleWeight = random.pick(listOf(700, 900)),
            titleTracking = random.range(-0.02f, 0.01f),
            titleLeading = 0.95f,
            titleSize = random.range(0.105f, 0.13f),
            authorFont = CoverFont.Mono,
            authorWeight = 500,
            authorTracking = 0.10f,
            rule = Rule.None,
        )
    }

internal fun businessAscent(cover: CoverCanvas): Typeset =
    with(cover) {
        val accent = hsv(random.float(), random.range(0.65f, 0.90f), random.range(0.70f, 0.90f))
        val dark = hsv(random.range(0.58f, 0.68f), random.range(0.35f, 0.55f),
            random.range(0.10f, 0.16f))
        verticalGradient(dark, mix(dark, accent, 0.35f), 1.6f)

        when (random.index(3)) {
            0 -> {
                val count = random.between(5, 8)
                val gap = width * 0.025f
                val barWidth = (width * 0.7f - gap * (count - 1)) / count
                val base = height * 0.86f
                for (index in 0 until count) {
                    val x = width * 0.15f + index * (barWidth + gap)
                    val fraction = index / (count - 1f)
                    val barHeight = height * (0.06f + 0.32f * fraction * fraction)
                    canvas.drawRect(x, base - barHeight, x + barWidth, base,
                        fillPaint(mix(accent, Color.WHITE, index / (count * 2.2f))))
                }
            }
            1 -> {
                for (pair in listOf(random.range(0.44f, 0.60f) to accent,
                    random.range(0.66f, 0.80f) to shade(accent, 0.6f))) {
                    val wedge = Path()
                    wedge.moveTo(0f, height)
                    wedge.lineTo(width, height * pair.first)
                    wedge.lineTo(width, height)
                    wedge.close()
                    canvas.drawPath(wedge, fillPaint(pair.second))
                }
            }
            else -> {
                val columns = random.between(3, 5)
                val cell = width * 0.72f / columns
                val top = height * 0.48f
                for (column in 0 until columns) {
                    for (row in 0 until 3) {
                        val left = width * 0.14f + column * cell
                        val box = RectF(left, top + row * cell, left + cell * 0.82f,
                            top + (row + 0.82f) * cell)
                        if (random.chance(0.55f)) {
                            canvas.drawRect(box, fillPaint(mix(accent, Color.WHITE,
                                random.range(0f, 0.40f))))
                        } else {
                            canvas.drawRect(box, strokePaint(mix(accent, Color.WHITE, 0.25f),
                                unit(0.005f)))
                        }
                    }
                }
            }
        }

        vignette(0.35f, 0.70f)
        grain(0.025f)
        Typeset(
            ink = Color.rgb(252, 252, 252),
            authorInk = mix(accent, Color.WHITE, 0.5f),
            titleFont = CoverFont.Sans,
            titleWeight = 900,
            titleTracking = -0.015f,
            titleLeading = 0.96f,
            titleSize = random.range(0.115f, 0.145f),
            authorFont = CoverFont.Sans,
            authorWeight = 500,
            authorTracking = 0.20f,
            rule = Rule.Bar,
        )
    }

internal fun literaryShape(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.float()
        val paper = hsv(random.range(0.08f, 0.13f), random.range(0.05f, 0.14f),
            random.range(0.90f, 0.97f))
        val accent = hsv(hue, random.range(0.45f, 0.80f), random.range(0.45f, 0.80f))
        val ink = hsv(hue, 0.25f, 0.14f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
        texture(0.09f)

        when (random.index(4)) {
            0 -> {
                val radius = width * random.range(0.34f, 0.46f)
                val centerY = height * random.range(0.62f, 0.72f)
                canvas.drawArc(RectF(width * 0.5f - radius, centerY - radius,
                    width * 0.5f + radius, centerY + radius), 180f, 180f, true,
                    fillPaint(accent))
            }
            1 -> {
                val horizon = height * random.range(0.60f, 0.72f)
                canvas.drawRect(0f, horizon, width, height, fillPaint(accent))
                val sunRadius = width * random.range(0.10f, 0.16f)
                canvas.drawCircle(width * 0.5f,
                    horizon - sunRadius * random.range(0.1f, 0.8f), sunRadius,
                    fillPaint(mix(accent, ink, 0.35f)))
            }
            2 -> {
                val radius = width * random.range(0.22f, 0.30f)
                canvas.drawCircle(width * random.range(0.4f, 0.6f),
                    height * random.range(0.58f, 0.68f), radius, fillPaint(accent))
            }
            else -> {
                val amplitude = height * random.range(0.012f, 0.022f)
                val spacing = height * random.range(0.045f, 0.06f)
                val frequency = random.range(1f, 2f)
                val count = random.between(5, 8)
                val top = height * 0.94f - count * spacing
                for (index in 0 until count) {
                    val wave = Path()
                    val y = top + index * spacing
                    var x = 0f
                    while (x <= width) {
                        val offset = sin(x / width * TAU * frequency + index * 0.7f) * amplitude
                        if (x == 0f) wave.moveTo(x, y + offset) else wave.lineTo(x, y + offset)
                        x += width / 160f
                    }
                    canvas.drawPath(wave, strokePaint(
                        mix(accent, paper, index / (count * 1.8f)), unit(0.006f)))
                }
            }
        }

        vignette(0.18f, 0.80f)
        grain(0.03f)
        Typeset(
            ink = ink,
            authorInk = shade(accent, 0.7f),
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(400, 600)),
            titleItalic = random.chance(0.35f),
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.06f, 0.18f),
            titleSize = random.range(0.060f, 0.080f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.18f,
        )
    }

internal fun childrenMeadow(cover: CoverCanvas): Typeset =
    with(cover) {
        val sky = hsv(random.range(0.50f, 0.60f), random.range(0.35f, 0.55f),
            random.range(0.95f, 1f))
        val grass = hsv(random.range(0.25f, 0.34f), random.range(0.55f, 0.75f),
            random.range(0.70f, 0.85f))
        val sun = hsv(random.range(0.09f, 0.14f), random.range(0.70f, 0.90f), 1f)
        verticalGradient(mix(sky, Color.WHITE, 0.35f), sky, 1.2f)

        val sunX = width * random.range(0.18f, 0.82f)
        val sunY = height * random.range(0.30f, 0.42f)
        val sunRadius = width * random.range(0.09f, 0.14f)
        val ray = strokePaint(sun, unit(0.008f))
        for (index in 0 until 12) {
            val angle = TAU * index / 12 + random.range(0f, 0.4f)
            canvas.drawLine(sunX + cos(angle) * sunRadius * 1.25f,
                sunY + sin(angle) * sunRadius * 1.25f, sunX + cos(angle) * sunRadius * 1.75f,
                sunY + sin(angle) * sunRadius * 1.75f, ray)
        }
        canvas.drawCircle(sunX, sunY, sunRadius, fillPaint(sun))

        val white = fillPaint(Color.WHITE)
        for (index in 0 until random.between(2, 4)) {
            val cloudX = random.range(0.1f, 0.9f) * width
            val cloudY = random.range(0.36f, 0.56f) * height
            val puff = width * random.range(0.05f, 0.085f)
            for (offset in listOf(-1f, -0.35f, 0.35f, 1f)) {
                canvas.drawCircle(cloudX + offset * puff, cloudY,
                    puff * if (abs(offset) > 0.6f) 0.62f else 1f, white)
            }
        }

        val hills = random.between(2, 3)
        for (index in 0 until hills) {
            val top = height * (0.66f + index * 0.07f)
            val radius = width * random.range(0.45f, 0.85f)
            canvas.drawCircle(width * random.range(0.1f, 0.9f), top + radius * 0.7f, radius,
                fillPaint(mix(grass, Color.WHITE, 0.30f - index * 0.14f)))
        }
        canvas.drawRect(0f, height * 0.86f, width, height, fillPaint(shade(grass, 0.85f)))

        for (index in 0 until random.between(5, 10)) {
            canvas.drawCircle(random.range(0f, width), random.range(0.86f, 1f) * height,
                unit(0.012f),
                fillPaint(random.pick(listOf(Color.WHITE, sun, hsv(0.95f, 0.45f, 1f)))))
        }

        Typeset(
            ink = Color.WHITE,
            authorInk = shade(grass, 0.45f),
            titleFont = CoverFont.Casual,
            titleCase = LetterCase.Title,
            titleTracking = 0f,
            titleLeading = 1.05f,
            titleSize = random.range(0.105f, 0.135f),
            authorFont = CoverFont.Casual,
            authorCase = LetterCase.Title,
            authorTracking = 0.06f,
            authorSize = 0.030f,
            rule = Rule.None,
            shadow = 0.7f,
        )
    }

internal fun humorConfetti(cover: CoverCanvas): Typeset =
    with(cover) {
        val base = hsv(random.float(), random.range(0.35f, 0.60f), random.range(0.92f, 1f))
        val ink = hsv(random.range(0.60f, 0.72f), random.range(0.40f, 0.60f),
            random.range(0.14f, 0.22f))
        val pop = hsv(random.float() + 0.5f, random.range(0.70f, 0.90f),
            random.range(0.85f, 1f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(base))

        for (index in 0 until random.between(24, 40)) {
            val x = random.range(width * 0.06f, width * 0.94f)
            val y = random.range(height * 0.30f, height * 0.96f)
            val scale = width * random.range(0.020f, 0.060f)
            val tone = random.pick(listOf(pop, ink, Color.WHITE))
            when (random.index(4)) {
                0 -> canvas.drawCircle(x, y, scale, fillPaint(tone))
                1 ->
                    canvas.drawPath(starPath(x, y, scale, scale * 0.45f,
                        random.pick(listOf(4, 5, 6)), random.range(0f, 2f)), fillPaint(tone))
                2 -> {
                    val zigzag = Path()
                    for (step in 0 until 6) {
                        val stepX = x + step * scale * 0.5f
                        val stepY = y + if (step % 2 == 0) -scale * 0.5f else scale * 0.5f
                        if (step == 0) zigzag.moveTo(stepX, stepY) else zigzag.lineTo(stepX,
                            stepY)
                    }
                    canvas.drawPath(zigzag, strokePaint(tone, unit(0.007f)))
                }
                else ->
                    canvas.drawRect(x - scale, y - scale * 0.4f, x + scale, y + scale * 0.4f,
                        fillPaint(tone))
            }
        }

        grain(0.02f)
        Typeset(
            ink = ink,
            authorInk = shade(ink, 1.4f),
            titleFont = CoverFont.Casual,
            titleTracking = 0.02f,
            titleLeading = 1.02f,
            titleSize = random.range(0.105f, 0.130f),
            authorFont = CoverFont.Casual,
            authorCase = LetterCase.Title,
            authorTracking = 0.10f,
            authorSize = 0.030f,
            rule = Rule.None,
        )
    }

internal fun travelPoster(cover: CoverCanvas): Typeset =
    with(cover) {
        val bands =
            listOf(
                hsv(random.range(0.95f, 1.02f), random.range(0.45f, 0.65f),
                    random.range(0.92f, 1f)),
                hsv(random.range(0.03f, 0.07f), random.range(0.60f, 0.80f),
                    random.range(0.92f, 1f)),
                hsv(random.range(0.08f, 0.12f), random.range(0.70f, 0.90f),
                    random.range(0.88f, 0.98f)),
                hsv(random.range(0.11f, 0.14f), random.range(0.55f, 0.75f),
                    random.range(0.80f, 0.92f)),
            )
        val ink = hsv(random.range(0.60f, 0.72f), random.range(0.45f, 0.65f),
            random.range(0.18f, 0.28f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(bands[0]))

        val top = height * random.range(0.26f, 0.34f)
        val horizon = height * random.range(0.66f, 0.74f)
        for (index in bands.indices) {
            canvas.drawRect(0f, top + (horizon - top) * index / bands.size, width,
                top + (horizon - top) * (index + 1) / bands.size, fillPaint(bands[index]))
        }
        canvas.drawRect(0f, 0f, width, top, fillPaint(mix(bands[0], Color.WHITE, 0.45f)))

        val sunRadius = width * random.range(0.16f, 0.22f)
        canvas.drawCircle(width * 0.5f, horizon - sunRadius * random.range(0.35f, 0.85f),
            sunRadius, fillPaint(mix(bands[3], Color.WHITE, 0.35f)))

        val profile = displacedLine(random, 0f, 0f, random.range(0.05f, 0.12f))
        val step = width / (profile.size - 1)
        val peaks = Path()
        peaks.moveTo(0f, height)
        for (index in profile.indices) {
            peaks.lineTo(index * step,
                horizon - abs(profile[index]) * height * 1.4f - height * 0.02f)
        }
        peaks.lineTo(width, height)
        peaks.close()
        canvas.drawPath(peaks, fillPaint(ink))
        canvas.drawRect(0f, horizon + height * 0.16f, width, height,
            fillPaint(shade(ink, 0.78f)))

        frame(CoverFrame.Hairline, mix(ink, Color.WHITE, 0.6f))
        Typeset(
            ink = ink,
            authorInk = mix(ink, Color.WHITE, 0.85f),
            titleFont = CoverFont.Condensed,
            titleWeight = random.pick(listOf(700, 900)),
            titleTracking = random.range(0.02f, 0.08f),
            titleLeading = 1f,
            titleSize = random.range(0.095f, 0.115f),
            authorFont = CoverFont.Sans,
            authorWeight = 500,
            authorTracking = 0.28f,
            rule = Rule.Bar,
        )
    }

internal fun romanceBotanical(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.96f, 0.02f, 0.06f, 0.92f, 0.10f))
        val blush = hsv(hue, random.range(0.18f, 0.30f), random.range(0.94f, 0.99f))
        val deep = hsv(hue - 0.03f, random.range(0.35f, 0.50f), random.range(0.62f, 0.80f))
        val gold = hsv(random.range(0.09f, 0.12f), 0.45f, 0.80f)
        verticalGradient(blush, deep, random.range(1.4f, 2.2f))
        texture(0.06f)

        val stems = random.between(3, 6)
        for (index in 0 until stems) {
            val baseX = width * (index + 0.5f) / stems + random.range(-0.05f, 0.05f) * width
            val baseY = height * random.range(0.98f, 1.04f)
            val stemHeight = height * random.range(0.28f, 0.48f)
            val curve = random.range(-0.09f, 0.09f) * width
            val line = mix(deep, Color.rgb(48, 26, 34), 0.70f)
            val stroke = strokePaint(line, unit(0.004f))
            val stem = Path()
            val points = List(21) {
                val fraction = it / 20f
                (baseX + curve * fraction * fraction) to (baseY - stemHeight * fraction)
            }
            stem.moveTo(points[0].first, points[0].second)
            for (point in points.drop(1)) stem.lineTo(point.first, point.second)
            canvas.drawPath(stem, stroke)

            for (leaf in 0 until random.between(4, 9)) {
                val anchor = points[random.between(4, 19)]
                val side = random.sign()
                val leafWidth = width * random.range(0.02f, 0.055f)
                val leafHeight = leafWidth * random.range(0.35f, 0.6f)
                val left = minOf(anchor.first, anchor.first + side * leafWidth)
                val right = maxOf(anchor.first, anchor.first + side * leafWidth)
                canvas.drawOval(RectF(left, anchor.second - leafHeight, right,
                    anchor.second + leafHeight), fillPaint(line))
            }
            val top = points.last()
            val petal = width * random.range(0.020f, 0.036f)
            val petalPaint = fillPaint(mix(deep, gold, 0.45f))
            for (turn in 0 until 8) {
                val angle = TAU * turn / 8f
                canvas.drawCircle(top.first + cos(angle) * petal,
                    top.second + sin(angle) * petal, petal * 0.62f, petalPaint)
            }
            canvas.drawCircle(top.first, top.second, petal * 0.45f, fillPaint(line))
        }

        radialGlow(width * 0.5f, height * 0.3f, width * 0.9f, Color.rgb(255, 250, 245), 0.18f, 2f)
        vignette(0.28f, 0.72f)
        grain(0.025f)
        frame(CoverFrame.Hairline, gold)
        Typeset(
            ink = mix(deep, Color.rgb(40, 20, 30), 0.72f),
            authorInk = mix(deep, Color.rgb(30, 15, 25), 0.55f),
            titleFont = CoverFont.Script,
            titleWeight = random.pick(listOf(500, 700)),
            titleCase = LetterCase.Title,
            titleTracking = 0f,
            titleLeading = 0.96f,
            titleSize = random.range(0.13f, 0.16f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
        )
    }

internal fun romanceDeco(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.95f, 0.99f, 0.04f, 0.88f, 0.08f))
        val blush = hsv(hue, random.range(0.20f, 0.34f), random.range(0.93f, 0.99f))
        val deep = hsv(hue - 0.02f, random.range(0.45f, 0.65f), random.range(0.45f, 0.62f))
        val gold = hsv(random.range(0.08f, 0.11f), random.range(0.60f, 0.80f),
            random.range(0.55f, 0.70f))
        verticalGradient(blush, mix(blush, deep, 0.75f), 1.5f)
        texture(0.06f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.60f, 0.68f)
        val archWidth = width * random.range(0.26f, 0.34f)
        val archHeight = height * random.range(0.26f, 0.34f)
        val stroke = unit(0.0065f)
        for (index in 0 until random.between(2, 4)) {
            val spread = archWidth * (1f + index * 0.13f)
            val rise = archHeight * (1f + index * 0.10f)
            val paint = strokePaint(alpha(gold, 0.86f), stroke)
            canvas.drawArc(RectF(centerX - spread, centerY - rise, centerX + spread,
                centerY + rise * 0.6f), 180f, 180f, false, paint)
            canvas.drawLine(centerX - spread, centerY - rise * 0.2f, centerX - spread,
                centerY + rise * 0.75f, paint)
            canvas.drawLine(centerX + spread, centerY - rise * 0.2f, centerX + spread,
                centerY + rise * 0.75f, paint)
        }

        val rays = random.pick(listOf(7, 9, 11))
        val rayPaint = strokePaint(alpha(gold, 0.55f), stroke * 0.5f)
        for (index in 0 until rays) {
            val angle = (TAU / 2f) + (TAU / 2f) * (index + 0.5f) / rays
            canvas.drawLine(centerX, centerY, centerX + cos(angle) * archWidth * 0.88f,
                centerY + sin(angle) * archHeight * 0.88f, rayPaint)
        }

        val roseRadius = width * random.range(0.070f, 0.095f)
        for (index in 0 until random.between(3, 5)) {
            canvas.drawCircle(centerX, centerY, roseRadius * (1f - index * 0.2f),
                strokePaint(alpha(mix(deep, gold, 0.4f), 0.90f), stroke))
        }
        canvas.drawCircle(centerX, centerY, roseRadius * 0.18f, fillPaint(deep))

        radialGlow(width * 0.5f, height * 0.32f, width * 0.9f, Color.rgb(255, 250, 246), 0.16f,
            2f)
        vignette(0.30f, 0.70f)
        grain(0.025f)
        frame(CoverFrame.Hairline, gold)
        Typeset(
            ink = mix(deep, Color.rgb(45, 22, 32), 0.60f),
            authorInk = mix(deep, Color.rgb(30, 15, 25), 0.45f),
            titleFont = random.pick(listOf(CoverFont.Script, CoverFont.Serif)),
            titleWeight = random.pick(listOf(500, 700)),
            titleItalic = true,
            titleCase = LetterCase.Title,
            titleTracking = 0f,
            titleLeading = 1.02f,
            titleSize = random.range(0.105f, 0.140f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
        )
    }
