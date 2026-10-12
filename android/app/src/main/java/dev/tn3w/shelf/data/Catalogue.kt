package dev.tn3w.shelf.data

import kotlinx.serialization.Serializable
import java.util.BitSet
import java.util.PriorityQueue
import kotlin.math.ln

const val COVERS = "https://covers.openlibrary.org"
const val DNB_COVERS = "https://portal.dnb.de"
const val SYNTHETIC = 1_000_000_000
const val DNB = 1

data class Author(val number: Int, val name: String) {
    fun photoUrl(host: String, size: String) =
        "$host/a/olid/OL${number}A-$size.jpg?default=false".takeIf { number < SYNTHETIC }
}

@Serializable
data class LibraryCover(val source: Int, val isbn: String) {
    fun url(dnbHost: String) = when (source) {
        DNB -> "$dnbHost/opac/mvb/cover?isbn=$isbn"
        else -> null
    }
}

data class Book(
    val work: Int,
    val title: String,
    val subtitle: String,
    val alternate: String,
    val authors: List<Author>,
    val year: Int,
    val cover: Int,
    val tags: List<Int>,
    val popularity: Popularity?,
    val libraryCover: LibraryCover? = null,
) {
    val author
        get() = authors.firstOrNull()?.name.orEmpty()

    fun coverUrl(host: String, size: String, dnbHost: String = DNB_COVERS) =
        if (cover == 0) libraryCover?.url(dnbHost) else "$host/b/id/$cover-$size.jpg"
}

private val COMPANION = Regex(
    "box(ed)? set|collection set|books? collection|\\d ?books? set|gift set|" +
        "books? bundle|\\buntitled\\b|" +
        "\\bseries ?(box|set|collection)?\\s*$|" +
        "\\(series\\)|\\b\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\b|omnibus|slipcase|" +
        "complete (series|collection|novels|saga|works)|collected (works|novels)|" +
        "trilogy|tetralogy|trilogie|gesamtausgabe|gesamtwerk|sammelband|" +
        "coffret|int[ée]grale|estuche|obras completas|colecci[óo]n completa|" +
        "colou?ring book|activity book|sticker|annual \\d{4}|calendar|planner|" +
        "study guide|sparknotes|cliffs ?notes|summary of|analysis of|quiz|trivia|" +
        "unofficial|companion|movie storybook|the making of|selections from|" +
        "big book|adventure game|lesson plan|workbook|journal\\s*$|notes\\s*$|" +
        "\\blevel \\d|\\d ?(paperback|hardcover)",
    RegexOption.IGNORE_CASE,
)

val Book.isCompanion
    get() = COMPANION.containsMatchIn("$title $subtitle")

private const val ISBN_BASE = 978_000_000_000L
private val ISBN_PREFIXES = setOf("978", "979")

private fun checkDigit(twelve: String): Int {
    val sum = twelve.withIndex().sumOf { (index, digit) ->
        digit.digitToInt() * if (index % 2 == 0) 1 else 3
    }
    return (10 - sum % 10) % 10
}

fun normalizedIsbn(text: String): String? {
    val cleaned = text.filter { it.isLetterOrDigit() }.uppercase()
    val twelve = when (cleaned.length) {
        10 -> "978" + cleaned.take(9)
        13 -> cleaned.take(12)
        else -> return null
    }
    if (!twelve.all { it in '0'..'9' } || twelve.take(3) !in ISBN_PREFIXES) return null
    val isbn = twelve + checkDigit(twelve)
    return isbn.takeIf { cleaned.length == 10 || it == cleaned }
}

fun isbnKey(isbn: String) =
    normalizedIsbn(isbn)?.let { (it.take(12).toLong() - ISBN_BASE).toInt() }

fun isbnOf(key: Int) = (ISBN_BASE + key).toString().let { it + checkDigit(it) }

data class Tag(val id: Int, val slug: String, val label: String, val category: String)

data class Series(val name: String, val members: List<Int>)

data class Location(val segment: Segment, val local: Int)

private class Scores(val works: IntArray, val values: FloatArray) {
    fun of(work: Int): Double {
        val at = works.binarySearch(work)
        return if (at < 0) 0.0 else values[at].toDouble()
    }
}

class Catalogue(val language: String, val segments: List<Segment>, val ranks: Ranks?) {
    private val visible = visibility(segments)
    private val order = segments.withIndex().associate { (index, it) -> it to index }
    val workCount = visible.sumOf { it.cardinality() }
    val tags = segments.firstOrNull()?.tags.orEmpty()
    val tagBySlug = tags.associateBy { it.slug }
    private val maxScore by lazy { scores.values.maxOrNull()?.toDouble() ?: 1.0 }
    private val tagCounts by lazy {
        tags.associate { tag -> tag.id to segments.sumOf { it.tagCount(tag.id) } }
    }

    private val scores by lazy {
        val works = allWorks()
        Scores(works, FloatArray(works.size) { rawScore(works[it]).toFloat() })
    }

    fun warmUp() = maxScore

    fun locate(work: Int): Location? = find(work) ?: ranks?.alias(work)?.let(::find)

    private fun find(work: Int): Location? {
        for (segment in segments.asReversed()) {
            val local = segment.localOf(work)
            if (local >= 0) return Location(segment, local)
            if (segment.tombstones.binarySearch(work) >= 0) return null
        }
        return null
    }

    fun alias(work: Int) = ranks?.alias(work)

    private fun rawScore(work: Int) = ranks?.popularity(work)?.score ?: 0.0

    fun score(work: Int) = scores.of(work)

    fun popularity(work: Int) = ln(1 + score(work)) / ln(1 + maxScore)

    fun tagCount(tag: Int) = tagCounts[tag] ?: 0

    fun book(work: Int) = locate(work)?.let(::book)

    fun books(works: Iterable<Int>) = works.mapNotNull(::book)

    fun book(location: Location): Book {
        val (segment, local) = location
        val heads = segment.heads(local)
        val facts = segment.facts(local)
        val authors =
            facts.authors.map { segment.author(it) }.map { Author(it.number, it.name) }
        val work = segment.work(local)
        return Book(
            work,
            heads.title,
            heads.subtitle,
            heads.alternate,
            authors,
            facts.year,
            facts.cover,
            facts.tags.toList(),
            ranks?.popularity(work),
            segment.libraryCover(local),
        )
    }

    fun bookOfIsbn(isbn: String): Book? {
        val key = isbnKey(isbn) ?: return null
        return segments.asReversed().firstNotNullOfOrNull { segment ->
            segment.localOfIsbn(key).takeIf { it >= 0 }?.let { book(segment.work(it)) }
        }
    }

    fun facts(work: Int) = locate(work)?.let { it.segment.facts(it.local) }

    fun firstAuthorNumber(work: Int) = locate(work)?.let { (segment, local) ->
        segment.facts(local).authors.firstOrNull()?.let { segment.author(it).number }
    }

    fun description(work: Int) =
        locate(work)?.let { it.segment.description(it.local) } ?: Description("", false)

    fun series(work: Int): Series? {
        val (segment, local) = locate(work) ?: return null
        val slot = segment.facts(local).series
        if (slot < 0) return null
        return segment.series(slot)
    }

    fun visibleWorks(segment: Segment, locals: IntArray): IntArray {
        val bits = visible[order.getValue(segment)]
        val result = IntArrayList()
        locals.forEach { if (bits.get(it)) result.add(segment.work(it)) }
        return result.toArray()
    }

    fun tagWorks(tag: Int): IntArray {
        val result = IntArrayList()
        segments.forEach { segment ->
            visibleWorks(segment, segment.tagWorks(tag)).forEach(result::add)
        }
        return result.toArray()
    }

    fun popularWorks(limit: Int, tag: Int? = null) =
        topWorks(if (tag == null) scores.works else tagWorks(tag), limit)

    fun topWorks(works: IntArray, limit: Int): List<Int> {
        if (limit <= 0) return emptyList()
        val heap = PriorityQueue<Int>(limit) { left, right ->
            score(left).compareTo(score(right))
        }
        for (work in works) {
            heap.add(work)
            if (heap.size > limit) heap.poll()
        }
        return heap.sortedByDescending { score(it) }
    }

    private fun allWorks(): IntArray {
        val result = IntArrayList()
        segments.forEachIndexed { index, segment ->
            val bits = visible[index]
            var local = bits.nextSetBit(0)
            while (local >= 0) {
                result.add(segment.work(local))
                local = bits.nextSetBit(local + 1)
            }
        }
        return result.toArray().also { it.sort() }
    }

    private fun authorRecords(author: Author): Sequence<Pair<Segment, AuthorRecord>> {
        val token = tokenize(author.name).maxByOrNull { it.length }
            ?: return emptySequence()
        return segments.asSequence().flatMap { segment ->
            val slots = segment.term(token)?.authorSlots ?: IntArray(0)
            slots
                .map(segment::author)
                .filter { it.number == author.number }
                .map { segment to it }
        }
    }

    fun authorWorks(author: Author) = authorRecords(author)
        .flatMap { (segment, record) -> visibleWorks(segment, record.works).toList() }
        .distinct()
        .toList()

    fun authorBorn(author: Author) =
        authorRecords(author).firstOrNull { it.second.born > 0 }?.second?.born ?: 0

    companion object {
        private const val SEGMENT_BITS = 8
        private const val LOCAL_BITS = 23

        fun visibility(segments: List<Segment>): List<BitSet> {
            require(segments.size < 1 shl SEGMENT_BITS)
            val bits = segments.map { BitSet(it.workCount) }
            val entries = LongArray(segments.sumOf { it.workCount + it.tombstones.size })
            var count = 0
            segments.forEachIndexed { index, segment ->
                repeat(segment.workCount) {
                    entries[count++] = entry(segment.work(it), index, it + 1)
                }
                segment.tombstones.forEach { entries[count++] = entry(it, index, 0) }
            }
            entries.sort()
            for (position in entries.indices) {
                val current = entries[position]
                val next = entries.getOrNull(position + 1)
                if (next != null && next ushr 32 == current ushr 32) continue
                val local = (current and ((1L shl LOCAL_BITS) - 1)).toInt() - 1
                if (local < 0) continue
                bits[((current ushr LOCAL_BITS) and 0xFF).toInt()].set(local)
            }
            return bits
        }

        private fun entry(work: Int, segment: Int, local: Int): Long =
            (work.toLong() shl 32) or (segment.toLong() shl LOCAL_BITS) or local.toLong()
    }
}

class Loaded(
    val catalogue: Catalogue,
    loadOthers: () -> List<Catalogue> = { emptyList() },
) {
    val searcher = Searcher(catalogue)
    val recommender = Recommender(catalogue)
    val others by lazy(loadOthers)

    fun catalogueOf(work: Int) =
        (listOf(catalogue) + others).firstOrNull { it.locate(work) != null }

    fun book(work: Int) = catalogueOf(work)?.book(work)
}
