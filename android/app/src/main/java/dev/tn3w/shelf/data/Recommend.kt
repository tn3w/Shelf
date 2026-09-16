package dev.tn3w.shelf.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

private val SHELF_WEIGHT =
    mapOf(Shelf.Read to 0.8, Shelf.Reading to 1.0, Shelf.Want to 0.5)
private val FORM_TAGS = setOf("fiction", "nonfiction")
private val AUDIENCE_TAGS =
    setOf("picture-book", "childrens", "middle-grade", "young-adult")
private val ARTICLES = setOf("the", "a", "an", "der", "die", "das", "le", "la", "el")
private const val SOURCES = 6
private const val MIN_SHARED = 200
private const val PER_TAG = 1500
private const val AUTHOR_DEPTH = 40
private const val MAX_PER_AUTHOR = 2
private const val ADULT = 4

private typealias Vector = Map<Int, Double>

private data class TitleKey(val key: String, val author: String)

private class Source(val book: Book, val vector: Vector, val weight: Double)

private class Profile(
    val tags: Vector,
    val audience: Double,
    val fictionShare: Double,
    val seen: Set<TitleKey>,
    val library: Set<Int>,
    val hidden: Set<Int>,
    val sources: List<Source>,
)

private class Scored(val score: Double, val work: Int, val vector: Vector)

class Suggestion(val book: Book, val because: Book?)

private fun titleKey(book: Book): TitleKey {
    val tokens = tokenize(mainTitle(book.title))
    val trimmed = if (tokens.firstOrNull() in ARTICLES) tokens.drop(1) else tokens
    return TitleKey(trimmed.joinToString(" "), book.author)
}

private fun Vector.norm() = sqrt(values.sumOf { it * it }).takeIf { it > 0 } ?: 1.0

private fun overlap(left: Vector, right: Vector): Double {
    val shared =
        left.keys.intersect(right.keys).sumOf { left.getValue(it) * right.getValue(it) }
    return shared / (left.norm() * right.norm())
}

class Recommender(private val catalogue: Catalogue) {
    private val total = catalogue.workCount
    private val slug = catalogue.tags.associate { it.id to it.slug }
    private val idf by lazy {
        catalogue.tags.associate {
            it.id to ln((total + 1.0) / (catalogue.tagCount(it.id) + 1))
        }
    }

    private fun tagVector(tags: List<Int>): Vector =
        tags
            .filter { slug[it] !in AUDIENCE_TAGS }
            .withIndex()
            .associate { (index, tag) ->
                val confidence = 1.0 / (1.0 + 0.15 * index)
                val weight = if (slug[tag] in FORM_TAGS) 0.25 else 1.0
                tag to (idf[tag] ?: 0.0) * confidence * weight
            }

    private fun audienceOf(slugs: Set<String>) =
        when {
            "picture-book" in slugs -> 0
            "young-adult" in slugs -> 3
            "middle-grade" in slugs -> 2
            "childrens" in slugs -> 1
            else -> ADULT
        }

    private fun buildProfile(entries: List<Saved>, hidden: Set<Int>): Profile {
        val tags = mutableMapOf<Int, Double>()
        var audience = 0.0
        var fiction = 0.0
        var weights = 0.0
        val candidates = mutableListOf<Source>()
        for (entry in entries) {
            val book = catalogue.book(entry.work) ?: continue
            val weight = SHELF_WEIGHT.getValue(entry.shelf)
            val vector = tagVector(book.tags)
            if (vector.isEmpty()) continue
            vector.forEach { (tag, value) ->
                tags.merge(tag, weight * value, Double::plus)
            }
            val slugs = book.tags.mapNotNull(slug::get).toSet()
            weights += weight
            audience += weight * audienceOf(slugs)
            if ("fiction" in slugs) fiction += weight
            candidates += Source(book, vector, weight)
        }
        val norm = tags.norm()
        return Profile(
            tags = tags.mapValues { it.value / norm },
            audience = if (weights > 0) audience / weights else ADULT.toDouble(),
            fictionShare = if (weights > 0) fiction / weights else 0.5,
            seen = candidates.map { titleKey(it.book) }.toSet(),
            library = entries.map { it.work }.toSet(),
            hidden = hidden,
            sources = pickSources(candidates),
        )
    }

    private fun pickSources(candidates: List<Source>): List<Source> {
        val pool = candidates.sortedByDescending { it.weight }.toMutableList()
        val chosen = mutableListOf<Source>()
        while (pool.isNotEmpty() && chosen.size < SOURCES) {
            val best = pool.maxBy { source ->
                val redundancy =
                    chosen.maxOfOrNull { overlap(source.vector, it.vector) } ?: 0.0
                source.weight - 0.8 * redundancy
            }
            pool.remove(best)
            chosen += best
        }
        return chosen
    }

    private fun retrieve(source: Source): Set<Int> {
        val tags =
            source.vector.keys
                .filter { slug[it] !in FORM_TAGS }
                .sortedByDescending { source.vector.getValue(it) }
        val works = mutableSetOf<Int>()
        if (tags.isNotEmpty()) works += catalogue.topWorks(narrow(tags), PER_TAG)
        source.book.authors.forEach {
            works += catalogue.authorWorks(it).take(AUTHOR_DEPTH)
        }
        return works
    }

    private fun narrow(tags: List<Int>): IntArray {
        val pool = catalogue.tagWorks(tags[0])
        val second = tags.getOrNull(1) ?: return pool
        val members = catalogue.tagWorks(second).toHashSet()
        val shared = pool.filter { it in members }
        return if (shared.size < MIN_SHARED) pool else shared.toIntArray()
    }

    private fun audienceFit(profile: Profile, slugs: Set<String>) =
        1.0 / (1.0 + 0.5 * abs(audienceOf(slugs) - profile.audience))

    private fun formFit(profile: Profile, slugs: Set<String>): Double {
        val fiction = if ("fiction" in slugs) 1.0 else 0.0
        return 1.0 - 0.45 * abs(fiction - profile.fictionShare)
    }

    private fun quality(work: Int): Double {
        val popularity = catalogue.ranks?.popularity(work)
        val ratings = popularity?.ratings ?: 0
        val mean = (8 * 3.8 + (popularity?.rating ?: 0.0) * ratings) / (8 + ratings)
        return 0.5 * catalogue.popularity(work) + 0.5 * (mean - 3.0) / 2.0
    }

    private fun score(profile: Profile, source: Source, work: Int): Scored? {
        val facts = catalogue.facts(work) ?: return null
        val tags = facts.tags.toList()
        val vector = tagVector(tags)
        if (vector.keys.none { slug[it] !in FORM_TAGS }) return null
        val neighbour = overlap(vector, source.vector)
        if (neighbour <= 0.2) return null
        val general =
            vector.entries.sumOf { (profile.tags[it.key] ?: 0.0) * it.value } /
                vector.norm()
        val slugs = tags.mapNotNull(slug::get).toSet()
        val fit = audienceFit(profile, slugs) * formFit(profile, slugs)
        val value = 0.55 * neighbour + 0.25 * general + 0.20 * quality(work)
        return Scored(value * fit, work, vector)
    }

    private inner class Picker(
        private val library: Set<Int> = emptySet(),
        private val hidden: Set<Int> = emptySet(),
        seen: Set<TitleKey> = emptySet(),
    ) {
        private val titles = seen.toMutableSet()
        private val series = mutableSetOf<String>()
        private val perAuthor = mutableMapOf<String, Int>()

        fun accept(work: Int): Book? {
            val book = catalogue.book(nextVolume(work, library)) ?: return null
            if (book.isCompanion || book.work in library || book.work in hidden)
                return null
            val name = catalogue.series(book.work)?.name
            if (name != null && name in series) return null
            val count = perAuthor[book.author] ?: 0
            if (count >= MAX_PER_AUTHOR || !titles.add(titleKey(book))) return null
            if (name != null) series += name
            perAuthor[book.author] = count + 1
            return book
        }
    }

    private fun interleave(
        profile: Profile,
        ranked: List<List<Scored>>,
        limit: Int,
    ): List<Suggestion> {
        val picker = Picker(profile.library, profile.hidden, profile.seen)
        val cursors = IntArray(ranked.size)
        val chosen = mutableListOf<Suggestion>()
        while (chosen.size < limit) {
            var added = false
            for (index in ranked.indices) {
                if (chosen.size >= limit) break
                val book = advance(ranked[index], cursors, index, picker) ?: continue
                chosen += Suggestion(book, profile.sources[index].book)
                added = true
            }
            if (!added) break
        }
        return chosen
    }

    private fun advance(
        list: List<Scored>,
        cursors: IntArray,
        index: Int,
        picker: Picker,
    ): Book? {
        while (cursors[index] < list.size) {
            picker.accept(list[cursors[index]++].work)?.let {
                return it
            }
        }
        return null
    }

    private fun nextVolume(work: Int, library: Set<Int>): Int {
        val members = catalogue.series(work)?.members ?: return work
        val unread = members.filter { it !in library && catalogue.locate(it) != null }
        return unread.firstOrNull() ?: work
    }

    fun suggest(
        entries: List<Saved>,
        limit: Int = 20,
        hidden: Set<Int> = emptySet(),
    ): List<Suggestion> {
        val profile = buildProfile(entries, hidden)
        if (profile.sources.isEmpty()) return popular(limit).map { Suggestion(it, null) }
        val ranked = profile.sources.map { source ->
            retrieve(source)
                .mapNotNull { score(profile, source, it) }
                .sortedByDescending { it.score }
                .take(limit * 4)
        }
        return interleave(profile, ranked, limit)
    }

    fun similar(book: Book, limit: Int = 12) =
        suggest(listOf(book.toSaved(Shelf.Read)), limit).map { it.book }

    fun series(book: Book): Pair<String, List<Book>>? {
        val series = catalogue.series(book.work) ?: return null
        return series.name to catalogue.books(series.members)
    }

    fun byAuthor(book: Book, limit: Int = 12): List<Book> {
        val author = book.authors.firstOrNull() ?: return emptyList()
        val sameSeries = catalogue.series(book.work)?.members.orEmpty().toSet()
        val works =
            catalogue
                .authorWorks(author)
                .filter { it != book.work && it !in sameSeries }
                .sortedByDescending(catalogue::score)
        return distinct(
            catalogue.books(works.take(limit * 3)),
            limit,
            setOf(titleKey(book)),
        )
    }

    fun authorBooks(author: Author, limit: Int = 90): List<Book> {
        val works = catalogue.authorWorks(author).sortedByDescending(catalogue::score)
        return distinct(catalogue.books(works.take(limit * 2)), limit)
    }

    fun popular(limit: Int = 20, tag: Int? = null): List<Book> {
        val picker = Picker()
        return catalogue
            .popularWorks(limit * 6, tag)
            .mapNotNull(picker::accept)
            .take(limit)
    }

    private fun distinct(
        books: List<Book>,
        limit: Int,
        seen: Set<TitleKey> = emptySet(),
    ) =
        books
            .filter { titleKey(it) !in seen && !it.isCompanion }
            .distinctBy(::titleKey)
            .take(limit)
}
