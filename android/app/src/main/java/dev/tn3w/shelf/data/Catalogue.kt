package dev.tn3w.shelf.data

import java.util.BitSet
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

    fun coverUrl(size: String = "M") =
        if (cover == 0) null else "https://covers.openlibrary.org/b/id/$cover-$size.jpg"
}

data class Tag(val id: Int, val slug: String, val label: String, val category: String)

data class Series(val name: String, val members: List<Int>)

data class Location(val segment: Segment, val local: Int)

class Catalogue(val language: String, val segments: List<Segment>, val ranks: Ranks?) {
    private val visible = visibility(segments)
    val month = (segments.map { it.month } + listOfNotNull(ranks?.month)).maxOrNull()
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

    fun isVisible(segment: Segment, local: Int) = visible[indexOf(segment)].get(local)

    private fun indexOf(segment: Segment) = segments.indexOf(segment)

    fun locate(work: Int): Location? {
        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            if (segment.tombstones.binarySearch(work) >= 0) return null
            val local = segment.localOf(work)
            if (local >= 0) return Location(segment, local)
        }
        return null
    }

    fun score(work: Int) = ranks?.popularity(work)?.score ?: 0.0

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
        locate(work)?.let { it.segment.description(it.local) }.orEmpty()

    fun series(work: Int): Series? {
        val (segment, local) = locate(work) ?: return null
        val slot = segment.facts(local).series
        if (slot < 0) return null
        val record = segment.series(slot)
        return Series(record.name, record.members.toList())
    }

    fun visibleWorks(segment: Segment, locals: IntArray): List<Int> {
        val bits = visible[indexOf(segment)]
        return locals.filter { bits.get(it) }.map(segment::work)
    }

    fun tagWorks(tag: Int) = segments.flatMap { visibleWorks(it, it.tagWorks(tag)) }

    fun popularWorks(limit: Int, tag: Int? = null): List<Int> {
        val candidates = if (tag == null) coreWorks() else tagWorks(tag)
        return candidates.sortedByDescending(::score).take(limit)
    }

    private fun coreWorks() =
        segments
            .filter { it.pack == "core" }
            .flatMap { visibleWorks(it, IntArray(it.workCount) { local -> local }) }

    fun authorWorks(author: Author): List<Int> {
        val token = tokenize(author.name).maxByOrNull { it.length } ?: return emptyList()
        return segments
            .flatMap { segment ->
                val slots = segment.term(token)?.authorSlots ?: IntArray(0)
                slots
                    .map(segment::author)
                    .filter { it.number == author.number }
                    .flatMap { visibleWorks(segment, it.works) }
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
