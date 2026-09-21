package dev.tn3w.shelf.cover.art

import android.graphics.BlurMaskFilter
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
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.starPath
import dev.tn3w.shelf.cover.stars
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal fun spiritualMandala(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.62f, 0.70f, 0.78f, 0.48f, 0.95f))
        val deep = hsv(hue, random.range(0.55f, 0.75f), random.range(0.16f, 0.24f))
        val gold = hsv(random.range(0.10f, 0.13f), random.range(0.45f, 0.65f),
            random.range(0.85f, 0.95f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(deep))

        val centerX = width * 0.5f
        val centerY = height * random.range(0.58f, 0.62f)
        val radius = width * random.range(0.34f, 0.39f)
        radialGlow(centerX, centerY, radius * 1.7f, gold, 0.22f, 2.4f)
        val dot = fillPaint(alpha(gold, 0.8f))
        for (index in 0 until 48) {
            val angle = TAU * index / 48
            canvas.drawCircle(centerX + cos(angle) * radius * 1.1f,
                centerY + sin(angle) * radius * 1.1f, unit(0.005f), dot)
        }
        val layers = random.between(4, 6)
        for (layer in 0 until layers) {
            petalRing(cover, centerX, centerY, radius * (1f - layer / layers.toFloat()),
                random.pick(listOf(8, 12, 16)), layer % 2 == 1, gold, deep)
        }
        canvas.drawCircle(centerX, centerY, radius * 0.07f, fillPaint(gold))

        texture(0.08f)
        vignette(0.45f, 0.6f)
        grain(0.03f)
        Typeset(
            ink = gold,
            authorInk = mix(gold, Color.WHITE, 0.3f),
            titleFont = CoverFont.Serif,
            titleWeight = 500,
            titleTracking = random.range(0.10f, 0.16f),
            titleSize = random.range(0.065f, 0.08f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
            shadow = 0.4f,
        )
    }

private fun petalRing(
    cover: CoverCanvas,
    centerX: Float,
    centerY: Float,
    reach: Float,
    petals: Int,
    filled: Boolean,
    gold: Int,
    deep: Int,
) =
    with(cover) {
        val length = reach * 0.5f
        val breadth = length * random.range(0.30f, 0.45f)
        val paint =
            if (filled) fillPaint(alpha(gold, 0.85f)) else strokePaint(gold, unit(0.004f))
        val offset = if (random.chance(0.5f)) 0.5f else 0f
        val oval = RectF(centerX - breadth / 2f, centerY - reach, centerX + breadth / 2f,
            centerY - reach + length)
        for (index in 0 until petals) {
            canvas.save()
            canvas.rotate(360f * (index + offset) / petals, centerX, centerY)
            canvas.drawOval(oval, fillPaint(deep))
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

internal fun natureLeaves(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.10f, 0.14f), random.range(0.08f, 0.16f),
            random.range(0.93f, 0.97f))
        val greens = List(4) {
            hsv(random.range(0.24f, 0.42f), random.range(0.40f, 0.75f),
                random.range(0.30f, 0.70f))
        }
        val berry = hsv(random.pick(listOf(0.02f, 0.07f, 0.95f)), 0.7f, 0.8f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
        texture(0.08f)

        var placed = 0
        repeat(200) {
            val x = random.range(-0.05f, 1.05f) * width
            val y = random.range(0.12f, 1.05f) * height
            val spreadX = (x - width / 2f) / (width * 0.42f)
            val spreadY = (y - height * 0.5f) / (height * 0.22f)
            if (placed >= 30 || spreadX * spreadX + spreadY * spreadY < 1f) return@repeat
            if (y < height * 0.2f && abs(spreadX) < 0.9f) return@repeat
            val outward = Math.toDegrees(atan2(spreadY, spreadX).toDouble()).toFloat()
            leaf(cover, x, y, width * random.range(0.16f, 0.32f),
                outward + random.range(-40f, 40f), random.pick(greens))
            placed++
        }
        for (index in 0 until random.between(3, 6)) {
            val side = random.pick(listOf(0.12f, 0.88f))
            val x = (side + random.range(-0.06f, 0.06f)) * width
            val y = random.range(0.72f, 0.95f) * height
            for (grape in 0 until 3) {
                canvas.drawCircle(x + grape * unit(0.022f), y + (grape % 2) * unit(0.02f),
                    unit(0.016f), fillPaint(berry))
            }
        }

        grain(0.025f)
        Typeset(
            ink = hsv(0.36f, 0.55f, 0.20f),
            authorInk = hsv(0.36f, 0.45f, 0.28f),
            titleFont = CoverFont.Serif,
            titleWeight = 600,
            titleTracking = random.range(0.06f, 0.12f),
            titleLeading = 1.08f,
            titleSize = random.range(0.075f, 0.095f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
            anchor = Anchor.Center,
            rule = Rule.None,
        )
    }

private fun leaf(cover: CoverCanvas, x: Float, y: Float, length: Float, degrees: Float,
    color: Int) =
    with(cover) {
        val half = length * random.range(0.18f, 0.26f)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(degrees)
        val shape = Path()
        shape.moveTo(0f, 0f)
        shape.quadTo(length * 0.45f, -half * 2f, length, 0f)
        shape.quadTo(length * 0.45f, half * 2f, 0f, 0f)
        canvas.drawPath(shape, fillPaint(color))
        val vein = strokePaint(mix(color, Color.WHITE, 0.35f), length * 0.016f)
        canvas.drawLine(0f, 0f, length * 0.92f, 0f, vein)
        for (step in 1..4) {
            val start = length * step / 5f
            val spread = half * 0.8f * (1f - step / 5f)
            canvas.drawLine(start, 0f, start + length * 0.12f, -spread, vein)
            canvas.drawLine(start, 0f, start + length * 0.12f, spread, vein)
        }
        canvas.restore()
    }

internal fun poetryMoons(cover: CoverCanvas): Typeset =
    with(cover) {
        val night = hsv(random.range(0.60f, 0.70f), random.range(0.45f, 0.65f),
            random.range(0.14f, 0.22f))
        val pale = hsv(random.range(0.10f, 0.14f), random.range(0.10f, 0.25f),
            random.range(0.90f, 0.97f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(night))
        stars((width * 0.25f).toInt(), height, pale, unit(0.002f))

        val count = random.pick(listOf(5, 7))
        val centerX = width * 0.5f
        val centerY = height * random.range(0.72f, 0.76f)
        val arc = width * 0.36f
        val moon = arc * sin(PI.toFloat() * 0.8f / (2 * (count - 1))) * 0.75f
        canvas.drawArc(RectF(centerX - arc, centerY - arc, centerX + arc, centerY + arc),
            198f, 144f, false, strokePaint(alpha(pale, 0.25f), unit(0.002f)))
        for (index in 0 until count) {
            val angle = PI.toFloat() * (1.1f + 0.8f * index / (count - 1f))
            val middle = index == count / 2
            moonPhase(cover, centerX + cos(angle) * arc, centerY + sin(angle) * arc,
                if (middle) moon * 1.2f else moon, index / (count - 1f), pale, night)
        }

        texture(0.10f)
        vignette(0.35f, 0.65f)
        grain(0.03f)
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
            rule = Rule.Ornament,
        )
    }

private fun moonPhase(
    cover: CoverCanvas,
    x: Float,
    y: Float,
    radius: Float,
    phase: Float,
    pale: Int,
    night: Int,
) =
    with(cover) {
        canvas.drawCircle(x, y, radius, fillPaint(pale))
        val offset = radius * 2f * sin(phase * PI.toFloat())
        val direction = if (phase < 0.5f) -1f else 1f
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(x, y, radius, Path.Direction.CW) })
        canvas.drawCircle(x + direction * offset, y, radius * 1.02f,
            fillPaint(mix(night, pale, 0.06f)))
        canvas.restore()
        canvas.drawCircle(x, y, radius, strokePaint(alpha(pale, 0.4f), unit(0.002f)))
    }

internal fun adventureCompass(cover: CoverCanvas): Typeset =
    with(cover) {
        val sea = hsv(random.range(0.50f, 0.58f), random.range(0.45f, 0.65f),
            random.range(0.28f, 0.40f))
        val brass = hsv(random.range(0.10f, 0.12f), random.range(0.45f, 0.60f),
            random.range(0.82f, 0.92f))
        val cream = Color.rgb(244, 236, 216)
        verticalGradient(shade(sea, 1.2f), shade(sea, 0.7f), 1f)
        texture(0.12f)

        val grid = strokePaint(alpha(cream, 0.10f), unit(0.002f))
        for (index in 1 until 8) {
            canvas.drawLine(width * index / 8f, 0f, width * index / 8f, height, grid)
            canvas.drawLine(0f, height * index / 8f, width, height * index / 8f, grid)
        }
        val centerX = width * 0.5f
        val centerY = height * random.range(0.58f, 0.63f)
        val radius = width * random.range(0.30f, 0.35f)
        for (index in 0 until 16) {
            val angle = TAU * index / 16
            canvas.drawLine(centerX, centerY, centerX + cos(angle) * width * 1.5f,
                centerY + sin(angle) * width * 1.5f, grid)
        }

        canvas.drawCircle(centerX, centerY, radius * 1.05f, fillPaint(alpha(sea, 0.6f)))
        val rim = radius * 1.05f
        canvas.drawCircle(centerX, centerY, rim, strokePaint(brass, unit(0.008f)))
        val inner = strokePaint(brass, unit(0.002f))
        canvas.drawCircle(centerX, centerY, radius * 0.97f, inner)
        val tick = strokePaint(brass, unit(0.002f))
        for (index in 0 until 64) {
            val angle = TAU * index / 64
            val inner = radius * if (index % 4 == 0) 0.88f else 0.92f
            val outer = radius * 0.97f
            canvas.drawLine(centerX + cos(angle) * inner, centerY + sin(angle) * inner,
                centerX + cos(angle) * outer, centerY + sin(angle) * outer, tick)
        }
        for (index in 0 until 8) {
            val angle = TAU * (index + 0.5f) / 8
            val dark = shade(brass, 0.6f)
            compassPoint(cover, centerX, centerY, angle, radius * 0.5f, brass, dark)
        }
        for (index in 0 until 4) {
            val angle = TAU * index / 4 - TAU / 4
            compassPoint(cover, centerX, centerY, angle, radius * 0.9f, cream, brass)
        }
        canvas.drawCircle(centerX, centerY, radius * 0.05f, fillPaint(shade(brass, 0.6f)))

        vignette(0.5f, 0.55f)
        grain(0.04f)
        frame(CoverFrame.Double, brass)
        Typeset(
            ink = cream,
            authorInk = brass,
            titleFont = CoverFont.Serif,
            titleWeight = 700,
            titleTracking = random.range(0.06f, 0.10f),
            titleLeading = 1.05f,
            titleSize = random.range(0.085f, 0.105f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
            rule = Rule.Ornament,
            shadow = 0.5f,
        )
    }

private fun compassPoint(
    cover: CoverCanvas,
    centerX: Float,
    centerY: Float,
    angle: Float,
    length: Float,
    light: Int,
    dark: Int,
) =
    with(cover) {
        val tipX = centerX + cos(angle) * length
        val tipY = centerY + sin(angle) * length
        val sideX = cos(angle + TAU / 4) * length * 0.16f
        val sideY = sin(angle + TAU / 4) * length * 0.16f
        for ((sign, color) in listOf(1f to light, -1f to dark)) {
            val half = Path()
            half.moveTo(centerX, centerY)
            half.lineTo(tipX, tipY)
            half.lineTo(centerX + sideX * sign, centerY + sideY * sign)
            half.close()
            canvas.drawPath(half, fillPaint(color))
        }
    }

internal fun historyLaurel(cover: CoverCanvas): Typeset =
    with(cover) {
        val ground =
            random.pick(listOf(hsv(0.98f, 0.65f, 0.34f), hsv(0.62f, 0.55f, 0.26f),
                hsv(0.40f, 0.45f, 0.22f), hsv(0.08f, 0.35f, 0.20f)))
        val gold = hsv(random.range(0.10f, 0.12f), random.range(0.50f, 0.65f),
            random.range(0.78f, 0.90f))
        verticalGradient(shade(ground, 1.15f), shade(ground, 0.7f), 1.2f)
        texture(0.12f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.58f, 0.62f)
        val radius = width * random.range(0.26f, 0.31f)
        for (side in listOf(-1f, 1f)) {
            laurelBranch(cover, centerX, centerY, radius, side, gold)
        }
        val medal = radius * 0.55f
        canvas.drawCircle(centerX, centerY, medal, strokePaint(gold, unit(0.004f)))
        canvas.drawPath(starPath(centerX, centerY, radius * 0.30f, radius * 0.12f,
            random.pick(listOf(5, 8)), -TAU / 4), fillPaint(gold))

        vignette(0.5f, 0.55f)
        grain(0.04f)
        frame(CoverFrame.Double, gold)
        Typeset(
            ink = gold,
            authorInk = mix(gold, Color.WHITE, 0.3f),
            titleFont = CoverFont.Serif,
            titleWeight = 600,
            titleTracking = random.range(0.10f, 0.16f),
            titleLeading = 1.12f,
            titleSize = random.range(0.07f, 0.09f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            shadow = 0.4f,
        )
    }

private fun laurelBranch(
    cover: CoverCanvas,
    centerX: Float,
    centerY: Float,
    radius: Float,
    side: Float,
    gold: Int,
) =
    with(cover) {
        val leaves = random.between(9, 12)
        val stem = Path()
        val leafPaint = fillPaint(gold)
        for (index in 0..leaves) {
            val progress = index / leaves.toFloat()
            val theta = PI.toFloat() * (0.5f + side * (0.08f + progress * 0.78f))
            val x = centerX + cos(theta) * radius
            val y = centerY + sin(theta) * radius
            if (index == 0) stem.moveTo(x, y) else stem.lineTo(x, y)
            val heading = Math.toDegrees(theta.toDouble()).toFloat() + side * 90f
            val size = radius * 0.28f * (1f - progress * 0.4f)
            for (flare in listOf(-38f, 38f)) {
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(heading + flare)
                canvas.drawOval(RectF(0f, -size * 0.2f, size, size * 0.2f), leafPaint)
                canvas.restore()
            }
        }
        canvas.drawPath(stem, strokePaint(gold, unit(0.004f)))
    }

internal fun mysteryBlinds(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.08f, 0.55f, 0.95f, 0.12f))
        val wall = hsv(hue, random.range(0.35f, 0.55f), random.range(0.10f, 0.16f))
        val light = hsv(hue, random.range(0.25f, 0.45f), random.range(0.85f, 0.95f))
        canvas.drawRect(0f, 0f, width, height, fillPaint(wall))
        val lightY = height * random.range(0.40f, 0.55f)
        radialGlow(width * random.range(0.3f, 0.7f), lightY, width * 1.1f, light, 0.22f)

        canvas.save()
        canvas.rotate(random.range(12f, 24f) * random.sign(), width / 2f, height / 2f)
        val slat = height / random.between(14, 20)
        val paint = fillPaint(light)
        paint.maskFilter = BlurMaskFilter(slat * 0.12f, BlurMaskFilter.Blur.NORMAL)
        val left = width * random.range(-0.1f, 0.2f)
        val right = left + width * random.range(0.8f, 1.1f)
        var y = height * 0.2f
        while (y < height * 1.1f) {
            val strength = (1f - abs(y - lightY) / (height * 0.55f)).coerceIn(0f, 1f)
            paint.alpha = (strength * 130).toInt()
            canvas.drawRect(left, y, right, y + slat * 0.55f, paint)
            y += slat
        }
        canvas.restore()

        val figureX = width * random.range(0.3f, 0.7f)
        val headY = height * random.range(0.64f, 0.70f)
        silhouette(cover, figureX, headY, width * 0.05f, shade(wall, 0.35f))
        vignette(0.6f, 0.5f)
        grain(0.07f)
        Typeset(
            ink = Color.rgb(240, 236, 228),
            authorInk = light,
            titleFont = CoverFont.Condensed,
            titleWeight = 700,
            titleTracking = 0.06f,
            titleLeading = 0.98f,
            titleSize = random.range(0.10f, 0.13f),
            authorFont = CoverFont.Typewriter,
            authorTracking = 0.16f,
            shadow = 0.6f,
        )
    }

private fun silhouette(
    cover: CoverCanvas,
    x: Float,
    headY: Float,
    head: Float,
    color: Int,
) =
    with(cover) {
        val paint = fillPaint(color)
        val coat = Path()
        coat.moveTo(x - head * 1.6f, headY + head * 1.7f)
        coat.lineTo(x + head * 1.6f, headY + head * 1.7f)
        coat.lineTo(x + head * 2.3f, height)
        coat.lineTo(x - head * 2.3f, height)
        coat.close()
        canvas.drawPath(coat, paint)
        canvas.drawOval(RectF(x - head * 1.6f, headY + head * 1.1f, x + head * 1.6f,
            headY + head * 2.4f), paint)
        canvas.drawCircle(x, headY, head, paint)
        canvas.drawOval(RectF(x - head * 1.7f, headY - head * 0.75f, x + head * 1.7f,
            headY - head * 0.35f), paint)
        val crown = RectF(x - head * 0.95f, headY - head * 1.7f, x + head * 0.95f,
            headY - head * 0.5f)
        canvas.drawRoundRect(crown, head * 0.3f, head * 0.3f, paint)
    }

private val CAMEO_GROUNDS = listOf(0.40f, 0.52f, 0.62f, 0.98f, 0.08f)

internal fun biographyCameo(cover: CoverCanvas): Typeset =
    with(cover) {
        val ground = hsv(random.pick(CAMEO_GROUNDS), random.range(0.35f, 0.55f),
            random.range(0.30f, 0.45f))
        val cream = hsv(random.range(0.08f, 0.12f), 0.14f, 0.95f)
        val gold = hsv(random.range(0.10f, 0.12f), 0.55f, 0.85f)
        verticalGradient(shade(ground, 1.15f), shade(ground, 0.75f), 1f)
        texture(0.1f)

        val centerX = width / 2f
        val centerY = height * 0.60f
        val oval = RectF(centerX - width * 0.26f, centerY - width * 0.34f,
            centerX + width * 0.26f, centerY + width * 0.34f)
        canvas.drawOval(RectF(oval).apply { inset(-unit(0.03f), -unit(0.03f)) },
            fillPaint(gold))
        canvas.drawOval(oval, fillPaint(cream))
        canvas.save()
        canvas.clipPath(Path().apply { addOval(oval, Path.Direction.CW) })
        canvas.drawPath(profile(centerX, centerY + width * 0.02f, width * 0.24f,
            random.chance(0.5f)), fillPaint(shade(ground, 0.6f)))
        canvas.restore()
        canvas.drawOval(RectF(oval).apply { inset(-unit(0.015f), -unit(0.015f)) },
            strokePaint(shade(gold, 0.7f), unit(0.003f)))

        vignette(0.4f, 0.6f)
        grain(0.03f)
        frame(CoverFrame.Hairline, gold)
        Typeset(
            ink = cream,
            authorInk = gold,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 700)),
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.04f, 0.10f),
            titleSize = random.range(0.08f, 0.10f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Line,
            shadow = 0.4f,
        )
    }

private val PROFILE =
    listOf(
        0.42f to -0.35f, 0.46f to -0.27f, 0.60f to -0.03f, 0.48f to 0.04f, 0.51f to 0.12f,
        0.46f to 0.17f, 0.49f to 0.22f, 0.43f to 0.36f, 0.34f to 0.44f, 0.20f to 0.48f,
        0.24f to 0.90f, 0.95f to 1.30f, 0.95f to 2f, -0.95f to 2f, -0.95f to 1.30f,
        -0.36f to 0.90f,
    )

private fun profile(
    centerX: Float,
    centerY: Float,
    size: Float,
    mirrored: Boolean,
): Path {
    val facing = if (mirrored) -1f else 1f
    val path = Path()
    path.moveTo(centerX - 0.36f * size * facing, centerY + 0.9f * size)
    path.cubicTo(centerX - 0.62f * size * facing, centerY + 0.2f * size,
        centerX - 0.66f * size * facing, centerY - 0.72f * size,
        centerX - 0.04f * size * facing, centerY - 0.86f * size)
    path.cubicTo(centerX + 0.34f * size * facing, centerY - 0.92f * size,
        centerX + 0.44f * size * facing, centerY - 0.58f * size,
        centerX + 0.42f * size * facing, centerY - 0.35f * size)
    for ((x, y) in PROFILE) path.lineTo(centerX + x * size * facing, centerY + y * size)
    path.close()
    return path
}
