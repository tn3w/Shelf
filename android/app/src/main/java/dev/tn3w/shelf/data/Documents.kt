package dev.tn3w.shelf.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.roundToInt

private const val SECTION_CHARACTERS = 40_000
private const val TITLE_LENGTH = 80
private val CHAPTER_HEADINGS = 1..3
const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
const val EXTRACTED_PREFIX = "comic"
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
private val HEADING = Regex("h([1-6])")
private val FRONT_MATTER =
    Regex("(?<!\\p{L})(copyright|colophon|imprint|toc|contents|titlepage)(?!\\p{L})")
private val WHITESPACE = Regex("\\s+")
private const val JP2_SIGNATURE = "\u0000\u0000\u0000\u000CjP  \r\n\u0087\n"
private const val CHUNK_SIZE = 1 shl 22
private const val ICC_PROFILE_METHOD: Byte = 2
private const val SRGB = 16
private const val GREYSCALE = 17
private val BOLD_TAGS = setOf("b", "strong")
private val ITALIC_TAGS = setOf("i", "em", "cite")
private val IMAGE_TAGS = setOf("img", "image")
private val PARAGRAPH_TAGS = setOf("text:p", "text:h")
private val SWITCHED_OFF = setOf("0", "false", "none")
private val DOCX_HEADING = Regex("heading\\s*(\\d)")
private val WINDOWS_1252 = Charset.forName("windows-1252")
private val COMIC_FORMATS = setOf("cbz", "cbr", "cb7", "cbt")
private val PLAIN_TEXT = setOf("txt", "text", "md", "markdown")
private val HTML = setOf("html", "htm", "xhtml")
private val SIGNATURES = mapOf(
    0 to "PK\u0003\u0004" to "zip",
    0 to "%PDF" to "pdf",
    0 to "Rar!\u001A\u0007" to "rar",
    0 to "7z\u00BC\u00AF\u0027\u001C" to "7z",
    0 to "{\\rtf" to "rtf",
    60 to "BOOKMOBI" to "mobi",
    60 to "TEXtREAd" to "mobi",
    257 to "ustar" to "tar",
)
private val CONTAINERS = mapOf(
    "zip" to setOf("epub", "kepub", "cbz", "fbz", "docx", "odt", "htmlz", "txtz", "zip"),
    "mobi" to setOf("mobi", "azw", "azw3", "kf8", "prc", "pdb"),
    "pdf" to setOf("pdf"),
    "rar" to setOf("cbr"),
    "7z" to setOf("cb7"),
    "tar" to setOf("cbt"),
    "rtf" to setOf("rtf"),
)
private val RTF_TOKEN = Regex(
    """\\([a-zA-Z]+)(-?\d+)? ?|\\'([0-9a-fA-F]{2})|\\([^a-zA-Z])""" +
        """|([{}])|([\r\n]+)|[^\\{}\r\n]+""",
)
private val RTF_SKIPPED = setOf(
    "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object",
    "themedata", "datastore", "listtable", "listoverridetable", "rsidtbl", "xmlnstbl",
    "latentstyles", "fldinst", "generator",
)
private val RTF_WORDS = mapOf(
    "par" to "\n\n", "sect" to "\n\n", "page" to "\n\n", "line" to "\n", "tab" to " ",
    "emdash" to "\u2014", "endash" to "\u2013", "bullet" to "\u2022",
    "lquote" to "\u2018", "rquote" to "\u2019", "ldblquote" to "\u201C",
    "rdblquote" to "\u201D",
)
private val RTF_SYMBOLS =
    mapOf("~" to "\u00A0", "-" to "", "_" to "\u2011", "\n" to "\n\n", "\r" to "\n\n")

val BOOK_FORMATS = listOf("epub", "pdf", "fb2", "cbz", "txt")
val READABLE_EXTENSIONS = CONTAINERS.values.flatten() + "fb2" + HTML + PLAIN_TEXT

data class Span(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

class Block(
    val text: String,
    val heading: Int = 0,
    val centered: Boolean = false,
    val spans: List<Span> = emptyList(),
    val image: String? = null,
)

data class Chapter(val title: String, val section: Int)

sealed interface Document : AutoCloseable {
    override fun close() {}
}

fun interface Resources : AutoCloseable {
    fun bytes(path: String): ByteArray?

    override fun close() {}
}

class TextDocument(
    val sections: List<List<Block>>,
    val chapters: List<Chapter>,
    private val resources: Resources = Resources { null },
) : Document {
    fun image(path: String) = runCatching { resources.bytes(path) }.getOrNull()

    override fun close() = resources.close()
}

interface PagedDocument : Document {
    val pageCount: Int

    fun page(index: Int, width: Int, height: Int): Any
}

class PdfDocument(file: File) : PagedDocument {
    private val descriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer =
        runCatching {
            PdfRenderer(descriptor)
        }.onFailure { descriptor.close() }.getOrThrow()
    override val pageCount = renderer.pageCount

    override fun page(index: Int, width: Int, height: Int): Bitmap =
        synchronized(renderer) {
            renderer.openPage(index).use { page ->
                val (fitWidth, fitHeight) = fit(page.width, page.height, width, height)
                Bitmap.createBitmap(fitWidth, fitHeight, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }

    override fun close() = synchronized(renderer) {
        renderer.close()
        descriptor.close()
    }
}

private class Box(val type: String, val start: Int, val end: Int)

internal fun replaceJpegIccProfiles(file: File) = runCatching {
    RandomAccessFile(file, "rw").use { access ->
        val mode = FileChannel.MapMode.READ_WRITE
        val buffer = access.channel.map(mode, 0, access.length())
        val chunk = ByteArray(CHUNK_SIZE)
        for (offset in 0 until buffer.limit() step CHUNK_SIZE - JP2_SIGNATURE.length) {
            val size = minOf(CHUNK_SIZE, buffer.limit() - offset)
            buffer.position(offset)
            buffer.get(chunk, 0, size)
            val text = String(chunk, 0, size, Charsets.ISO_8859_1)
            var index = text.indexOf(JP2_SIGNATURE)
            while (index >= 0) {
                replaceIccProfile(buffer, offset + index + JP2_SIGNATURE.length)
                index = text.indexOf(JP2_SIGNATURE, index + 1)
            }
        }
    }
}

private fun replaceIccProfile(buffer: ByteBuffer, start: Int) {
    val header = boxes(buffer, start, buffer.limit()).firstOrNull { it.type == "jp2h" }
        ?: return
    val children = boxes(buffer, header.start + 8, header.end).toList()
    val image = children.firstOrNull { it.type == "ihdr" } ?: return
    val color = children.firstOrNull { it.type == "colr" } ?: return
    val isIccProfile = buffer.get(color.start + 8) == ICC_PROFILE_METHOD
    if (!isIccProfile || color.end - color.start < 23) return
    val colorSpace = if (buffer.getShort(image.start + 16) >= 3) SRGB else GREYSCALE
    buffer.position(color.start)
    buffer.putInt(15).put("colr".toByteArray())
    buffer.put(byteArrayOf(1, 0, 0)).putInt(colorSpace)
    buffer.putInt(color.end - buffer.position()).put("free".toByteArray())
}

private fun boxes(buffer: ByteBuffer, start: Int, end: Int) =
    generateSequence(start) { it + buffer.getInt(it) }
        .takeWhile { it + 8 <= end && buffer.getInt(it) in 8..end - it }
        .map {
            val type = ByteArray(4) { index -> buffer.get(it + 4 + index) }
            Box(String(type), it, it + buffer.getInt(it))
        }

class ComicDocument(names: List<String>, private val resources: Resources) :
    PagedDocument {
    private val pages = names.filter(::isComicPage).sortedBy(::naturalSortKey)
    override val pageCount = pages.size

    override fun page(index: Int, width: Int, height: Int) =
        checkNotNull(resources.bytes(pages[index])) { "missing page" }

    override fun close() = resources.close()
}

private class ExtractedPages : Resources {
    private val directory = Files.createTempDirectory(EXTRACTED_PREFIX).toFile()
    private val files = mutableMapOf<String, File>()
    private var remaining = MAX_EXTRACTED_BYTES

    val names
        get() = files.keys.toList()

    fun add(name: String, write: (OutputStream) -> Unit) {
        if (!isComicPage(name)) return
        val file = directory.resolve("${files.size}")
        file.outputStream().use { write(Limited(it)) }
        files[name] = file
    }

    override fun bytes(path: String) =
        files[path]?.takeIf { it.length() <= MAX_ENTRY_BYTES }?.readBytes()

    override fun close() {
        directory.deleteRecursively()
    }

    private inner class Limited(output: OutputStream) : FilterOutputStream(output) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            remaining -= length
            if (remaining < 0) throw IOException("comic too large")
            out.write(bytes, offset, length)
        }

        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)
    }
}

private fun extractComic(read: (ExtractedPages) -> Unit): ComicDocument {
    val pages = ExtractedPages()
    runCatching { read(pages) }.onFailure { pages.close() }.getOrThrow()
    return ComicDocument(pages.names, pages)
}

private fun rarComic(file: File) = extractComic { pages ->
    Archive(file).use { archive ->
        archive.filterNot { it.isDirectory }.forEach { header ->
            pages.add(header.fileName) { archive.extractFile(header, it) }
        }
    }
}

private fun sevenZipComic(file: File) = extractComic { pages ->
    SevenZFile.builder().setFile(file).get().use { archive ->
        archive.entries.filterNot { it.isDirectory }.forEach { entry ->
            pages.add(entry.name) { archive.getInputStream(entry).copyTo(it) }
        }
    }
}

private fun tarComic(file: File) = extractComic { pages ->
    TarArchiveInputStream(file.inputStream().buffered()).use { archive ->
        generateSequence { archive.nextEntry }.filter { it.isFile }.forEach { entry ->
            pages.add(entry.name) { archive.copyTo(it) }
        }
    }
}

private fun isComicPage(name: String): Boolean {
    val file = File(name)
    if (name.startsWith("__MACOSX/") || file.name.startsWith(".")) return false
    return file.extension.lowercase() in IMAGE_EXTENSIONS
}

internal fun naturalSortKey(name: String) =
    name.lowercase().replace(Regex("\\d+")) { it.value.padStart(20, '0') }

private fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Empty page" }
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

data class Metadata(val title: String, val author: String)

internal fun signatureOf(file: File): String? {
    val header = file.inputStream().use { input ->
        ByteArray(512).let { it.copyOf(input.read(it).coerceAtLeast(0)) }
    }
    val text = String(header, Charsets.ISO_8859_1)
    return SIGNATURES.entries.firstOrNull { (signature, _) ->
        text.startsWith(signature.second, signature.first)
    }?.value
}

private fun formatOf(file: File) = signatureOf(file) ?: file.extension.lowercase()

internal fun isFormat(file: File, format: String): Boolean {
    val container = CONTAINERS.entries.firstOrNull { format in it.value } ?: return true
    return signatureOf(file) == container.key
}

fun readMetadata(file: File): Metadata? = runCatching {
    when (formatOf(file)) {
        "zip" -> ZipBook(file).use(::zipMetadata)
        "mobi" -> mobiMetadata(file)
        "fb2" -> fictionBookMetadata(file.inputStream())
        else -> null
    }
}
    .getOrNull()
    ?.takeIf { it.title.isNotBlank() }

private fun zipMetadata(book: ZipBook): Metadata? {
    book.find("fb2")?.let { return book.stream(it)?.let(::fictionBookMetadata) }
    val path = book.opfPath
        ?: book.find("opf")
        ?: listOf("docProps/core.xml", "meta.xml").firstOrNull(book::has)
        ?: return null
    val xml = book.xml(path) ?: return null
    return Metadata(
        xml.selectFirst("dc|title")?.text().orEmpty(),
        xml.selectFirst("dc|creator")?.text().orEmpty(),
    )
}

private fun fictionBookMetadata(input: InputStream): Metadata? {
    val document = input.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    val info = document.selectFirst("title-info") ?: return null
    val author = info.selectFirst("author")?.let { author ->
        author.select("> first-name, > last-name").joinToString(" ") { it.text() }
    }
    return Metadata(info.selectFirst("book-title")?.text().orEmpty(), author.orEmpty())
}

fun openDocument(file: File): Document = when (val format = formatOf(file)) {
    "zip" -> openZip(file)
    "pdf" -> PdfDocument(file.also(::replaceJpegIccProfiles))
    "mobi" -> readMobi(file)
    "rar" -> rarComic(file)
    "7z" -> sevenZipComic(file)
    "tar" -> tarComic(file)
    "rtf" -> textDocument(rtfText(file.readText(Charsets.ISO_8859_1)))
    "fb2" -> readFictionBook(file.inputStream())
    in HTML -> byHeadings(htmlBlocks(Jsoup.parse(file).body()) { null })
    in PLAIN_TEXT -> textDocument(decodeText(file.readBytes()))
    else -> error("unsupported format $format")
}

private fun openZip(file: File): Document {
    val book = ZipBook(file)
    val extension = file.extension.lowercase()
    return runCatching { readZip(book, extension) }
        .onFailure { book.close() }
        .getOrThrow()
}

private fun readZip(book: ZipBook, extension: String): Document {
    if (extension in COMIC_FORMATS) return ComicDocument(book.names, book)
    book.opfPath?.let { return readEpub(book, it) }
    if (book.has("word/document.xml")) return readDocx(book)
    if (book.has("content.xml")) return readOpenDocument(book)
    book.find("fb2")?.let { path ->
        return book.use { readFictionBook(checkNotNull(it.stream(path))) }
    }
    book.find(*HTML.toTypedArray())?.let { return readHtml(book, it) }
    if (book.names.any(::isComicPage)) return ComicDocument(book.names, book)
    book.find(*PLAIN_TEXT.toTypedArray())?.let { path ->
        return book.use { textDocument(decodeText(checkNotNull(it.bytes(path)))) }
    }
    error("no book in archive")
}

internal fun Regex.scan(input: CharSequence) = sequence {
    val matcher = toPattern().matcher(input)
    while (matcher.find()) yield(matcher.toMatchResult())
}

internal fun textDocument(text: String) = byHeadings(plainBlocks(text))

internal fun decodeText(bytes: ByteArray): String = try {
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    text.removePrefix("\uFEFF")
} catch (_: CharacterCodingException) {
    String(bytes, WINDOWS_1252)
}

internal fun assemble(
    parts: List<Pair<String?, List<Block>>>,
    resources: Resources = Resources { null },
): TextDocument {
    val sections = mutableListOf<List<Block>>()
    val chapters = mutableListOf<Chapter>()
    for ((title, blocks) in parts.filter { it.second.isNotEmpty() }) {
        title?.takeIf { it.isNotBlank() }?.let { chapters += Chapter(it, sections.size) }
        sections += split(blocks)
    }
    return TextDocument(sections, chapters, resources)
}

private fun split(blocks: List<Block>): List<List<Block>> {
    val parts = mutableListOf(mutableListOf<Block>())
    var size = 0
    for (block in blocks) {
        if (size > SECTION_CHARACTERS) {
            parts += mutableListOf<Block>()
            size = 0
        }
        parts.last() += block
        size += block.text.length
    }
    return parts
}

internal fun titled(blocks: List<Block>): Pair<String?, List<Block>> {
    val heading = blocks.firstOrNull { it.heading in CHAPTER_HEADINGS }?.text
    val first = blocks.firstOrNull { it.text.isNotBlank() }
    val centered = first?.takeIf { it.centered && it.text.length <= TITLE_LENGTH }?.text
    return (heading ?: centered) to blocks
}

internal fun byHeadings(blocks: List<Block>, resources: Resources = Resources { null }) =
    assemble(headingParts(blocks), resources)

internal fun headingParts(blocks: List<Block>): List<Pair<String?, List<Block>>> {
    val parts = mutableListOf<Pair<String?, List<Block>>>()
    var current = mutableListOf<Block>()
    for (block in blocks) {
        val starts = block.heading in CHAPTER_HEADINGS && current.any { it.heading == 0 }
        if (starts) {
            parts += titled(current)
            current = mutableListOf()
        }
        current += block
    }
    parts += titled(current)
    return parts
}

private fun plainBlocks(text: String) = text
    .split(Regex("\\n\\s*\\n"))
    .map { it.replace(WHITESPACE, " ").trim() }
    .filter { it.isNotEmpty() }
    .map {
        val level = it.takeWhile { character -> character == '#' }.length.coerceAtMost(6)
        Block(it.drop(level).trim(), heading = level, centered = level > 0)
    }

private class HtmlReader(
    private val imagePath: (String) -> String?,
    private val anchors: Set<String> = emptySet(),
) {
    val blocks = mutableListOf<Block>()
    val marks = mutableMapOf<String, Int>()
    private val text = StringBuilder()
    private val spans = mutableListOf<Span>()

    fun read(block: Element, bold: Boolean = false, italic: Boolean = false) {
        block.childNodes().forEach { visit(it, block, bold, italic) }
        flush(block)
    }

    fun inline(element: Element): Pair<String, List<Span>> {
        element.childNodes().forEach { visit(it, element, bold = false, italic = false) }
        return take()
    }

    private fun visit(node: Node, block: Element, bold: Boolean, italic: Boolean) {
        if (node is TextNode) return append(node.wholeText, bold, italic)
        if (node !is Element) return
        if (node.id() in anchors) marks.putIfAbsent(node.id(), blocks.size)
        val tag = node.tagName().lowercase()
        when {
            tag == "br" -> if (text.isNotEmpty()) text.append('\n')

            tag in IMAGE_TAGS -> addImage(node, block)

            node.isBlock -> {
                flush(block)
                read(node, bold, italic)
            }

            else -> node.childNodes().forEach {
                visit(it, block, bold || tag in BOLD_TAGS, italic || tag in ITALIC_TAGS)
            }
        }
    }

    private fun append(words: String, bold: Boolean, italic: Boolean) {
        val normalized = words.replace(WHITESPACE, " ")
        val start = text.length
        val atBreak = text.isEmpty() || text.endsWith(" ") || text.endsWith("\n")
        text.append(if (atBreak) normalized.trimStart() else normalized)
        if ((bold || italic) && text.length > start) {
            spans += Span(start, text.length, bold, italic)
        }
    }

    private fun addImage(element: Element, block: Element) {
        val source = element.attr("src")
            .ifEmpty { element.attr("xlink:href").ifEmpty { element.attr("href") } }
        val path = imagePath(source) ?: return
        flush(block)
        blocks += Block("", image = path)
    }

    private fun flush(block: Element) {
        val (content, styles) = take()
        if (content.isEmpty()) return
        val level = HEADING.matchEntire(block.tagName().lowercase())
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0
        blocks += Block(content, level, level > 0 || isCentered(block), styles)
    }

    private fun take(): Pair<String, List<Span>> {
        val trimmed = text.trimEnd().toString()
        val kept = spans.filter { it.start < trimmed.length }
            .map { it.copy(end = minOf(it.end, trimmed.length)) }
        text.clear()
        spans.clear()
        return trimmed to kept
    }
}

private fun isCentered(element: Element) = generateSequence(element) { it.parent() }
    .take(3)
    .any {
        "center" in it.className() ||
            it.attr("align").equals("center", ignoreCase = true) ||
            "text-align:center" in it.attr("style").replace(" ", "")
    }

internal fun htmlBlocks(root: Element, imagePath: (String) -> String?) =
    HtmlReader(imagePath).apply {
        read(root)
    }.blocks.ifEmpty { plainBlocks(root.wholeText()) }

private fun ZipFile.bytes(entry: ZipEntry): ByteArray {
    if (entry.size > MAX_ENTRY_BYTES) throw IOException("${entry.name} is too large")
    return getInputStream(entry).use { it.readAtMost(MAX_ENTRY_BYTES) }
}

internal fun resolvePath(base: String, href: String): String {
    val link = href.substringBefore('#')
    val decoded = runCatching { URI(link).path }.getOrNull() ?: link
    val path = File(base).resolveSibling(decoded).normalize().invariantSeparatorsPath
    return path.removePrefix("/")
}

private class ZipBook(file: File) : Resources {
    private val zip = ZipFile(file)
    val names = zip.entries().toList().filterNot { it.isDirectory }.map { it.name }

    val opfPath
        get() = xml("META-INF/container.xml")?.selectFirst("rootfile")?.attr("full-path")

    fun has(path: String) = zip.getEntry(path) != null

    fun find(vararg extensions: String) =
        names.firstOrNull { File(it).extension.lowercase() in extensions }

    override fun bytes(path: String) = zip.getEntry(path)?.let(zip::bytes)

    fun stream(path: String) = bytes(path)?.inputStream()

    fun xml(path: String) =
        bytes(path)?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }

    fun tableOfContents(
        items: List<Element>,
        opfPath: String,
    ): List<Pair<String, String>> {
        val nav = items.firstOrNull { "nav" in it.attr("properties").split(" ") }
        val navPath = nav?.let { resolvePath(opfPath, it.attr("href")) }
        val navs = navPath?.let { xml(it) }?.select("nav").orEmpty()
        val toc = navs.firstOrNull { it.attr("epub:type") == "toc" } ?: navs.firstOrNull()
        val links = toc?.select("a[href]").orEmpty()
        if (navPath != null && links.isNotEmpty()) {
            return links.map { target(navPath, it.attr("href")) to it.text() }
        }
        val ncx =
            items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                ?: return emptyList()
        val ncxPath = resolvePath(opfPath, ncx.attr("href"))
        return xml(ncxPath)?.select("navPoint").orEmpty().map { point ->
            val source = point.selectFirst("content")?.attr("src").orEmpty()
            target(ncxPath, source) to
                point.selectFirst("navLabel text")?.text().orEmpty()
        }
    }

    override fun close() = zip.close()
}

private fun target(base: String, href: String): String {
    val fragment = href.substringAfter('#', "")
    return resolvePath(base, href) + if (fragment.isEmpty()) "" else "#$fragment"
}

private fun readEpub(epub: ZipBook, opfPath: String): TextDocument {
    val opf = epub.xml(opfPath) ?: return assemble(emptyList(), epub)
    val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
    val contents = epub.tableOfContents(manifest.values.toList(), opfPath)
    val parts = opf.select("spine > itemref").flatMap { reference ->
        val item = manifest[reference.attr("idref")] ?: return@flatMap emptyList()
        val path = resolvePath(opfPath, item.attr("href"))
        val body =
            epub.bytes(path)?.let { Jsoup.parse(it.inputStream(), null, "").body() }
                ?: return@flatMap emptyList()
        val entries = contents.filter { it.first.substringBefore('#') == path }
            .map { it.first.substringAfter('#', "") to it.second }
        val title = entries.firstOrNull()?.second.orEmpty()
        val hints = "$title ${path.substringAfterLast('/')}".lowercase()
        val isFrontMatter =
            FRONT_MATTER.containsMatchIn(hints) && body.text().length < 3000
        if (isFrontMatter) return@flatMap emptyList()
        anchoredParts(body, entries) { resolvePath(path, it).takeIf(epub::has) }
    }
    return assemble(parts, epub)
}

private fun anchoredParts(
    body: Element,
    entries: List<Pair<String, String>>,
    imagePath: (String) -> String?,
): List<Pair<String?, List<Block>>> {
    val anchors = entries.map { it.first }.filter { it.isNotEmpty() }.toSet()
    val reader = HtmlReader(imagePath, anchors).apply { read(body) }
    val starts = entries
        .map { (anchor, title) ->
            (reader.marks[anchor] ?: 0) to title
        }
        .plus(0 to null)
        .sortedBy { it.first }
        .distinctBy { it.first }
    return starts.mapIndexed { index, (start, title) ->
        val end = starts.getOrNull(index + 1)?.first ?: reader.blocks.size
        title to reader.blocks.subList(start, end)
    }
}

private fun readHtml(book: ZipBook, path: String): TextDocument {
    val body = Jsoup.parse(checkNotNull(book.stream(path)), null, "").body()
    return byHeadings(htmlBlocks(body) { resolvePath(path, it).takeIf(book::has) }, book)
}

private fun headingTag(level: Int) = if (level > 0) "h${level.coerceAtMost(6)}" else "p"

private fun headingLevel(name: String, properties: Element?): Int? {
    val outline = properties?.selectFirst("w|outlineLvl")?.attr("w:val")?.toIntOrNull()
    if (outline != null) return (outline + 1).takeIf { it < 10 }
    if (name.lowercase() == "title") return 1
    return DOCX_HEADING.find(name.lowercase())?.groupValues?.get(1)?.toInt()
}

private fun docxStyles(book: ZipBook) =
    book.xml("word/styles.xml")?.select("w|style").orEmpty().mapNotNull { style ->
        val name = style.selectFirst("w|name")?.attr("w:val").orEmpty()
        headingLevel(name, style.selectFirst("w|pPr"))?.let {
            style.attr("w:styleId") to
                it
        }
    }.toMap()

private fun docxLevel(paragraph: Element, styles: Map<String, Int>): Int {
    val properties = paragraph.selectFirst("w|pPr")
    val style = properties?.selectFirst("w|pStyle")?.attr("w:val").orEmpty()
    return headingLevel("", properties) ?: styles[style] ?: headingLevel(style, null) ?: 0
}

private fun Element.isSwitchedOn(tag: String) =
    selectFirst("w|rPr > $tag")?.attr("w:val")?.let { it !in SWITCHED_OFF } ?: false

private fun readDocx(book: ZipBook): TextDocument {
    val document = checkNotNull(book.xml("word/document.xml"))
    val links = book.xml("word/_rels/document.xml.rels")?.select("Relationship").orEmpty()
        .associate {
            it.attr("Id") to resolvePath("word/document.xml", it.attr("Target"))
        }
    val styles = docxStyles(book)
    val body = Element("body")
    for (paragraph in document.select("w|body w|p")) {
        val block = body.appendElement(headingTag(docxLevel(paragraph, styles)))
        for (run in paragraph.select("w|r")) {
            var target = block
            if (run.isSwitchedOn("w|b")) target = target.appendElement("b")
            if (run.isSwitchedOn("w|i")) target = target.appendElement("i")
            target.appendText(
                run.select("w|t, w|tab, w|br, w|cr")
                    .joinToString("") {
                        if (it.tagName() ==
                            "w:t"
                        ) {
                            it.wholeText()
                        } else {
                            " "
                        }
                    },
            )
            run.select("a|blip").mapNotNull { links[it.attr("r:embed")] }
                .forEach { block.appendElement("img").attr("src", it) }
        }
    }
    return byHeadings(htmlBlocks(body) { it.takeIf(book::has) }, book)
}

private fun readOpenDocument(book: ZipBook): TextDocument {
    val document = checkNotNull(book.xml("content.xml"))
    document.select("office|annotation, text|tracked-changes").remove()
    document.select("text|s, text|tab, text|line-break").forEach {
        it.replaceWith(TextNode(" "))
    }
    val body = Element("body")
    document.select("text|h, text|p")
        .filter { paragraph ->
            paragraph.parents().none { it.tagName() in PARAGRAPH_TAGS }
        }
        .forEach { paragraph ->
            val isHeading = paragraph.tagName() == "text:h"
            val level = paragraph.attr("text:outline-level").toIntOrNull() ?: 1
            body.appendElement(headingTag(if (isHeading) level else 0))
                .appendText(paragraph.wholeText())
            paragraph.select("draw|image")
                .forEach { body.appendElement("img").attr("src", it.attr("xlink:href")) }
        }
    return byHeadings(htmlBlocks(body) { it.takeIf(book::has) }, book)
}

private fun windowsCharacter(hex: String) =
    String(byteArrayOf(hex.toInt(16).toByte()), WINDOWS_1252)

internal fun rtfText(source: String): String {
    val text = StringBuilder()
    val groups = ArrayDeque<Boolean>()
    var skipping = false
    var fallback = 0
    for (match in RTF_TOKEN.scan(source)) {
        val parts = (1..6).map { match.group(it).orEmpty() }
        val (word, argument, hex, symbol, brace) = parts
        val lineBreak = parts[5]
        when {
            brace == "{" -> groups.addLast(skipping)

            brace == "}" -> skipping = groups.removeLastOrNull() ?: false

            skipping -> Unit

            symbol == "*" || word in RTF_SKIPPED -> skipping = true

            word == "u" -> {
                text.append((argument.toIntOrNull() ?: 0).toChar())
                fallback = 1
            }

            word.isNotEmpty() -> text.append(RTF_WORDS[word].orEmpty())

            hex.isNotEmpty() && fallback > 0 -> fallback--

            hex.isNotEmpty() -> text.append(windowsCharacter(hex))

            symbol.isNotEmpty() -> text.append(RTF_SYMBOLS[symbol] ?: symbol)

            lineBreak.isNotEmpty() -> Unit

            else -> {
                text.append(match.group().drop(fallback))
                fallback = 0
            }
        }
    }
    return text.toString()
}

private fun readFictionBook(input: InputStream): TextDocument {
    val document = input.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    val binaries = document.select("binary").associate { it.id() to it.text() }
    val images =
        Resources { path -> binaries[path]?.let { Base64.getMimeDecoder().decode(it) } }
    val body = document.selectFirst("body") ?: return assemble(emptyList())
    val sections =
        body.children().filter { it.tagName() == "section" }.ifEmpty { listOf(body) }
    val parts = sections.map { it.selectFirst("> title")?.text() to fictionBlocks(it) }
    return assemble(parts, images)
}

private fun fictionBlocks(section: Element) =
    section.select("p, v, subtitle, image").mapNotNull { element ->
        if (element.tagName() == "image") {
            val link = element.attributes().firstOrNull { it.key.endsWith("href") }?.value
            return@mapNotNull link?.let { Block("", image = it.removePrefix("#")) }
        }
        val (text, spans) = HtmlReader({ null }).inline(element)
        val isTitle = element.parents().any { it.tagName() == "title" }
        val level = when {
            isTitle -> 1
            element.tagName() == "subtitle" -> 3
            else -> 0
        }
        Block(text, level, level > 0, spans).takeIf { text.isNotEmpty() }
    }
