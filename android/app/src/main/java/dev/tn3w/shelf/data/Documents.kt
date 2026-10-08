package dev.tn3w.shelf.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.ZipFile
import kotlin.math.roundToInt

private const val SECTION_CHARACTERS = 40_000
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
private val HEADING = Regex("h([1-6])")
private val FRONT_MATTER = Regex("copyright|colophon|imprint|toc|contents|titlepage")
private val WHITESPACE = Regex("\\s+")
private const val JP2_SIGNATURE = "\u0000\u0000\u0000\u000CjP  \r\n\u0087\n"
private const val CHUNK_SIZE = 1 shl 22
private const val ICC_PROFILE_METHOD: Byte = 2
private const val SRGB = 16
private const val GREYSCALE = 17
private val BOLD_TAGS = setOf("b", "strong")
private val ITALIC_TAGS = setOf("i", "em", "cite")
private val IMAGE_TAGS = setOf("img", "image")

val READABLE_EXTENSIONS =
    setOf("epub", "pdf", "txt", "md", "html", "htm", "xhtml", "fb2", "cbz")

data class Span(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

class Block(
    val text: String,
    val heading: Int = 0,
    val centered: Boolean = false,
    val spans: List<Span> = emptyList(),
    val image: ByteArray? = null,
)

data class Chapter(val title: String, val section: Int)

sealed interface Document : AutoCloseable {
    override fun close() {}
}

class TextDocument(val sections: List<List<Block>>, val chapters: List<Chapter>) :
    Document

interface PagedDocument : Document {
    val pageCount: Int

    suspend fun render(index: Int, width: Int, height: Int): Bitmap
}

class PdfDocument(file: File) : PagedDocument {
    private val descriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    override val pageCount = renderer.pageCount

    override suspend fun render(index: Int, width: Int, height: Int): Bitmap =
        mutex.withLock {
            renderer.openPage(index).use { page ->
                val (fitWidth, fitHeight) = fit(page.width, page.height, width, height)
                Bitmap.createBitmap(fitWidth, fitHeight, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }

    override fun close() = runBlocking {
        mutex.withLock {
            renderer.close()
            descriptor.close()
        }
    }
}

private class Box(val type: String, val start: Int, val end: Int)

private fun replaceJpegIccProfiles(file: File) = runCatching {
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

    override suspend fun render(index: Int, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(index, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        return decode(index, options) ?: error("Unreadable image ${entries[index].name}")
    }

    private fun decode(index: Int, options: BitmapFactory.Options) =
        zip.getInputStream(entries[index]).use {
            BitmapFactory.decodeStream(it, null, options)
        }

    override fun close() = zip.close()
}

private fun isComicPage(name: String): Boolean {
    val fileName = name.substringAfterLast('/')
    if (name.startsWith("__MACOSX/") || fileName.startsWith(".")) return false
    return fileName.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
}

internal fun naturalSortKey(name: String) =
    name.lowercase().replace(Regex("\\d+")) { it.value.padStart(20, '0') }

private fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Empty page" }
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

private fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    var size = 1
    while (width / (size * 2) >= maxWidth || height / (size * 2) >= maxHeight) size *= 2
    return size
}

fun decodeImage(bytes: ByteArray, width: Int, height: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
    val document = Jsoup.parse(file.readText(), "", Parser.xmlParser())
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

    else -> byHeadings(plainBlocks(file.readText()))
}

private fun assemble(parts: List<Pair<String?, List<Block>>>): TextDocument {
    val sections = mutableListOf<List<Block>>()
    val chapters = mutableListOf<Chapter>()
    for ((title, blocks) in parts.filter { it.second.isNotEmpty() }) {
        title?.takeIf { it.isNotBlank() }?.let { chapters += Chapter(it, sections.size) }
        sections += split(blocks)
    }
    return TextDocument(sections, chapters)
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

private class HtmlReader(private val image: (String) -> ByteArray?) {
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
            tag == "br" -> append(" ", bold = false, italic = false)

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
        text.append(if (text.endsWith(" ")) normalized.trimStart() else normalized)
        if ((bold || italic) && text.length > start) {
            spans += Span(start, text.length, bold, italic)
        }
    }

    private fun addImage(element: Element, block: Element) {
        val source = element.attr("src")
            .ifEmpty { element.attr("xlink:href").ifEmpty { element.attr("href") } }
        val bytes = image(source) ?: return
        flush(block)
        blocks += Block("", image = bytes)
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
        val leading = text.length - text.trimStart().length
        val trimmed = text.trim().toString()
        val shifted = spans.mapNotNull { span ->
            val start = (span.start - leading).coerceIn(0, trimmed.length)
            val end = (span.end - leading).coerceIn(0, trimmed.length)
            if (end > start) span.copy(start = start, end = end) else null
        }
        text.clear()
        spans.clear()
        return trimmed to shifted
    }
}

private fun isCentered(element: Element) = generateSequence(element) { it.parent() }
    .take(3)
    .any {
        "center" in it.className() ||
            "text-align:center" in it.attr("style").replace(" ", "")
    }

private fun htmlBlocks(root: Element, image: (String) -> ByteArray?) =
    HtmlReader(image).apply {
        read(root)
    }.blocks.ifEmpty { plainBlocks(root.wholeText()) }

private class Epub(private val zip: ZipFile) {
    val opfPath
        get() = xml("META-INF/container.xml")?.selectFirst("rootfile")?.attr("full-path")

    fun bytes(path: String) = zip.getEntry(path)?.let { entry ->
        zip.getInputStream(entry).use { it.readBytes() }
    }

    fun xml(path: String) =
        bytes(path)?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }

    fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#').replace(" ", "%20")
        return URI(null, null, "/$base", null).resolve(clean).path.removePrefix("/")
    }

    fun titles(items: List<Element>, opfPath: String): Map<String, String> {
        val nav = items.firstOrNull { "nav" in it.attr("properties").split(" ") }
        val navPath = nav?.let { resolve(opfPath, it.attr("href")) }
        val links = navPath?.let { xml(it) }?.select("nav a[href]").orEmpty()
        if (navPath != null && links.isNotEmpty()) {
            return links.reversed().associate {
                resolve(navPath, it.attr("href")) to it.text()
            }
        }
        val ncx =
            items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                ?: return emptyMap()
        val ncxPath = resolve(opfPath, ncx.attr("href"))
        return xml(ncxPath)?.select("navPoint").orEmpty().reversed().associate { point ->
            val source = point.selectFirst("content")?.attr("src").orEmpty()
            resolve(ncxPath, source) to
                point.selectFirst("navLabel text")?.text().orEmpty()
        }
    }
}

private fun readEpub(file: File): TextDocument = ZipFile(file).use { zip ->
    val epub = Epub(zip)
    val opfPath = epub.opfPath ?: return assemble(emptyList())
    val opf = epub.xml(opfPath) ?: return assemble(emptyList())
    val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
    val titles = epub.titles(manifest.values.toList(), opfPath)
    val parts = opf.select("spine > itemref").mapNotNull { reference ->
        val item = manifest[reference.attr("idref")] ?: return@mapNotNull null
        val path = epub.resolve(opfPath, item.attr("href"))
        val body = epub.bytes(path)?.let { Jsoup.parse(String(it)).body() }
            ?: return@mapNotNull null
        val hints = "${titles[path].orEmpty()} ${path.substringAfterLast('/')}"
            .lowercase()
        val isFrontMatter =
            FRONT_MATTER.containsMatchIn(hints) && body.text().length < 3000
        if (isFrontMatter) return@mapNotNull null
        titles[path] to htmlBlocks(body) { epub.bytes(epub.resolve(path, it)) }
    }
    assemble(parts)
}

private fun readFictionBook(file: File): TextDocument {
    val document = Jsoup.parse(file.readText(), "", Parser.xmlParser())
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
