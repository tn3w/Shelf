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
}
