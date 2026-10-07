package dev.tn3w.shelf.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.LruCache
import dev.tn3w.shelf.cover.art.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal typealias ArtDirection = (CoverCanvas) -> Typeset

internal fun directions(genre: CoverGenre): List<ArtDirection> = when (genre) {
    CoverGenre.Fantasy ->
        listOf(
            ::fantasyPeaks,
            ::fantasySigil,
            ::fantasyForest,
            ::fantasyDawn,
            ::fantasyIlluminated,
            ::fantasyCrystal,
            ::fantasySword,
        )

    CoverGenre.Dystopian ->
        listOf(
            ::dystopianMonolith,
            ::dystopianEye,
            ::dystopianBlocks,
            ::dystopianPropaganda,
            ::dystopianBarcode,
        )

    CoverGenre.ScienceFiction ->
        listOf(
            ::scifiPlanet,
            ::scifiOrbit,
            ::scifiCircuit,
            ::scifiTunnel,
            ::scifiRetro,
            ::dystopianGlitch,
        )

    CoverGenre.Mystery ->
        listOf(
            ::mysteryStreet,
            ::mysteryKeyhole,
            ::mysteryPrint,
            ::mysteryBlinds,
            ::mysteryRedThread,
        )

    CoverGenre.Horror ->
        listOf(::horrorMoon, ::horrorCracks, ::horrorDrip, ::horrorFog)

    CoverGenre.Romance ->
        listOf(::romanceBotanical, ::romanceDeco, ::romanceBloom, ::romanceLetter)

    CoverGenre.Adventure ->
        listOf(::adventureMap, ::adventureCompass, ::travelRoute, ::seigaihaWaves)

    CoverGenre.Children ->
        listOf(::childrenMeadow, ::childrenBalloons, ::childrenRainbow, ::natureTree)

    CoverGenre.Poetry ->
        listOf(::poetryWash, ::poetryMoons, ::poetryRain, ::literaryFields)

    CoverGenre.Nature ->
        listOf(::natureContours, ::natureLeaves, ::natureLake, ::natureTree)

    CoverGenre.Travel ->
        listOf(::travelPoster, ::travelStamp, ::travelRoute, ::natureLake)

    CoverGenre.Spiritual ->
        listOf(::spiritualRays, ::spiritualMandala, ::spiritualEnso, ::spiritualLotus)

    CoverGenre.Business ->
        listOf(::businessAscent, ::businessGraph, ::swissGrid, ::technicalBlueprint)

    CoverGenre.Humor ->
        listOf(::humorConfetti, ::humorPop, ::risoHalftone, ::childrenBalloons)

    CoverGenre.Vintage ->
        listOf(::vintageBands, ::penguinBands, ::vintageDeco, ::bauhausTiles)

    CoverGenre.Biography ->
        listOf(::biographyPortrait, ::biographyCameo, ::historyLaurel, ::penguinBands)

    CoverGenre.History ->
        listOf(::historyEmblem, ::historyLaurel, ::historyTemple, ::fantasyIlluminated)

    CoverGenre.Technical ->
        listOf(::technicalGeometry, ::technicalBlueprint, ::technicalAtom, ::bauhausTiles)

    CoverGenre.Literary ->
        listOf(::literaryType, ::literaryShape, ::penguinBands, ::literaryFields)
}

data class CoverRequest(
    val work: Int,
    val title: String,
    val author: String,
    val slugs: List<String> = emptyList(),
)

fun renderCover(request: CoverRequest, widthPixels: Int): Bitmap {
    val random = CoverRandom(coverSeed(request.work, request.title, request.author))
    val direction = random.pick(directions(classify(request.slugs)))
    val width = widthPixels.coerceIn(48, 2048)
    val bitmap = Bitmap.createBitmap(width, width * 3 / 2, Bitmap.Config.ARGB_8888)
    val cover = CoverCanvas(bitmap, random)
    cover.drawTypography(request.title, request.author, direction(cover))
    return bitmap
}

object Covers {
    private const val VERSION = "v5"
    private const val DISK_LIMIT_BYTES = 32L * 1024 * 1024
    private val pruned = AtomicBoolean()

    private val webp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private val compressFormat =
        if (webp) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
    private val extension = if (webp) "webp" else "jpg"

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8192).toInt().coerceIn(2048, 32768),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun cached(context: Context, request: CoverRequest, widthPixels: Int): Bitmap {
        val width = snap(widthPixels)
        val seed = coverSeed(request.work, request.title, request.author)
        val genre = classify(request.slugs)
        val key = "$VERSION-$seed-${genre.name}-$width"
        memory.get(key)?.let { return it }

        val directory = File(context.cacheDir, "covers").apply { mkdirs() }
        if (pruned.compareAndSet(false, true)) prune(directory)

        val file = File(directory, "$key.$extension")
        decode(file)?.let {
            file.setLastModified(System.currentTimeMillis())
            memory.put(key, it)
            return it
        }
        val bitmap = renderCover(request, width)
        memory.put(key, bitmap)
        store(file, bitmap)
        return bitmap
    }

    private fun snap(widthPixels: Int): Int {
        val steps = intArrayOf(120, 180, 240, 320, 420, 560, 720)
        return steps.firstOrNull { it >= widthPixels } ?: steps.last()
    }

    private fun prune(directory: File) {
        val files = directory.listFiles() ?: return
        val (current, stale) = files.partition {
            it.name.startsWith("$VERSION-") && it.extension == extension
        }
        stale.forEach { it.delete() }

        var total = 0L
        current.sortedByDescending { it.lastModified() }.forEach {
            total += it.length()
            if (total > DISK_LIMIT_BYTES) it.delete()
        }
    }

    private fun decode(file: File): Bitmap? = if (!file.exists()) {
        null
    } else {
        runCatching { BitmapFactory.decodeFile(file.path) }
            .getOrNull()
    }

    private fun store(file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(compressFormat, 82, it) }
        }
    }
}

enum class CoverGenre {
    Fantasy,
    Dystopian,
    ScienceFiction,
    Mystery,
    Horror,
    Romance,
    Adventure,
    Children,
    Poetry,
    Nature,
    Travel,
    Spiritual,
    Business,
    Humor,
    Vintage,
    Biography,
    History,
    Technical,
    Literary,
}

private val SLUG_GENRES = mapOf(
    "epic-fantasy" to (CoverGenre.Fantasy to 5),
    "urban-fantasy" to (CoverGenre.Fantasy to 5),
    "fantasy" to (CoverGenre.Fantasy to 6),
    "folklore" to (CoverGenre.Fantasy to 5),
    "dystopian" to (CoverGenre.Dystopian to 7),
    "science-fiction" to (CoverGenre.ScienceFiction to 6),
    "space-opera" to (CoverGenre.ScienceFiction to 7),
    "cozy-mystery" to (CoverGenre.Mystery to 7),
    "mystery" to (CoverGenre.Mystery to 6),
    "crime" to (CoverGenre.Mystery to 6),
    "thriller" to (CoverGenre.Mystery to 6),
    "true-crime" to (CoverGenre.Mystery to 5),
    "horror" to (CoverGenre.Horror to 7),
    "paranormal" to (CoverGenre.Horror to 6),
    "romance" to (CoverGenre.Romance to 6),
    "contemporary-romance" to (CoverGenre.Romance to 7),
    "historical-romance" to (CoverGenre.Romance to 7),
    "regency-romance" to (CoverGenre.Romance to 7),
    "adventure" to (CoverGenre.Adventure to 6),
    "western" to (CoverGenre.Adventure to 6),
    "sports" to (CoverGenre.Adventure to 5),
    "picture-book" to (CoverGenre.Children to 9),
    "childrens" to (CoverGenre.Children to 5),
    "middle-grade" to (CoverGenre.Children to 3),
    "parenting" to (CoverGenre.Children to 5),
    "poetry" to (CoverGenre.Poetry to 7),
    "nature" to (CoverGenre.Nature to 6),
    "health" to (CoverGenre.Nature to 5),
    "cooking" to (CoverGenre.Nature to 5),
    "travel" to (CoverGenre.Travel to 6),
    "spirituality" to (CoverGenre.Spiritual to 6),
    "religion" to (CoverGenre.Spiritual to 6),
    "philosophy" to (CoverGenre.Spiritual to 5),
    "business" to (CoverGenre.Business to 6),
    "economics" to (CoverGenre.Business to 5),
    "self-help" to (CoverGenre.Business to 5),
    "psychology" to (CoverGenre.Business to 5),
    "society" to (CoverGenre.Business to 4),
    "politics" to (CoverGenre.Business to 4),
    "humor" to (CoverGenre.Humor to 5),
    "graphic-novel" to (CoverGenre.Humor to 3),
    "classics" to (CoverGenre.Vintage to 6),
    "biography" to (CoverGenre.Biography to 8),
    "history" to (CoverGenre.History to 6),
    "war-history" to (CoverGenre.History to 6),
    "historical-fiction" to (CoverGenre.History to 6),
    "war-fiction" to (CoverGenre.History to 6),
    "law" to (CoverGenre.History to 4),
    "technology" to (CoverGenre.Technical to 6),
    "science" to (CoverGenre.Technical to 6),
    "mathematics" to (CoverGenre.Technical to 6),
    "education" to (CoverGenre.Technical to 5),
    "reference" to (CoverGenre.Technical to 5),
    "language" to (CoverGenre.Technical to 5),
    "music" to (CoverGenre.Technical to 4),
    "art" to (CoverGenre.Technical to 4),
    "literary-fiction" to (CoverGenre.Literary to 6),
    "literary-criticism" to (CoverGenre.Literary to 5),
    "drama" to (CoverGenre.Literary to 4),
    "short-stories" to (CoverGenre.Literary to 3),
    "fiction" to (CoverGenre.Literary to 1),
)

private val TOPICS = setOf(
    "nature", "health", "cooking", "travel", "business", "economics", "self-help",
    "psychology", "society", "politics", "history", "war-history", "law",
    "technology", "science", "mathematics", "education", "reference", "language",
    "music", "art", "spirituality", "religion", "philosophy", "parenting", "sports",
    "biography",
)

internal fun classify(slugs: List<String>): CoverGenre {
    val scores = IntArray(CoverGenre.entries.size)
    val fiction = "fiction" in slugs
    for (slug in slugs) {
        val match = SLUG_GENRES[slug] ?: continue
        val weight = if (fiction && slug in TOPICS) match.second / 2 else match.second
        scores[match.first.ordinal] += weight
    }
    val best = scores.indices.maxByOrNull { scores[it] } ?: return CoverGenre.Literary
    return if (scores[best] == 0) CoverGenre.Literary else CoverGenre.entries[best]
}

private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL.toLong()
private val MIX_HIGH = 0xBF58476D1CE4E5B9uL.toLong()
private val MIX_LOW = 0x94D049BB133111EBuL.toLong()

internal class CoverRandom(seed: Long) {
    private var state = seed

    private fun next(): Long {
        state += GOLDEN_GAMMA
        var value = state
        value = (value xor (value ushr 30)) * MIX_HIGH
        value = (value xor (value ushr 27)) * MIX_LOW
        return value xor (value ushr 31)
    }

    fun float() = (next() ushr 11).toFloat() / (1L shl 53).toFloat()

    fun range(from: Float, until: Float) = from + float() * (until - from)

    fun index(count: Int) = (float() * count).toInt().coerceIn(0, count - 1)

    fun between(from: Int, to: Int) = from + index(to - from + 1)

    fun <T> pick(values: List<T>) = values[index(values.size)]

    fun chance(probability: Float) = float() < probability

    fun sign() = if (chance(0.5f)) 1f else -1f
}

internal fun coverSeed(work: Int, title: String, author: String): Long {
    val digest =
        MessageDigest.getInstance("SHA-256").digest("$work|$title|$author".toByteArray())
    var value = 0L
    for (index in 0 until 8) value = (value shl 8) or (digest[index].toLong() and 0xFF)
    return value
}

internal const val TAU = (Math.PI * 2).toFloat()

internal class CoverCanvas(val bitmap: Bitmap, val random: CoverRandom) {
    val canvas = Canvas(bitmap)
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()

    fun unit(fraction: Float) = width * fraction
}

internal fun hsv(hue: Float, saturation: Float, value: Float): Int {
    val wrapped = ((hue % 1f) + 1f) % 1f
    return Color.HSVToColor(floatArrayOf(wrapped * 360f, saturation, value))
}

internal fun mix(first: Int, second: Int, amount: Float): Int {
    val weight = amount.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(first) + (Color.red(second) - Color.red(first)) * weight).toInt(),
        (
            Color.green(first) + (Color.green(second) - Color.green(first)) * weight
            ).toInt(),
        (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * weight).toInt(),
    )
}

internal fun shade(color: Int, factor: Float) = Color.rgb(
    (Color.red(color) * factor).toInt().coerceIn(0, 255),
    (Color.green(color) * factor).toInt().coerceIn(0, 255),
    (Color.blue(color) * factor).toInt().coerceIn(0, 255),
)

internal fun luminance(color: Int) = (
    0.2126f * Color.red(color) + 0.7152f * Color.green(color) +
        0.0722f * Color.blue(color)
    ) /
    255f

internal fun alpha(color: Int, amount: Float) = Color.argb(
    (amount.coerceIn(0f, 1f) * 255).toInt(),
    Color.red(color),
    Color.green(color),
    Color.blue(color),
)

internal fun fillPaint(color: Int) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

internal fun strokePaint(color: Int, thickness: Float) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = thickness.coerceAtLeast(1f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

private fun rampStops(count: Int) = FloatArray(count) { it / (count - 1f) }

internal fun CoverCanvas.verticalGradient(top: Int, bottom: Int, gamma: Float = 1f) {
    val stops = rampStops(12)
    val colors = IntArray(stops.size) { mix(top, bottom, stops[it].pow(gamma)) }
    val paint = fillPaint(Color.BLACK)
    paint.shader =
        LinearGradient(0f, 0f, 0f, height, colors, stops, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
}

internal fun CoverCanvas.scrimBand(
    top: Float,
    bottom: Float,
    toward: Int,
    strength: Float,
) {
    if (strength <= 0.01f) return
    val stops = floatArrayOf(0f, 0.5f, 1f)
    val colors = intArrayOf(
        alpha(toward, 0f),
        alpha(toward, strength.coerceIn(0f, 1f)),
        alpha(toward, 0f),
    )
    val paint = fillPaint(Color.BLACK)
    paint.shader =
        LinearGradient(0f, top, 0f, bottom, colors, stops, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, top, width, bottom, paint)
}

internal fun displacedLine(
    random: CoverRandom,
    start: Float,
    end: Float,
    roughness: Float,
    depth: Int = 7,
): FloatArray {
    var points = floatArrayOf(start, end)
    var scale = roughness
    repeat(depth) {
        val stepped = FloatArray(points.size * 2 - 1)
        for (index in 0 until points.size - 1) {
            val middle = (points[index] + points[index + 1]) / 2f
            stepped[index * 2] = points[index]
            stepped[index * 2 + 1] = middle + random.range(-scale, scale)
        }
        stepped[stepped.size - 1] = points[points.size - 1]
        points = stepped
        scale *= 0.52f
    }
    return points
}

internal fun CoverCanvas.ridge(baseline: Float, rise: Float, roughness: Float): Path {
    val profile = displacedLine(
        random, baseline + random.range(-0.03f, 0.03f), baseline, roughness,
    )
    val step = width / (profile.size - 1)
    val shape = Path()
    shape.moveTo(0f, height)
    for (index in profile.indices) {
        val x = index * step
        val y = (profile[index] - rise) * height
        shape.lineTo(x, y)
    }
    shape.lineTo(width, height)
    shape.close()
    return shape
}

internal class Harmonic(val amplitude: Float, val frequency: Int, val phase: Float)

internal fun randomHarmonics(
    random: CoverRandom,
    count: Int,
    strength: Float,
): List<Harmonic> {
    val frequencies = mutableListOf(2, 3, 4, 5, 7)
    return List(count) {
        val frequency = frequencies.removeAt(random.index(frequencies.size))
        Harmonic(
            random.range(strength * 0.4f, strength), frequency, random.range(0f, TAU),
        )
    }
}

internal fun wobblyRingPath(
    centerX: Float,
    centerY: Float,
    radius: Float,
    harmonics: List<Harmonic>,
    points: Int = 160,
    squash: Float = 1f,
): Path {
    val path = Path()
    for (index in 0..points) {
        val angle = TAU * index / points
        var offset = 0f
        for (harmonic in harmonics) {
            offset +=
                harmonic.amplitude * sin(harmonic.frequency * angle + harmonic.phase)
        }
        val distance = radius * (1f + offset)
        val x = centerX + cos(angle) * distance
        val y = centerY + sin(angle) * distance * squash
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

internal fun starPath(
    centerX: Float,
    centerY: Float,
    outer: Float,
    inner: Float,
    points: Int,
    rotation: Float = 0f,
): Path {
    val path = Path()
    for (index in 0 until points * 2) {
        val radius = if (index % 2 == 0) outer else inner
        val angle = TAU * index / (points * 2) + rotation
        val x = centerX + cos(angle) * radius
        val y = centerY + sin(angle) * radius
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

internal enum class CoverFont(val family: String, val weight: Int) {
    Serif("serif", 600),
    Sans("sans-serif", 700),
    Condensed("sans-serif-condensed", 700),
}

internal enum class Anchor {
    Top,
    Center,
    Bottom,
}

internal data class Typeset(
    val ink: Int,
    val authorInk: Int = ink,
    val font: CoverFont = CoverFont.Serif,
    val italic: Boolean = false,
    val upper: Boolean = false,
    val anchor: Anchor = Anchor.Top,
)

private const val MARGIN = 0.10f
private const val LEADING = 1.1f

private val typefaces = ConcurrentHashMap<String, Typeface>()

private fun typeface(family: String, weight: Int, italic: Boolean): Typeface =
    typefaces.getOrPut("$family-$weight-$italic") {
        val base = Typeface.create(family, Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, italic)
        } else {
            val bold = weight >= 600
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(base, style)
        }
    }

private fun textPaint(typeface: Typeface, tracking: Float) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
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
    paint: Paint,
    limit: Float,
    available: Float,
): TitleLayout {
    var size = available / 2f
    val step = (size / 40f).coerceAtLeast(0.5f)
    while (size > available / 8f) {
        paint.textSize = size
        val lines = wrap(text, paint, limit)
        if (lines != null && lines.size <= 4 &&
            lines.size * size * LEADING <= available
        ) {
            return TitleLayout(lines, paint, size)
        }
        size -= step
    }
    paint.textSize = size
    return TitleLayout(wrap(text, paint, limit) ?: listOf(text), paint, size)
}

private fun CoverCanvas.backdrop(top: Float, bottom: Float): Float {
    val first = top.toInt().coerceIn(0, bitmap.height - 1)
    val last = bottom.toInt().coerceIn(first + 1, bitmap.height)
    val pixels = IntArray(bitmap.width * (last - first))
    bitmap.getPixels(pixels, 0, bitmap.width, 0, first, bitmap.width, last - first)
    val samples = pixels.indices step 7
    return samples.sumOf { luminance(pixels[it]).toDouble() }.toFloat() / samples.count()
}

private fun CoverCanvas.legibleInk(top: Float, bottom: Float, ink: Int): Int {
    val backdrop = backdrop(top, bottom)
    if (abs(luminance(ink) - backdrop) >= 0.4f) return ink
    return if (backdrop > 0.5f) Color.rgb(16, 16, 18) else Color.WHITE
}

private fun CoverCanvas.drawCentered(
    text: String,
    baseline: Float,
    paint: Paint,
    ink: Int,
) {
    paint.color = ink
    val offset = paint.letterSpacing * paint.textSize / 2f
    canvas.drawText(text, width / 2f + offset, baseline, paint)
}

internal fun CoverCanvas.drawTypography(title: String, author: String, typeset: Typeset) {
    val font = typeset.font
    val titlePaint = textPaint(
        typeface(font.family, font.weight, typeset.italic),
        if (typeset.upper) 0.06f else 0f,
    )
    val titleText = if (typeset.upper) title.uppercase() else title
    val layout = fitTitle(titleText, titlePaint, width * (1 - MARGIN * 2), height * 0.26f)
    val block = layout.lines.size * layout.size * LEADING
    val top = when (typeset.anchor) {
        Anchor.Top -> height * 0.08f
        Anchor.Center -> (height - block) / 2f
        Anchor.Bottom -> height * 0.86f - block
    }
    val bandTop = top - layout.size * 0.4f
    val bandBottom = top + block + layout.size * 0.3f
    val titleInk = legibleInk(bandTop, bandBottom, typeset.ink)

    val authorPaint = textPaint(typeface("sans-serif", 500, false), 0.14f)
    authorPaint.textSize = height * 0.025f
    val authorY = height * if (typeset.anchor == Anchor.Bottom) 0.075f else 0.925f
    val authorInk =
        legibleInk(authorY - height * 0.02f, authorY + height * 0.005f, typeset.authorInk)

    layout.lines.forEachIndexed { index, line ->
        val baseline = top + layout.size * (index * LEADING + 0.82f)
        drawCentered(line, baseline, titlePaint, titleInk)
    }
    drawCentered(author.uppercase(), authorY, authorPaint, authorInk)
}
