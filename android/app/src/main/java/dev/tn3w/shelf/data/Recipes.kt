package dev.tn3w.shelf.data

import android.content.Context
import android.net.Uri
import dev.tn3w.shelf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

private val FORM = "application/x-www-form-urlencoded".toMediaType()
private const val MAX_PAGE_BYTES = 5L * 1024 * 1024
private val REGEX_TIME = 2.seconds
private const val DEBUG_ITEMS = 5
private const val DEBUG_PAGE_CHARACTERS = 100_000
private val PLACEHOLDER = Regex("""\{(\+?)(\w+)\}""")
private val HOST = Regex("""://([^/:?#{]+)""")
private const val HIDDEN = "<hidden>"
private val QUERY_VALUE = Regex("""([?&][^=&\s"]+=)[^&\s#"{][^&\s#"]*""")
private val SECRET_HEADER =
    Regex("""(?im)^(\s*< (?:set-cookie|cookie|authorization|proxy-authorization):).*$""")
private val BODY_LINE = Regex("""(?m)^(\s*body:).*$""")
val QUERY_VARIABLES = Query("", "", "").variables().keys
private val ISBN_VARIABLE = Regex("""\{\+?isbns?\}""")
private val SINGLE_ISBN = Regex("""\{\+?isbn\}""")
private const val ISBN_TRIES = 5
private val recipeJson = Json { prettyPrint = true }

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
    val search: RecipeStep,
    val steps: List<RecipeStep> = emptyList(),
    val download: String,
    val format: String = "epub",
    val languages: Set<String> = emptySet(),
    val headers: Map<String, String> = emptyMap(),
    val id: String = "",
)

const val SOURCES_FILE = "shelf-sources.json"

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

private class Scope(
    val base: String,
    private val text: String? = null,
    private val element: Element? = null,
    private val node: JsonElement? = null,
) {
    private val source by lazy { text ?: element?.outerHtml() ?: node.toString() }
    private val document by lazy { element ?: Jsoup.parse(source, base) }
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
        val absolute = element?.absUrl(name).orEmpty()
        return Scope(base, absolute.ifEmpty { element?.attr(name).orEmpty() })
    }

    fun match(regex: Regex) = regex.scan(Deadline(source)).map { found ->
        Scope(
            base,
            if (found.groupCount() >
                0
            ) {
                found.group(1).orEmpty()
            } else {
                found.group()
            },
        )
    }.toList()
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

private fun Query.variables() = mapOf(
    "query" to words,
    "title" to title,
    "author" to author,
    "language" to language,
    "isbn" to isbns.firstOrNull().orEmpty(),
    "isbns" to isbns.joinToString(","),
)

private fun elapsed(started: ValueTimeMark) =
    "${started.elapsedNow().inWholeMilliseconds} ms"

data class OwnSource(
    val recipe: Recipe,
    private val log: (String) -> Unit = {},
    private val verbose: Boolean = false,
) : Provider {
    override val id
        get() = recipe.id
    override val name
        get() = recipe.name
    override val site
        get() = HOST.find(recipe.search.url)?.groupValues?.get(1).orEmpty()
    override val about = R.string.provider_own
    override val languages
        get() = recipe.languages
    private val searchTemplates
        get() = listOfNotNull(recipe.search.url, recipe.search.body)
    val usesIsbn
        get() = searchTemplates.any { ISBN_VARIABLE.containsMatchIn(it) }
    override val exact
        get() = usesIsbn

    override fun find(query: Query): List<Offer> {
        val variables = query.variables()
        if (!usesIsbn) return search(variables)
        if (query.isbns.isEmpty()) {
            log("no ISBN known")
            return emptyList()
        }
        val single = searchTemplates.any { SINGLE_ISBN.containsMatchIn(it) }
        val tries = if (single) ISBN_TRIES else 1
        query.isbns.take(tries).forEach { isbn ->
            val offers = search(variables + ("isbn" to isbn))
            if (offers.isNotEmpty()) return offers
        }
        return emptyList()
    }

    private fun search(variables: Map<String, String>): List<Offer> {
        val page = fetch(recipe.search, variables, base = null)
        val items = recipe.search.items?.all(page).orEmpty()
        log("${items.size} items")
        return items.mapIndexedNotNull { index, item -> offer(item, variables, index) }
    }

    private fun offer(item: Scope, variables: Map<String, String>, index: Int): Offer? {
        val fields = recipe.search.fields.extract(item)
        if (verbose && index < DEBUG_ITEMS) log("item ${index + 1}: $fields")
        val title = fields["title"] ?: return null
        val format = fields["format"]?.lowercase()?.takeIf { it in READABLE_EXTENSIONS }
        val cover = fields["cover"]
            ?.let { runCatching { resolve(item.base, it) }.getOrNull() }
        val author = fields["author"].orEmpty()
        val chosen = format ?: recipe.format
        return Offer(this, title, author, item.base, cover, 0, chosen, variables + fields)
    }

    override fun open(offer: Offer): Response {
        var variables = offer.variables
        var base = offer.link
        recipe.steps.forEach { step ->
            val page = fetch(step, variables, base)
            val found = step.fields.extract(page)
            found.forEach { (name, value) -> log("  $name = $value") }
            val missing = step.fields.keys.firstOrNull { it !in found }
            if (missing != null) throw IOException("$missing not found on ${page.base}")
            variables = variables + found
            base = page.base
        }
        val url = resolve(base, expand(recipe.download, variables))
        log("GET $url")
        val started = TimeSource.Monotonic.markNow()
        return request(downloads, url, null).also {
            val type = it.header("Content-Type").orEmpty()
            log("  ${it.code} · $type · ${elapsed(started)}")
            if (verbose) logHeaders(it)
        }
    }

    private fun fetch(
        step: RecipeStep,
        variables: Map<String, String>,
        base: String?,
    ): Scope {
        val url = resolve(base, expand(step.url, variables))
        val body = step.body?.let { expand(it, variables) }
        log("${if (body == null) "GET" else "POST"} $url")
        if (verbose && body != null) log("  body: $body")
        val started = TimeSource.Monotonic.markNow()
        return request(client, url, body).use { response ->
            val text = response.stream.readAtMost(MAX_PAGE_BYTES).decodeToString()
            log("  ${response.code} · ${text.length} characters · ${elapsed(started)}")
            if (verbose) {
                logHeaders(response)
                log("  page:\n${text.take(DEBUG_PAGE_CHARACTERS)}")
            }
            Scope(response.request.url.toString(), text)
        }
    }

    private fun logHeaders(response: Response) =
        response.headers.forEach { (name, value) -> log("  < $name: $value") }

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

    private val downloads by lazy {
        client.newBuilder().readTimeout(DOWNLOAD_MINUTES, TimeUnit.MINUTES).build()
    }

    private fun request(client: OkHttpClient, url: String, body: String?): Response {
        val request = Request.Builder().url(url)
        if (body != null) request.post(body.toRequestBody(FORM))
        val response = client.newCall(request.build()).execute()
        generateSequence(response) { it.priorResponse }.toList().asReversed()
            .zipWithNext { prior, next -> log("  ${prior.code} → ${next.request.url}") }
        if (response.isSuccessful) return response
        if (verbose) {
            logHeaders(response)
            val error = response.stream.readAtMost(MAX_PAGE_BYTES).decodeToString()
            log("  page:\n${error.take(DEBUG_PAGE_CHARACTERS)}")
        }
        response.close()
        throw HttpException(response.code, response.request.url.toString())
    }

    fun probe(query: Query) {
        val offers = find(query)
        offers.take(3).forEach { log("• ${it.title} · ${it.author}") }
        val first = offers.firstOrNull() ?: return log("no item with a title")
        first.variables.forEach { (name, value) -> log("  $name = $value") }
        open(first).use { file ->
            val bytes = file.body.contentLength()
            log("file: ${file.header("Content-Type").orEmpty()} · $bytes bytes")
        }
    }
}

private fun hideQuery(url: String) = url.replace(QUERY_VALUE, "$1$HIDDEN")

private fun RecipeStep.redacted() = copy(
    url = hideQuery(url),
    body = body?.let {
        HIDDEN
    },
)

fun Recipe.redacted() = copy(
    headers = headers.mapValues { HIDDEN },
    search = search.redacted(),
    steps = steps.map { it.redacted() },
    download = hideQuery(download),
)

fun Recipe.redact(text: String) = headers.values.filter { it.isNotBlank() }
    .fold(text) { result, secret -> result.replace(secret, HIDDEN) }
    .let(::hideQuery)
    .replace(SECRET_HEADER, "$1 $HIDDEN")
    .replace(BODY_LINE, "$1 $HIDDEN")

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
}

private fun templateProblem(
    template: String,
    known: Set<String>,
    url: Boolean,
    absolute: Boolean = false,
): String? {
    val names = PLACEHOLDER.findAll(template).map { it.groupValues[2] }
    val unknown = names.firstOrNull { it !in known }
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

class OwnSources(private val context: Context) {
    private val file = context.filesDir.resolve(SOURCES_FILE)
    private val writing = Mutex()
    val recipes = MutableStateFlow(read())

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
        context.writeTo(uri) { it.write(recipesText(recipes.value).toByteArray()) }

    private suspend fun write(change: (List<Recipe>) -> List<Recipe>) = writing.withLock {
        withContext(Dispatchers.IO) {
            val next = change(recipes.value)
            replaceFile(file) { it.writeText(json.encodeToString(next)) }
            recipes.value = next
        }
    }
}
