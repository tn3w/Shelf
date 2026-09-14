package dev.tn3w.shelf.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private const val AUTHOR_LIMIT = 150
private const val RERANK_DEPTH = 250
private const val PREFIX_EXPANSIONS = 12
private const val FUZZY_CANDIDATES = 60
private const val AUTHOR_FIELD = 0.85
private const val EXACT = 1.0
private const val PREFIX = 0.5
private const val PHRASE = 0.4
private const val LENGTH = 0.04
private const val COVERAGE = 0.4
private const val COMBO = 0.5
private const val AUTHOR = 1.0
private const val POPULARITY = 0.6
private const val ALTERNATE = 0.6
private val ARTICLE = Regex("^(the|a|an|der|die|das|le|la|les|el|los|las) ")
private val TITLE_BREAK = Regex("[:;(/]")

private class TermMatch(val text: String, val weight: Double)

private class TokenScores(
    val token: String,
    val scores: Map<Int, Double>,
    val terms: Map<Int, String>,
    val idf: Double,
)

private fun withoutArticle(text: String) = text.replaceFirst(ARTICLE, "")

fun mainTitle(title: String) = title.split(TITLE_BREAK).first()

fun editDistance(left: String, right: String, limit: Int): Int {
    if (abs(left.length - right.length) > limit) return limit + 1
    var beforePrevious: IntArray? = null
    var previous = IntArray(right.length + 1) { it }
    for (i in 1..left.length) {
        val current = IntArray(right.length + 1).also { it[0] = i }
        for (j in 1..right.length) {
            val cost = if (left[i - 1] == right[j - 1]) 0 else 1
            current[j] =
                minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            val swapped =
                i > 1 &&
                    j > 1 &&
                    left[i - 1] == right[j - 2] &&
                    left[i - 2] == right[j - 1]
            if (swapped && beforePrevious != null) {
                current[j] = min(current[j], beforePrevious[j - 2] + 1)
            }
        }
        if (current.min() > limit) return limit + 1
        beforePrevious = previous
        previous = current
    }
    return previous.last()
}

private fun allowedTypos(token: String) =
    when {
        token.length <= 3 -> 0
        token.length <= 6 -> 1
        else -> 2
    }

private fun transpositions(token: String) =
    (0 until token.length - 1).map {
        token.substring(0, it) + token[it + 1] + token[it] + token.substring(it + 2)
    }

private fun trigrams(term: String) = "$$term$".windowed(3)

class Searcher(private val catalogue: Catalogue) {
    private val segments = catalogue.segments
    private val total = catalogue.ranks?.works ?: catalogue.workCount

    private fun fuzzyTerms(token: String): List<Pair<String, Int>> {
        val limit = allowedTypos(token)
        if (limit == 0) return emptyList()
        val needed = max(1, trigrams(token).size - 3 * limit)
        val variants = listOf(token) + transpositions(token)
        val texts = segments.flatMap { segment ->
            val hits = mutableMapOf<Int, Int>()
            for (variant in variants) {
                val variantHits = mutableMapOf<Int, Int>()
                trigrams(variant).toSet().forEach { gram ->
                    segment.gramTerms(gram).forEach {
                        variantHits.merge(it, 1, Int::plus)
                    }
                }
                variantHits.forEach { (term, count) -> hits.merge(term, count, ::max) }
            }
            hits
                .filterValues { it >= needed }
                .entries
                .sortedByDescending { it.value }
                .take(FUZZY_CANDIDATES)
                .map { segment.termById(it.key).text }
        }
        return texts.distinct().mapNotNull { text ->
            val distance = editDistance(token, text, limit)
            if (text == token || distance > limit) null else text to distance
        }
    }

    private fun frequency(text: String) =
        catalogue.ranks?.frequency(text)
            ?: segments.sumOf { it.term(text)?.frequency ?: 0 }

    private fun completions(prefix: String) =
        segments
            .flatMap { segment ->
                segment.completions(prefix, PREFIX_EXPANSIONS).map { it.text }
            }
            .distinct()
            .sortedByDescending(::frequency)
            .take(PREFIX_EXPANSIONS)

    private fun termMatches(token: String, isLast: Boolean): List<TermMatch> {
        val matches = mutableListOf<TermMatch>()
        val exactFrequency = segments.sumOf { it.term(token)?.frequency ?: 0 }
        if (exactFrequency > 0) matches += TermMatch(token, 1.0)
        var completionFrequency = 0
        if (isLast && token.length >= 2) {
            val weight = if (token.length >= 4) 0.9 else 0.75
            completions(token).forEach {
                completionFrequency += frequency(it)
                matches += TermMatch(it, weight)
            }
        }
        val sparse =
            exactFrequency < 50 && (completionFrequency < 20 || exactFrequency == 0)
        if (!sparse) return matches
        fuzzyTerms(token).forEach { (text, distance) ->
            matches += TermMatch(text, if (distance == 1) 0.72 else 0.5)
        }
        return matches
    }

    private fun tokenScores(token: String, matches: List<TermMatch>): TokenScores {
        val scores = HashMap<Int, Double>()
        val matched = HashMap<Int, String>()
        fun offer(work: Int, value: Double, text: String) {
            if (value <= (scores[work] ?: 0.0)) return
            scores[work] = value
            matched[work] = text
        }
        var authors = 0
        for (match in matches) {
            for (segment in segments) {
                val term = segment.term(match.text) ?: continue
                catalogue.visibleWorks(segment, term.titleWorks).forEach {
                    offer(it, match.weight, match.text)
                }
                for (slot in term.authorSlots) {
                    if (authors++ >= AUTHOR_LIMIT) break
                    catalogue.visibleWorks(segment, segment.author(slot).works).forEach {
                        offer(it, match.weight * AUTHOR_FIELD, match.text)
                    }
                }
            }
        }
        val documents = max(scores.size, matches.sumOf { frequency(it.text) })
        val idf = ln((total + 1.0) / (min(documents, total) + 1)) + 1.0
        return TokenScores(token, scores, matched, idf)
    }

    private fun candidates(perToken: List<TokenScores>): Map<Int, Double> {
        val totalIdf = perToken.sumOf { it.idf }
        val works = perToken.flatMapTo(HashSet()) { it.scores.keys }
        return works.associateWith { work ->
            var matched = 0
            var text = 0.0
            for (entry in perToken) {
                val value = entry.scores[work] ?: continue
                matched++
                text += value * entry.idf
            }
            val coverage = matched.toDouble() / perToken.size
            text / totalIdf * coverage * coverage
        }
    }

    private fun bonus(book: Book, perToken: List<TokenScores>): Double {
        val query = perToken.map { it.terms[book.work] ?: it.token }
        val confidence = perToken.sumOf { it.scores[book.work] ?: 0.0 } / perToken.size
        val lastToken = perToken.last().token
        val completing =
            query.last() != lastToken &&
                (query.last().startsWith(lastToken) || lastToken.startsWith(query.last()))
        val joinedQuery = query.joinToString(" ")
        val bestTitle =
            listOf(book.title to 1.0, book.alternate to ALTERNATE)
                .filter { it.first.isNotEmpty() }
                .maxOfOrNull { (text, discount) ->
                    titleValue(text, book.subtitle, query, joinedQuery, completing) *
                        discount
                }
                ?.coerceAtLeast(0.0) ?: 0.0
        val words = tokenize("${book.title} ${book.subtitle} ${book.alternate}").toSet()
        val authorTokens = book.authors.flatMap { tokenize(it.name) }.toSet()
        val titleHits = query.count { it in words }
        val authorHits = query.count { it in authorTokens }
        var bonus = bestTitle * confidence
        bonus += COVERAGE * min(1.0, (titleHits + authorHits).toDouble() / query.size)
        if (titleHits in 1 until query.size && query.size <= titleHits + authorHits) {
            bonus += COMBO
        }
        if (authorHits == query.size && titleHits < query.size)
            bonus += AUTHOR * confidence
        return bonus
    }

    private fun titleValue(
        title: String,
        subtitle: String,
        query: List<String>,
        joinedQuery: String,
        completing: Boolean,
    ): Double {
        val joined = tokenize(title).joinToString(" ")
        val full = tokenize("$title $subtitle").joinToString(" ")
        val main = tokenize(mainTitle(title))
        val titles = setOf(withoutArticle(joined), withoutArticle(main.joinToString(" ")))
        val value =
            when {
                withoutArticle(joinedQuery) in titles && !completing -> EXACT
                joined.startsWith(joinedQuery) -> PREFIX
                " $joinedQuery " in " $full " -> PHRASE
                else -> 0.0
            }
        return value - min(LENGTH * max(0, main.size - query.size), 0.3)
    }

    fun search(query: String, limit: Int = 30): List<Book> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val perToken = tokens.mapIndexed { position, token ->
            tokenScores(token, termMatches(token, position == tokens.lastIndex))
        }
        val scored = candidates(perToken)
        val popularity = HashMap<Int, Double>()
        fun popularityOf(work: Int) =
            popularity.getOrPut(work) { catalogue.popularity(work) }
        val head =
            scored.keys
                .sortedByDescending { scored.getValue(it) + 0.35 * popularityOf(it) }
                .take(RERANK_DEPTH)
        return head
            .mapNotNull(catalogue::book)
            .map { book ->
                val base = scored.getValue(book.work) + 0.35 * popularityOf(book.work)
                book to
                    base + bonus(book, perToken) + POPULARITY * popularityOf(book.work)
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun complete(query: String, limit: Int = 3): List<String> {
        val tokens = tokenize(query)
        val last = tokens.lastOrNull()?.takeIf { it.length >= 2 } ?: return emptyList()
        val head = tokens.dropLast(1).joinToString(" ")
        return completions(last)
            .ifEmpty { fuzzyTerms(last).sortedBy { it.second }.map { it.first } }
            .take(limit)
            .map { if (head.isEmpty()) it else "$head $it" }
    }

    fun authors(query: String, limit: Int = 3): List<Author> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val perToken = tokens.mapIndexed { position, token ->
            val texts = termMatches(token, position == tokens.lastIndex).map { it.text }
            segments
                .flatMap { segment ->
                    texts.flatMap { text ->
                        val slots = segment.term(text)?.authorSlots ?: IntArray(0)
                        slots.take(AUTHOR_LIMIT).map(segment::author)
                    }
                }
                .associate { it.number to Author(it.number, it.name) }
        }
        val common =
            perToken.map { it.keys }.reduce { left, right -> left intersect right }
        return common
            .mapNotNull { perToken.first()[it] }
            .sortedBy { author -> tokenize(author.name).size }
            .take(limit)
    }
}
