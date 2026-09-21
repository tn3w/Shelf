package dev.tn3w.shelf.cover.art

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.Rule
import dev.tn3w.shelf.cover.TAU
import dev.tn3w.shelf.cover.Typeset
import dev.tn3w.shelf.cover.alpha
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.scanlines
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.starPath
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.cos
import kotlin.math.sin

private class Concrete(val top: Int, val low: Int, val alarm: Int)

private fun concrete(cover: CoverCanvas): Concrete =
    with(cover) {
        val chill = random.range(0.04f, 0.62f)
        val alarmHue = random.pick(listOf(1.0f, 1.0f, 1.0f, 0.03f, 0.08f))
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
            rule = Rule.Bar,
            shadow = 0.5f,
            scrim = 0.45f,
        )
    }

internal fun dystopianMonolith(cover: CoverCanvas): Typeset =
    with(cover) {
        val palette = concrete(cover)
        val center = random.range(0.44f, 0.56f) * width
        val half = random.range(0.34f, 0.40f) * width
        val top = random.range(0.40f, 0.46f) * height
        val tiers = random.between(3, 5)
        val tierHeight = (height - top) / tiers
        val ziggurat = Path()
        for (tier in 0 until tiers) {
            val spread = half * (1f - tier * 0.7f / tiers)
            val floor = height - tierHeight * tier
            ziggurat.addRect(center - spread, floor - tierHeight, center + spread, floor,
                Path.Direction.CW)
        }
        val stone = hsv(0.58f, 0.05f, random.range(0.72f, 0.85f))
        canvas.drawPath(ziggurat, fillPaint(stone))
        canvas.save()
        canvas.clipPath(ziggurat)
        val shade = fillPaint(alpha(Color.BLACK, 0.35f))
        canvas.drawRect(center, top, center + half, height, shade)
        val rows = tiers * 3
        val slit = fillPaint(alpha(Color.BLACK, 0.55f))
        for (row in 0 until rows) {
            val y = top + (height - top) * (row + 0.55f) / rows
            canvas.drawRect(center - half, y, center + half, y + unit(0.006f), slit)
        }
        canvas.restore()
        radialGlow(center, top, width * 0.5f, palette.alarm, 0.25f, 2.4f)
        dystopianFinish(cover, palette.alarm)
    }

internal fun dystopianEye(cover: CoverCanvas): Typeset =
    with(cover) {
        val palette = concrete(cover)
        val centerX = width * random.range(0.40f, 0.60f)
        val centerY = height * random.range(0.60f, 0.64f)
        val radius = width * random.range(0.17f, 0.24f)
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
        val top = height * 0.40f
        val cellHeight = (height * 0.86f - top) / rows
        val markedColumn = random.index(columns)
        val markedRow = random.index(rows)
        for (column in 0 until columns) {
            for (row in 0 until rows) {
                val x = margin + cellWidth * column
                val y = top + cellHeight * row
                val marked = column == markedColumn && row == markedRow
                val tone = hsv(0.58f, 0.05f, 0.10f + 0.05f * ((column + row) % 3))
                canvas.drawRect(x + cellWidth * 0.12f, y + cellHeight * 0.12f,
                    x + cellWidth * 0.88f, y + cellHeight * 0.88f,
                    fillPaint(if (marked) palette.alarm else tone))
            }
        }
        radialGlow(margin + cellWidth * (markedColumn + 0.5f),
            top + cellHeight * (markedRow + 0.5f), width * 0.3f, palette.alarm, 0.45f,
            2.4f)
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
        dystopianFinish(cover, palette.alarm)
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

internal fun dystopianPropaganda(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.08f, 0.12f), random.range(0.14f, 0.24f), 0.90f)
        val red = hsv(random.range(0.98f, 1.01f), 0.85f, random.range(0.70f, 0.80f))
        val black = Color.rgb(22, 20, 20)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

        val originX = width * random.range(0.08f, 0.3f)
        val originY = height * 0.94f
        val rays = 16
        canvas.save()
        canvas.clipRect(0f, height * 0.42f, width, height * 0.84f)
        for (index in 0 until rays step 2) {
            val start = Math.toRadians(-100.0 + 100.0 * index / rays).toFloat()
            val end = Math.toRadians(-100.0 + 100.0 * (index + 1) / rays).toFloat()
            val wedge = Path()
            wedge.moveTo(originX, originY)
            val reach = width * 2f
            wedge.lineTo(originX + cos(start) * reach, originY + sin(start) * reach)
            wedge.lineTo(originX + cos(end) * reach, originY + sin(end) * reach)
            wedge.close()
            canvas.drawPath(wedge, fillPaint(red))
        }
        canvas.restore()

        val centerX = width * random.range(0.58f, 0.70f)
        val centerY = height * random.range(0.58f, 0.62f)
        val radius = width * random.range(0.14f, 0.18f)
        canvas.drawCircle(centerX, centerY, radius, fillPaint(black))
        val star = starPath(centerX, centerY, radius * 0.7f, radius * 0.28f, 5, -TAU / 4)
        canvas.drawPath(star, fillPaint(red))
        canvas.save()
        canvas.rotate(random.range(-32f, -22f), width / 2f, height * 0.70f)
        canvas.drawRect(-width, height * 0.70f, width * 2f, height * 0.70f + unit(0.06f),
            fillPaint(black))
        canvas.restore()

        texture(0.16f)
        vignette(0.3f, 0.6f)
        grain(0.05f)
        Typeset(
            ink = black,
            authorInk = red,
            titleFont = CoverFont.Condensed,
            titleWeight = 900,
            titleTracking = 0.01f,
            titleLeading = 0.96f,
            titleSize = random.range(0.12f, 0.15f),
            authorFont = CoverFont.Condensed,
            authorWeight = 700,
            authorTracking = 0.2f,
            rule = Rule.Bar,
        )
    }
