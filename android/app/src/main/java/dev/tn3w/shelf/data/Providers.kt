package dev.tn3w.shelf.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

private const val OFFER_LIMIT = 5
private const val CANDIDATE_LIMIT = 8
const val MAX_BOOK_BYTES = 200L * 1024 * 1024
const val DOWNLOAD_MINUTES = 2L
val downloadClient by lazy { http.forDownloads() }

fun OkHttpClient.forDownloads() = newBuilder()
    .readTimeout(DOWNLOAD_MINUTES, TimeUnit.MINUTES)
    .callTimeout(0, TimeUnit.MINUTES)
    .build()
private val FORM = "application/x-www-form-urlencoded".toMediaType()
private const val MAX_PAGE_BYTES = 5L * 1024 * 1024
private val REGEX_TIME = 2.seconds
private val PLACEHOLDER = Regex("""\{(\+?)(\w+)\}""")
private val URL_ATTRIBUTES = setOf("href", "src", "url", "action", "poster")
private val HOST = Regex("""://(?:\{\w+\}\.)?([^/:?#{]+)""")
private val ISBN_VARIABLE = Regex("""\{\+?isbns?\}""")
private val SINGLE_ISBN = Regex("""\{\+?isbn\}""")
private const val ISBN_TRIES = 5
private const val BUNDLED_SOURCES = "sources"
private val BIBLIOGRAPHIC_CODES = mapOf("de" to "ger", "fr" to "fre")
private val recipeJson = Json { prettyPrint = true }
const val SOURCES_FILE = "shelf-sources.json"

data class Query(
    val title: String,
    val author: String,
    val language: String,
    val isbns: List<String> = emptyList(),
) {
    val words
        get() = "${mainTitle(title).trim()} $author".trim()
}

val QUERY_VARIABLES = Query("", "", "").variables().keys

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

@Serializable
data class Pick(
    val css: String? = null,
    val json: String? = null,
    val attr: String? = null,
    val regex: String? = null,
)

@Serializable
data class RecipeStep(
    val url: String,
    val body: String? = null,
    val items: Pick? = null,
    val fields: Map<String, Pick> = emptyMap(),
)

@Serializable
data class Recipe(
    val name: String,
    val about: Map<String, String> = emptyMap(),
    val search: RecipeStep,
    val steps: List<RecipeStep> = emptyList(),
    val download: String,
    val cover: String = "",
    val format: String = "epub",
    val languages: Set<String> = emptySet(),
    val headers: Map<String, String> = emptyMap(),
    val id: String = "",
)

val STARTER_RECIPE = Recipe(
    name = "Project Gutenberg (HTML)",
    search = RecipeStep(
        url = "https://www.gutenberg.org/ebooks/search/?query={query}",
        items = Pick(css = "li.booklink"),
        fields = mapOf(
            "title" to Pick(css = ".title"),
            "author" to Pick(css = ".subtitle"),
            "cover" to Pick(css = "img.cover-thumb", attr = "src"),
            "page" to Pick(css = "a.link", attr = "href"),
        ),
    ),
    steps = listOf(
        RecipeStep(
            url = "{+page}",
            fields = mapOf("file" to Pick(css = "a[href$=.epub3.images]", attr = "href")),
        ),
    ),
    download = "{+file}",
)

fun parseRecipe(text: String) = runCatching { recipeJson.decodeFromString<Recipe>(text) }

fun parseRecipes(text: String) = runCatching {
    val element = recipeJson.parseToJsonElement(text)
    if (element is JsonArray) {
        recipeJson.decodeFromJsonElement<List<Recipe>>(element)
    } else {
        listOf(recipeJson.decodeFromJsonElement<Recipe>(element))
    }
}

fun Recipe.toText() = recipeJson.encodeToString(copy(id = ""))

fun recipesText(recipes: List<Recipe>) =
    recipeJson.encodeToString(recipes.map { it.copy(id = "") })

fun Settings.enabledProviders(recipes: List<Recipe>) =
    recipes.filter { it.id in providers }.map(::Provider)

data class Provider(val recipe: Recipe, private val log: (String) -> Unit = {}) {
    val id
        get() = recipe.id
    val name
        get() = recipe.name
    val site
        get() = HOST.find(recipe.search.url)?.groupValues?.get(1).orEmpty()
    private val searchTemplates
        get() = listOfNotNull(recipe.search.url, recipe.search.body)
    val usesIsbn
        get() = searchTemplates.any { ISBN_VARIABLE.containsMatchIn(it) }

    fun serves(language: String) =
        recipe.languages.isEmpty() || language in recipe.languages

    suspend fun search(query: Query) = withContext(Dispatchers.IO) {
        val completed = rank(query, find(query), CANDIDATE_LIMIT, exact = usesIsbn)
            .map { async { runSuspendCatching { complete(it) } } }
            .awaitAll()
        val offers = completed.mapNotNull { it.getOrNull() }
            .filter { it.bytes <= MAX_BOOK_BYTES }
        val error = completed.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (offers.isEmpty() && error != null) throw error
        offers.take(OFFER_LIMIT)
    }

    suspend fun find(query: Query): List<Offer> {
        val variables = query.variables()
        if (!usesIsbn) return items(variables)
        if (query.isbns.isEmpty()) {
            log("no ISBN known")
            return emptyList()
        }
        val single = searchTemplates.any { SINGLE_ISBN.containsMatchIn(it) }
        val tries = if (single) ISBN_TRIES else 1
        query.isbns.take(tries).forEach { isbn ->
            val offers = items(variables + ("isbn" to isbn))
            if (offers.isNotEmpty()) return offers
        }
        return emptyList()
    }

    private suspend fun items(variables: Map<String, String>): List<Offer> {
        val page = fetch(recipe.search, variables, base = null)
        val items = recipe.search.items?.all(page).orEmpty()
        log("${items.size} items")
        return items.mapNotNull { offer(it, variables) }
    }

    private fun offer(item: Scope, variables: Map<String, String>): Offer? {
        val fields = recipe.search.fields.extract(item)
        if ("skip" in fields) return null
        val title = fields["title"] ?: return null
        val format = fields["format"]?.lowercase()?.takeIf { it in READABLE_EXTENSIONS }
        val cover = fields["cover"]
            ?.let { runCatching { resolve(item.base, it) }.getOrNull() }
        val author = fields["author"].orEmpty()
        val chosen = format ?: recipe.format
        return Offer(this, title, author, item.base, cover, 0, chosen, variables + fields)
    }

    suspend fun complete(offer: Offer): Offer? {
        var variables = offer.variables
        var base = offer.link
        recipe.steps.forEach { step ->
            if (lacks(variables, step.url, step.body)) return null
            val page = fetch(step, variables, base)
            val found = step.fields.extract(page)
            found.forEach { (name, value) -> log("  $name = $value") }
            val missing = step.fields.keys.firstOrNull { it !in found }
            if (missing != null) {
                log("$missing not found on ${page.base}")
                return null
            }
            variables = variables + found
            base = page.base
        }
        if (lacks(variables, recipe.download)) return null
        val cover = recipe.cover.takeUnless { it.isEmpty() || lacks(variables, it) }
            ?.let { resolve(base, expand(it, variables)) }
        return offer.copy(
            link = resolve(base, expand(recipe.download, variables)),
            cover = cover ?: offer.cover,
            bytes = variables["size"]?.toLongOrNull() ?: 0,
            variables = variables,
        )
    }

    private fun lacks(
        variables: Map<String, String>,
        vararg templates: String?,
    ): Boolean {
        val name = templates.filterNotNull().flatMap(::placeholders)
            .firstOrNull { it !in variables } ?: return false
        log("{$name} not found")
        return true
    }

    suspend fun open(offer: Offer): Response {
        log("GET ${offer.link}")
        val started = TimeSource.Monotonic.markNow()
        return request(downloads, offer.link, null).also {
            val type = it.header("Content-Type").orEmpty()
            log("  ${it.code} · $type · ${elapsed(started)}")
        }
    }

    private suspend fun fetch(
        step: RecipeStep,
        variables: Map<String, String>,
        base: String?,
    ): Scope {
        val url = resolve(base, expand(step.url, variables))
        val body = step.body?.let { expand(it, variables) }
        log("${if (body == null) "GET" else "POST"} $url")
        val started = TimeSource.Monotonic.markNow()
        return request(client, url, body).use { response ->
            val text = response.stream.readAtMost(MAX_PAGE_BYTES).decodeToString()
            log("  ${response.code} · ${text.length} characters · ${elapsed(started)}")
            Scope(response.request.url.toString(), text)
        }
    }

    private val client by lazy {
        http.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!request.url.host.equals(site, ignoreCase = true)) {
                return@addNetworkInterceptor chain.proceed(request)
            }
            val signed = request.newBuilder()
            recipe.headers.forEach { (name, value) -> signed.header(name, value) }
            chain.proceed(signed.build())
        }.build()
    }

    private val downloads by lazy { client.forDownloads() }

    private suspend fun request(
        client: OkHttpClient,
        url: String,
        body: String?,
    ): Response {
        val request = Request.Builder().url(url)
        if (body != null) request.post(body.toRequestBody(FORM))
        val response = client.newCall(request.build()).executeAsync()
        generateSequence(response) { it.priorResponse }.toList().asReversed()
            .zipWithNext { prior, next -> log("  ${prior.code} → ${next.request.url}") }
        if (response.isSuccessful) return response
        response.close()
        throw HttpException(response.code, response.request.url.toString())
    }

    suspend fun probe(query: Query) {
        val offers = find(query)
        offers.take(3).forEach { log("• ${it.title} · ${it.author}") }
        val first = offers.firstOrNull() ?: return log("no item with a title")
        val ready = complete(first) ?: return
        ready.variables.forEach { (name, value) -> log("  $name = $value") }
        open(ready).use { file ->
            val bytes = file.body.contentLength()
            log("file: ${file.header("Content-Type").orEmpty()} · $bytes bytes")
        }
    }
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

suspend fun fetchBook(offer: Offer, target: File, onProgress: (Float) -> Unit) =
    withContext(Dispatchers.IO) {
        offer.provider.open(offer).use { response ->
            download(response, target) { progress ->
                ensureActive()
                onProgress(progress)
            }
        }
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

private class Scope(
    val base: String,
    private val text: String? = null,
    private val element: Element? = null,
    private val node: JsonElement? = null,
) {
    private val source by lazy {
        text ?: element?.outerHtml() ?: (node as? JsonPrimitive)?.content
            ?: node.toString()
    }
    private val document by lazy { element ?: Jsoup.parse(source, base, parser()) }
    private val root by lazy { node ?: json.parseToJsonElement(source) }

    val value
        get() = element?.text() ?: (node as? JsonPrimitive)?.content ?: source

    fun select(css: String) = document.select(css).map { Scope(base, element = it) }

    fun at(path: String): List<Scope> {
        val found = path.split('.').filter { it.isNotEmpty() }
            .fold<String, JsonElement?>(root) { current, key -> current.child(key) }
            ?: return emptyList()
        val nodes = if (found is JsonArray) found.toList() else listOf(found)
        return nodes.map { Scope(base, node = it) }
    }

    fun attribute(name: String): Scope {
        val absolute = element?.takeIf { name in URL_ATTRIBUTES }?.absUrl(name).orEmpty()
        return Scope(base, absolute.ifEmpty { element?.attr(name).orEmpty() })
    }

    fun match(regex: Regex) = regex.scan(Deadline(source)).map { found ->
        val text = if (found.groupCount() > 0) found.group(1).orEmpty() else found.group()
        Scope(base, if (element == null) text else Parser.unescapeEntities(text, false))
    }.toList()

    private fun parser(): Parser {
        val xml = source.trimStart().startsWith("<?xml")
        return if (xml) Parser.xmlParser() else Parser.htmlParser()
    }
}

private class Deadline(
    private val text: CharSequence,
    private val end: ValueTimeMark = TimeSource.Monotonic.markNow() + REGEX_TIME,
) : CharSequence by text {
    override fun get(index: Int): Char {
        if (end.hasPassedNow()) throw IOException("regex ran over $REGEX_TIME")
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        Deadline(text.subSequence(startIndex, endIndex), end)

    override fun toString() = text.toString()
}

private fun JsonElement?.child(key: String) = when (this) {
    is JsonObject -> this[key]
    is JsonArray -> key.toIntOrNull()?.let(::getOrNull)
    else -> null
}

private fun Pick.all(scope: Scope): List<Scope> {
    var scopes = listOf(scope)
    css?.let { selector -> scopes = scopes.flatMap { it.select(selector) } }
    json?.let { path -> scopes = scopes.flatMap { it.at(path) } }
    attr?.let { name -> scopes = scopes.map { it.attribute(name) } }
    regex?.let { pattern -> scopes = scopes.flatMap { it.match(Regex(pattern)) } }
    return scopes
}

private fun Pick.first(scope: Scope) =
    all(scope).firstOrNull()?.value?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<String, Pick>.extract(scope: Scope) =
    mapNotNull { (name, pick) -> pick.first(scope)?.let { name to it } }.toMap()

internal fun expand(template: String, variables: Map<String, String>) =
    PLACEHOLDER.replace(template) { match ->
        val (raw, name) = match.destructured
        val value = requireNotNull(variables[name]) { "unknown variable {$name}" }
        if (raw.isEmpty()) encode(value) else value
    }

private fun resolve(base: String?, url: String) =
    if (base == null) url else URI(base).resolve(url).toString()

private fun placeholders(template: String) =
    PLACEHOLDER.findAll(template).map { it.groupValues[2] }.toList()

private fun Query.variables(): Map<String, String> {
    val locale = Locale.forLanguageTag(language)
    return mapOf(
        "query" to words,
        "title" to title,
        "author" to author,
        "titleWords" to tokenize(mainTitle(title)).joinToString(" "),
        "surname" to surname(author),
        "language" to language,
        "language3" to (BIBLIOGRAPHIC_CODES[language] ?: locale.isO3Language),
        "languageName" to locale.getDisplayLanguage(Locale.ENGLISH),
        "isbn" to isbns.firstOrNull().orEmpty(),
        "isbns" to isbns.joinToString(","),
    )
}

private fun elapsed(started: ValueTimeMark) =
    "${started.elapsedNow().inWholeMilliseconds} ms"

fun Recipe.problems(): List<String> = buildList {
    if (name.isBlank()) add("name is empty")
    if (search.items == null) add("search needs items")
    if ("title" !in search.fields) add("search needs a title field")
    if (format !in READABLE_EXTENSIONS) add("format $format cannot be read")
    var known = QUERY_VARIABLES
    (listOf(search) + steps).forEachIndexed { index, step ->
        templateProblem(step.url, known, url = true, absolute = index == 0)?.let(::add)
        step.body?.let { templateProblem(it, known, url = false) }?.let(::add)
        (step.fields.values + listOfNotNull(step.items)).mapNotNull { it.problem() }
            .forEach(::add)
        known = known + step.fields.keys
    }
    templateProblem(download, known, url = true)?.let(::add)
    if (cover.isNotEmpty()) templateProblem(cover, known, url = true)?.let(::add)
}

private fun templateProblem(
    template: String,
    known: Set<String>,
    url: Boolean,
    absolute: Boolean = false,
): String? {
    val unknown = placeholders(template).firstOrNull { it !in known }
    if (unknown != null) return "unknown variable {$unknown} in $template"
    if (!url || template.startsWith("{+")) return null
    val sample = runCatching { URI(expand(template, known.associateWith { "x" })) }
        .getOrNull() ?: return "invalid URL $template"
    if (sample.isAbsolute || !absolute) return null
    return "$template must be an absolute URL"
}

val Pick.isEmpty
    get() = listOfNotNull(css, json, attr, regex).isEmpty()

fun firstLine(error: Throwable) =
    error.message?.lineSequence()?.firstOrNull().orEmpty().take(200)

private fun Pick.problem(): String? {
    if (isEmpty) return "empty rule"
    val error = runCatching {
        css?.let { Jsoup.parse("").select(it) }
        regex?.let { Regex(it) }
    }.exceptionOrNull() ?: return null
    return "invalid rule: ${firstLine(error)}"
}

data class Imported(val added: List<Recipe>, val total: Int)

class Sources(private val context: Context) {
    private val file = context.filesDir.resolve(SOURCES_FILE)
    private val writing = Mutex()
    private val _own = MutableStateFlow(read())
    val own = _own.asStateFlow()
    val bundled by lazy {
        context.assets.list(BUNDLED_SOURCES).orEmpty().sorted().map { name ->
            val text = context.assets.open("$BUNDLED_SOURCES/$name")
                .use { it.readBytes().decodeToString() }
            parseRecipe(text).getOrThrow().copy(id = name.removeSuffix(".json"))
        }
    }

    private fun read() =
        runCatching { json.decodeFromString<List<Recipe>>(file.readText()) }
            .getOrDefault(emptyList())

    suspend fun save(recipe: Recipe) = write { recipes ->
        val replaced = recipes.map { if (it.id == recipe.id) recipe else it }
        if (recipes.any { it.id == recipe.id }) replaced else recipes + recipe
    }

    suspend fun remove(id: String) = write { recipes -> recipes.filter { it.id != id } }

    suspend fun import(uri: Uri): Imported? {
        val text =
            context.readFrom(uri) { it.readBytes().decodeToString() } ?: return null
        val found = parseRecipes(text).getOrNull() ?: return null
        val added = found.filter { it.problems().isEmpty() }
            .map { it.copy(id = UUID.randomUUID().toString()) }
        write { recipes -> recipes + added }
        return Imported(added, found.size)
    }

    suspend fun export(uri: Uri) =
        context.writeTo(uri) { it.write(recipesText(_own.value).toByteArray()) }

    private suspend fun write(change: (List<Recipe>) -> List<Recipe>) = writing.withLock {
        withContext(Dispatchers.IO) {
            val next = change(_own.value)
            replaceFile(file) { it.writeText(json.encodeToString(next)) }
            _own.value = next
        }
    }
}
