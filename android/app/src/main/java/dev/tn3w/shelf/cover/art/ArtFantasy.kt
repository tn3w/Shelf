package dev.tn3w.shelf.cover.art

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
import dev.tn3w.shelf.cover.glowPaint
import dev.tn3w.shelf.cover.grain
import dev.tn3w.shelf.cover.hsv
import dev.tn3w.shelf.cover.mix
import dev.tn3w.shelf.cover.radialGlow
import dev.tn3w.shelf.cover.randomHarmonics
import dev.tn3w.shelf.cover.ridge
import dev.tn3w.shelf.cover.scrimBand
import dev.tn3w.shelf.cover.shade
import dev.tn3w.shelf.cover.starPath
import dev.tn3w.shelf.cover.stars
import dev.tn3w.shelf.cover.strokePaint
import dev.tn3w.shelf.cover.texture
import dev.tn3w.shelf.cover.verticalGradient
import dev.tn3w.shelf.cover.vignette
import dev.tn3w.shelf.cover.wobblyRingPath
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
        val moonY = random.range(0.30f, 0.38f) * height
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

internal fun fantasyDawn(cover: CoverCanvas): Typeset =
    with(cover) {
        val warm = random.pick(listOf(0.02f, 0.06f, 0.10f, 0.95f))
        val glow = hsv(warm, random.range(0.35f, 0.55f), 1f)
        val deep = hsv(random.pick(listOf(0.62f, 0.70f, 0.78f, 0.85f)),
            random.range(0.35f, 0.55f), random.range(0.28f, 0.38f))
        verticalGradient(hsv(0.60f, random.range(0.12f, 0.24f), 0.96f), glow, 1.3f)

        val horizon = random.range(0.54f, 0.60f)
        val sunX = width * random.range(0.3f, 0.7f)
        val sunRadius = width * random.range(0.14f, 0.20f)
        radialGlow(sunX, horizon * height, width, Color.WHITE, 0.45f, 2f)
        canvas.drawCircle(sunX, horizon * height - sunRadius * 0.3f, sunRadius,
            fillPaint(mix(glow, Color.WHITE, 0.65f)))

        val bird = strokePaint(alpha(deep, 0.7f), unit(0.004f))
        for (index in 0 until random.between(3, 7)) {
            val x = width * random.range(0.15f, 0.85f)
            val y = height * random.range(0.40f, 0.50f)
            val span = unit(random.range(0.015f, 0.03f))
            canvas.drawLine(x - span, y - span * 0.5f, x, y, bird)
            canvas.drawLine(x, y, x + span, y - span * 0.5f, bird)
        }

        val layers = 4
        for (index in 0 until layers) {
            val depth = index / (layers - 1f)
            val shape = ridge(horizon + depth * 0.24f, random.range(0.04f, 0.12f),
                random.range(0.04f, 0.08f))
            canvas.drawPath(shape.fill, fillPaint(mix(mix(glow, Color.WHITE, 0.3f), deep,
                0.25f + depth * 0.75f)))
        }

        grain(0.025f)
        frame(CoverFrame.Hairline, deep)
        Typeset(
            ink = shade(deep, 0.6f),
            authorInk = mix(glow, Color.WHITE, 0.5f),
            titleFont = CoverFont.Serif,
            titleWeight = random.pick(listOf(500, 700)),
            titleItalic = random.chance(0.3f),
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.04f, 0.10f),
            titleSize = random.range(0.085f, 0.105f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.20f,
            rule = Rule.Ornament,
        )
    }

internal fun fantasyIlluminated(cover: CoverCanvas): Typeset =
    with(cover) {
        val vellum = hsv(random.range(0.09f, 0.12f), random.range(0.14f, 0.24f), 0.94f)
        val gold = hsv(random.range(0.10f, 0.12f), 0.65f, random.range(0.78f, 0.86f))
        val blue = hsv(random.range(0.60f, 0.64f), 0.75f, random.range(0.45f, 0.58f))
        val red = hsv(random.range(0.98f, 1.01f), 0.75f, random.range(0.60f, 0.72f))
        verticalGradient(vellum, shade(vellum, 0.9f), 1f)
        texture(0.14f)

        val inset = width * 0.05f
        val band = width * 0.045f
        val outer = RectF(inset, inset, width - inset, height - inset)
        canvas.drawRect(outer, strokePaint(random.pick(listOf(blue, red)), band))
        canvas.drawRect(RectF(outer).apply { inset(-band * 0.6f, -band * 0.6f) },
            strokePaint(gold, unit(0.003f)))
        canvas.drawRect(RectF(outer).apply { inset(band * 0.6f, band * 0.6f) },
            strokePaint(gold, unit(0.003f)))
        val dot = fillPaint(gold)
        val step = band * 1.2f
        var along = outer.left
        while (along <= outer.right) {
            canvas.drawCircle(along, outer.top, band * 0.16f, dot)
            canvas.drawCircle(along, outer.bottom, band * 0.16f, dot)
            along += step
        }
        along = outer.top
        while (along <= outer.bottom) {
            canvas.drawCircle(outer.left, along, band * 0.16f, dot)
            canvas.drawCircle(outer.right, along, band * 0.16f, dot)
            along += step
        }

        val centerX = width * 0.5f
        val centerY = height * random.range(0.60f, 0.64f)
        val radius = width * random.range(0.15f, 0.19f)
        for (index in 0 until 4) {
            curl(cover, centerX, centerY, radius, TAU * (index + 0.5f) / 4, gold, red)
        }
        canvas.drawCircle(centerX, centerY, radius, fillPaint(blue))
        canvas.drawCircle(centerX, centerY, radius, strokePaint(gold, unit(0.012f)))
        val petals = random.pick(listOf(6, 8))
        for (index in 0 until petals) {
            val angle = TAU * index / petals
            canvas.drawCircle(centerX + cos(angle) * radius * 0.45f,
                centerY + sin(angle) * radius * 0.45f, radius * 0.24f, fillPaint(red))
        }
        canvas.drawCircle(centerX, centerY, radius * 0.25f, fillPaint(gold))

        grain(0.03f)
        Typeset(
            ink = shade(red, 0.55f),
            authorInk = shade(blue, 0.7f),
            titleFont = CoverFont.Serif,
            titleWeight = 700,
            titleCase = random.pick(listOf(LetterCase.Upper, LetterCase.Title)),
            titleTracking = random.range(0.03f, 0.08f),
            titleSize = random.range(0.08f, 0.10f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.18f,
            rule = Rule.Ornament,
        )
    }

private fun curl(
    cover: CoverCanvas,
    centerX: Float,
    centerY: Float,
    radius: Float,
    direction: Float,
    gold: Int,
    red: Int,
) =
    with(cover) {
        val spiralX = centerX + cos(direction) * radius * 1.7f
        val spiralY = centerY + sin(direction) * radius * 1.7f
        val vine = Path()
        vine.moveTo(centerX + cos(direction) * radius, centerY + sin(direction) * radius)
        for (step in 0..60) {
            val progress = step / 60f
            val angle = direction + TAU / 2 + progress * TAU * 1.2f
            val distance = radius * 0.7f * (1f - progress * 0.85f)
            val x = spiralX + cos(angle) * distance
            val y = spiralY + sin(angle) * distance
            vine.lineTo(x, y)
            if (step % 15 == 7) canvas.drawCircle(x, y, radius * 0.09f, fillPaint(red))
        }
        canvas.drawPath(vine, strokePaint(gold, unit(0.008f)))
    }

private val CRYSTAL_HUES = listOf(0.33f, 0.98f, 0.78f, 0.58f, 0.12f, 0.48f)

internal fun fantasyCrystal(cover: CoverCanvas): Typeset =
    with(cover) {
        val hue = random.pick(CRYSTAL_HUES)
        val gem = hsv(hue, random.range(0.60f, 0.80f), random.range(0.80f, 0.95f))
        verticalGradient(hsv(hue + 0.04f, 0.70f, 0.08f), hsv(hue, 0.60f, 0.30f), 1.2f)
        stars((width * 0.3f).toInt(), height * 0.7f, mix(gem, Color.WHITE, 0.6f),
            unit(0.002f))

        val baseY = height * 0.86f
        radialGlow(width * 0.5f, baseY - height * 0.15f, width * 0.8f, gem, 0.45f, 2.2f)
        val shards =
            List(random.between(5, 8)) {
                Triple(width * random.range(0.36f, 0.64f), random.range(-24f, 24f),
                    height * random.range(0.16f, 0.40f))
            }.sortedByDescending { it.third }
        for ((x, tilt, length) in shards) {
            val breadth = width * random.range(0.035f, 0.065f)
            shard(cover, x, baseY, length, breadth, tilt, gem)
        }

        val rock = ridge(0.87f, 0f, 0.03f)
        canvas.drawPath(rock.fill, fillPaint(hsv(hue, 0.35f, 0.07f)))
        val sparkle = fillPaint(Color.WHITE)
        for (index in 0 until random.between(4, 8)) {
            val size = unit(random.range(0.012f, 0.025f))
            canvas.drawPath(starPath(width * random.range(0.2f, 0.8f),
                height * random.range(0.45f, 0.82f), size, size * 0.2f, 4), sparkle)
        }

        vignette(0.5f, 0.55f)
        grain(0.03f)
        Typeset(
            ink = mix(gem, Color.WHITE, 0.8f),
            authorInk = mix(gem, Color.WHITE, 0.4f),
            titleFont = random.pick(listOf(CoverFont.Serif, CoverFont.Sans)),
            titleWeight = random.pick(listOf(300, 600)),
            titleTracking = random.range(0.12f, 0.22f),
            titleSize = random.range(0.07f, 0.09f),
            authorFont = CoverFont.Sans,
            authorWeight = 400,
            authorTracking = 0.28f,
            rule = Rule.Line,
            shadow = 0.6f,
        )
    }

private fun shard(
    cover: CoverCanvas,
    x: Float,
    baseY: Float,
    length: Float,
    breadth: Float,
    tilt: Float,
    gem: Int,
) =
    with(cover) {
        val tone = mix(gem, Color.WHITE, random.range(0f, 0.3f))
        canvas.save()
        canvas.rotate(tilt, x, baseY)
        val shoulder = baseY - length * 0.78f
        val tip = baseY - length
        val left = Path()
        left.moveTo(x - breadth, baseY)
        left.lineTo(x - breadth, shoulder)
        left.lineTo(x, tip)
        left.lineTo(x, baseY)
        left.close()
        val right = Path()
        right.moveTo(x + breadth, baseY)
        right.lineTo(x + breadth, shoulder)
        right.lineTo(x, tip)
        right.lineTo(x, baseY)
        right.close()
        canvas.drawPath(left, fillPaint(mix(tone, Color.WHITE, 0.35f)))
        canvas.drawPath(right, fillPaint(shade(tone, 0.55f)))
        canvas.drawLine(x, baseY, x, tip,
            strokePaint(alpha(Color.WHITE, 0.7f), unit(0.003f)))
        canvas.restore()
    }

private val SKIES =
    listOf(
        Triple(0.98f, 0.07f, 0.12f),
        Triple(0.75f, 0.03f, 0.10f),
        Triple(0.60f, 0.52f, 0.14f),
    )

internal fun fantasySword(cover: CoverCanvas): Typeset =
    with(cover) {
        val (topHue, lowHue, sunHue) = random.pick(SKIES)
        val low = hsv(lowHue, random.range(0.55f, 0.75f), random.range(0.85f, 0.95f))
        verticalGradient(hsv(topHue, 0.75f, 0.22f), low, 0.9f)
        val sunY = height * random.range(0.58f, 0.63f)
        val sunRadius = width * random.range(0.24f, 0.30f)
        val sun = hsv(sunHue, 0.35f, 1f)
        radialGlow(width * 0.5f, sunY, sunRadius * 3f, sun, 0.5f, 2f)
        canvas.drawCircle(width * 0.5f, sunY, sunRadius, fillPaint(alpha(sun, 0.85f)))
        val ray = strokePaint(alpha(sun, 0.25f), unit(0.004f))
        for (index in 0 until 24) {
            val angle = TAU * index / 24
            canvas.drawLine(width * 0.5f + cos(angle) * sunRadius * 1.15f,
                sunY + sin(angle) * sunRadius * 1.15f,
                width * 0.5f + cos(angle) * sunRadius * 1.6f,
                sunY + sin(angle) * sunRadius * 1.6f, ray)
        }

        val ink = hsv(topHue, 0.5f, 0.08f)
        sword(cover, width * 0.5f, height * 0.56f, ink)
        val stone = wobblyRingPath(width * 0.5f, height * 0.93f, width * 0.32f,
            randomHarmonics(random, 3, 0.06f), squash = 0.45f)
        canvas.drawPath(stone, fillPaint(ink))
        canvas.drawRect(0f, height * 0.94f, width, height, fillPaint(ink))

        vignette(0.45f, 0.6f)
        grain(0.035f)
        frame(CoverFrame.Corners, sun)
        Typeset(
            ink = mix(sun, Color.WHITE, 0.6f),
            authorInk = mix(sun, Color.WHITE, 0.3f),
            titleFont = CoverFont.Serif,
            titleWeight = 700,
            titleTracking = random.range(0.08f, 0.14f),
            titleSize = random.range(0.08f, 0.10f),
            authorFont = CoverFont.SmallCaps,
            authorCase = LetterCase.Title,
            authorTracking = 0.22f,
            rule = Rule.Ornament,
            shadow = 0.7f,
        )
    }

private fun sword(cover: CoverCanvas, x: Float, guardY: Float, ink: Int) =
    with(cover) {
        val paint = fillPaint(ink)
        val blade = Path()
        blade.moveTo(x - unit(0.038f), guardY)
        blade.lineTo(x + unit(0.038f), guardY)
        blade.lineTo(x + unit(0.026f), height * 0.84f)
        blade.lineTo(x, height * 0.87f)
        blade.lineTo(x - unit(0.026f), height * 0.84f)
        blade.close()
        canvas.drawPath(blade, paint)
        val guard = Path()
        guard.moveTo(x - unit(0.15f), guardY + unit(0.035f))
        guard.quadTo(x, guardY - unit(0.045f), x + unit(0.15f), guardY + unit(0.035f))
        guard.quadTo(x, guardY - unit(0.005f), x - unit(0.15f), guardY + unit(0.035f))
        guard.close()
        canvas.drawPath(guard, paint)
        canvas.drawRect(x - unit(0.011f), guardY - unit(0.11f), x + unit(0.011f), guardY,
            paint)
        canvas.drawCircle(x, guardY - unit(0.12f), unit(0.024f), paint)
    }
