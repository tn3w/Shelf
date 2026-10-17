package dev.tn3w.shelf.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

private val trusted = Searcher(
    catalogue(
        listOf(
            Fixture(1, "Novum Organum", "Francis Bacon", score = 9),
            Fixture(2, "The Lightning Thief", "Rick Riordan", score = 5),
            Fixture(
                3,
                "Percy Jackson and the Lightning Thief",
                "Guide Press",
                subtitle = "Study Guide",
                score = 9,
            ),
            Fixture(4, "The Hobbit", "John Tolkien", score = 6),
            Fixture(5, "The Hobbit and The Lord of the Rings", "John Tolkien", score = 9),
            Fixture(6, "Gardens of the Moon", "Steven Erikson", score = 2),
            Fixture(7, "Gardens of Montgomery", "Ann Montgomery", score = 9),
            Fixture(8, "Harry Potter", "Jo Rowling", score = 5),
            Fixture(9, "Harry Potter", "Jo Rowling", subtitle = "Complete Box Set"),
        ),
    ),
)

private fun works(query: String) = searcher.search(query).map { it.work }

private fun trustedWorks(query: String) = trusted.search(query).map { it.work }

private fun isCompanion(title: String) = Book(1, title).isCompanion

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

    @Test
    fun lookupSharesTokenMatchesAcrossSearchCompleteAndAuthors() {
        val lookup = searcher.lookup("dun")
        assertEquals(
            searcher.search("dun").map {
                it.work
            },
            searcher.search(lookup).map { it.work },
        )
        assertEquals(listOf("dune", "dunk"), searcher.complete(lookup).sorted().take(2))
        assertEquals(emptyList<Author>(), searcher.authors(searcher.lookup("")))
        assertEquals(
            listOf("Bea Baker"),
            searcher.authors(searcher.lookup("bea")).map {
                it.name
            },
        )
    }

    @Test
    fun unanchoredMatchesFindNothing() {
        assertEquals(emptyList<Int>(), trustedWorks("katja brandis seawalkers"))
    }

    @Test
    fun knownItemsComeFirstWithoutCompanions() {
        mapOf(
            "percy jackson the lightning thief" to 2,
            "lightning thief" to 2,
            "the hobbit" to 4,
            "hobit" to 4,
            "hobit tolkein" to 4,
            "gardens of the moon" to 6,
        ).forEach { (query, work) ->
            val found = trustedWorks(query)
            assertEquals(query, work, found.first())
            assertFalse(query, found.any { it == 3 || it == 5 })
        }
        assertEquals(5, trustedWorks("the hobbit and the lord of the rings").first())
        assertFalse(9 in trustedWorks("harry potter"))
        assertTrue(9 in trustedWorks("harry potter box set"))
    }

    @Test
    fun twoPartOmnibusIsCompanion() {
        assertTrue(isCompanion("The Hobbit and The Lord of the Rings"))
        assertTrue(isCompanion("The Hobbit / The Lord of the Rings"))
        assertTrue(isCompanion("Die Firma / Die Akte"))
        assertTrue(isCompanion("Der Zauberlehrling ; Die Doppelganger"))
        assertFalse(isCompanion("The Lion, the Witch and the Wardrobe"))
        assertFalse(isCompanion("The Old Man and the Sea"))
        assertFalse(isCompanion("Pride & Prejudice"))
        assertFalse(isCompanion("The City & The City"))
        assertFalse(isCompanion("El poder / The Power"))
        assertFalse(isCompanion("La Bella y La Bestia"))
    }

    @Test
    fun seriesNameFindsEveryVolume() {
        fun volume(work: Int, title: String, series: String? = "Gregs Tagebuch") =
            Fixture(work, title, "Jeff Kinney", series = series, score = 2)
        val volumes = Searcher(
            catalogue(
                listOf(
                    volume(1, "Von Idioten umzingelt!"),
                    volume(2, "Gregs Tagebuch 2. Gibt's Probleme?", series = null),
                    volume(3, "Jetzt reicht's!"),
                    Fixture(4, "Tagebuch einer Reise", score = 2),
                ),
            ),
        )
        val found = volumes.search("gregs tagebuch").map { it.work }
        assertEquals(listOf(1, 2, 3), found.sorted())
    }

    @Test
    fun didYouMeanFixesTruncatedLastWord() {
        val lookup = trusted.lookup("gardens of the mon")
        val suggestions = trusted.complete(lookup, trusted.search(lookup))
        assertEquals("gardens of the moon", suggestions.first())
    }

    @Test
    fun cancelledJobStopsLookupSearchAndAuthors() {
        val cancelled = Job().apply { cancel() }
        val lookup = searcher.lookup("dune")
        assertThrows(CancellationException::class.java) {
            searcher.lookup("dune", cancelled)
        }
        assertThrows(CancellationException::class.java) {
            searcher.search(lookup, job = cancelled)
        }
        assertThrows(CancellationException::class.java) {
            searcher.authors(lookup, job = cancelled)
        }
    }
}
