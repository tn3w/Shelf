package dev.tn3w.shelf.cover.art

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import dev.tn3w.shelf.cover.Anchor
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.Rule
import dev.tn3w.shelf.cover.Typeset
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.scanlines
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette

private class Concrete(val top: Int, val low: Int, val alarm: Int)

private fun concrete(cover: CoverCanvas): Concrete =
    with(cover) {
        val chill = random.range(0.04f, 0.62f)
        val alarmHue = random.pick(listOf(1.0f, 1.0f, 1.0f, 0.09f, 0.52f, 0.22f))
        val result =
            Concrete(
                hsv(chill, random.range(0.03f, 0.16f), random.range(0.52f, 0.86f)),
                hsv(chill + random.range(-0.08f, 0.08f), random.range(0.05f, 0.20f),
                    random.range(0.08f, 0.28f)),
                hsv(alarmHue + random.range(-0.02f, 0.02f), random.range(0.70f, 0.95f),
                    random.range(0.68f, 0.92f)),
            )
        verticalGradient(result.top, result.low, random.range(0.6f, 1.8f))
        texture(random.range(0.10f, 0.22f))
        result
    }

private fun dystopianFinish(cover: CoverCanvas, alarm: Int): Typeset =
    with(cover) {
        scanlines(random.range(0.12f, 0.26f), height / random.range(200f, 340f))
        vignette(random.range(0.6f, 0.8f), 0.5f)
        grain(random.range(0.07f, 0.11f))
        frame(CoverFrame.Rule, alarm)
        Typeset(
            ink = Color.rgb(238, 236, 232),
            authorInk = alarm,
            titleFont = random.pick(listOf(CoverFont.Condensed, CoverFont.Sans)),
            titleWeight = 900,
            titleTracking = random.range(-0.01f, 0.02f),
            titleLeading = 0.98f,
            titleSize = random.range(0.125f, 0.155f),
            authorFont = CoverFont.Typewriter,
            authorTracking = 0.16f,
            anchor = random.pick(listOf(Anchor.Top, Anchor.Bottom)),
            rule = Rule.Bar,
            shadow = 0.5f,
            scrim = 0.45f,
        )
    }

internal fun dystopianMonolith(cover: CoverCanvas): Typeset =
    with(cover) {
        val palette = concrete(cover)
        val center = random.range(0.42f, 0.58f) * width
        val half = random.range(0.16f, 0.24f) * width
        val top = random.range(0.18f, 0.30f) * height
        for (side in listOf(-1f, 1f)) {
            val wing = half * random.range(1.4f, 2.1f)
            val offset = center + side * (half + wing * 0.85f)
            val wingTop = top + height * random.range(0.45f, 0.7f) * 0.35f
            canvas.drawRect(offset - wing, wingTop, offset + wing, height,
                fillPaint(hsv(0.58f, 0.08f, random.range(0.12f, 0.2f))))
        }
        canvas.drawRect(center - half, top, center + half, height,
            fillPaint(hsv(0.58f, 0.06f, random.range(0.20f, 0.28f))))

        val columns = random.between(5, 8)
        val rows = random.between(9, 16)
        val cellWidth = half * 2f / columns
        val cellHeight = (height - top) / rows
        val threshold = random.range(0.10f, 0.22f)
        val lit = glowPaint(palette.alarm, unit(0.004f))
        for (column in 0 until columns) {
            for (row in 0 until rows) {
                if (!random.chance(threshold)) continue
                val x = center - half + cellWidth * (column + 0.5f)
                val y = top + cellHeight * (row + 0.5f)
                lit.color = mix(palette.alarm, Color.rgb(255, 200, 120), random.range(0f, 0.5f))
                canvas.drawRect(x - cellWidth * 0.16f, y - cellHeight * 0.2f,
                    x + cellWidth * 0.16f, y + cellHeight * 0.2f, lit)
            }
        }
        dystopianFinish(cover, palette.alarm)
    }

internal fun dystopianEye(cover: CoverCanvas): Typeset =
    with(cover) {
        val palette = concrete(cover)
        val centerX = width * random.range(0.40f, 0.60f)
        val centerY = height * random.range(0.40f, 0.58f)
        val radius = width * random.range(0.17f, 0.30f)
        val lidSpread = random.range(1.7f, 2.6f)
        val lidRise = random.range(0.92f, 1.35f)
        radialGlow(centerX, centerY, radius * 3.2f, shade(palette.alarm, 0.5f), 0.35f, 2f)

        val lid = Path()
        lid.moveTo(centerX - radius * lidSpread, centerY)
        lid.lineTo(centerX, centerY - radius * lidRise)
        lid.lineTo(centerX + radius * lidSpread, centerY)
        lid.lineTo(centerX, centerY + radius * lidRise)
        lid.close()
        canvas.drawPath(lid, fillPaint(hsv(random.range(0f, 1f), random.range(0f, 0.25f),
            random.range(0.04f, 0.10f))))

        canvas.save()
        canvas.clipPath(lid)
        val rings = random.between(5, 14)
        val spacing = random.range(0.055f, 0.105f)
        for (step in 0 until rings) {
            val ring = radius * (1f - step * spacing)
            canvas.drawCircle(centerX, centerY, ring,
                strokePaint(mix(palette.alarm, shade(palette.alarm, 0.15f), step * spacing),
                    unit(random.range(0.003f, 0.006f))))
        }
        val pupil = radius * random.range(0.28f, 0.40f)
        canvas.drawCircle(centerX, centerY, pupil, fillPaint(Color.BLACK))
        canvas.drawCircle(centerX - pupil * 0.4f, centerY - pupil * 0.45f, pupil * 0.3f,
            fillPaint(Color.rgb(200, 200, 210)))
        canvas.restore()
        dystopianFinish(cover, palette.alarm)
    }

internal fun dystopianBlocks(cover: CoverCanvas): Typeset =
    with(cover) {
        val palette = concrete(cover)
        val columns = random.between(6, 9)
        val rows = random.between(8, 12)
        val margin = width * 0.08f
        val cellWidth = (width - margin * 2f) / columns
        val cellHeight = (height - margin * 2f) / rows
        val markedColumn = random.index(columns)
        val markedRow = random.index(rows)
        for (column in 0 until columns) {
            for (row in 0 until rows) {
                val x = margin + cellWidth * column
                val y = margin + cellHeight * row
                val marked = column == markedColumn && row == markedRow
                val tone = hsv(0.58f, 0.05f, 0.10f + 0.05f * ((column + row) % 3))
                canvas.drawRect(x + cellWidth * 0.12f, y + cellHeight * 0.12f,
                    x + cellWidth * 0.88f, y + cellHeight * 0.88f,
                    fillPaint(if (marked) palette.alarm else tone))
            }
        }
        radialGlow(margin + cellWidth * (markedColumn + 0.5f),
            margin + cellHeight * (markedRow + 0.5f), width * 0.3f, palette.alarm, 0.45f, 2.4f)
        dystopianFinish(cover, palette.alarm)
    }

internal fun dystopianGlitch(cover: CoverCanvas): Typeset =
    with(cover) {
        val palette = concrete(cover)
        val centerX = width * random.range(0.4f, 0.6f)
        val centerY = height * random.range(0.60f, 0.64f)
        val size = width * random.range(0.20f, 0.26f)
        val shift = unit(random.range(0.015f, 0.03f))
        val shape = random.index(3)
        glitchShape(cover, shape, centerX - shift, centerY, size, Color.rgb(40, 220, 230))
        glitchShape(cover, shape, centerX + shift, centerY, size, palette.alarm)
        glitchShape(cover, shape, centerX, centerY, size, shade(palette.low, 0.6f))

        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        for (index in 0 until random.between(8, 16)) {
            val top = height * random.range(0.35f, 0.85f)
            val slice = height * random.range(0.004f, 0.03f)
            val offset = unit(random.range(-0.12f, 0.12f))
            val source = Rect(0, top.toInt(), width.toInt(), (top + slice).toInt())
            val target = RectF(offset, top, width + offset, top + slice)
            canvas.drawBitmap(copy, source, target, null)
        }
        copy.recycle()
        dystopianFinish(cover, palette.alarm).copy(anchor = Anchor.Top)
    }

private fun glitchShape(
    cover: CoverCanvas,
    shape: Int,
    x: Float,
    y: Float,
    size: Float,
    color: Int,
) =
    with(cover) {
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
            else -> canvas.drawRect(x - size * 0.45f, y - size * 1.1f, x + size * 0.45f,
                y + size * 1.1f, paint)
        }
    }
