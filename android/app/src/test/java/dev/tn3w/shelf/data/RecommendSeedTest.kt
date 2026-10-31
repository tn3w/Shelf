package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val MAGIC = listOf("fiction", "fantasy", "magic")
private val CRIME = listOf("fiction", "crime")
private const val SEA = "Sailors cross stormy oceans aboard ships"

private fun book(
    work: Int,
    tags: List<String> = MAGIC,
    author: String = "Writer $work",
    title: String = "Tale $work",
    series: String? = null,
    year: Int = 0,
    score: Int = 5,
    description: String = "",
    rating: Double = 4.0,
) = Fixture(
    work,
    title,
    author,
    tags,
    series,
    year = year,
    score = score,
    description = description,
    rating = rating,
)

/** Unrelated nonfiction, so that the tags under test stay informative. */
private val FILLER = (1000..1039).map { book(it, listOf("nonfiction"), score = 1) }

private fun shelf(books: List<Fixture>, language: String = "en") = (books + FILLER).let {
    Catalogue(language, listOf(segment(it)), Ranks(ranksBuffer(it)))
}

private fun Catalogue.recommend(seed: RecommendSeed, limit: Int = 10) =
    Recommender(this).recommend(seed, limit)

private fun List<Recommendation>.works() = map { it.book.work }

private fun saved(work: Int, at: Long) = Saved(work, Shelf.Read, updated = at, added = at)

private fun author(name: String) = Author(numberOf(name), name)

class RecommendSeedTest {
    @Test
    fun authorSeedRecommendsOtherAuthors() {
        val books = (1..4).map { book(it, CRIME, author = "Ann Sleuth") } +
            (10..19).map { book(it, CRIME) }
        val found = shelf(books).recommend(RecommendSeed.Writer(author("Ann Sleuth")))
        assertTrue(found.isNotEmpty())
        assertTrue(found.none { it.book.author == "Ann Sleuth" })
    }

    @Test
    fun workSeedSkipsItsOwnSeries() {
        val books = (1..3).map { book(it, author = "Saga Smith", series = "Ember") } +
            book(4, author = "Saga Smith", title = "Ember Rising") +
            (10..19).map { book(it) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertTrue(found.isNotEmpty())
        assertTrue(found.works().none { it in 1..4 })
    }

    @Test
    fun seriesSeedSkipsEveryVolume() {
        val books = (1..3).map { book(it, author = "Saga Smith", series = "Ember") } +
            (10..19).map { book(it) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Saga(catalogue.book(2)!!))
        assertTrue(found.isNotEmpty())
        assertTrue(found.works().none { it in 1..3 })
    }

    @Test
    fun oneAuthorCannotFloodTheList() {
        val books = (1..12).map { book(it, author = "Prolific", score = 40) } +
            (20..32).map { book(it) }
        val catalogue = shelf(books)
        val other = catalogue.recommend(RecommendSeed.Work(catalogue.book(20)!!))
        assertTrue(other.count { it.book.author == "Prolific" } <= 2)
        val own = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertTrue(own.count { it.book.author == "Prolific" } <= 1)
        assertEquals(10, own.size)
    }

    @Test
    fun editionsOfOneTitleAppearOnce() {
        val books = listOf(
            book(30, title = "The Dragon Road", author = "Road Writer", score = 30),
            book(31, title = "The Dragon Road", author = "Road Writer", score = 29),
        ) + (1..12).map { book(it) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertEquals(1, found.count { it.book.title == "The Dragon Road" })
        val owned = catalogue.recommend(RecommendSeed.Library(listOf(saved(30, 1))))
        assertTrue(owned.none { it.book.title == "The Dragon Road" })
    }

    @Test
    fun libraryExcludesOwnedAndHiddenWorks() {
        val books = (1..20).map { book(it) }
        val library = (1..5).map { saved(it, it.toLong()) }
        val found =
            shelf(books).recommend(RecommendSeed.Library(library, hidden = setOf(6, 7)))
        assertTrue(found.isNotEmpty())
        assertTrue(found.works().none { it in 1..7 })
    }

    @Test
    fun recentReadingLeadsTheLibraryList() {
        val books = (1..10).map { book(it, MAGIC) } + (11..20).map { book(it, CRIME) }
        val catalogue = shelf(books)
        val fantasyFirst =
            listOf(saved(1, 1), saved(2, 2), saved(11, 100), saved(12, 101))
        val recent = catalogue.recommend(RecommendSeed.Library(fantasyFirst))
        assertTrue(recent.first().book.work in 13..20)
        val crimeFirst = listOf(saved(11, 1), saved(12, 2), saved(1, 100), saved(2, 101))
        val reversed = catalogue.recommend(RecommendSeed.Library(crimeFirst))
        assertTrue(reversed.first().book.work in 3..10)
    }

    @Test
    fun lighterTasteStillGetsAPlace() {
        val books = (1..30).map { book(it, MAGIC, score = 60) } +
            (31..60).map { book(it, CRIME, score = 2) }
        val entries =
            listOf(saved(31, 1), saved(32, 2), saved(1, 10), saved(2, 11), saved(3, 12))
        val found = shelf(books).recommend(RecommendSeed.Library(entries))
        assertTrue(found.any { it.book.work in 33..60 })
        assertTrue(found.count { it.book.work in 4..30 } > found.size / 2)
    }

    @Test
    fun repeatedEntriesWeighMore() {
        val books = (1..10).map { book(it, MAGIC) } + (11..20).map { book(it, CRIME) }
        val entries = listOf(saved(1, 5)) + List(3) { saved(11, 5) }
        val found = shelf(books).recommend(RecommendSeed.Library(entries))
        assertTrue(found.first().book.work in 12..20)
    }

    @Test
    fun foreignTitlesRankBelowLocalOnes() {
        val books = listOf(
            book(1, title = "Der Drache"),
            book(2, title = "The Magic of the Night", score = 9),
            book(3, title = "Die Magie der Nacht", score = 8),
        )
        val catalogue = shelf(books, "de")
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertEquals(listOf(3, 2), found.works())
    }

    @Test
    fun titleLanguageHeuristic() {
        assertTrue(isLocalTitle("Die Therapie", "de"))
        assertTrue(isLocalTitle("Sakrileg", "de"))
        assertFalse(isLocalTitle("The Silent Patient", "de"))
        assertTrue(isLocalTitle("채식주의자", "ko"))
        assertFalse(isLocalTitle("Catching Fire", "ko"))
        assertTrue(isLocalTitle("The Hobbit", "en"))
        assertFalse(isLocalTitle("Le petit prince", "en"))
        assertTrue(isLocalTitle("L'étranger", "fr"))
    }

    @Test
    fun sparseSeedStillFindsBooksOfItsEra() {
        val books = listOf(
            book(1, listOf("fiction"), author = "Anton", year = 1926),
            book(5, listOf("fiction", "space"), author = "Anton", year = 1931),
            book(2, listOf("fiction"), year = 2015),
            book(3, listOf("fiction"), year = 1930),
            book(4, listOf("fiction", "space"), year = 1928),
        )
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!)).works()
        assertTrue(found.isNotEmpty())
        assertTrue(found.indexOf(3) < found.indexOf(2))
    }

    @Test
    fun eraBreaksTiesBetweenEqualMatches() {
        val books = listOf(
            book(1, CRIME, year = 1925),
            book(2, CRIME, year = 2020),
            book(3, CRIME, year = 1930),
        )
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertEquals(listOf(3, 2), found.works())
    }

    @Test
    fun closeMatchBeatsPopularLooseMatch() {
        val full = listOf("fiction", "fantasy", "magic", "dragons")
        val books = listOf(
            book(1, full),
            book(2, listOf("fiction", "fantasy"), score = 60),
            book(3, full, score = 3),
        ) + (10..14).map { book(it, listOf("fiction", "crime")) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!)).works()
        assertEquals(3, found.first())
        assertTrue(found.none { it in 10..14 })
    }

    @Test
    fun recommendationsExplainTheirScore() {
        val books = (1..6).map { book(it) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertTrue(found.isNotEmpty())
        found.forEach {
            val expected = listOf("sim", "era", "quality", "lang")
            assertTrue(it.signals.keys.containsAll(expected))
        }
        assertEquals(found.sortedByDescending { it.score }, found)
    }

    @Test
    fun everySeedRecommendsOtherBooks() {
        val books = (1..3).map { book(it, author = "Saga Smith", series = "Ember") } +
            (10..19).map { book(it) }
        val catalogue = shelf(books)
        val recommender = Recommender(catalogue)
        val first = catalogue.book(1)!!
        val seeds = listOf(
            RecommendSeed.Work(first) to 1..3,
            RecommendSeed.Saga(first) to 1..3,
            RecommendSeed.Writer(author("Saga Smith")) to 1..3,
            RecommendSeed.Mix(listOf(first, catalogue.book(10)!!)) to (1..3) + 10,
        )
        seeds.forEach { (seed, excluded) ->
            val found = recommender.recommended(seed, 5).map { it.work }
            assertEquals(recommender.recommend(seed, 5).works(), found)
            assertTrue(found.isNotEmpty())
            assertTrue(found.none { it in excluded })
        }
    }

    @Test
    fun mixBlendsEveryPickedTaste() {
        val books = (1..10).map { book(it, MAGIC) } + (11..20).map { book(it, CRIME) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Mix(catalogue.books(listOf(1, 11))))
        assertTrue(found.works().any { it in 2..10 })
        assertTrue(found.works().any { it in 12..20 })
        assertTrue(found.works().none { it == 1 || it == 11 })
    }

    @Test
    fun tagBooksSortByPopularityOrRating() {
        val books = listOf(
            book(1, CRIME, score = 30, rating = 3.5),
            book(2, CRIME, score = 20, rating = 4.8),
            book(3, CRIME, score = 10, rating = 4.2),
        )
        val catalogue = shelf(books)
        val crime = catalogue.tagBySlug.getValue("crime").id
        fun order(sort: TagSort) =
            Recommender(catalogue).popular(3, crime, sort).map { it.work }
        assertEquals(listOf(1, 2, 3), order(TagSort.Popular))
        assertEquals(listOf(2, 3, 1), order(TagSort.Rated))
    }
}

class SeedContentTest {
    @Test
    fun audienceOnlySeedKeepsCandidatesWithMoreTags() {
        val kids = listOf("fiction", "childrens")
        val books = listOf(Fixture(1, "Seed", "Kid Writer", kids)) +
            (2..14).map { Fixture(it, "Tale $it", "Writer $it", kids + "magic") }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertEquals(10, found.size)
    }

    @Test
    fun blockedHeadStillFillsTheList() {
        val books = (1..60).map { book(it, author = "Prolific", score = 90) } +
            (100..115).map { book(it, score = 1) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertEquals(10, found.size)
    }

    @Test
    fun sharedDescriptionWordsBreakTagTies() {
        val books = listOf(
            book(1, description = "An orphan wizard enters Hogwarts castle for spells"),
            book(2, description = "A detective walks foggy London streets at night"),
            book(3, description = "Young wizard pupils at Hogwarts castle learn spells"),
        ) + (10..20).map { book(it, description = SEA) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertEquals(3, found.first().book.work)
        assertTrue(found.first().signals.getValue("text") > 0.0)
    }

    @Test
    fun authorNamesInDescriptionsDoNotCount() {
        val quill = "Mira Quill"
        val books = listOf(
            book(1, author = quill, description = "$quill writes about lighthouses"),
            book(2, author = quill, description = "$quill writes about orchards"),
            book(3, description = "Keepers tend remote lighthouses through winter"),
        ) + (10..20).map { book(it, description = SEA) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        val text = found.associate { it.book.work to it.signals.getValue("text") }
        assertTrue(text.getValue(3) > (text[2] ?: 0.0))
    }

    @Test
    fun sequelsWithoutSeriesRecordsStayOut() {
        val books = listOf(
            book(1, title = "Foundation", author = "Isaac Asimov", score = 50),
            book(2, title = "Foundation and Empire", author = "Isaac Asimov", score = 40),
            book(3, title = "Nightfall", author = "Isaac Asimov", score = 30),
        ) + (10..20).map { book(it) }
        val catalogue = shelf(books)
        val library = catalogue.recommend(RecommendSeed.Library(listOf(saved(1, 1))))
        assertTrue(2 !in library.works())
        assertTrue(3 in library.works())
        val similar = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!))
        assertTrue(2 !in similar.works())
    }
}

class FeedbackTest {
    private fun rated(work: Int, stars: Int, at: Long) =
        Saved(work, Shelf.Read, updated = at, rating = stars, added = at)

    @Test
    fun lowRatedAuthorsSinkInTheLibraryList() {
        val books = listOf(book(1, MAGIC), book(2, MAGIC, author = "Dull Hand")) +
            (3..8).map { book(it, MAGIC) } +
            (20..23).map { book(it, MAGIC, author = "Dull Hand", score = 30) }
        val entries = listOf(rated(1, 5, 1), rated(2, 1, 2))
        val found = shelf(books).recommend(RecommendSeed.Library(entries), 6)
        assertTrue(found.isNotEmpty())
        assertTrue(found.take(4).none { it.book.author == "Dull Hand" })
        assertTrue(found.all { it.signals.containsKey("aversion") })
    }

    @Test
    fun lowRatedBooksDoNotShapeTheTaste() {
        val books = (1..10).map { book(it, MAGIC) } + (11..20).map { book(it, CRIME) }
        val entries = listOf(rated(11, 4, 1), rated(1, 1, 100), rated(2, 2, 101))
        val found = shelf(books).recommend(RecommendSeed.Library(entries), 5)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.book.work in 12..20 })
    }

    @Test
    fun translationsOfOneBookAppearOnce() {
        val dragon = "The Dragon"
        val books = listOf(
            Fixture(30, "Wyrmfire", "Anna Wyrm", MAGIC, score = 9, alternate = dragon),
            Fixture(31, "Wyrmfeuer", "Anna Wyrm", MAGIC, score = 8, alternate = dragon),
        ) + (1..12).map { book(it) }
        val catalogue = shelf(books)
        val found = catalogue.recommend(RecommendSeed.Work(catalogue.book(1)!!)).works()
        assertEquals(1, found.count { it in 30..31 })
        val library = RecommendSeed.Library(listOf(saved(30, 1)))
        val owned = catalogue.recommend(library).works()
        assertTrue(31 !in owned)
    }
}

class HomeRowsTest {
    @Test
    fun becauseRowsSkipSequelsOfOwnedBooks() {
        val books = listOf(
            book(1, title = "Foundation", author = "Isaac Asimov", score = 50),
            book(2, title = "Foundation and Empire", author = "Isaac Asimov", score = 40),
        ) + (10..30).map { book(it) }
        val rows = Recommender(shelf(books)).rows(listOf(saved(1, 1)), seed = 3)
        val because = rows.filter { it.kind == RowKind.Because }.flatMap { it.books }
        assertTrue(because.isNotEmpty())
        assertTrue(because.none { it.work == 2 })
    }

    @Test
    fun becauseRowsPreferLocalTitles() {
        val books = listOf(book(1, title = "Der Drache")) +
            (2..9).map { book(it, title = "The Night of the Dragon $it", score = 9) } +
            (10..17).map { book(it, title = "Die Nacht der Drachen $it", score = 8) }
        val rows = Recommender(shelf(books, "de")).rows(listOf(saved(1, 1)), seed = 3)
        val because = rows.first { it.kind == RowKind.Because }.books
        assertTrue(because.take(4).all { it.work in 10..17 })
    }
}

class CatalogueSignalTest {
    @Test
    fun indexesOfHandlesRunsAndMisses() {
        val first = (1..9 step 2).map { book(it) }
        val second = (2..10 step 2).map { book(it) }
        val all = first + second
        val catalogue = Catalogue(
            "en",
            listOf(segment(first), segment(second, pack = "fantasy")),
            Ranks(ranksBuffer(all)),
        )
        val items = intArrayOf(1, 5, 9, 2, 4, 10, 7, 99, 3)
        val found = catalogue.indexesOf(items)
        items.zip(found.toList()).forEach { (work, index) ->
            if (work == 99) {
                assertEquals(-1, index)
            } else {
                assertEquals(work, catalogue.workAt(index))
            }
        }
    }

    @Test
    fun authorWorksIgnoresNamesakes() {
        val books = (1..3).map { book(it, author = "Ann Jones") } +
            (4..9).map { book(it, author = "Ann Smith $it") }
        val catalogue = shelf(books)
        assertEquals(listOf(1, 2, 3), catalogue.authorWorks(author("Ann Jones")).sorted())
    }

    @Test
    fun qualityGrowsWithReadersAndStaysInRange() {
        val books = listOf(book(1, score = 1), book(2, score = 400))
        val catalogue = shelf(books)
        val low = catalogue.quality(1)
        val high = catalogue.quality(2)
        assertTrue(low in 0.0..1.0 && high in 0.0..1.0)
        assertTrue(high > low)
        assertEquals(0.0, catalogue.quality(99), 0.0)
    }
}
