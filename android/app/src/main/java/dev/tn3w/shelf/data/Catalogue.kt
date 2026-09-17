package dev.tn3w.shelf.data

import java.util.BitSet
import java.util.PriorityQueue
import kotlin.math.ln

data class Author(val number: Int, val name: String)

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
) {
    val author
        get() = authors.firstOrNull()?.name.orEmpty()

    fun coverUrl(size: String) =
        if (cover == 0) null else "https://covers.openlibrary.org/b/id/$cover-$size.jpg"
}

private val COMPANION =
    Regex(
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
    val tags =
        segments.firstOrNull()?.tags.orEmpty().map {
            Tag(it.id, it.slug, it.label, it.category)
        }
    val tagBySlug = tags.associateBy { it.slug }
    private val maxScore by lazy {
        segments
            .filter { it.pack == "core" }
            .flatMap { segment -> (0 until segment.workCount).map(segment::work) }
            .maxOfOrNull { score(it) } ?: 1.0
    }
    private val tagCounts by lazy {
        tags.associate { tag -> tag.id to segments.sumOf { it.tagCount(tag.id) } }
    }

    private val scores by lazy {
        val works = allWorks()
        Scores(works, FloatArray(works.size) { rawScore(works[it]).toFloat() })
    }

    fun locate(work: Int): Location? {
        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            if (segment.tombstones.binarySearch(work) >= 0) return null
            val local = segment.localOf(work)
            if (local >= 0) return Location(segment, local)
        }
        return null
    }

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
        )
    }

    fun facts(work: Int) = locate(work)?.let { it.segment.facts(it.local) }

    fun description(work: Int) =
        locate(work)?.let { it.segment.description(it.local) } ?: Description("", false)

    fun series(work: Int): Series? {
        val (segment, local) = locate(work) ?: return null
        val slot = segment.facts(local).series
        if (slot < 0) return null
        val record = segment.series(slot)
        return Series(record.name, record.members.toList())
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

    fun authorWorks(author: Author): List<Int> {
        val token = tokenize(author.name).maxByOrNull { it.length } ?: return emptyList()
        return segments
            .flatMap { segment ->
                val slots = segment.term(token)?.authorSlots ?: IntArray(0)
                slots
                    .map(segment::author)
                    .filter { it.number == author.number }
                    .flatMap { visibleWorks(segment, it.works).toList() }
            }
            .distinct()
    }

    fun authorBorn(author: Author): Int {
        val book = authorWorks(author).firstOrNull()?.let(::locate) ?: return 0
        val slots = book.segment.facts(book.local).authors
        return slots
            .map(book.segment::author)
            .firstOrNull { it.number == author.number }
            ?.born ?: 0
    }

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
                bits[(current ushr LOCAL_BITS and 0xFF).toInt()].set(local)
            }
            return bits
        }

        private fun entry(work: Int, segment: Int, local: Int): Long =
            (work.toLong() shl 32) or (segment.toLong() shl LOCAL_BITS) or local.toLong()
    }
}
