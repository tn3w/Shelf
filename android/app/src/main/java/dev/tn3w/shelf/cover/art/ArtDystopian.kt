package dev.tn3w.shelf.cover.art

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.sin

private class Concrete(val ground: Int, val alarm: Int)

private fun concrete(cover: CoverCanvas): Concrete = with(cover) {
    val palette = Concrete(
        hsv(
            random.range(0.04f, 0.62f),
            random.range(0.04f, 0.14f),
            random.range(0.14f, 0.26f),
        ),
        hsv(
            random.pick(listOf(1.0f, 1.0f, 1.0f, 0.03f, 0.08f)),
            random.range(0.75f, 0.95f),
            random.range(0.75f, 0.92f),
        ),
    )
    canvas.drawColor(palette.ground)
    palette
}

private fun dystopianType(cover: CoverCanvas, alarm: Int): Typeset = with(cover) {
    Typeset(
        ink = Color.rgb(238, 236, 232),
        authorInk = alarm,
        font = random.pick(listOf(CoverFont.Condensed, CoverFont.Sans)),
        upper = true,
    )
}

internal fun dystopianMonolith(cover: CoverCanvas): Typeset = with(cover) {
    val palette = concrete(cover)
    val center = width * 0.5f
    val half = random.range(0.32f, 0.38f) * width
    val top = random.range(0.42f, 0.48f) * height
    val tiers = random.between(3, 5)
    val floor = height * 0.86f
    val tierHeight = (floor - top) / tiers
    val ziggurat = Path()
    for (tier in 0 until tiers) {
        val spread = half * (1f - tier * 0.7f / tiers)
        val base = floor - tierHeight * tier
        ziggurat.addRect(
            center - spread, base - tierHeight, center + spread, base, Path.Direction.CW,
        )
    }
    canvas.drawPath(ziggurat, fillPaint(hsv(0.58f, 0.05f, random.range(0.72f, 0.85f))))
    canvas.save()
    canvas.clipPath(ziggurat)
    canvas.drawRect(
        center,
        top,
        center + half,
        height,
        fillPaint(alpha(Color.BLACK, 0.3f)),
    )
    canvas.restore()
    dystopianType(cover, palette.alarm)
}

internal fun dystopianEye(cover: CoverCanvas): Typeset = with(cover) {
    val palette = concrete(cover)
    val centerX = width * 0.5f
    val centerY = height * 0.58f
    val radius = width * random.range(0.17f, 0.22f)
    val spread = random.range(1.8f, 2.4f)
    val rise = random.range(0.95f, 1.25f)

    val lid = Path()
    lid.moveTo(centerX - radius * spread, centerY)
    lid.lineTo(centerX, centerY - radius * rise)
    lid.lineTo(centerX + radius * spread, centerY)
    lid.lineTo(centerX, centerY + radius * rise)
    lid.close()
    canvas.drawPath(lid, fillPaint(Color.rgb(10, 10, 12)))
    canvas.drawCircle(centerX, centerY, radius * 0.8f, fillPaint(palette.alarm))
    canvas.drawCircle(
        centerX,
        centerY,
        radius * random.range(0.28f, 0.36f),
        fillPaint(Color.BLACK),
    )
    dystopianType(cover, palette.alarm)
}

internal fun dystopianBlocks(cover: CoverCanvas): Typeset = with(cover) {
    val palette = concrete(cover)
    val columns = random.between(4, 6)
    val rows = random.between(5, 7)
    val margin = width * 0.12f
    val cellWidth = (width - margin * 2f) / columns
    val top = height * 0.40f
    val cellHeight = (height * 0.84f - top) / rows
    val markedColumn = random.index(columns)
    val markedRow = random.index(rows)
    val tone = mix(palette.ground, Color.BLACK, 0.45f)
    for (column in 0 until columns) {
        for (row in 0 until rows) {
            val x = margin + cellWidth * column
            val y = top + cellHeight * row
            val marked = column == markedColumn && row == markedRow
            canvas.drawRect(
                x + cellWidth * 0.1f,
                y + cellHeight * 0.1f,
                x + cellWidth * 0.9f,
                y + cellHeight * 0.9f,
                fillPaint(if (marked) palette.alarm else tone),
            )
        }
    }
    dystopianType(cover, palette.alarm)
}

internal fun dystopianGlitch(cover: CoverCanvas): Typeset = with(cover) {
    val palette = concrete(cover)
    val centerX = width * 0.5f
    val centerY = height * 0.58f
    val size = width * random.range(0.20f, 0.26f)
    val shift = unit(random.range(0.015f, 0.025f))
    val shape = random.index(3)
    glitchShape(cover, shape, centerX - shift, centerY, size, Color.rgb(40, 220, 230))
    glitchShape(cover, shape, centerX + shift, centerY, size, palette.alarm)
    glitchShape(cover, shape, centerX, centerY, size, Color.rgb(12, 12, 14))

    val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    repeat(3) {
        val top = centerY + size * random.range(-0.9f, 0.7f)
        val slice = height * random.range(0.01f, 0.025f)
        val offset = unit(random.range(0.05f, 0.10f)) * random.sign()
        val source = Rect(0, top.toInt(), width.toInt(), (top + slice).toInt())
        canvas.drawBitmap(
            copy,
            source,
            RectF(offset, top, width + offset, top + slice),
            null,
        )
    }
    copy.recycle()
    dystopianType(cover, palette.alarm)
}

private fun glitchShape(
    cover: CoverCanvas,
    shape: Int,
    x: Float,
    y: Float,
    size: Float,
    color: Int,
) = with(cover) {
    val paint = fillPaint(color)
    when (shape) {
        0 -> canvas.drawCircle(x, y, size, paint)

        1 -> {
            val pyramid = Path()
            pyramid.moveTo(x, y - size * 1.1f)
            pyramid.lineTo(x + size * 1.1f, y + size * 0.8f)
            pyramid.lineTo(x - size * 1.1f, y + size * 0.8f)
            pyramid.close()
            canvas.drawPath(pyramid, paint)
        }

        else -> canvas.drawRect(
            x - size * 0.45f, y - size * 1.1f, x + size * 0.45f, y + size * 1.1f, paint,
        )
    }
}

internal fun dystopianPropaganda(cover: CoverCanvas): Typeset = with(cover) {
    val paper = hsv(random.range(0.08f, 0.12f), random.range(0.12f, 0.20f), 0.92f)
    val red = hsv(random.range(0.98f, 1.01f), 0.85f, random.range(0.70f, 0.80f))
    val black = Color.rgb(22, 20, 20)
    canvas.drawColor(paper)

    val centerX = width * 0.5f
    val centerY = height * 0.60f
    val rays = 12
    canvas.save()
    canvas.clipRect(0f, height * 0.38f, width, height * 0.84f)
    for (index in 0 until rays step 2) {
        val start = TAU * index / rays
        val end = TAU * (index + 1) / rays
        val wedge = Path()
        wedge.moveTo(centerX, centerY)
        wedge.lineTo(centerX + cos(start) * width * 2f, centerY + sin(start) * width * 2f)
        wedge.lineTo(centerX + cos(end) * width * 2f, centerY + sin(end) * width * 2f)
        wedge.close()
        canvas.drawPath(wedge, fillPaint(red))
    }
    canvas.restore()

    val radius = width * random.range(0.15f, 0.18f)
    canvas.drawCircle(centerX, centerY, radius, fillPaint(black))
    val star = starPath(centerX, centerY, radius * 0.7f, radius * 0.28f, 5, -TAU / 4)
    canvas.drawPath(star, fillPaint(red))

    Typeset(ink = black, authorInk = red, font = CoverFont.Condensed, upper = true)
}
