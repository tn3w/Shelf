package dev.tn3w.shelf.data

import androidx.collection.LruCache
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private const val PROBED_LINES = 2
private const val WEAK_FRAMES = 3
private const val MIN_QUALITY = 3.0
private const val CERTAIN_INFORMATION = 12.0
private const val AUTHOR_INFORMATION = 6.0
private const val SUBSUMED = 0.5
private const val CANDIDATES = 400
private const val COMMON_WORD = 60
private const val CACHE_SIZE = 4096
private const val POPULARITY_BONUS = 0.08
private val STOP_PHRASES = listOf(
    "a novel", "novel", "new york times", "bestseller", "bestselling", "international",
    "sunday times", "now a major motion picture", "roman", "kriminalroman", "novela",
    "spiegel", "edited by", "translated by", "introduction by", "foreword by",
    "penguin classics", "the classic", "edition", "winner of", "copies sold",
    "exclusive", "signed copy", "prix goncourt", "sale",
).map(::tokenize)
private val LETTER_GAP = Regex("(?<=\\p{L})[.·•‧∙-](?=\\p{L})")

class ScanSettings(
    val lines: Int = 8,
    val blur: Double = 20.0,
    val threshold: Double = 1.0,
    val margin: Double = 0.3,
    val frames: Int = 1,
    val decay: Double = 0.5,
    val tapThreshold: Double = 0.7,
    val tapMargin: Double = 0.2,
)

class TextLine(
    val text: String,
    val confidence: Float,
    val height: Float,
    val dots: List<Dot> = emptyList(),
)

private class Orientation(val turned: Boolean, val flipped: Boolean)

private class Layout(val view: View, val lines: List<LineBox>, val turned: Boolean)

private class Choice(
    val layout: Layout,
    val flipped: Boolean,
    val read: Map<LineBox, TextLine> = emptyMap(),
)

private fun <T, R> parallel(items: List<T>, transform: (T) -> R): List<R> =
    items.parallelStream().map(transform).collect(Collectors.toList())

private fun sameRow(first: LineBox, second: LineBox): Boolean {
    val vertical = min(first.bottom, second.bottom) - max(first.top, second.top)
    val horizontal = min(first.right, second.right) - max(first.left, second.left)
    return horizontal > 0 && vertical > 0.4f * min(first.height, second.height)
}

class Reader(
    private val recognizer: Recognizer,
    private val settings: ScanSettings,
    private val known: (String) -> Boolean = { true },
) {
    @Volatile
    private var orientation: Orientation? = null
    private var weak = 0

    private fun layout(frame: Gray, small: Gray, angle: Double, turned: Boolean): Layout {
        val view =
            View(frame, if (turned) angle + PI / 2 else angle, detectionScale(frame))
        return Layout(view, findLines(view.render(small)).take(settings.lines), turned)
    }

    private fun line(view: View, box: LineBox, flipped: Boolean): TextLine {
        val reading = recognizer.read(view.crop(box, flipped))
        val dots = view.dots(box, reading.positions, flipped)
        return TextLine(reading.text, reading.confidence, box.height, dots)
    }

    private fun quality(lines: Collection<TextLine>) = lines.sumOf { line ->
        val letters = tokenize(line.text).sumOf {
            if (it.length >= 3 && known(it)) it.length.toDouble() else it.length / 5.0
        }
        line.confidence * min(letters, 12.0)
    }

    private fun orient(frame: Gray, small: Gray, angle: Double): Choice {
        val choices = listOf(false, true)
            .map { layout(frame, small, angle, it) }
            .flatMap { layout -> listOf(false, true).map { Choice(layout, it) } }
        val probes = choices.flatMap { choice ->
            choice.layout.lines.take(PROBED_LINES).map { choice to it }
        }
        val read = parallel(probes) { (choice, box) ->
            box to line(choice.layout.view, box, choice.flipped)
        }
        val byChoice = probes.zip(read).groupBy({ it.first.first }, { it.second })
        val best = choices.maxBy { choice ->
            quality(byChoice[choice].orEmpty().map { it.second })
        }
        return Choice(best.layout, best.flipped, byChoice[best].orEmpty().toMap())
    }

    fun read(frame: Gray): List<TextLine> {
        val small = frame.shrunk(detectionScale(frame))
        if (sharpness(small) < settings.blur) return emptyList()
        val angle = skew(small)
        val current = orientation
        val choice = if (current == null) {
            orient(frame, small, angle)
        } else {
            Choice(layout(frame, small, angle, current.turned), current.flipped)
        }
        val boxes = choice.layout.lines
        val lines = parallel(boxes) {
            choice.read[it] ?: line(choice.layout.view, it, choice.flipped)
        }
        remember(Orientation(choice.layout.turned, choice.flipped), quality(lines))
        return withoutRepeatedDots(
            boxes.zip(lines).filter {
                it.second.text.trim().length >=
                    2
            },
        )
    }

    private fun remember(chosen: Orientation, quality: Double) {
        weak = if (quality >= MIN_QUALITY) 0 else weak + 1
        orientation = when {
            weak == 0 -> chosen
            weak >= WEAK_FRAMES -> null.also { weak = 0 }
            else -> orientation
        }
    }

    private fun withoutRepeatedDots(
        lines: List<Pair<LineBox, TextLine>>,
    ): List<TextLine> {
        val dotted = mutableListOf<LineBox>()
        return lines.map { (box, line) ->
            val repeated = dotted.any { sameRow(box, it) }
            dotted += box
            if (repeated) TextLine(line.text, line.confidence, line.height) else line
        }
    }
}

private class Word(
    val text: String,
    val confidence: Float,
    val prominent: Boolean,
    val line: Int,
)

private class BookProfile(
    val titles: List<List<String>>,
    val authors: List<List<String>>,
    val tokens: Set<String>,
    val popularity: Double,
)

private fun withoutStopPhrases(tokens: List<String>): List<String> {
    val result = tokens.toMutableList()
    for (phrase in STOP_PHRASES) {
        var index = indexOf(result, phrase)
        while (index >= 0) {
            repeat(phrase.size) { result.removeAt(index) }
            index = indexOf(result, phrase)
        }
    }
    return result
}

private fun indexOf(tokens: List<String>, phrase: List<String>) =
    (0..tokens.size - phrase.size).firstOrNull { start ->
        phrase.indices.all { tokens[start + it] == phrase[it] }
    } ?: -1

private fun similarity(token: String, word: String): Double {
    if (token == word) return 1.0
    val partial = word.length >= 3 && (token.startsWith(word) || token.endsWith(word))
    return max(if (partial) 0.8 * word.length / token.length else 0.0, typos(token, word))
}

private fun fits(token: String, word: String) =
    similarity(token, word) >= 0.5 || (word.length >= 3 && word in token)

private fun typos(token: String, word: String): Double {
    val limit = if (token.length == 3) 1 else allowedTypos(token)
    if (limit == 0) return 0.0
    val distance = editDistance(token, word, limit)
    return when {
        distance > limit -> 0.0
        distance == 1 -> 0.75
        else -> 0.5
    }
}

fun identity(catalogue: Catalogue, work: Int) = catalogue.book(work)?.let { book ->
    val title = tokenize(mainTitle(book.title))
    val words = title.filterNot { token -> token.all(Char::isDigit) }.ifEmpty { title }
    words + tokenize(book.authors.firstOrNull()?.name.orEmpty())
} ?: listOf(work.toString())

class Matcher(private val catalogue: Catalogue, private val searcher: Searcher) {
    private val hits = LruCache<String, Map<Int, Double>>(CACHE_SIZE)
    private val profiles = LruCache<Int, BookProfile>(CACHE_SIZE)
    private val weights = LruCache<String, Double>(CACHE_SIZE)
    private val frequencies = LruCache<String, Int>(CACHE_SIZE)
    private val total = (catalogue.ranks?.works ?: catalogue.workCount).toDouble()

    private fun words(lines: List<TextLine>): List<Word> {
        val tallest = lines.map { it.height }.sortedDescending().take(2)
        return lines.flatMapIndexed { index, line ->
            val text = line.text.replace(LETTER_GAP, "")
            withoutStopPhrases(tokenize(text)).flatMap(::separated).map {
                Word(it, line.confidence, line.height in tallest, index)
            }
        }
    }

    private fun separated(token: String): List<String> {
        if (token.length < 6 || frequency(token) > 0) return listOf(token)
        val splits = (2..token.length - 2)
            .map { token.substring(0, it) to token.substring(it) }
        val split = splits
            .filter { (left, right) -> frequency(left) > 0 && frequency(right) > 0 }
            .maxByOrNull { (left, right) -> min(frequency(left), frequency(right)) }
            ?: splits.filter { (left, right) -> left.length >= 4 && right.length >= 4 }
                .filter { (left, right) -> frequency(left) > 0 && close(right) }
                .maxByOrNull { (left, _) -> left.length }
            ?: return listOf(token)
        return listOf(token, split.first, split.second)
    }

    private fun matches(word: String) =
        hits.getOrPut(word) { searcher.matches(word, word.length >= 4) }

    private fun close(word: String) = matches(word).isNotEmpty()

    private fun popularity(work: Int) =
        catalogue.popularity(work).takeIf { it.isFinite() } ?: 0.0

    private fun frequency(token: String) =
        frequencies.getOrPut(token) { searcher.frequency(token) }

    private fun weight(token: String) = weights.getOrPut(token) {
        ln((total + 1) / (frequency(token) + 1)) + 1
    }

    private fun profile(work: Int) = catalogue.book(work)?.let { book ->
        profiles.getOrPut(work) { buildProfile(work, book) }
    }

    private fun buildProfile(work: Int, book: Book): BookProfile {
        val authors = book.authors.take(2).map { tokenize(it.name) }
        val named = authors.flatten().toSet()
        val titles = listOf(book.title, book.alternate)
            .filter { it.isNotBlank() }
            .map { title ->
                val tokens = tokenize(mainTitle(title))
                tokens.filter { it !in ARTICLES && it !in named }.ifEmpty { tokens }
            }
        val extra = tokenize(
            "${book.title} ${book.alternate} ${book.subtitle} " +
                catalogue.series(work)?.name.orEmpty(),
        )
        val tokens = (titles.flatten() + authors.flatten() + extra).toSet()
        return BookProfile(titles, authors, tokens, popularity(work))
    }

    private fun candidates(words: List<Word>): List<Int> {
        val counts = HashMap<Int, Int>()
        val rare = HashSet<Int>()
        for (word in words.map { it.text }.distinct()) {
            if (word.length < 3 || word in ARTICLES) continue
            val matched = matches(word)
            matched.keys.forEach { counts.merge(it, 1, Int::plus) }
            val exact = matched.filterValues { it >= 1.0 }
            when {
                matched.size <= COMMON_WORD -> rare += matched.keys
                exact.size <= COMMON_WORD -> rare += exact.keys
            }
        }
        return counts.keys
            .filter { counts.getValue(it) >= 2 || it in rare }
            .sortedByDescending { counts.getValue(it) + popularity(it) }
            .take(CANDIDATES)
    }

    private fun best(token: String, words: List<Word>) = words.maxOfOrNull {
        similarity(token, it.text) * (0.6 + 0.4 * it.confidence)
    } ?: 0.0

    private fun coverage(
        tokens: List<String>,
        words: List<Word>,
        surname: Boolean,
    ): Double {
        if (tokens.isEmpty()) return 0.0
        var found = 0.0
        var possible = 0.0
        tokens.forEachIndexed { index, token ->
            val weight = when {
                surname && index == tokens.lastIndex -> 2.0
                surname -> 1.0
                token.length <= 2 -> weight(token) / 2
                else -> weight(token)
            }
            found += weight * best(token, words)
            possible += weight
        }
        return found / possible
    }

    private fun score(profile: BookProfile, words: List<Word>): Double {
        val title = profile.titles.maxOfOrNull { coverage(it, words, false) } ?: 0.0
        if (title == 0.0) return 0.0
        val author = profile.authors.maxOfOrNull { coverage(it, words, true) } ?: 0.0
        val prominent = words.filter {
            it.prominent && it.text.length >= 3 && it.text !in ARTICLES &&
                frequency(it.text) > 0
        }
        val explained = if (prominent.isEmpty()) {
            1.0
        } else {
            prominent.count { word ->
                profile.tokens.any { fits(it, word.text) }
            }.toDouble() / prominent.size
        }
        val precision = precision(profile, words)
        val information = profile.titles.maxOf { tokens ->
            tokens.sumOf { weight(it) * best(it, words) }
        }
        val certainty =
            min(1.0, (information + AUTHOR_INFORMATION * author) / CERTAIN_INFORMATION)
        return certainty * (
            title * (0.6 + 0.4 * author) * (0.25 + 0.75 * explained) *
                (0.4 + 0.6 * precision) + POPULARITY_BONUS * profile.popularity * title
            )
    }

    private fun precision(profile: BookProfile, words: List<Word>): Double {
        val titleTokens = profile.titles.flatten()
        val counted = words.filter { it.text.length >= 3 && it.text !in ARTICLES }
        val lines = counted.groupBy { it.line }.values.filter { line ->
            line.any { word -> titleTokens.any { fits(it, word.text) } }
        }
        val total = lines.sumOf { it.size }
        if (total == 0) return 1.0
        val explained = lines.sumOf { line ->
            line.count { word -> profile.tokens.any { fits(it, word.text) } }
        }
        return explained.toDouble() / total
    }

    private fun support(profile: BookProfile, evidence: List<String>): Double {
        val total = evidence.sumOf(::weight)
        if (total == 0.0) return 1.0
        val explained = evidence.filter { word -> profile.tokens.any { fits(it, word) } }
        return explained.sumOf(::weight) / total
    }

    private fun subsumed(profile: BookProfile, others: Collection<BookProfile?>) =
        others.any { other ->
            other != null && other.popularity > profile.popularity &&
                profile.titles.any { title ->
                    other.titles.any { it.size > title.size && it.containsAll(title) }
                }
        }

    fun scores(lines: List<TextLine>): Map<Int, Double> {
        val words = words(lines)
        if (words.isEmpty()) return emptyMap()
        val evidence = words.map { it.text }.distinct()
            .filter { it.length >= 3 && it !in ARTICLES && frequency(it) > 0 }
        val profiles = candidates(words).associateWith(::profile)
        val supports = profiles.mapValues { (_, profile) ->
            profile?.let { support(it, evidence) } ?: 0.0
        }
        val best = supports.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return profiles
            .mapValues { (work, profile) ->
                if (profile == null) return@mapValues 0.0
                val relative = supports.getValue(work) / best
                val shadow = if (subsumed(profile, profiles.values)) SUBSUMED else 1.0
                score(profile, words) * relative * shadow
            }
            .filterValues { it > 0.05 }
            .entries
            .groupBy { identity(catalogue, it.key) }
            .values
            .associate { same -> same.maxBy { popularity(it.key) }.toPair() }
    }
}

class StopRule(private val settings: ScanSettings) {
    private val totals = HashMap<Int, Double>()
    private var leader = -1
    private var streak = 0

    fun update(scores: Map<Int, Double>, tapped: Boolean = false): Int? {
        totals.replaceAll { _, value -> value * settings.decay }
        scores.forEach { (work, value) -> totals.merge(work, value, Double::plus) }
        totals.values.removeIf { it < 0.02 }
        val ranked = totals.entries.sortedByDescending { it.value }.take(2)
        val top = ranked.firstOrNull() ?: return null.also { streak = 0 }
        val second = ranked.getOrNull(1)?.value ?: 0.0
        val threshold = if (tapped) settings.tapThreshold else settings.threshold
        val margin = if (tapped) settings.tapMargin else settings.margin
        val clear = top.value >= threshold && top.value - second >= margin
        streak = when {
            !clear -> 0
            top.key == leader -> streak + 1
            else -> 1
        }
        leader = top.key
        return leader.takeIf { streak >= if (tapped) 1 else settings.frames }
    }
}

private val BARCODE_FORMATS = listOf(BarcodeFormat.EAN_13, BarcodeFormat.QR_CODE)
private val BARCODE_HINTS = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to BARCODE_FORMATS,
    DecodeHintType.TRY_HARDER to true,
)
private val ISBN_IN_TEXT = Regex("97[89](-?\\d){10}")

private class GraySource(private val frame: Gray, private val quarterTurns: Int = 0) :
    LuminanceSource(
        if (quarterTurns % 2 == 1) frame.height else frame.width,
        if (quarterTurns % 2 == 1) frame.width else frame.height,
    ) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val target = row?.takeIf { it.size >= width } ?: ByteArray(width)
        for (x in 0 until width) target[x] = luminance(x, y)
        return target
    }

    override fun getMatrix() = ByteArray(width * height) {
        luminance(
            it % width,
            it / width,
        )
    }

    override fun isRotateSupported() = true

    override fun rotateCounterClockwise() = GraySource(frame, (quarterTurns + 1) % 4)

    private fun luminance(x: Int, y: Int): Byte {
        val index = when (quarterTurns) {
            0 -> y * frame.width + x
            1 -> x * frame.width + frame.width - 1 - y
            2 -> (frame.height - 1 - y) * frame.width + frame.width - 1 - x
            else -> (frame.height - 1 - x) * frame.width + y
        }
        return frame.pixels[index].toInt().toByte()
    }
}

class BarcodeReader {
    private val reader = MultiFormatReader().apply { setHints(BARCODE_HINTS) }

    fun isbn(frame: Gray): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(GraySource(frame)))
        val text = runCatching { reader.decodeWithState(bitmap).text }.getOrNull()
        return text?.let(ISBN_IN_TEXT::find)?.value?.let(::normalizedIsbn)
    }
}

class Look(val work: Int?, val lines: List<TextLine>, val scores: Map<Int, Double>)

class Step(val work: Int?, val dots: List<Dot>)

class Scanner(
    private val catalogue: Catalogue,
    searcher: Searcher,
    recognizer: Recognizer,
    settings: ScanSettings = ScanSettings(),
    private val others: () -> List<Catalogue> = { emptyList() },
) {
    private val vocabulary = ConcurrentHashMap<String, Boolean>()
    private val reader = Reader(recognizer, settings) { word ->
        vocabulary.getOrPut(word) { searcher.frequency(word) > 0 }
    }
    private val matcher = Matcher(catalogue, searcher)
    private val rule = StopRule(settings)
    private val barcodes = BarcodeReader()

    fun look(frame: Gray): Look {
        val scanned = barcodes.isbn(frame)?.let(::bookOfIsbn)
        if (scanned != null) return Look(scanned.work, emptyList(), emptyMap())
        val lines = reader.read(frame)
        return Look(null, lines, matcher.scores(lines))
    }

    fun next(frame: Gray, tapped: Boolean = false): Step {
        val look = look(frame)
        if (look.work != null) return Step(look.work, emptyList())
        val work = rule.update(look.scores, tapped) ?: return Step(null, emptyList())
        return Step(work, matched(look.lines, work))
    }

    private fun bookOfIsbn(isbn: String) = catalogue.bookOfIsbn(isbn)
        ?: others().firstNotNullOfOrNull { it.bookOfIsbn(isbn) }

    private fun matched(lines: List<TextLine>, work: Int): List<Dot> {
        val words = identity(catalogue, work).filter { it.length >= 3 }
        return lines.filter { line ->
            tokenize(line.text.replace(LETTER_GAP, "")).any { token ->
                words.any { similarity(it, token) >= 0.75 }
            }
        }.flatMap { it.dots }
    }
}
