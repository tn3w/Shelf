package dev.tn3w.shelf.cover.art

import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal fun mysteryRedThread(cover: CoverCanvas): Typeset = with(cover) {
    canvas.drawColor(hsv(0.07f, 0.50f, 0.30f))
    val red = hsv(0.99f, 0.85f, 0.85f)
    val notes = listOf(0 to 0, 1 to 1, 0 to 2, 2 to 0, 2 to 2).shuffledBy(cover).take(4)
    val pins = notes.map { (column, row) ->
        val x = width * (0.24f + column * 0.26f + random.range(-0.03f, 0.03f))
        val y = height * (0.46f + row * 0.14f)
        note(cover, x, y)
        x to y
    }
    val thread = strokePaint(red, unit(0.005f))
    for (index in 1 until pins.size) {
        val (fromX, fromY) = pins[index - 1]
        val (toX, toY) = pins[index]
        canvas.drawLine(fromX, fromY, toX, toY, thread)
    }
    for ((x, y) in pins) canvas.drawCircle(x, y, unit(0.016f), fillPaint(red))

    Typeset(ink = Color.rgb(244, 238, 226), upper = true)
}

private fun <T> List<T>.shuffledBy(cover: CoverCanvas) = sortedBy { cover.random.float() }

private fun note(cover: CoverCanvas, x: Float, y: Float) = with(cover) {
    val half = width * 0.09f
    canvas.save()
    canvas.rotate(random.range(-6f, 6f), x, y)
    val paper = random.pick(listOf(Color.rgb(246, 240, 226), Color.rgb(250, 232, 150)))
    canvas.drawRect(
        x - half,
        y - half * 0.4f,
        x + half,
        y + half * 1.6f,
        fillPaint(paper),
    )
    canvas.restore()
}

internal fun scifiRetro(cover: CoverCanvas): Typeset = with(cover) {
    val horizon = height * random.range(0.66f, 0.70f)
    val magenta = hsv(random.range(0.86f, 0.93f), 0.85f, 1f)
    verticalGradient(hsv(0.72f, 0.80f, 0.12f), hsv(0.86f, 0.75f, 0.45f), 1.6f)

    val radius = width * random.range(0.26f, 0.32f)
    val sunY = horizon - radius * random.range(0.2f, 0.4f)
    val stripes = Path()
    var stripe = sunY + radius * 0.1f
    var gap = radius * 0.04f
    while (stripe < sunY + radius) {
        stripes.addRect(0f, stripe, width, stripe + gap, Path.Direction.CW)
        stripe += gap + radius * 0.12f
        gap *= 1.4f
    }
    val sun = fillPaint(Color.WHITE)
    sun.shader = LinearGradient(
        0f,
        sunY - radius,
        0f,
        sunY + radius,
        hsv(0.14f, 0.8f, 1f),
        hsv(0.93f, 0.8f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.save()
    canvas.clipOutPath(stripes)
    canvas.drawCircle(width / 2f, sunY, radius, sun)
    canvas.restore()

    canvas.drawRect(0f, horizon, width, height, fillPaint(hsv(0.74f, 0.85f, 0.10f)))
    val grid = strokePaint(alpha(magenta, 0.8f), unit(0.003f))
    for (index in 0..6) {
        val fraction = index / 6f
        val y = horizon + (height - horizon) * fraction * fraction
        canvas.drawLine(0f, y, width, y, grid)
    }
    for (index in -5..5) {
        canvas.drawLine(
            width / 2f + index * width * 0.1f,
            horizon,
            width / 2f + index * width * 0.7f,
            height,
            grid,
        )
    }

    Typeset(
        ink = Color.rgb(255, 236, 250),
        authorInk = hsv(0.52f, 0.5f, 1f),
        font = random.pick(listOf(CoverFont.Sans, CoverFont.Condensed)),
        italic = true,
        upper = true,
    )
}

internal fun dystopianBarcode(cover: CoverCanvas): Typeset = with(cover) {
    val alarm = hsv(random.pick(listOf(1f, 0.03f, 0.08f)), 0.85f, 0.85f)
    canvas.drawColor(
        hsv(random.range(0f, 1f), random.range(0.02f, 0.10f), random.range(0.14f, 0.20f)),
    )

    val label = RectF(width * 0.18f, height * 0.46f, width * 0.82f, height * 0.74f)
    canvas.drawRect(label, fillPaint(Color.rgb(232, 230, 224)))
    val bars = RectF(
        label.left + unit(0.05f),
        label.top + unit(0.06f),
        label.right - unit(0.05f),
        label.bottom - unit(0.12f),
    )
    val ink = fillPaint(Color.rgb(16, 16, 18))
    var x = bars.left
    while (x < bars.right) {
        val bar = unit(0.006f) * random.between(1, 4)
        if (random.chance(0.55f)) {
            canvas.drawRect(
                x,
                bars.top,
                (x + bar).coerceAtMost(bars.right),
                bars.bottom,
                ink,
            )
        }
        x += bar
    }
    val digits = Paint(ink).apply {
        typeface = Typeface.MONOSPACE
        textSize = unit(0.045f)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.3f
    }
    val serial = (1..10).joinToString("") { random.between(0, 9).toString() }
    canvas.drawText(serial, label.centerX(), label.bottom - unit(0.04f), digits)

    Typeset(
        ink = Color.rgb(238, 236, 232),
        authorInk = alarm,
        font = CoverFont.Condensed,
        upper = true,
    )
}

internal fun romanceLetter(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.96f, 0.99f, 0.03f, 0.92f))
    val blush = hsv(hue, random.range(0.16f, 0.26f), random.range(0.94f, 0.99f))
    val wax = hsv(hue, random.range(0.75f, 0.90f), random.range(0.55f, 0.70f))
    canvas.drawColor(blush)

    val envelope = RectF(width * 0.18f, height * 0.48f, width * 0.82f, height * 0.76f)
    canvas.drawRect(envelope, fillPaint(Color.rgb(250, 246, 238)))
    val fold = strokePaint(mix(blush, wax, 0.25f), unit(0.004f))
    val flapY = envelope.top + envelope.height() * 0.55f
    canvas.drawLine(envelope.left, envelope.top, envelope.centerX(), flapY, fold)
    canvas.drawLine(envelope.right, envelope.top, envelope.centerX(), flapY, fold)
    canvas.drawCircle(envelope.centerX(), flapY, unit(0.06f), fillPaint(wax))

    Typeset(ink = mix(wax, Color.rgb(40, 20, 30), 0.6f), italic = true)
}

internal fun humorPop(cover: CoverCanvas): Typeset = with(cover) {
    val ground = hsv(random.pick(listOf(0.52f, 0.55f, 0.95f, 0.33f)), 0.55f, 0.95f)
    val burst = hsv(random.range(0.12f, 0.15f), 0.85f, 1f)
    val core = hsv(random.range(0f, 0.03f), 0.85f, 0.95f)
    val ink = Color.rgb(20, 20, 24)
    canvas.drawColor(ground)

    val centerX = width * 0.5f
    val centerY = height * 0.58f
    val outline = strokePaint(ink, unit(0.012f))
    val points = random.between(12, 16)
    val turn = random.range(0f, 1f)
    val big = starPath(centerX, centerY, width * 0.34f, width * 0.25f, points, turn)
    canvas.drawPath(big, fillPaint(burst))
    canvas.drawPath(big, outline)
    canvas.drawCircle(centerX, centerY, width * 0.13f, fillPaint(core))
    canvas.drawCircle(centerX, centerY, width * 0.13f, outline)

    Typeset(ink = ink, font = CoverFont.Condensed, upper = true)
}

internal fun businessGraph(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(random.range(0.55f, 0.65f), 0.04f, 0.97f)
    val accent = hsv(random.pick(listOf(0.02f, 0.33f, 0.58f, 0.08f, 0.75f)), 0.75f, 0.80f)
    val ink = hsv(0.62f, 0.35f, 0.16f)
    canvas.drawColor(paper)

    val chart = RectF(width * 0.14f, height * 0.44f, width * 0.86f, height * 0.80f)
    val count = random.between(5, 7)
    var level = random.range(0.1f, 0.25f)
    val line = Path()
    for (index in 0 until count) {
        level = (level + random.range(-0.06f, 0.22f)).coerceIn(0.05f, 0.95f)
        val x = chart.left + chart.width() * index / (count - 1f)
        val y = chart.bottom - chart.height() * if (index == count - 1) 0.95f else level
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    canvas.drawPath(line, strokePaint(accent, unit(0.012f)))
    canvas.drawCircle(
        chart.right,
        chart.top + chart.height() * 0.05f,
        unit(0.025f),
        fillPaint(accent),
    )
    canvas.drawLine(
        chart.left, chart.bottom, chart.right, chart.bottom,
        strokePaint(
            ink,
            unit(0.004f),
        ),
    )

    Typeset(
        ink = ink,
        authorInk = shade(accent, 0.8f),
        font = CoverFont.Sans,
        upper = true,
    )
}

internal fun vintageDeco(cover: CoverCanvas): Typeset = with(cover) {
    val night = random.pick(
        listOf(
            Color.rgb(14, 14, 16),
            hsv(0.60f, 0.6f, 0.14f),
            hsv(0.40f, 0.5f, 0.12f),
            hsv(0.98f, 0.6f, 0.16f),
        ),
    )
    val gold = hsv(random.range(0.10f, 0.12f), 0.55f, 0.86f)
    canvas.drawColor(night)

    val centerX = width / 2f
    val centerY = height * 0.82f
    val radius = width * 0.42f
    val rays = random.pick(listOf(7, 9, 11))
    for (index in 0 until rays) {
        val start = 180f + 180f * index / rays
        canvas.drawArc(
            circle(centerX, centerY, radius),
            start,
            90f / rays,
            true,
            fillPaint(gold),
        )
    }
    canvas.drawCircle(centerX, centerY, radius * 0.24f, fillPaint(gold))
    canvas.drawRect(0f, centerY, width, height, fillPaint(night))

    Typeset(
        ink = gold,
        font = random.pick(listOf(CoverFont.Condensed, CoverFont.Sans)),
        upper = true,
    )
}

internal fun literaryFields(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.float()
    val dark = random.chance(0.5f)
    canvas.drawColor(if (dark) hsv(hue, 0.55f, 0.22f) else hsv(hue, 0.30f, 0.84f))
    val fields = random.between(2, 3)
    val top = height * 0.40f
    val span = (height * 0.86f - top) / fields
    for (index in 0 until fields) {
        val tone = hsv(
            hue + random.range(-0.08f, 0.08f),
            random.range(0.45f, 0.75f),
            if (dark) random.range(0.40f, 0.70f) else random.range(0.55f, 0.90f),
        )
        val paint = fillPaint(tone)
        paint.maskFilter = BlurMaskFilter(unit(0.012f), BlurMaskFilter.Blur.NORMAL)
        val y = top + span * index
        canvas.drawRect(
            width * 0.12f,
            y + unit(0.02f),
            width * 0.88f,
            y + span * 0.88f,
            paint,
        )
    }
    Typeset(
        ink = if (dark) Color.rgb(240, 234, 224) else hsv(hue, 0.5f, 0.15f),
        upper = random.chance(0.5f),
    )
}

internal fun technicalAtom(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.55f, 0.60f, 0.48f, 0.75f))
    val accent = hsv(hue, 0.5f, 1f)
    canvas.drawColor(hsv(hue + 0.05f, 0.7f, 0.14f))
    val centerX = width * 0.5f
    val centerY = height * 0.60f
    val reach = width * random.range(0.34f, 0.38f)

    val orbit = RectF(
        centerX - reach, centerY - reach * 0.3f, centerX + reach, centerY + reach * 0.3f,
    )
    for (index in 0 until 3) {
        canvas.save()
        canvas.rotate(60f * index + 30f, centerX, centerY)
        canvas.drawOval(orbit, strokePaint(accent, unit(0.005f)))
        val angle = random.range(0f, TAU)
        canvas.drawCircle(
            centerX + cos(angle) * reach,
            centerY + sin(angle) * reach * 0.3f,
            unit(0.018f),
            fillPaint(accent),
        )
        canvas.restore()
    }
    canvas.drawCircle(centerX, centerY, unit(0.05f), fillPaint(hsv(0.02f, 0.7f, 0.95f)))

    Typeset(
        ink = Color.rgb(236, 244, 252),
        authorInk = accent,
        font = CoverFont.Sans,
        upper = true,
    )
}

internal fun travelRoute(cover: CoverCanvas): Typeset = with(cover) {
    val sea = hsv(random.range(0.50f, 0.58f), random.range(0.12f, 0.24f), 0.95f)
    val land = hsv(random.range(0.50f, 0.60f), 0.45f, 0.55f)
    val accent = hsv(random.pick(listOf(0.01f, 0.06f, 0.95f)), 0.8f, 0.9f)
    canvas.drawColor(sea)

    val blobs = List(random.between(3, 4)) {
        Triple(
            random.range(0.15f, 0.85f) * width,
            random.range(0.45f, 0.85f) * height,
            width * random.range(0.14f, 0.22f),
        )
    }
    val pitch = width / 26f
    val dot = fillPaint(land)
    var y = height * 0.38f
    while (y < height * 0.90f) {
        var x = pitch / 2f
        while (x < width) {
            val mass = blobs.sumOf { (blobX, blobY, spread) ->
                val offsetX = (x - blobX) / spread
                val offsetY = (y - blobY) / spread
                exp(-(offsetX * offsetX + offsetY * offsetY).toDouble())
            }
            if (mass > 0.5) canvas.drawCircle(x, y, pitch * 0.3f, dot)
            x += pitch
        }
        y += pitch
    }

    val (startX, startY) = blobs[0].first to blobs[0].second
    val (endX, endY) = blobs[1].first to blobs[1].second
    val route = Path()
    route.moveTo(startX, startY)
    val peak = minOf(startY, endY) - height * random.range(0.10f, 0.16f)
    route.quadTo((startX + endX) / 2f, peak, endX, endY)
    val dashed = strokePaint(accent, unit(0.006f))
    dashed.pathEffect = DashPathEffect(floatArrayOf(unit(0.02f), unit(0.015f)), 0f)
    canvas.drawPath(route, dashed)
    canvas.drawCircle(startX, startY, unit(0.02f), fillPaint(accent))
    canvas.drawCircle(endX, endY, unit(0.02f), fillPaint(accent))

    Typeset(ink = shade(land, 0.45f), font = CoverFont.Condensed, upper = true)
}

private fun circle(centerX: Float, centerY: Float, radius: Float) =
    RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
