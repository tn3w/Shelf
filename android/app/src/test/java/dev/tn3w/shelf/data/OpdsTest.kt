package dev.tn3w.shelf.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Challenge
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

private const val BASE = "https://books.example/opds/"

private val ATOM = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
  <title>Shelf Test Library</title>
  <link rel="next" type="application/atom+xml;profile=opds-catalog" href="?page=2"/>
  <link rel="search" type="application/opensearchdescription+xml" href="/osd.xml"/>
  <entry>
    <title>Recently added</title>
    <link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="new"/>
  </entry>
  <entry>
    <title>A Test Novel</title>
    <author><name>Ada Writer</name></author>
    <author><name>Bo Helper</name></author>
    <summary>A book made for tests.</summary>
    <link rel="http://opds-spec.org/image/thumbnail" type="image/jpeg" href="/c/1.jpg"/>
    <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/f/1.pdf"/>
    <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
      title="EPUB" length="1234" href="/f/1.epub"/>
    <link rel="http://opds-spec.org/acquisition/buy" type="application/epub+zip" href="/b"/>
    <link rel="http://opds-spec.org/acquisition" type="application/x-unknown" href="/f/1"/>
  </entry>
</feed>"""

private val JSON_FEED = """{
  "metadata": {"title": "Json Library"},
  "links": [
    {"rel": "next", "href": "?page=2", "type": "application/opds+json"},
    {"rel": "search", "href": "/search{?query,limit}", "templated": true}
  ],
  "navigation": [{"title": "Fiction", "href": "fiction"}],
  "groups": [{"metadata": {"title": "New"}, "publications": [{
    "metadata": {"title": "Json Novel", "author": [{"name": "Cy Coder"}, "Di Dev"],
      "description": "<p>Short <b>blurb</b></p>"},
    "links": [
      {"rel": "http://opds-spec.org/acquisition/open-access",
       "type": "application/epub+zip", "href": "/f/2.epub"},
      {"rel": "alternate", "type": "application/opds-publication+json",
       "href": "/p/2"}
    ],
    "images": [{"href": "/c/2.jpg"}]
  }]}]
}"""

private fun HttpExchange.reply(code: Int, body: String = "", challenge: String? = null) {
    challenge?.let { responseHeaders.add("WWW-Authenticate", it) }
    val bytes = body.toByteArray()
    sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    close()
}

private val BOOK_PAGE = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
    <title>Pride and Prejudice</title>
    <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/1.epub"/>
    </entry></feed>"""

private val AUDIO_PAGE = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
    <title>Pride and Prejudice</title>
    <link rel="http://opds-spec.org/acquisition" type="audio/mpeg" href="/2.mp3"/>
    </entry></feed>"""

private fun results(query: String) = """<feed xmlns="http://www.w3.org/2005/Atom">
    <title>$query</title>
    <entry><title>Pride and Prejudice</title><content>Jane Austen</content>
      <link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/b/1"/>
    </entry>
    <entry><title>Pride and Prejudice</title>
      <link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/b/2"/>
    </entry>
    <entry><title>Emma</title>
      <link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/b/3"/>
    </entry></feed>"""

class OpdsTest {
    private lateinit var server: HttpServer
    private val basic = Credentials.basic("reader", "secret")
    private val root
        get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != basic) {
                val challenge = "Basic realm=\"Home\""
                return@createContext exchange.reply(401, challenge = challenge)
            }
            when (exchange.requestURI.path) {
                "/opds" -> exchange.reply(200, ATOM)
                "/f/1.epub" -> exchange.reply(200, "PK\u0003\u0004 test book")
                "/f/1.pdf" -> exchange.reply(200, "<html>login</html>")
                "/search" -> exchange.reply(200, results(exchange.requestURI.query))
                "/b/1" -> exchange.reply(200, BOOK_PAGE)
                "/b/2" -> exchange.reply(200, AUDIO_PAGE)
                else -> exchange.reply(404)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client(password: String? = null): CatalogClient {
        val logins = password?.let { mapOf("127.0.0.1" to Login("reader", it)) }
        return CatalogClient(Catalog("Home", "$root/opds", logins.orEmpty()))
    }

    @Test
    fun atomFeedKeepsBooksFoldersPagesAndSearch() {
        val feed = parseFeed(ATOM, BASE)
        assertEquals("Shelf Test Library", feed.title)
        assertEquals("https://books.example/opds/?page=2", feed.next)
        assertEquals("https://books.example/osd.xml", feed.searchDescription)
        assertNull(feed.search)
        val (folder, book) = feed.entries
        assertEquals("https://books.example/opds/new", folder.link)
        assertTrue(folder.files.isEmpty())
        assertEquals("Ada Writer, Bo Helper", book.author)
        assertEquals("A book made for tests.", book.summary)
        assertEquals("https://books.example/c/1.jpg", book.cover)
        assertEquals(listOf("epub", "pdf"), book.files.map { it.format })
        assertEquals(1234L, book.files.first().bytes)
    }

    @Test
    fun jsonFeedFlattensGroupsAndReadsPublications() {
        val feed = parseFeed(JSON_FEED, BASE)
        assertEquals("Json Library", feed.title)
        assertEquals("https://books.example/search{?query,limit}", feed.search)
        val (folder, book) = feed.entries
        assertEquals("https://books.example/opds/fiction", folder.link)
        assertEquals("Cy Coder, Di Dev", book.author)
        assertEquals("Short blurb", book.summary)
        assertEquals("https://books.example/p/2", book.link)
        assertEquals("https://books.example/f/2.epub", book.files.single().url)
    }

    @Test
    fun singlePublicationBecomesOneEntry() {
        val publication = """{"metadata": {"title": "Alone"}, "links": [
            {"rel": "http://opds-spec.org/acquisition", "type": "application/pdf",
             "href": "https://books.example/alone.pdf"}]}"""
        val entry = parseFeed(publication, BASE).entries.single()
        assertEquals("Alone", entry.title)
        assertEquals("pdf", entry.files.single().format)
    }

    @Test
    fun searchTemplatesFillTheQuery() {
        assertEquals(
            "https://a.example/s?query=war%20and%20peace",
            searchUrl("https://a.example/s{?query,limit}", "war and peace"),
        )
        assertEquals(
            "https://a.example/s?q=tolstoy&p=",
            searchUrl("https://a.example/s?q={searchTerms}&p={startPage?}", "tolstoy"),
        )
        val description = """<OpenSearchDescription>
            <Url type="text/html" template="https://a.example/html?q={searchTerms}"/>
            <Url type="application/atom+xml" template="https://a.example/q={searchTerms}"/>
            </OpenSearchDescription>"""
        assertEquals("https://a.example/q={searchTerms}", searchTemplate(description))
    }

    @Test
    fun catalogSearchFollowsResultsToTheirFiles() = runBlocking {
        val catalog = Catalog(
            "Home",
            "$root/opds",
            mapOf("127.0.0.1" to Login("reader", "secret")),
            search = "$root/search?q={searchTerms}%20l.{language}",
        )
        val found = CatalogClient(catalog).find(Query("Pride and Prejudice", "", "de"))
        assertEquals("$root/1.epub", found.single().files.single().url)
    }

    @Test
    fun searchDropsUnfilledParameters() {
        assertEquals(
            "https://a.example/s?q=emma&n=&lang=en",
            searchUrl(
                "https://a.example/s?q={searchTerms}&n={count}&lang={language}",
                "emma",
                "en",
            ),
        )
    }

    @Test
    fun bundledCatalogsKeepTheirIdsAndSearch() {
        val ids = BUNDLED_CATALOGS.map { it.id }
        assertEquals(ids.distinct(), ids)
        BUNDLED_CATALOGS.forEach {
            assertTrue(it.id, it.search.contains("{searchTerms}"))
        }
        assertTrue(BUNDLED_CATALOGS.all { it.isBundled })
        assertFalse(Catalog("Mine", "https://x.example/opds").isBundled)
    }

    @Test
    fun formatsComeFromTypeOrExtension() {
        assertEquals("epub", opdsFormat("application/epub+zip; charset=utf-8", "/x"))
        assertEquals("cbz", opdsFormat("application/octet-stream", "/comic.CBZ?x=1"))
        assertNull(opdsFormat("text/html", "/page"))
    }

    @Test
    fun digestMatchesTheRfcExample() {
        val challenge = Challenge(
            "Digest",
            mapOf(
                "realm" to "testrealm@host.com",
                "qop" to "auth,auth-int",
                "nonce" to "dcd98b7102dd2f0e8b11d0f600bfb0c093",
                "opaque" to "5ccc069c403ebaf9f0171e9517f40e41",
            ),
        )
        val login = Login("Mufasa", "Circle Of Life")
        val header =
            digestAuthorization(login, challenge, "GET", "/dir/index.html", "0a4f113b")
        assertTrue(header, "response=\"6629fae49393a05397450978507c4ef1\"" in header)
    }

    @Test
    fun missingLoginAsksForOne() = runBlocking {
        val needed = runCatching { client().feed() }.exceptionOrNull() as LoginNeeded
        assertEquals("Home", needed.realm)
        assertEquals("127.0.0.1", needed.host)
        assertFalse(needed.failed)
    }

    @Test
    fun wrongPasswordIsReported() = runBlocking {
        val needed = runCatching {
            client("wrong").feed()
        }.exceptionOrNull() as LoginNeeded
        assertTrue(needed.failed)
    }

    @Test
    fun basicLoginOpensTheFeedAndDownloads() = runBlocking {
        val client = client("secret")
        val book = client.feed().entries[1]
        assertEquals(basic, client.authorization("127.0.0.1"))
        val target = File.createTempFile("opds", ".epub")
        try {
            client.download(book.files.first(), target) {}
            assertTrue(target.readText().endsWith("test book"))
            val page = runCatching { client.download(book.files[1], target) {} }
            assertTrue(page.isFailure)
        } finally {
            target.delete()
        }
    }
}
