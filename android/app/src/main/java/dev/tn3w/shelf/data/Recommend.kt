package dev.tn3w.shelf.data

import androidx.collection.MutableIntList
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

private val SHELF_WEIGHT =
    mapOf(Shelf.Read to 0.8, Shelf.Reading to 1.0, Shelf.Want to 0.5)
private val FORM_TAGS = setOf("fiction", "nonfiction")
private val AUDIENCE_TAGS =
    setOf("picture-book", "childrens", "middle-grade", "young-adult")
private val BROAD_TAGS = FORM_TAGS + AUDIENCE_TAGS

internal fun isFiction(slugs: Set<String>) = "fiction" in slugs || "nonfiction" !in slugs

private const val MAX_SOURCES = 40
private const val MERGE_OVERLAP = 0.7
private const val WEAK_MERGE_OVERLAP = 0.3
private const val MERGE_AUDIENCE_GAP = 1.0
private const val MIN_OWN_ROW_TAGS = 2
private const val MAX_PER_AUTHOR = 2
private const val MAX_AUTHOR_ROWS = 2
private const val MIN_ROW = 4
private const val MIN_SERIES_ROW = 2
private const val MAX_AUDIENCE_GAP = 2.0
private const val JITTER = 0.15
private const val READERS_REFERENCE = 50_000.0
private const val RATING_PRIOR = 8
private const val RATING_PRIOR_MEAN = 3.8
private const val RATING_FLOOR = 3.0
private const val RATING_SPAN = 2.0
private const val READERS_SHARE = 0.7
private const val RATING_SHARE = 0.3
private const val CONFIDENCE_DECAY = 0.15
private const val FORM_WEIGHT = 0.25
private const val MIN_NEIGHBOUR = 0.2
private const val MIN_COVERAGE = 0.3
private const val COVERAGE_WEIGHT = 0.7
private const val YOUNGER_PENALTY = 0.4
private const val OLDER_PENALTY = 0.15

/** Retrieval: posting lists of the taste's strongest tags, then a popularity prior. */
private const val TASTE_TAGS = 6
private const val CANDIDATES = 400
private const val PRIOR_POPULARITY = 0.3
private const val AUTHOR_DEPTH = 40
private const val MIN_CANDIDATES = 120
private const val MAX_CLUSTERS = 4
private const val SPARSE_CANDIDATES = 1000
private const val SPARSE_SHARE = 0.5
private const val SPARSE_ERA_WEIGHT = 0.6

/** Ranking weights. */
private const val SIMILARITY_WEIGHT = 0.60
private const val ERA_WEIGHT = 0.15
private const val QUALITY_WEIGHT = 0.35
private const val AFFINITY_WEIGHT = 0.15
private const val ERA_SCALE = 25.0
private const val ERA_UNKNOWN = 0.4
private const val FOREIGN_PENALTY = 0.35

/** Description text: pool-local TF-IDF cosine against the seed's descriptions. */
private const val TEXT_WEIGHT = 0.8
private const val TEXT_DEPTH = 100
private const val MIN_TEXT_DEPTH = 30
private const val ROW_POOL = 4
private const val TEXT_CHARS = 1000
private const val TEXT_WORD = 4
private const val TEXT_SEEDS = 6
private const val TEXT_CACHE = 2048

/** Diversity re-ranking. */
private const val POOL_FACTOR = 5
private const val AUTHOR_DECAY = 0.55
private const val CLUSTER_DECAY = 0.85

/** Library weighting. */
private const val RECENCY_HALF_LIFE = 5.0
private const val RECENCY_FLOOR = 0.3
private const val REPEAT_BOOST = 0.5
private const val SPARSE_TAGS = 2
private const val ENRICH_WEIGHT = 0.4
private const val ENRICH_WORKS = 6
private const val DISLIKED_STARS = 2
private const val DISLIKE_WEIGHT = 0.4
private const val DISLIKED_AUTHOR = 0.6
private const val SEED_AUTHOR_WORKS = 8
private const val MIN_SEED_WEIGHT = 0.1

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
    val fiction = sources.filter { it.fiction }.sumOf { it.weight } >= weight / 2
    val books = sources.map { it.book }
    val readBooks = sources.filter { it.read }.map { it.book }
}

private class Profile(
    val seen: Set<TitleKey>,
    val library: Set<Int>,
    val hidden: Set<Int>,
    val librarySeries: Set<String>,
    val clusters: List<Cluster>,
    val authors: Map<Int, Double>,
    val dislikes: Dislikes,
)

/** Books rated one or two stars: their tags and authors count against candidates. */
private class Dislikes(val vector: Vector = emptyMap(), val authors: Set<Int> = emptySet()) {
    val isEmpty get() = vector.isEmpty() && authors.isEmpty()
}

/** What a seed asks for: a tag profile, an era, an audience and favoured authors. */
private class Taste(
    val vector: Vector,
    val year: Double,
    val spread: Double,
    val audience: Double,
    val fiction: Boolean,
    val authors: Map<Int, Double> = emptyMap(),
    val seedAuthors: Set<Int> = emptySet(),
    val works: Map<Int, Double> = emptyMap(),
    val dislikes: Dislikes = Dislikes(),
) {
    /** Mostly broad tags even after enrichment: lean on era and quality instead. */
    var sparse = false
}

/** What a seed rules out. */
private class Bounds(
    val works: Set<Int> = emptySet(),
    val titles: Set<String> = emptySet(),
    val series: Set<String> = emptySet(),
    val authors: Set<Int> = emptySet(),
    val seedAuthorLimit: Int = 1,
    /** Seed authors and the distinctive words of their seed titles and series. */
    val kin: Map<Int, Set<String>> = emptyMap(),
    val groups: Boolean = false,
)

private const val KIN_WORD = 4
private val COMMON_WORDS by lazy { STOPWORDS.values.flatten().toSet() }

/** Distinctive words of a series name or title, with elided articles split off. */
private fun kinWords(name: String) = tokenize(name.replace('\'', ' ').replace('’', ' '))
    .filter { it.length >= KIN_WORD && it !in COMMON_WORDS && !it.all(Char::isDigit) }

private class Scored(
    val score: Double,
    val work: Int,
    val signals: Map<String, Double> = emptyMap(),
    val group: Int = 0,
)

sealed interface RecommendSeed {
    data class Work(val book: Book) : RecommendSeed

    data class Writer(val author: Author) : RecommendSeed

    data class Saga(val book: Book) : RecommendSeed

    data class Library(val entries: List<Saved>, val hidden: Set<Int> = emptySet()) :
        RecommendSeed
}

class Recommendation(val book: Book, val score: Double, val signals: Map<String, Double>)

data class AuthorGroup(val series: String?, val books: List<Book>)

enum class RowKind {
    Series,
    Because,
    Author,
    Popular,
}

class HomeRow(
    val kind: RowKind,
    val books: List<Book>,
    val sources: List<Book> = emptyList(),
    val author: Author? = null,
) {
    val key = listOf(
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

private val LOCAL_LETTERS = mapOf(
    "de" to "äöüß",
    "fr" to "éèêàçùœ",
    "es" to "ñáéíóú¿¡",
    "sv" to "åäö",
    "fi" to "äö",
    "pl" to "ąćęłńóśźż",
    "cs" to "čďěňřšťůž",
    "et" to "õäöüšž",
)
private val STOPWORDS = mapOf(
    "en" to setOf("the", "of", "and", "with", "from", "my", "your", "this", "that", "is"),
    "de" to setOf("der", "die", "das", "und", "ein", "eine", "im", "mit", "von", "des", "dem"),
    "fr" to setOf("le", "la", "les", "et", "du", "des", "une", "au", "aux", "pour", "dans"),
    "es" to setOf("el", "la", "los", "las", "y", "del", "una", "con", "por", "para"),
    "sv" to setOf("och", "som", "ett", "av", "med", "till", "det", "för", "på"),
    "fi" to setOf("ja", "sekä", "tai", "kun"),
    "pl" to setOf("i", "w", "z", "na", "nie", "do", "się", "jak"),
    "cs" to setOf("a", "v", "z", "na", "se", "do", "je", "pro", "ve"),
    "et" to setOf("ja", "ning", "kui", "see"),
)

/**
 * Whether a title reads as the catalogue's language. Works are shared between language
 * catalogues and a work without a local edition keeps its original, often English, title.
 */
internal fun isLocalTitle(title: String, language: String): Boolean {
    if (language == "ko") return title.any { it in '\uac00'..'\ud7a3' }
    val lower = title.lowercase()
    if (LOCAL_LETTERS[language]?.any { it in lower } == true) return true
    val words = tokenize(title).toSet()
    if (words.any { it in STOPWORDS[language].orEmpty() }) return true
    val foreign = STOPWORDS.filterKeys { it != language }.values
    return foreign.none { stopwords -> words.any(stopwords::contains) }
}

/** Reader count on a log scale blended with a Bayesian-smoothed rating, in [0, 1]. */
internal fun qualityOf(popularity: Popularity): Double {
    val readers = min(1.0, ln(1.0 + popularity.readers) / ln(1.0 + READERS_REFERENCE))
    val ratings = popularity.ratings
    val mean = (RATING_PRIOR * RATING_PRIOR_MEAN + popularity.rating * ratings) /
        (RATING_PRIOR + ratings)
    val rating = ((mean - RATING_FLOOR) / RATING_SPAN).coerceIn(0.0, 1.0)
    return (READERS_SHARE * readers + RATING_SHARE * rating).coerceIn(0.0, 1.0)
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

/**
 * Seeded recommendations over one language catalogue. [resolve] finds works the catalogue
 * lacks, such as library entries saved under another language, so they still shape the
 * profile; results always come from [catalogue].
 */
class Recommender(
    private val catalogue: Catalogue,
    private val resolve: (Int) -> Book? = { null },
) {
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
        val audience = tagVector(tags) { slug[it] in AUDIENCE_TAGS }
        return audience.ifEmpty { specific }.normalized()
    }

    private fun tagVector(tags: List<Int>, keep: (Int) -> Boolean): Vector = tags
        .filter(keep)
        .withIndex()
        .associate { (index, tag) ->
            val confidence = 1.0 / (1.0 + CONFIDENCE_DECAY * index)
            val weight = if (slug[tag] in FORM_TAGS) FORM_WEIGHT else 1.0
            tag to (idf[tag] ?: 0.0) * confidence * weight
        }

    private fun specificTags(vector: Vector) =
        vector.keys.count { slug[it] !in BROAD_TAGS }

    private fun specificShare(vector: Vector): Double {
        val total = vector.values.sumOf { it * it }
        if (total <= 0) return 0.0
        return vector.filterKeys { slug[it] !in BROAD_TAGS }.values.sumOf { it * it } / total
    }

    private fun audienceOf(slugs: Set<String>) = when {
        "picture-book" in slugs -> 0
        "young-adult" in slugs -> 3
        "middle-grade" in slugs -> 2
        "childrens" in slugs -> 1
        else -> 4
    }

    private fun sourceOf(book: Book, weight: Double, read: Boolean = true): Source? {
        val vector = vectorOf(book.tags)
        if (vector.isEmpty()) return null
        val slugs = book.tags.mapNotNull(slug::get).toSet()
        return Source(book, vector, weight, audienceOf(slugs), isFiction(slugs), read)
    }

    /**
     * Library sources, most recent first. Weight falls with recency rank, rises with the
     * star rating and with repeated entries for the same work.
     */
    private fun librarySources(entries: List<Saved>): List<Source> = entries
        .filter { it.rating !in 1..DISLIKED_STARS }
        .groupBy { it.work }
        .map { (_, saved) -> saved.maxBy { max(it.updated, it.added) } to saved.size }
        .sortedByDescending { (entry, _) -> max(entry.updated, entry.added) }
        .take(MAX_SOURCES)
        .mapIndexedNotNull { rank, (entry, repeats) ->
            val book = catalogue.book(entry.work) ?: resolve(entry.work)
                ?: return@mapIndexedNotNull null
            val recency = RECENCY_FLOOR +
                (1 - RECENCY_FLOOR) * 0.5.pow(rank / RECENCY_HALF_LIFE)
            val rating = if (entry.rating > 0) 0.5 + entry.rating / 5.0 else 1.0
            val repeat = 1 + REPEAT_BOOST * (repeats - 1)
            val weight = SHELF_WEIGHT.getValue(entry.shelf) * recency * rating * repeat
            sourceOf(book, weight, entry.shelf != Shelf.Want)
        }

    private fun buildProfile(entries: List<Saved>, hidden: Set<Int>): Profile {
        val sources = librarySources(entries)
        val saved = entries.map { it.work }.toSet()
        return Profile(
            seen = sources.map { titleKey(it.book) }.toSet(),
            library = saved,
            hidden = hidden,
            librarySeries = saved.mapNotNull { catalogue.series(it)?.name }.toSet(),
            clusters = cluster(sources),
            authors = authorAffinity(sources),
            dislikes = dislikesOf(entries),
        )
    }

    private fun dislikesOf(entries: List<Saved>): Dislikes {
        val books = entries
            .filter { it.rating in 1..DISLIKED_STARS }
            .mapNotNull { catalogue.book(it.work) ?: resolve(it.work) }
        val sources = books.mapNotNull { sourceOf(it, 1.0) }
        val liked = entries.filter { it.rating == 0 || it.rating > DISLIKED_STARS }
            .mapNotNull { catalogue.firstAuthorNumber(it.work) }
            .toSet()
        val authors = books.mapNotNull { it.authors.firstOrNull()?.number }
            .filter { it !in liked }
            .toSet()
        return Dislikes(if (sources.isEmpty()) emptyMap() else blend(sources), authors)
    }

    /** Authors that recur in the library, scaled so the most read one is 1. */
    private fun authorAffinity(sources: List<Source>): Map<Int, Double> {
        val weights = sources
            .flatMap { source -> source.book.authors.take(1).map { it.number to source } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .mapValues { (_, books) -> books.sumOf { it.weight } }
        val top = weights.values.maxOrNull() ?: return emptyMap()
        return weights.mapValues { it.value / top }
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

    private fun worksOf(author: Author) = catalogue.authorWorks(author).filter {
        catalogue.firstAuthorNumber(it) == author.number
    }

    /** A seed with too few specific tags borrows them from its author's best works. */
    private fun enriched(sources: List<Source>): List<Source> {
        if (sources.any { specificTags(it.vector) >= SPARSE_TAGS }) return sources
        val own = sources.map { it.book.work }.toSet()
        val borrowed = sources
            .flatMap { it.book.authors.take(1) }
            .distinct()
            .flatMap { author ->
                catalogue.authorWorks(author)
                    .filter { it !in own }
                    .sortedByDescending(catalogue::score)
                    .take(ENRICH_WORKS)
            }
            .mapNotNull(catalogue::book)
            .mapNotNull { sourceOf(it, ENRICH_WEIGHT / ENRICH_WORKS) }
            .filter { specificTags(it.vector) > 0 }
        return sources + borrowed
    }

    private fun tasteOf(
        sources: List<Source>,
        authors: Map<Int, Double> = emptyMap(),
        seedAuthors: Set<Int> = emptySet(),
        dislikes: Dislikes = Dislikes(),
    ): Taste? {
        if (sources.isEmpty()) return null
        val weight = sources.sumOf { it.weight }
        val dated = sources.filter { it.book.year > 0 }
        val datedWeight = dated.sumOf { it.weight }
        val year = if (datedWeight > 0) {
            dated.sumOf { it.weight * it.book.year } / datedWeight
        } else {
            0.0
        }
        val spread = if (datedWeight > 0) {
            sqrt(dated.sumOf { it.weight * (it.book.year - year).pow(2) } / datedWeight)
        } else {
            0.0
        }
        return Taste(
            vector = blend(sources),
            year = year,
            spread = spread,
            audience = sources.sumOf { it.weight * it.audience } / weight,
            fiction = sources.filter { it.fiction }.sumOf { it.weight } >= weight / 2,
            authors = authors,
            seedAuthors = seedAuthors,
            works = sources.groupBy { it.book.work }
                .mapValues { (_, all) -> all.sumOf { it.weight } },
            dislikes = dislikes,
        ).also { taste -> taste.sparse = specificShare(taste.vector) < SPARSE_SHARE }
    }

    private fun tasteOf(
        cluster: Cluster,
        authors: Map<Int, Double>,
        dislikes: Dislikes = Dislikes(),
    ) = tasteOf(cluster.sources, authors, dislikes = dislikes)

    /**
     * Candidate retrieval: accumulate the taste's weight over the posting lists of its
     * strongest tags, so a work matching several rare tags outranks a bestseller that
     * matches one. Popularity only breaks near ties.
     */
    private fun retrieve(taste: Taste, extra: Collection<Int>, size: Int): Set<Int> {
        val ranked = taste.vector.entries.sortedByDescending { it.value }
        val specific = ranked.filter { slug[it.key] !in FORM_TAGS }
        val tags = (if (taste.sparse) ranked else specific.ifEmpty { ranked }).take(TASTE_TAGS)
        val limit = if (taste.sparse) SPARSE_CANDIDATES * size / CANDIDATES else size
        val heap = PriorityQueue<Pair<Double, Int>>(limit + 1, compareBy { it.first })
        synchronized(sums) {
            val touched = MutableIntList()
            for ((tag, weight) in tags) {
                val value = (weight * (idf[tag] ?: 0.0)).toFloat()
                if (value <= 0f) continue
                for (index in catalogue.indexesOf(catalogue.tagWorks(tag))) {
                    if (index < 0) continue
                    if (sums[index] == 0f) touched.add(index)
                    sums[index] += value
                }
            }
            var best = 0f
            touched.forEach { best = max(best, sums[it]) }
            val floor = best * (1 - PRIOR_POPULARITY)
            touched.forEach { index ->
                val sum = sums[index]
                sums[index] = 0f
                if (sum < floor && heap.size >= limit) return@forEach
                val prior = sum / best + PRIOR_POPULARITY * catalogue.popularityAt(index)
                if (heap.size < limit) {
                    heap.add(prior to index)
                } else if (prior > heap.peek()!!.first) {
                    heap.poll()
                    heap.add(prior to index)
                }
            }
        }
        return heap.map { catalogue.workAt(it.second) }.toSet() + extra
    }

    /** Per-work accumulator for [retrieve], kept zeroed between calls. */
    private val sums by lazy { FloatArray(catalogue.indexed) }

    private fun score(taste: Taste, work: Int, group: Int = 0): Scored? {
        val facts = catalogue.facts(work) ?: return null
        val tags = facts.tags.toList()
        val vector = vectorOf(tags)
        if (vector.isEmpty()) return null
        val slugs = tags.mapNotNull(slug::get).toSet()
        if (isFiction(slugs) != taste.fiction) return null
        val gap = audienceOf(slugs) - taste.audience
        if (abs(gap) > MAX_AUDIENCE_GAP) return null
        val similarity = if (taste.sparse) {
            coverage(tags.associateWith { 1.0 }, taste.vector)
        } else {
            overlap(vector, taste.vector)
        }
        if (similarity <= MIN_NEIGHBOUR) return null
        val covered = MIN_COVERAGE + COVERAGE_WEIGHT * coverage(vector, taste.vector)
        val era = if (taste.year > 0 && facts.year > 0) {
            exp(-abs(facts.year - taste.year) / (ERA_SCALE + taste.spread))
        } else {
            ERA_UNKNOWN
        }
        val quality = catalogue.quality(work)
        val affinity = if (taste.authors.isEmpty()) {
            0.0
        } else {
            taste.authors[catalogue.firstAuthorNumber(work)] ?: 0.0
        }
        val penalty = 1.0 + YOUNGER_PENALTY * max(0.0, -gap) + OLDER_PENALTY * max(0.0, gap)
        val eraWeight = if (taste.sparse) SPARSE_ERA_WEIGHT else ERA_WEIGHT
        val aversion = aversionTo(taste.dislikes, vector, work)
        val value = (
            SIMILARITY_WEIGHT * similarity * covered + eraWeight * era +
                QUALITY_WEIGHT * quality + AFFINITY_WEIGHT * affinity
            ) * (1 - aversion) / penalty
        return Scored(
            value,
            work,
            mapOf(
                "sim" to similarity,
                "cover" to covered,
                "era" to era,
                "quality" to quality,
                "affinity" to affinity,
                "aversion" to aversion,
                "penalty" to penalty,
            ),
            group,
        )
    }

    private val texts = object : LinkedHashMap<Int, Map<String, Int>>(TEXT_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<String, Int>>) =
            size > TEXT_CACHE
    }

    /** Content words of a work's description, with counts; its authors' names left out. */
    private fun textOf(work: Int, book: Book? = null): Map<String, Int> = synchronized(texts) {
        texts.getOrPut(work) {
            val text = catalogue.description(work).text
            if (text.isEmpty()) return@getOrPut emptyMap()
            val names = (book ?: catalogue.book(work))?.authors.orEmpty()
                .flatMap { tokenize(it.name) }
                .toSet()
            tokenize(text.take(TEXT_CHARS))
                .filter { it.length >= TEXT_WORD && !it.all(Char::isDigit) && it !in names }
                .groupingBy { it }
                .eachCount()
        }
    }

    /**
     * Refines the head of a ranking with what needs the full record: titles in another
     * language count for less, and description similarity is added. Text term weights
     * come from the head itself, so words every candidate shares (genre boilerplate) count
     * for little and names, places and themes the seed shares with a few count for much.
     */
    private fun refine(
        taste: Taste,
        scored: List<Scored>,
        depth: Int = TEXT_DEPTH,
    ): List<Scored> {
        val sorted = scored.sortedByDescending { it.score }
        val head = sorted.take(depth)
        val books = head.map { catalogue.book(it.work) }
        val docs = head.zip(books) { candidate, book -> textOf(candidate.work, book) }
        val seed = mutableMapOf<String, Double>()
        taste.works.entries.sortedByDescending { it.value }.take(TEXT_SEEDS)
            .forEach { (work, weight) ->
                textOf(work).forEach { (term, count) ->
                    seed.merge(term, weight * (1 + ln(count.toDouble())), Double::plus)
                }
            }
        val frequency = HashMap<String, Int>()
        docs.forEach { doc -> doc.keys.forEach { frequency.merge(it, 1, Int::plus) } }
        val count = docs.size + 1.0
        fun idf(term: String) = ln((count + 1) / ((frequency[term] ?: 0) + 1.0))
        val query = seed.mapValues { (term, value) -> value * idf(term) }
        val queryNorm = sqrt(query.values.sumOf { it * it })
        val rescored = head.indices.map { index ->
            val candidate = head[index]
            var dot = 0.0
            var norm = 0.0
            docs[index].forEach { (term, value) ->
                val weight = (1 + ln(value.toDouble())) * idf(term)
                norm += weight * weight
                query[term]?.let { dot += it * weight }
            }
            val text = if (norm > 0 && queryNorm > 0) dot / (queryNorm * sqrt(norm)) else 0.0
            val local = books[index]?.let(::localness) ?: 1.0
            Scored(
                (candidate.score + TEXT_WEIGHT * text) * local,
                candidate.work,
                candidate.signals + ("text" to text) + ("lang" to local),
                candidate.group,
            )
        }
        return rescored + sorted.drop(depth)
    }

    private fun localness(book: Book) =
        if (isLocalTitle(book.title, catalogue.language)) 1.0 else FOREIGN_PENALTY

    /** How strongly a candidate resembles what the reader rated low, in [0, 1). */
    private fun aversionTo(dislikes: Dislikes, vector: Vector, work: Int): Double {
        if (dislikes.isEmpty) return 0.0
        val tags = if (dislikes.vector.isEmpty()) 0.0 else overlap(vector, dislikes.vector)
        val author = dislikes.authors.isNotEmpty() &&
            catalogue.firstAuthorNumber(work) in dislikes.authors
        return 1 - (1 - DISLIKE_WEIGHT * tags) * (if (author) 1 - DISLIKED_AUTHOR else 1.0)
    }

    private fun rank(
        taste: Taste,
        extra: Collection<Int>,
        group: Int = 0,
        size: Int = CANDIDATES,
    ) = retrieve(taste, extra, size).mapNotNull { score(taste, it, group) }

    private fun firstAvailable(members: List<Int>, library: Set<Int>, hidden: Set<Int>) =
        members.firstOrNull {
            it !in library && it !in hidden && catalogue.locate(it) != null
        }

    /**
     * Greedy diversity re-ranking over a sliding window of the ranking, widened when
     * blocks empty it: one book per series (its first unread volume), no second edition
     * of a title, and each further book by an author or a cluster counts for less.
     */
    private fun diversify(
        scored: List<Scored>,
        bounds: Bounds,
        taste: Taste?,
        limit: Int,
    ): List<Recommendation> {
        val sorted = scored.sortedByDescending { it.score }
        val window = limit * POOL_FACTOR
        var next = window
        val books = HashMap<Int, Book?>()
        fun bookOf(work: Int): Book? = books.getOrPut(work) {
            val members = catalogue.series(work)?.members
            val first = members?.let { firstAvailable(it, bounds.works, emptySet()) }
            catalogue.book(first ?: work)
        }
        fun localOf(candidate: Scored, book: Book) =
            if ("lang" in candidate.signals) 1.0 else localness(book)
        val remaining = sorted.take(window).toMutableList()
        if (bounds.groups) {
            val head = remaining.map { it.work }.toSet()
            remaining += sorted.drop(window)
                .groupBy { it.group }
                .values
                .flatMap { group -> group.take(limit).filter { it.work !in head } }
        }
        val chosen = mutableListOf<Recommendation>()
        val perAuthor = mutableMapOf<Int, Int>()
        val perGroup = mutableMapOf<Int, Int>()
        val series = mutableSetOf<String>()
        val titles = mutableSetOf<String>()
        val works = mutableSetOf<Int>()
        while (chosen.size < limit) {
            if (remaining.isEmpty()) {
                if (next >= sorted.size) break
                remaining += sorted.subList(next, minOf(sorted.size, next + window))
                next += window
            }
            var bestIndex = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for ((index, candidate) in remaining.withIndex()) {
                val book = bookOf(candidate.work) ?: continue
                val author = book.authors.firstOrNull()?.number ?: -1
                val repeats = perAuthor[author] ?: 0
                val value = candidate.score * localOf(candidate, book) *
                    AUTHOR_DECAY.pow(repeats) *
                    if (bounds.groups) CLUSTER_DECAY.pow(perGroup[candidate.group] ?: 0) else 1.0
                if (value > bestScore) {
                    bestScore = value
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break
            val candidate = remaining.removeAt(bestIndex)
            val book = bookOf(candidate.work) ?: continue
            val author = book.authors.firstOrNull()?.number ?: -1
            val numbers = book.authors.map { it.number }
            val name = catalogue.series(book.work)?.name
            val title = titleKey(book).key
            val original = originalKey(book)
            val kin = numbers.flatMap { bounds.kin[it].orEmpty() }.toSet()
            val sameSeries = kin.isNotEmpty() && sequelWords(book, name).any(kin::contains)
            val seedAuthor = taste != null && author in taste.seedAuthors
            val authorCount = perAuthor[author] ?: 0
            val blocked = book.isCompanion || book.work in bounds.works ||
                book.work in works || numbers.any { it in bounds.authors } || sameSeries ||
                (name != null && (name in series || name in bounds.series)) ||
                title in bounds.titles || title in titles ||
                (original != null && (original in bounds.titles || original in titles)) ||
                authorCount >= MAX_PER_AUTHOR ||
                (seedAuthor && authorCount >= bounds.seedAuthorLimit)
            if (blocked) continue
            works += book.work
            titles += title
            original?.let { titles += it }
            if (name != null) series += name
            perAuthor[author] = authorCount + 1
            perGroup[candidate.group] = (perGroup[candidate.group] ?: 0) + 1
            chosen += Recommendation(
                book,
                bestScore,
                mapOf("lang" to localness(book)) + candidate.signals,
            )
        }
        return chosen
    }

    private fun sequelWords(book: Book, series: String?) =
        kinWords(book.title) + series?.let(::kinWords).orEmpty()

    /** Per author, the words that mark a sequel or spin-off of these books. */
    private fun kinOf(books: List<Book>): Map<Int, Set<String>> = books
        .flatMap { book ->
            val words = kinWords(mainTitle(book.title)) +
                catalogue.series(book.work)?.name?.let(::kinWords).orEmpty()
            book.authors.map { it.number to words }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, words) -> words.flatten().toSet() }

    private fun seriesNames(works: Iterable<Int>) =
        works.mapNotNull { catalogue.series(it)?.name }.toSet()

    private fun titlesOf(books: Iterable<Book>) =
        books.flatMap { listOfNotNull(titleKey(it).key, originalKey(it)) }.toSet()

    /** The original-language title with its author: separate works that are one book. */
    private fun originalKey(book: Book) = book.alternate.takeIf { it.isNotEmpty() }?.let {
        "${tokenize(mainTitle(it)).joinToString(" ")}|${book.authors.firstOrNull()?.number}"
    }

    private fun similarToBooks(
        seeds: List<Book>,
        bounds: Bounds,
        limit: Int,
        seedAuthors: Set<Int>,
    ): List<Recommendation> {
        val sources = seeds.mapNotNull { sourceOf(it, 1.0) }
        val taste = tasteOf(enriched(sources), seedAuthors = seedAuthors)
            ?: return popularRecommendations(limit, bounds)
        val extra = seedAuthors
            .filter { it !in bounds.authors }
            .flatMap { number ->
                seeds.flatMap { it.authors }.filter { it.number == number }.distinct()
            }
            .flatMap { catalogue.authorWorks(it).take(AUTHOR_DEPTH) }
        return diversify(refine(taste, rank(taste, extra)), bounds, taste, limit)
    }

    private fun popularRecommendations(limit: Int, bounds: Bounds) =
        diversify(
            catalogue.popularWorks(limit * POOL_FACTOR).mapIndexed { index, work ->
                Scored(1.0 / (1 + index), work)
            },
            bounds,
            null,
            limit,
        )

    fun recommend(seed: RecommendSeed, limit: Int = 12): List<Recommendation> = when (seed) {
        is RecommendSeed.Work -> {
            val book = seed.book
            val series = seriesNames(listOf(book.work))
            similarToBooks(
                listOf(book),
                Bounds(
                    setOf(book.work),
                    titlesOf(listOf(book)),
                    series,
                    kin = kinOf(listOf(book)),
                ),
                limit,
                book.authors.take(1).map { it.number }.toSet(),
            )
        }
        is RecommendSeed.Saga -> {
            val members = catalogue.series(seed.book.work)?.members.orEmpty()
            val books = catalogue.books(members.ifEmpty { listOf(seed.book.work) })
            similarToBooks(
                books,
                seriesNames(listOf(seed.book.work)).let { series ->
                    Bounds(
                        books.map { it.work }.toSet(),
                        titlesOf(books),
                        series,
                        kin = kinOf(books),
                    )
                },
                limit,
                books.flatMap { it.authors }.map { it.number }.toSet(),
            )
        }
        is RecommendSeed.Writer -> {
            val works = worksOf(seed.author)
                .sortedByDescending(catalogue::score)
                .take(SEED_AUTHOR_WORKS)
            val top = works.firstOrNull()?.let(catalogue::score)?.takeIf { it > 0 } ?: 1.0
            val sources = catalogue.books(works).mapNotNull { book ->
                sourceOf(book, max(MIN_SEED_WEIGHT, catalogue.score(book.work) / top))
            }
            val bounds = Bounds(authors = setOf(seed.author.number), groups = true)
            val clusters = cluster(enriched(sources))
            if (clusters.isEmpty()) {
                popularRecommendations(limit, bounds)
            } else {
                mixed(clusters, emptyMap(), bounds, limit)
            }
        }
        is RecommendSeed.Library -> personal(seed.entries, seed.hidden, limit)
    }

    /**
     * Library seed: each taste cluster ranks its own candidates; the merged list is
     * weighted by cluster weight (recency, rating, repetition) and re-ranked for
     * diversity across clusters and authors.
     */
    private fun personal(
        entries: List<Saved>,
        hidden: Set<Int>,
        limit: Int,
    ): List<Recommendation> {
        val profile = buildProfile(entries, hidden)
        val owned = entries.map { it.work }.toSet()
        val bounds = Bounds(
            works = owned + hidden,
            titles = profile.seen.map { it.key }.toSet() +
                titlesOf(profile.clusters.flatMap { it.books }),
            series = profile.librarySeries,
            kin = kinOf(profile.clusters.flatMap { it.books }),
            seedAuthorLimit = MAX_PER_AUTHOR,
            groups = true,
        )
        if (profile.clusters.isEmpty()) return popularRecommendations(limit, bounds)
        return mixed(profile.clusters, profile.authors, bounds, limit, profile.dislikes)
    }

    /**
     * Ranks each taste cluster on its own and merges the lists, scaled by cluster weight,
     * so a library or an author with two sides gets both, the heavier one first.
     */
    private fun mixed(
        clusters: List<Cluster>,
        authors: Map<Int, Double>,
        bounds: Bounds,
        limit: Int,
        dislikes: Dislikes = Dislikes(),
    ): List<Recommendation> {
        val heaviest = clusters.first().weight
        val scored = clusters.take(MAX_CLUSTERS).withIndex().flatMap { (index, cluster) ->
            val taste = tasteOf(cluster, authors, dislikes) ?: return@flatMap emptyList()
            val share = 0.5 + 0.5 * cluster.weight / heaviest
            val extra = authors.keys.flatMap { number ->
                cluster.books.flatMap { it.authors }.filter { it.number == number }
            }.distinct().flatMap { catalogue.authorWorks(it).take(AUTHOR_DEPTH) }
            val size = max(MIN_CANDIDATES, (CANDIDATES * cluster.weight / heaviest).toInt())
            val depth = max(MIN_TEXT_DEPTH, (TEXT_DEPTH * cluster.weight / heaviest).toInt())
            refine(taste, rank(taste, extra, index, size), depth).map {
                Scored(it.score * share, it.work, it.signals + ("share" to share), index)
            }
        }
        val best = scored.groupBy { it.work }.map { (_, all) -> all.maxBy { it.score } }
        return diversify(best, bounds, null, limit)
    }

    private inner class Picker(
        private val library: Set<Int> = emptySet(),
        private val hidden: Set<Int> = emptySet(),
        private val blockedSeries: Set<String> = emptySet(),
        private val titles: MutableSet<TitleKey> = mutableSetOf(),
        private val authorLimit: Int = MAX_PER_AUTHOR,
        private val blockedTitles: Set<String> = emptySet(),
        private val kin: Map<Int, Set<String>> = emptyMap(),
    ) {
        private val series = mutableSetOf<String>()
        private val perAuthor = mutableMapOf<String, Int>()

        private fun firstUnread(work: Int): Int {
            val members = catalogue.series(work)?.members ?: return work
            return firstAvailable(members, library, hidden) ?: work
        }

        fun accept(work: Int): Book? {
            val book = catalogue.book(firstUnread(work)) ?: return null
            if (book.isCompanion || book.work in library || book.work in hidden) {
                return null
            }
            val name = catalogue.series(book.work)?.name
            if (name != null && (name in series || name in blockedSeries)) return null
            val key = titleKey(book)
            if (key.key in blockedTitles) return null
            val words = book.authors.flatMap { kin[it.number].orEmpty() }.toSet()
            if (words.isNotEmpty() && sequelWords(book, name).any(words::contains)) return null
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
    ): HomeRow? {
        val books = profile.library
            .mapNotNull { work -> nextVolume(work, profile) }
            .distinct()
            .let(catalogue::books)
            .filter { taken.add(titleKey(it)) }
            .take(size)
        return if (books.size < MIN_SERIES_ROW) null else HomeRow(RowKind.Series, books)
    }

    private fun nextVolume(work: Int, profile: Profile): Int? {
        val members = catalogue.series(work)?.members ?: return null
        if (work !in members) return null
        return firstAvailable(members, profile.library, profile.hidden)
    }

    private fun picker(
        profile: Profile,
        taken: MutableSet<TitleKey>,
        authorLimit: Int = MAX_PER_AUTHOR,
        kin: Map<Int, Set<String>> = emptyMap(),
    ) = Picker(
        profile.library,
        profile.hidden,
        profile.librarySeries,
        taken,
        authorLimit,
        profile.seen.map { it.key }.toSet(),
        kin,
    )

    private fun authorRows(
        profile: Profile,
        taken: MutableSet<TitleKey>,
        size: Int,
    ): List<HomeRow> {
        val authors = profile.clusters
            .flatMap { cluster -> cluster.sources.map { it.book to cluster.weight } }
            .mapNotNull { (book, weight) -> book.authors.firstOrNull()?.to(weight) }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
        val rows = mutableListOf<HomeRow>()
        for (author in authors) {
            if (rows.size >= MAX_AUTHOR_ROWS) break
            val picker = picker(profile, taken, authorLimit = size)
            val books = worksOf(author)
                .sortedByDescending(catalogue::score)
                .take(size * 4)
                .mapNotNull(picker::accept)
                .take(size)
            if (books.size < MIN_ROW) continue
            rows += HomeRow(RowKind.Author, books, author = author)
        }
        return rows
    }

    fun rows(
        entries: List<Saved>,
        seed: Long = 0,
        hidden: Set<Int> = emptySet(),
        size: Int = 12,
    ): List<HomeRow> {
        val profile = buildProfile(entries, hidden)
        if (profile.clusters.isEmpty()) {
            return listOf(HomeRow(RowKind.Popular, popular(size)))
        }
        val random = Random(seed)
        val taken = profile.seen.toMutableSet()
        val rows = mutableListOf<HomeRow>()
        seriesRow(profile, taken, size)?.let { rows += it }
        val kin = kinOf(profile.clusters.flatMap { it.books })
        val heaviest = profile.clusters.first().weight
        for (cluster in profile.clusters) {
            val taste = tasteOf(cluster, profile.authors, profile.dislikes) ?: continue
            val picker = picker(profile, taken, kin = kin)
            val share = cluster.weight / heaviest
            val depth = max(MIN_TEXT_DEPTH, (size * ROW_POOL * share).toInt())
            val pool = max(MIN_CANDIDATES, (CANDIDATES * share).toInt())
            val books = refine(taste, rank(taste, emptyList(), size = pool), depth)
                .map { Scored(it.score * random.nextDouble(1 - JITTER, 1 + JITTER), it.work) }
                .sortedByDescending { it.score }
                .take(size * ROW_POOL)
                .mapNotNull { picker.accept(it.work) }
            if (books.size >= MIN_ROW) {
                rows += HomeRow(RowKind.Because, books.take(size), cluster.readBooks)
            }
        }
        rows += authorRows(profile, taken, size)
        return rows
    }

    /**
     * Evaluation hook: the raw ranking for a work, blind to its author (no enrichment, no
     * author candidates) and without diversity rules, best first.
     */
    internal fun blindRanking(book: Book, depth: Int): List<Int> {
        val source = sourceOf(book, 1.0) ?: return emptyList()
        val taste = tasteOf(listOf(source)) ?: return emptyList()
        return refine(taste, rank(taste, emptyList()).filter { it.work != book.work })
            .sortedByDescending { it.score }
            .take(depth)
            .map { it.work }
    }

    fun similar(book: Book, limit: Int = 12): List<Book> =
        recommend(RecommendSeed.Work(book), limit).map { it.book }

    fun series(book: Book): Pair<String, List<Book>>? {
        val series = catalogue.series(book.work) ?: return null
        return series.name to catalogue.books(series.members)
    }

    fun byAuthor(book: Book, limit: Int = 12): List<Book> {
        val author = book.authors.firstOrNull() ?: return emptyList()
        val sameSeries = catalogue.series(book.work)?.members.orEmpty().toSet()
        val works = worksOf(author).filter { it != book.work && it !in sameSeries }
            .sortedByDescending(catalogue::score)
        return distinct(
            catalogue.books(works.take(limit * 3)), limit, setOf(titleKey(book)),
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
        val groups = series
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
    ) = books
        .filter { titleKey(it) !in seen && !it.isCompanion }
        .distinctBy(::titleKey)
        .take(limit)
}
