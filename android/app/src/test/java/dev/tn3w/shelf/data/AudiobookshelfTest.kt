package dev.tn3w.shelf.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

private const val BASE = "https://audio.example/abs"

private val LIBRARIES = """{"libraries": [
    {"id": "l1", "name": "Audiobooks", "mediaType": "book"},
    {"id": "l2", "name": "Shows", "mediaType": "podcast"}
]}"""

private val ITEMS = """{"results": [
    {"id": "i1", "media": {"coverPath": "/c.jpg",
        "metadata": {"title": "A Long Walk", "authorName": "Ada Writer"}}},
    {"id": "i2", "media": {"metadata": {"title": "Quiet Hours", "authors": [{"name": "B"}]}}}
], "total": 120, "limit": 50, "page": 1}"""

private val ITEM = """{"id": "i1", "media": {
    "metadata": {"title": "A Long Walk", "authorName": "Ada Writer",
        "description": "<p>Far <b>away</b></p>"},
    "coverPath": "/c.jpg",
    "ebookFile": {"ino": "9", "metadata": {"filename": "walk.epub", "size": 2048}},
    "audioFiles": [
        {"index": 2, "ino": "b", "duration": 60.5, "metadata": {"filename": "two.mp3"}},
        {"index": 1, "ino": "a", "duration": 100, "metadata": {"filename": "one.mp3"}},
        {"index": 3, "ino": "x", "duration": 5, "exclude": true,
            "metadata": {"filename": "extra.mp3"}}
    ],
    "chapters": [
        {"start": 0, "title": "Opening"},
        {"start": 40, "title": "Middle"},
        {"start": 130.25, "title": "Ending"}
    ]
}}"""

class AudiobookshelfTest {
    private val shelf = Audiobookshelf("$BASE/")
    private lateinit var server: HttpServer
    private val root get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        networkAllowed = true
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (code, body) = when {
                exchange.requestURI.path == "/login" ->
                    200 to """{"user": {"token": "secret", "accessToken": "short"}}"""

                exchange.requestHeaders.getFirst("Authorization") != "Bearer secret" ->
                    401 to ""

                else -> 200 to LIBRARIES
            }
            val bytes = body.toByteArray()
            val length = if (bytes.isEmpty()) -1 else bytes.size.toLong()
            exchange.sendResponseHeaders(code, length)
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun listsOnlyBookLibraries() {
        val feed = shelf.feed(LIBRARIES, shelf.libraries)

        assertEquals(listOf("Audiobooks"), feed.entries.map { it.title })
        val link = feed.entries.single().link.orEmpty()
        assertTrue(link.startsWith("$BASE/api/libraries/l1/items?"))
        assertTrue("page=0" in link)
    }

    @Test
    fun pagesItemsAndOffersLibrarySearch() {
        val url = "$BASE/api/libraries/l1/items?limit=50&page=1&minified=1"
        val feed = shelf.feed(ITEMS, url)

        assertEquals(listOf("Ada Writer", "B"), feed.entries.map { it.author })
        assertEquals("$BASE/api/items/i1/cover", feed.entries[0].cover)
        assertNull(feed.entries[1].cover)
        assertEquals("$BASE/api/items/i1?expanded=1", feed.entries[0].link)
        assertTrue("page=2" in feed.next.orEmpty())
        val search = searchUrl(feed.search.orEmpty(), "walk")
        assertEquals("$BASE/api/libraries/l1/search?q=walk&limit=50", search)
    }

    @Test
    fun lastPageHasNoNext() {
        val url = "$BASE/api/libraries/l1/items?limit=50&page=2&minified=1"
        assertNull(shelf.feed(ITEMS, url).next)
    }

    @Test
    fun itemOffersEbookAndOrderedTracksWithTheirChapters() {
        val entry = shelf.feed(ITEM, "$BASE/api/items/i1?expanded=1").entries.single()

        assertEquals("Far away", entry.summary)
        val ebook = entry.files.single()
        assertEquals(Acquisition("$BASE/api/items/i1/ebook", "epub", bytes = 2048), ebook)
        assertEquals(listOf("one.mp3", "two.mp3"), entry.tracks.map { it.name })
        assertEquals("$BASE/api/items/i1/file/a", entry.tracks[0].uri)
        assertEquals(listOf(100_000L, 60_500L), entry.tracks.map { it.millis })
        assertEquals(
            listOf(Mark("Opening", 0), Mark("Middle", 40_000)),
            entry.tracks[0].chapters,
        )
        assertEquals(listOf(Mark("Ending", 30_250)), entry.tracks[1].chapters)
        val book = audiobookOf(entry.tracks)
        assertEquals("A Long Walk", titleOf(entry.tracks))
        assertEquals(160, book.seconds)
    }

    @Test
    fun searchResultsCarryTheirItems() {
        val found = """{"book": [{"libraryItem": $ITEM}], "podcast": []}"""
        val url = "$BASE/api/libraries/l1/search?q=walk"
        val entry = shelf.feed(found, url).entries.single()

        assertEquals(2, entry.tracks.size)
        assertTrue(entry.isDownloadable)
    }

    @Test
    fun signsInWithTheLastingTokenAndSendsItAsBearer() = runBlocking {
        val catalog = Catalog("Home", "$root/", server = Server.Audiobookshelf)
        val needed = runCatching { CatalogClient(catalog).feed() }.exceptionOrNull()
        check(needed is LoginNeeded)
        assertEquals("$root/login", needed.tokenUrl)

        val login = CatalogClient(catalog).signIn(needed, "reader", "pass")
        assertEquals(Login("reader", "pass", "secret"), login)

        val signedIn = catalog.copy(logins = mapOf("127.0.0.1" to login))
        val client = CatalogClient(signedIn)
        assertEquals(listOf("Audiobooks"), client.feed().entries.map { it.title })
        assertEquals("$root/x?token=secret", client.streamUrl("$root/x"))
    }
}
