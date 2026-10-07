package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal fun spiritualMandala(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.62f, 0.70f, 0.78f, 0.48f, 0.95f))
    val deep = hsv(hue, random.range(0.55f, 0.75f), random.range(0.16f, 0.24f))
    val gold = hsv(
        random.range(0.10f, 0.13f),
        random.range(0.45f, 0.65f),
        random.range(0.85f, 0.95f),
    )
    canvas.drawRect(0f, 0f, width, height, fillPaint(deep))

    val centerX = width * 0.5f
    val centerY = height * random.range(0.58f, 0.62f)
    val radius = width * random.range(0.34f, 0.39f)
    val layers = 3
    for (layer in 0 until layers) {
        petalRing(
            cover,
            centerX,
            centerY,
            radius * (1f - layer / layers.toFloat()),
            random.pick(listOf(8, 12)),
            layer % 2 == 1,
            gold,
            deep,
        )
    }
    canvas.drawCircle(centerX, centerY, radius * 0.07f, fillPaint(gold))

    Typeset(
        ink = gold,
        authorInk = mix(gold, Color.WHITE, 0.3f),
        upper = true,
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
) = with(cover) {
    val length = reach * 0.5f
    val breadth = length * random.range(0.30f, 0.45f)
    val paint =
        if (filled) fillPaint(gold) else strokePaint(gold, unit(0.005f))
    val offset = if (random.chance(0.5f)) 0.5f else 0f
    val oval = RectF(
        centerX - breadth / 2f,
        centerY - reach,
        centerX + breadth / 2f,
        centerY - reach + length,
    )
    for (index in 0 until petals) {
        canvas.save()
        canvas.rotate(360f * (index + offset) / petals, centerX, centerY)
        canvas.drawOval(oval, fillPaint(deep))
        canvas.drawOval(oval, paint)
        canvas.restore()
    }
}

internal fun natureLeaves(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(
        random.range(0.10f, 0.14f),
        random.range(0.08f, 0.16f),
        random.range(0.93f, 0.97f),
    )
    val greens = List(2) {
        hsv(
            random.range(0.24f, 0.42f),
            random.range(0.40f, 0.75f),
            random.range(0.30f, 0.70f),
        )
    }
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

    var placed = 0
    repeat(200) {
        val x = random.range(-0.05f, 1.05f) * width
        val y = random.range(0.12f, 1.05f) * height
        val spreadX = (x - width / 2f) / (width * 0.42f)
        val spreadY = (y - height * 0.5f) / (height * 0.22f)
        if (placed >= 14 || spreadX * spreadX + spreadY * spreadY < 1f) return@repeat
        if (y < height * 0.2f && abs(spreadX) < 0.9f) return@repeat
        val outward = Math.toDegrees(atan2(spreadY, spreadX).toDouble()).toFloat()
        leaf(
            cover,
            x,
            y,
            width * random.range(0.20f, 0.32f),
            outward + random.range(-40f, 40f),
            random.pick(greens),
        )
        placed++
    }

    Typeset(
        ink = hsv(0.36f, 0.55f, 0.20f),
        authorInk = hsv(0.36f, 0.45f, 0.28f),
        upper = true,
        anchor = Anchor.Center,
    )
}

private fun leaf(
    cover: CoverCanvas,
    x: Float,
    y: Float,
    length: Float,
    degrees: Float,
    color: Int,
) = with(cover) {
    val half = length * random.range(0.18f, 0.26f)
    canvas.save()
    canvas.translate(x, y)
    canvas.rotate(degrees)
    val shape = Path()
    shape.moveTo(0f, 0f)
    shape.quadTo(length * 0.45f, -half * 2f, length, 0f)
    shape.quadTo(length * 0.45f, half * 2f, 0f, 0f)
    canvas.drawPath(shape, fillPaint(color))
    canvas.restore()
}

internal fun poetryMoons(cover: CoverCanvas): Typeset = with(cover) {
    val night = hsv(
        random.range(0.60f, 0.70f),
        random.range(0.45f, 0.65f),
        random.range(0.14f, 0.22f),
    )
    val pale = hsv(
        random.range(0.10f, 0.14f),
        random.range(0.10f, 0.25f),
        random.range(0.90f, 0.97f),
    )
    canvas.drawRect(0f, 0f, width, height, fillPaint(night))

    val count = random.pick(listOf(5, 7))
    val centerX = width * 0.5f
    val centerY = height * random.range(0.72f, 0.76f)
    val arc = width * 0.36f
    val moon = arc * sin(PI.toFloat() * 0.8f / (2 * (count - 1))) * 0.75f
    for (index in 0 until count) {
        val angle = PI.toFloat() * (1.1f + 0.8f * index / (count - 1f))
        val middle = index == count / 2
        moonPhase(
            cover,
            centerX + cos(angle) * arc,
            centerY + sin(angle) * arc,
            if (middle) moon * 1.2f else moon,
            index / (count - 1f),
            pale,
            night,
        )
    }

    Typeset(
        ink = pale,
        italic = true,
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
) = with(cover) {
    canvas.drawCircle(x, y, radius, fillPaint(pale))
    val offset = radius * 2f * sin(phase * PI.toFloat())
    val direction = if (phase < 0.5f) -1f else 1f
    canvas.save()
    canvas.clipPath(Path().apply { addCircle(x, y, radius, Path.Direction.CW) })
    canvas.drawCircle(
        x + direction * offset, y, radius * 1.02f, fillPaint(mix(night, pale, 0.12f)),
    )
    canvas.restore()
}

internal fun adventureCompass(cover: CoverCanvas): Typeset = with(cover) {
    val sea = hsv(
        random.range(0.50f, 0.58f),
        random.range(0.45f, 0.65f),
        random.range(0.28f, 0.40f),
    )
    val brass = hsv(
        random.range(0.10f, 0.12f),
        random.range(0.45f, 0.60f),
        random.range(0.82f, 0.92f),
    )
    val cream = Color.rgb(244, 236, 216)
    canvas.drawColor(sea)

    val centerX = width * 0.5f
    val centerY = height * random.range(0.58f, 0.63f)
    val radius = width * random.range(0.30f, 0.35f)
    canvas.drawCircle(centerX, centerY, radius * 1.05f, strokePaint(brass, unit(0.008f)))
    for (index in 0 until 4) {
        val angle = TAU * (index + 0.5f) / 4
        compassPoint(
            cover,
            centerX,
            centerY,
            angle,
            radius * 0.5f,
            brass,
            shade(brass, 0.6f),
        )
    }
    for (index in 0 until 4) {
        val angle = TAU * index / 4 - TAU / 4
        compassPoint(cover, centerX, centerY, angle, radius * 0.9f, cream, brass)
    }

    Typeset(
        ink = cream,
        authorInk = brass,
        upper = true,
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
) = with(cover) {
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

internal fun historyLaurel(cover: CoverCanvas): Typeset = with(cover) {
    val ground = random.pick(
        listOf(
            hsv(0.98f, 0.65f, 0.34f),
            hsv(0.62f, 0.55f, 0.26f),
            hsv(0.40f, 0.45f, 0.22f),
            hsv(0.08f, 0.35f, 0.20f),
        ),
    )
    val gold = hsv(
        random.range(0.10f, 0.12f),
        random.range(0.50f, 0.65f),
        random.range(0.78f, 0.90f),
    )
    canvas.drawColor(ground)

    val centerX = width * 0.5f
    val centerY = height * random.range(0.58f, 0.62f)
    val radius = width * random.range(0.26f, 0.31f)
    for (side in listOf(-1f, 1f)) {
        laurelBranch(cover, centerX, centerY, radius, side, gold)
    }
    canvas.drawPath(
        starPath(
            centerX,
            centerY,
            radius * 0.30f,
            radius * 0.12f,
            random.pick(listOf(5, 8)),
            -TAU / 4,
        ),
        fillPaint(gold),
    )

    Typeset(
        ink = gold,
        authorInk = mix(gold, Color.WHITE, 0.3f),
        upper = true,
    )
}

private fun laurelBranch(
    cover: CoverCanvas,
    centerX: Float,
    centerY: Float,
    radius: Float,
    side: Float,
    gold: Int,
) = with(cover) {
    val leaves = 7
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

internal fun mysteryBlinds(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.08f, 0.55f, 0.95f, 0.12f))
    val wall = hsv(hue, random.range(0.35f, 0.55f), random.range(0.10f, 0.16f))
    val light = hsv(hue, random.range(0.25f, 0.45f), random.range(0.85f, 0.95f))
    canvas.drawRect(0f, 0f, width, height, fillPaint(wall))
    val lightY = height * random.range(0.40f, 0.55f)

    canvas.save()
    canvas.rotate(random.range(12f, 24f) * random.sign(), width / 2f, height / 2f)
    val slat = height / random.between(10, 13)
    val paint = fillPaint(light)
    val left = width * random.range(-0.1f, 0.2f)
    val right = left + width * random.range(0.8f, 1.1f)
    var y = height * 0.2f
    while (y < height * 1.1f) {
        val strength = (1f - abs(y - lightY) / (height * 0.55f)).coerceIn(0f, 1f)
        paint.alpha = (strength * 110).toInt()
        canvas.drawRect(left, y, right, y + slat * 0.55f, paint)
        y += slat
    }
    canvas.restore()

    val figureX = width * random.range(0.3f, 0.7f)
    val headY = height * random.range(0.64f, 0.70f)
    silhouette(cover, figureX, headY, width * 0.05f, shade(wall, 0.35f))
    Typeset(
        ink = Color.rgb(240, 236, 228),
        authorInk = light,
        font = CoverFont.Condensed,
        upper = true,
    )
}

private fun silhouette(
    cover: CoverCanvas,
    x: Float,
    headY: Float,
    head: Float,
    color: Int,
) = with(cover) {
    val paint = fillPaint(color)
    val coat = Path()
    coat.moveTo(x - head * 1.6f, headY + head * 1.7f)
    coat.lineTo(x + head * 1.6f, headY + head * 1.7f)
    coat.lineTo(x + head * 2.3f, height)
    coat.lineTo(x - head * 2.3f, height)
    coat.close()
    canvas.drawPath(coat, paint)
    canvas.drawOval(
        RectF(x - head * 1.6f, headY + head * 1.1f, x + head * 1.6f, headY + head * 2.4f),
        paint,
    )
    canvas.drawCircle(x, headY, head, paint)
    canvas.drawOval(
        RectF(
            x - head * 1.7f, headY - head * 0.75f, x + head * 1.7f, headY - head * 0.35f,
        ),
        paint,
    )
    val crown = RectF(
        x - head * 0.95f, headY - head * 1.7f, x + head * 0.95f, headY - head * 0.5f,
    )
    canvas.drawRoundRect(crown, head * 0.3f, head * 0.3f, paint)
}

private val CAMEO_GROUNDS = listOf(0.40f, 0.52f, 0.62f, 0.98f, 0.08f)

internal fun biographyCameo(cover: CoverCanvas): Typeset = with(cover) {
    val ground = hsv(
        random.pick(CAMEO_GROUNDS),
        random.range(0.35f, 0.55f),
        random.range(0.30f, 0.45f),
    )
    val cream = hsv(random.range(0.08f, 0.12f), 0.14f, 0.95f)
    val gold = hsv(random.range(0.10f, 0.12f), 0.55f, 0.85f)
    canvas.drawColor(ground)

    val centerX = width / 2f
    val centerY = height * 0.60f
    val oval = RectF(
        centerX - width * 0.26f,
        centerY - width * 0.34f,
        centerX + width * 0.26f,
        centerY + width * 0.34f,
    )
    canvas.drawOval(
        RectF(oval).apply { inset(-unit(0.03f), -unit(0.03f)) }, fillPaint(gold),
    )
    canvas.drawOval(oval, fillPaint(cream))
    canvas.save()
    canvas.clipPath(Path().apply { addOval(oval, Path.Direction.CW) })
    canvas.drawPath(
        profile(centerX, centerY + width * 0.02f, width * 0.24f, random.chance(0.5f)),
        fillPaint(shade(ground, 0.6f)),
    )
    canvas.restore()

    Typeset(
        ink = cream,
        authorInk = gold,
        upper = random.chance(0.5f),
    )
}

private val PROFILE = listOf(
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
    path.cubicTo(
        centerX - 0.62f * size * facing,
        centerY + 0.2f * size,
        centerX - 0.66f * size * facing,
        centerY - 0.72f * size,
        centerX - 0.04f * size * facing,
        centerY - 0.86f * size,
    )
    path.cubicTo(
        centerX + 0.34f * size * facing,
        centerY - 0.92f * size,
        centerX + 0.44f * size * facing,
        centerY - 0.58f * size,
        centerX + 0.42f * size * facing,
        centerY - 0.35f * size,
    )
    for ((x, y) in PROFILE) path.lineTo(centerX + x * size * facing, centerY + y * size)
    path.close()
    return path
}
