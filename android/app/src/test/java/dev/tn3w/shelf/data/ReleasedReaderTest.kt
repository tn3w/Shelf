package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private val PACK = Regex("""([a-z]{2})-([a-z-]+)-\d{4}-\d{2}-\d{2}(?:-\d+)?\.bin""")

private fun mapped(file: File): ByteBuffer = RandomAccessFile(file, "r").use {
    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
}

class ReleasedReaderTest {
    private val output = System.getProperty("shelf.packs").orEmpty()

    @Test
    fun releasedReaderLoadsBuiltPacks() {
        val output = File(output)
        assumeTrue("run with -Pshelf.packs=<builder out dir>", output.path.isNotEmpty())
        val text = File(output, "manifest.json").readText()
        val manifest = json.decodeFromString<Manifest>(text)
        assertEquals(CATALOGUE_FORMAT, manifest.format)
        assertTrue(
            manifest.segments.none {
                it.id.startsWith("links-") || "extras" in it.id
            },
        )
        val packs = output.listFiles().orEmpty().mapNotNull { file ->
            PACK.matchEntire(file.name)?.destructured?.let { (language, pack) ->
                Triple(language, pack, file)
            }
        }
        assertTrue(packs.isNotEmpty())
        packs.groupBy { it.first }.forEach { (language, files) ->
            val segments = files.filter {
                it.second != "ranks"
            }.map { Segment(mapped(it.third)) }
            val ranks = files.firstOrNull {
                it.second == "ranks"
            }?.let { Ranks(mapped(it.third)) }
            val catalogue = Catalogue(language, segments, ranks)
            segments.forEach { segment ->
                (0 until segment.workCount).forEach { local ->
                    val book = catalogue.book(segment.work(local))
                    assertTrue(book?.title.orEmpty().isNotEmpty())
                    segment.description(local)
                }
            }
            val first =
                catalogue.book(segments.first().work(0)) ?: error("no book in $language")
            val found = Searcher(catalogue).search(first.title).map { it.work }
            assertTrue("$language: ${first.title}", first.work in found)
        }
    }
}
