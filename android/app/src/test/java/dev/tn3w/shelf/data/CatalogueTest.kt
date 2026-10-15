package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

private val BOOKS = listOf(
    Fixture(
        30,
        "Dragon Moon",
        "Cara Quill",
        listOf("fiction", "dragons"),
        series = "Moon",
        year = 1999,
        description = "Wings.",
        score = 7,
    ),
    Fixture(10, "Quiet Stars", tags = listOf("space"), subtitle = "A Novel", score = 3),
    Fixture(20, "Moon Rising", "Cara Quill", listOf("fiction"), series = "Moon"),
)

private fun Facts.fields() = listOf(authors.toList(), year, cover, tags.toList(), series)

class CatalogueTest {
    @Test
    fun segmentReadsRecordsAcrossBlocks() {
        val segment = segment(BOOKS)
        assertEquals(listOf(10, 20, 30), (0 until segment.workCount).map(segment::work))
        assertEquals(listOf(2, -1), listOf(segment.localOf(30), segment.localOf(15)))
        assertEquals(Heads("Quiet Stars", "A Novel", ""), segment.heads(0))
        assertEquals(Heads("Dragon Moon", "", ""), segment.heads(2))
        val tags = listOf(TAGS.indexOf("fiction"), TAGS.indexOf("dragons"))
        assertEquals(listOf(listOf(1), 1999, 0, tags, 0), segment.facts(2).fields())
        assertEquals(listOf(listOf(0), 0, 0, listOf(5), -1), segment.facts(0).fields())
        assertEquals(Series("Moon", listOf(30, 20)), segment.series(0))
        val author = segment.author(1)
        assertEquals("Cara Quill" to numberOf("Cara Quill"), author.name to author.number)
        assertArrayEquals(intArrayOf(1, 2), author.works)
        assertEquals(Description("Wings.", false), segment.description(2))
        assertEquals(Description("", false), segment.description(0))
        assertArrayEquals(intArrayOf(1, 2), segment.tagWorks(TAGS.indexOf("fiction")))
        assertEquals(2, segment.tagCount(TAGS.indexOf("fiction")))
    }

    @Test
    fun segmentReadsPrefixCompressedTerms() {
        val segment = segment(BOOKS)
        assertArrayEquals(intArrayOf(1, 2), segment.term("moon")!!.titleWorks)
        assertArrayEquals(intArrayOf(1), segment.term("quill")!!.authorSlots)
        assertNull(segment.term("moo"))
        assertEquals(listOf("moon"), segment.completions("mo", 5).map { it.text })
        assertEquals(listOf("rising"), segment.completions("risin", 5).map { it.text })
        val gram = segment.gramTerms("oon").map { segment.termById(it).text }
        assertEquals(listOf("moon"), gram)
    }

    @Test
    fun ranksReadPopularityAcrossBlocks() {
        val ranks = Ranks(ranksBuffer(BOOKS))
        assertEquals(3, ranks.works)
        assertEquals(Popularity(7.0, 7, 7, 4.0, 1), ranks.popularity(30))
        assertEquals(Popularity(3.0, 3, 3, 4.0, 1), ranks.popularity(10))
        assertNull(ranks.popularity(5))
        assertNull(ranks.popularity(25))
        assertEquals(2, ranks.frequency("moon"))
    }

    @Test
    fun rejectsForeignFormats() {
        val sections = mapOf("meta" to "pack=core".toByteArray())
        val foreign = container(sections, magic = "ABCD")
        val newer = container(sections, version = CATALOGUE_FORMAT + 1)
        val garbage = ByteBuffer.wrap(Random(1).nextBytes(512))
        listOf(foreign, newer, garbage).forEach { buffer ->
            assertThrows(IllegalStateException::class.java) { Segment(buffer) }
        }
    }

    @Test
    fun truncatedSegmentFailsWhenOpened() {
        val bytes = segmentBuffer(BOOKS).array()
        listOf(0, 7, 40, bytes.size / 2, bytes.size - 1).forEach { length ->
            val truncated = ByteBuffer.wrap(bytes.copyOf(length))
            assertThrows(RuntimeException::class.java) { Segment(truncated) }
        }
    }

    @Test
    fun newerSegmentOverridesAndTombstonesHide() {
        val base = segment(
            listOf(Fixture(1, "Old Title"), Fixture(2, "Gone"), Fixture(3, "Kept")),
        )
        val delta = Segment(
            segmentBuffer(
                listOf(Fixture(1, "New Title")),
                month = "2000-02",
                base = "2000-01",
                tombstones = listOf(2),
            ),
        )
        val catalogue = Catalogue("en", listOf(base, delta), null)
        assertEquals(
            listOf("New Title", null, "Kept"),
            (1..3).map {
                catalogue.book(it)?.title
            },
        )
        assertEquals(2, catalogue.workCount)
        val searcher = Searcher(catalogue)
        assertEquals(emptyList<Book>(), searcher.search("gone"))
        assertEquals(emptyList<Book>(), searcher.search("old"))
    }

    @Test
    fun isbnsFindNewestVisibleBook() {
        val base = segment(
            listOf(
                Fixture(1, "Old Title", isbns = listOf("9780439023481")),
                Fixture(2, "Gone", isbns = listOf("9780441013593")),
                Fixture(3, "Plain"),
                Fixture(4, "Twin", isbns = listOf("9791032305690", "0306406152")),
            ),
        )
        val delta = Segment(
            segmentBuffer(
                listOf(Fixture(1, "New Title")),
                month = "2000-02",
                base = "2000-01",
                tombstones = listOf(2),
            ),
        )
        val catalogue = Catalogue("en", listOf(base, delta), null)
        val isbns =
            listOf("978-0-439-02348-1", "9780441013593", "979103230569-0", "0306406152")
        assertEquals(
            listOf("New Title", null, "Twin", "Twin", null),
            (isbns + "9780000000002").map { catalogue.bookOfIsbn(it)?.title },
        )
    }

    @Test
    fun loadedFindsBooksOfOtherInstalledLanguages() {
        val english = Catalogue("en", listOf(segment(listOf(Fixture(1, "Emma")))), null)
        val german =
            Catalogue("de", listOf(segment(listOf(Fixture(2, "Der Process")))), null)
        val loaded = Loaded(english) { listOf(german) }
        assertEquals(
            listOf("Emma", "Der Process", null),
            (1..3).map {
                loaded.book(it)?.title
            },
        )
        assertEquals("de", loaded.catalogueOf(2)?.language)
    }

    @Test
    fun isbnsOfListEveryEditionOfAWork() {
        val base = segment(
            listOf(
                Fixture(1, "Old Title", isbns = listOf("9780439023481")),
                Fixture(3, "Plain"),
                Fixture(4, "Twin", isbns = listOf("9791032305690", "0306406152")),
            ),
        )
        val delta = Segment(
            segmentBuffer(
                listOf(Fixture(1, "New Title")),
                month = "2000-02",
                base = "2000-01",
            ),
        )
        val catalogue = Catalogue("en", listOf(base, delta), null)
        assertEquals(listOf("9780439023481"), catalogue.isbnsOf(1))
        assertEquals(emptyList<String>(), catalogue.isbnsOf(3))
        val twins = setOf("9791032305690", "9780306406157")
        assertEquals(twins, catalogue.isbnsOf(4).toSet())
        assertEquals(emptyList<String>(), catalogue(BOOKS).isbnsOf(1))
    }

    @Test
    fun isbnsOfFollowsAliases() {
        val books = listOf(Fixture(42, "Moved", isbns = listOf("9780439023481")))
        val aliases = listOf(SYNTHETIC + 1 to 42)
        val catalogue =
            Catalogue("en", listOf(segment(books)), Ranks(ranksBuffer(books, aliases)))
        assertEquals(listOf("9780439023481"), catalogue.isbnsOf(SYNTHETIC + 1))
    }

    @Test
    fun isbnsForPrefersOwnThenLibraryThenCatalogue() {
        val book = Book(1, "Emma", libraryCover = LibraryCover(DNB, "9783161484100"))
        val catalogue = listOf("9783161484100", "bad", "9780439023481")
        val isbns = isbnsFor(book, "0306406152", catalogue)
        assertEquals(listOf("9780306406157", "9783161484100", "9780439023481"), isbns)
    }

    @Test
    fun packsWithoutIsbnsFindNothing() {
        assertNull(catalogue(BOOKS).bookOfIsbn("9780439023481"))
    }

    @Test
    fun coverUrlPrefersOpenLibraryThenLibrary() {
        val book = Book(1, "T")
        val dnb = LibraryCover(DNB, "9783833911316")
        assertNull(book.coverUrl(COVERS, "L"))
        assertEquals(
            "https://portal.dnb.de/opac/mvb/cover?isbn=9783833911316",
            book.copy(libraryCover = dnb).coverUrl(COVERS, "L"),
        )
        assertEquals(
            "$COVERS/b/id/7-L.jpg",
            book.copy(cover = 7, libraryCover = dnb).coverUrl(COVERS, "L"),
        )
        assertEquals(
            "https://mirror.example/opac/mvb/cover?isbn=9783833911316",
            book.copy(libraryCover = dnb).coverUrl(COVERS, "L", "https://mirror.example"),
        )
        val unknown = LibraryCover(99, "9783833911316")
        assertNull(book.copy(libraryCover = unknown).coverUrl(COVERS, "L"))
    }

    @Test
    fun libraryCoversReadFromOptionalSection() {
        val books = listOf(
            Fixture(SYNTHETIC + 5, "Jetzt reicht's!", libraryIsbn = "9783833911316"),
            Fixture(4, "Emma"),
            Fixture(SYNTHETIC + 9, "Ich war's nicht!", libraryIsbn = "978-3-8387-8656-8"),
        )
        val catalogue = Catalogue("de", listOf(segment(books)), null)
        val straw = catalogue.book(SYNTHETIC + 5)!!.libraryCover
        assertEquals(LibraryCover(DNB, "9783833911316"), straw)
        assertEquals("9783838786568", catalogue.book(SYNTHETIC + 9)!!.libraryCover?.isbn)
        assertNull(catalogue.book(4)!!.libraryCover)
        assertNull(catalogue(BOOKS).book(30)!!.libraryCover)
    }

    @Test
    fun aliasesResolveMovedWorks() {
        val books =
            listOf(Fixture(42, "The Last Straw"), Fixture(SYNTHETIC + 3, "Dog Days"))
        val aliases = listOf(SYNTHETIC + 1 to 42, SYNTHETIC + 2 to SYNTHETIC + 3)
        val catalogue =
            Catalogue("de", listOf(segment(books)), Ranks(ranksBuffer(books, aliases)))
        assertEquals(42, catalogue.book(SYNTHETIC + 1)?.work)
        assertEquals(SYNTHETIC + 3, catalogue.book(SYNTHETIC + 2)?.work)
        assertEquals(42, catalogue.alias(SYNTHETIC + 1))
        assertNull(catalogue.book(SYNTHETIC + 4))
        assertNull(catalogue(BOOKS).alias(30))
    }

    @Test
    fun syntheticAuthorsHaveNoPhoto() {
        assertNull(Author(SYNTHETIC + 13395351, "Jeff Kinney").photoUrl(COVERS, "M"))
        assertEquals(
            "$COVERS/a/olid/OL7A-M.jpg?default=false",
            Author(7, "Jeff Kinney").photoUrl(COVERS, "M"),
        )
    }
}
