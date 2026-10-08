package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val FANTASY = listOf("fiction", "fantasy", "magic")

private fun fantasy(work: Int, series: String? = null) =
    Fixture(work, "Tale $work", "Writer $work", FANTASY, series, score = work % 7)

private val BOOKS = listOf(1, 2, 3).map { fantasy(it, "Ember") } +
    listOf(11, 12).map { fantasy(it, "Frost") } +
    (20..29).map(::fantasy) +
    (40..45).map { Fixture(it, "Orbit $it", "Pilot $it", listOf("fiction", "space")) }

private val shelfCatalogue = catalogue(BOOKS)
private val recommender = Recommender(shelfCatalogue)

private val LIBRARY = setOf(1, 11)

private fun rows(hidden: Set<Int> = emptySet()) =
    recommender.rows(LIBRARY.map { Saved(it, Shelf.Read, 0) }, seed = 7, hidden = hidden)

private fun List<HomeRow>.works() = map { row -> row.kind to row.books.map { it.work } }

class RecommendTest {
    @Test
    fun rowsSkipLibraryHiddenAndRepeats() {
        val rows = rows(hidden = setOf(21))
        val works = rows.flatMap { row -> row.books.map { it.work } }
        assertTrue(rows.any { it.kind == RowKind.Because })
        assertTrue(works.none { it in LIBRARY + 21 })
        assertEquals(works.distinct(), works)
    }

    @Test
    fun seriesRowOffersNextUnreadVolume() {
        fun series(hidden: Set<Int>) =
            rows(hidden).works().first { it.first == RowKind.Series }.second
        assertEquals(listOf(2, 12), series(emptySet()))
        assertEquals(listOf(3, 12), series(setOf(2)))
    }

    @Test
    fun sameSeedSameRows() {
        assertEquals(rows().works(), rows().works())
        val book = shelfCatalogue.book(20)!!
        val similar = recommender.similar(book).map { it.work }
        assertEquals(similar, recommender.similar(book).map { it.work })
        assertTrue(similar.isNotEmpty() && 20 !in similar)
    }
}
