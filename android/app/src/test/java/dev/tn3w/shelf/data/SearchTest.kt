package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val searcher = Searcher(
    catalogue(
        listOf(
            Fixture(1, "Dune", "Frank Herbert", score = 2),
            Fixture(2, "Dune Messiah", "Frank Herbert", score = 9),
            Fixture(3, "Dunk Tank", "Bea Baker", score = 9),
            Fixture(4, "Dune", "Sam Fan", subtitle = "A Guide", score = 1),
            Fixture(5, "The Silmarillion", "John Tolkien", score = 5),
            Fixture(6, "The Hobbit", "John Tolkien", score = 8),
            Fixture(7, "Sand Castles", "Bea Baker", score = 4),
        ),
    ),
)

private fun works(query: String) = searcher.search(query).map { it.work }

class SearchTest {
    @Test
    fun exactTitleBeatsPrefixAndTypo() {
        assertEquals(listOf(1, 2, 3), works("dune").filter { it != 4 })
    }

    @Test
    fun typoStillFindsTitle() {
        assertEquals(5, works("silmarilion").first())
        assertEquals(6, works("hobit").first())
    }

    @Test
    fun authorNarrowsMultiWordQuery() {
        assertEquals("Frank Herbert", searcher.search("dune herbert").first().author)
        assertEquals(4, works("dune fan").first())
    }

    @Test
    fun findMatchesImportedTitleAndAuthor() {
        assertEquals(1, searcher.find("Dune: Deluxe Edition", "Frank Herbert")?.work)
        assertNull(searcher.find("Dune", "Nobody Known"))
        assertNull(searcher.find("Sand Castles", "Frank Herbert"))
    }

    @Test
    fun garbageFindsNothing() {
        listOf("", "  !!! ", "zzzzzz", "q").forEach { query ->
            assertEquals(emptyList<Int>(), works(query))
        }
    }
}
