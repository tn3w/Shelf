package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import dev.tn3w.shelf.cover.Anchor
import dev.tn3w.shelf.cover.CoverCanvas
import dev.tn3w.shelf.cover.CoverFont
import dev.tn3w.shelf.cover.CoverFrame
import dev.tn3w.shelf.cover.LetterCase
import dev.tn3w.shelf.cover.Rule
import dev.tn3w.shelf.cover.TAU
import dev.tn3w.shelf.cover.Typeset
import dev.tn3w.shelf.cover.fillPaint
import dev.tn3w.shelf.cover.frame
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.ridge
import dev.tn3w.shelf.cover.scrimBand
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.stars
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import kotlin.math.cos
import kotlin.math.sin

internal fun fantasyPeaks(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.60f, 0.66f, 0.71f, 0.76f, 0.53f))
        val accent = hsv(random.pick(listOf(0.10f, 0.12f, 0.08f, 0.45f)),
            random.range(0.45f, 0.70f), random.range(0.90f, 1f))
        val horizon = random.range(0.44f, 0.56f)
        val skyTop = hsv(hue, random.range(0.70f, 0.88f), random.range(0.10f, 0.16f))
        val skyLow = hsv(hue - 0.09f, random.range(0.45f, 0.65f), random.range(0.38f, 0.55f))
        verticalGradient(skyTop, skyLow, random.range(2.0f, 3.0f))
        stars((width * 0.55f).toInt(), height * horizon, Color.WHITE, unit(0.003f))

        val moonX = random.pick(listOf(0.24f, 0.30f, 0.70f, 0.78f)) * width
        val moonY = random.range(0.16f, 0.30f) * height
        val moonRadius = random.range(0.055f, 0.095f) * width
        radialGlow(moonX, moonY, moonRadius * 7f, accent, 0.45f, 2.6f)
        canvas.drawCircle(moonX, moonY, moonRadius,
            glowPaint(mix(accent, Color.WHITE, 0.45f), moonRadius * 0.12f))
        canvas.drawCircle(moonX, moonY, moonRadius, fillPaint(mix(accent, Color.WHITE, 0.45f)))

        radialGlow(moonX, horizon * height, width * 1.1f, mix(accent, skyLow, 0.6f), 0.22f, 1.6f)

        val layers = random.between(3, 4)
        val rim = mix(accent, Color.WHITE, 0.35f)
        for (index in 0 until layers) {
            val depth = index / (layers - 1f).coerceAtLeast(1f)
            val base = horizon + depth * random.range(0.10f, 0.16f)
            val tone = mix(mix(skyLow, skyTop, 0.30f + depth * 0.45f), Color.BLACK,
                depth * 0.38f)
            val shape = ridge(base, random.range(0.02f, 0.16f) * (1f - depth * 0.4f),
                random.range(0.03f, 0.09f))
            canvas.drawPath(shape.fill, fillPaint(tone))
            canvas.drawPath(shape.crest,
                strokePaint(mix(tone, rim, 0.45f - depth * 0.25f), unit(0.0035f)))
        }

        if (random.chance(0.65f)) tower(cover, horizon, accent)
        treeline(cover, mix(skyTop, Color.BLACK, 0.45f))

        vignette(0.5f, 0.62f)
        grain(0.035f)
        frame(CoverFrame.Ornate, accent)
        Typeset(
            ink = mix(accent, Color.WHITE, 0.72f),
            authorInk = accent,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(600, 700)),
            titleTracking = random.range(0.05f, 0.10f),
            titleSize = random.range(0.085f, 0.10f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
            rule = Rule.Ornament,
            shadow = 0.9f,
        )
    }

private fun tower(cover: CoverCanvas, horizon: Float, accent: Int) =
    with(cover) {
        val baseX = random.range(0.18f, 0.82f) * width
        val baseY = (horizon + random.range(0.02f, 0.07f)) * height
        val towerHeight = random.range(0.10f, 0.20f) * height
        val towerWidth = towerHeight * random.range(0.16f, 0.24f)
        val silhouette = Path()
        silhouette.addRect(baseX - towerWidth, baseY - towerHeight, baseX + towerWidth, baseY,
            Path.Direction.CW)
        val roof = Path()
        roof.moveTo(baseX - towerWidth * 1.5f, baseY - towerHeight)
        roof.lineTo(baseX + towerWidth * 1.5f, baseY - towerHeight)
        roof.lineTo(baseX, baseY - towerHeight * random.range(1.3f, 1.5f))
        roof.close()
        silhouette.addPath(roof)
        for (side in listOf(-1f, 1f)) {
            val wingHeight = towerHeight * random.range(0.45f, 0.7f)
            val wingWidth = towerWidth * random.range(1.1f, 1.8f)
            val offset = baseX + side * towerWidth * random.range(1.8f, 2.6f)
            silhouette.addRect(offset - wingWidth, baseY - wingHeight, offset + wingWidth, baseY,
                Path.Direction.CW)
        }
        canvas.drawPath(silhouette, fillPaint(Color.rgb(6, 6, 12)))

        val lit = glowPaint(accent, unit(0.008f))
        for (index in 0 until random.between(3, 7)) {
            val x = baseX + random.range(-towerWidth * 0.6f, towerWidth * 0.6f)
            val y = baseY - random.range(0.1f, 0.9f) * towerHeight
            canvas.drawCircle(x, y, unit(0.004f), lit)
        }
    }

private fun treeline(cover: CoverCanvas, tone: Int) =
    with(cover) {
        val trees = Path()
        val base = random.range(0.93f, 0.98f) * height
        var x = -width * 0.05f
        while (x < width * 1.05f) {
            val treeHeight = random.range(0.04f, 0.10f) * height
            val half = treeHeight * random.range(0.16f, 0.26f)
            trees.moveTo(x - half, base)
            trees.lineTo(x, base - treeHeight)
            trees.lineTo(x + half, base)
            trees.close()
            x += half * random.range(0.8f, 1.5f)
        }
        trees.addRect(0f, base - unit(0.004f), width, height, Path.Direction.CW)
        canvas.drawPath(trees, fillPaint(tone))
    }

internal fun fantasySigil(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.72f, 0.78f, 0.52f, 0.66f, 0.04f))
        val accent = hsv(random.pick(listOf(0.10f, 0.45f, 0.55f, 0.85f)),
            random.range(0.45f, 0.70f), random.range(0.92f, 1f))
        val deep = hsv(hue, random.range(0.70f, 0.90f), random.range(0.07f, 0.12f))
        verticalGradient(deep, shade(deep, random.range(1.6f, 2.6f)), 0.7f)
        texture(0.12f)

        val centerX = width * 0.5f
        val centerY = height * random.range(0.56f, 0.64f)
        val radius = width * random.range(0.24f, 0.31f)
        radialGlow(centerX, centerY, radius * 3f, accent, 0.40f, 2.2f)

        val sigil = Path()
        for (scale in listOf(1f, 0.86f, 0.52f)) {
            sigil.addCircle(centerX, centerY, radius * scale, Path.Direction.CW)
        }
        val points = random.pick(listOf(5, 6, 7))
        val skip = if (points % 2 == 0) 1 else 2
        val corners =
            List(points) {
                val angle = TAU * it / points - TAU / 4f
                (centerX + cos(angle) * radius * 0.86f) to
                    (centerY + sin(angle) * radius * 0.86f)
            }
        for (index in 0 until points) {
            val from = corners[index]
            val to = corners[(index + skip) % points]
            sigil.moveTo(from.first, from.second)
            sigil.lineTo(to.first, to.second)
        }
        val glyphs = random.between(10, 18)
        for (index in 0 until glyphs) {
            val angle = TAU * index / glyphs
            val anchorX = centerX + cos(angle) * radius * 0.93f
            val anchorY = centerY + sin(angle) * radius * 0.93f
            for (stroke in 0 until random.between(2, 4)) {
                val mark = unit(random.range(0.006f, 0.016f))
                val skew = angle + random.range(-0.5f, 0.5f)
                sigil.moveTo(anchorX + cos(skew) * mark, anchorY + sin(skew) * mark)
                sigil.lineTo(anchorX - cos(skew) * mark, anchorY - sin(skew) * mark)
            }
        }
        canvas.drawPath(sigil, glowPaint(accent, unit(0.018f), unit(0.006f)))
        canvas.drawPath(sigil, strokePaint(accent, unit(0.004f)))

        val sparkPaint = glowPaint(mix(accent, Color.WHITE, 0.5f), unit(0.004f))
        for (index in 0 until random.between(40, 90)) {
            val angle = random.range(0f, TAU)
            val distance = radius * random.range(0.2f, 2.4f)
            canvas.drawCircle(centerX + cos(angle) * distance, centerY + sin(angle) * distance,
                unit(random.range(0.001f, 0.004f)), sparkPaint)
        }

        vignette(0.65f, 0.46f)
        grain(0.04f)
        frame(CoverFrame.Ornate, accent)
        Typeset(
            ink = mix(accent, Color.WHITE, 0.7f),
            authorInk = accent,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 700)),
            titleTracking = random.range(0.08f, 0.16f),
            titleSize = random.range(0.070f, 0.090f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
            shadow = 0.7f,
        )
    }

internal fun fantasyForest(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(listOf(0.38f, 0.44f, 0.30f, 0.52f))
        val canopy = hsv(hue, random.range(0.60f, 0.85f), random.range(0.10f, 0.16f))
        val haze = hsv(hue - random.range(0.04f, 0.10f), random.range(0.30f, 0.50f),
            random.range(0.45f, 0.62f))
        val glow = hsv(random.pick(listOf(0.12f, 0.16f, 0.45f)), random.range(0.35f, 0.60f),
            random.range(0.92f, 1f))
        verticalGradient(canopy, haze, random.range(2.2f, 3.4f))

        for (index in 0 until random.between(3, 6)) {
            val topX = random.range(0.1f, 0.9f) * width
            val lean = random.range(0.08f, 0.28f) * width
            val spread = random.range(0.03f, 0.09f) * width
            val shaft = Path()
            shaft.moveTo(topX - spread, 0f)
            shaft.lineTo(topX + spread, 0f)
            shaft.lineTo(topX + lean + spread * 2.4f, height)
            shaft.lineTo(topX + lean - spread * 2.4f, height)
            shaft.close()
            canvas.drawPath(shaft, glowPaint(shade(glow, 0.22f), unit(0.06f)))
        }

        val trunks =
            List(random.between(6, 9)) { random.range(-0.05f, 1.05f) * width to random.float() }
                .sortedBy { it.second }
        for ((position, depth) in trunks) {
            val thickness = width * (0.010f + depth * depth * random.range(0.045f, 0.085f))
            val lean = random.range(-0.04f, 0.04f) * width
            val tone = mix(mix(haze, canopy, 0.45f), Color.rgb(5, 9, 7), 0.25f + depth * 0.70f)
            val trunk = Path()
            trunk.moveTo(position - thickness, height)
            trunk.lineTo(position - thickness * 0.62f + lean, 0f)
            trunk.lineTo(position + thickness * 0.62f + lean, 0f)
            trunk.lineTo(position + thickness, height)
            trunk.close()
            canvas.drawPath(trunk, fillPaint(tone))
            val branch = strokePaint(tone, thickness * 0.35f)
            for (index in 0 until random.between(1, 3)) {
                val anchorY = random.range(0.05f, 0.45f) * height
                val side = random.sign()
                canvas.drawLine(position + lean, anchorY,
                    position + lean + side * thickness * random.range(2.0f, 4.5f),
                    anchorY - height * random.range(0.04f, 0.10f), branch)
            }
        }

        scrimBand(random.range(0.62f, 0.76f) * height, height,
            mix(haze, Color.WHITE, 0.35f), random.range(0.26f, 0.42f))

        val firefly = glowPaint(glow, unit(0.006f))
        for (index in 0 until random.between(20, 45)) {
            canvas.drawCircle(random.range(0f, width), random.range(0.35f, 0.95f) * height,
                unit(random.range(0.002f, 0.006f)), firefly)
        }

        vignette(0.58f, 0.52f)
        grain(0.045f)
        frame(random.pick(listOf(CoverFrame.Hairline, CoverFrame.Ornate)), glow)
        Typeset(
            ink = mix(glow, Color.WHITE, 0.75f),
            authorInk = glow,
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 600, 700)),
            titleTracking = random.range(0.04f, 0.12f),
            titleSize = random.range(0.080f, 0.100f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
            anchor = random.pick(listOf(Anchor.Top, Anchor.Bottom)),
            rule = Rule.Ornament,
            shadow = 0.9f,
        )
    }
