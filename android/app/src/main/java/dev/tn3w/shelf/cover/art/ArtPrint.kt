package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private class Palette(val paper: Int, val primary: Int, val secondary: Int)

private val SWISS = listOf(
    Palette(Color.rgb(241, 237, 228), Color.rgb(226, 58, 40), Color.rgb(22, 22, 24)),
    Palette(Color.rgb(22, 24, 30), Color.rgb(246, 196, 38), Color.rgb(236, 234, 228)),
    Palette(
        Color.rgb(232, 236, 238), Color.rgb(30, 86, 200), Color.rgb(255, 106, 60),
    ),
    Palette(
        Color.rgb(246, 222, 200), Color.rgb(18, 110, 104), Color.rgb(200, 60, 40),
    ),
    Palette(Color.rgb(28, 40, 72), Color.rgb(255, 120, 90), Color.rgb(244, 236, 220)),
)

internal fun swissGrid(cover: CoverCanvas): Typeset = with(cover) {
    val palette = random.pick(SWISS)
    canvas.drawRect(0f, 0f, width, height, fillPaint(palette.paper))
    val cell = width / 6f

    val centerX = cell * random.between(2, 4)
    val centerY = height * random.range(0.60f, 0.66f)
    val radius = cell * random.range(1.5f, 2.1f)
    canvas.drawCircle(centerX, centerY, radius, fillPaint(palette.primary))

    canvas.save()
    canvas.rotate(random.pick(listOf(-30f, -45f, -60f, 30f)), centerX, centerY)
    canvas.drawRect(
        centerX - radius * 1.4f,
        centerY - cell * 0.07f,
        centerX + radius * 1.4f,
        centerY + cell * 0.07f,
        fillPaint(palette.secondary),
    )
    canvas.restore()
    Typeset(
        ink = palette.secondary,
        font = CoverFont.Sans,
    )
}

private val BAUHAUS = listOf(
    Color.rgb(214, 52, 42),
    Color.rgb(244, 190, 40),
    Color.rgb(34, 76, 160),
    Color.rgb(24, 24, 26),
    Color.rgb(238, 232, 218),
)

internal fun bauhausTiles(cover: CoverCanvas): Typeset = with(cover) {
    canvas.drawRect(0f, 0f, width, height, fillPaint(Color.rgb(240, 234, 220)))
    val columns = random.between(2, 3)
    val cell = width * 0.76f / columns
    val rows = (height * 0.44f / cell).toInt().coerceIn(1, 2)
    val left = width * 0.12f
    val top = height * 0.84f - rows * cell
    for (column in 0 until columns) {
        for (row in 0 until rows) {
            val x = left + column * cell
            val y = top + row * cell
            bauhausTile(cover, RectF(x, y, x + cell, y + cell))
        }
    }
    Typeset(
        ink = BAUHAUS[3],
        authorInk = BAUHAUS[0],
        font = CoverFont.Sans,
        upper = true,
    )
}

private fun bauhausTile(cover: CoverCanvas, box: RectF) = with(cover) {
    val ground = random.pick(BAUHAUS)
    val figure = fillPaint(random.pick(BAUHAUS.filter { it != ground }))
    val size = box.width()
    canvas.drawRect(box, fillPaint(ground))
    canvas.save()
    canvas.clipRect(box)
    canvas.rotate(90f * random.index(4), box.centerX(), box.centerY())
    when (random.index(5)) {
        0 -> canvas.drawCircle(box.centerX(), box.centerY(), size * 0.42f, figure)

        1 -> canvas.drawCircle(box.left, box.top, size, figure)

        2 -> canvas.drawCircle(box.centerX(), box.bottom, size / 2f, figure)

        3 -> {
            val triangle = Path()
            triangle.moveTo(box.left, box.bottom)
            triangle.lineTo(box.right, box.bottom)
            triangle.lineTo(box.left, box.top)
            triangle.close()
            canvas.drawPath(triangle, figure)
        }

        else -> {
            canvas.drawCircle(box.centerX(), box.centerY(), size * 0.20f, figure)
            figure.style = Paint.Style.STROKE
            figure.strokeWidth = size * 0.09f
            canvas.drawCircle(box.centerX(), box.centerY(), size * 0.36f, figure)
        }
    }
    canvas.restore()
}

private val RISO = listOf(
    Color.rgb(255, 72, 176) to Color.rgb(0, 120, 191),
    Color.rgb(255, 108, 47) to Color.rgb(0, 131, 138),
    Color.rgb(0, 169, 92) to Color.rgb(255, 72, 176),
    Color.rgb(255, 232, 0) to Color.rgb(93, 80, 200),
)

internal fun risoHalftone(cover: CoverCanvas): Typeset = with(cover) {
    val (first, second) = random.pick(RISO)
    val paper = Color.rgb(246, 241, 230)
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
    halftone(
        first,
        width * random.range(0.3f, 0.7f),
        height * 0.62f,
        width * random.range(0.50f, 0.56f),
        15f,
    )
    canvas.drawCircle(
        width * random.range(0.40f, 0.60f),
        height * random.range(0.58f, 0.64f),
        width * random.range(0.18f, 0.24f),
        darken(second),
    )

    Typeset(
        ink = mix(second, Color.BLACK, 0.55f),
        authorInk = mix(second, Color.BLACK, 0.4f),
        font = CoverFont.Sans,
        upper = true,
    )
}

private fun darken(color: Int) =
    fillPaint(color).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN) }

private fun CoverCanvas.halftone(
    color: Int,
    centerX: Float,
    centerY: Float,
    reach: Float,
    degrees: Float,
) {
    val pitch = width / 24f
    val paint = darken(color)
    val angle = Math.toRadians(degrees.toDouble()).toFloat()
    val span = (hypot(width, height) / pitch / 2f).toInt() + 1
    for (row in -span..span) {
        for (column in -span..span) {
            val x = width / 2f + column * pitch * cos(angle) - row * pitch * sin(angle)
            val y = height / 2f + column * pitch * sin(angle) + row * pitch * cos(angle)
            val strength = 1f - hypot(x - centerX, y - centerY) / reach
            if (strength <= 0.05f || x < -pitch || x > width + pitch) continue
            canvas.drawCircle(x, y, pitch * 0.66f * strength, paint)
        }
    }
}

internal fun penguinBands(cover: CoverCanvas): Typeset = with(cover) {
    val band = random.pick(
        listOf(
            Color.rgb(238, 112, 36),
            Color.rgb(0, 132, 90),
            Color.rgb(30, 90, 160),
            Color.rgb(200, 40, 90),
            Color.rgb(112, 58, 140),
            Color.rgb(196, 36, 40),
        ),
    )
    val cream = Color.rgb(246, 242, 232)
    canvas.drawRect(0f, 0f, width, height, fillPaint(band))
    val top = height * 0.32f
    val bottom = height * 0.68f
    canvas.drawRect(0f, top, width, bottom, fillPaint(cream))

    Typeset(
        ink = Color.rgb(28, 26, 24),
        authorInk = cream,
        font = CoverFont.Sans,
        anchor = Anchor.Center,
    )
}

internal fun travelStamp(cover: CoverCanvas): Typeset = with(cover) {
    val hue = random.pick(listOf(0.02f, 0.08f, 0.52f, 0.58f, 0.95f))
    val ground = hsv(hue, random.range(0.40f, 0.60f), random.range(0.50f, 0.68f))
    val paper = Color.rgb(248, 244, 234)
    canvas.drawRect(0f, 0f, width, height, fillPaint(ground))

    val stamp = RectF(width * 0.17f, height * 0.37f, width * 0.83f, height * 0.83f)
    perforate(cover, stamp, paper, ground)
    val window = RectF(
        stamp.left + unit(0.05f),
        stamp.top + unit(0.05f),
        stamp.right - unit(0.05f),
        stamp.bottom - unit(0.12f),
    )
    stampScene(cover, window, hue)

    Typeset(
        ink = paper,
        font = CoverFont.Condensed,
        upper = true,
    )
}

private fun perforate(cover: CoverCanvas, box: RectF, paper: Int, ground: Int) =
    with(cover) {
        canvas.drawRect(box, fillPaint(paper))
        val hole = unit(0.018f)
        val punch = fillPaint(ground)
        val across = (box.width() / (hole * 2.6f)).roundToInt()
        val down = (box.height() / (hole * 2.6f)).roundToInt()
        for (index in 0..across) {
            val x = box.left + box.width() * index / across
            canvas.drawCircle(x, box.top, hole, punch)
            canvas.drawCircle(x, box.bottom, hole, punch)
        }
        for (index in 0..down) {
            val y = box.top + box.height() * index / down
            canvas.drawCircle(box.left, y, hole, punch)
            canvas.drawCircle(box.right, y, hole, punch)
        }
    }

private fun stampScene(cover: CoverCanvas, window: RectF, hue: Float) = with(cover) {
    canvas.save()
    canvas.clipRect(window)
    canvas.drawRect(window, fillPaint(hsv(random.range(0.07f, 0.11f), 0.30f, 0.99f)))
    val horizon = window.top + window.height() * 0.66f
    canvas.drawCircle(
        window.centerX() + window.width() * random.range(-0.2f, 0.2f),
        window.top + window.height() * 0.42f,
        window.width() * 0.18f,
        fillPaint(hsv(random.range(0.02f, 0.07f), 0.70f, 0.95f)),
    )

    val profile = displacedLine(random, 0f, 0f, random.range(0.10f, 0.18f))
    val step = window.width() / (profile.size - 1)
    val peaks = Path()
    peaks.moveTo(window.left, window.bottom)
    for (index in profile.indices) {
        peaks.lineTo(
            window.left + index * step,
            horizon - abs(profile[index]) * window.height() * 1.2f,
        )
    }
    peaks.lineTo(window.right, window.bottom)
    peaks.close()
    canvas.drawPath(peaks, fillPaint(hsv(hue, 0.35f, 0.42f)))

    val sea = hsv(random.range(0.50f, 0.60f), 0.50f, 0.50f)
    canvas.drawRect(window.left, horizon, window.right, window.bottom, fillPaint(sea))
    canvas.restore()
}

internal fun seigaihaWaves(cover: CoverCanvas): Typeset = with(cover) {
    val indigo = hsv(
        random.range(0.58f, 0.64f),
        random.range(0.55f, 0.75f),
        random.range(0.30f, 0.45f),
    )
    val paper = hsv(
        random.range(0.08f, 0.12f),
        random.range(0.08f, 0.16f),
        random.range(0.93f, 0.97f),
    )
    canvas.drawRect(0f, 0f, width, height, fillPaint(paper))
    var y = height * random.range(0.56f, 0.62f)
    val sun = width * random.range(0.13f, 0.17f)
    val sunY = y - sun * random.range(0.2f, 0.6f)
    val red = hsv(random.range(0f, 0.03f), 0.78f, 0.86f)
    canvas.drawCircle(width * random.range(0.25f, 0.75f), sunY, sun, fillPaint(red))

    val radius = width / random.between(4, 5)
    var row = 0
    while (y < height + radius) {
        var x = if (row % 2 == 0) 0f else radius
        while (x <= width + radius) {
            scallop(cover, x, y, radius, paper, indigo)
            x += radius * 2f
        }
        y += radius * 0.5f
        row++
    }
    canvas.drawRect(0f, height * 0.85f, width, height, fillPaint(indigo))

    Typeset(
        ink = indigo,
        authorInk = paper,
        upper = true,
    )
}

private fun scallop(
    cover: CoverCanvas,
    centerX: Float,
    centerY: Float,
    radius: Float,
    paper: Int,
    indigo: Int,
) = with(cover) {
    canvas.drawCircle(centerX, centerY, radius, fillPaint(paper))
    val paint = strokePaint(indigo, radius * 0.07f)
    for (ring in 0 until 3) {
        canvas.drawCircle(centerX, centerY, radius * (0.9f - ring * 0.28f), paint)
    }
}
