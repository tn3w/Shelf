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
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.scanlines
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.starPath
import dev.tn3w.shelf.cover.stars
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal fun mysteryRedThread(cover: CoverCanvas): Typeset =
    with(cover) {
        verticalGradient(hsv(0.07f, 0.50f, 0.36f), hsv(0.06f, 0.55f, 0.22f), 1f)
        texture(0.22f)
        val red = hsv(0.99f, 0.85f, 0.80f)
        val notes = Array(3) { column -> Array(3) { row -> column to row } }.flatten()
            .filter { random.chance(0.8f) }
        val pins = notes.map { (column, row) ->
            val x = width * (0.22f + column * 0.28f + random.range(-0.05f, 0.05f))
            val y = height * (0.44f + row * 0.15f + random.range(-0.02f, 0.02f))
            note(cover, x, y, random.chance(0.35f))
            x to y - width * 0.07f
        }
        val thread = strokePaint(red, unit(0.004f))
        for (index in 1 until pins.size) {
            val from = pins[index - 1]
            val to = pins[random.index(index)]
            canvas.drawLine(from.first, from.second, to.first, to.second, thread)
        }
        for ((x, y) in pins) {
            canvas.drawCircle(x, y, unit(0.014f), fillPaint(red))
            canvas.drawCircle(x - unit(0.004f), y - unit(0.004f), unit(0.004f),
                fillPaint(alpha(Color.WHITE, 0.6f)))
        }

        vignette(0.6f, 0.5f)
        grain(0.05f)
        Typeset(
            ink = Color.rgb(244, 238, 226),
            authorInk = Color.rgb(230, 220, 200),
            titleFont = CoverFont.Typewriter,
            titleWeight = 700,
            titleTracking = random.range(0.02f, 0.08f),
            titleLeading = 1.05f,
            titleSize = random.range(0.085f, 0.105f),
            authorFont = CoverFont.Typewriter,
            authorTracking = 0.16f,
            rule = Rule.Line,
            shadow = 0.7f,
            scrim = 0.3f,
        )
    }

private fun note(cover: CoverCanvas, x: Float, y: Float, photo: Boolean) =
    with(cover) {
        val half = width * random.range(0.08f, 0.11f)
        val tall = half * if (photo) 1.2f else random.range(0.8f, 1.1f)
        canvas.save()
        canvas.rotate(random.range(-9f, 9f), x, y)
        val top = y - width * 0.07f
        val card = RectF(x - half, top, x + half, top + tall * 2f)
        val shadow = fillPaint(alpha(Color.BLACK, 0.4f))
        shadow.maskFilter = BlurMaskFilter(unit(0.01f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(RectF(card).apply { offset(unit(0.006f), unit(0.01f)) }, shadow)
        val paper =
            random.pick(listOf(Color.rgb(246, 240, 226), Color.rgb(250, 232, 150)))
        canvas.drawRect(card, fillPaint(if (photo) Color.rgb(240, 240, 236) else paper))
        if (photo) {
            val image = RectF(card).apply { inset(half * 0.12f, half * 0.12f) }
            image.bottom -= half * 0.35f
            canvas.drawRect(image, fillPaint(hsv(0.08f, 0.15f, random.range(0.2f, 0.4f))))
            val face = fillPaint(hsv(0.08f, 0.1f, 0.12f))
            val headY = image.centerY() - half * 0.1f
            canvas.drawCircle(image.centerX(), headY, half * 0.28f, face)
        } else {
            val line = strokePaint(Color.rgb(90, 90, 110), unit(0.0025f))
            var lineY = card.top + tall * 0.4f
            while (lineY < card.bottom - tall * 0.2f) {
                canvas.drawLine(card.left + half * 0.2f, lineY,
                    card.right - half * random.range(0.2f, 0.7f), lineY, line)
                lineY += tall * 0.3f
            }
        }
        canvas.restore()
    }

internal fun scifiRetro(cover: CoverCanvas): Typeset =
    with(cover) {
        val horizon = height * random.range(0.64f, 0.68f)
        val magenta = hsv(random.range(0.86f, 0.93f), 0.85f, 1f)
        verticalGradient(hsv(0.72f, 0.80f, 0.12f), hsv(0.86f, 0.75f, 0.55f), 1.6f)
        stars((width * 0.4f).toInt(), horizon * 0.6f, Color.WHITE, unit(0.0025f))

        val radius = width * random.range(0.26f, 0.32f)
        val sunY = horizon - radius * random.range(0.2f, 0.45f)
        radialGlow(width / 2f, sunY, radius * 2.4f, magenta, 0.45f, 2.2f)
        val stripes = Path()
        var stripe = sunY + radius * 0.05f
        var gap = radius * 0.03f
        while (stripe < sunY + radius) {
            stripes.addRect(0f, stripe, width, stripe + gap, Path.Direction.CW)
            stripe += gap + radius * 0.1f
            gap *= 1.35f
        }
        val sun = fillPaint(Color.WHITE)
        sun.shader = LinearGradient(0f, sunY - radius, 0f, sunY + radius,
            hsv(0.14f, 0.8f, 1f), hsv(0.93f, 0.8f, 1f), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipOutPath(stripes)
        canvas.drawCircle(width / 2f, sunY, radius, sun)
        canvas.restore()

        canvas.drawRect(0f, horizon, width, height, fillPaint(hsv(0.74f, 0.85f, 0.10f)))
        val grid = strokePaint(magenta, unit(0.003f))
        for (index in 1..12) {
            val fraction = index / 12f
            val y = horizon + (height - horizon) * fraction * fraction
            canvas.drawLine(0f, y, width, y, grid)
        }
        for (index in -10..10) {
            canvas.drawLine(width / 2f + index * width * 0.06f, horizon,
                width / 2f + index * width * 0.6f, height, grid)
        }
        canvas.drawLine(0f, horizon, width, horizon, glowPaint(magenta, unit(0.01f),
            unit(0.004f)))

        scanlines(0.12f, height / 320f)
        grain(0.03f)
        Typeset(
            ink = Color.rgb(255, 236, 250),
            authorInk = hsv(0.52f, 0.5f, 1f),
            titleFont = random.pick(listOf(CoverFont.Sans, CoverFont.Condensed)),
            titleWeight = 900,
            titleItalic = true,
            titleTracking = random.range(0.02f, 0.08f),
            titleLeading = 1f,
            titleSize = random.range(0.10f, 0.125f),
            authorFont = CoverFont.Sans,
            authorWeight = 500,
            authorTracking = 0.3f,
            rule = Rule.None,
            shadow = 0.8f,
        )
    }

internal fun dystopianBarcode(cover: CoverCanvas): Typeset =
    with(cover) {
        val base = hsv(random.range(0f, 1f), random.range(0.02f, 0.10f),
            random.range(0.14f, 0.22f))
        val alarm = hsv(random.pick(listOf(1f, 0.09f, 0.52f)), 0.85f, 0.85f)
        verticalGradient(shade(base, 1.3f), base, 1f)
        texture(0.16f)

        val label = RectF(width * 0.16f, height * 0.44f, width * 0.84f, height * 0.76f)
        canvas.drawRect(label, fillPaint(Color.rgb(232, 230, 224)))
        val bars = RectF(label.left + unit(0.05f), label.top + unit(0.06f),
            label.right - unit(0.05f), label.bottom - unit(0.12f))
        val ink = fillPaint(Color.rgb(16, 16, 18))
        var x = bars.left
        while (x < bars.right) {
            val bar = unit(0.006f) * random.between(1, 4)
            if (random.chance(0.55f)) {
                val end = (x + bar).coerceAtMost(bars.right)
                canvas.drawRect(x, bars.top, end, bars.bottom, ink)
            }
            x += bar
        }
        val digits = Paint(ink).apply {
            typeface = Typeface.MONOSPACE
            textSize = unit(0.05f)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.3f
        }
        val serial = (1..12).joinToString("") { random.between(0, 9).toString() }
        canvas.drawText(serial, label.centerX(), label.bottom - unit(0.04f), digits)
        canvas.drawRect(label.left, label.top - unit(0.03f), label.left + unit(0.2f),
            label.top, fillPaint(alarm))

        scanlines(0.15f, height / 260f)
        vignette(0.7f, 0.5f)
        grain(0.08f)
        frame(CoverFrame.Rule, alarm)
        Typeset(
            ink = Color.rgb(238, 236, 232),
            authorInk = alarm,
            titleFont = CoverFont.Condensed,
            titleWeight = 900,
            titleTracking = 0.01f,
            titleLeading = 0.98f,
            titleSize = random.range(0.12f, 0.15f),
            authorFont = CoverFont.Typewriter,
            authorTracking = 0.16f,
            rule = Rule.Bar,
            shadow = 0.5f,
        )
    }

internal fun romanceLetter(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.96f, 0.99f, 0.03f, 0.92f))
        val blush = hsv(hue, random.range(0.18f, 0.30f), random.range(0.94f, 0.99f))
        val wax = hsv(hue, random.range(0.75f, 0.90f), random.range(0.55f, 0.70f))
        verticalGradient(blush, mix(blush, wax, 0.25f), 1.4f)
        val faint = fillPaint(alpha(wax, 0.12f))
        for (index in 0 until random.between(14, 24)) {
            val size = unit(random.range(0.015f, 0.035f))
            val y = random.range(0.3f, 1f) * height
            canvas.drawPath(heart(random.range(0f, width), y, size), faint)
        }

        val envelope = RectF(width * 0.16f, height * 0.46f, width * 0.84f, height * 0.78f)
        canvas.save()
        canvas.rotate(random.range(-6f, 6f), envelope.centerX(), envelope.centerY())
        val shadow = fillPaint(alpha(Color.BLACK, 0.25f))
        shadow.maskFilter = BlurMaskFilter(unit(0.02f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(RectF(envelope).apply { offset(0f, unit(0.015f)) }, shadow)
        canvas.drawRect(envelope, fillPaint(Color.rgb(250, 244, 232)))
        val fold = strokePaint(Color.rgb(214, 200, 184), unit(0.004f))
        val flapY = envelope.top + envelope.height() * 0.55f
        canvas.drawLine(envelope.left, envelope.top, envelope.centerX(), flapY, fold)
        canvas.drawLine(envelope.right, envelope.top, envelope.centerX(), flapY, fold)
        canvas.drawLine(envelope.left, envelope.bottom, envelope.centerX() - unit(0.08f),
            flapY + unit(0.02f), fold)
        canvas.drawLine(envelope.right, envelope.bottom, envelope.centerX() + unit(0.08f),
            flapY + unit(0.02f), fold)
        val seal = unit(0.075f)
        canvas.drawCircle(envelope.centerX(), flapY, seal, fillPaint(wax))
        canvas.drawCircle(envelope.centerX(), flapY, seal * 0.72f,
            strokePaint(shade(wax, 0.75f), unit(0.004f)))
        canvas.drawPath(heart(envelope.centerX(), flapY, seal * 0.3f),
            fillPaint(shade(wax, 0.75f)))
        canvas.restore()

        grain(0.025f)
        frame(CoverFrame.Hairline, wax)
        Typeset(
            ink = mix(wax, Color.rgb(40, 20, 30), 0.6f),
            authorInk = mix(wax, Color.rgb(30, 15, 25), 0.45f),
            titleFont = CoverFont.Script,
            titleWeight = random.pick(listOf(500, 700)),
            titleCase = LetterCase.Title,
            titleTracking = 0f,
            titleLeading = 0.98f,
            titleSize = random.range(0.12f, 0.15f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
        )
    }

private fun heart(x: Float, y: Float, size: Float): Path {
    val path = Path()
    path.moveTo(x, y + size * 0.9f)
    path.cubicTo(x - size * 1.6f, y - size * 0.2f, x - size * 0.6f, y - size * 1.3f, x,
        y - size * 0.4f)
    path.cubicTo(x + size * 0.6f, y - size * 1.3f, x + size * 1.6f, y - size * 0.2f, x,
        y + size * 0.9f)
    path.close()
    return path
}

internal fun humorPop(cover: CoverCanvas): Typeset =
    with(cover) {
        val ground = hsv(random.pick(listOf(0.52f, 0.55f, 0.95f, 0.33f)), 0.55f, 0.95f)
        val burst = hsv(random.range(0.12f, 0.15f), 0.85f, 1f)
        val core = hsv(random.range(0f, 0.03f), 0.85f, 0.95f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(ground))
        val dots = fillPaint(shade(ground, 0.85f))
        val pitch = width / 22f
        for (row in 0..(height / pitch).toInt()) {
            for (column in 0..22) {
                val x = column * pitch + if (row % 2 == 0) 0f else pitch / 2f
                canvas.drawCircle(x, row * pitch, pitch * 0.22f, dots)
            }
        }

        val centerX = width * 0.5f
        val centerY = height * random.range(0.58f, 0.62f)
        val outline = strokePaint(Color.rgb(20, 20, 24), unit(0.012f))
        val points = random.between(12, 18)
        val big = starPath(centerX, centerY, width * 0.34f, width * 0.24f, points,
            random.range(0f, 1f))
        canvas.drawPath(big, fillPaint(burst))
        canvas.drawPath(big, outline)
        val small = starPath(centerX, centerY, width * 0.20f, width * 0.14f, points,
            random.range(0f, 1f))
        canvas.drawPath(small, fillPaint(core))
        canvas.drawPath(small, outline)

        grain(0.02f)
        Typeset(
            ink = Color.rgb(20, 20, 24),
            authorInk = Color.rgb(20, 20, 24),
            titleFont = CoverFont.Condensed,
            titleWeight = 900,
            titleTracking = 0.01f,
            titleLeading = 0.95f,
            titleSize = random.range(0.12f, 0.15f),
            authorFont = CoverFont.Condensed,
            authorWeight = 700,
            authorTracking = 0.12f,
            authorSize = 0.03f,
            rule = Rule.None,
        )
    }

internal fun businessGraph(cover: CoverCanvas): Typeset =
    with(cover) {
        val paper = hsv(random.range(0.55f, 0.65f), 0.04f, 0.97f)
        val hue = random.pick(listOf(0.02f, 0.33f, 0.58f, 0.08f, 0.75f))
        val accent = hsv(hue, 0.75f, 0.80f)
        val ink = hsv(0.62f, 0.35f, 0.16f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(paper))

        val chart = RectF(width * 0.12f, height * 0.42f, width * 0.88f, height * 0.82f)
        val grid = strokePaint(alpha(ink, 0.10f), unit(0.002f))
        for (index in 0..4) {
            val y = chart.top + chart.height() * index / 4f
            canvas.drawLine(chart.left, y, chart.right, y, grid)
        }
        val count = random.between(7, 10)
        var level = random.range(0.1f, 0.25f)
        val points = List(count) { index ->
            level = (level + random.range(-0.08f, 0.2f)).coerceIn(0.05f, 0.95f)
            val x = chart.left + chart.width() * index / (count - 1f)
            x to chart.bottom - chart.height() * if (index == count - 1) 0.95f else level
        }
        val line = Path()
        val area = Path()
        area.moveTo(chart.left, chart.bottom)
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(chart.right, chart.bottom)
        area.close()
        canvas.drawPath(area, fillPaint(alpha(accent, 0.14f)))
        canvas.drawPath(line, strokePaint(accent, unit(0.008f)))
        for ((x, y) in points) canvas.drawCircle(x, y, unit(0.012f), fillPaint(accent))
        val (lastX, lastY) = points.last()
        canvas.drawCircle(lastX, lastY, unit(0.03f), strokePaint(accent, unit(0.004f)))
        canvas.drawLine(chart.left, chart.bottom, chart.right, chart.bottom,
            strokePaint(ink, unit(0.004f)))

        grain(0.015f)
        Typeset(
            ink = ink,
            authorInk = shade(accent, 0.8f),
            titleFont = CoverFont.Sans,
            titleWeight = 800,
            titleTracking = -0.01f,
            titleLeading = 0.98f,
            titleSize = random.range(0.10f, 0.13f),
            authorFont = CoverFont.Sans,
            authorWeight = 600,
            authorTracking = 0.18f,
            rule = Rule.Bar,
        )
    }

internal fun vintageDeco(cover: CoverCanvas): Typeset =
    with(cover) {
        val night = random.pick(listOf(Color.rgb(14, 14, 16), hsv(0.60f, 0.6f, 0.14f),
            hsv(0.40f, 0.5f, 0.12f), hsv(0.98f, 0.6f, 0.16f)))
        val gold = hsv(random.range(0.10f, 0.12f), 0.55f, 0.86f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(night))
        texture(0.08f)

        val centerX = width / 2f
        val centerY = height * 0.84f
        val radius = width * 0.46f
        val rays = random.pick(listOf(9, 11, 13))
        val ray = fillPaint(alpha(gold, 0.8f))
        for (index in 0 until rays) {
            val start = 180f + 180f * index / rays
            canvas.drawArc(circle(centerX, centerY, radius), start, 90f / rays, true, ray)
        }
        for (scale in listOf(0.34f, 0.5f, 1.04f)) {
            canvas.drawArc(circle(centerX, centerY, radius * scale), 180f, 180f, false,
                strokePaint(gold, unit(0.005f)))
        }
        canvas.drawCircle(centerX, centerY, radius * 0.2f, fillPaint(gold))
        canvas.drawRect(0f, centerY, width, height, fillPaint(night))
        canvas.drawLine(width * 0.08f, centerY, width * 0.92f, centerY,
            strokePaint(gold, unit(0.005f)))

        grain(0.03f)
        frame(CoverFrame.Double, gold)
        Typeset(
            ink = gold,
            authorInk = mix(gold, Color.WHITE, 0.3f),
            titleFont = random.pick(listOf(CoverFont.Condensed, CoverFont.SmallCaps)),
            titleWeight = random.pick(listOf(400, 700)),
            titleTracking = random.range(0.14f, 0.24f),
            titleLeading = 1.1f,
            titleSize = random.range(0.075f, 0.095f),
            authorFont = CoverFont.Condensed,
            authorTracking = 0.32f,
            rule = Rule.Ornament,
        )
    }

internal fun literaryFields(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.float()
        val dark = random.chance(0.5f)
        val ground = if (dark) hsv(hue, 0.55f, 0.22f) else hsv(hue, 0.35f, 0.80f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(ground))
        texture(0.08f)
        val fields = random.between(2, 3)
        val top = height * 0.38f
        val span = (height * 0.86f - top) / fields
        for (index in 0 until fields) {
            val tone = hsv(hue + random.range(-0.08f, 0.08f), random.range(0.45f, 0.80f),
                if (dark) random.range(0.35f, 0.70f) else random.range(0.55f, 0.95f))
            val paint = fillPaint(alpha(tone, 0.9f))
            paint.maskFilter = BlurMaskFilter(unit(0.015f), BlurMaskFilter.Blur.NORMAL)
            val y = top + span * index
            canvas.drawRect(width * 0.09f, y + unit(0.02f), width * 0.91f,
                y + span * random.range(0.82f, 0.95f), paint)
        }
        grain(0.03f)
        Typeset(
            ink = if (dark) Color.rgb(240, 234, 224) else hsv(hue, 0.5f, 0.15f),
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(400, 600)),
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.04f, 0.12f),
            titleSize = random.range(0.075f, 0.095f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.2f,
            rule = Rule.None,
        )
    }

internal fun technicalAtom(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.55f, 0.60f, 0.48f, 0.75f))
        val accent = hsv(hue, 0.6f, 1f)
        verticalGradient(hsv(hue + 0.05f, 0.7f, 0.10f), hsv(hue, 0.7f, 0.24f), 1f)
        val centerX = width * 0.5f
        val centerY = height * random.range(0.60f, 0.64f)
        val reach = width * random.range(0.36f, 0.40f)
        radialGlow(centerX, centerY, reach * 1.4f, accent, 0.3f, 2.2f)

        val orbits = random.pick(listOf(3, 4))
        val orbit = RectF(centerX - reach, centerY - reach * 0.3f, centerX + reach,
            centerY + reach * 0.3f)
        for (index in 0 until orbits) {
            val degrees = 180f * index / orbits + 20f
            canvas.save()
            canvas.rotate(degrees, centerX, centerY)
            canvas.drawOval(orbit, glowPaint(accent, unit(0.008f), unit(0.004f)))
            canvas.drawOval(orbit, strokePaint(accent, unit(0.004f)))
            val angle = random.range(0f, TAU)
            val electron = fillPaint(mix(accent, Color.WHITE, 0.6f))
            canvas.drawCircle(centerX + cos(angle) * reach,
                centerY + sin(angle) * reach * 0.3f, unit(0.018f), electron)
            canvas.restore()
        }
        val nucleus = unit(0.045f)
        for (index in 0 until 9) {
            val angle = TAU * index / 9 * 2.3f
            val distance = nucleus * 0.7f * (index % 3) / 2f
            val tone =
                if (index % 2 == 0) hsv(0.02f, 0.7f, 0.9f) else hsv(hue, 0.2f, 0.85f)
            canvas.drawCircle(centerX + cos(angle) * distance,
                centerY + sin(angle) * distance, nucleus * 0.55f, fillPaint(tone))
        }

        vignette(0.45f, 0.55f)
        grain(0.03f)
        Typeset(
            ink = Color.rgb(236, 244, 252),
            authorInk = accent,
            titleFont = CoverFont.Sans,
            titleWeight = random.pick(listOf(300, 700)),
            titleTracking = random.range(0.06f, 0.16f),
            titleSize = random.range(0.075f, 0.095f),
            authorFont = CoverFont.Mono,
            authorWeight = 400,
            authorTracking = 0.18f,
            rule = Rule.Line,
            shadow = 0.4f,
        )
    }

internal fun travelRoute(cover: CoverCanvas): Typeset =
    with(cover) {
        val sea = hsv(random.range(0.50f, 0.58f), random.range(0.15f, 0.30f), 0.95f)
        val land = hsv(random.range(0.50f, 0.60f), 0.45f, 0.55f)
        val accent = hsv(random.pick(listOf(0.01f, 0.06f, 0.95f)), 0.8f, 0.9f)
        canvas.drawRect(0f, 0f, width, height, fillPaint(sea))

        val blobs = List(random.between(4, 6)) {
            Triple(random.range(0f, width), random.range(0.4f, 0.95f) * height,
                width * random.range(0.12f, 0.24f))
        }
        val pitch = width / 40f
        val dot = fillPaint(land)
        var y = height * 0.36f
        while (y < height) {
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

        val start = blobs[0].first to blobs[0].second
        val end = blobs[1].first to blobs[1].second
        val lift = height * random.range(0.12f, 0.2f)
        val route = Path()
        route.moveTo(start.first, start.second)
        val peak = minOf(start.second, end.second) - lift
        route.quadTo((start.first + end.first) / 2f, peak, end.first, end.second)
        val dashed = strokePaint(accent, unit(0.006f))
        dashed.pathEffect = DashPathEffect(floatArrayOf(unit(0.02f), unit(0.015f)), 0f)
        canvas.drawPath(route, dashed)
        for ((x, pinY) in listOf(start, end)) pin(cover, x, pinY, accent)

        grain(0.02f)
        frame(CoverFrame.Hairline, land)
        Typeset(
            ink = shade(land, 0.45f),
            authorInk = shade(land, 0.5f),
            titleFont = CoverFont.Condensed,
            titleWeight = 700,
            titleTracking = random.range(0.04f, 0.10f),
            titleLeading = 1f,
            titleSize = random.range(0.10f, 0.12f),
            authorFont = CoverFont.Sans,
            authorWeight = 500,
            authorTracking = 0.26f,
            rule = Rule.Bar,
            anchor = Anchor.Top,
        )
    }

private fun pin(cover: CoverCanvas, x: Float, y: Float, color: Int) =
    with(cover) {
        val size = unit(0.035f)
        val drop = Path()
        drop.moveTo(x, y)
        drop.cubicTo(x - size * 1.6f, y - size * 1.4f, x - size, y - size * 2.8f, x,
            y - size * 2.8f)
        drop.cubicTo(x + size, y - size * 2.8f, x + size * 1.6f, y - size * 1.4f, x, y)
        drop.close()
        canvas.drawPath(drop, fillPaint(color))
        canvas.drawCircle(x, y - size * 1.85f, size * 0.4f, fillPaint(Color.WHITE))
    }

private fun circle(centerX: Float, centerY: Float, radius: Float) =
    RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
