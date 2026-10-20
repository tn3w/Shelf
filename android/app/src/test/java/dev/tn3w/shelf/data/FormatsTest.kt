package dev.tn3w.shelf.data

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

private val IMAGE = byteArrayOf(1, 2, 3)
private val PAGES = mapOf("b.jpg" to byteArrayOf(2), "a.png" to byteArrayOf(1))
private val META = mapOf(100 to "Ann Author", 503 to "Night Train")
private val EXPECTED = Metadata("Night Train", "Ann Author")
private val PALM_DOC = byteArrayOf(
    'a'.code.toByte(), 'b'.code.toByte(), 0x80.toByte(), 0x10,
    0xE3.toByte(), 2, 'x'.code.toByte(), 'y'.code.toByte(),
)
private val TRAILING = byteArrayOf(0xAA.toByte(), 1, 0, 0, 0x83.toByte())

private fun palmDatabase(file: File, type: String, records: List<ByteArray>) =
    file.apply {
        val table = ByteBuffer.allocate(78 + records.size * 8)
        table.put("Palm Name".toByteArray())
        table.position(60)
        table.put(type.toByteArray())
        table.putShort(76, records.size.toShort())
        table.position(78)
        var offset = table.capacity()
        records.forEach {
            table.putInt(offset).putInt(0)
            offset += it.size
        }
        writeBytes(records.fold(table.array(), ByteArray::plus))
    }

private fun palmDocHeader(compression: Int, textLength: Int, encryption: Int = 0) =
    ByteBuffer.allocate(16)
        .putShort(0, compression.toShort())
        .putInt(4, textLength)
        .putShort(8, 1)
        .putShort(10, 4096)
        .putShort(12, encryption.toShort())
        .array()

private fun mobiHeader(
    textLength: Int,
    version: Int,
    firstImage: Int,
    fdst: Int = -1,
    exth: Map<Int, ByteArray> = emptyMap(),
): ByteArray {
    val records = exth.map { (type, value) ->
        ByteBuffer.allocate(8 + value.size).putInt(type).putInt(8 + value.size).put(value)
            .array()
    }
    val exthLength = 12 + records.sumOf { it.size }
    val buffer = ByteBuffer.allocate(248 + exthLength)
    buffer.put(palmDocHeader(1, textLength))
    buffer.put("MOBI".toByteArray()).putInt(232)
    buffer.putInt(28, 65001).putInt(36, version).putInt(108, firstImage)
    buffer.putInt(128, 0x40).putInt(192, fdst).putShort(242, 3)
    buffer.position(248)
    buffer.put("EXTH".toByteArray()).putInt(exthLength).putInt(records.size)
    records.forEach { buffer.put(it) }
    return buffer.array()
}

private fun textRecord(text: String) = text.toByteArray() + TRAILING

private fun strings(values: Map<Int, String>) = values.mapValues {
    it.value.toByteArray()
}

private fun kf8Records(text: String, css: String): List<ByteArray> {
    val end = text.length + css.length
    val fdst = ByteBuffer.allocate(28).put("FDST".toByteArray()).putInt(12).putInt(2)
        .putInt(0).putInt(text.length).putInt(text.length).putInt(end)
        .array()
    val header = mobiHeader(end, 8, 2, fdst = 3, strings(META))
    return listOf(header, textRecord(text + css), IMAGE, fdst)
}

private fun tar(file: File, entries: Map<String, ByteArray>) = file.apply {
    TarArchiveOutputStream(outputStream()).use { archive ->
        entries.forEach { (name, bytes) ->
            archive.putArchiveEntry(
                TarArchiveEntry(name).apply {
                    size =
                        bytes.size.toLong()
                },
            )
            archive.write(bytes)
            archive.closeArchiveEntry()
        }
    }
}

private fun sevenZip(file: File, entries: Map<String, ByteArray>) = file.apply {
    SevenZOutputFile(this).use { archive ->
        entries.forEach { (name, bytes) ->
            archive.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name })
            archive.write(bytes)
            archive.closeArchiveEntry()
        }
    }
}

private fun mobi(file: File, html: String) = palmDatabase(
    file,
    "BOOKMOBI",
    listOf(mobiHeader(html.length, 6, -1), textRecord(html)),
)

private fun chapters(document: Document) = (document as TextDocument).chapters

private fun text(document: Document) =
    (document as TextDocument).sections.flatten().map { it.text }

class FormatsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun palmDocDecompressesLiteralsPairsAndSpaces() {
        assertEquals("ababa cxy", decompressPalmDoc(PALM_DOC).decodeToString())
    }

    @Test
    fun palmDocTextOpens() {
        val records = listOf(palmDocHeader(2, 9), PALM_DOC)
        val file = palmDatabase(folder.newFile("notes.pdb"), "TEXtREAd", records)
        assertEquals(listOf("ababa cxy"), text(openDocument(file)))
    }

    @Test
    fun mobiReadsHtmlImagesAndMetadata() {
        val html = "<html><body><h1>One</h1><p>First <b>bold</b></p>" +
            "<img recindex=\"00001\"/><mbp:pagebreak/>" +
            "<h1>Two</h1><p>Second</p></body></html>"
        val header = mobiHeader(html.length, 6, 2, exth = strings(META))
        val records = listOf(header, textRecord(html), IMAGE)
        val file = palmDatabase(folder.newFile("book.mobi"), "BOOKMOBI", records)
        val document = openDocument(file) as TextDocument
        val blocks = document.sections.flatten()
        val expected = listOf("One", "First bold", "", "Two", "Second")
        assertEquals(expected, blocks.map { it.text })
        assertEquals(listOf(Chapter("One", 0), Chapter("Two", 1)), document.chapters)
        assertArrayEquals(IMAGE, document.image(blocks[2].image!!))
        assertEquals(EXPECTED, readMetadata(file))
        document.close()
    }

    @Test
    fun kf8SplitsFilesAndSkipsStyleFlows() {
        val text = "<html><head><title>x</title></head><body></body></html>" +
            "<div><h1>Alpha</h1><p>Text</p>" +
            "<img src=\"kindle:embed:0001?mime=image/png\"/></div><html><body></body></html><h2>Beta</h2><p>More</p>"
        val records = kf8Records(text, "p { color: red }")
        val file = palmDatabase(folder.newFile("book.azw3"), "BOOKMOBI", records)
        val document = openDocument(file) as TextDocument
        val blocks = document.sections.flatten()
        assertEquals(listOf("Alpha", "Text", "", "Beta", "More"), blocks.map { it.text })
        assertEquals(listOf(Chapter("Alpha", 0), Chapter("Beta", 1)), document.chapters)
        assertArrayEquals(IMAGE, document.image(blocks[2].image!!))
        assertEquals(EXPECTED, readMetadata(file))
    }

    @Test
    fun jointMobiPrefersKf8Part() {
        val old = "<html><body><p>Old</p></body></html>"
        val kf8 = kf8Records("<html><body></body></html><p>New</p>", "")
        val boundary = mapOf(121 to ByteBuffer.allocate(4).putInt(3).array())
        val first = mobiHeader(old.length, 6, 2, exth = boundary)
        val records = listOf(first, textRecord(old), IMAGE) + kf8.take(2)
        val file = palmDatabase(folder.newFile("joint.mobi"), "BOOKMOBI", records)
        assertEquals(listOf("New"), text(openDocument(file)))
    }

    @Test
    fun encryptedMobiFails() {
        val records = listOf(palmDocHeader(1, 1, encryption = 2), "x".toByteArray())
        val file = palmDatabase(folder.newFile("locked.azw"), "BOOKMOBI", records)
        assertThrows(IllegalStateException::class.java) { openDocument(file) }
    }

    @Test(timeout = 5000)
    fun huffCodeWithZeroLengthFails() {
        val huff = ByteBuffer.allocate(24 + 1024 + 256).put("HUFF".toByteArray())
            .putInt(8, 24).putInt(12, 24 + 1024).array()
        val dictionary = ByteBuffer.allocate(20).put("CDIC".toByteArray())
            .putInt(8, 1).putShort(16, 2).putShort(18, 0x8000.toShort()).array()
        val decoder = HuffDecoder(huff, listOf(dictionary))
        assertThrows(IllegalStateException::class.java) {
            decoder.unpack(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun rtfKeepsTextAndSkipsTables() {
        val file = folder.newFile("story.rtf")
        file.writeText(
            "{\\rtf1\\ansi{\\fonttbl{\\f0 Times;}}{\\*\\generator Word;}" +
                "\\f0 Caf\\'e9 \\b bold\\b0\\par\nEuro \\u8364?\\par}",
        )
        assertEquals(listOf("Café bold", "Euro €"), text(openDocument(file)))
    }

    @Test
    fun docxReadsHeadingsStylesImagesAndMetadata() {
        val document = """
            <w:document><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Start</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Plain </w:t></w:r>
              <w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r>
              <w:r><w:rPr><w:i w:val="0"/></w:rPr><w:t xml:space="preserve"> end</w:t></w:r></w:p>
            <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
            </w:body></w:document>
        """
        val relations = """<Relationships>
            <Relationship Id="rId1" Target="media/one.png"/></Relationships>"""
        val core = "<cp:coreProperties><dc:title>Night Train</dc:title>" +
            "<dc:creator>Ann Author</dc:creator></cp:coreProperties>"
        val file = zip(
            folder.newFile("paper.docx"),
            mapOf(
                "word/document.xml" to document.toByteArray(),
                "word/_rels/document.xml.rels" to relations.toByteArray(),
                "word/media/one.png" to IMAGE,
                "docProps/core.xml" to core.toByteArray(),
            ),
        )
        val opened = openDocument(file) as TextDocument
        val blocks = opened.sections.flatten()
        assertEquals(listOf("Start", "Plain bold end", ""), blocks.map { it.text })
        assertEquals(1, blocks[0].heading)
        assertEquals(listOf(Span(6, 10, bold = true, italic = false)), blocks[1].spans)
        assertArrayEquals(IMAGE, opened.image(blocks[2].image!!))
        assertEquals(EXPECTED, readMetadata(file))
    }

    @Test
    fun openDocumentReadsHeadingsListsAndImages() {
        val content = """
            <office:document-content><office:body><office:text>
            <text:h text:outline-level="2">Heading</text:h>
            <text:p>One<text:tab/>two<text:line-break/>three</text:p>
            <text:list><text:list-item><text:p>Item</text:p></text:list-item></text:list>
            <text:p><draw:frame><draw:image xlink:href="Pictures/a.png"/></draw:frame></text:p>
            </office:text></office:body></office:document-content>
        """
        val file = zip(
            folder.newFile("essay.odt"),
            mapOf("content.xml" to content.toByteArray(), "Pictures/a.png" to IMAGE),
        )
        val opened = openDocument(file) as TextDocument
        val blocks = opened.sections.flatten()
        assertEquals(
            listOf("Heading", "One two three", "Item", ""),
            blocks.map {
                it.text
            },
        )
        assertEquals(2, blocks[0].heading)
        assertArrayEquals(IMAGE, opened.image(blocks[3].image!!))
    }

    @Test
    fun zippedFictionBookKeepsImages() {
        val fictionBook = """
            <FictionBook><description><title-info>
              <author><first-name>Ann</first-name><last-name>Author</last-name></author>
              <book-title>Night Train</book-title>
            </title-info></description>
            <body><section><title><p>One</p></title><p>Text</p>
              <image l:href="#pic.png"/></section></body>
            <binary id="pic.png" content-type="image/png">AQID</binary></FictionBook>
        """
        val entries = mapOf("book.fb2" to fictionBook.toByteArray())
        listOf("book.fbz", "book.fb2.zip").forEach { name ->
            val file = zip(folder.newFile(name), entries)
            val document = openDocument(file) as TextDocument
            val blocks = document.sections.flatten()
            assertEquals(listOf("One", "Text", ""), blocks.map { it.text })
            assertArrayEquals(IMAGE, document.image(blocks[2].image!!))
            assertEquals(EXPECTED, readMetadata(file))
        }
    }

    @Test
    fun comicsOpenFromEveryArchive() {
        val files = listOf(
            zip(folder.newFile("zipped.cbr"), PAGES),
            tar(folder.newFile("comic.cbt"), PAGES + ("notes.txt" to IMAGE)),
            sevenZip(folder.newFile("comic.cb7"), PAGES),
        )
        files.forEach { file ->
            (openDocument(file) as PagedDocument).use {
                assertEquals(2, it.pageCount)
                val page = it.page(0, 1, 1)
                val bytes = if (page is File) page.readBytes() else page as ByteArray
                assertArrayEquals(PAGES["a.png"], bytes)
                assertEquals(file.extension != "cbr", page is File)
            }
        }
    }

    @Test
    fun downloadsMustMatchTheirContainer() {
        val records = listOf(palmDocHeader(1, 1), "x".toByteArray())
        val mobi = palmDatabase(folder.newFile("book.bin"), "BOOKMOBI", records)
        assertTrue(isFormat(mobi, "azw3"))
        assertFalse(isFormat(mobi, "epub"))
        assertTrue(isFormat(mobi, "fb2"))
    }

    @Test
    fun mobiChaptersFollowGuideTableOfContents() {
        val head = "<html><head><guide><reference type=\"toc\" filepos=%010d />" +
            "</guide></head><body><p>Front</p>"
        val first = "<mbp:pagebreak/><p>Alpha text</p>"
        val second = "<mbp:pagebreak/><p>Beta text</p>"
        val contents = "<mbp:pagebreak/><p><a filepos=%010d>Alpha</a></p>" +
            "<p><a filepos=%010d>Beta</a></p></body></html>"
        val alpha = head.format(0).length
        val beta = alpha + first.length
        val html = head.format(beta + second.length) + first + second +
            contents.format(alpha, beta)
        val document = openDocument(mobi(folder.newFile("guide.mobi"), html))
        assertEquals(listOf(Chapter("Alpha", 1), Chapter("Beta", 2)), chapters(document))
    }

    @Test
    fun mobiWithoutContentsSplitsAtPageBreaksWithCenteredTitles() {
        val html = "<html><body><p>Front</p><mbp:pagebreak/>" +
            "<p align=\"center\"><b>Alpha</b></p><p>Text</p><mbp:pagebreak/>" +
            "<p align=\"center\"><b>Beta</b></p><p>More</p></body></html>"
        val document = openDocument(mobi(folder.newFile("breaks.mobi"), html))
        assertEquals(listOf(Chapter("Alpha", 1), Chapter("Beta", 2)), chapters(document))
    }

    @Test
    fun palmDocHoldingHtmlRendersMarkup() {
        val html = "<HTML><BODY><h1>Title</h1><p>Body</p>" +
            "<img recindex=\"1\"/></BODY></HTML>"
        val records = listOf(palmDocHeader(1, html.length), html.toByteArray())
        val file = palmDatabase(folder.newFile("markup.pdb"), "TEXtREAd", records)
        assertEquals(listOf("Title", "Body"), text(openDocument(file)))
    }

    @Test
    fun kf8ChaptersSplitAtThirdLevelHeadings() {
        val html = "<html><body></body></html><h2>Book</h2><h3>One</h3><p>A</p>" +
            "<h3>Two</h3><p>B</p>"
        val file =
            palmDatabase(folder.newFile("levels.azw3"), "BOOKMOBI", kf8Records(html, ""))
        val expected = listOf(Chapter("Book", 0), Chapter("Two", 1))
        assertEquals(expected, chapters(openDocument(file)))
    }

    @Test
    fun docxHeadingsFollowLocalizedStyleNames() {
        val styles = "<w:styles><w:style w:styleId=\"Titre1\">" +
            "<w:name w:val=\"heading 1\"/></w:style></w:styles>"
        val document = "<w:document><w:body><w:p><w:pPr><w:pStyle w:val=\"Titre1\"/>" +
            "</w:pPr><w:r><w:t>Chapitre</w:t></w:r></w:p></w:body></w:document>"
        val file = zip(
            folder.newFile("french.docx"),
            mapOf("word/document.xml" to document, "word/styles.xml" to styles)
                .mapValues { it.value.toByteArray() },
        )
        val block = (openDocument(file) as TextDocument).sections.flatten().single()
        assertEquals(1, block.heading)
    }

    @Test
    fun openDocumentSkipsAnnotations() {
        val content =
            "<office:text><text:h text:outline-level=\"1\"><office:annotation>" +
                "<dc:date>2026-01-01</dc:date><text:p>Note</text:p></office:annotation>" +
                "Heading</text:h><text:p>Body</text:p></office:text>"
        val entries = mapOf("content.xml" to content.toByteArray())
        val file = zip(folder.newFile("notes.odt"), entries)
        assertEquals(listOf("Heading", "Body"), text(openDocument(file)))
    }

    @Test
    fun scanFindsSameMatchesAsFindAll() {
        val regex = Regex("(a)?b")
        val input = "abxbab"
        val scanned = regex.scan(input).map {
            it.start() to it.group(1).orEmpty()
        }.toList()
        val expected = regex.findAll(input).map {
            it.range.first to it.groupValues[1]
        }.toList()
        assertEquals(expected, scanned)
    }
}
