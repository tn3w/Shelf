package dev.tn3w.shelf.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Evaluation harness for [Recommender]. Runs against a real catalogue release on the JVM.
 *
 * SHELF_CATALOGUE=/path/to/release ./gradlew :app:testGithubDebugUnitTest \
 *     --tests '*RecommendEval*'
 *
 * The directory holds the release's `<lang>-<pack>-<month>.bin` files. Seeds are resolved
 * by title/author search at run time, so the harness carries no catalogue ids or months.
 * The report goes to SHELF_EVAL_OUT (default build/reports/recommend-eval.txt).
 */
private val RELEASE = System.getenv("SHELF_CATALOGUE")?.let(::File)
private val OUTPUT = File(System.getenv("SHELF_EVAL_OUT") ?: "build/reports/recommend-eval.txt")
private const val TOP = 10
private const val RUNS = 5

sealed interface EvalSeed {
    val language: String
    val label: String

    data class Work(override val language: String, val query: String) : EvalSeed {
        override val label = "work: $query"
    }

    data class Writer(override val language: String, val name: String) : EvalSeed {
        override val label = "author: $name"
    }

    data class Saga(override val language: String, val query: String) : EvalSeed {
        override val label = "series: $query"
    }

    /**
     * Library seed; queries are oldest first, a repeated query is a re-read. [stars] are
     * star ratings by position, 0 for unrated.
     */
    data class Shelfful(
        override val language: String,
        val name: String,
        val queries: List<String>,
        val stars: List<Int> = emptyList(),
    ) : EvalSeed {
        override val label = "library: $name"
    }

    /** Sparse seed: a resolved work that carries at most [maxTags] tags. */
    data class Sparse(override val language: String, val query: String, val maxTags: Int) :
        EvalSeed {
        override val label = "sparse: $query"
    }
}

val EVAL_SEEDS = listOf(
    EvalSeed.Work("en", "The Hobbit Tolkien"),
    EvalSeed.Work("en", "Gone Girl Gillian Flynn"),
    EvalSeed.Work("en", "Sapiens Harari"),
    EvalSeed.Writer("en", "Agatha Christie"),
    EvalSeed.Saga("en", "Harry Potter Philosopher's Stone Rowling"),
    EvalSeed.Shelfful(
        "en",
        "sci-fi reader turning to crime",
        listOf(
            "Dune Frank Herbert", "Foundation Asimov", "Neuromancer Gibson",
            "The Girl with the Dragon Tattoo Larsson", "Big Little Lies Moriarty",
            "The Silent Patient Michaelides", "The Silent Patient Michaelides",
        ),
    ),
    EvalSeed.Work("de", "Der Vorleser Schlink"),
    EvalSeed.Writer("de", "Sebastian Fitzek"),
    EvalSeed.Shelfful(
        "de",
        "Krimi und Klassiker",
        listOf(
            "Die Verwandlung Kafka", "Der Steppenwolf Hesse", "Das Parfum Süskind",
            "Der Schwarm Schätzing", "Die Therapie Fitzek",
        ),
    ),
    EvalSeed.Work("fr", "L'Étranger Camus"),
    EvalSeed.Saga("fr", "Astérix Goscinny"),
    EvalSeed.Work("es", "Cien años de soledad García Márquez"),
    EvalSeed.Writer("es", "Carlos Ruiz Zafón"),
    EvalSeed.Work("sv", "Män som hatar kvinnor Larsson"),
    EvalSeed.Saga("sv", "Harry Potter och de vises sten"),
    EvalSeed.Work("fi", "Tuntematon sotilas Linna"),
    EvalSeed.Saga("pl", "Diuna Herbert"),
    EvalSeed.Work("ko", "채식주의자 Han Kang"),
    EvalSeed.Work("cs", "Osudy dobrého vojáka Švejka Hašek"),
    EvalSeed.Work("et", "Tõde ja õigus Tammsaare"),
    EvalSeed.Shelfful(
        "fr",
        "jeunesse",
        listOf("Le Petit Prince Saint-Exupéry", "Astérix le Gaulois", "Tintin au Tibet"),
    ),
    EvalSeed.Sparse("en", "poetry", maxTags = 2),
    EvalSeed.Shelfful(
        "en",
        "fantasy fan who disliked two romances",
        listOf(
            "The Name of the Wind Rothfuss", "Mistborn Sanderson", "A Wizard of Earthsea",
            "Fifty Shades of Grey James", "The Notebook Sparks",
        ),
        stars = listOf(5, 5, 4, 1, 2),
    ),
)

class Resolved(val seed: EvalSeed, val books: List<Book>, val author: Author?)

class Line(val book: Book, val score: Double, val signals: Map<String, Double>)

fun interface Engine {
    fun run(recommender: Recommender, seed: Resolved, limit: Int): List<Line>
}

private fun library(books: List<Book>, stars: List<Int> = emptyList()) =
    books.mapIndexed { index, book ->
        val at = 1_000L * (index + 1)
        Saved(book.work, Shelf.Read, at, rating = stars.getOrElse(index) { 0 }, added = at)
    }

/** The shipped engine: [Recommender.recommend]. */
val CURRENT = Engine { recommender, resolved, limit ->
    val seed = when (val seed = resolved.seed) {
        is EvalSeed.Work, is EvalSeed.Sparse -> RecommendSeed.Work(resolved.books.first())
        is EvalSeed.Writer -> RecommendSeed.Writer(resolved.author!!)
        is EvalSeed.Saga -> RecommendSeed.Saga(resolved.books.first())
        is EvalSeed.Shelfful -> RecommendSeed.Library(library(resolved.books, seed.stars))
    }
    recommender.recommend(seed, limit).map { Line(it.book, it.score, it.signals) }
}

/** The Home screen for a library seed: each row, and hygiene across all rows. */
private fun homeRows(
    recommender: Recommender,
    entries: List<Saved>,
    language: String,
    out: Appendable,
) {
    recommender.rows(entries, seed = 7)
    val start = System.nanoTime()
    val rows = recommender.rows(entries, seed = 7)
    val millis = (System.nanoTime() - start) / 1e6
    rows.forEach { row ->
        val because = row.sources.joinToString(", ") { it.title }.take(60)
        val label = row.author?.name ?: because
        out.appendLine("   row ${row.kind} [$label]")
        out.appendLine("      " + row.books.take(6).joinToString("; ") { it.title.take(30) })
    }
    val all = rows.flatMap { it.books }
    out.appendLine(
        "   rows=%d books=%d crossDups=%d owned=%d foreign=%d authorMax=%d time=%.1fms".format(
            rows.size, all.size, all.size - all.distinctBy(::titleOf).size,
            all.count { book -> entries.any { it.work == book.work } },
            all.count { looksForeign(it.title, language) },
            all.groupingBy { it.author }.eachCount().values.maxOrNull() ?: 0, millis,
        ),
    )
}

private fun mapped(file: File) = RandomAccessFile(file, "r").use {
    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
}

fun loadRelease(folder: File, language: String): Catalogue {
    val files = folder.listFiles().orEmpty()
        .filter { it.name.startsWith("$language-") && it.extension == "bin" }
    val (ranks, segments) = files.partition { "-ranks-" in it.name }
    return Catalogue(
        language,
        currentSegments(segments.map { Segment(mapped(it)) }),
        ranks.maxWithOrNull(compareBy(releaseOrder) { it.nameWithoutExtension })
            ?.let { Ranks(mapped(it)) },
    )
}

fun resolve(catalogue: Catalogue, searcher: Searcher, seed: EvalSeed): Resolved? {
    fun first(query: String) = searcher.search(query, 5).firstOrNull { !it.isCompanion }
    return when (seed) {
        is EvalSeed.Work -> first(seed.query)?.let { Resolved(seed, listOf(it), null) }
        is EvalSeed.Saga -> searcher.search(seed.query, 10)
            .firstOrNull { !it.isCompanion && catalogue.series(it.work) != null }
            ?.let { Resolved(seed, listOf(it), null) }
        is EvalSeed.Writer -> searcher.authors(searcher.lookup(seed.name), 1).firstOrNull()
            ?.let { author ->
                val top = catalogue.authorWorks(author).maxByOrNull(catalogue::score)
                Resolved(seed, listOfNotNull(top?.let(catalogue::book)), author)
            }
        is EvalSeed.Shelfful -> seed.queries.mapNotNull(::first)
            .takeIf { it.isNotEmpty() }
            ?.let { Resolved(seed, it, null) }
        is EvalSeed.Sparse -> searcher.search(seed.query, 200)
            .filter { !it.isCompanion && it.tags.size in 1..seed.maxTags }
            .maxByOrNull { catalogue.score(it.work) }
            ?.let { Resolved(seed, listOf(it), null) }
    }
}

private val STOPWORDS = mapOf(
    "en" to setOf("the", "of", "and", "a", "to", "in", "with", "for", "my", "is"),
    "de" to setOf("der", "die", "das", "und", "ein", "eine", "im", "mit", "von", "des"),
    "fr" to setOf("le", "la", "les", "et", "du", "des", "une", "un", "au", "aux"),
    "es" to setOf("el", "los", "las", "y", "del", "una", "un", "en", "con", "por"),
    "sv" to setOf("och", "som", "en", "ett", "av", "med", "till", "det", "den", "för"),
    "fi" to setOf("ja", "on", "ei", "kun", "se", "sen"),
    "pl" to setOf("i", "w", "z", "na", "nie", "do", "się", "o"),
    "cs" to setOf("a", "v", "z", "na", "se", "do", "je", "o"),
    "et" to setOf("ja", "on", "ei", "see", "kui", "ning"),
)

/** Harness-side guess: a title written in English inside another catalogue language. */
private val ENGLISH = setOf("the", "of", "and", "with", "from", "my", "your", "this", "that", "is")

fun looksForeign(title: String, language: String): Boolean {
    if (language == "ko") return title.none { it in '\uac00'..'\ud7a3' }
    if (language == "en") return false
    val words = tokenize(title).toSet()
    if (words.any { it in STOPWORDS[language].orEmpty() - ENGLISH }) return false
    return words.any { it in ENGLISH }
}

private class Diagnostics(val catalogue: Catalogue, seeds: List<Book>) {
    private val total = catalogue.workCount.toDouble()
    private val slug = catalogue.tags.associate { it.id to it.slug }
    private val idf = catalogue.tags.associate {
        it.id to ln((total + 1) / (catalogue.tagCount(it.id) + 1))
    }
    private val profile = vector(seeds.flatMap { it.tags })
    private val seedAuthors = seeds.flatMap { it.authors }.map { it.number }.toSet()
    private val seedSeries = seeds.mapNotNull { catalogue.series(it.work)?.name }.toSet()

    fun vector(tags: List<Int>) = tags.groupingBy { it }.eachCount()
        .mapValues { (tag, count) -> count * (idf[tag] ?: 0.0) }

    fun cosine(tags: List<Int>): Double {
        val other = vector(tags)
        val dot = other.entries.sumOf { (tag, value) -> value * (profile[tag] ?: 0.0) }
        val norm = sqrt(other.values.sumOf { it * it }) * sqrt(profile.values.sumOf { it * it })
        return if (norm == 0.0) 0.0 else dot / norm
    }

    fun signals(book: Book) = mapOf(
        "cos" to cosine(book.tags),
        "readers" to (book.popularity?.readers ?: 0).toDouble(),
        "seedAuthor" to if (book.authors.any { it.number in seedAuthors }) 1.0 else 0.0,
        "seedSeries" to
            if (catalogue.series(book.work)?.name in seedSeries) 1.0 else 0.0,
    )

    fun tags(book: Book) = book.tags.take(5).mapNotNull(slug::get).joinToString(",")
}

class SeedReport(
    val label: String,
    val language: String,
    val lines: List<Line>,
    val millis: Double,
    val authorMax: Int,
    val seriesMax: Int,
    val duplicates: Int,
    val foreign: Int,
    val seedAuthorShare: Double,
    val meanCosine: Double,
    val medianReaders: Int,
)

private fun titleOf(book: Book) = tokenize(mainTitle(book.title))
    .let { if (it.firstOrNull() in ARTICLES) it.drop(1) else it }
    .joinToString(" ")

fun evaluate(engine: Engine, folder: File, out: Appendable): List<SeedReport> {
    val reports = mutableListOf<SeedReport>()
    for ((language, seeds) in EVAL_SEEDS.groupBy { it.language }) {
        val catalogue = loadRelease(folder, language)
        if (catalogue.workCount == 0) continue
        val recommender = Recommender(catalogue)
        val searcher = Searcher(catalogue)
        catalogue.warmUp()
        for (seed in seeds) {
            val resolved = resolve(catalogue, searcher, seed)
            out.appendLine("== [$language] ${seed.label}")
            if (resolved == null) {
                out.appendLine("   (seed not found)")
                continue
            }
            resolved.author?.let { out.appendLine("   author ${it.name}") }
            val slugs = catalogue.tags.associate { it.id to it.slug }
            resolved.books.forEach { book ->
                out.appendLine(
                    "   seed ${book.title} / ${book.author} (${book.year}) " +
                        book.tags.mapNotNull(slugs::get).joinToString(",") +
                        (catalogue.series(book.work)?.let { " [${it.name}]" } ?: ""),
                )
            }
            engine.run(recommender, resolved, TOP)
            val times = (1..RUNS).map {
                val start = System.nanoTime()
                engine.run(recommender, resolved, TOP)
                (System.nanoTime() - start) / 1e6
            }
            val lines = engine.run(recommender, resolved, TOP)
            val diagnostics = Diagnostics(catalogue, resolved.books)
            lines.forEachIndexed { index, line ->
                val book = line.book
                val external = diagnostics.signals(book)
                val series = catalogue.series(book.work)?.name?.let { " [$it]" }.orEmpty()
                val flags = buildList {
                    if (looksForeign(book.title, language)) add("FOREIGN")
                    if (external.getValue("seedAuthor") > 0) add("SEED-AUTHOR")
                    if (external.getValue("seedSeries") > 0) add("SEED-SERIES")
                }.joinToString(" ")
                out.appendLine(
                    "%2d %.3f %s / %s%s (%d) %s".format(
                        index + 1, line.score, book.title, book.author, series, book.year,
                        flags,
                    ),
                )
                val internal = line.signals.entries.joinToString(" ") {
                    "%s=%.2f".format(it.key, it.value)
                }
                out.appendLine(
                    "      cos=%.2f readers=%d tags=%s %s".format(
                        external.getValue("cos"), book.popularity?.readers ?: 0,
                        diagnostics.tags(book), internal,
                    ),
                )
            }
            if (seed is EvalSeed.Shelfful) {
                homeRows(recommender, library(resolved.books, seed.stars), language, out)
            }
            val books = lines.map { it.book }
            val report = SeedReport(
                seed.label, language, lines, times.sorted()[RUNS / 2],
                authorMax = books.groupingBy { it.author }.eachCount().values.maxOrNull() ?: 0,
                seriesMax = books.mapNotNull { catalogue.series(it.work)?.name }
                    .groupingBy { it }.eachCount().values.maxOrNull() ?: 0,
                duplicates = books.size - books.distinctBy(::titleOf).size,
                foreign = books.count { looksForeign(it.title, language) },
                seedAuthorShare = books.count { diagnostics.signals(it)["seedAuthor"]!! > 0 }
                    .toDouble() / books.size.coerceAtLeast(1),
                meanCosine = books.map { diagnostics.cosine(it.tags) }.average()
                    .takeIf { !it.isNaN() } ?: 0.0,
                medianReaders = books.map { it.popularity?.readers ?: 0 }.sorted()
                    .getOrElse(books.size / 2) { 0 },
            )
            reports += report
            out.appendLine(
                (
                    "   -> n=%d authorMax=%d seriesMax=%d dups=%d foreign=%d " +
                        "seedAuthor=%.0f%% cos=%.2f medianReaders=%d time=%.1fms"
                    ).format(
                        books.size, report.authorMax, report.seriesMax, report.duplicates,
                        report.foreign, 100 * report.seedAuthorShare, report.meanCosine,
                        report.medianReaders, report.millis,
                    ),
            )
        }
    }
    out.appendLine("== summary")
    out.appendLine(
        (
            "seeds=%d meanCos=%.3f meanLogReaders=%.2f authorMax>3=%d seriesMax>2=%d " +
                "dups=%d foreign=%d empty<%d=%d medianTime=%.1fms maxTime=%.1fms"
            ).format(
                reports.size, reports.map { it.meanCosine }.average(),
                reports.map { ln(1.0 + it.medianReaders) }.average(),
                reports.count { it.authorMax > 3 }, reports.count { it.seriesMax > 2 },
                reports.sumOf { it.duplicates }, reports.sumOf { it.foreign }, TOP,
                reports.count { it.lines.size < TOP },
                reports.map { it.millis }.sorted().getOrElse(reports.size / 2) { 0.0 },
                reports.maxOfOrNull { it.millis } ?: 0.0,
            ),
    )
    return reports
}

private const val SAMPLE_POOL = 4000
private const val SAMPLE_SEEDS = 40
private const val BLIND_DEPTH = 50

private fun percentile(values: List<Double>, share: Double) =
    values.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * share).toInt()] }

/**
 * Sampled evaluation: [SAMPLE_SEEDS] work seeds spread over each language's popular
 * works. Reports list hygiene (foreign, duplicates, flooding, seed editions), quality,
 * time, and a relevance proxy: blind same-author recall, how many of the seed author's
 * other works the author-blind ranking puts in its top [BLIND_DEPTH].
 */
private const val SAMPLE_HEADER =
    "set   seeds empty foreign dups seedEd authorMax quality recall mrr p50ms p95ms"

fun sample(folder: File, out: Appendable, languages: List<String> = LANGUAGES) {
    out.appendLine(SAMPLE_HEADER)
    val totals = languages.mapNotNull { language ->
        val catalogue = loadRelease(folder, language)
        if (catalogue.workCount == 0) null else sampleRow(catalogue, catalogue, language, out)
    }
    val mean = totals.first().indices.map { column -> totals.map { it[column] }.average() }
    out.appendLine(
        "%-5s %5.0f %5.1f %7.3f %4.1f %6.1f %9.1f %7.3f %6.3f %5.3f %5.1f %5.1f"
            .format("mean", *mean.toTypedArray()),
    )
}

/**
 * One sampled row: seeds spread over [source]'s popular works, recommended from
 * [target]. With two languages this is a book opened from another installed catalogue.
 */
fun sampleRow(
    target: Catalogue,
    source: Catalogue,
    label: String,
    out: Appendable,
    resolve: (Int) -> Book? = { null },
    pick: (Book) -> Boolean = { true },
): List<Double> {
    target.warmUp()
    source.warmUp()
    val language = target.language
    val recommender = Recommender(target, resolve)
    val pool = source.popularWorks(SAMPLE_POOL)
        .mapNotNull { work -> source.book(work)?.let { target.adopt(it, source) } }
        .filter { !it.isCompanion && pick(it) }
    val step = (pool.size / SAMPLE_SEEDS).coerceAtLeast(1)
    val seeds = pool.filterIndexed { index, _ -> index % step == 0 }.take(SAMPLE_SEEDS)
    val times = mutableListOf<Double>()
    var empty = 0
    var foreign = 0
    var dups = 0
    var seedEditions = 0
    var flooded = 0
    var shown = 0
    val qualities = mutableListOf<Double>()
    val recalls = mutableListOf<Double>()
    val ranks = mutableListOf<Double>()
    for (seed in seeds) {
        recommender.recommend(RecommendSeed.Work(seed), TOP)
        val start = System.nanoTime()
        val found = recommender.recommend(RecommendSeed.Work(seed), TOP)
        times += (System.nanoTime() - start) / 1e6
        if (found.size < TOP) empty++
        val books = found.map { it.book }
        shown += books.size
        foreign += books.count { looksForeign(it.title, language) }
        dups += books.size - books.distinctBy(::titleOf).size +
            books.filter { it.alternate.isNotEmpty() }.let { alt ->
                alt.size - alt.distinctBy { tokenize(mainTitle(it.alternate)) to it.author }.size
            } + books.count { book ->
                book.alternate.isNotEmpty() && book.author == seed.author &&
                    tokenize(mainTitle(book.alternate)) == tokenize(mainTitle(seed.alternate))
            }
        seedEditions += books.count { titleOf(it) == titleOf(seed) }
        if ((books.groupingBy { it.author }.eachCount().values.maxOrNull() ?: 0) > 2) {
            flooded++
        }
        qualities += books.map { target.quality(it.work) }
        val author = seed.authors.firstOrNull() ?: continue
        val own = target.authorWorks(author)
            .filter { it != seed.work && target.firstAuthorNumber(it) == author.number }
            .toSet()
        if (own.size < 2) continue
        val ranking = recommender.blindRanking(seed, BLIND_DEPTH)
        recalls += ranking.count { it in own }.toDouble() / minOf(own.size, BLIND_DEPTH)
        ranks += ranking.indexOfFirst { it in own }.let { if (it < 0) 0.0 else 1.0 / (it + 1) }
    }
    val row = listOf(
        seeds.size.toDouble(), empty.toDouble(), foreign.toDouble() / shown.coerceAtLeast(1),
        dups.toDouble(), seedEditions.toDouble(), flooded.toDouble(),
        qualities.average().orZero(), recalls.average().orZero(), ranks.average().orZero(),
        percentile(times, 0.5), percentile(times, 0.95),
    )
    out.appendLine(
        "%-5s %5.0f %5.0f %7.3f %4.0f %6.0f %9.0f %7.3f %6.3f %5.3f %5.1f %5.1f"
            .format(label, *row.toTypedArray()),
    )
    return row
}

private fun Double.orZero() = if (isNaN()) 0.0 else this

private const val LIBRARIES = 15
private const val LIBRARY_SIZE = 6

/**
 * A library saved under [source] and recommended from [target]: coherent libraries (a
 * popular work plus its nearest neighbours in [source]), with and without a resolver
 * for entries [target] lacks. Reports how many entries [target] holds itself, how many
 * lists come out, and how close they stay to the library's tags.
 */
fun libraryRow(target: Catalogue, source: Catalogue, label: String, out: Appendable) {
    target.warmUp()
    source.warmUp()
    val neighbours = Recommender(source)
    val pool = source.popularWorks(SAMPLE_POOL)
    val step = (pool.size / LIBRARIES).coerceAtLeast(1)
    val libraries = pool.filterIndexed { index, _ -> index % step == 0 }
        .mapNotNull(source::book)
        .take(LIBRARIES)
        .map { seed -> listOf(seed.work) + neighbours.blindRanking(seed, LIBRARY_SIZE - 1) }
    val adopt = { work: Int -> source.book(work)?.let { target.adopt(it, source) } }
    val held = libraries.flatten().count { target.locate(it) != null }
    for ((mode, resolve) in listOf("plain" to { _: Int -> null }, "resolve" to adopt)) {
        val recommender = Recommender(target, resolve)
        var lists = 0
        val cosines = mutableListOf<Double>()
        val times = mutableListOf<Double>()
        for (library in libraries) {
            val entries = library.mapIndexed { index, work ->
                Saved(work, Shelf.Read, updated = index + 1L, added = index + 1L)
            }
            val start = System.nanoTime()
            val found = recommender.recommend(RecommendSeed.Library(entries), TOP)
            times += (System.nanoTime() - start) / 1e6
            if (found.isNotEmpty()) lists++
            val diagnostics = Diagnostics(target, library.mapNotNull(adopt))
            cosines += found.map { diagnostics.cosine(it.book.tags) }.average().orZero()
        }
        out.appendLine(
            "%-8s %-7s held=%3d/%3d lists=%2d/%2d cos=%.3f p50=%.1fms p95=%.1fms".format(
                label, mode, held, libraries.flatten().size, lists, libraries.size,
                cosines.average(), percentile(times, 0.5), percentile(times, 0.95),
            ),
        )
    }
}

private const val MIXES = 20
private const val MIX_SIDE = 3
private const val MIX_APART = 0.3

/**
 * Two-taste libraries: [MIX_SIDE] older books around seed A and [MIX_SIDE] newer ones
 * around an unrelated seed B. A good list shows both, B (recent) more. Reports the
 * share of lists with both tastes and B's share of the items.
 */
fun mixRow(catalogue: Catalogue, out: Appendable) {
    catalogue.warmUp()
    val recommender = Recommender(catalogue)
    val pool = catalogue.popularWorks(SAMPLE_POOL).mapNotNull(catalogue::book)
        .filter { !it.isCompanion && it.tags.size >= 2 }
    val step = (pool.size / (2 * MIXES)).coerceAtLeast(1)
    val seeds = pool.filterIndexed { index, _ -> index % step == 0 }
    var both = 0
    var lists = 0
    val recent = mutableListOf<Double>()
    val times = mutableListOf<Double>()
    for (a in seeds) {
        if (lists >= MIXES) break
        val b = seeds.firstOrNull { other ->
            other.work != a.work && Diagnostics(catalogue, listOf(a)).cosine(other.tags) < MIX_APART
        } ?: continue
        val sideA = listOf(a.work) + recommender.blindRanking(a, MIX_SIDE - 1)
        val sideB = listOf(b.work) + recommender.blindRanking(b, MIX_SIDE - 1)
        val entries = (sideA + sideB).mapIndexed { index, work ->
            Saved(work, Shelf.Read, updated = index + 1L, added = index + 1L)
        }
        val start = System.nanoTime()
        val found = recommender.recommend(RecommendSeed.Library(entries), TOP)
        times += (System.nanoTime() - start) / 1e6
        val nearA = Diagnostics(catalogue, sideA.mapNotNull(catalogue::book))
        val nearB = Diagnostics(catalogue, sideB.mapNotNull(catalogue::book))
        val sides = found.map { nearB.cosine(it.book.tags) >= nearA.cosine(it.book.tags) }
        lists++
        if (sides.any { it } && sides.any { !it }) both++
        recent += sides.count { it }.toDouble() / sides.size.coerceAtLeast(1)
    }
    out.appendLine(
        "%-4s mixes=%2d bothTastes=%.2f recentShare=%.2f p50=%.1fms p95=%.1fms".format(
            catalogue.language, lists, both.toDouble() / lists.coerceAtLeast(1),
            recent.average().orZero(), percentile(times, 0.5), percentile(times, 0.95),
        ),
    )
}

/** Loads a language with only the given packs, like an install that skipped the rest. */
fun loadPacks(folder: File, language: String, packs: Set<String>): Catalogue {
    val full = loadRelease(folder, language)
    return Catalogue(language, full.segments.filter { it.pack in packs }, full.ranks)
}

class RecommendEval {
    @Test
    fun libraryMixing() {
        val folder = RELEASE
        assumeTrue("set SHELF_CATALOGUE to a catalogue release folder", folder?.isDirectory == true)
        val text = StringBuilder()
        for (language in LANGUAGES) mixRow(loadRelease(folder!!, language), text)
        File(OUTPUT.path.replace(".txt", "-mix.txt")).apply { parentFile?.mkdirs() }
            .writeText(text.toString())
        println(text)
    }

    @Test
    fun multiCatalogue() {
        val folder = RELEASE
        assumeTrue("set SHELF_CATALOGUE to a catalogue release folder", folder?.isDirectory == true)
        val text = StringBuilder()
        text.appendLine("== packs: one language with fewer packs installed")
        text.appendLine(SAMPLE_HEADER)
        for (language in listOf("en", "de", "fr")) {
            val all = loadRelease(folder!!, language).segments.map { it.pack }.toSet()
            for (packs in listOf(setOf("core"), setOf("core", "general"), all)) {
                val catalogue = loadPacks(folder, language, packs)
                val label = "$language:" + if (packs == all) "all" else packs.joinToString("+")
                sampleRow(catalogue, catalogue, label, text)
            }
        }
        val pairs = listOf("en" to "de", "de" to "en", "en" to "ko", "fr" to "es", "sv" to "fi")
        text.appendLine("== seeds opened from another language's catalogue")
        text.appendLine(SAMPLE_HEADER)
        val catalogues = pairs.flatMap { it.toList() }.distinct()
            .associateWith { loadRelease(folder!!, it) }
        for ((from, to) in pairs) {
            sampleRow(catalogues.getValue(to), catalogues.getValue(from), "$from>$to", text)
        }
        text.appendLine("== sparse seeds (broad tags only)")
        text.appendLine(SAMPLE_HEADER)
        val broad = setOf("fiction", "nonfiction", "childrens", "picture-book", "young-adult")
        for (language in listOf("en", "fr", "et", "sv", "cs", "fi", "ko")) {
            val catalogue = catalogues[language] ?: loadRelease(folder!!, language)
            val slugs = catalogue.tags.associate { it.id to it.slug }
            val sparse = { book: Book -> book.tags.all { slugs[it] in broad } }
            sampleRow(catalogue, catalogue, "$language:sp", text, pick = sparse)
        }
        text.appendLine("== libraries saved under another language")
        for ((from, to) in pairs) {
            libraryRow(catalogues.getValue(to), catalogues.getValue(from), "$from>$to", text)
        }
        File(OUTPUT.path.replace(".txt", "-multi.txt")).apply { parentFile?.mkdirs() }
            .writeText(text.toString())
        println(text)
    }


    @Test
    fun sampleRelease() {
        val folder = RELEASE
        assumeTrue("set SHELF_CATALOGUE to a catalogue release folder", folder?.isDirectory == true)
        val text = StringBuilder()
        sample(folder!!, text)
        File(OUTPUT.path.replace(".txt", "-sample.txt")).apply { parentFile?.mkdirs() }
            .writeText(text.toString())
        println(text)
    }


    @Test
    fun evaluateRelease() {
        val folder = RELEASE
        assumeTrue("set SHELF_CATALOGUE to a catalogue release folder", folder?.isDirectory == true)
        OUTPUT.parentFile?.mkdirs()
        val text = StringBuilder()
        evaluate(CURRENT, folder!!, text)
        OUTPUT.writeText(text.toString())
        println(text)
    }
}
