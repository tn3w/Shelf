package dev.tn3w.shelf.data

import androidx.annotation.StringRes
import dev.tn3w.shelf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val OFFER_LIMIT = 5
private const val CANDIDATE_LIMIT = 8
const val MAX_BOOK_BYTES = 200L * 1024 * 1024
const val DOWNLOAD_MINUTES = 2L
val downloadClient by lazy {
    http.newBuilder().readTimeout(DOWNLOAD_MINUTES, TimeUnit.MINUTES).build()
}
private const val EPUB_TYPE = "application/epub+zip"

data class Query(
    val title: String,
    val author: String,
    val language: String,
    val isbns: List<String> = emptyList(),
) {
    val words
        get() = "${mainTitle(title).trim()} $author".trim()
}

data class Offer(
    val provider: Provider,
    val title: String,
    val author: String,
    val link: String,
    val cover: String? = null,
    val bytes: Long = 0,
    val format: String = "epub",
    val variables: Map<String, String> = emptyMap(),
)

interface Provider {
    val id: String
    val name: String
    val site: String

    @get:StringRes
    val about: Int
    val languages: Set<String>
        get() = emptySet()
    val exact: Boolean
        get() = false

    fun find(query: Query): List<Offer>

    fun complete(offer: Offer): Offer? = offer

    fun open(offer: Offer) = downloadClient.fetch(offer.link)
}

abstract class FeedProvider : Provider {
    abstract fun searchUrl(query: Query): String

    abstract fun offers(body: String, query: Query): List<Offer>

    open fun detailsUrl(offer: Offer): String? = null

    open fun details(offer: Offer, body: String): Offer? = offer

    override fun find(query: Query) = offers(fetchText(searchUrl(query), "*/*"), query)

    override fun complete(offer: Offer): Offer? {
        val url = detailsUrl(offer) ?: return offer
        return details(offer, fetchText(url, "*/*"))
    }
}

val PROVIDERS = listOf(StandardEbooks, Gutenberg, Wikisource, InternetArchive)

fun allProviders(own: List<Recipe>) = PROVIDERS + own.map(::OwnSource)

fun Settings.enabledProviders(own: List<Recipe> = emptyList()) =
    allProviders(own).filter { it.id in providers }

fun Settings.instantProvider(own: List<Recipe> = emptyList()) =
    allProviders(own).firstOrNull { it.id == instantSource }

fun Provider.serves(language: String) = languages.isEmpty() || language in languages

suspend fun Provider.search(query: Query) = withContext(Dispatchers.IO) {
    rank(query, find(query), CANDIDATE_LIMIT, exact)
        .map { async { runCatching { complete(it) }.getOrNull() } }
        .awaitAll()
        .filterNotNull()
        .filter { it.bytes <= MAX_BOOK_BYTES }
        .take(OFFER_LIMIT)
}

internal fun rank(
    query: Query,
    offers: List<Offer>,
    limit: Int = OFFER_LIMIT,
    exact: Boolean = false,
): List<Offer> {
    val wanted = tokenize(mainTitle(query.title)).filterNot { it in ARTICLES }
    val authors = tokenize(query.author).toSet()
    fun matches(offer: Offer): Boolean {
        if (wanted.isEmpty() || !tokenize(offer.title).containsAll(wanted)) return false
        val writers = tokenize(offer.author)
        return writers.isEmpty() || authors.isEmpty() || writers.any { it in authors }
    }
    return offers
        .filter { exact || matches(it) }
        .distinctBy { it.link to it.variables }
        .sortedBy { tokenize(it.title).size }
        .take(limit)
}

fun fetchBook(offer: Offer, target: File, onProgress: (Float) -> Unit) {
    offer.provider.open(offer).use { download(it, target, onProgress) }
    check(isFormat(target, offer.format)) { "not an ${offer.format} file" }
}

private fun download(response: Response, target: File, onProgress: (Float) -> Unit) {
    val total = response.body.contentLength()
    check(total <= MAX_BOOK_BYTES) { "book too large" }
    response.stream.use { input ->
        target.outputStream().use { output ->
            copyWithProgress(input, output) { copied ->
                check(copied <= MAX_BOOK_BYTES) { "book too large" }
                onProgress(if (total > 0) copied.toFloat() / total else 0f)
            }
        }
    }
}

internal fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

private fun xml(body: String) = Jsoup.parse(body, "", Parser.xmlParser())

private fun Element.epubLinks() = select("link").filter { it.attr("type") == EPUB_TYPE }

object StandardEbooks : FeedProvider() {
    override val id = "standard-ebooks"
    override val name = "Standard Ebooks"
    override val site = "standardebooks.org"
    override val about = R.string.provider_standard_ebooks
    override val languages = setOf("en")

    override fun searchUrl(query: Query) =
        "https://standardebooks.org/feeds/atom/all?query=${encode(query.words)}"

    override fun offers(body: String, query: Query) =
        xml(body).select("entry").mapNotNull { entry ->
            val epub = entry.epubLinks().firstOrNull() ?: return@mapNotNull null
            Offer(
                this,
                entry.selectFirst("title")?.text().orEmpty(),
                entry.selectFirst("author name")?.text().orEmpty(),
                epub.attr("href"),
                entry.selectFirst("media|thumbnail")?.attr("url"),
                epub.attr("length").toLongOrNull() ?: 0,
            )
        }
}

private val LANGUAGE_NOTE = Regex(""" \(\p{Lu}\p{Ll}+\)$""")
private const val GUTENBERG_COVERS = "https://www.gutenberg.org/cache/epub"
private val GUTENBERG_BOOK = Regex("""^https://www\.gutenberg\.org/ebooks/(\d+)\.opds$""")

object Gutenberg : FeedProvider() {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
    override val site = "gutenberg.org"
    override val about = R.string.provider_gutenberg

    override fun searchUrl(query: Query) =
        "https://www.gutenberg.org/ebooks/search.opds/" +
            "?query=${encode("${query.words} l.${query.language}")}"

    override fun offers(body: String, query: Query) =
        xml(body).select("entry").mapNotNull { entry ->
            val page = entry.selectFirst("id")?.text().orEmpty()
            val number = GUTENBERG_BOOK.find(page)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val cover = "$GUTENBERG_COVERS/$number/pg$number.cover.small.jpg"
            val title = entry.selectFirst("title")?.text().orEmpty()
            val author = entry.selectFirst("content")?.text().orEmpty()
            Offer(this, title.replace(LANGUAGE_NOTE, ""), author, page, cover)
        }

    override fun detailsUrl(offer: Offer) = offer.link

    override fun details(offer: Offer, body: String): Offer? {
        val epubs = xml(body).epubLinks()
        val epub = epubs.firstOrNull { it.attr("href").endsWith(".epub3.images") }
            ?: epubs.firstOrNull()
            ?: return null
        return offer.copy(
            link = epub.attr("href"),
            bytes = epub.attr("length").toLongOrNull() ?: 0,
        )
    }
}

private val VERSION_PAGES =
    listOf("Versions pages", "Versiones", "Éditions multiples").joinToString("|") {
        "Category:$it"
    }

@Serializable
private class WikiResponse(val query: WikiPages = WikiPages())

@Serializable
private class WikiPages(val pages: List<WikiPage> = emptyList())

@Serializable
private class WikiPage(
    val title: String,
    val index: Int = 0,
    val pageprops: Map<String, String> = emptyMap(),
    val categories: List<JsonElement> = emptyList(),
) {
    val isWork
        get() = '/' !in title && pageprops.isEmpty() && categories.isEmpty()
}

object Wikisource : FeedProvider() {
    override val id = "wikisource"
    override val name = "Wikisource"
    override val site = "wikisource.org"
    override val about = R.string.provider_wikisource

    override fun searchUrl(query: Query) =
        "https://${query.language}.wikisource.org/w/api.php?action=query&format=json" +
            "&formatversion=2&generator=search&gsrnamespace=0&gsrlimit=20" +
            "&prop=${encode("pageprops|categories")}&ppprop=disambiguation" +
            "&clcategories=${encode(VERSION_PAGES)}&gsrsearch=${encode(query.words)}"

    override fun offers(body: String, query: Query) =
        json.decodeFromString<WikiResponse>(body).query.pages
            .filter { it.isWork }
            .sortedBy { it.index }
            .map { page ->
                val link = "https://ws-export.wmcloud.org/?format=epub-3" +
                    "&lang=${query.language}&page=${encode(page.title)}"
                Offer(this, page.title, "", link)
            }
}

private const val ARCHIVE_FILTER = " AND mediatype:texts AND format:EPUB" +
    " AND NOT access-restricted-item:true AND NOT collection:opensource"

private val ARCHIVE_LANGUAGES = mapOf(
    "en" to listOf("eng", "English"),
    "de" to listOf("ger", "deu", "German"),
    "fr" to listOf("fre", "fra", "French"),
    "es" to listOf("spa", "Spanish"),
)

@Serializable
private class ArchiveSearch(val response: ArchiveDocuments)

@Serializable
private class ArchiveDocuments(val docs: List<ArchiveDocument>)

@Serializable
private class ArchiveDocument(
    val identifier: String,
    val title: JsonElement? = null,
    val creator: JsonElement? = null,
)

@Serializable
private class ArchiveFiles(val result: List<ArchiveFile>)

@Serializable
private class ArchiveFile(
    val name: String,
    val size: String = "",
    @SerialName("private") val hidden: String = "",
)

private val CATALOGUE_NOTES =
    Regex(""",\s*(\d{4}\??-(\d{4}\??)?|author|editor|translator|illustrator)\.?""")

private fun JsonElement?.texts(): List<String> = when (this) {
    is JsonArray -> map { it.jsonPrimitive.content }
    is JsonPrimitive -> listOf(content)
    else -> emptyList()
}

private fun ArchiveDocument.offer(provider: Provider): Offer {
    val name = title.texts().firstOrNull().orEmpty().trimEnd(' ', ';', ':', '/', ',', '.')
    val creators = creator.texts().map { it.replace(CATALOGUE_NOTES, "") }
    val cover = "https://archive.org/services/img/$identifier"
    return Offer(provider, name, creators.joinToString(" & "), identifier, cover)
}

object InternetArchive : FeedProvider() {
    override val id = "internet-archive"
    override val name = "Internet Archive"
    override val site = "archive.org"
    override val about = R.string.provider_internet_archive

    override fun searchUrl(query: Query): String {
        val title = tokenize(mainTitle(query.title)).joinToString(" ")
        val author = tokenize(query.author).lastOrNull()?.let { " AND creator:($it)" }
        val languages = ARCHIVE_LANGUAGES[query.language].orEmpty() + query.language
        val language = " AND language:(${languages.joinToString(" OR ")})"
        val search = encode("title:($title)${author.orEmpty()}$language$ARCHIVE_FILTER")
        return "https://archive.org/advancedsearch.php?q=$search" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=20&output=json"
    }

    override fun offers(body: String, query: Query) =
        json.decodeFromString<ArchiveSearch>(body).response.docs.map { it.offer(this) }

    override fun detailsUrl(offer: Offer) =
        "https://archive.org/metadata/${offer.link}/files"

    override fun details(offer: Offer, body: String): Offer? {
        val epub = json.decodeFromString<ArchiveFiles>(body).result
            .firstOrNull { it.name.endsWith(".epub") && it.hidden != "true" }
            ?: return null
        val path = epub.name.split("/").joinToString("/", transform = ::encode)
        return offer.copy(
            link = "https://archive.org/download/${offer.link}/$path",
            bytes = epub.size.toLongOrNull() ?: 0,
        )
    }
}
