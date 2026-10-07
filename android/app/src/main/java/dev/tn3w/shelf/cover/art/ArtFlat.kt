package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal fun technicalGeometry(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.float()
    val paper = hsv(hue, random.range(0.04f, 0.10f), random.range(0.93f, 0.98f))
    val accent = hsv(hue, random.range(0.70f, 0.95f), random.range(0.75f, 0.92f))
    val ink = hsv(hue, 0.30f, random.range(0.10f, 0.16f))
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

    val bandTop = height * 0.42f
    val bandBottom = height * random.range(0.80f, 0.90f)
    when (random.index(3)) {
        0 -> {
            val centerX = width * 0.5f
            val centerY = (bandTop + bandBottom) / 2f
            val biggest = minOf(width * 0.42f, (bandBottom - bandTop) / 2f)
            for (index in 0 until 4) {
                canvas.drawCircle(
                    centerX,
                    centerY,
                    biggest * (1f - index / 4f),
                    fillPaint(if (index % 2 == 0) accent else paper),
                )
            }
        }

        1 -> {
            val count = random.between(4, 6)
            val gap = width * 0.03f
            val barWidth = (width * 0.76f - gap * (count - 1)) / count
            for (index in 0 until count) {
                val x = width * 0.12f + index * (barWidth + gap)
                val barHeight = (bandBottom - bandTop) * random.range(0.25f, 1f)
                canvas.drawRect(
                    x,
                    bandBottom - barHeight,
                    x + barWidth,
                    bandBottom,
                    fillPaint(accent),
                )
            }
        }

        else -> {
            val columns = 3
            val rows = 3
            val cellWidth = width * 0.76f / columns
            val cellHeight = (bandBottom - bandTop) / rows
            for (column in 0 until columns) {
                for (row in 0 until rows) {
                    val x = width * 0.12f + cellWidth * column
                    val y = bandTop + cellHeight * row
                    val size = minOf(cellWidth, cellHeight) * 0.8f
                    val box = RectF(x, y, x + size, y + size)
                    if (random.chance(0.5f)) {
                        canvas.drawOval(box, fillPaint(accent))
                    } else {
                        canvas.drawArc(
                            box,
                            90f * random.index(4),
                            180f,
                            true,
                            fillPaint(ink),
                        )
                    }
                }
            }
        }
    }

    Typeset(
        ink = ink,
        authorInk = shade(accent, 0.75f),
        font = random.pick(listOf(CoverFont.Sans, CoverFont.Condensed)),
        upper = true,
    )
}

internal fun businessAscent(cover: CoverCanvas): Typeset = with(cover) {
    val accent =
        hsv(random.float(), random.range(0.65f, 0.90f), random.range(0.70f, 0.90f))
    val dark = hsv(
        random.range(0.58f, 0.68f),
        random.range(0.35f, 0.55f),
        random.range(0.10f, 0.16f),
    )
    canvas.drawColor(dark)

    when (random.index(3)) {
        0 -> {
            val count = random.between(4, 6)
            val gap = width * 0.03f
            val barWidth = (width * 0.7f - gap * (count - 1)) / count
            val base = height * 0.86f
            for (index in 0 until count) {
                val x = width * 0.15f + index * (barWidth + gap)
                val fraction = index / (count - 1f)
                val barHeight = height * (0.06f + 0.32f * fraction * fraction)
                canvas.drawRect(
                    x,
                    base - barHeight,
                    x + barWidth,
                    base,
                    fillPaint(accent),
                )
            }
        }

        1 -> {
            for (pair in listOf(
                random.range(0.44f, 0.60f) to accent,
                random.range(0.66f, 0.80f) to shade(accent, 0.6f),
            )) {
                val wedge = Path()
                wedge.moveTo(0f, height)
                wedge.lineTo(width, height * pair.first)
                wedge.lineTo(width, height)
                wedge.close()
                canvas.drawPath(wedge, fillPaint(pair.second))
            }
        }

        else -> {
            val columns = 3
            val cell = width * 0.72f / columns
            val top = height * 0.48f
            for (column in 0 until columns) {
                for (row in 0 until 3) {
                    val left = width * 0.14f + column * cell
                    val box = RectF(
                        left,
                        top + row * cell,
                        left + cell * 0.82f,
                        top + (row + 0.82f) * cell,
                    )
                    if (column + row >= 2) canvas.drawRect(box, fillPaint(accent))
                }
            }
        }
    }

    Typeset(
        ink = Color.rgb(252, 252, 252),
        authorInk = mix(accent, Color.WHITE, 0.5f),
        font = CoverFont.Sans,
        upper = true,
    )
}

internal fun literaryShape(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.float()
    val paper = hsv(
        random.range(0.08f, 0.13f),
        random.range(0.05f, 0.14f),
        random.range(0.90f, 0.97f),
    )
    val accent = hsv(hue, random.range(0.45f, 0.80f), random.range(0.45f, 0.80f))
    val ink = hsv(hue, 0.25f, 0.14f)
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

    when (random.index(4)) {
        0 -> {
            val radius = width * random.range(0.34f, 0.46f)
            val centerY = height * random.range(0.62f, 0.72f)
            canvas.drawArc(
                RectF(
                    width * 0.5f - radius,
                    centerY - radius,
                    width * 0.5f + radius,
                    centerY + radius,
                ),
                180f,
                180f,
                true,
                fillPaint(accent),
            )
        }

        1 -> {
            val horizon = height * random.range(0.60f, 0.72f)
            canvas.drawRect(0f, horizon, width, height, fillPaint(accent))
            val sunRadius = width * random.range(0.10f, 0.16f)
            canvas.drawCircle(
                width * 0.5f,
                horizon - sunRadius * random.range(0.1f, 0.8f),
                sunRadius,
                fillPaint(mix(accent, ink, 0.35f)),
            )
        }

        2 -> {
            val radius = width * random.range(0.22f, 0.30f)
            canvas.drawCircle(
                width * random.range(0.4f, 0.6f),
                height * random.range(0.58f, 0.68f),
                radius,
                fillPaint(accent),
            )
        }

        else -> {
            val amplitude = height * random.range(0.012f, 0.022f)
            val spacing = height * random.range(0.045f, 0.06f)
            val frequency = random.range(1f, 2f)
            val count = 4
            val top = height * 0.90f - count * spacing
            for (index in 0 until count) {
                val wave = Path()
                val y = top + index * spacing
                var x = 0f
                while (x <= width) {
                    val offset =
                        sin(x / width * TAU * frequency + index * 0.7f) * amplitude
                    if (x ==
                        0f
                    ) {
                        wave.moveTo(x, y + offset)
                    } else {
                        wave.lineTo(x, y + offset)
                    }
                    x += width / 160f
                }
                canvas.drawPath(
                    wave,
                    strokePaint(accent, unit(0.008f)),
                )
            }
        }
    }

    Typeset(
        ink = ink,
        authorInk = shade(accent, 0.7f),
        italic = random.chance(0.35f),
        upper = random.chance(0.5f),
    )
}

internal fun childrenMeadow(cover: CoverCanvas): Typeset = with(cover) {
    val sky = hsv(
        random.range(0.50f, 0.60f), random.range(0.35f, 0.55f), random.range(0.95f, 1f),
    )
    val grass = hsv(
        random.range(0.25f, 0.34f),
        random.range(0.55f, 0.75f),
        random.range(0.70f, 0.85f),
    )
    val sun = hsv(random.range(0.09f, 0.14f), random.range(0.70f, 0.90f), 1f)
    canvas.drawColor(sky)

    canvas.drawCircle(
        width * random.range(0.30f, 0.70f),
        height * random.range(0.50f, 0.56f),
        width * random.range(0.12f, 0.15f),
        fillPaint(sun),
    )

    for (index in 0 until 2) {
        val top = height * (0.66f + index * 0.07f)
        val radius = width * random.range(0.45f, 0.85f)
        canvas.drawCircle(
            width * random.range(0.1f, 0.9f),
            top + radius * 0.7f,
            radius,
            fillPaint(mix(grass, Color.WHITE, 0.30f - index * 0.14f)),
        )
    }
    canvas.drawRect(0f, height * 0.86f, width, height, fillPaint(shade(grass, 0.85f)))

    Typeset(
        ink = Color.WHITE,
        authorInk = shade(grass, 0.45f),
        font = CoverFont.Sans,
    )
}

internal fun humorConfetti(cover: CoverCanvas): Typeset = with(cover) {
    val base = hsv(random.float(), random.range(0.35f, 0.60f), random.range(0.92f, 1f))
    val ink = hsv(
        random.range(0.60f, 0.72f),
        random.range(0.40f, 0.60f),
        random.range(0.14f, 0.22f),
    )
    val pop = hsv(
        random.float() + 0.5f, random.range(0.70f, 0.90f), random.range(0.85f, 1f),
    )
    canvas.drawRect(0f, 0f, width, height, fillPaint(base))

    for (index in 0 until 6) {
        val x = width * (0.3f + index % 2 * 0.4f + random.range(-0.06f, 0.06f))
        val y = height * (0.44f + index / 2 * 0.15f + random.range(-0.03f, 0.03f))
        val scale = width * random.range(0.06f, 0.08f)
        val paint = fillPaint(if (index % 3 == 0) ink else pop)
        canvas.save()
        canvas.rotate(random.range(-30f, 30f), x, y)
        when (random.index(3)) {
            0 -> canvas.drawCircle(x, y, scale, paint)

            1 -> canvas.drawRect(
                x - scale,
                y - scale * 0.5f,
                x + scale,
                y + scale * 0.5f,
                paint,
            )

            else -> canvas.drawPath(starPath(x, y, scale, scale * 0.45f, 5), paint)
        }
        canvas.restore()
    }

    Typeset(
        ink = ink,
        authorInk = shade(ink, 1.4f),
        font = CoverFont.Sans,
        upper = true,
    )
}

internal fun travelPoster(cover: CoverCanvas): Typeset = with(cover) {
    val bands = listOf(
        hsv(
            random.range(0.95f, 1.02f),
            random.range(0.45f, 0.65f),
            random.range(0.92f, 1f),
        ),
        hsv(
            random.range(0.03f, 0.07f),
            random.range(0.60f, 0.80f),
            random.range(0.92f, 1f),
        ),
        hsv(
            random.range(0.08f, 0.12f),
            random.range(0.70f, 0.90f),
            random.range(0.88f, 0.98f),
        ),
        hsv(
            random.range(0.11f, 0.14f),
            random.range(0.55f, 0.75f),
            random.range(0.80f, 0.92f),
        ),
    )
    val ink = hsv(
        random.range(0.60f, 0.72f),
        random.range(0.45f, 0.65f),
        random.range(0.18f, 0.28f),
    )
    canvas.drawRect(0f, 0f, width, height, fillPaint(bands[0]))

    val top = height * random.range(0.26f, 0.34f)
    val horizon = height * random.range(0.66f, 0.74f)
    for (index in bands.indices) {
        canvas.drawRect(
            0f,
            top + (horizon - top) * index / bands.size,
            width,
            top + (horizon - top) * (index + 1) / bands.size,
            fillPaint(bands[index]),
        )
    }
    canvas.drawRect(0f, 0f, width, top, fillPaint(mix(bands[0], Color.WHITE, 0.45f)))

    val sunRadius = width * random.range(0.16f, 0.22f)
    canvas.drawCircle(
        width * 0.5f,
        horizon - sunRadius * random.range(0.35f, 0.85f),
        sunRadius,
        fillPaint(mix(bands[3], Color.WHITE, 0.35f)),
    )

    val profile = displacedLine(random, 0f, 0f, random.range(0.05f, 0.12f))
    val step = width / (profile.size - 1)
    val peaks = Path()
    peaks.moveTo(0f, height)
    for (index in profile.indices) {
        peaks.lineTo(
            index * step, horizon - abs(profile[index]) * height * 1.4f - height * 0.02f,
        )
    }
    peaks.lineTo(width, height)
    peaks.close()
    canvas.drawPath(peaks, fillPaint(ink))
    canvas.drawRect(
        0f, horizon + height * 0.16f, width, height, fillPaint(shade(ink, 0.78f)),
    )

    Typeset(
        ink = ink,
        authorInk = mix(ink, Color.WHITE, 0.85f),
        font = CoverFont.Condensed,
        upper = true,
    )
}

internal fun romanceBotanical(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.96f, 0.02f, 0.06f, 0.92f, 0.10f))
    val blush = hsv(hue, random.range(0.18f, 0.30f), random.range(0.94f, 0.99f))
    val deep = hsv(hue - 0.03f, random.range(0.35f, 0.50f), random.range(0.62f, 0.80f))
    val gold = hsv(random.range(0.09f, 0.12f), 0.45f, 0.80f)
    canvas.drawColor(blush)

    val stems = 3
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

        repeat(3) {
            val anchor = points[random.between(4, 19)]
            val side = random.sign()
            val leafWidth = width * random.range(0.04f, 0.06f)
            val leafHeight = leafWidth * random.range(0.35f, 0.6f)
            val left = minOf(anchor.first, anchor.first + side * leafWidth)
            val right = maxOf(anchor.first, anchor.first + side * leafWidth)
            canvas.drawOval(
                RectF(
                    left, anchor.second - leafHeight, right, anchor.second + leafHeight,
                ),
                fillPaint(line),
            )
        }
        val top = points.last()
        val petal = width * random.range(0.020f, 0.036f)
        val petalPaint = fillPaint(mix(deep, gold, 0.45f))
        for (turn in 0 until 8) {
            val angle = TAU * turn / 8f
            canvas.drawCircle(
                top.first + cos(angle) * petal,
                top.second + sin(angle) * petal,
                petal * 0.62f,
                petalPaint,
            )
        }
        canvas.drawCircle(top.first, top.second, petal * 0.45f, fillPaint(line))
    }

    Typeset(
        ink = mix(deep, Color.rgb(40, 20, 30), 0.72f),
        authorInk = mix(deep, Color.rgb(30, 15, 25), 0.55f),
        italic = true,
    )
}

internal fun romanceDeco(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.95f, 0.99f, 0.04f, 0.88f, 0.08f))
    val blush = hsv(hue, random.range(0.20f, 0.34f), random.range(0.93f, 0.99f))
    val deep = hsv(hue - 0.02f, random.range(0.45f, 0.65f), random.range(0.45f, 0.62f))
    val gold = hsv(
        random.range(0.08f, 0.11f),
        random.range(0.60f, 0.80f),
        random.range(0.55f, 0.70f),
    )
    canvas.drawColor(blush)

    val centerX = width * 0.5f
    val centerY = height * random.range(0.62f, 0.66f)
    val archWidth = width * random.range(0.26f, 0.32f)
    val archHeight = height * random.range(0.24f, 0.30f)
    val stroke = unit(0.008f)
    for (index in 0 until 2) {
        val spread = archWidth * (1f + index * 0.15f)
        val rise = archHeight * (1f + index * 0.12f)
        val paint = strokePaint(gold, stroke)
        canvas.drawArc(
            RectF(
                centerX - spread, centerY - rise, centerX + spread, centerY + rise * 0.6f,
            ),
            180f,
            180f,
            false,
            paint,
        )
        canvas.drawLine(
            centerX - spread,
            centerY - rise * 0.2f,
            centerX - spread,
            centerY + rise * 0.75f,
            paint,
        )
        canvas.drawLine(
            centerX + spread,
            centerY - rise * 0.2f,
            centerX + spread,
            centerY + rise * 0.75f,
            paint,
        )
    }

    canvas.drawCircle(
        centerX,
        centerY,
        width * random.range(0.08f, 0.11f),
        fillPaint(deep),
    )

    Typeset(
        ink = mix(deep, Color.rgb(45, 22, 32), 0.60f),
        authorInk = mix(deep, Color.rgb(30, 15, 25), 0.45f),
        italic = true,
    )
}

internal fun romanceBloom(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.96f, 0.99f, 0.03f, 0.92f, 0.08f))
    val blush = hsv(hue, random.range(0.10f, 0.20f), random.range(0.95f, 0.99f))
    val deep = hsv(hue, random.range(0.55f, 0.75f), random.range(0.60f, 0.78f))
    val leafTone = hsv(random.range(0.30f, 0.40f), 0.40f, random.range(0.40f, 0.55f))
    canvas.drawColor(blush)

    val centerX = width * 0.5f
    val centerY = height * random.range(0.57f, 0.61f)
    val radius = width * random.range(0.24f, 0.28f)
    for (side in listOf(-1f, 1f)) {
        canvas.save()
        canvas.rotate(side * random.range(25f, 45f) + 90f, centerX, centerY)
        val leaf = RectF(
            centerX,
            centerY - radius * 0.22f,
            centerX + radius * 1.5f,
            centerY + radius * 0.22f,
        )
        canvas.drawOval(leaf, fillPaint(leafTone))
        canvas.restore()
    }
    for (ring in 0 until 3) {
        val petals = 9 - ring * 2
        val distance = radius * (0.62f - ring * 0.22f)
        val petal = radius * (0.40f - ring * 0.08f)
        val tone = mix(mix(deep, blush, 0.55f), deep, ring / 2f)
        val phase = random.range(0f, TAU)
        for (index in 0 until petals) {
            val angle = TAU * index / petals + phase
            val x = centerX + cos(angle) * distance
            val y = centerY + sin(angle) * distance
            canvas.drawCircle(x, y, petal, fillPaint(tone))
        }
    }

    Typeset(
        ink = mix(deep, Color.rgb(40, 20, 30), 0.7f),
        authorInk = mix(deep, Color.rgb(30, 15, 25), 0.5f),
        italic = true,
    )
}

internal fun childrenBalloons(cover: CoverCanvas): Typeset = with(cover) {
    val sky = hsv(random.range(0.50f, 0.60f), random.range(0.25f, 0.45f), 1f)
    canvas.drawColor(sky)

    val knotX = width * 0.5f
    val knotY = height * 0.84f
    val string = strokePaint(Color.rgb(90, 90, 100), unit(0.003f))
    val count = random.between(3, 5)
    val hues = List(count) { random.float() }
    for (index in 0 until count) {
        val column = 0.22f + 0.56f * index / (count - 1f)
        val x = width * (column + random.range(-0.04f, 0.04f))
        val y = height * random.range(0.44f, 0.58f)
        val radius = width * random.range(0.08f, 0.10f)
        val tone = hsv(hues[index], random.range(0.55f, 0.75f), 0.95f)
        val cord = Path()
        cord.moveTo(x, y + radius * 1.2f)
        cord.quadTo(x + unit(0.04f), (y + knotY) / 2f, knotX, knotY)
        canvas.drawPath(cord, string)
        val body = RectF(x - radius, y - radius * 1.2f, x + radius, y + radius * 1.2f)
        canvas.drawOval(body, fillPaint(tone))
    }

    Typeset(
        ink = hsv(random.range(0.60f, 0.70f), 0.60f, 0.30f),
        authorInk = hsv(0.62f, 0.5f, 0.35f),
        font = CoverFont.Sans,
    )
}
