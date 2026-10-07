package dev.tn3w.shelf.cover.art

import android.graphics.Color
import android.graphics.Path
import dev.tn3w.shelf.cover.*
import kotlin.math.cos
import kotlin.math.sin

private fun noirType(cover: CoverCanvas, accent: Int): Typeset = with(cover) {
    Typeset(
        ink = Color.rgb(240, 236, 228),
        authorInk = accent,
        font = random.pick(listOf(CoverFont.Sans, CoverFont.Condensed)),
        upper = true,
    )
}

internal fun mysteryStreet(cover: CoverCanvas): Typeset = with(cover) {
    val night = hsv(
        random.range(0.58f, 0.66f),
        random.range(0.30f, 0.55f),
        random.range(0.08f, 0.13f),
    )
    val accent = hsv(random.range(0.09f, 0.13f), 0.70f, 0.95f)
    canvas.drawColor(night)

    val lampX = random.range(0.35f, 0.65f) * width
    val lampY = height * 0.36f
    val spread = width * random.range(0.28f, 0.38f)
    val beam = Path()
    beam.moveTo(lampX, lampY)
    beam.lineTo(lampX - spread, height)
    beam.lineTo(lampX + spread, height)
    beam.close()
    canvas.drawPath(beam, fillPaint(alpha(accent, 0.22f)))

    val ground = height * 0.90f
    val personX = lampX + random.range(-0.10f, 0.10f) * width
    val personHeight = random.range(0.20f, 0.26f) * height
    val shoulder = personHeight * 0.22f
    val figure = Path()
    figure.moveTo(personX - shoulder, ground)
    figure.lineTo(personX - shoulder * 0.8f, ground - personHeight * 0.72f)
    figure.lineTo(personX, ground - personHeight)
    figure.lineTo(personX + shoulder * 0.8f, ground - personHeight * 0.72f)
    figure.lineTo(personX + shoulder, ground)
    figure.close()
    val hat = personHeight * 0.20f
    figure.addOval(
        personX - hat,
        ground - personHeight - hat * 0.5f,
        personX + hat,
        ground - personHeight + hat * 0.4f,
        Path.Direction.CW,
    )
    figure.addRect(0f, ground, width, height, Path.Direction.CW)
    canvas.drawPath(figure, fillPaint(Color.rgb(5, 5, 8)))

    noirType(cover, accent)
}

internal fun mysteryKeyhole(cover: CoverCanvas): Typeset = with(cover) {
    val wall = hsv(
        random.range(0.55f, 0.65f),
        random.range(0.18f, 0.35f),
        random.range(0.10f, 0.16f),
    )
    val warm = hsv(random.range(0.09f, 0.13f), random.range(0.35f, 0.55f), 1f)
    canvas.drawColor(wall)

    val centerX = width * 0.5f
    val centerY = height * 0.54f
    val head = width * random.range(0.11f, 0.14f)
    val shaft = head * random.range(1.6f, 2.0f)
    val keyhole = Path()
    keyhole.addCircle(centerX, centerY, head, Path.Direction.CW)
    keyhole.moveTo(centerX - head * 0.36f, centerY + head * 0.55f)
    keyhole.lineTo(centerX + head * 0.36f, centerY + head * 0.55f)
    keyhole.lineTo(centerX + head * 0.80f, centerY + shaft)
    keyhole.lineTo(centerX - head * 0.80f, centerY + shaft)
    keyhole.close()
    canvas.drawPath(keyhole, fillPaint(warm))

    val floor = Path()
    floor.moveTo(centerX - head * 0.80f, centerY + shaft)
    floor.lineTo(centerX + head * 0.80f, centerY + shaft)
    floor.lineTo(centerX + width * 0.40f, height)
    floor.lineTo(centerX - width * 0.40f, height)
    floor.close()
    canvas.drawPath(floor, fillPaint(alpha(warm, 0.14f)))

    noirType(cover, warm)
}

internal fun mysteryPrint(cover: CoverCanvas): Typeset = with(cover) {
    val charcoal = hsv(
        random.range(0.55f, 0.68f),
        random.range(0.10f, 0.25f),
        random.range(0.11f, 0.15f),
    )
    val accent = hsv(
        random.pick(listOf(0f, 0.02f, 0.12f, 0.55f)),
        random.range(0.55f, 0.85f),
        random.range(0.75f, 0.95f),
    )
    canvas.drawColor(charcoal)

    val centerX = width * 0.5f
    val centerY = height * 0.60f
    val harmonics = randomHarmonics(random, 3, random.range(0.030f, 0.050f))
    val paint = strokePaint(accent, unit(0.006f))
    for (index in 0 until random.between(12, 15)) {
        val radius = width * (0.03f + index * 0.022f)
        val ring = wobblyRingPath(centerX, centerY, radius, harmonics, 240, 1.22f)
        if (index == 0) {
            canvas.drawPath(ring, paint)
            continue
        }
        canvas.save()
        val angle = random.range(0f, TAU)
        val reach = radius * 3f
        val gap = Path()
        gap.moveTo(centerX, centerY)
        gap.lineTo(centerX + cos(angle) * reach, centerY + sin(angle) * reach)
        gap.lineTo(
            centerX + cos(angle + 0.5f) * reach,
            centerY + sin(angle + 0.5f) * reach,
        )
        gap.close()
        canvas.clipOutPath(gap)
        canvas.drawPath(ring, paint)
        canvas.restore()
    }

    noirType(cover, accent)
}

internal fun horrorCracks(cover: CoverCanvas): Typeset = with(cover) {
    val blood = hsv(
        random.range(0.98f, 1.02f),
        random.range(0.75f, 0.95f),
        random.range(0.45f, 0.65f),
    )
    canvas.drawColor(Color.rgb(16, 11, 12))
    val centerX = width * random.range(0.4f, 0.6f)
    val centerY = height * random.range(0.45f, 0.55f)

    val cracks = Path()
    repeat(random.between(3, 5)) {
        branch(
            cover,
            cracks,
            centerX,
            centerY,
            random.range(0f, TAU),
            height * random.range(0.10f, 0.16f),
            5,
        )
    }
    val ink = mix(blood, Color.rgb(255, 210, 200), 0.25f)
    canvas.drawPath(cracks, strokePaint(ink, unit(0.004f)))

    horrorType(cover, blood)
}

private fun branch(
    cover: CoverCanvas,
    path: Path,
    x: Float,
    y: Float,
    angle: Float,
    length: Float,
    depth: Int,
) {
    if (depth == 0 || length < cover.width * 0.01f) return
    val endX = x + cos(angle) * length
    val endY = y + sin(angle) * length
    path.moveTo(x, y)
    path.lineTo(endX, endY)
    repeat(cover.random.between(1, 2)) {
        branch(
            cover,
            path,
            endX,
            endY,
            angle + cover.random.range(-0.8f, 0.8f),
            length * cover.random.range(0.5f, 0.78f),
            depth - 1,
        )
    }
}

private fun horrorType(cover: CoverCanvas, blood: Int): Typeset = with(cover) {
    Typeset(
        ink = mix(blood, Color.rgb(255, 245, 240), 0.55f),
        authorInk = Color.rgb(190, 178, 175),
        upper = true,
    )
}

internal fun horrorMoon(cover: CoverCanvas): Typeset = with(cover) {
    val blood = hsv(
        random.range(0.97f, 1.03f),
        random.range(0.55f, 0.85f),
        random.range(0.60f, 0.80f),
    )
    canvas.drawColor(
        hsv(
            random.range(0.62f, 0.72f),
            random.range(0.45f, 0.70f),
            random.range(0.06f, 0.10f),
        ),
    )

    canvas.drawCircle(
        width * 0.5f,
        height * random.range(0.48f, 0.54f),
        width * random.range(0.22f, 0.27f),
        fillPaint(mix(blood, Color.rgb(255, 210, 190), 0.30f)),
    )

    val branches = Path()
    branch(
        cover,
        branches,
        width * random.range(0.35f, 0.65f),
        height * 1.02f,
        -TAU / 4f + random.range(-0.2f, 0.2f),
        height * random.range(0.18f, 0.24f),
        6,
    )
    canvas.drawPath(branches, strokePaint(Color.rgb(8, 6, 9), unit(0.008f)))

    horrorType(cover, blood)
}

internal fun horrorDrip(cover: CoverCanvas): Typeset = with(cover) {
    val blood = hsv(
        random.range(0.98f, 1.01f),
        random.range(0.85f, 0.95f),
        random.range(0.55f, 0.68f),
    )
    val bone = hsv(
        random.range(0.08f, 0.12f),
        random.range(0.06f, 0.14f),
        random.range(0.88f, 0.94f),
    )
    canvas.drawColor(bone)

    val band = height * 0.11f
    val path = Path()
    path.moveTo(0f, 0f)
    path.lineTo(0f, band)
    var x = 0f
    while (x < width) {
        val bulb = unit(random.range(0.014f, 0.026f))
        val length = height * random.range(0.1f, 0.62f).let { it * it }
        val center = x + unit(random.range(0.06f, 0.14f)) + bulb
        if (center + bulb * 2f > width) break
        path.quadTo((x + center) / 2f, band, center - bulb * 1.8f, band)
        drip(path, center, band, length, bulb)
        x = center + bulb * 1.8f
    }
    path.lineTo(width, band)
    path.lineTo(width, 0f)
    path.close()
    canvas.drawPath(path, fillPaint(blood))

    Typeset(
        ink = Color.rgb(24, 14, 14),
        authorInk = bone,
        upper = true,
        anchor = Anchor.Bottom,
    )
}

private fun drip(path: Path, x: Float, top: Float, length: Float, bulb: Float) {
    val neck = bulb * 0.55f
    val bottom = top + length
    val shoulder = top + length * 0.3f
    path.cubicTo(x - neck, top, x - neck, shoulder, x - neck, bottom - bulb * 1.1f)
    path.cubicTo(
        x - bulb * 1.45f,
        bottom + bulb * 1.35f,
        x + bulb * 1.45f,
        bottom + bulb * 1.35f,
        x + neck,
        bottom - bulb * 1.1f,
    )
    path.cubicTo(x + neck, shoulder, x + neck, top, x + bulb * 1.8f, top)
}

internal fun horrorFog(cover: CoverCanvas): Typeset = with(cover) {
    val haze = hsv(
        random.range(0.30f, 0.55f),
        random.range(0.06f, 0.18f),
        random.range(0.55f, 0.70f),
    )
    canvas.drawColor(shade(haze, 0.5f))
    canvas.drawCircle(
        width * random.range(0.30f, 0.70f),
        height * random.range(0.42f, 0.48f),
        width * 0.1f,
        fillPaint(mix(haze, Color.WHITE, 0.6f)),
    )

    for (layer in 0 until 2) {
        val trees = Path()
        repeat(random.between(2, 4)) {
            branch(
                cover,
                trees,
                random.range(0.05f, 0.95f) * width,
                height * 1.02f,
                -TAU / 4f + random.range(-0.15f, 0.15f),
                height * random.range(0.12f, 0.18f) * (0.8f + layer * 0.3f),
                6,
            )
        }
        val tone = mix(haze, Color.rgb(8, 10, 10), 0.45f + layer * 0.45f)
        canvas.drawPath(trees, strokePaint(tone, unit(0.006f + layer * 0.006f)))
        if (layer == 0) scrimBand(height * 0.50f, height * 1.4f, haze, 0.5f)
    }

    Typeset(ink = Color.rgb(236, 232, 226), upper = true)
}
