package dev.tn3w.shelf.data

import android.content.Context
import android.net.Uri
import dev.tn3w.shelf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

private const val MAX_REDIRECTS = 5
private const val MAX_PAGE_BYTES = 5L * 1024 * 1024
private const val PAGE_TIMEOUT = 30_000
private const val REGEX_MILLIS = 2_000L
private const val DEBUG_ITEMS = 5
private const val DEBUG_PAGE_CHARACTERS = 100_000
private val PLACEHOLDER = Regex("""\{(\+?)(\w+)\}""")
private val HOST = Regex("""://([^/:?#{]+)""")
private val QUERY_VARIABLES =
    setOf("query", "title", "author", "language", "isbn", "isbns")
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
    val source: String,
    val base: String,
    val element: Element? = null,
    val node: JsonElement? = null,
) {
    val value
        get() = element?.text() ?: (node as? JsonPrimitive)?.content ?: source

    fun select(css: String) = (element ?: Jsoup.parse(source, base)).select(css).map {
        Scope(it.outerHtml(), base, element = it)
    }

    fun at(path: String): List<Scope> {
        val root = node ?: json.parseToJsonElement(source)
        val found = path.split('.').filter { it.isNotEmpty() }
            .fold<String, JsonElement?>(root) { current, key -> current.child(key) }
            ?: return emptyList()
        val nodes = if (found is JsonArray) found.toList() else listOf(found)
        return nodes.map { Scope(it.toString(), base, node = it) }
    }

    fun attribute(name: String): Scope {
        val absolute = element?.absUrl(name).orEmpty()
        return Scope(absolute.ifEmpty { element?.attr(name).orEmpty() }, base)
    }

    fun match(regex: Regex) = regex.findAll(Deadline(source)).map { found ->
        Scope(found.groupValues.getOrElse(1) { found.value }, base)
    }.toList()
}

private class Deadline(
    private val text: CharSequence,
    private val end: Long = System.nanoTime() + REGEX_MILLIS * 1_000_000,
) : CharSequence by text {
    override fun get(index: Int): Char {
        if (System.nanoTime() > end) throw IOException("regex ran over $REGEX_MILLIS ms")
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

fun isbnsFor(book: Book, saved: String, catalogue: List<String>) =
    (listOf(saved, book.libraryCover?.isbn.orEmpty()) + catalogue)
        .mapNotNull(::normalizedIsbn)
        .distinct()

private fun HttpURLConnection.text(): String {
    val output = ByteArrayOutputStream()
    inputStream.use { input ->
        copyWithProgress(input, output) { size ->
            check(size <= MAX_PAGE_BYTES) { "page too large" }
        }
    }
    return output.toByteArray().decodeToString()
}

private fun elapsed(started: Long) = "${(System.nanoTime() - started) / 1_000_000} ms"

data class OwnSource(val recipe: Recipe) : Provider {
    var log: (String) -> Unit = {}
    var verbose = false
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
        if (query.isbns.isEmpty()) return emptyList<Offer>().also { log("no ISBN known") }
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
            ?.let { runCatching { URI(resolve(item.base, it)).toURL() }.getOrNull() }
            ?.takeIf { isPermitted(it, local = true) }
            ?.toString()
        val author = fields["author"].orEmpty()
        val chosen = format ?: recipe.format
        return Offer(this, title, author, item.base, cover, 0, chosen, variables + fields)
    }

    override fun open(offer: Offer): HttpURLConnection {
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
        val started = System.nanoTime()
        return request(url, null, DOWNLOAD_TIMEOUT).also {
            val type = it.contentType.orEmpty()
            log("  ${it.responseCode} · $type · ${elapsed(started)}")
            if (verbose) headers(it)
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
        val started = System.nanoTime()
        val connection = request(url, body, PAGE_TIMEOUT)
        val text = connection.text()
        val code = connection.responseCode
        log("  $code · ${text.length} characters · ${elapsed(started)}")
        if (verbose) {
            headers(connection)
            log("  page:\n${text.take(DEBUG_PAGE_CHARACTERS)}")
        }
        return Scope(text, connection.url.toString())
    }

    private fun headers(connection: HttpURLConnection) =
        connection.headerFields.forEach { (name, values) ->
            if (name != null) log("  < $name: ${values.joinToString()}")
        }

    private fun send(
        url: String,
        sent: Map<String, String>,
        body: String?,
        timeout: Int,
    ) = connect(url, local = true).apply {
        instanceFollowRedirects = false
        readTimeout = timeout
        sent.forEach { (name, value) -> setRequestProperty(name, value) }
        if (body == null) return@apply
        requestMethod = "POST"
        doOutput = true
        outputStream.use { it.write(body.toByteArray()) }
    }

    private fun request(url: String, body: String?, timeout: Int): HttpURLConnection {
        var target = URI(url)
        var sent = recipe.headers
        var payload = body
        repeat(MAX_REDIRECTS + 1) {
            val connection = send(target.toString(), sent, payload, timeout)
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (code !in 300..399 || location == null) return checked(connection)
            connection.disconnect()
            val next = target.resolve(location)
            log("  $code → $next")
            if (next.host != target.host) sent = emptyMap()
            if (code != 307 && code != 308) payload = null
            target = next
        }
        throw IOException("too many redirects from $url")
    }

    private fun checked(connection: HttpURLConnection): HttpURLConnection {
        val code = connection.responseCode
        if (code in 200..299) return connection
        if (verbose) {
            headers(connection)
            val error = connection.errorStream?.use { it.readBytes().decodeToString() }
            log("  page:\n${error.orEmpty().take(DEBUG_PAGE_CHARACTERS)}")
        }
        throw IOException("HTTP $code from ${connection.url}")
    }

    fun probe(query: Query) {
        val offers = find(query)
        offers.take(3).forEach { log("• ${it.title} · ${it.author}") }
        val first = offers.firstOrNull() ?: return log("no item with a title")
        first.variables.forEach { (name, value) -> log("  $name = $value") }
        val file = open(first)
        log("file: ${file.contentType.orEmpty()} · ${file.contentLengthLong} bytes")
        file.disconnect()
    }
}

fun Recipe.redacted() = copy(headers = headers.mapValues { "<hidden>" })

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
    if (!sample.isAbsolute && !absolute) return null
    val parsed = runCatching { sample.toURL() }.getOrNull()
    if (parsed != null && isPermitted(parsed, local = true)) return null
    return "$template must start with https:// (http:// only for local hosts)"
}

private fun Pick.problem(): String? {
    if (listOfNotNull(css, json, attr, regex).isEmpty()) return "empty rule"
    val error = runCatching {
        css?.let { Jsoup.parse("").select(it) }
        regex?.let { Regex(it) }
    }.exceptionOrNull() ?: return null
    return "invalid rule: ${error.message?.lineSequence()?.first()}"
}

data class Imported(val added: List<Recipe>, val total: Int)

class OwnSources(private val context: Context) {
    private val file = context.filesDir.resolve("sources.json")
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
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                }
            }.getOrNull()
        } ?: return null
        val found = parseRecipes(text).getOrNull() ?: return null
        val added = found.filter { it.problems().isEmpty() }
            .map { it.copy(id = UUID.randomUUID().toString()) }
        write { recipes -> recipes + added }
        return Imported(added, found.size)
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(recipesText(recipes.value).toByteArray())
            } != null
        }.getOrDefault(false)
    }

    private suspend fun write(change: (List<Recipe>) -> List<Recipe>) =
        withContext(Dispatchers.IO) {
            val next = change(recipes.value)
            replaceFile(file) { it.writeText(json.encodeToString(next)) }
            recipes.value = next
        }
}
