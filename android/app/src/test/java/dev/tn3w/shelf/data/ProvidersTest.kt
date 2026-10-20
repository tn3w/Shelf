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
import java.util.concurrent.CopyOnWriteArrayList

private const val TOKEN = "Bearer shelf-test"
private val PRIDE = Query("Pride and Prejudice", "Jane Austen", "en")
private val PRIDE_QUERY = Query("Pride", "", "en")
private val KAFKA = Query("Die Verwandlung", "Franz Kafka", "de")
private const val KEY = "k3y9"
private const val KEY_PATTERN =
    "download\\?md5=[a-fA-F0-9]{32}&(?:amp;)?key=([A-Za-z0-9]+)"
private val BOOKS = listOf(
    Triple("Pride and Prejudice", "Jane Austen", "a".repeat(32)),
    Triple("Pride and Prejudice (Illustrated)", "Jane Austen", "b".repeat(32)),
    Triple("Emma", "Jane Austen", "c".repeat(32)),
)
private val SOURCES = File("src/main/assets/sources")

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

private fun feed(vararg entries: String) =
    """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"
        xmlns:media="http://search.yahoo.com/mrss/">
        <id>https://example.org/search</id>${entries.joinToString("")}</feed>"""

private fun bundled(id: String) =
    parseRecipe(SOURCES.resolve("$id.json").readText()).getOrThrow().copy(id = id)

private fun offer(title: String, author: String = "", link: String = title) =
    Offer(Provider(STARTER_RECIPE), title, author, link)

class ProvidersTest {
    private lateinit var server: HttpServer
    private val fileHeaders = mutableListOf<String?>()
    private val pages = mutableMapOf<String, String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val port
        get() = server.address.port

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), "UTF-8")
            val page = pages[exchange.requestURI.path]
            exchange.reply(if (page == null) 404 else 200, page.orEmpty())
        }
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

    private fun local(id: String, origin: String): Provider {
        val text = bundled(id).toText().replace(origin, "http://127.0.0.1:$port")
        return Provider(parseRecipe(text).getOrThrow().copy(id = id))
    }

    private fun Provider.offers(query: Query) = runBlocking { search(query) }

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
    fun providersAreOffByDefault() {
        val recipes = listOf(bundled("gutenberg"), htmlRecipe())
        assertTrue(Settings().enabledProviders(recipes).isEmpty())
        val chosen = Settings(providers = setOf("gutenberg", "gone"))
        assertEquals(listOf("gutenberg"), chosen.enabledProviders(recipes).map { it.id })
    }

    @Test
    fun instantSourceIsOffByDefault() {
        val recipes = listOf(bundled("gutenberg"))
        assertNull(Settings().instantProvider(recipes))
        assertNull(Settings(instantSource = "gone").instantProvider(recipes))
        val chosen = Settings(instantSource = "gutenberg").instantProvider(recipes)
        assertEquals("gutenberg", chosen?.id)
    }

    @Test
    fun bundledSourcesAreValidRecipes() {
        val files = SOURCES.listFiles().orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val recipe = bundled(file.nameWithoutExtension)
            assertEquals(file.name, emptyList<String>(), recipe.problems())
            assertTrue(file.name, recipe.about.containsKey("en"))
        }
    }

    @Test
    fun providersServeTheirLanguages() {
        assertTrue(Provider(bundled("standard-ebooks")).serves("en"))
        assertFalse(Provider(bundled("standard-ebooks")).serves("de"))
        assertTrue(Provider(bundled("gutenberg")).serves("de"))
    }

    @Test
    fun standardEbooksOffersCompatibleEpubWithCoverAndSize() {
        pages["/feeds/atom/all"] = feed(
            """<entry><title>Pride and Prejudice</title>
            <author><name>Jane Austen</name></author>
            <media:thumbnail url="https://standardebooks.org/cover.jpg"/>
            <link href="https://standardebooks.org/a" type="application/xhtml+xml"/>
            <link href="https://standardebooks.org/a.epub" length="831959"
                type="application/epub+zip"/>
            <link href="https://standardebooks.org/b.epub" type="application/epub+zip"/>
            </entry>""",
            "<entry><title>Pride and Prejudice</title></entry>",
        )
        val offer = local("standard-ebooks", "https://standardebooks.org").offers(PRIDE)
            .single()
        assertEquals("Jane Austen", offer.author)
        assertEquals("https://standardebooks.org/a.epub", offer.link)
        assertEquals("https://standardebooks.org/cover.jpg", offer.cover)
        assertEquals(831959L, offer.bytes)
    }

    @Test
    fun gutenbergLooksUpEpubAndDropsAudioBooks() {
        pages["/ebooks/search.opds/"] = feed(
            """<entry><id>https://www.gutenberg.org/ebooks/1342.opds</id>
            <title>Pride and Prejudice</title><content>Jane Austen</content></entry>""",
            """<entry><id>https://www.gutenberg.org/ebooks/7.opds</id>
            <title>Pride and Prejudice</title><content>Jane Austen</content></entry>""",
            """<entry><id>https://www.gutenberg.org/ebooks/authors.opds</id>
            <title>Authors</title></entry>""",
        )
        pages["/ebooks/1342.opds"] = feed(
            """<entry>
            <link type="application/epub+zip" length="10" href="https://g.org/1342.epub"/>
            <link type="application/epub+zip" length="20"
                href="https://g.org/1342.epub3.images"/>
            </entry>""",
        )
        pages["/ebooks/7.opds"] =
            feed("""<entry><link type="audio/mpeg" href="/7.mp3"/></entry>""")
        val offer = local("gutenberg", "https://www.gutenberg.org").offers(PRIDE).single()
        assertEquals("Jane Austen", offer.author)
        assertEquals("https://g.org/1342.epub3.images", offer.link)
        assertEquals(20L, offer.bytes)
        assertTrue(offer.cover.orEmpty().endsWith("/1342/pg1342.cover.small.jpg"))
        assertTrue(requests.any { it.endsWith("Jane Austen l.en") })
    }

    @Test
    fun gutenbergDropsLanguageNoteAndDecodesEntities() {
        pages["/ebooks/search.opds/"] = feed(
            """<entry><id>https://www.gutenberg.org/ebooks/22367.opds</id>
            <title>Die Verwandlung &amp; Anderes (German)</title>
            <content>Franz Kafka</content></entry>""",
        )
        pages["/ebooks/22367.opds"] = feed(
            """<entry><link type="application/epub+zip" length="1"
                href="https://g.org/22367.epub3.images"/></entry>""",
        )
        val offer = local("gutenberg", "https://www.gutenberg.org").offers(KAFKA).single()
        assertEquals("Die Verwandlung & Anderes", offer.title)
    }

    @Test
    fun wikisourceKeepsWorksOnly() {
        pages["/w/api.php"] = """{"query":{"pages":[
            {"title":"Die Verwandlung (Franz Kafka)","index":3},
            {"title":"Die Verwandlung","index":1,"pageprops":{"disambiguation":""}},
            {"title":"Die Verwandlung (1915)","index":2},
            {"title":"Die Verwandlung/Kapitel 1","index":4},
            {"title":"Pride and Prejudice","index":5,
                "categories":[{"ns":14,"title":"Category:Versions pages"}]}]}}"""
        val source = local("wikisource", "https://{language}.wikisource.org")
        val offers = source.offers(KAFKA)
        assertEquals(
            listOf("Die Verwandlung (1915)", "Die Verwandlung (Franz Kafka)"),
            offers.map { it.title },
        )
        assertEquals(
            "https://ws-export.wmcloud.org/?format=epub-3&lang=de" +
                "&page=Die%20Verwandlung%20%281915%29",
            offers.first().link,
        )
        assertEquals("wikisource.org", Provider(bundled("wikisource")).site)
        pages["/w/api.php"] = """{"batchcomplete":true}"""
        assertTrue(source.offers(PRIDE).isEmpty())
    }

    @Test
    fun internetArchiveSearchesFreeEpubsAndDownloadsPublicFile() {
        pages["/advancedsearch.php"] = """{"response":{"docs":[
            {"identifier":"one","title":"Pride and prejudice;",
                "creator":["Austen, Jane, 1775-1817, author","C. E. Brock"]},
            {"identifier":"two","title":"Pride and Prejudice","creator":"Jane Austen"},
            {"identifier":"three"}]}}"""
        pages["/metadata/one/files"] = """{"result":[
            {"name":"hidden.epub","size":"5","private":"true"},
            {"name":"Pride and Prejudice.epub","size":"539764"},
            {"name":"pride.pdf","size":"9"}]}"""
        pages["/metadata/two/files"] = """{"result":[{"name":"a.pdf","size":"1"}]}"""
        val source = local("internet-archive", "https://archive.org")
        val offer = source.offers(PRIDE).single()
        assertEquals("Pride and prejudice", offer.title)
        assertEquals("Austen, Jane", offer.author)
        assertTrue(offer.link.endsWith("/download/one/Pride%20and%20Prejudice.epub"))
        assertEquals(539764L, offer.bytes)
        assertTrue(offer.cover.orEmpty().endsWith("/services/img/one"))
        val search = requests.first { it.startsWith("q=") }
        assertTrue("title:(pride and prejudice) AND creator:(austen*)" in search)
        assertTrue("language:(eng OR English OR en)" in search)
        assertTrue("NOT access-restricted-item:true" in search)
    }

    @Test
    fun rankKeepsMatchingTitlesAndAuthorsShortestFirst() {
        val ranked = rank(
            PRIDE,
            listOf(
                offer("Pride and Prejudice: Illustrated Edition", "Austen, Jane, 1775"),
                offer("Pride and Prejudice", "Jane Austen"),
                offer("Pride and Prejudice and Zombies", "Seth Grahame-Smith"),
                offer("Emma", "Jane Austen"),
                offer("Pride and Prejudice (1813)"),
            ),
        )
        assertEquals(
            listOf(
                "Pride and Prejudice",
                "Pride and Prejudice (1813)",
                "Pride and Prejudice: Illustrated Edition",
            ),
            ranked.map { it.title },
        )
    }

    @Test
    fun rankDropsDuplicatesAndLimitsOffers() {
        val offers = (1..8).map { offer("Pride and Prejudice $it") } +
            offer("Pride and Prejudice", link = "Pride and Prejudice 1")
        val ranked = rank(PRIDE, offers)
        assertEquals(5, ranked.size)
        assertEquals(5, ranked.map { it.link }.toSet().size)
    }

    @Test
    fun rankIgnoresArticlesAndSubtitles() {
        val query = Query("Die Verwandlung: Erzählung", "Franz Kafka", "de")
        val ranked = rank(query, listOf(offer("Verwandlung (German)", "Franz Kafka")))
        assertEquals(1, ranked.size)
    }

    @Test
    fun downloadsMustMatchTheirFormat() {
        val file = File.createTempFile("book", ".bin")
        try {
            file.writeText("<html>Not found</html>")
            assertFalse(isFormat(file, "epub"))
            assertTrue(isFormat(file, "txt"))
            file.writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4))
            assertTrue(isFormat(file, "epub"))
            file.writeBytes(ByteArray(0))
            assertFalse(isFormat(file, "pdf"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun searchListsEveryMatchingItem() = runBlocking<Unit> {
        val offers = Provider(htmlRecipe()).find(Query("Pride and Prejudice", "", "en"))
        assertEquals(
            listOf("Pride and Prejudice", "Pride and Prejudice (Illustrated)"),
            offers.map { it.title },
        )
        assertEquals("Jane Austen", offers.first().author)
        assertEquals("a".repeat(32), offers.first().variables["md5"])
    }

    @Test
    fun stepsExtractKeyAndRedirectToFileWithoutLeakingHeaders() = runBlocking<Unit> {
        val offer = Provider(htmlRecipe()).offers(Query("Emma", "", "en")).single()
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
        val source = Provider(htmlRecipe().copy(headers = emptyMap()))
        val error = runCatching {
            source.offers(Query("Emma", "", "en"))
        }.exceptionOrNull()
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
        val offer = Provider(recipe).offers(Query("Emma", "", "en")).single()
        assertEquals("Emma", offer.title)
        assertEquals("Jane Austen", offer.author)
        assertEquals("c".repeat(32), offer.variables["id"])
        assertEquals(200, runBlocking { Provider(recipe).open(offer).use { it.code } })
    }

    @Test
    fun skipRuleDropsItems() = runBlocking<Unit> {
        val search = htmlRecipe().search
        val skip = Pick(css = ".title", regex = "Illustrated")
        val recipe = htmlRecipe().copy(
            search = search.copy(fields = search.fields + ("skip" to skip)),
        )
        val offers = Provider(recipe).find(PRIDE_QUERY)
        assertEquals(listOf("Pride and Prejudice"), offers.map { it.title })
    }

    @Test
    fun probeTracesEveryRequest() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        val source = Provider(htmlRecipe(), log = { lines += it })
        source.probe(Query("Emma", "Jane Austen", "en"))
        assertTrue(lines.any { it.startsWith("GET http://127.0.0.1:$port/search") })
        assertTrue(lines.any { it.trim() == "key = $KEY" })
        assertTrue(lines.any { it.contains("/files/") })
    }

    @Test
    fun traceShowsEachRequestBeforeItsAnswerAndEveryRedirect() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        Provider(htmlRecipe(), log = { lines += it })
            .probe(Query("Emma", "", "en"))
        val request = lines.indexOfFirst {
            it.startsWith("GET http://127.0.0.1:$port/search")
        }
        val answer = lines.indexOfFirst { it.startsWith("  200 · ") }
        assertTrue(request in 0 until answer)
        assertTrue(lines.any { it.startsWith("  307 → http://localhost:$port/files/") })
    }

    @Test
    fun debugTraceAddsHeadersPagesAndItems() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        Provider(htmlRecipe(), log = { lines += it }, verbose = true)
            .probe(Query("Pride", "", "en"))
        assertTrue(lines.any { it.startsWith("  < Content-length: ") })
        assertTrue(lines.any { it.startsWith("  page:") && "<li class=\"book\">" in it })
        assertTrue(lines.any { it.startsWith("item 2: ") && "Illustrated" in it })
    }

    @Test
    fun runawayRegexStopsWithAnError() = runBlocking<Unit> {
        val recipe = htmlRecipe().copy(
            search = htmlRecipe().search.copy(
                url = "http://127.0.0.1:$port/slow",
                items = Pick(regex = "(.*a){20}$"),
            ),
        )
        val error = runCatching { Provider(recipe).find(PRIDE_QUERY) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("regex ran over"))
    }

    private fun isbnRecipe() = htmlRecipe().copy(
        search = htmlRecipe().search.copy(url = "http://127.0.0.1:$port/isbn?q={isbn}"),
    )

    @Test
    fun isbnSearchTriesEachIsbnAndKeepsExactMatches() {
        val lines = mutableListOf<String>()
        val source = Provider(isbnRecipe(), log = { lines += it })
        val query = PRIDE_QUERY.copy(isbns = listOf("9780439023481", "9780306406157"))
        val offers = source.offers(query)
        assertEquals(listOf("Other Edition"), offers.map { it.title })
        assertEquals("9780306406157", offers.single().variables["isbn"])
        assertEquals(2, lines.count { it.startsWith("GET http://127.0.0.1:$port/isbn") })
        assertTrue(isbnRecipe().problems().isEmpty())
    }

    @Test
    fun isbnSearchWithoutIsbnFindsNothing() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        val source = Provider(isbnRecipe(), log = { lines += it })
        assertTrue(source.find(PRIDE_QUERY).isEmpty())
        assertEquals(listOf("no ISBN known"), lines)
        assertFalse(Provider(htmlRecipe()).usesIsbn)
    }

    @Test
    fun providersWithSameRecipeAreEqualKeys() {
        val results = mapOf(Provider(htmlRecipe()) to 1)
        assertEquals(1, results[Provider(htmlRecipe())])
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
            cover = "http://example.org/{gone}",
            search = htmlRecipe().search.copy(fields = mapOf("md5" to Pick(regex = "("))),
        )
        val problems = broken.problems()
        assertTrue(problems.any { "name" in it })
        assertTrue(problems.any { "title" in it })
        assertTrue(problems.any { "invalid rule" in it })
        assertTrue(problems.any { "{missing}" in it })
        assertTrue(problems.any { "{gone}" in it })
    }

    @Test
    fun headersOnlyReachSearchHost() = runBlocking<Unit> {
        val step = RecipeStep(
            url = "http://localhost:$port/details?md5={md5}",
            fields = mapOf("key" to Pick(regex = KEY_PATTERN)),
        )
        val source = Provider(htmlRecipe().copy(steps = listOf(step)))
        val offer = source.find(Query("Emma", "", "en")).single()
        val error = runCatching { source.complete(offer) }.exceptionOrNull()
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
    fun ownSourceDescribesItsHost() {
        val settings = Settings(providers = setOf("local"))
        val enabled = settings.enabledProviders(listOf(htmlRecipe()))
        assertEquals(listOf("local"), enabled.map { it.id })
        assertTrue(enabled.single().recipe.languages.isEmpty())
        assertEquals("127.0.0.1", enabled.single().site)
    }
}
