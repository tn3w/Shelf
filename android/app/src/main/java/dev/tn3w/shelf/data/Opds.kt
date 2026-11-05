package dev.tn3w.shelf.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Challenge
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

const val CATALOGS_FILE = "shelf-catalogs.json"
private const val MAX_FEED_BYTES = 8L * 1024 * 1024
private const val ACCEPT = "application/atom+xml, application/opds+json;q=0.9, */*;q=0.8"
private const val ACQUISITION = "http://opds-spec.org/acquisition"
private val ACQUISITIONS = setOf(ACQUISITION, "$ACQUISITION/open-access")
private val THUMBNAILS = setOf(
    "http://opds-spec.org/image/thumbnail",
    "http://opds-spec.org/thumbnail",
    "x-stanza-cover-image-thumbnail",
)
private val IMAGES =
    setOf("http://opds-spec.org/image", "http://opds-spec.org/cover", "cover")
private val SKIPPED_LINKS = setOf("self", "start", "up", "search", "next", "previous")
private const val OAUTH_PASSWORD = "http://opds-spec.org/auth/oauth/password"
private val PREFERRED =
    listOf("epub", "kepub", "azw3", "mobi", "fb2", "pdf", "cbz", "cbr")
private val MIME_FORMATS = mapOf(
    "application/epub+zip" to "epub",
    "application/kepub+zip" to "kepub",
    "application/x-mobipocket-ebook" to "mobi",
    "application/x-mobi8-ebook" to "azw3",
    "application/vnd.amazon.ebook" to "azw3",
    "application/pdf" to "pdf",
    "application/x-fictionbook+xml" to "fb2",
    "application/fb2+xml" to "fb2",
    "application/x-cbz" to "cbz",
    "application/vnd.comicbook+zip" to "cbz",
    "application/x-cbr" to "cbr",
    "application/vnd.comicbook-rar" to "cbr",
    "application/x-cb7" to "cb7",
    "application/rtf" to "rtf",
    "text/rtf" to "rtf",
    "text/plain" to "txt",
    "application/xhtml+xml" to "xhtml",
    "application/vnd.oasis.opendocument.text" to "odt",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
)
private val TEMPLATE_QUERY = Regex("""\{\?[^}]*\}""")
private val SEARCH_TERMS = Regex("""\{searchTerms\??\}""")
private val LEFTOVER_PARAMETER = Regex("""\{[^}]*\}""")

@Serializable
data class Login(
    val username: String = "",
    val password: String = "",
    val token: String = "",
)

@Serializable
data class Catalog(
    val name: String,
    val url: String,
    val logins: Map<String, Login> = emptyMap(),
    val search: String = "",
    val languages: Set<String> = emptySet(),
    val id: String = UUID.randomUUID().toString(),
) {
    val host
        get() = runCatching { URI(url).host }.getOrNull().orEmpty()
    val isBundled
        get() = BUNDLED_CATALOGS.any { it.id == id }

    fun serves(language: String) = languages.isEmpty() || language in languages
}

val BUNDLED_CATALOGS = listOf(
    Catalog(
        "Project Gutenberg",
        "https://www.gutenberg.org/ebooks.opds/",
        search = "https://www.gutenberg.org/ebooks/search.opds/?query={searchTerms}" +
            "%20l.{language}",
        id = "gutenberg",
    ),
    Catalog(
        "Standard Ebooks",
        "https://standardebooks.org/feeds/opds",
        search = "https://standardebooks.org/feeds/opds/all?query={searchTerms}",
        languages = setOf("en"),
        id = "standard-ebooks",
    ),
)

data class Acquisition(
    val url: String,
    val format: String,
    val label: String = "",
    val bytes: Long = 0,
)

data class Entry(
    val title: String,
    val author: String = "",
    val summary: String = "",
    val cover: String? = null,
    val link: String? = null,
    val files: List<Acquisition> = emptyList(),
)

data class Feed(
    val title: String,
    val entries: List<Entry>,
    val next: String? = null,
    val search: String? = null,
    val searchDescription: String? = null,
)

class LoginNeeded(
    val host: String,
    val realm: String,
    val tokenUrl: String? = null,
    val failed: Boolean = false,
) : IOException("login needed for $host") {
    fun failed() = LoginNeeded(host, realm, tokenUrl, failed = true)
}

fun parseFeed(text: String, base: String): Feed {
    if (text.trimStart().startsWith("{")) {
        return jsonFeed(json.parseToJsonElement(text).jsonObject, base)
    }
    val document = Jsoup.parse(text, base, Parser.xmlParser())
    val root = document.children().firstOrNull { it.normalName() == "feed" }
        ?: document.children().firstOrNull { it.normalName() == "entry" }
        ?: throw IOException("not an OPDS feed")
    if (root.normalName() ==
        "entry"
    ) {
        return Feed(root.child("title"), listOf(atomEntry(root)))
    }
    val links = root.links()
    val search = links.firstOrNull { it.attr("rel") == "search" }
    val describes = search?.attr("type").orEmpty().contains("opensearchdescription")
    return Feed(
        title = root.child("title"),
        entries = root.children().filter { it.normalName() == "entry" }.map(::atomEntry),
        next = links.firstOrNull { it.attr("rel") == "next" }?.href(),
        search = search?.href()?.takeUnless { describes },
        searchDescription = search?.href()?.takeIf { describes },
    )
}

fun searchTemplate(description: String): String? {
    val urls = Jsoup.parse(description, "", Parser.xmlParser()).select("Url")
    val url = urls.firstOrNull { "opds" in it.attr("type") }
        ?: urls.firstOrNull { "atom" in it.attr("type") }
        ?: urls.firstOrNull()
    return url?.attr("template")?.takeIf { it.isNotEmpty() }
}

fun searchUrl(template: String, terms: String, language: String = "") = template
    .replace(TEMPLATE_QUERY) { "?query=${encode(terms)}" }
    .replace(SEARCH_TERMS) { encode(terms) }
    .replace("{language}", language)
    .replace(LEFTOVER_PARAMETER, "")

fun opdsFormat(type: String, url: String): String? {
    val mime = type.substringBefore(';').trim().lowercase()
    MIME_FORMATS[mime]?.let { return it }
    val path = url.substringBefore('?').substringAfterLast('/')
    val extension = path.substringAfterLast('.', "")
    return extension.lowercase().takeIf { it in READABLE_EXTENSIONS }
}

private fun Element.child(name: String) =
    children().firstOrNull { it.normalName() == name }?.text()?.trim().orEmpty()

private fun Element.links() = children().filter { it.normalName() == "link" }

private fun Element.href() = resolve(baseUri(), attr("href"))

private fun resolve(base: String, href: String): String {
    val path = href.trim().substringBefore('{')
    val template = href.trim().removePrefix(path)
    return runCatching {
        URI(base).resolve(path).toString() + template
    }.getOrDefault(href)
}

private fun isFeed(type: String) =
    "opds" in type || type.startsWith("application/atom+xml")

private fun atomEntry(element: Element): Entry {
    val links = element.links()
    val files = links.filter { it.attr("rel") in ACQUISITIONS }.mapNotNull { link ->
        val url = link.href()
        val format = opdsFormat(link.attr("type"), url) ?: return@mapNotNull null
        Acquisition(
            url, format, link.attr("title"),
            link.attr("length").toLongOrNull() ?: 0,
        )
    }
    val cover = links.firstOrNull { it.attr("rel") in THUMBNAILS }
        ?: links.firstOrNull { it.attr("rel") in IMAGES }
    val link = links.firstOrNull {
        isFeed(it.attr("type")) && it.attr("rel") !in SKIPPED_LINKS + ACQUISITIONS
    }
    val authors = element.children().filter { it.normalName() == "author" }
        .map { it.child("name") }.filter { it.isNotEmpty() }
    return Entry(
        title = element.child("title"),
        author = authors.joinToString(", "),
        summary = element.child("summary").ifEmpty { element.child("content") },
        cover = cover?.href(),
        link = link?.href(),
        files = sorted(files),
    )
}

private fun sorted(files: List<Acquisition>) = files.sortedBy {
    PREFERRED.indexOf(it.format).takeIf { index -> index >= 0 } ?: PREFERRED.size
}

private class Link(
    val rels: List<String>,
    val type: String,
    val href: String,
    val title: String,
    val bytes: Long,
)

private fun JsonElement?.objects() =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?: listOfNotNull(this as? JsonObject)

private fun JsonObject.text(key: String) =
    (this[key] as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.links(base: String) = this["links"].objects().map { link ->
    val rel = link["rel"]
    val rels = (rel as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?: listOfNotNull((rel as? JsonPrimitive)?.content)
    val bytes = (link["properties"] as? JsonObject)?.text("size")?.toLongOrNull() ?: 0
    val href = resolve(base, link.text("href"))
    Link(rels, link.text("type"), href, link.text("title"), bytes)
}

private fun names(element: JsonElement?): List<String> = when (element) {
    is JsonPrimitive -> listOf(element.content)
    is JsonArray -> element.flatMap(::names)
    is JsonObject -> listOf(element.text("name"))
    else -> emptyList()
}

private fun publication(item: JsonObject, base: String): Entry {
    val metadata = item["metadata"] as? JsonObject ?: JsonObject(emptyMap())
    val links = item.links(base)
    val files = links.filter { link -> link.rels.any { it in ACQUISITIONS } }
        .mapNotNull { link ->
            val format = opdsFormat(link.type, link.href) ?: return@mapNotNull null
            Acquisition(link.href, format, link.title, link.bytes)
        }
    val detail = links.firstOrNull {
        it.type == "application/opds-publication+json" && "self" !in it.rels
    }
    val cover = item["images"].objects().firstOrNull()?.text("href")
    return Entry(
        title = metadata.text("title"),
        author = names(metadata["author"]).filter { it.isNotBlank() }.joinToString(", "),
        summary = Jsoup.parse(metadata.text("description")).text(),
        cover = cover?.let { resolve(base, it) },
        link = detail?.href,
        files = sorted(files),
    )
}

private fun jsonFeed(root: JsonObject, base: String): Feed {
    val title = (root["metadata"] as? JsonObject)?.text("title").orEmpty()
    val links = root.links(base)
    if ("publications" !in root && "navigation" !in root && "groups" !in root) {
        return Feed(title, listOf(publication(root, base)))
    }
    val groups = listOf(root) + root["groups"].objects()
    val navigation = groups.flatMap { it["navigation"].objects() }.map {
        Entry(it.text("title"), link = resolve(base, it.text("href")))
    }
    val publications = groups.flatMap { it["publications"].objects() }
        .map { publication(it, base) }
    return Feed(
        title = title,
        entries = navigation + publications,
        next = links.firstOrNull { "next" in it.rels }?.href,
        search = links.firstOrNull { "search" in it.rels }?.href,
    )
}

private fun md5(text: String) = MessageDigest.getInstance("MD5")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun digestAuthorization(
    login: Login,
    challenge: Challenge,
    method: String,
    path: String,
    clientNonce: String = Random.nextLong().toULong().toString(16),
): String {
    val parameters = challenge.authParams
    val realm = parameters["realm"].orEmpty()
    val nonce = parameters["nonce"].orEmpty()
    val qop = parameters["qop"]?.split(',')?.map {
        it.trim()
    }?.firstOrNull { it == "auth" }
    val secret = md5("${login.username}:$realm:${login.password}")
    val request = md5("$method:$path")
    val count = "00000001"
    val answer = if (qop == null) {
        md5("$secret:$nonce:$request")
    } else {
        md5("$secret:$nonce:$count:$clientNonce:$qop:$request")
    }
    val fields = listOfNotNull(
        "username=\"${login.username}\"",
        "realm=\"$realm\"",
        "nonce=\"$nonce\"",
        "uri=\"$path\"",
        "response=\"$answer\"",
        parameters["opaque"]?.let { "opaque=\"$it\"" },
        qop?.let { "qop=$it, nc=$count, cnonce=\"$clientNonce\"" },
    )
    return "Digest ${fields.joinToString(", ")}"
}

class CatalogClient(private val catalog: Catalog) {
    private val basicHosts = ConcurrentHashMap.newKeySet<String>()
    private val descriptions = ConcurrentHashMap<String, String>()

    private val client = http.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val header = authorization(request.url.host)
                ?: return@addInterceptor chain.proceed(request)
            chain.proceed(request.newBuilder().header("Authorization", header).build())
        }
        .authenticator { route, response ->
            answer(response) ?: http.authenticator.authenticate(route, response)
        }
        .build()

    private val downloads by lazy { client.forDownloads() }

    fun authorization(host: String): String? {
        val login = catalog.logins[host] ?: return null
        if (login.token.isNotEmpty()) return "Bearer ${login.token}"
        if (host !in basicHosts) return null
        return Credentials.basic(login.username, login.password, Charsets.UTF_8)
    }

    private fun answer(response: Response): Request? {
        val request = response.request
        val login = catalog.logins[request.url.host] ?: return null
        if (request.header("Authorization") != null) return null
        val challenges = response.challenges()
        val digest = challenges.firstOrNull {
            it.scheme.equals("Digest", ignoreCase = true)
        }
        val header = when {
            digest != null -> {
                val path = request.url.encodedPath +
                    request.url.encodedQuery?.let { "?$it" }.orEmpty()
                digestAuthorization(login, digest, request.method, path)
            }

            else -> {
                basicHosts += request.url.host
                Credentials.basic(login.username, login.password, Charsets.UTF_8)
            }
        }
        return request.newBuilder().header("Authorization", header).build()
    }

    private suspend fun get(url: String) = client.newCall(
        Request.Builder().url(url).header("Accept", ACCEPT).build(),
    ).executeAsync()

    private fun Response.text() =
        body.byteStream().readAtMost(MAX_FEED_BYTES).decodeToString()

    private fun Response.check(text: String) {
        val type = header("Content-Type").orEmpty()
        if (code == 401 || "opds-authentication" in type) throw loginNeeded(this, text)
        if (!isSuccessful) throw HttpException(code, request.url.toString())
    }

    suspend fun feed(url: String = catalog.url): Feed = withContext(Dispatchers.IO) {
        val feed = get(url).use { response ->
            val text = response.text()
            response.check(text)
            parseFeed(text, response.request.url.toString())
        }
        val description = feed.searchDescription ?: return@withContext feed
        feed.copy(search = runSuspendCatching { describe(description) }.getOrNull())
    }

    suspend fun find(query: Query, limit: Int = 5): List<Entry> = coroutineScope {
        val template = catalog.search.ifEmpty { feed().search }
            ?: return@coroutineScope emptyList()
        val terms = mainTitle(query.title).trim()
        feed(searchUrl(template, terms, query.language)).entries
            .filter { query.matches(it.title, it.author) }
            .take(limit)
            .map { async { runSuspendCatching { withFiles(it) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun withFiles(entry: Entry): Entry? {
        if (entry.files.isNotEmpty()) return entry
        val link = entry.link ?: return null
        val found =
            feed(link).entries.firstOrNull { it.files.isNotEmpty() } ?: return null
        return found.copy(cover = found.cover ?: entry.cover)
    }

    private suspend fun describe(url: String) = descriptions[url] ?: get(url).use {
        it.check("")
        searchTemplate(it.text())?.also { template -> descriptions[url] = template }
    }

    suspend fun signIn(needed: LoginNeeded, username: String, password: String): Login {
        val tokenUrl = needed.tokenUrl ?: return Login(username, password)
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder().url(tokenUrl).post(form).build()
        val token = withContext(Dispatchers.IO) {
            http.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) throw needed.failed()
                val body = json.parseToJsonElement(response.text()).jsonObject
                body.text("access_token")
            }
        }
        check(token.isNotEmpty()) { "no access token" }
        return Login(username, token = token)
    }

    suspend fun download(file: Acquisition, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(file.url).build()
            downloads.newCall(request).executeAsync().use { response ->
                if (response.code == 401) throw loginNeeded(response, "")
                if (!response.isSuccessful) throw HttpException(response.code, file.url)
                download(response, target) { progress ->
                    ensureActive()
                    onProgress(progress)
                }
            }
            check(isFormat(target, file.format)) { "not an ${file.format} file" }
        }
}

private fun loginNeeded(response: Response, text: String): LoginNeeded {
    val host = response.request.url.host
    val failed = response.request.header("Authorization") != null
    val document = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    val password = document?.get("authentication").objects()
        .firstOrNull { it.text("type") == OAUTH_PASSWORD }
    val tokenUrl = password?.links("")?.firstOrNull { "authenticate" in it.rels }?.href
    val realm = document?.text("title")?.ifEmpty { null }
        ?: response.challenges().firstNotNullOfOrNull { it.realm }
        ?: host
    return LoginNeeded(host, realm, tokenUrl, failed)
}

class Catalogs(context: Context) {
    private val file = context.filesDir.resolve(CATALOGS_FILE)
    private val writing = Mutex()
    private var stored = read()
    private val _all = MutableStateFlow(merged(stored))
    val all = _all.asStateFlow()
    private val clients = ConcurrentHashMap<Catalog, CatalogClient>()

    private fun read() =
        runCatching { json.decodeFromString<List<Catalog>>(file.readText()) }
            .getOrDefault(emptyList())

    fun client(catalog: Catalog) = clients.getOrPut(catalog) { CatalogClient(catalog) }

    private fun merged(stored: List<Catalog>) = BUNDLED_CATALOGS.map { bundled ->
        val logins = stored.firstOrNull { it.id == bundled.id }?.logins.orEmpty()
        bundled.copy(logins = logins)
    } + stored.filterNot { it.isBundled }

    suspend fun save(catalog: Catalog) = write { catalogs ->
        val replaced = catalogs.map { if (it.id == catalog.id) catalog else it }
        if (catalogs.any { it.id == catalog.id }) replaced else catalogs + catalog
    }

    suspend fun remove(id: String) = write { catalogs -> catalogs.filter { it.id != id } }

    private suspend fun write(change: (List<Catalog>) -> List<Catalog>) =
        writing.withLock {
            withContext(Dispatchers.IO) {
                stored = change(stored)
                replaceFile(file) { it.writeText(json.encodeToString(stored)) }
                _all.value = merged(stored)
            }
        }
}
