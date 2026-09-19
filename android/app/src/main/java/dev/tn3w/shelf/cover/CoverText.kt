package dev.tn3w.shelf.cover

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import kotlin.math.abs
import kotlin.math.sqrt

internal enum class CoverFont(val family: String, val fallback: String) {
    Sans("sans-serif", "sans-serif"),
    Serif("serif", "serif"),
    Condensed("sans-serif-condensed", "sans-serif"),
    Mono("monospace", "monospace"),
    Typewriter("serif-monospace", "monospace"),
    Script("cursive", "serif"),
    SmallCaps("sans-serif-smallcaps", "sans-serif"),
    Casual("casual", "sans-serif"),
}

internal enum class LetterCase {
    Upper,
    Title,
    Plain,
}

internal enum class Anchor {
    Top,
    Center,
    Bottom,
}

internal enum class Rule {
    None,
    Line,
    Bar,
    Ornament,
}

internal data class Typeset(
    val ink: Int,
    val authorInk: Int = ink,
    val titleFont: CoverFont = CoverFont.Serif,
    val titleWeight: Int = 700,
    val titleItalic: Boolean = false,
    val titleCase: LetterCase = LetterCase.Upper,
    val titleTracking: Float = 0.04f,
    val titleLeading: Float = 1.16f,
    val titleSize: Float = 0.105f,
    val authorFont: CoverFont = CoverFont.Sans,
    val authorWeight: Int = 500,
    val authorCase: LetterCase = LetterCase.Upper,
    val authorTracking: Float = 0.24f,
    val authorSize: Float = 0.026f,
    val anchor: Anchor = Anchor.Top,
    val rule: Rule = Rule.Line,
    val shadow: Float = 0f,
    val scrim: Float = 0f,
)

private object CoverFonts {
    private val resolved = HashMap<String, Typeface>()
    private val weighted = HashMap<String, Typeface>()
    private val probe = Paint()

    private fun family(font: CoverFont): Typeface =
        resolved.getOrPut(font.family) {
            val candidate = Typeface.create(font.family, Typeface.NORMAL)
            if (font.family == font.fallback || measures(candidate) != measures(DEFAULT)) {
                candidate
            } else {
                Typeface.create(font.fallback, Typeface.NORMAL)
            }
        }

    private fun measures(typeface: Typeface): Float {
        probe.typeface = typeface
        probe.textSize = 64f
        return probe.measureText("Hamburgefonstiv 123")
    }

    private val DEFAULT = Typeface.create("sans-serif", Typeface.NORMAL)

    fun typeface(font: CoverFont, weight: Int, italic: Boolean): Typeface =
        weighted.getOrPut("${font.name}-$weight-$italic") {
            val base = family(font)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, weight.coerceIn(100, 900), italic)
            } else {
                val style =
                    when {
                        weight >= 600 && italic -> Typeface.BOLD_ITALIC
                        weight >= 600 -> Typeface.BOLD
                        italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                Typeface.create(base, style)
            }
        }
}

private fun cased(text: String, letterCase: LetterCase) =
    when (letterCase) {
        LetterCase.Upper -> text.uppercase()
        LetterCase.Title -> text
        LetterCase.Plain -> text.lowercase()
    }

private fun textPaint(font: CoverFont, weight: Int, italic: Boolean, tracking: Float) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = CoverFonts.typeface(font, weight, italic)
        letterSpacing = tracking
        textAlign = Paint.Align.CENTER
    }

private fun wrap(text: String, paint: Paint, limit: Float): List<String>? {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(candidate) <= limit || current.isEmpty()) {
            current = candidate
        } else {
            lines += current
            current = word
        }
    }
    lines += current
    return if (lines.any { paint.measureText(it) > limit }) null else lines
}

private class TitleLayout(val lines: List<String>, val paint: Paint, val size: Float)

private fun fitTitle(
    text: String,
    typeset: Typeset,
    limit: Float,
    available: Float,
    ceiling: Float,
): TitleLayout {
    val paint =
        textPaint(typeset.titleFont, typeset.titleWeight, typeset.titleItalic,
            typeset.titleTracking)
    var size = ceiling
    val step = (ceiling / 40f).coerceAtLeast(0.5f)
    while (size > ceiling * 0.25f) {
        paint.textSize = size
        val lines = wrap(text, paint, limit)
        if (lines != null && lines.size <= 4 && lines.size * size * typeset.titleLeading <=
            available) {
            return TitleLayout(lines, paint, size)
        }
        size -= step
    }
    paint.textSize = size
    return TitleLayout(wrap(text, paint, limit) ?: listOf(text), paint, size)
}

private fun bandStats(bitmap: Bitmap, top: Float, bottom: Float): FloatArray {
    val first = top.toInt().coerceIn(0, bitmap.height - 1)
    val last = bottom.toInt().coerceIn(first + 1, bitmap.height)
    val pixels = IntArray(bitmap.width * (last - first))
    bitmap.getPixels(pixels, 0, bitmap.width, 0, first, bitmap.width, last - first)
    var sum = 0f
    var squares = 0f
    var count = 0
    var index = 0
    while (index < pixels.size) {
        val value = luminance(pixels[index])
        sum += value
        squares += value * value
        count++
        index += 7
    }
    val mean = sum / count
    return floatArrayOf(mean, sqrt((squares / count - mean * mean).coerceAtLeast(0f)))
}

private fun CoverCanvas.legibleInk(top: Float, bottom: Float, ink: Int, target: Float): Int {
    val stats = bandStats(bitmap, top, bottom)
    val textLight = luminance(ink) > stats[0]
    val toward = if (textLight) Color.BLACK else Color.WHITE
    val busy = ((stats[1] - 0.13f) * 2.2f).coerceIn(0f, 0.5f)
    val deficit = (target - abs(luminance(ink) - stats[0])).coerceAtLeast(0f)
    scrimBand(top, bottom, toward, (busy + deficit * 1.3f).coerceAtMost(0.82f))

    val settled = bandStats(bitmap, top, bottom)[0]
    val gap = abs(luminance(ink) - settled)
    if (gap >= target * 0.8f) return ink
    val pull = if (textLight) Color.WHITE else Color.rgb(10, 10, 12)
    return mix(ink, pull, ((target * 0.8f - gap) * 2.2f).coerceAtMost(0.85f))
}

private fun CoverCanvas.drawLine(text: String, centerY: Float, paint: Paint, ink: Int,
    shadow: Float) {
    val offset = paint.letterSpacing * paint.textSize / 2f
    if (shadow > 0f) {
        val shade = Paint(paint)
        shade.color = Color.argb((220 * shadow).toInt().coerceIn(0, 255), 0, 0, 0)
        shade.maskFilter = BlurMaskFilter(unit(0.012f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawText(text, width / 2f + offset, centerY, shade)
    }
    paint.color = ink
    canvas.drawText(text, width / 2f + offset, centerY, paint)
}

private fun CoverCanvas.drawRule(rule: Rule, centerY: Float, ink: Int) {
    when (rule) {
        Rule.None -> return
        Rule.Line ->
            canvas.drawRect(width / 2f - unit(0.10f), centerY, width / 2f + unit(0.10f),
                centerY + unit(0.003f), fillPaint(ink))
        Rule.Bar ->
            canvas.drawRect(width / 2f - unit(0.14f), centerY, width / 2f + unit(0.14f),
                centerY + unit(0.009f), fillPaint(ink))
        Rule.Ornament -> {
            val mark = unit(0.010f)
            for (offset in listOf(-unit(0.055f), 0f, unit(0.055f))) {
                canvas.drawPath(
                    starPath(width / 2f + offset, centerY, mark, mark * 0.35f, 4, 0f),
                    fillPaint(ink),
                )
            }
        }
    }
}

internal fun CoverCanvas.drawTypography(title: String, author: String, typeset: Typeset) {
    val margin = unit(0.11f)
    val tracking =
        if (typeset.titleCase == LetterCase.Upper) typeset.titleTracking
        else typeset.titleTracking.coerceAtMost(0.03f)
    val settled = typeset.copy(titleTracking = tracking)
    val layout =
        fitTitle(
            cased(title, settled.titleCase),
            settled,
            width - margin * 2,
            height * 0.30f,
            height * settled.titleSize * 1.6f,
        )
    val block = layout.lines.size * layout.size * settled.titleLeading
    val top =
        when (settled.anchor) {
            Anchor.Top -> height * 0.085f
            Anchor.Center -> (height - block) / 2f
            Anchor.Bottom -> height * 0.86f - block
        }

    scrimBand(top - layout.size * 0.5f, top + block + layout.size * 0.4f, Color.BLACK,
        settled.scrim)
    val titleInk =
        legibleInk(top - layout.size * 0.5f, top + block + layout.size * 0.4f, settled.ink, 0.45f)

    val authorPaint =
        textPaint(settled.authorFont, settled.authorWeight, false, settled.authorTracking)
    authorPaint.textSize = height * settled.authorSize
    val authorY = height * if (settled.anchor == Anchor.Top) 0.895f else 0.075f
    val ruleY = authorY - height * 0.030f
    val authorInk =
        legibleInk(ruleY - height * 0.03f, authorY + height * 0.05f, settled.authorInk, 0.40f)

    for (index in layout.lines.indices) {
        val baseline = top + layout.size * (index * settled.titleLeading + 0.82f)
        drawLine(layout.lines[index], baseline, layout.paint, titleInk, settled.shadow)
    }
    drawRule(settled.rule, ruleY, authorInk)
    drawLine(cased(author, settled.authorCase), authorY, authorPaint, authorInk, settled.shadow)
}
