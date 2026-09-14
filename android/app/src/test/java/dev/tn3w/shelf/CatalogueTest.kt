package dev.tn3w.shelf

import dev.tn3w.shelf.data.Catalogue
import dev.tn3w.shelf.data.Ranks
import dev.tn3w.shelf.data.Searcher
import dev.tn3w.shelf.data.Segment
import dev.tn3w.shelf.data.mapFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {
    private val directory = File(System.getProperty("catalogue") ?: "build/testCatalogue")

    private fun segment(name: String) =
        Segment(name, mapFile(directory.resolve("$name.bin")))

    private fun catalogue(language: String): Catalogue {
        val files =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("$language-") && "ranks" !in it.name }
                .sortedBy { it.name }
        val ranks =
            Ranks("ranks", mapFile(directory.resolve("$language-ranks-2026-09.bin")))
        return Catalogue(language, files.map { segment(it.nameWithoutExtension) }, ranks)
    }

    @Test
    fun germanCoreHasPhilosophersStone() {
        val core = Catalogue("de", listOf(segment("de-core-2026-09")), null)
        val titles =
            (0 until core.segments[0].workCount).map { core.segments[0].heads(it).title }
        assertTrue("Harry Potter und der Stein der Weisen" in titles)
    }

    @Test
    fun englishHarryPotterSeriesInOrderAndTypoSearch() {
        val english = catalogue("en")
        val searcher = Searcher(english)
        val stone = searcher.search("harry potter philosopher").first()
        val series = requireNotNull(english.series(stone.work))
        val titles = english.books(series.members).map { it.title }
        assertEquals(7, titles.size)
        assertTrue(titles[1].contains("Chamber of Secrets"))
        assertTrue(titles[6].contains("Deathly Hallows"))

        val thief = searcher.search("lightnig thief").take(3).map { it.title }
        assertTrue(thief.toString(), thief.any { it.contains("Lightning Thief") })
    }

    @Test
    fun deltaTombstoneHidesWork() {
        val base = segment("de-core-2026-09")
        val hidden = base.work(0)
        val delta = Segment("de-core-2026-10", tombstoneSegment(hidden))
        val merged = Catalogue("de", listOf(base, delta), null)
        assertNull(merged.book(hidden))
        assertNotNull(merged.book(base.work(1)))
        assertTrue(merged.tags.indices.none { hidden in merged.tagWorks(it) })
    }

    private fun tombstoneSegment(work: Int): ByteBuffer {
        val meta =
            listOf(
                    "format=1",
                    "language=de",
                    "pack=core",
                    "month=2026-10",
                    "base=2026-09",
                    "works=0",
                    "authors=0",
                    "terms=0",
                    "records_per_block=32",
                    "terms_per_block=16",
                )
                .joinToString("\n", postfix = "\n")
                .toByteArray()
        val emptyTable = littleEndian(0, 0)
        val sections =
            listOf(
                "meta" to meta,
                "works" to ByteArray(0),
                "tombstones" to littleEndian(work),
                "head_dictionary" to ByteArray(0),
                "text_dictionary" to ByteArray(0),
                "facts" to emptyTable,
                "heads" to emptyTable,
                "authors" to emptyTable,
                "tags" to emptyTable,
                "series" to emptyTable,
                "terms" to emptyTable,
                "completions" to emptyTable,
                "grams" to emptyTable,
                "descriptions" to littleEndian(0) + emptyTable,
            )
        val header = 12 + sections.size * 24
        val buffer =
            ByteBuffer.allocate(header + sections.sumOf { it.second.size })
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("SHLF".toByteArray()).putInt(1).putInt(sections.size)
        var offset = header
        for ((name, bytes) in sections) {
            buffer.put(name.toByteArray().copyOf(16)).putInt(offset).putInt(bytes.size)
            offset += bytes.size
        }
        sections.forEach { buffer.put(it.second) }
        return ByteBuffer.wrap(buffer.array())
    }

    private fun littleEndian(vararg values: Int): ByteArray =
        ByteBuffer.allocate(4 * values.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach(::putInt) }
            .array()
}
