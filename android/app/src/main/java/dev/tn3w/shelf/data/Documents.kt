package dev.tn3w.shelf.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

private const val SECTION_CHARACTERS = 40_000
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
private val HEADING = Regex("h([1-6])")
private val FRONT_MATTER = Regex("copyright|colophon|imprint|toc|contents|titlepage")
private val WHITESPACE = Regex("\\s+")
private val BOLD_TAGS = setOf("b", "strong")
private val ITALIC_TAGS = setOf("i", "em", "cite")

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

    suspend fun render(index: Int, width: Int): Bitmap?
}

class PdfDocument(file: File) : PagedDocument {
    private val descriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    override val pageCount = renderer.pageCount

    override suspend fun render(index: Int, width: Int): Bitmap = mutex.withLock {
        renderer.openPage(index).use { page ->
            val height = width * page.height / page.width
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

class ComicDocument(file: File) : PagedDocument {
    private val zip = ZipFile(file)
    private val entries =
        zip.entries()
            .asSequence()
            .filter { it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
            .toList()
    override val pageCount = entries.size

    override suspend fun render(index: Int, width: Int): Bitmap? =
        zip.getInputStream(entries[index]).use(BitmapFactory::decodeStream)

    override fun close() = zip.close()
}

fun decodeImage(bytes: ByteArray): Bitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

fun openDocument(file: File): Document =
    when (file.extension.lowercase()) {
        "epub" -> readEpub(file)
        "pdf" -> PdfDocument(file)
        "cbz" -> ComicDocument(file)
        "fb2" -> readFictionBook(file)
        "html",
        "htm",
        "xhtml" -> byHeadings(htmlBlocks(Jsoup.parse(file).body()) { null })
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

private fun plainBlocks(text: String) =
    text
        .split(Regex("\\n\\s*\\n"))
        .map { it.replace(WHITESPACE, " ").trim() }
        .filter { it.isNotEmpty() }
        .map {
            val level =
                it.takeWhile { character -> character == '#' }.length.coerceAtMost(6)
            Block(it.drop(level).trim(), heading = level, centered = level > 0)
        }

private fun inline(element: Element): Pair<String, List<Span>> {
    val text = StringBuilder()
    val spans = mutableListOf<Span>()
    fun walk(node: Node, bold: Boolean, italic: Boolean) {
        if (node is TextNode) {
            val words = node.wholeText.replace(WHITESPACE, " ")
            val start = text.length
            text.append(if (text.endsWith(" ")) words.trimStart() else words)
            if ((bold || italic) && text.length > start) {
                spans += Span(start, text.length, bold, italic)
            }
            return
        }
        if (node !is Element) return
        val tag = node.tagName().lowercase()
        if (tag == "br") text.append(" ")
        node.childNodes().forEach {
            walk(it, bold || tag in BOLD_TAGS, italic || tag in ITALIC_TAGS)
        }
    }
    walk(element, bold = false, italic = false)
    val leading = text.length - text.trimStart().length
    val trimmed = text.trim().toString()
    val shifted = spans.mapNotNull { span ->
        val start = (span.start - leading).coerceIn(0, trimmed.length)
        val end = (span.end - leading).coerceIn(0, trimmed.length)
        if (end > start) span.copy(start = start, end = end) else null
    }
    return trimmed to shifted
}

private fun isCentered(element: Element) =
    generateSequence(element) { it.parent() }
        .take(3)
        .any {
            "center" in it.className() ||
                "text-align:center" in it.attr("style").replace(" ", "")
        }

private fun imageSource(element: Element) =
    element.selectFirst("img, image")?.let {
        it.attr("src").ifEmpty { it.attr("xlink:href").ifEmpty { it.attr("href") } }
    }

private fun htmlBlocks(root: Element, image: (String) -> ByteArray?): List<Block> {
    val blocks =
        root.allElements
            .filter { element ->
                element.isBlock && element.children().none { it.isBlock }
            }
            .mapNotNull { element ->
                val (text, spans) = inline(element)
                if (text.isEmpty()) {
                    return@mapNotNull imageSource(element)?.let(image)?.let {
                        Block("", image = it)
                    }
                }
                val level =
                    HEADING.matchEntire(element.tagName().lowercase())
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: 0
                Block(text, level, level > 0 || isCentered(element), spans)
            }
    return blocks.ifEmpty { plainBlocks(root.wholeText()) }
}

private class Epub(private val zip: ZipFile) {
    fun bytes(path: String) =
        zip.getEntry(path)?.let { entry ->
            zip.getInputStream(entry).use { it.readBytes() }
        }

    fun xml(path: String) =
        bytes(path)?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }

    fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#').replace(" ", "%20")
        return URI("/$base").resolve(clean).path.removePrefix("/")
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

private fun readEpub(file: File): TextDocument =
    ZipFile(file).use { zip ->
        val epub = Epub(zip)
        val opfPath =
            epub.xml("META-INF/container.xml")?.selectFirst("rootfile")?.attr("full-path")
                ?: return assemble(emptyList())
        val opf = epub.xml(opfPath) ?: return assemble(emptyList())
        val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
        val titles = epub.titles(manifest.values.toList(), opfPath)
        val parts =
            opf.select("spine > itemref").mapNotNull { reference ->
                val item = manifest[reference.attr("idref")] ?: return@mapNotNull null
                val path = epub.resolve(opfPath, item.attr("href"))
                val body =
                    epub.bytes(path)?.let { Jsoup.parse(String(it)).body() }
                        ?: return@mapNotNull null
                val hints =
                    "${titles[path].orEmpty()} ${path.substringAfterLast('/')}"
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
            val blocks =
                section.select("p, v, subtitle").mapNotNull { paragraph ->
                    val (text, spans) = inline(paragraph)
                    val isTitle = paragraph.parents().any { it.tagName() == "title" }
                    val level =
                        if (isTitle) 1
                        else if (paragraph.tagName() == "subtitle") 3 else 0
                    Block(text, level, level > 0, spans).takeIf { text.isNotEmpty() }
                }
            title to blocks
        }
    )
}
