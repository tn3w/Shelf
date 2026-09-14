package dev.tn3w.shelf.data

import kotlin.math.ln
import kotlin.math.sqrt

private val SHELF_WEIGHT =
    mapOf(Shelf.Read to 0.8, Shelf.Reading to 0.9, Shelf.Want to 0.5)
private val KIDS_TAGS = setOf("childrens", "picture-book", "middle-grade")
private val FORM_TAGS = setOf("fiction", "nonfiction")
private val ARTICLES = setOf("the", "a", "an", "der", "die", "das", "le", "la", "el")
private const val POSTINGS_PER_TAG = 4000
private const val PROFILE_TAGS = 8
private const val MAX_PER_AUTHOR = 2

private typealias Vector = Map<Int, Double>

private data class TitleKey(val key: String, val author: String)

private class Profile(
    val tags: Map<Int, Double>,
    val kidsShare: Double,
    val fictionShare: Double,
    val seen: Set<TitleKey>,
    val library: Set<Int>,
    val liked: List<Pair<Book, Vector>>,
)

private class Scored(val score: Double, val work: Int, val vector: Vector)

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
        tags.withIndex().associate { (index, tag) ->
            val confidence = 1.0 / (1.0 + 0.15 * index)
            val weight = if (slug[tag] in FORM_TAGS) 0.25 else 1.0
            tag to (idf[tag] ?: 0.0) * confidence * weight
        }

    private fun buildProfile(entries: List<Saved>): Profile {
        val tags = mutableMapOf<Int, Double>()
        var kids = 0.0
        var fiction = 0.0
        var weights = 0.0
        val liked = mutableListOf<Pair<Book, Vector>>()
        for (entry in entries) {
            val book = catalogue.book(entry.work) ?: continue
            val weight = SHELF_WEIGHT.getValue(entry.shelf)
            val vector = tagVector(book.tags)
            vector.forEach { (tag, value) ->
                tags.merge(tag, weight * value, Double::plus)
            }
            val slugs = book.tags.mapNotNull(slug::get).toSet()
            weights += weight
            if (slugs.any { it in KIDS_TAGS }) kids += weight
            if ("fiction" in slugs) fiction += weight
            liked += book to vector
        }
        val norm = tags.norm()
        return Profile(
            tags = tags.mapValues { it.value / norm },
            kidsShare = if (weights > 0) kids / weights else 0.0,
            fictionShare = if (weights > 0) fiction / weights else 0.5,
            seen = liked.map { titleKey(it.first) }.toSet(),
            library = entries.map { it.work }.toSet(),
            liked = liked,
        )
    }

    private fun retrieve(profile: Profile): Set<Int> {
        fun strength(tag: Int) =
            profile.tags.getValue(tag) * if (slug[tag] in FORM_TAGS) 0.2 else 1.0
        val candidates = mutableSetOf<Int>()
        profile.tags.keys
            .sortedByDescending(::strength)
            .take(PROFILE_TAGS)
            .forEachIndexed { rank, tag ->
                val depth = POSTINGS_PER_TAG / (1 + rank / 3)
                candidates +=
                    catalogue
                        .tagWorks(tag)
                        .sortedByDescending(catalogue::score)
                        .take(depth)
            }
        profile.liked
            .flatMap { it.first.authors }
            .distinct()
            .forEach {
                candidates += catalogue.authorWorks(it).take(60)
            }
        return candidates - profile.library
    }

    private fun audienceFit(profile: Profile, slugs: Set<String>): Double {
        val isKids = slugs.any { it in KIDS_TAGS }
        if (isKids && profile.kidsShare < 0.2) return 0.15
        if (!isKids && profile.kidsShare > 0.7)
            return if ("young-adult" in slugs) 0.7 else 0.35
        return 1.0
    }

    private fun formFit(profile: Profile, slugs: Set<String>): Double {
        if ("fiction" in slugs && profile.fictionShare < 0.15) return 0.5
        if ("fiction" !in slugs && profile.fictionShare > 0.85) return 0.6
        return 1.0
    }

    private fun quality(work: Int): Double {
        val popularity = catalogue.ranks?.popularity(work)
        val ratings = popularity?.ratings ?: 0
        val mean = (8 * 3.8 + (popularity?.rating ?: 0.0) * ratings) / (8 + ratings)
        return 0.55 * catalogue.popularity(work) + 0.45 * (mean - 3.0) / 2.0
    }

    private fun score(profile: Profile, work: Int): Scored? {
        val facts = catalogue.facts(work) ?: return null
        val tags = facts.tags.toList()
        val vector = tagVector(tags)
        if (vector.isEmpty()) return null
        val similarity =
            vector.entries.sumOf { (profile.tags[it.key] ?: 0.0) * it.value } /
                vector.norm()
        if (similarity <= 0.05) return null
        val slugs = tags.mapNotNull(slug::get).toSet()
        val fit = audienceFit(profile, slugs) * formFit(profile, slugs)
        return Scored((0.62 * similarity + 0.30 * quality(work)) * fit, work, vector)
    }

    private fun diversify(
        profile: Profile,
        scored: List<Scored>,
        limit: Int,
    ): List<Book> {
        val chosen = mutableListOf<Book>()
        val perAuthor = mutableMapOf<String, Int>()
        val vectors = mutableListOf<Vector>()
        val pool = scored.take(limit * 12).toMutableList()
        val titles = profile.seen.toMutableSet()
        while (pool.isNotEmpty() && chosen.size < limit) {
            val best = pool.maxBy { candidate ->
                val redundancy =
                    vectors.maxOfOrNull { overlap(candidate.vector, it) } ?: 0.0
                candidate.score - 0.18 * redundancy
            }
            pool.remove(best)
            val book = catalogue.book(seriesStart(best.work, profile.library)) ?: continue
            val count = perAuthor[book.author] ?: 0
            if (count >= MAX_PER_AUTHOR || !titles.add(titleKey(book))) continue
            perAuthor[book.author] = count + 1
            vectors += best.vector
            chosen += book
        }
        return chosen
    }

    private fun seriesStart(work: Int, library: Set<Int>): Int {
        val members = catalogue.series(work)?.members ?: return work
        if (members.any { it in library }) return work
        return members.firstOrNull { catalogue.locate(it) != null } ?: work
    }

    fun recommend(entries: List<Saved>, limit: Int = 20): List<Book> {
        val profile = buildProfile(entries)
        if (profile.liked.isEmpty()) return popular(limit)
        val scored =
            retrieve(profile)
                .mapNotNull { score(profile, it) }
                .sortedByDescending { it.score }
        return diversify(profile, scored, limit)
    }

    fun similar(book: Book, limit: Int = 12) =
        recommend(listOf(book.toSaved(Shelf.Read)), limit)

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

    fun popular(limit: Int = 20, tag: Int? = null): List<Book> =
        distinct(catalogue.books(catalogue.popularWorks(limit * 3, tag)), limit)

    private fun distinct(
        books: List<Book>,
        limit: Int,
        seen: Set<TitleKey> = emptySet(),
    ) = books.filter { titleKey(it) !in seen }.distinctBy(::titleKey).take(limit)
}
