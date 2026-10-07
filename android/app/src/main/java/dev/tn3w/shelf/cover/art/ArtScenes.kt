package dev.tn3w.shelf.cover.art

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.sin

private val LAKE_SKIES = listOf(
    hsv(0.60f, 0.35f, 0.95f) to hsv(0.08f, 0.35f, 1f),
    hsv(0.72f, 0.55f, 0.35f) to hsv(0.03f, 0.55f, 0.95f),
    hsv(0.55f, 0.45f, 0.85f) to hsv(0.52f, 0.15f, 1f),
    hsv(0.66f, 0.60f, 0.18f) to hsv(0.60f, 0.40f, 0.55f),
)

internal fun natureLake(cover: CoverCanvas): Typeset = with(cover) {
    val (top, low) = random.pick(LAKE_SKIES)
    val horizon = random.range(0.60f, 0.64f)
    verticalGradient(top, low, 1.2f)
    val far = mix(top, low, 0.5f)
    val near = shade(mix(top, Color.rgb(20, 30, 40), 0.6f), 0.8f)
    for (index in 0 until 2) {
        val shape = ridge(
            horizon,
            random.range(0.08f, 0.20f) * (1f - index * 0.4f),
            random.range(0.04f, 0.07f),
        )
        canvas.drawPath(shape, fillPaint(mix(far, near, index.toFloat())))
    }

    val waterline = (height * horizon).toInt()
    val depth = (height.toInt() - waterline).coerceAtMost(waterline)
    val sky = Bitmap.createBitmap(bitmap, 0, waterline - depth, bitmap.width, depth)
    canvas.save()
    canvas.scale(1f, -1f, 0f, waterline.toFloat())
    canvas.drawBitmap(sky, 0f, (waterline - depth).toFloat(), null)
    canvas.restore()
    sky.recycle()
    val water = mix(top, Color.rgb(10, 30, 50), 0.4f)
    val surface = fillPaint(alpha(water, 0.45f))
    canvas.drawRect(0f, waterline.toFloat(), width, height, surface)

    Typeset(
        ink = Color.WHITE,
        authorInk = Color.rgb(236, 240, 244),
        font = random.pick(listOf(CoverFont.Serif, CoverFont.Sans)),
        upper = true,
    )
}

private val SEASONS = listOf(
    listOf(0.95f, 0.97f, 0.92f),
    listOf(0.28f, 0.33f, 0.38f),
    listOf(0.03f, 0.07f, 0.11f),
    listOf(0.12f, 0.15f, 0.08f),
)

internal fun natureTree(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(random.range(0.08f, 0.12f), random.range(0.06f, 0.14f), 0.95f)
    val hues = random.pick(SEASONS)
    val bark = hsv(0.07f, 0.45f, 0.30f)
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

    val centerX = width * random.range(0.44f, 0.56f)
    val crown = height * random.range(0.58f, 0.62f)
    val ground = height * 0.84f
    val trunk = Path()
    trunk.moveTo(centerX - unit(0.05f), ground)
    val bend = crown + unit(0.1f)
    trunk.quadTo(centerX - unit(0.015f), bend, centerX - unit(0.02f), crown)
    trunk.lineTo(centerX + unit(0.02f), crown)
    trunk.quadTo(centerX + unit(0.015f), bend, centerX + unit(0.05f), ground)
    trunk.close()
    canvas.drawPath(trunk, fillPaint(bark))

    val radius = width * random.range(0.24f, 0.28f)
    repeat(7) { index ->
        val angle = TAU * index / 7 + random.range(-0.3f, 0.3f)
        val distance = if (index == 0) 0f else radius * 0.55f
        val tone = hsv(
            random.pick(hues), random.range(0.45f, 0.65f), random.range(0.70f, 0.88f),
        )
        canvas.drawCircle(
            centerX + cos(angle) * distance,
            crown - radius * 0.45f + sin(angle) * distance * 0.75f,
            radius * random.range(0.40f, 0.48f),
            fillPaint(tone),
        )
    }
    canvas.drawRect(
        width * 0.2f,
        ground,
        width * 0.8f,
        ground + unit(0.006f),
        fillPaint(bark),
    )

    Typeset(
        ink = shade(bark, 0.6f),
        authorInk = bark,
        upper = random.chance(0.5f),
    )
}

private val RAINBOW = listOf(0.0f, 0.07f, 0.14f, 0.33f, 0.56f, 0.75f)

internal fun childrenRainbow(cover: CoverCanvas): Typeset = with(cover) {
    val sky = hsv(random.range(0.52f, 0.58f), random.range(0.20f, 0.35f), 1f)
    canvas.drawColor(sky)
    val centerX = width * 0.5f
    val base = height * 0.82f
    val band = width * 0.045f
    var radius = width * 0.42f
    for (hue in RAINBOW) {
        val arc = RectF(centerX - radius, base - radius, centerX + radius, base + radius)
        canvas.drawArc(arc, 180f, 180f, false, strokePaint(hsv(hue, 0.6f, 1f), band))
        radius -= band
    }
    canvas.drawRect(
        0f,
        base + unit(0.02f),
        width,
        height,
        fillPaint(hsv(random.range(0.26f, 0.32f), 0.55f, 0.80f)),
    )

    Typeset(
        ink = hsv(random.range(0.62f, 0.72f), 0.60f, 0.35f),
        authorInk = Color.WHITE,
        font = CoverFont.Sans,
    )
}

internal fun historyTemple(cover: CoverCanvas): Typeset = with(cover) {
    val dark = random.chance(0.5f)
    val stone = if (dark) hsv(0.10f, 0.12f, 0.90f) else hsv(0.07f, 0.55f, 0.35f)
    val ground = if (dark) {
        hsv(random.pick(listOf(0.62f, 0.98f, 0.40f)), 0.55f, 0.22f)
    } else {
        hsv(0.10f, 0.22f, 0.92f)
    }
    canvas.drawColor(ground)

    val paint = fillPaint(stone)
    val left = width * 0.18f
    val right = width * 0.82f
    val base = height * 0.77f
    for (step in 0 until 3) {
        val inset = unit(0.03f) * (2 - step)
        val stepTop = base + unit(0.03f) * step
        val reach = unit(0.06f) - inset
        val stepBottom = stepTop + unit(0.024f)
        canvas.drawRect(left - reach, stepTop, right + reach, stepBottom, paint)
    }
    val columns = random.pick(listOf(4, 6))
    val capital = height * 0.52f
    val gap = (right - left) / (columns - 1)
    for (index in 0 until columns) {
        val x = left + gap * index
        canvas.drawRect(x - unit(0.03f), capital, x + unit(0.03f), base, paint)
        canvas.drawRect(
            x - unit(0.045f), capital - unit(0.02f), x + unit(0.045f), capital, paint,
        )
    }
    canvas.drawRect(
        left - unit(0.07f),
        capital - unit(0.07f),
        right + unit(0.07f),
        capital - unit(0.025f),
        paint,
    )
    val pediment = Path()
    pediment.moveTo(left - unit(0.08f), capital - unit(0.08f))
    pediment.lineTo(right + unit(0.08f), capital - unit(0.08f))
    pediment.lineTo(width / 2f, capital - unit(0.20f))
    pediment.close()
    canvas.drawPath(pediment, paint)

    Typeset(
        ink = stone,
        upper = true,
    )
}

internal fun spiritualLotus(cover: CoverCanvas): Typeset = with(cover) {
    val water = hsv(
        random.range(0.48f, 0.58f),
        random.range(0.45f, 0.65f),
        random.range(0.20f, 0.30f),
    )
    val petal = hsv(random.pick(listOf(0.95f, 0.92f, 0.12f, 0.0f)), 0.35f, 0.98f)
    val gold = hsv(0.12f, 0.55f, 0.95f)
    canvas.drawColor(water)
    val centerX = width / 2f
    val centerY = height * random.range(0.70f, 0.74f)

    val size = width * random.range(0.24f, 0.28f)
    for ((count, reach, tone) in listOf(
        Triple(7, 1f, shade(petal, 0.8f)),
        Triple(5, 0.85f, petal),
        Triple(3, 0.7f, mix(petal, Color.WHITE, 0.5f)),
    )) {
        for (index in 0 until count) {
            val angle = -90f + 150f * (index / (count - 1f) - 0.5f)
            lotusPetal(cover, centerX, centerY, size * reach, angle, tone)
        }
    }

    Typeset(
        ink = mix(gold, Color.WHITE, 0.4f),
        authorInk = mix(petal, Color.WHITE, 0.3f),
        upper = random.chance(0.5f),
    )
}

private fun lotusPetal(
    cover: CoverCanvas,
    x: Float,
    y: Float,
    length: Float,
    degrees: Float,
    tone: Int,
) = with(cover) {
    canvas.save()
    canvas.rotate(degrees + 90f, x, y)
    val shape = Path()
    shape.moveTo(x, y)
    shape.quadTo(x - length * 0.32f, y - length * 0.55f, x, y - length)
    shape.quadTo(x + length * 0.32f, y - length * 0.55f, x, y)
    shape.close()
    canvas.drawPath(shape, fillPaint(tone))
    canvas.restore()
}

internal fun poetryRain(cover: CoverCanvas): Typeset = with(cover) {
    val dusk = hsv(
        random.range(0.55f, 0.68f),
        random.range(0.20f, 0.40f),
        random.range(0.30f, 0.45f),
    )
    val pale = hsv(0.6f, 0.08f, 0.95f)
    val umbrella = hsv(random.pick(listOf(0.0f, 0.12f, 0.95f)), 0.8f, 0.9f)
    canvas.drawColor(shade(dusk, 0.7f))
    val rain = strokePaint(alpha(pale, 0.3f), unit(0.003f))
    repeat(36) {
        val x = random.range(0f, 1f) * width
        val y = random.range(0.30f, 0.86f) * height
        canvas.drawLine(x, y, x, y + height * 0.05f, rain)
    }

    val umbrellaX = width * 0.5f
    val umbrellaY = height * random.range(0.60f, 0.64f)
    val span = width * 0.13f
    canvas.drawArc(
        RectF(
            umbrellaX - span,
            umbrellaY - span * 0.8f,
            umbrellaX + span,
            umbrellaY + span * 0.8f,
        ),
        180f,
        180f,
        true,
        fillPaint(umbrella),
    )
    val handle = strokePaint(Color.rgb(30, 30, 36), unit(0.006f))
    canvas.drawLine(umbrellaX, umbrellaY, umbrellaX, umbrellaY + span * 1.2f, handle)
    val hook = umbrellaY + span * 1.2f
    canvas.drawArc(
        RectF(
            umbrellaX - unit(0.03f),
            hook - unit(0.03f),
            umbrellaX + unit(0.01f),
            hook + unit(0.03f),
        ),
        0f,
        180f,
        false,
        handle,
    )

    Typeset(
        ink = pale,
        italic = true,
    )
}
