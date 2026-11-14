package dev.tn3w.shelf.data

import androidx.collection.LruCache
import androidx.collection.MutableIntList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.BitSet
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.roundToInt

const val COVERS = "https://covers.openlibrary.org"
const val DNB_COVERS = "https://portal.dnb.de"
const val DIGAR_COVERS = "https://www.digar.ee"
const val BOOKBITES_COVERS = "https://bookbites-prod-se.covers.bookbites.com"
const val SYNTHETIC = 1_000_000_000
const val DNB = 1
const val DIGAR = 2
const val ISBN_COVER = 3
const val BOOKBITES = 4
private const val QUALITY_STEPS = 255.0
private const val GALLOP = 8

data class Author(val number: Int, val name: String) {
    fun photoUrl(size: String) =
        "$COVERS/a/olid/OL${number}A-$size.jpg?default=false".takeIf {
            number < SYNTHETIC
        }
}

@Serializable
data class LibraryCover(val source: Int, @SerialName("isbn") val key: String) {
    val url
        get() = when (source) {
            DNB -> "$DNB_COVERS/opac/mvb/cover?isbn=$key"
            DIGAR -> "$DIGAR_COVERS/arhiiv/nlib-digar:$key?thumb=1"
            ISBN_COVER -> "$COVERS/b/isbn/$key-L.jpg?default=false"
            BOOKBITES -> "$BOOKBITES_COVERS/$key"
            else -> null
        }
}

data class Book(
    val work: Int,
    val title: String,
    val subtitle: String = "",
    val alternate: String = "",
    val authors: List<Author> = emptyList(),
    val year: Int = 0,
    val cover: Int = 0,
    val tags: List<Int> = emptyList(),
    val popularity: Popularity? = null,
    val libraryCover: LibraryCover? = null,
    /** Catalogue the book was read from, empty for saved and own books. */
    val language: String = "",
) {
    val author
        get() = authors.firstOrNull()?.name.orEmpty()

    fun coverUrl(size: String) =
        if (cover == 0) libraryCover?.url else "$COVERS/b/id/$cover-$size.jpg"
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

private val OMNIBUS = Regex(
    listOf("The|A|An", "Der|Die|Das", "Le|La|Les", "El|La|Los|Las").joinToString("|") {
        "^($it) [^,&/;+]+( and| /|;) ($it) \\p{Lu}"
    },
)

fun isCompanion(text: String) = COMPANION.containsMatchIn(text)

private val companions = LruCache<String, Boolean>(4096)

val Book.isCompanion
    get() = companions.getOrPut("$title\n$subtitle") {
        isCompanion("$title $subtitle") || OMNIBUS.containsMatchIn(title)
    }

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

fun isbnsFor(book: Book, saved: String, catalogue: List<String>) =
    (listOf(saved, book.libraryCover?.key.orEmpty()) + catalogue)
        .mapNotNull(::normalizedIsbn)
        .distinct()

fun isbnKey(isbn: String) =
    normalizedIsbn(isbn)?.let { (it.take(12).toLong() - ISBN_BASE).toInt() }

fun isbnOf(key: Int) = (ISBN_BASE + key).toString().let { it + checkDigit(it) }

data class Tag(val id: Int, val slug: String, val label: String, val category: String)

data class Series(val name: String, val members: List<Int>)

data class Location(val segment: Segment, val local: Int)

private class Ranked(size: Int) {
    val qualities = ByteArray(size)
    private val tenths = IntArray(size)
    private val readers = IntArray(size) { -1 }
    private val ratings = IntArray(size)
    private val twentieths = ByteArray(size)
    private val editions = ByteArray(size)

    operator fun set(index: Int, popularity: Popularity) {
        qualities[index] = (QUALITY_STEPS * qualityOf(popularity)).toInt().toByte()
        tenths[index] = (popularity.score * 10).roundToInt()
        readers[index] = popularity.readers
        ratings[index] = popularity.ratings
        twentieths[index] = (popularity.rating * 20).roundToInt().toByte()
        editions[index] = popularity.editions.toByte()
    }

    fun score(index: Int) = (tenths[index] / 10.0).toFloat()

    operator fun get(index: Int) = if (readers[index] < 0) {
        null
    } else {
        Popularity(
            tenths[index] / 10.0,
            readers[index],
            ratings[index],
            (twentieths[index].toInt() and 0xFF) / 20.0,
            editions[index].toInt() and 0xFF,
        )
    }
}

class Catalogue(val language: String, val segments: List<Segment>, val ranks: Ranks?) {
    private val visible = visibility(segments)
    private val order = segments.withIndex().associate { (index, it) -> it to index }
    val workCount = visible.sumOf { it.cardinality() }
    val tags = segments.firstOrNull()?.tags.orEmpty()
    val tagBySlug = tags.associateBy { it.slug }

    /** Newest release among the segments, as the cover proxy names it. */
    val release = segments.map { it.month }.maxWithOrNull(releaseOrder)
    private val maxScore by lazy {
        works.indices.maxOfOrNull(::scoreAt)?.takeIf { it > 0 }?.toDouble() ?: 1.0
    }
    private val tagCounts by lazy {
        tags.associate { tag -> tag.id to segments.sumOf { it.tagCount(tag.id) } }
    }

    private val works by lazy { allWorks() }
    private val ranking = lazy {
        Ranked(works.size).also { ranked ->
            works.forEachIndexed { index, work ->
                ranks?.popularity(work)?.let { ranked[index] = it }
            }
        }
    }
    private val ranked by ranking

    private fun scoreAt(index: Int) = ranked.score(index)

    fun warmUp() = maxScore

    fun trim() {
        segments.forEach(Segment::trim)
        ranks?.trim()
    }

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

    fun score(work: Int) =
        works.binarySearch(work).let { if (it < 0) 0.0 else scoreAt(it).toDouble() }

    fun popularity(work: Int) = ln(1 + score(work)) / ln(1 + maxScore)

    /** Number of visible works; positions below index the score arrays. */
    val indexed get() = works.size

    fun workAt(index: Int) = works[index]

    fun popularityAt(index: Int) = ln(1 + scoreAt(index)) / ln(1 + maxScore)

    /**
     * Positions of [items] among all works, -1 for absent ones. Posting lists arrive as
     * ascending runs, one per segment, so a moving pointer replaces most binary searches.
     */
    fun indexesOf(items: IntArray): IntArray {
        val all = works
        val result = IntArray(items.size)
        var pointer = 0
        for ((position, work) in items.withIndex()) {
            if (pointer >= all.size || all[pointer] > work) pointer = 0
            var steps = 0
            while (pointer < all.size && all[pointer] < work && steps < GALLOP) {
                pointer++
                steps++
            }
            if (pointer < all.size && all[pointer] < work) {
                val found = all.binarySearch(work, pointer, all.size)
                pointer = if (found >= 0) found else -found - 1
            }
            result[position] =
                if (pointer < all.size && all[pointer] == work) pointer else -1
        }
        return result
    }

    /** Reader count and smoothed rating in [0, 1], see [qualityOf]. */
    fun quality(work: Int) = works.binarySearch(work).let {
        if (it < 0) 0.0 else (ranked.qualities[it].toInt() and 0xFF) / QUALITY_STEPS
    }

    fun popularityOf(work: Int): Popularity? {
        val index = if (ranking.isInitialized()) works.binarySearch(work) else -1
        return if (index < 0) ranks?.popularity(work) else ranked[index]
    }

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
            popularityOf(work),
            segment.libraryCover(local),
            language,
        )
    }

    fun bookOfIsbn(isbn: String): Book? {
        val key = isbnKey(isbn) ?: return null
        return segments.asReversed().firstNotNullOfOrNull { segment ->
            segment.localOfIsbn(key).takeIf { it >= 0 }?.let { book(segment.work(it)) }
        }
    }

    fun isbnsOf(work: Int): List<String> {
        val target = if (find(work) != null) work else alias(work) ?: return emptyList()
        return segments
            .flatMap { segment ->
                val local = segment.localOf(target)
                if (local < 0) emptyList() else segment.isbnsOf(local)
            }
            .distinct()
            .map(::isbnOf)
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
        val result = MutableIntList()
        locals.forEach { if (bits.get(it)) result.add(segment.work(it)) }
        return result.toIntArray()
    }

    fun tagWorks(tag: Int): IntArray {
        val result = MutableIntList()
        segments.forEach { segment ->
            visibleWorks(segment, segment.tagWorks(tag)).forEach(result::add)
        }
        return result.toIntArray()
    }

    fun popularWorks(limit: Int, tag: Int? = null) =
        if (tag == null) topOfAll(limit) else topWorks(tagWorks(tag), limit)

    /** [topWorks] over every work, reading scores by index instead of by lookup. */
    private fun topOfAll(limit: Int): List<Int> {
        if (limit <= 0) return emptyList()
        val heap = PriorityQueue<Int>(limit + 1) { left, right ->
            scoreAt(left).compareTo(scoreAt(right))
        }
        for (index in works.indices) {
            if (heap.size < limit) {
                heap.add(index)
            } else if (scoreAt(index) > scoreAt(heap.peek()!!)) {
                heap.poll()
                heap.add(index)
            }
        }
        return heap.sortedByDescending { scoreAt(it) }.map { works[it] }
    }

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
        val result = MutableIntList()
        segments.forEachIndexed { index, segment ->
            visible[index].stream().forEach { result.add(segment.work(it)) }
        }
        return result.toIntArray().also { it.sort() }
    }

    private fun authorRecords(author: Author): Sequence<Pair<Segment, AuthorRecord>> {
        val token = tokenize(author.name)
            .distinct()
            .map { token ->
                token to segments.sumOf { it.term(token)?.authorSlots?.size ?: 0 }
            }
            .filter { it.second > 0 }
            .minByOrNull { it.second }
            ?.first
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

    /** A book from another language's catalogue, its tag ids mapped onto this one's. */
    fun adopt(book: Book, from: Catalogue): Book {
        if (from === this || from.tags == tags) return book
        val ids = from.tags.associate { tag -> tag.id to tagBySlug[tag.slug]?.id }
        return book.copy(tags = book.tags.mapNotNull { ids[it] })
    }

    fun authorBorn(author: Author) =
        authorRecords(author).firstOrNull { it.second.born > 0 }?.second?.born ?: 0

    companion object {
        private const val SEGMENT_BITS = 8
        private const val LOCAL_BITS = 23

        fun visibility(segments: List<Segment>): List<BitSet> {
            require(segments.size < 1 shl SEGMENT_BITS)
            require(segments.all { it.workCount < 1 shl LOCAL_BITS })
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
    private val loadingOthers = lazy(loadOthers)
    val others by loadingOthers
    val recommender = Recommender(catalogue, ::foreignBook)

    fun catalogueOf(work: Int) =
        (listOf(catalogue) + others).firstOrNull { it.locate(work) != null }

    fun book(work: Int) = catalogueOf(work)?.book(work)

    /** A work only another installed language has, ready to seed this catalogue. */
    private fun foreignBook(work: Int) = others.firstNotNullOfOrNull { other ->
        other.book(work)?.let { catalogue.adopt(it, other) }
    }

    fun trim() {
        catalogue.trim()
        if (loadingOthers.isInitialized()) others.forEach(Catalogue::trim)
    }

    /** The book as a recommendation seed for the current catalogue. */
    fun seedBook(work: Int) = catalogue.book(work) ?: foreignBook(work)
}
