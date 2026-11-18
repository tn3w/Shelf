package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val wishTags = listOf(
    "fiction" to "Romane",
    "romance" to "Liebesroman",
    "historical-romance" to "Historischer Liebesroman",
    "history" to "Geschichte",
    "fantasy" to "Fantasy",
    "dragons" to "Drachen",
    "witches" to "Hexen",
    "travel" to "Reisen",
    "time-travel" to "Zeitreisen",
    "childrens" to "Kinderbücher",
    "middle-grade" to "Kinderbücher ab 10",
    "young-adult" to "Jugendbücher",
    "picture-book" to "Bilderbücher",
    "humor" to "Humor",
    "vacations" to "Urlaub & Reisen",
    "crime" to "Krimi",
).mapIndexed { id, (slug, label) -> Tag(id, slug, label, "genre") }

private fun slugs(query: String) = wishOf(query, wishTags).tags.map { it.slug }

private fun excluded(query: String) = wishOf(query, wishTags).excluded.map { it.slug }

class WishTest {
    @Test
    fun namesTagsByLabelOrSlug() {
        assertEquals(listOf("fantasy", "dragons"), slugs("Fantasy mit Drachen"))
        assertEquals(listOf("witches"), slugs("books about witches"))
        assertEquals(listOf("witches"), slugs("a witch story"))
    }

    @Test
    fun longestMatchWins() {
        assertEquals(listOf("historical-romance"), slugs("historical romance"))
        assertEquals(listOf("time-travel"), slugs("a time travel story"))
    }

    @Test
    fun slugsOnlyMatchWholeWords() {
        assertEquals(listOf("fiction"), slugs("Romane über den Krieg"))
    }

    @Test
    fun negatedTagsAreExcluded() {
        assertEquals(listOf("witches"), slugs("witches but no romance"))
        assertEquals(listOf("romance"), excluded("witches but no romance"))
        assertEquals(listOf("romance"), excluded("Hexen ohne Liebesroman"))
    }

    @Test
    fun everydayWordsNameTags() {
        assertEquals(listOf("humor", "vacations"), slugs("something light for the beach"))
        assertEquals(listOf("crime"), slugs("ein spannender Krimi"))
        assertEquals(listOf("romance"), slugs("a sweet love story"))
    }

    @Test
    fun readerGroupComesFromWordsOrAge() {
        assertEquals(listOf("young-adult", "dragons"), slugs("Drachen für Jugendliche"))
        assertEquals(listOf("middle-grade", "witches"), slugs("witches, 10 years old"))
        assertEquals(listOf("picture-book", "dragons"), slugs("dragons for a toddler"))
        assertEquals(listOf("childrens", "witches"), slugs("witches for kids"))
        assertEquals(listOf("young-adult"), slugs("something for my teen daughter"))
    }

    @Test
    fun unknownWishesNameNothing() {
        assertTrue(slugs("something by my favourite writer").isEmpty())
    }

    @Test
    fun wishedBooksCarryEveryTagAndKeepTheReaderGroup() {
        val dragons = (1..20).map {
            Fixture(it, "Dragon $it", "Writer $it", listOf("dragons"))
        }
        val kids = Fixture(30, "Little Dragon", "Kid", listOf("dragons", "childrens"))
        val catalogue = catalogue(dragons + kids)
        val tags = catalogue.tags.filter { it.slug in setOf("dragons", "childrens") }

        val wished = Recommender(catalogue).wished(Wish(tags, emptyList()))

        assertEquals(listOf(30), wished.books.map { it.work })
        assertEquals(tags.map { it.slug }.toSet(), wished.used.map { it.slug }.toSet())
    }

    @Test
    fun broadestTagIsLetGoWhenTooFewBooksMatch() {
        val fantasy = (1..20).map {
            Fixture(it, "Saga $it", "Writer $it", listOf("fantasy", "fiction"))
        }
        val catalogue = catalogue(fantasy + Fixture(40, "Comet", "Star", listOf("space")))
        val tags = catalogue.tags.filter { it.slug in setOf("fantasy", "space") }

        val wished = Recommender(catalogue).wished(Wish(tags, emptyList()))

        assertEquals(listOf("space"), wished.used.map { it.slug })
        assertEquals(listOf(40), wished.books.map { it.work })
    }

    @Test
    fun excludedTagsKeepTheirBooksOut() {
        val plain = (1..20).map {
            Fixture(it, "Dragon $it", "Writer $it", listOf("dragons"))
        }
        val magic =
            Fixture(30, "Spell Dragon", "Mage", listOf("dragons", "magic"), score = 99)
        val catalogue = catalogue(plain + magic)
        val tag = { slug: String -> catalogue.tags.filter { it.slug == slug } }

        val wished = Recommender(catalogue).wished(Wish(tag("dragons"), tag("magic")))

        assertTrue(wished.books.isNotEmpty())
        assertTrue(wished.books.none { it.work == 30 })
    }
}
