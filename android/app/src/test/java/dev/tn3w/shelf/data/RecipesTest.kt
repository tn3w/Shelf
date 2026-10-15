package dev.tn3w.shelf.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

private const val TOKEN = "Bearer shelf-test"
private val PRIDE_QUERY = Query("Pride", "", "en")
private const val KEY = "k3y9"
private const val KEY_PATTERN =
    "download\\?md5=[a-fA-F0-9]{32}&(?:amp;)?key=([A-Za-z0-9]+)"
private val BOOKS = listOf(
    Triple("Pride and Prejudice", "Jane Austen", "a".repeat(32)),
    Triple("Pride and Prejudice (Illustrated)", "Jane Austen", "b".repeat(32)),
    Triple("Emma", "Jane Austen", "c".repeat(32)),
)

private fun HttpExchange.reply(code: Int, body: String = "", location: String? = null) {
    location?.let { responseHeaders.add("Location", it) }
    val bytes = body.toByteArray()
    sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    close()
}

private fun HttpExchange.parameters() = requestURI.rawQuery.orEmpty().split("&")
    .filter { "=" in it }
    .associate {
        it.substringBefore("=") to
            URLDecoder.decode(it.substringAfter("="), "UTF-8")
    }

private fun matching(query: String) = BOOKS.filter { (title, author) ->
    tokenize(query).any { it in tokenize("$title $author") }
}

class RecipesTest {
    private lateinit var server: HttpServer
    private val fileHeaders = mutableListOf<String?>()
    private val port
        get() = server.address.port

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            val items = matching(exchange.parameters()["q"].orEmpty()).joinToString("") {
                """<li class="book"><a href="/details?md5=${it.third}">
                <span class="title">${it.first}</span></a>
                <span class="author">${it.second}</span></li>"""
            }
            exchange.reply(200, "<html><ul>$items</ul></html>")
        }
        server.createContext("/details") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != TOKEN) {
                return@createContext exchange.reply(401)
            }
            val md5 = exchange.parameters()["md5"]
            exchange.reply(200, """<a href="/download?md5=$md5&amp;key=$KEY">Get</a>""")
        }
        server.createContext("/download") { exchange ->
            val parameters = exchange.parameters()
            if (parameters["key"] != KEY) return@createContext exchange.reply(403)
            val file = "http://localhost:$port/files/${parameters["md5"]}.epub"
            exchange.reply(307, location = file)
        }
        server.createContext("/files") { exchange ->
            fileHeaders += exchange.requestHeaders.getFirst("Authorization")
            exchange.reply(200, "PK\u0003\u0004 ${exchange.requestURI.path}")
        }
        server.createContext("/isbn") { exchange ->
            val found = exchange.parameters()["q"] == "9780306406157"
            val item = """<li class="book"><span class="title">Other Edition</span>
                <a href="/details?md5=${"a".repeat(32)}">x</a></li>"""
            exchange.reply(200, if (found) "<ul>$item</ul>" else "<ul></ul>")
        }
        server.createContext("/slow") { exchange ->
            exchange.reply(200, "a".repeat(40) + "!")
        }
        server.createContext("/api/search") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val request = json.parseToJsonElement(body).jsonObject
            val query = request.getValue("q").jsonPrimitive.content
            val results = matching(query).joinToString(",") {
                """{"name":"${it.first}","authors":["${it.second}"],"id":"${it.third}"}"""
            }
            exchange.reply(200, """{"data":{"results":[$results]}}""")
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun htmlRecipe() = Recipe(
        name = "Local",
        headers = mapOf("Authorization" to TOKEN),
        search = RecipeStep(
            url = "http://127.0.0.1:$port/search?q={query}",
            items = Pick(css = "li.book"),
            fields = mapOf(
                "title" to Pick(css = ".title"),
                "author" to Pick(css = ".author"),
                "md5" to Pick(css = "a", attr = "href", regex = "md5=([a-fA-F0-9]{32})"),
            ),
        ),
        steps = listOf(
            RecipeStep(
                url = "/details?md5={md5}",
                fields = mapOf("key" to Pick(regex = KEY_PATTERN)),
            ),
        ),
        download = "http://127.0.0.1:$port/download?md5={md5}&key={key}",
        id = "local",
    )

    @Test
    fun searchListsEveryMatchingItem() {
        val offers = OwnSource(htmlRecipe()).find(Query("Pride and Prejudice", "", "en"))
        assertEquals(
            listOf("Pride and Prejudice", "Pride and Prejudice (Illustrated)"),
            offers.map { it.title },
        )
        assertEquals("Jane Austen", offers.first().author)
        assertEquals("a".repeat(32), offers.first().variables["md5"])
    }

    @Test
    fun stepsExtractKeyAndRedirectToFileWithoutLeakingHeaders() {
        val source = OwnSource(htmlRecipe())
        val offer = source.find(Query("Emma", "", "en")).single()
        val target = File.createTempFile("book", ".epub")
        try {
            fetchBook(offer, target) {}
            val expected = "PK\u0003\u0004 /files/${"c".repeat(32)}.epub"
            assertEquals(expected, target.readText())
            assertEquals(listOf<String?>(null), fileHeaders)
        } finally {
            target.delete()
        }
    }

    @Test
    fun failedStepReportsStatus() {
        val recipe = htmlRecipe().copy(headers = emptyMap())
        val source = OwnSource(recipe)
        val offer = source.find(Query("Emma", "", "en")).single()
        val error = runCatching { source.open(offer) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("401"))
    }

    @Test
    fun jsonApiWithPostBody() {
        val recipe = Recipe(
            name = "API",
            search = RecipeStep(
                url = "http://127.0.0.1:$port/api/search",
                body = """{"q":"{+query}"}""",
                items = Pick(json = "data.results"),
                fields = mapOf(
                    "title" to Pick(json = "name"),
                    "author" to Pick(json = "authors.0"),
                    "id" to Pick(json = "id"),
                ),
            ),
            download = "http://127.0.0.1:$port/files/{id}.epub",
        )
        val offers = OwnSource(recipe).find(Query("Emma", "", "en"))
        assertEquals("Emma", offers.single().title)
        assertEquals("Jane Austen", offers.single().author)
        assertEquals("c".repeat(32), offers.single().variables["id"])
        assertEquals(200, OwnSource(recipe).open(offers.single()).use { it.code })
    }

    @Test
    fun probeTracesEveryRequest() {
        val lines = mutableListOf<String>()
        val source = OwnSource(htmlRecipe(), log = { lines += it })
        source.probe(Query("Emma", "Jane Austen", "en"))
        assertTrue(lines.any { it.startsWith("GET http://127.0.0.1:$port/search") })
        assertTrue(lines.any { it.trim() == "key = $KEY" })
        assertTrue(lines.any { it.contains("/files/") })
    }

    @Test
    fun traceShowsEachRequestBeforeItsAnswerAndEveryRedirect() {
        val lines = mutableListOf<String>()
        OwnSource(htmlRecipe(), log = { lines += it })
            .probe(Query("Emma", "", "en"))
        val request = lines.indexOfFirst {
            it.startsWith("GET http://127.0.0.1:$port/search")
        }
        val answer = lines.indexOfFirst { it.startsWith("  200 · ") }
        assertTrue(request in 0 until answer)
        assertTrue(lines.any { it.startsWith("  307 → http://localhost:$port/files/") })
    }

    @Test
    fun debugTraceAddsHeadersPagesAndItems() {
        val lines = mutableListOf<String>()
        OwnSource(htmlRecipe(), log = { lines += it }, verbose = true)
            .probe(Query("Pride", "", "en"))
        assertTrue(lines.any { it.startsWith("  < Content-length: ") })
        assertTrue(lines.any { it.startsWith("  page:") && "<li class=\"book\">" in it })
        assertTrue(lines.any { it.startsWith("item 2: ") && "Illustrated" in it })
    }

    @Test
    fun runawayRegexStopsWithAnError() {
        val recipe = htmlRecipe().copy(
            search = htmlRecipe().search.copy(
                url = "http://127.0.0.1:$port/slow",
                items = Pick(regex = "(.*a){20}$"),
            ),
        )
        val error = runCatching { OwnSource(recipe).find(PRIDE_QUERY) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("regex ran over"))
    }

    private fun isbnRecipe() = htmlRecipe().copy(
        search = htmlRecipe().search.copy(url = "http://127.0.0.1:$port/isbn?q={isbn}"),
    )

    @Test
    fun isbnSearchTriesEachIsbnAndKeepsExactMatches() {
        val lines = mutableListOf<String>()
        val source = OwnSource(isbnRecipe(), log = { lines += it })
        val query = PRIDE_QUERY.copy(isbns = listOf("9780439023481", "9780306406157"))
        val offers = runBlocking { source.search(query) }
        assertEquals(listOf("Other Edition"), offers.map { it.title })
        assertEquals("9780306406157", offers.single().variables["isbn"])
        assertEquals(2, lines.count { it.startsWith("GET http://127.0.0.1:$port/isbn") })
        assertTrue(isbnRecipe().problems().isEmpty())
    }

    @Test
    fun isbnSearchWithoutIsbnFindsNothing() {
        val lines = mutableListOf<String>()
        val source = OwnSource(isbnRecipe(), log = { lines += it })
        assertTrue(source.find(PRIDE_QUERY).isEmpty())
        assertEquals(listOf("no ISBN known"), lines)
        assertFalse(OwnSource(htmlRecipe()).usesIsbn)
    }

    @Test
    fun ownSourcesWithSameRecipeAreEqualKeys() {
        val results = mapOf<Provider, Int>(OwnSource(htmlRecipe()) to 1)
        assertEquals(1, results[OwnSource(htmlRecipe())])
    }

    @Test
    fun redactedRecipeHidesHeaderValues() {
        val hidden = mapOf("Authorization" to "<hidden>")
        assertEquals(hidden, htmlRecipe().redacted().headers)
    }

    @Test
    fun expandEncodesUnlessRaw() {
        val variables = mapOf("q" to "a b&c", "link" to "/x?y=1")
        assertEquals("s?q=a%20b%26c", expand("s?q={q}", variables))
        assertEquals("/x?y=1", expand("{+link}", variables))
        assertEquals("""{"q":"a b&c"}""", expand("""{"q":"{+q}"}""", variables))
    }

    @Test
    fun validationExplainsProblems() {
        assertTrue(htmlRecipe().problems().isEmpty())
        assertTrue(STARTER_RECIPE.problems().isEmpty())
        val broken = htmlRecipe().copy(
            name = "",
            download = "http://example.org/{missing}",
            search = htmlRecipe().search.copy(fields = mapOf("md5" to Pick(regex = "("))),
        )
        val problems = broken.problems()
        assertTrue(problems.any { "name" in it })
        assertTrue(problems.any { "title" in it })
        assertTrue(problems.any { "invalid rule" in it })
        assertTrue(problems.any { "{missing}" in it })
    }

    @Test
    fun headersOnlyReachSearchHost() {
        val step = RecipeStep(
            url = "http://localhost:$port/details?md5={md5}",
            fields = mapOf("key" to Pick(regex = KEY_PATTERN)),
        )
        val source = OwnSource(htmlRecipe().copy(steps = listOf(step)))
        val offer = source.find(Query("Emma", "", "en")).single()
        val error = runCatching { source.open(offer) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("401"))
    }

    @Test
    fun readsStopAtLimit() {
        assertEquals(3, "abc".byteInputStream().readAtMost(3).size)
        val error = runCatching {
            "abcd".byteInputStream().readAtMost(3)
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun debugReportHidesSecrets() {
        val recipe = htmlRecipe().copy(
            search = htmlRecipe().search.copy(body = "secret=b0dy"),
            download = "https://example.org/get?apikey=abc123&md5={md5}",
        )
        val trace = listOf(
            "GET https://example.org/get?apikey=abc123&md5=emma",
            "  body: secret=b0dy",
            "  < Set-Cookie: session=s3cret",
            "  < Content-Type: text/html",
            "  page: echo $TOKEN",
        ).map(recipe::redact)
        val text = recipe.redacted().toText() + trace
        listOf("abc123", "emma", "b0dy", "s3cret", TOKEN).forEach {
            assertFalse(it, it in text)
        }
        assertTrue("Content-Type: text/html" in text)
        assertTrue("md5={md5}" in text)
    }

    @Test
    fun recipeFilesHoldOneOrManySources() {
        val text = recipesText(listOf(htmlRecipe(), STARTER_RECIPE))
        val parsed = parseRecipes(text).getOrThrow()
        assertEquals(listOf("Local", STARTER_RECIPE.name), parsed.map { it.name })
        assertTrue(parsed.all { it.id.isEmpty() })
        val single = parseRecipes(htmlRecipe().toText()).getOrThrow().single()
        assertEquals("Local", single.name)
        assertTrue(parseRecipe("""{"name":"x","serch":{}}""").isFailure)
    }

    @Test
    fun ownSourceRanksLikeBuiltIns() {
        val settings = Settings(providers = setOf("local"))
        val enabled = settings.enabledProviders(listOf(htmlRecipe()))
        assertEquals(listOf("local"), enabled.map { it.id })
        assertNull(enabled.single().languages.firstOrNull())
        assertEquals("127.0.0.1", enabled.single().site)
    }
}
