package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val FIRST_IMAGE = byteArrayOf(1, 2, 3)
private val SECOND_IMAGE = byteArrayOf(4, 5, 6)

private fun zip(file: File, entries: Map<String, ByteArray>) = file.apply {
    ZipOutputStream(outputStream()).use { archive ->
        entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name))
            archive.write(bytes)
            archive.closeEntry()
        }
    }
}

private fun epub(file: File, body: String, chapter: String = "chapter") = zip(
    file,
    mapOf(
        "META-INF/container.xml" to """
            <container><rootfiles>
              <rootfile full-path="OEBPS/content.opf"/>
            </rootfiles></container>
        """.toByteArray(),
        "OEBPS/content.opf" to """
            <package>
              <manifest><item id="one" href="text/$chapter.xhtml"/></manifest>
              <spine><itemref idref="one"/></spine>
            </package>
        """.toByteArray(),
        "OEBPS/text/$chapter.xhtml" to "<html><body>$body</body></html>".toByteArray(),
        "OEBPS/images/first.png" to FIRST_IMAGE,
        "OEBPS/images/second.png" to SECOND_IMAGE,
    ),
)

private fun blocks(document: Document) = (document as TextDocument).sections.flatten()

class DocumentTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun epubKeepsImagesBesideAndInsideParagraphs() {
        val body = """
            <h1>Chapter One</h1>
            <p>Before</p>
            <img src="../images/first.png"/>
            <p>Middle <img src="../images/second.png"/> after</p>
            <p>End</p>
        """
        val blocks = blocks(openDocument(epub(folder.newFile("book.epub"), body)))
        assertEquals(
            listOf("Chapter One", "Before", "", "Middle", "", "after", "End"),
            blocks.map { it.text },
        )
        assertEquals(1, blocks[0].heading)
        assertNull(blocks[1].image)
        assertArrayEquals(FIRST_IMAGE, blocks[2].image)
        assertArrayEquals(SECOND_IMAGE, blocks[4].image)
    }

    @Test
    fun epubKeepsInlineStyles() {
        val body = "<p>Plain <b>bold</b> and <em>soft</em></p>"
        val file = epub(folder.newFile("styled.epub"), body)
        val block = blocks(openDocument(file)).single()
        assertEquals("Plain bold and soft", block.text)
        assertEquals(
            listOf(Span(6, 10, bold = true, italic = false), Span(15, 19, false, true)),
            block.spans,
        )
    }

    @Test
    fun epubSkipsFrontMatterByWholeWordOnly() {
        val body = "<p>Short</p>"
        val skipped = epub(folder.newFile("toc.epub"), body, chapter = "toc")
        val kept = epub(folder.newFile("stockholm.epub"), body, chapter = "stockholm")
        assertEquals(emptyList<Block>(), blocks(openDocument(skipped)))
        assertEquals(listOf("Short"), blocks(openDocument(kept)).map { it.text })
    }

    @Test
    fun plainTextSplitsParagraphsAndHeadings() {
        val file = folder.newFile("notes.txt")
        file.writeText("# Title\n\nFirst   line\nwraps.\n\n\nSecond.")
        val document = openDocument(file) as TextDocument
        assertEquals(
            listOf("Title", "First line wraps.", "Second."),
            blocks(document).map { it.text },
        )
        assertEquals(listOf(Chapter("Title", 0)), document.chapters)
    }

    @Test
    fun plainTextFallsBackToLatinWhenNotUtf8() {
        val file = folder.newFile("latin.txt")
        file.writeText("Café über", Charsets.ISO_8859_1)
        assertEquals("Café über", blocks(openDocument(file)).single().text)
    }

    @Test
    fun plainTextDropsUtf8ByteOrderMark() {
        val file = folder.newFile("marked.txt")
        file.writeText("\uFEFFCafé")
        assertEquals("Café", blocks(openDocument(file)).single().text)
    }

    @Test
    fun fictionBookHonoursDeclaredEncoding() {
        val file = folder.newFile("russian.fb2")
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <FictionBook><body><section><p>Привет мир</p></section></body></FictionBook>
        """.trimIndent()
        file.writeText(xml, charset("windows-1251"))
        assertEquals("Привет мир", blocks(openDocument(file)).single().text)
    }

    @Test
    fun comicSkipsMacMetadataAndHiddenFiles() {
        val image = byteArrayOf(0)
        val file = zip(
            folder.newFile("comic.cbz"),
            mapOf(
                "page1.jpg" to image,
                "page2.png" to image,
                "__MACOSX/._page1.jpg" to image,
                ".hidden.jpg" to image,
                "notes.txt" to image,
            ),
        )
        ComicDocument(file).use { assertEquals(2, it.pageCount) }
    }

    @Test
    fun comicPagesSortNaturally() {
        val names = listOf("page10.jpg", "Page2.jpg", "page1.jpg", "b/page1.jpg")
        assertEquals(
            listOf("b/page1.jpg", "page1.jpg", "Page2.jpg", "page10.jpg"),
            names.sortedBy(::naturalSortKey),
        )
    }
}
