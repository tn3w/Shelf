package dev.tn3w.shelf.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.roundToInt

private const val SECTION_CHARACTERS = 40_000
const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
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

val BOOK_FORMATS = listOf("epub", "pdf", "fb2", "cbz", "txt")
val READABLE_EXTENSIONS = BOOK_FORMATS + listOf("md", "html", "htm", "xhtml")

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

class TextDocument(
    val sections: List<List<Block>>,
    val chapters: List<Chapter>,
    private val archive: ZipFile? = null,
) : Document {
    fun image(path: String) =
        runCatching { archive?.run { getEntry(path)?.let { bytes(it) } } }.getOrNull()

    override fun close() {
        archive?.close()
    }
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

class ComicDocument(file: File) : PagedDocument {
    private val zip = ZipFile(file)
    private val entries = zip.entries()
        .asSequence()
        .filter { isComicPage(it.name) }
        .sortedBy { naturalSortKey(it.name) }
        .toList()
    override val pageCount = entries.size

    override fun page(index: Int, width: Int, height: Int) = zip.bytes(entries[index])

    override fun close() = zip.close()
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

fun readMetadata(file: File): Metadata? = runCatching {
    when (file.extension.lowercase()) {
        "epub" -> ZipFile(file).use { epubMetadata(Epub(it)) }
        "fb2" -> fictionBookMetadata(file)
        else -> null
    }
}
    .getOrNull()
    ?.takeIf { it.title.isNotBlank() }

private fun epubMetadata(epub: Epub): Metadata? {
    val opf = epub.opfPath?.let(epub::xml) ?: return null
    return Metadata(
        opf.selectFirst("dc|title")?.text().orEmpty(),
        opf.selectFirst("dc|creator")?.text().orEmpty(),
    )
}

private fun fictionBookMetadata(file: File): Metadata? {
    val document = Jsoup.parse(file, null, "", Parser.xmlParser())
    val info = document.selectFirst("title-info") ?: return null
    val author = info.selectFirst("author")?.let { author ->
        author.select("> first-name, > last-name").joinToString(" ") { it.text() }
    }
    return Metadata(info.selectFirst("book-title")?.text().orEmpty(), author.orEmpty())
}

fun openDocument(file: File): Document = when (file.extension.lowercase()) {
    "epub" -> readEpub(file)

    "pdf" -> PdfDocument(file.also(::replaceJpegIccProfiles))

    "cbz" -> ComicDocument(file)

    "fb2" -> readFictionBook(file)

    "html",
    "htm",
    "xhtml",
    -> byHeadings(htmlBlocks(Jsoup.parse(file).body()) { null })

    else -> byHeadings(plainBlocks(decodeText(file.readBytes())))
}

private fun decodeText(bytes: ByteArray): String = try {
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    text.removePrefix("\uFEFF")
} catch (_: CharacterCodingException) {
    String(bytes, Charset.forName("windows-1252"))
}

private fun assemble(
    parts: List<Pair<String?, List<Block>>>,
    archive: ZipFile? = null,
): TextDocument {
    val sections = mutableListOf<List<Block>>()
    val chapters = mutableListOf<Chapter>()
    for ((title, blocks) in parts.filter { it.second.isNotEmpty() }) {
        title?.takeIf { it.isNotBlank() }?.let { chapters += Chapter(it, sections.size) }
        sections += split(blocks)
    }
    return TextDocument(sections, chapters, archive)
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

private fun titled(blocks: List<Block>) =
    blocks.firstOrNull { it.heading in 1..2 }?.text to blocks

private fun byHeadings(blocks: List<Block>): TextDocument {
    val parts = mutableListOf<Pair<String?, List<Block>>>()
    var current = mutableListOf<Block>()
    for (block in blocks) {
        val starts = block.heading in 1..2 && current.any { it.heading == 0 }
        if (starts) {
            parts += titled(current)
            current = mutableListOf()
        }
        current += block
    }
    parts += titled(current)
    return assemble(parts)
}

private fun plainBlocks(text: String) = text
    .split(Regex("\\n\\s*\\n"))
    .map { it.replace(WHITESPACE, " ").trim() }
    .filter { it.isNotEmpty() }
    .map {
        val level = it.takeWhile { character -> character == '#' }.length.coerceAtMost(6)
        Block(it.drop(level).trim(), heading = level, centered = level > 0)
    }

private class HtmlReader(private val imagePath: (String) -> String?) {
    val blocks = mutableListOf<Block>()
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
            "text-align:center" in it.attr("style").replace(" ", "")
    }

private fun htmlBlocks(root: Element, imagePath: (String) -> String?) =
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

private class Epub(private val zip: ZipFile) {
    val opfPath
        get() = xml("META-INF/container.xml")?.selectFirst("rootfile")?.attr("full-path")

    fun has(path: String) = zip.getEntry(path) != null

    fun bytes(path: String) = zip.getEntry(path)?.let(zip::bytes)

    fun xml(path: String) =
        bytes(path)?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }

    fun titles(items: List<Element>, opfPath: String): Map<String, String> {
        val nav = items.firstOrNull { "nav" in it.attr("properties").split(" ") }
        val navPath = nav?.let { resolvePath(opfPath, it.attr("href")) }
        val links = navPath?.let { xml(it) }?.select("nav a[href]").orEmpty()
        if (navPath != null && links.isNotEmpty()) {
            return links.reversed().associate {
                resolvePath(navPath, it.attr("href")) to it.text()
            }
        }
        val ncx =
            items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                ?: return emptyMap()
        val ncxPath = resolvePath(opfPath, ncx.attr("href"))
        return xml(ncxPath)?.select("navPoint").orEmpty().reversed().associate { point ->
            val source = point.selectFirst("content")?.attr("src").orEmpty()
            resolvePath(ncxPath, source) to
                point.selectFirst("navLabel text")?.text().orEmpty()
        }
    }
}

private fun readEpub(file: File): TextDocument {
    val zip = ZipFile(file)
    return runCatching { readEpub(zip) }.onFailure { zip.close() }.getOrThrow()
}

private fun readEpub(zip: ZipFile): TextDocument {
    val epub = Epub(zip)
    val opfPath = epub.opfPath ?: return assemble(emptyList(), zip)
    val opf = epub.xml(opfPath) ?: return assemble(emptyList(), zip)
    val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
    val titles = epub.titles(manifest.values.toList(), opfPath)
    val parts = opf.select("spine > itemref").mapNotNull { reference ->
        val item = manifest[reference.attr("idref")] ?: return@mapNotNull null
        val path = resolvePath(opfPath, item.attr("href"))
        val body =
            epub.bytes(path)?.let { Jsoup.parse(it.inputStream(), null, "").body() }
                ?: return@mapNotNull null
        val hints = "${titles[path].orEmpty()} ${path.substringAfterLast('/')}"
            .lowercase()
        val isFrontMatter =
            FRONT_MATTER.containsMatchIn(hints) && body.text().length < 3000
        if (isFrontMatter) return@mapNotNull null
        titles[path] to htmlBlocks(body) { resolvePath(path, it).takeIf(epub::has) }
    }
    return assemble(parts, zip)
}

private fun readFictionBook(file: File): TextDocument {
    val document = Jsoup.parse(file, null, "", Parser.xmlParser())
    val body = document.selectFirst("body") ?: return assemble(emptyList())
    val sections =
        body.children().filter { it.tagName() == "section" }.ifEmpty { listOf(body) }
    return assemble(
        sections.map { section ->
            val title = section.selectFirst("> title")?.text()
            val blocks = section.select("p, v, subtitle").mapNotNull { paragraph ->
                val (text, spans) = HtmlReader { null }.inline(paragraph)
                val isTitle = paragraph.parents().any { it.tagName() == "title" }
                val level = when {
                    isTitle -> 1
                    paragraph.tagName() == "subtitle" -> 3
                    else -> 0
                }
                Block(text, level, level > 0, spans).takeIf { text.isNotEmpty() }
            }
            title to blocks
        },
    )
}
