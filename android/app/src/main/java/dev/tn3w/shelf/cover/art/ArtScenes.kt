package dev.tn3w.shelf.cover.art

import android.graphics.Bitmap
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
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.ridge
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val LAKE_SKIES =
    listOf(
        hsv(0.60f, 0.35f, 0.95f) to hsv(0.08f, 0.35f, 1f),
        hsv(0.72f, 0.55f, 0.35f) to hsv(0.03f, 0.55f, 0.95f),
        hsv(0.55f, 0.45f, 0.85f) to hsv(0.52f, 0.15f, 1f),
        hsv(0.66f, 0.60f, 0.18f) to hsv(0.60f, 0.40f, 0.55f),
    )

internal fun natureLake(cover: CoverCanvas): Typeset =
    with(cover) {
        val (top, low) = random.pick(LAKE_SKIES)
        val horizon = random.range(0.60f, 0.64f)
        verticalGradient(top, low, 1.2f)
        radialGlow(width * random.range(0.3f, 0.7f), height * horizon, width * 0.8f,
            Color.WHITE, 0.3f, 2f)
        val far = mix(top, low, 0.5f)
        val near = shade(mix(top, Color.rgb(20, 30, 40), 0.6f), 0.8f)
        for (index in 0 until 3) {
            val shape = ridge(horizon, random.range(0.06f, 0.22f) * (1f - index * 0.3f),
                random.range(0.04f, 0.09f))
            canvas.drawPath(shape.fill, fillPaint(mix(far, near, index / 2f)))
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
        val ripple = strokePaint(alpha(Color.WHITE, 0.25f), unit(0.003f))
        for (index in 0 until random.between(10, 16)) {
            val y = waterline + (height - waterline) * random.range(0.05f, 1f)
            val x = random.range(0f, width)
            canvas.drawLine(x, y, x + unit(random.range(0.05f, 0.2f)), y, ripple)
        }

        vignette(0.35f, 0.65f)
        grain(0.03f)
        Typeset(
            ink = Color.WHITE,
            authorInk = Color.rgb(236, 240, 244),
            titleFont = random.pick(listOf(CoverFont.Serif, CoverFont.Sans)),
            titleWeight = random.pick(listOf(300, 600)),
            titleTracking = random.range(0.10f, 0.20f),
            titleSize = random.range(0.075f, 0.09f),
            authorFont = CoverFont.Sans,
            authorWeight = 500,
            authorTracking = 0.28f,
            rule = Rule.Line,
            shadow = 0.6f,
        )
    }

private val SEASONS =
    listOf(
        listOf(0.95f, 0.97f, 0.92f),
        listOf(0.28f, 0.33f, 0.38f),
        listOf(0.03f, 0.07f, 0.11f),
        listOf(0.12f, 0.15f, 0.08f),
    )

internal fun natureTree(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.08f, 0.12f), random.range(0.06f, 0.14f), 0.95f)
        val hues = random.pick(SEASONS)
        val bark = hsv(0.07f, 0.45f, 0.30f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
        texture(0.08f)

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
        val limb = strokePaint(bark, unit(0.012f))
        for (side in listOf(-1f, 1f)) {
            canvas.drawLine(centerX, crown + unit(0.06f), centerX + side * unit(0.14f),
                crown - unit(0.06f), limb)
        }

        val radius = width * random.range(0.24f, 0.28f)
        for (index in 0 until 26) {
            val angle = random.range(0f, TAU)
            val distance = radius * random.range(0f, 0.8f)
            val tone = hsv(random.pick(hues), random.range(0.45f, 0.75f),
                random.range(0.60f, 0.90f))
            canvas.drawCircle(centerX + cos(angle) * distance,
                crown - radius * 0.35f + sin(angle) * distance * 0.8f,
                radius * random.range(0.22f, 0.36f), fillPaint(alpha(tone, 0.85f)))
        }
        for (index in 0 until random.between(5, 10)) {
            val tone = hsv(random.pick(hues), 0.6f, 0.8f)
            canvas.drawCircle(centerX + random.range(-0.4f, 0.4f) * width,
                random.range(crown + radius * 0.4f, ground), unit(0.01f), fillPaint(tone))
        }
        canvas.drawOval(RectF(width * 0.1f, ground - unit(0.03f), width * 0.9f,
            ground + unit(0.05f)), fillPaint(mix(paper, bark, 0.3f)))

        grain(0.025f)
        Typeset(
            ink = shade(bark, 0.6f),
            authorInk = bark,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 700)),
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.04f, 0.10f),
            titleSize = random.range(0.08f, 0.10f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.2f,
            rule = Rule.None,
        )
    }

private val RAINBOW = listOf(0.0f, 0.07f, 0.14f, 0.33f, 0.56f, 0.75f)

internal fun childrenRainbow(cover: CoverCanvas): Typeset =
    with(cover) {
        val sky = hsv(random.range(0.52f, 0.58f), random.range(0.20f, 0.35f), 1f)
        verticalGradient(mix(sky, Color.WHITE, 0.6f), sky, 1f)
        val centerX = width * 0.5f
        val base = height * 0.82f
        val band = width * 0.045f
        var radius = width * 0.42f
        for (hue in RAINBOW) {
            val arc = RectF(centerX - radius, base - radius, centerX + radius,
                base + radius)
            canvas.drawArc(arc, 180f, 180f, false, strokePaint(hsv(hue, 0.6f, 1f), band))
            radius -= band
        }
        val white = fillPaint(Color.WHITE)
        for (side in listOf(-1f, 1f)) {
            val cloudX = centerX + side * width * 0.37f
            val puff = width * 0.07f
            for (offset in listOf(-1f, -0.35f, 0.35f, 1f)) {
                canvas.drawCircle(cloudX + offset * puff, base - unit(0.01f),
                    puff * if (abs(offset) > 0.6f) 0.62f else 1f, white)
            }
        }
        canvas.drawRect(0f, base + unit(0.02f), width, height,
            fillPaint(hsv(random.range(0.26f, 0.32f), 0.55f, 0.80f)))

        Typeset(
            ink = hsv(random.range(0.62f, 0.72f), 0.60f, 0.35f),
            authorInk = Color.WHITE,
            titleFont = CoverFont.Casual,
            titleCase = LetterCase.Title,
            titleTracking = 0f,
            titleLeading = 1.05f,
            titleSize = random.range(0.105f, 0.13f),
            authorFont = CoverFont.Casual,
            authorCase = LetterCase.Title,
            authorTracking = 0.06f,
            authorSize = 0.030f,
            rule = Rule.None,
            shadow = 0.3f,
        )
    }

internal fun historyTemple(cover: CoverCanvas): Typeset =
    with(cover) {
        val dark = random.chance(0.5f)
        val stone = if (dark) hsv(0.10f, 0.12f, 0.90f) else hsv(0.07f, 0.55f, 0.35f)
        val ground =
            if (dark) hsv(random.pick(listOf(0.62f, 0.98f, 0.40f)), 0.55f, 0.22f)
            else hsv(0.10f, 0.22f, 0.92f)
        verticalGradient(shade(ground, 1.1f), shade(ground, 0.85f), 1f)
        texture(0.12f)
        radialGlow(width / 2f, height * 0.55f, width * 0.7f, stone, 0.12f, 2f)

        val paint = fillPaint(stone)
        val left = width * 0.18f
        val right = width * 0.82f
        val base = height * 0.77f
        for (step in 0 until 3) {
            val inset = unit(0.03f) * (2 - step)
            val stepTop = base + unit(0.03f) * step
            val reach = unit(0.06f) - inset
            canvas.drawRect(left - reach, stepTop, right + reach, stepTop + unit(0.024f), paint)
        }
        val columns = random.pick(listOf(4, 6))
        val capital = height * 0.52f
        val gap = (right - left) / (columns - 1)
        for (index in 0 until columns) {
            val x = left + gap * index
            canvas.drawRect(x - unit(0.03f), capital, x + unit(0.03f), base, paint)
            canvas.drawRect(x - unit(0.045f), capital - unit(0.02f), x + unit(0.045f),
                capital, paint)
        }
        canvas.drawRect(left - unit(0.07f), capital - unit(0.07f), right + unit(0.07f),
            capital - unit(0.025f), paint)
        val pediment = Path()
        pediment.moveTo(left - unit(0.08f), capital - unit(0.08f))
        pediment.lineTo(right + unit(0.08f), capital - unit(0.08f))
        pediment.lineTo(width / 2f, capital - unit(0.20f))
        pediment.close()
        canvas.drawPath(pediment, paint)

        vignette(0.4f, 0.6f)
        grain(0.035f)
        frame(CoverFrame.Double, stone)
        Typeset(
            ink = stone,
            titleFont = CoverFont.Serif,
            titleWeight = 600,
            titleTracking = random.range(0.10f, 0.18f),
            titleLeading = 1.12f,
            titleSize = random.range(0.07f, 0.09f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.24f,
            shadow = if (dark) 0.4f else 0f,
        )
    }

internal fun spiritualLotus(cover: CoverCanvas): Typeset =
    with(cover) {
        val water = hsv(random.range(0.48f, 0.58f), random.range(0.45f, 0.65f),
            random.range(0.20f, 0.30f))
        val petal = hsv(random.pick(listOf(0.95f, 0.92f, 0.12f, 0.0f)), 0.35f, 0.98f)
        val gold = hsv(0.12f, 0.55f, 0.95f)
        verticalGradient(shade(water, 0.6f), water, 1f)
        val centerX = width / 2f
        val centerY = height * random.range(0.66f, 0.70f)
        radialGlow(centerX, centerY - unit(0.1f), width * 0.7f, gold, 0.35f, 2.2f)

        val ripple = strokePaint(alpha(Color.WHITE, 0.18f), unit(0.003f))
        for (index in 1..4) {
            val spread = width * 0.14f * index
            canvas.drawOval(RectF(centerX - spread, centerY - spread * 0.12f,
                centerX + spread, centerY + spread * 0.12f), ripple)
        }
        val size = width * random.range(0.24f, 0.28f)
        for ((count, reach, tone) in listOf(Triple(7, 1f, shade(petal, 0.8f)),
            Triple(5, 0.85f, petal), Triple(3, 0.7f, mix(petal, Color.WHITE, 0.5f)))) {
            for (index in 0 until count) {
                val angle = -90f + 150f * (index / (count - 1f) - 0.5f)
                lotusPetal(cover, centerX, centerY, size * reach, angle, tone)
            }
        }
        canvas.drawCircle(centerX, centerY - size * 0.15f, unit(0.02f), fillPaint(gold))

        vignette(0.5f, 0.55f)
        grain(0.03f)
        Typeset(
            ink = mix(gold, Color.WHITE, 0.4f),
            authorInk = mix(petal, Color.WHITE, 0.3f),
            titleFont = CoverFont.Serif,
            titleWeight = 400,
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.08f, 0.16f),
            titleSize = random.range(0.07f, 0.09f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
            shadow = 0.4f,
        )
    }

private fun lotusPetal(
    cover: CoverCanvas,
    x: Float,
    y: Float,
    length: Float,
    degrees: Float,
    tone: Int,
) =
    with(cover) {
        canvas.save()
        canvas.rotate(degrees + 90f, x, y)
        val shape = Path()
        shape.moveTo(x, y)
        shape.quadTo(x - length * 0.32f, y - length * 0.55f, x, y - length)
        shape.quadTo(x + length * 0.32f, y - length * 0.55f, x, y)
        shape.close()
        canvas.drawPath(shape, fillPaint(tone))
        canvas.drawPath(shape, strokePaint(shade(tone, 0.8f), unit(0.003f)))
        canvas.restore()
    }

internal fun poetryRain(cover: CoverCanvas): Typeset =
    with(cover) {
        val dusk = hsv(random.range(0.55f, 0.68f), random.range(0.20f, 0.40f),
            random.range(0.30f, 0.45f))
        val pale = hsv(0.6f, 0.08f, 0.95f)
        val umbrella = hsv(random.pick(listOf(0.0f, 0.12f, 0.95f)), 0.8f, 0.9f)
        verticalGradient(shade(dusk, 0.6f), dusk, 1f)
        val slant = random.range(0.05f, 0.18f)
        val rain = strokePaint(alpha(pale, 0.35f), unit(0.0025f))
        for (index in 0 until 160) {
            val x = random.range(-0.1f, 1f) * width
            val y = random.range(0f, 0.9f) * height
            val length = height * random.range(0.02f, 0.05f)
            canvas.drawLine(x, y, x + length * slant, y + length, rain)
        }
        val water = height * 0.80f
        canvas.drawRect(0f, water, width, height, fillPaint(shade(dusk, 0.7f)))
        val ripple = strokePaint(alpha(pale, 0.35f), unit(0.003f))
        for (index in 0 until random.between(6, 11)) {
            val x = random.range(0.05f, 0.95f) * width
            val y = random.range(water + unit(0.03f), height)
            for (ring in 1..2) {
                val spread = unit(0.03f * ring)
                canvas.drawOval(RectF(x - spread, y - spread * 0.3f, x + spread,
                    y + spread * 0.3f), ripple)
            }
        }

        val umbrellaX = width * random.range(0.35f, 0.65f)
        val umbrellaY = height * random.range(0.60f, 0.64f)
        val span = width * 0.13f
        canvas.drawArc(RectF(umbrellaX - span, umbrellaY - span * 0.8f, umbrellaX + span,
            umbrellaY + span * 0.8f), 180f, 180f, true, fillPaint(umbrella))
        val handle = strokePaint(Color.rgb(30, 30, 36), unit(0.006f))
        canvas.drawLine(umbrellaX, umbrellaY, umbrellaX, umbrellaY + span * 1.2f, handle)
        val hook = umbrellaY + span * 1.2f
        canvas.drawArc(RectF(umbrellaX - unit(0.03f), hook - unit(0.03f),
            umbrellaX + unit(0.01f), hook + unit(0.03f)), 0f, 180f, false, handle)

        vignette(0.4f, 0.6f)
        grain(0.04f)
        Typeset(
            ink = pale,
            titleFont = CoverFont.Serif,
            titleWeight = 400,
            titleItalic = true,
            titleCase = LetterCase.Title,
            titleTracking = 0.01f,
            titleLeading = 1.1f,
            titleSize = random.range(0.085f, 0.105f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.24f,
            rule = Rule.None,
        )
    }
