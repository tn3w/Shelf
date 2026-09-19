package dev.tn3w.shelf.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private val SHELF_WEIGHT =
    mapOf(Shelf.Read to 0.8, Shelf.Reading to 1.0, Shelf.Want to 0.5)
private val FORM_TAGS = setOf("fiction", "nonfiction")
private val AUDIENCE_TAGS =
    setOf("picture-book", "childrens", "middle-grade", "young-adult")
private val BROAD_TAGS = FORM_TAGS + AUDIENCE_TAGS
private val ARTICLES = setOf("the", "a", "an", "der", "die", "das", "le", "la", "el")
private const val MAX_SOURCES = 24
private const val MERGE_OVERLAP = 0.7
private const val WEAK_MERGE_OVERLAP = 0.3
private const val MERGE_AUDIENCE_GAP = 1.0
private const val MIN_OWN_ROW_TAGS = 2
private const val MIN_SHARED = 200
private const val PER_TAG = 1500
private const val AUTHOR_DEPTH = 40
private const val MAX_PER_AUTHOR = 2
private const val MAX_AUTHOR_ROWS = 2
private const val MIN_ROW = 4
private const val MIN_SERIES_ROW = 2
private const val ADULT = 4
private const val MAX_AUDIENCE_GAP = 2.0
private const val SPECIFIC_TAGS = 3.0
private const val JITTER = 0.15
private const val READERS_REFERENCE = 50_000.0
private const val RATING_PRIOR = 8

private typealias Vector = Map<Int, Double>

private data class TitleKey(val key: String, val author: String)

private class Source(
    val book: Book,
    val vector: Vector,
    val weight: Double,
    val audience: Int,
    val fiction: Boolean,
    val read: Boolean,
)

private class Cluster(val sources: List<Source>) {
    val weight = sources.sumOf { it.weight }
    val vector = blend(sources)
    val audience = sources.sumOf { it.weight * it.audience } / weight
    val fiction = sources.sumOf { it.weight * if (it.fiction) 1.0 else 0.0 } >= weight / 2
    val books = sources.map { it.book }
    val readBooks = sources.filter { it.read }.map { it.book }
}

private class Profile(
    val tags: Vector,
    val seen: Set<TitleKey>,
    val library: Set<Int>,
    val hidden: Set<Int>,
    val librarySeries: Set<String>,
    val clusters: List<Cluster>,
)

private class Scored(val score: Double, val work: Int)

data class AuthorGroup(val series: String?, val books: List<Book>)

enum class RowKind {
    Series,
    Because,
    Author,
    Popular,
}

class Row(
    val kind: RowKind,
    val books: List<Book>,
    val sources: List<Book> = emptyList(),
    val author: Author? = null,
) {
    val key =
        listOf(
                kind.name,
                "${books.firstOrNull()?.work}",
                sources.joinToString("-") { "${it.work}" },
                "${author?.number}",
            )
            .joinToString("-")
}

private fun titleKey(book: Book): TitleKey {
    val tokens = tokenize(mainTitle(book.title))
    val trimmed = if (tokens.firstOrNull() in ARTICLES) tokens.drop(1) else tokens
    return TitleKey(trimmed.joinToString(" "), book.author)
}

private fun Vector.norm() = sqrt(values.sumOf { it * it }).takeIf { it > 0 } ?: 1.0

private fun Vector.normalized() = norm().let { length -> mapValues { it.value / length } }

private fun blend(sources: List<Source>): Vector {
    val merged = mutableMapOf<Int, Double>()
    sources.forEach { source ->
        source.vector.forEach { (tag, value) ->
            merged.merge(tag, source.weight * value, Double::plus)
        }
    }
    return merged.normalized()
}

private fun coverage(candidate: Vector, cluster: Vector): Double {
    val total = cluster.values.sumOf { it * it }
    if (total <= 0) return 1.0
    val covered = cluster.filterKeys { it in candidate.keys }.values.sumOf { it * it }
    return covered / total
}

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

    private fun vectorOf(tags: List<Int>): Vector {
        val specific = tagVector(tags) { slug[it] !in AUDIENCE_TAGS }
        if (specificTags(specific) > 0) return specific.normalized()
        return tagVector(tags) { slug[it] in AUDIENCE_TAGS }.normalized()
    }

    private fun tagVector(tags: List<Int>, keep: (Int) -> Boolean): Vector =
        tags
            .filter(keep)
            .withIndex()
            .associate { (index, tag) ->
                val confidence = 1.0 / (1.0 + 0.15 * index)
                val weight = if (slug[tag] in FORM_TAGS) 0.25 else 1.0
                tag to (idf[tag] ?: 0.0) * confidence * weight
            }

    private fun specificTags(vector: Vector) =
        vector.keys.count { slug[it] !in BROAD_TAGS }

    private fun audienceOf(slugs: Set<String>) =
        when {
            "picture-book" in slugs -> 0
            "young-adult" in slugs -> 3
            "middle-grade" in slugs -> 2
            "childrens" in slugs -> 1
            else -> ADULT
        }

    private fun sourceOf(entry: Saved): Source? {
        val book = catalogue.book(entry.work) ?: return null
        val vector = vectorOf(book.tags)
        if (vector.isEmpty()) return null
        val slugs = book.tags.mapNotNull(slug::get).toSet()
        return Source(
            book,
            vector,
            SHELF_WEIGHT.getValue(entry.shelf),
            audienceOf(slugs),
            "fiction" in slugs || "nonfiction" !in slugs,
            entry.shelf != Shelf.Want,
        )
    }

    private fun buildProfile(entries: List<Saved>, hidden: Set<Int>): Profile {
        val sources = entries.mapNotNull(::sourceOf).take(MAX_SOURCES)
        val tags = mutableMapOf<Int, Double>()
        sources.forEach { source ->
            source.vector.forEach { (tag, value) ->
                tags.merge(tag, source.weight * value, Double::plus)
            }
        }
        val saved = entries.map { it.work }.toSet()
        return Profile(
            tags = tags.normalized(),
            seen = sources.map { titleKey(it.book) }.toSet(),
            library = saved,
            hidden = hidden,
            librarySeries = saved.mapNotNull { catalogue.series(it)?.name }.toSet(),
            clusters = cluster(sources),
        )
    }

    private fun cluster(sources: List<Source>): List<Cluster> {
        val groups = mutableListOf<MutableList<Source>>()
        for (source in sources.sortedByDescending { it.weight }) {
            val match = bestGroup(source, groups)
            if (match != null) match += source else groups += mutableListOf(source)
        }
        return groups.map(::Cluster).sortedByDescending { it.weight }
    }

    private fun bestGroup(
        source: Source,
        groups: List<MutableList<Source>>,
    ): MutableList<Source>? {
        val weak = specificTags(source.vector) < MIN_OWN_ROW_TAGS
        val needed = if (weak) WEAK_MERGE_OVERLAP else MERGE_OVERLAP
        return groups
            .filter { group ->
                group.all { abs(it.audience - source.audience) <= MERGE_AUDIENCE_GAP }
            }
            .map { it to overlap(source.vector, blend(it)) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= needed }
            ?.first
    }

    private fun worksOf(author: Author) =
        catalogue.authorWorks(author).filter {
            catalogue.book(it)?.authors?.firstOrNull()?.number == author.number
        }

    private fun retrieve(cluster: Cluster): Set<Int> {
        val tags =
            cluster.vector.keys
                .filter { slug[it] !in FORM_TAGS }
                .sortedByDescending { cluster.vector.getValue(it) }
        val works = mutableSetOf<Int>()
        if (tags.isNotEmpty()) {
            val pool = catalogue.tagWorks(tags[0])
            works += catalogue.topWorks(narrow(pool, tags), PER_TAG)
            works += catalogue.topWorks(pool, PER_TAG / 3)
        }
        cluster.books.forEach { book ->
            book.authors.forEach { works += catalogue.authorWorks(it).take(AUTHOR_DEPTH) }
        }
        return works
    }

    private fun narrow(pool: IntArray, tags: List<Int>): IntArray {
        val second = tags.getOrNull(1) ?: return pool
        val members = catalogue.tagWorks(second).toHashSet()
        val shared = pool.filter { it in members }
        return if (shared.size < MIN_SHARED) pool else shared.toIntArray()
    }

    private fun quality(work: Int): Double {
        val popularity = catalogue.ranks?.popularity(work) ?: return 0.0
        val readers =
            min(1.0, ln(1.0 + popularity.readers) / ln(1.0 + READERS_REFERENCE))
        val mean =
            (RATING_PRIOR * 3.8 + popularity.rating * popularity.ratings) /
                (RATING_PRIOR + popularity.ratings)
        return 0.7 * readers + 0.3 * (mean - 3.0) / 2.0
    }

    private fun score(profile: Profile, cluster: Cluster, work: Int): Scored? {
        val facts = catalogue.facts(work) ?: return null
        val tags = facts.tags.toList()
        val vector = vectorOf(tags)
        if (vector.isEmpty()) return null
        val slugs = tags.mapNotNull(slug::get).toSet()
        if (("fiction" in slugs) != cluster.fiction) return null
        val gap = audienceOf(slugs) - cluster.audience
        if (abs(gap) > MAX_AUDIENCE_GAP) return null
        val neighbour = overlap(vector, cluster.vector)
        if (neighbour <= 0.2) return null
        val general = overlap(vector, profile.tags)
        val specificity = min(1.0, specificTags(cluster.vector) / SPECIFIC_TAGS)
        val value =
            0.40 * specificity * neighbour +
                (0.20 + 0.30 * (1 - specificity)) * general +
                (0.40 + 0.10 * (1 - specificity)) * quality(work)
        val younger = max(0.0, -gap)
        val older = max(0.0, gap)
        val covered = 0.3 + 0.7 * coverage(vector, cluster.vector)
        return Scored(value * covered / (1.0 + 0.4 * younger + 0.15 * older), work)
    }

    private fun rank(
        profile: Profile,
        cluster: Cluster,
        random: Random,
        limit: Int,
    ): List<Scored> =
        retrieve(cluster)
            .mapNotNull { score(profile, cluster, it) }
            .map { Scored(it.score * random.nextDouble(1 - JITTER, 1 + JITTER), it.work) }
            .sortedByDescending { it.score }
            .take(limit)

    private inner class Picker(
        private val library: Set<Int> = emptySet(),
        private val hidden: Set<Int> = emptySet(),
        private val blockedSeries: Set<String> = emptySet(),
        private val titles: MutableSet<TitleKey> = mutableSetOf(),
        private val authorLimit: Int = MAX_PER_AUTHOR,
        private val blockedTitles: Set<String> = emptySet(),
    ) {
        private val series = mutableSetOf<String>()
        private val perAuthor = mutableMapOf<String, Int>()

        private fun firstUnread(work: Int): Int {
            val members = catalogue.series(work)?.members ?: return work
            return members.firstOrNull {
                it !in library && it !in hidden && catalogue.locate(it) != null
            } ?: work
        }

        fun accept(work: Int): Book? {
            val book = catalogue.book(firstUnread(work)) ?: return null
            if (book.isCompanion || book.work in library || book.work in hidden)
                return null
            val name = catalogue.series(book.work)?.name
            if (name != null && (name in series || name in blockedSeries)) return null
            val key = titleKey(book)
            if (key.key in blockedTitles) return null
            val count = perAuthor[book.author] ?: 0
            if (count >= authorLimit || !titles.add(key)) return null
            if (name != null) series += name
            perAuthor[book.author] = count + 1
            return book
        }
    }

    private fun seriesRow(
        profile: Profile,
        taken: MutableSet<TitleKey>,
        size: Int,
    ): Row? {
        val books =
            profile.library
                .mapNotNull { work -> nextVolume(work, profile) }
                .distinct()
                .let(catalogue::books)
                .filter { taken.add(titleKey(it)) }
                .take(size)
        return if (books.size < MIN_SERIES_ROW) null else Row(RowKind.Series, books)
    }

    private fun nextVolume(work: Int, profile: Profile): Int? {
        val members = catalogue.series(work)?.members ?: return null
        if (work !in members) return null
        return members.firstOrNull {
            it !in profile.library &&
                it !in profile.hidden &&
                catalogue.locate(it) != null
        }
    }

    private fun picker(
        profile: Profile,
        taken: MutableSet<TitleKey>,
        authorLimit: Int = MAX_PER_AUTHOR,
    ) =
        Picker(
            profile.library,
            profile.hidden,
            profile.librarySeries,
            taken,
            authorLimit,
            profile.seen.map { it.key }.toSet(),
        )

    private fun authorRows(
        profile: Profile,
        taken: MutableSet<TitleKey>,
        size: Int,
    ): List<Row> {
        val authors =
            profile.clusters
                .flatMap { cluster -> cluster.sources.map { it.book to cluster.weight } }
                .mapNotNull { (book, weight) -> book.authors.firstOrNull()?.to(weight) }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
        val rows = mutableListOf<Row>()
        for (author in authors) {
            if (rows.size >= MAX_AUTHOR_ROWS) break
            val picker = picker(profile, taken, authorLimit = size)
            val books =
                worksOf(author)
                    .sortedByDescending(catalogue::score)
                    .take(size * 4)
                    .mapNotNull(picker::accept)
                    .take(size)
            if (books.size >= MIN_ROW) rows += Row(RowKind.Author, books, author = author)
        }
        return rows
    }

    fun rows(
        entries: List<Saved>,
        seed: Long = 0,
        hidden: Set<Int> = emptySet(),
        size: Int = 12,
    ): List<Row> {
        val profile = buildProfile(entries, hidden)
        if (profile.clusters.isEmpty()) {
            return listOf(Row(RowKind.Popular, popular(size)))
        }
        val random = Random(seed)
        val taken = profile.seen.toMutableSet()
        val rows = mutableListOf<Row>()
        seriesRow(profile, taken, size)?.let { rows += it }
        for (cluster in profile.clusters) {
            val picker = picker(profile, taken)
            val books =
                rank(profile, cluster, random, size * 4)
                    .mapNotNull { picker.accept(it.work) }
            if (books.size >= MIN_ROW) {
                rows += Row(RowKind.Because, books.take(size), cluster.readBooks)
            }
        }
        rows += authorRows(profile, taken, size)
        return rows
    }

    fun similar(book: Book, limit: Int = 12): List<Book> {
        val profile = buildProfile(listOf(book.toSaved(Shelf.Read)), emptySet())
        val cluster = profile.clusters.firstOrNull() ?: return popular(limit)
        val picker =
            Picker(setOf(book.work), titles = mutableSetOf(titleKey(book)))
        return rank(profile, cluster, Random(book.work.toLong()), limit * 4)
            .mapNotNull { picker.accept(it.work) }
            .take(limit)
    }

    fun series(book: Book): Pair<String, List<Book>>? {
        val series = catalogue.series(book.work) ?: return null
        return series.name to catalogue.books(series.members)
    }

    fun byAuthor(book: Book, limit: Int = 12): List<Book> {
        val author = book.authors.firstOrNull() ?: return emptyList()
        val sameSeries = catalogue.series(book.work)?.members.orEmpty().toSet()
        val works =
            worksOf(author).filter { it != book.work && it !in sameSeries }
                .sortedByDescending(catalogue::score)
        return distinct(
            catalogue.books(works.take(limit * 3)),
            limit,
            setOf(titleKey(book)),
        )
    }

    private fun authorBooks(author: Author, limit: Int): List<Book> {
        val works = catalogue.authorWorks(author).sortedByDescending(catalogue::score)
        return distinct(catalogue.books(works.take(limit * 2)), limit)
    }

    fun authorShelf(author: Author, limit: Int = 90): List<AuthorGroup> {
        val grouped =
            authorBooks(author, limit).groupBy { catalogue.series(it.work)?.name }
        val (series, single) =
            grouped.entries.partition { it.key != null && it.value.size > 1 }
        val standalone = single.flatMap { it.value }
        val byScore = compareByDescending<Book> { catalogue.score(it.work) }
        val groups =
            series
                .map { (name, members) -> AuthorGroup(name, readingOrder(members)) }
                .sortedByDescending { group ->
                    group.books.maxOf { catalogue.score(it.work) }
                }
        if (standalone.isEmpty()) return groups
        return groups + AuthorGroup(null, standalone.sortedWith(byScore))
    }

    private fun readingOrder(books: List<Book>): List<Book> {
        val order = catalogue.series(books.first().work)?.members.orEmpty()
        return books.sortedBy {
            val rank = order.indexOf(it.work)
            if (rank < 0) order.size else rank
        }
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
