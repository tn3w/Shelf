package dev.tn3w.shelf.data

import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val CANDIDATES = 400
private const val PROBE_WIDTH = 120
private const val PROBED_LINES = 8
private const val ORIENTATION_LINES = 3
private const val MIN_ORIENTATION_QUALITY = 3.0
private const val COMMON_WORD = 60
private const val POPULARITY_BONUS = 0.08
private val STOP_PHRASES = listOf(
    "a novel", "novel", "new york times", "bestseller", "bestselling", "international",
    "sunday times", "now a major motion picture", "roman", "kriminalroman", "novela",
    "spiegel", "edited by", "translated by", "introduction by", "foreword by",
    "penguin classics", "the classic", "edition", "winner of", "copies sold",
    "exclusive", "signed copy", "prix goncourt", "sale",
).map(::tokenize)

class ScanSettings(
    val lines: Int = 6,
    val blur: Double = 20.0,
    val threshold: Double = 1.1,
    val margin: Double = 0.6,
    val frames: Int = 1,
    val decay: Double = 0.5,
    val tapThreshold: Double = 0.8,
    val tapMargin: Double = 0.3,
)

private val LETTER_GAP = Regex("(?<=\\p{L})[.·•‧∙-](?=\\p{L})")

private class Probe(val reading: Reading, val complete: Boolean)

private fun <T, R> parallel(items: List<T>, transform: (T) -> R): List<R> =
    items.parallelStream().map(transform).collect(Collectors.toList())

private class Oriented(
    val view: View,
    val lines: List<LineBox>,
    val turned: Boolean,
    val flipped: Boolean,
    val probes: Map<LineBox, Probe> = emptyMap(),
    val quality: Double = 0.0,
)

private fun sameRow(first: LineBox, second: LineBox): Boolean {
    val vertical = min(first.bottom, second.bottom) - max(first.top, second.top)
    val horizontal = min(first.right, second.right) - max(first.left, second.left)
    return horizontal > 0 && vertical > 0.4f * min(first.height, second.height)
}

private fun strength(reading: Reading) = reading.confidence * min(reading.text.length, 12)

class Reader(
    private val recognizer: Recognizer,
    private val settings: ScanSettings,
    private val known: (String) -> Boolean = { true },
) {
    @Volatile
    private var remembered: Pair<Boolean, Boolean>? = null

    private fun view(frame: Gray, rotation: Double): View {
        val upright = abs(sin(rotation)) < 0.5
        val width = if (upright) frame.width else frame.height
        val height = if (upright) frame.height else frame.width
        return View(frame, rotation, width, height)
    }

    private fun probe(option: Oriented, line: LineBox): Probe {
        val crop = option.view.crop(line, option.flipped, PROBE_WIDTH)
        return Probe(recognizer.read(crop, line.height), crop.width < PROBE_WIDTH)
    }

    private fun quality(reading: Reading): Float {
        val letters = tokenize(reading.text).sumOf {
            if (it.length >= 3 && known(it)) it.length.toDouble() else it.length / 5.0
        }
        return reading.confidence * min(letters.toFloat(), 12f)
    }

    private fun probed(option: Oriented, count: Int): Oriented {
        val lines = option.lines.take(count)
        val probes = parallel(lines) { option.probes[it] ?: probe(option, it) }
        return Oriented(
            option.view, option.lines, option.turned, option.flipped,
            lines.zip(probes).toMap(), probes.sumOf { quality(it.reading).toDouble() },
        )
    }

    private fun layout(frame: Gray, angle: Double, turned: Boolean): List<Oriented> {
        val view = view(frame, if (turned) angle + PI / 2 else angle)
        val lines = findLines(view.render()).take(PROBED_LINES)
        return listOf(false, true).map { flipped ->
            Oriented(view, lines, turned, flipped)
        }
    }

    private fun orient(frame: Gray): Oriented {
        val angle = skew(frame)
        val last = remembered
        val turns = last?.let { listOf(it.first) } ?: listOf(false, true)
        val best = turns.flatMap { layout(frame, angle, it) }
            .filter { last == null || it.flipped == last.second }
            .map { probed(it, ORIENTATION_LINES) }
            .maxBy { it.quality }
        remembered = (best.turned to best.flipped)
            .takeIf { best.quality >= MIN_ORIENTATION_QUALITY }
        return best
    }

    fun read(frame: Gray): List<Reading> {
        if (sharpness(frame) < settings.blur) return emptyList()
        val oriented = probed(orient(frame), PROBED_LINES)
        val view = oriented.view
        val chosen = oriented.probes.entries
            .filter { it.value.reading.text.trim().length >= 2 }
            .sortedByDescending { strength(it.value.reading) }
            .take(settings.lines)
        val read = parallel(chosen) { (line, probe) ->
            line to (
                probe.reading.takeIf { probe.complete }
                    ?: recognizer.read(view.crop(line, oriented.flipped), line.height)
                )
        }
        val dotted = mutableListOf<LineBox>()
        return read.map { (line, reading) ->
            val overlaps = dotted.any { sameRow(line, it) }
            dotted += line
            val dots = view.dots(line, reading.positions, oriented.flipped)
            val shown = if (overlaps) emptyList() else dots
            with(reading) { Reading(text, confidence, height, positions, shown) }
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
    private val hits = HashMap<String, Map<Int, Double>>()
    private val profiles = HashMap<Int, BookProfile?>()
    private val weights = HashMap<String, Double>()
    private val frequencies = HashMap<String, Int>()
    private val total = (catalogue.ranks?.works ?: catalogue.workCount).toDouble()

    private fun words(readings: List<Reading>): List<Word> {
        val tallest = readings.map { it.height }.sortedDescending().take(2)
        return readings.flatMapIndexed { line, reading ->
            val text = reading.text.replace(LETTER_GAP, "")
            withoutStopPhrases(tokenize(text)).flatMap(::separated).map {
                Word(it, reading.confidence, reading.height in tallest, line)
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

    private fun close(word: String) =
        hits.getOrPut(word) { searcher.matches(word, word.length >= 4) }.isNotEmpty()

    private fun popularity(work: Int) =
        catalogue.popularity(work).takeIf { it.isFinite() } ?: 0.0

    private fun frequency(token: String) =
        frequencies.getOrPut(token) { searcher.frequency(token) }

    private fun weight(token: String) = weights.getOrPut(token) {
        ln((total + 1) / (frequency(token) + 1)) + 1
    }

    private fun profile(work: Int) = profiles.getOrPut(work) {
        catalogue.book(work)?.let { book ->
            val titles = listOf(book.title, book.alternate)
                .filter { it.isNotBlank() }
                .map { title ->
                    val tokens = tokenize(mainTitle(title))
                    tokens.filter { it !in ARTICLES }.ifEmpty { tokens }
                }
            val authors = book.authors.take(2).map { tokenize(it.name) }
            val extra = tokenize(
                "${book.title} ${book.alternate} ${book.subtitle} " +
                    catalogue.series(work)?.name.orEmpty(),
            )
            val tokens = (titles.flatten() + authors.flatten() + extra).toSet()
            BookProfile(titles, authors, tokens, popularity(work))
        }
    }

    private fun candidates(words: List<Word>): List<Int> {
        val counts = HashMap<Int, Int>()
        val rare = HashSet<Int>()
        for (word in words.map { it.text }.distinct()) {
            if (word.length < 3 || word in ARTICLES) continue
            val matched = hits.getOrPut(word) { searcher.matches(word, word.length >= 4) }
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
        return title * (0.6 + 0.4 * author) * (0.25 + 0.75 * explained) *
            (0.4 + 0.6 * precision) + POPULARITY_BONUS * profile.popularity * title
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

    fun scores(readings: List<Reading>): Map<Int, Double> {
        val words = words(readings)
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
                val relative = supports.getValue(work) / best
                profile?.let { score(it, words) * relative } ?: 0.0
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

class Step(val work: Int?, val dots: List<Dot>)

class Scanner(
    private val catalogue: Catalogue,
    searcher: Searcher,
    recognizer: Recognizer,
    settings: ScanSettings = ScanSettings(),
) {
    private val vocabulary = ConcurrentHashMap<String, Boolean>()
    private val reader = Reader(recognizer, settings) { word ->
        vocabulary.getOrPut(word) { searcher.frequency(word) > 0 }
    }
    private val matcher = Matcher(catalogue, searcher)
    private val rule = StopRule(settings)

    fun next(frame: Gray, tapped: Boolean = false): Step {
        val readings = reader.read(frame)
        val work = rule.update(matcher.scores(readings), tapped)
        return Step(work, work?.let { matched(readings, it) }.orEmpty())
    }

    private fun matched(readings: List<Reading>, work: Int): List<Dot> {
        val words = identity(catalogue, work).filter { it.length >= 3 }
        return readings.filter { reading ->
            tokenize(reading.text.replace(LETTER_GAP, "")).any { token ->
                words.any { similarity(it, token) >= 0.75 }
            }
        }.flatMap { it.dots }
    }
}
