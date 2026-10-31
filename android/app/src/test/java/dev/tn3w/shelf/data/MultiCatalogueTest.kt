package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val CRIME_TAGS = listOf("fiction", "crime")
private val MAGIC_TAGS = listOf("fiction", "fantasy", "magic")

private fun fixture(work: Int, tags: List<String>, score: Int = 5) =
    Fixture(work, "Tale $work", "Writer $work", tags, score = score)

private fun language(
    code: String,
    books: List<Fixture>,
    table: List<String> = TAGS,
): Catalogue {
    val segment = Segment(segmentBuffer(books, tagTable = table))
    return Catalogue(code, listOf(segment), Ranks(ranksBuffer(books)))
}

/** Primary: popular fantasy and a little crime. Other: the crime the reader saved. */
private val PRIMARY = (1..10).map { fixture(it, MAGIC_TAGS, score = 50) } +
    (11..20).map { fixture(it, CRIME_TAGS) } +
    (900..939).map { fixture(it, listOf("nonfiction"), score = 1) }
private val OTHER = (101..106).map { fixture(it, CRIME_TAGS) } +
    (950..979).map { fixture(it, listOf("nonfiction"), score = 1) }

class MultiCatalogueTest {
    private val primary = language("de", PRIMARY)
    private val other = language("en", OTHER, TAGS.reversed())
    private val loaded = Loaded(primary) { listOf(other) }
    private val library = (101..104).map {
        Saved(it, Shelf.Read, it.toLong(), added = it.toLong())
    }

    @Test
    fun adoptMapsTagsBySlug() {
        val book = other.book(101)!!
        val slugs = { catalogue: Catalogue, tags: List<Int> ->
            tags.map { id -> catalogue.tags.first { it.id == id }.slug }.toSet()
        }
        assertEquals(CRIME_TAGS.toSet(), slugs(other, book.tags))
        assertEquals(CRIME_TAGS.toSet(), slugs(primary, primary.adopt(book, other).tags))
    }

    @Test
    fun librarySavedUnderAnotherLanguageStillShapesTheList() {
        val blind = Recommender(primary).recommend(RecommendSeed.Library(library), 5)
        assertTrue(blind.all { it.book.work in 1..10 })
        val found = loaded.recommender.recommend(RecommendSeed.Library(library), 5)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.book.work in 11..20 })
    }

    @Test
    fun foreignBookSeedsTheCurrentCatalogue() {
        val seed = loaded.seedBook(101)!!
        val similar = loaded.recommender.recommended(RecommendSeed.Work(seed), 5)
            .map { it.work }
        assertTrue(similar.isNotEmpty())
        assertTrue(similar.all { it in 11..20 })
        assertEquals(primary.book(3)?.work, loaded.seedBook(3)?.work)
    }

    @Test
    fun popularWorksFollowScores() {
        val top = primary.popularWorks(12)
        assertEquals(top.sortedByDescending(primary::score), top)
        assertTrue(top.take(10).all { it in 1..10 })
    }
}
