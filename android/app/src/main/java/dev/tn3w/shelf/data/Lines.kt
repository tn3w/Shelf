package dev.tn3w.shelf.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val LINE_HEIGHT = 48
private const val MAX_LINE_WIDTH = 960
private const val EDGE_FLOOR = 60f
private const val EDGE_FACTOR = 2.0f
private const val BLOCK = 32

class Gray(val width: Int, val height: Int, val pixels: FloatArray) {
    fun sample(x: Float, y: Float): Float {
        val clampedX = x.coerceIn(0f, width - 1.001f)
        val clampedY = y.coerceIn(0f, height - 1.001f)
        val left = clampedX.toInt()
        val top = clampedY.toInt()
        val fractionX = clampedX - left
        val index = top * width + left
        val upper = pixels[index] + (pixels[index + 1] - pixels[index]) * fractionX
        val below = index + width
        val lower = pixels[below] + (pixels[below + 1] - pixels[below]) * fractionX
        return upper + (lower - upper) * (clampedY - top)
    }

    companion object {
        fun of(bytes: ByteArray, width: Int, height: Int, rowStride: Int = width) = Gray(
            width, height,
            FloatArray(width * height) {
                (bytes[it / width * rowStride + it % width].toInt() and 0xFF).toFloat()
            },
        )
    }
}

private data class CropArea(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val outputWidth: Int,
)

class LineBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val glyphs: Int = 1,
) {
    val width
        get() = right - left
    val height
        get() = bottom - top
}

class Dot(val x: Float, val y: Float)

fun sharpness(frame: Gray): Double {
    var sum = 0.0
    var squares = 0.0
    var count = 0
    for (y in 2 until frame.height - 2 step 2) {
        for (x in 2 until frame.width - 2 step 2) {
            val index = y * frame.width + x
            val pixels = frame.pixels
            val value =
                pixels[index - 1] + pixels[index + 1] + pixels[index - frame.width] +
                    pixels[index + frame.width] - 4 * pixels[index]
            sum += value
            squares += value * value
            count++
        }
    }
    val mean = sum / count
    return squares / count - mean * mean
}

fun skew(frame: Gray): Double {
    var real = 0.0
    var imaginary = 0.0
    val pixels = frame.pixels
    for (y in 1 until frame.height - 1 step 2) {
        for (x in 1 until frame.width - 1 step 2) {
            val index = y * frame.width + x
            val gradientX = (pixels[index + 1] - pixels[index - 1]).toDouble()
            val below = pixels[index + frame.width]
            val gradientY = (below - pixels[index - frame.width]).toDouble()
            val squared = gradientX * gradientX + gradientY * gradientY
            if (squared < 400) continue
            val dx = (x - frame.width / 2.0) / frame.width
            val dy = (y - frame.height / 2.0) / frame.height
            val weight = exp(-4 * (dx * dx + dy * dy)) / (squared * squared)
            val xx = gradientX * gradientX
            val yy = gradientY * gradientY
            real += weight * (xx * xx - 6 * xx * yy + yy * yy)
            imaginary += weight * 4 * gradientX * gradientY * (xx - yy)
        }
    }
    return atan2(imaginary, real) / 4
}

class View(private val frame: Gray, angle: Double, val width: Int, val height: Int) {
    private val cosine = cos(angle).toFloat()
    private val sine = sin(angle).toFloat()

    private fun frameX(u: Float, v: Float) =
        frame.width / 2f + cosine * (u - width / 2f) - sine * (v - height / 2f)

    private fun frameY(u: Float, v: Float) =
        frame.height / 2f + sine * (u - width / 2f) + cosine * (v - height / 2f)

    fun at(u: Float, v: Float) = frame.sample(frameX(u, v), frameY(u, v))

    fun dots(box: LineBox, positions: List<Float>, flipped: Boolean): List<Dot> {
        val area = area(box, MAX_LINE_WIDTH)
        val v = (box.top + box.bottom) / 2
        return positions.map {
            val u = area.left + area.width * if (flipped) 1 - it else it
            Dot(frameX(u, v), frameY(u, v))
        }
    }

    private fun area(box: LineBox, limit: Int): CropArea {
        val marginY = box.height * 0.2f
        val spanY = box.height + 2 * marginY
        val fullWidth = (box.width + box.height * 0.5f) * LINE_HEIGHT / spanY
        val outputWidth = fullWidth.roundToInt().coerceIn(LINE_HEIGHT, limit)
        val left = box.left - box.height * 0.25f
        val spanX = outputWidth * spanY / LINE_HEIGHT
        return CropArea(left, box.top - marginY, spanX, spanY, outputWidth)
    }

    fun render() = Gray(
        width, height,
        FloatArray(width * height) {
            at((it % width).toFloat(), (it / width).toFloat())
        },
    )

    fun crop(box: LineBox, flipped: Boolean, limit: Int = MAX_LINE_WIDTH): Gray {
        val (left, top, spanX, spanY, outputWidth) = area(box, limit)
        val step = spanY / LINE_HEIGHT
        val samples = step.toInt().coerceIn(1, 4)
        val pixels = FloatArray(outputWidth * LINE_HEIGHT) { index ->
            val column = index % outputWidth
            val row = index / outputWidth
            var total = 0f
            for (subY in 0 until samples) {
                for (subX in 0 until samples) {
                    val u =
                        left + (column + (subX + 0.5f) / samples) * spanX / outputWidth
                    val v = top + (row + (subY + 0.5f) / samples) * step
                    total += if (flipped) {
                        at(2 * left + spanX - u, 2 * top + spanY - v)
                    } else {
                        at(u, v)
                    }
                }
            }
            total / (samples * samples)
        }
        return Gray(outputWidth, LINE_HEIGHT, pixels)
    }
}

private fun edges(image: Gray): BooleanArray {
    val width = image.width
    val pixels = image.pixels
    val gradient = FloatArray(pixels.size)
    for (y in 1 until image.height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            val right = pixels[index + 1 - width] + 2 * pixels[index + 1] +
                pixels[index + 1 + width]
            val left = pixels[index - 1 - width] + 2 * pixels[index - 1] +
                pixels[index - 1 + width]
            gradient[index] = abs(right - left)
        }
    }
    val local = localMeans(gradient, width, image.height)
    val columns = (width + BLOCK - 1) / BLOCK
    return BooleanArray(pixels.size) { index ->
        val block = index / width / BLOCK * columns + index % width / BLOCK
        gradient[index] > max(EDGE_FLOOR, EDGE_FACTOR * local[block])
    }
}

private fun localMeans(values: FloatArray, width: Int, height: Int): FloatArray {
    val columns = (width + BLOCK - 1) / BLOCK
    val rows = (height + BLOCK - 1) / BLOCK
    val sums = FloatArray(columns * rows)
    val counts = IntArray(columns * rows)
    for (index in values.indices) {
        val block = index / width / BLOCK * columns + index % width / BLOCK
        sums[block] += values[index]
        counts[block]++
    }
    return FloatArray(columns * rows) { block ->
        var total = 0f
        var count = 0
        for (row in block / columns - 1..block / columns + 1) {
            for (column in block % columns - 1..block % columns + 1) {
                if (row !in 0 until rows || column !in 0 until columns) continue
                total += sums[row * columns + column]
                count += counts[row * columns + column]
            }
        }
        total / count
    }
}

private class Component(var left: Int, var top: Int, var right: Int, var bottom: Int) {
    var edges = 0
    val width
        get() = right - left + 1
    val height
        get() = bottom - top + 1
}

private fun components(edges: BooleanArray, width: Int): List<Component> {
    val parent = IntArray(edges.size) { it }
    fun root(index: Int): Int {
        var current = index
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }
    for (index in edges.indices) {
        if (!edges[index]) continue
        if (index % width > 0 && edges[index - 1]) parent[root(index)] = root(index - 1)
        if (index >= width && edges[index - width]) {
            parent[root(index)] = root(index - width)
        }
    }
    val found = HashMap<Int, Component>()
    for (index in edges.indices) {
        if (!edges[index]) continue
        val x = index % width
        val y = index / width
        val component = found.getOrPut(root(index)) { Component(x, y, x, y) }
        component.left = min(component.left, x)
        component.right = max(component.right, x)
        component.top = min(component.top, y)
        component.bottom = max(component.bottom, y)
        component.edges++
    }
    return found.values.toList()
}

private fun isGlyph(component: Component, maximum: Int): Boolean {
    val box = component.width * component.height
    return component.height in 6..maximum &&
        component.width <= component.height * 12 &&
        component.edges >= box * 0.1
}

private class Group(first: LineBox) {
    var box = first
    private var tops = first.top
    private var bottoms = first.bottom
    private var aspects = first.width / first.height
    private var count = 1
    private var glyphs = first.glyphs

    val aspect
        get() = aspects / count

    fun accepts(glyph: LineBox): Boolean {
        val top = tops / count
        val bottom = bottoms / count
        val overlap = min(bottom, glyph.bottom) - max(top, glyph.top)
        val smaller = min(bottom - top, glyph.height)
        val ratio = max(bottom - top, glyph.height) / smaller
        val gap = glyph.left - box.right
        return overlap >= smaller * 0.6f && ratio <= 2.2f &&
            gap <= max(bottom - top, glyph.height)
    }

    fun add(glyph: LineBox) {
        tops += glyph.top
        bottoms += glyph.bottom
        aspects += glyph.width / glyph.height
        count++
        glyphs += glyph.glyphs
        box = LineBox(
            min(box.left, glyph.left), min(box.top, glyph.top),
            max(box.right, glyph.right), max(box.bottom, glyph.bottom), glyphs,
        )
    }
}

private fun half(image: Gray): Gray {
    val width = image.width / 2
    val height = image.height / 2
    val pixels = image.pixels
    return Gray(
        width, height,
        FloatArray(width * height) { index ->
            val source = (index / width) * 2 * image.width + (index % width) * 2
            (
                pixels[source] + pixels[source + 1] + pixels[source + image.width] +
                    pixels[source + image.width + 1]
                ) / 4
        },
    )
}

private fun linesAt(image: Gray, maximum: Int): List<LineBox> {
    val glyphs = components(edges(image), image.width)
        .filter { isGlyph(it, maximum) }
        .map {
            val glyphs = max(1, it.width / it.height)
            LineBox(
                it.left.toFloat(), it.top.toFloat(), it.right + 1f, it.bottom + 1f,
                glyphs,
            )
        }
        .sortedBy { it.left }
    val groups = mutableListOf<Group>()
    for (glyph in glyphs) {
        val group = groups.firstOrNull { it.accepts(glyph) }
        if (group == null) groups += Group(glyph) else group.add(glyph)
    }
    return groups
        .filter { it.aspect >= 0.35f }
        .map { it.box }
        .filter { it.glyphs >= 2 && it.width >= it.height * 1.2f }
}

private fun inside(inner: LineBox, outer: LineBox): Boolean {
    val width = min(inner.right, outer.right) - max(inner.left, outer.left)
    val height = min(inner.bottom, outer.bottom) - max(inner.top, outer.top)
    return width > 0 && height > 0 && width * height >= 0.6f * inner.width * inner.height
}

fun findLines(image: Gray): List<LineBox> {
    val found = mutableListOf<LineBox>()
    var level = image
    for (depth in 0 until 3) {
        val scale = (1 shl depth).toFloat()
        val maximum = if (depth == 2) level.height / 2 else 40
        found += linesAt(level, maximum).map {
            LineBox(
                it.left * scale, it.top * scale, it.right * scale, it.bottom * scale,
                it.glyphs,
            )
        }
        level = half(level)
    }
    val kept = mutableListOf<LineBox>()
    for (line in found.sortedByDescending { it.height }) {
        if (line.height > image.height / 4f) continue
        if (kept.none { inside(line, it) && it.height < line.height * 2 }) kept += line
    }
    return kept.sortedByDescending {
        val x = ((it.left + it.right) / 2 - image.width / 2f) / image.width
        val y = ((it.top + it.bottom) / 2 - image.height / 2f) / image.height
        sqrt(it.height) * min(it.glyphs, 8) * exp(-(x * x + y * y) / 0.18f)
    }
}
