package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.sin

internal fun historyEmblem(cover: CoverCanvas): Typeset = with(cover) {
    val parchment = hsv(
        random.range(0.08f, 0.11f),
        random.range(0.18f, 0.30f),
        random.range(0.88f, 0.95f),
    )
    val sepia = hsv(
        random.range(0.06f, 0.09f),
        random.range(0.55f, 0.70f),
        random.range(0.30f, 0.42f),
    )
    canvas.drawColor(parchment)

    val centerX = width * 0.5f
    val centerY = height * 0.58f
    val radius = width * random.range(0.22f, 0.28f)
    canvas.drawCircle(centerX, centerY, radius, strokePaint(sepia, unit(0.008f)))
    val spokes = random.pick(listOf(8, 12, 16))
    val spokePaint = strokePaint(sepia, unit(0.004f))
    for (index in 0 until spokes) {
        val angle = TAU * index / spokes
        val inner = radius * 0.5f
        canvas.drawLine(
            centerX + cos(angle) * inner,
            centerY + sin(angle) * inner,
            centerX + cos(angle) * radius * 0.82f,
            centerY + sin(angle) * radius * 0.82f,
            spokePaint,
        )
    }
    canvas.drawCircle(
        centerX, centerY, radius * 0.3f, fillPaint(sepia),
    )

    Typeset(
        ink = shade(sepia, 0.65f),
        authorInk = sepia,
        upper = true,
    )
}

internal fun vintageBands(cover: CoverCanvas): Typeset = with(cover) {
    val shell = hsv(
        random.range(0.09f, 0.12f),
        random.range(0.10f, 0.18f),
        random.range(0.92f, 0.97f),
    )
    val band = random.pick(
        listOf(
            hsv(0.055f, 0.85f, 0.92f),
            hsv(0.33f, 0.55f, 0.45f),
            hsv(0.60f, 0.55f, 0.45f),
            hsv(0.98f, 0.65f, 0.60f),
            hsv(0.12f, 0.75f, 0.85f),
        ),
    )
    val ink = hsv(random.range(0.05f, 0.10f), 0.25f, random.range(0.12f, 0.18f))
    canvas.drawRect(0f, 0f, width, height, fillPaint(band))

    val panelTop = height * random.range(0.26f, 0.32f)
    val panelBottom = height * random.range(0.68f, 0.74f)
    canvas.drawRect(0f, panelTop, width, panelBottom, fillPaint(shell))

    Typeset(
        ink = ink,
        authorInk = shell,
        upper = true,
        anchor = Anchor.Center,
    )
}

internal fun adventureMap(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(
        random.range(0.09f, 0.12f),
        random.range(0.20f, 0.32f),
        random.range(0.86f, 0.94f),
    )
    val ink = hsv(
        random.range(0.05f, 0.09f),
        random.range(0.55f, 0.75f),
        random.range(0.24f, 0.34f),
    )
    val sea = hsv(
        random.range(0.50f, 0.56f),
        random.range(0.30f, 0.45f),
        random.range(0.55f, 0.70f),
    )
    canvas.drawColor(paper)

    val centerX = width * random.range(0.44f, 0.56f)
    val centerY = height * random.range(0.56f, 0.62f)
    val harmonics = randomHarmonics(random, 3, random.range(0.14f, 0.26f))
    val island = width * random.range(0.22f, 0.30f)
    for (step in 1..2) {
        canvas.drawPath(
            wobblyRingPath(centerX, centerY, island * (1f + step * 0.18f), harmonics),
            strokePaint(mix(sea, paper, step / 4f), unit(0.004f)),
        )
    }
    val coast = wobblyRingPath(centerX, centerY, island, harmonics)
    canvas.drawPath(coast, fillPaint(mix(paper, ink, 0.18f)))
    canvas.drawPath(coast, strokePaint(ink, unit(0.004f)))

    val mark = width * 0.022f
    val cross = strokePaint(hsv(0f, 0.7f, 0.55f), unit(0.006f))
    for (angle in listOf(0.7f, -0.7f)) {
        canvas.drawLine(
            centerX - cos(angle) * mark,
            centerY - sin(angle) * mark,
            centerX + cos(angle) * mark,
            centerY + sin(angle) * mark,
            cross,
        )
    }

    Typeset(
        ink = shade(ink, 0.75f),
        authorInk = ink,
        upper = true,
    )
}

internal fun poetryWash(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(
        random.range(0.05f, 0.14f),
        random.range(0.03f, 0.10f),
        random.range(0.94f, 0.99f),
    )
    val accent =
        hsv(random.float(), random.range(0.25f, 0.55f), random.range(0.40f, 0.70f))
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

    val centerX = width * random.range(0.42f, 0.58f)
    val centerY = height * random.range(0.58f, 0.68f)
    when (random.index(3)) {
        0 ->
            repeat(3) {
                val radius = width * random.range(0.10f, 0.26f)
                val spotX = centerX + random.range(-0.12f, 0.12f) * width
                val spotY = centerY + random.range(-0.10f, 0.10f) * height
                canvas.drawPath(
                    wobblyRingPath(
                        spotX, spotY, radius, randomHarmonics(random, 3, 0.22f), 120,
                    ),
                    fillPaint(alpha(accent, random.range(0.11f, 0.23f))),
                )
            }

        1 -> {
            val top = height * random.range(0.44f, 0.52f)
            canvas.drawLine(
                centerX, height * 0.94f, centerX, top, strokePaint(accent, unit(0.004f)),
            )
            val twig = strokePaint(alpha(accent, 0.78f), unit(0.003f))
            for (index in 0 until 6) {
                val fraction = index / 7f
                val side = if (index % 2 == 0) 1f else -1f
                val length = width * random.range(0.06f, 0.13f) * (1f - fraction * 0.4f)
                val anchorY = height * 0.94f - (height * 0.94f - top) * (fraction + 0.1f)
                canvas.drawLine(
                    centerX,
                    anchorY,
                    centerX + side * length,
                    anchorY - length * 0.55f,
                    twig,
                )
            }
        }

        else ->
            for (index in 0 until 4) {
                val radius = width * (0.10f + index * 0.07f)
                canvas.drawOval(
                    RectF(
                        centerX - radius,
                        centerY - radius * 0.62f,
                        centerX + radius,
                        centerY + radius * 0.62f,
                    ),
                    strokePaint(alpha(accent, 0.85f), unit(0.004f)),
                )
            }
    }

    Typeset(
        ink = mix(accent, Color.rgb(25, 22, 30), 0.65f),
        authorInk = shade(accent, 0.85f),
        italic = true,
    )
}

internal fun natureContours(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.27f, 0.32f, 0.38f, 0.14f, 0.48f))
    val paper = hsv(
        random.range(0.09f, 0.13f),
        random.range(0.06f, 0.14f),
        random.range(0.92f, 0.97f),
    )
    val deep = hsv(hue, random.range(0.50f, 0.75f), random.range(0.28f, 0.45f))
    canvas.drawColor(paper)

    val centerX = width * 0.5f
    val centerY = height * random.range(0.58f, 0.64f)
    val harmonics = randomHarmonics(random, 3, random.range(0.10f, 0.18f))
    for (index in 0 until 7) {
        canvas.drawPath(
            wobblyRingPath(centerX, centerY, width * (0.08f + index * 0.055f), harmonics),
            strokePaint(mix(deep, paper, index / 9f), unit(0.005f)),
        )
    }
    canvas.drawPath(
        wobblyRingPath(centerX, centerY, width * 0.04f, harmonics), fillPaint(deep),
    )

    Typeset(
        ink = shade(deep, 0.7f),
        authorInk = deep,
        font = CoverFont.Sans,
        upper = true,
    )
}

internal fun spiritualRays(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.62f, 0.72f, 0.06f, 0.52f))
    val deep = hsv(hue, random.range(0.55f, 0.80f), random.range(0.08f, 0.14f))
    val gold = hsv(
        random.range(0.09f, 0.13f), random.range(0.55f, 0.75f), random.range(0.88f, 1f),
    )
    canvas.drawColor(deep)
    val centerX = width * 0.5f
    val centerY = height * 0.58f

    val count = random.pick(listOf(12, 16, 20))
    for (index in 0 until count) {
        val angle = TAU * index / count
        val ray = Path()
        ray.moveTo(centerX, centerY)
        ray.lineTo(
            centerX + cos(angle - 0.02f) * width, centerY + sin(angle - 0.02f) * width,
        )
        ray.lineTo(
            centerX + cos(angle + 0.02f) * width, centerY + sin(angle + 0.02f) * width,
        )
        ray.close()
        canvas.drawPath(ray, fillPaint(alpha(gold, 0.25f)))
    }
    canvas.drawCircle(
        centerX,
        centerY,
        width * random.range(0.12f, 0.16f),
        fillPaint(gold),
    )

    Typeset(
        ink = mix(gold, Color.WHITE, 0.55f),
        authorInk = gold,
        upper = true,
    )
}

internal fun biographyPortrait(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.float()
    val light = hsv(hue, random.range(0.10f, 0.22f), random.range(0.90f, 0.96f))
    val dark = hsv(
        hue + random.range(0.35f, 0.6f),
        random.range(0.45f, 0.70f),
        random.range(0.20f, 0.32f),
    )
    canvas.drawColor(light)

    val headRadius = width * random.range(0.16f, 0.20f)
    val headX = width * 0.5f
    val headY = height * random.range(0.52f, 0.56f)
    val bust = Path()
    bust.addOval(
        headX - headRadius * 0.86f,
        headY - headRadius,
        headX + headRadius * 0.86f,
        headY + headRadius,
        Path.Direction.CW,
    )
    val shoulder = headRadius * random.range(2.1f, 2.7f)
    bust.addOval(
        headX - shoulder,
        headY + headRadius * 0.85f,
        headX + shoulder,
        height * 1.06f,
        Path.Direction.CW,
    )

    canvas.save()
    canvas.clipRect(0f, 0f, width, height * 0.86f)
    canvas.clipPath(bust)
    val pitch = unit(0.026f).coerceAtLeast(3f)
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

    Typeset(
        ink = shade(dark, 0.75f),
        authorInk = shade(dark, 0.85f),
        upper = true,
    )
}

internal fun spiritualEnso(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(
        random.range(0.08f, 0.12f),
        random.range(0.06f, 0.14f),
        random.range(0.92f, 0.96f),
    )
    val sumi = Color.rgb(24, 22, 24)
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

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
        canvas.drawCircle(
            centerX + cos(angle) * wobble + random.range(-1f, 1f),
            centerY + sin(angle) * wobble + random.range(-1f, 1f),
            brush * swell * random.range(0.85f, 1f),
            paint,
        )
    }

    val seal = unit(0.05f)
    val sealX = centerX + radius * 1.1f
    val sealY = centerY + radius * 1.05f
    canvas.drawRect(
        sealX - seal,
        sealY - seal,
        sealX + seal,
        sealY + seal,
        fillPaint(hsv(0.99f, 0.80f, 0.72f)),
    )

    Typeset(
        ink = sumi,
        authorInk = Color.rgb(80, 74, 70),
        upper = random.chance(0.5f),
    )
}

internal fun technicalBlueprint(cover: CoverCanvas): Typeset = with(cover) {
    val blue = hsv(
        random.range(0.58f, 0.62f),
        random.range(0.70f, 0.85f),
        random.range(0.45f, 0.58f),
    )
    val chalk = Color.rgb(236, 242, 250)
    canvas.drawColor(blue)
    val grid = strokePaint(alpha(chalk, 0.12f), 1f)
    val pitch = width / 8f
    for (index in 0..12) {
        canvas.drawLine(index * pitch, 0f, index * pitch, height, grid)
        canvas.drawLine(0f, index * pitch, width, index * pitch, grid)
    }

    val centerX = width * 0.5f
    val centerY = height * 0.60f
    val radius = width * random.range(0.22f, 0.28f)
    val line = strokePaint(chalk, unit(0.005f))
    canvas.drawPath(gear(centerX, centerY, radius, random.between(10, 16)), line)
    canvas.drawCircle(centerX, centerY, radius * 0.3f, line)

    Typeset(
        ink = chalk,
        font = CoverFont.Sans,
        upper = true,
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

private val TYPE_PALETTES = listOf(
    Color.rgb(242, 236, 224) to Color.rgb(28, 26, 24),
    Color.rgb(28, 40, 64) to Color.rgb(240, 230, 208),
    Color.rgb(36, 64, 50) to Color.rgb(236, 228, 206),
    Color.rgb(176, 84, 58) to Color.rgb(250, 238, 222),
    Color.rgb(214, 170, 76) to Color.rgb(30, 26, 22),
    Color.rgb(110, 30, 44) to Color.rgb(244, 214, 214),
    Color.rgb(222, 226, 222) to Color.rgb(40, 60, 90),
)

internal fun literaryType(cover: CoverCanvas): Typeset = with(cover) {
    val (paper, ink) = random.pick(TYPE_PALETTES)
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
    for (y in listOf(0.30f, 0.70f)) {
        val lineY = height * y
        canvas.drawRect(
            width * 0.4f,
            lineY,
            width * 0.6f,
            lineY + unit(0.004f),
            fillPaint(ink),
        )
    }

    Typeset(
        ink = ink,
        italic = random.chance(0.3f),
        upper = random.chance(0.5f),
        anchor = Anchor.Center,
    )
}
