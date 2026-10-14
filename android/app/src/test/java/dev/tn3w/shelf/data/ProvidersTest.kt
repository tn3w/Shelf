package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val PRIDE = Query("Pride and Prejudice", "Jane Austen", "en")

private fun offer(title: String, author: String = "", link: String = title) =
    Offer(Gutenberg, title, author, link)

private fun feed(vararg entries: String) =
    """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"
        xmlns:media="http://search.yahoo.com/mrss/">
        <id>https://example.org/search</id>${entries.joinToString("")}</feed>"""

class ProvidersTest {
    @Test
    fun providersAreOffByDefault() {
        assertTrue(Settings().enabledProviders().isEmpty())
        val chosen = Settings(providers = setOf(Gutenberg.id, "gone"))
        assertEquals(listOf(Gutenberg), chosen.enabledProviders())
    }

    @Test
    fun providerIdsAreUnique() {
        assertEquals(PROVIDERS.size, PROVIDERS.map { it.id }.toSet().size)
    }

    @Test
    fun providersServeTheirLanguages() {
        assertTrue(StandardEbooks.serves("en"))
        assertFalse(StandardEbooks.serves("de"))
        assertTrue(Gutenberg.serves("de"))
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
    fun standardEbooksOffersCompatibleEpubWithCoverAndSize() {
        val body = feed(
            """<entry><title>Pride and Prejudice</title>
            <author><name>Jane Austen</name></author>
            <media:thumbnail url="https://standardebooks.org/cover.jpg"/>
            <link href="https://standardebooks.org/a" type="application/xhtml+xml"/>
            <link href="https://standardebooks.org/a.epub" length="831959"
                type="application/epub+zip"/>
            <link href="https://standardebooks.org/b.epub" type="application/epub+zip"/>
            </entry>""",
            "<entry><title>No downloads</title></entry>",
        )
        val offer = StandardEbooks.offers(body, PRIDE).single()
        assertEquals("Jane Austen", offer.author)
        assertEquals("https://standardebooks.org/a.epub", offer.link)
        assertEquals("https://standardebooks.org/cover.jpg", offer.cover)
        assertEquals(831959L, offer.bytes)
        assertNull(StandardEbooks.detailsUrl(offer))
    }

    @Test
    fun gutenbergFindsBooksAndLooksUpTheirEpub() {
        val page = "https://www.gutenberg.org/ebooks/1342.opds"
        val body = feed(
            """<entry><id>$page</id>
            <title>Pride and Prejudice</title><content>Jane Austen</content></entry>""",
            """<entry><id>https://www.gutenberg.org/ebooks/authors.opds</id>
            <title>Authors</title></entry>""",
        )
        assertTrue(Gutenberg.searchUrl(PRIDE).endsWith("%20l.en"))
        val offer = Gutenberg.offers(body, PRIDE).single()
        assertEquals("Jane Austen", offer.author)
        assertEquals(page, Gutenberg.detailsUrl(offer))
        assertTrue(offer.cover.orEmpty().endsWith("/1342/pg1342.cover.small.jpg"))

        val details = feed(
            """<entry>
            <link type="application/epub+zip" length="10" href="https://g.org/1342.epub"/>
            <link type="application/epub+zip" length="20"
                href="https://g.org/1342.epub3.images"/>
            </entry>""",
        )
        val epub = Gutenberg.details(offer, details)
        assertEquals("https://g.org/1342.epub3.images", epub?.link)
        assertEquals(20L, epub?.bytes)
    }

    @Test
    fun gutenbergDropsAudioBooks() {
        val offer = offer("Pride and Prejudice", link = "https://g.org/1.opds")
        val audio =
            feed(
                """<entry><link type="audio/mpeg" href="https://g.org/1.mp3"/>
            </entry>""",
            )
        assertNull(Gutenberg.details(offer, audio))
    }

    @Test
    fun wikisourceKeepsWorksInSearchOrder() {
        val body = """{"query":{"pages":[
            {"title":"Die Verwandlung (Franz Kafka)","index":3},
            {"title":"Die Verwandlung","index":1,"pageprops":{"disambiguation":""}},
            {"title":"Die Verwandlung (1915)","index":2},
            {"title":"Die Verwandlung/Kapitel 1","index":4},
            {"title":"Pride and Prejudice","index":5,
                "categories":[{"ns":14,"title":"Category:Versions pages"}]}]}}"""
        val query = Query("Die Verwandlung", "Franz Kafka", "de")
        val offers = Wikisource.offers(body, query)
        assertEquals(
            listOf("Die Verwandlung (1915)", "Die Verwandlung (Franz Kafka)"),
            offers.map { it.title },
        )
        assertEquals(
            "https://ws-export.wmcloud.org/?format=epub-3&lang=de" +
                "&page=Die%20Verwandlung%20%281915%29",
            offers.first().link,
        )
        assertTrue(Wikisource.searchUrl(query).startsWith("https://de.wikisource.org/"))
    }

    @Test
    fun wikisourceWithoutResultsHasNoOffers() {
        assertTrue(Wikisource.offers("""{"batchcomplete":true}""", PRIDE).isEmpty())
    }

    @Test
    fun providerUrlsAreValidUris() {
        PROVIDERS.forEach { java.net.URI(it.searchUrl(PRIDE)) }
    }

    @Test
    fun internetArchiveReadsSingleAndListedCreators() {
        val body = """{"response":{"docs":[
            {"identifier":"one","title":"Pride and prejudice","creator":"Austen, Jane"},
            {"identifier":"two","title":"Pride","creator":["Jane Austen","C. E. Brock"]},
            {"identifier":"three"}]}}"""
        val offers = InternetArchive.offers(body, PRIDE)
        assertEquals(listOf("one", "two", "three"), offers.map { it.link })
        assertEquals("Jane Austen & C. E. Brock", offers[1].author)
        assertEquals("", offers[2].title)
        assertEquals("https://archive.org/services/img/one", offers[0].cover)
        val files = InternetArchive.detailsUrl(offers[0])
        assertEquals("https://archive.org/metadata/one/files", files)
    }

    @Test
    fun internetArchiveDropsCatalogueNotes() {
        val body = """{"response":{"docs":[{"identifier":"faust","title":"Faust;",
            "creator":["Goethe, Johann Wolfgang von, 1749-1832",
                "Austen, Jane, 1775-1817, author", "Lomba, José, 1868-"]}]}}"""
        val offer = InternetArchive.offers(body, PRIDE).single()
        assertEquals("Faust", offer.title)
        val authors = "Goethe, Johann Wolfgang von & Austen, Jane & Lomba, José"
        assertEquals(authors, offer.author)
    }

    @Test
    fun gutenbergDropsLanguageNote() {
        val body = feed(
            """<entry><id>https://www.gutenberg.org/ebooks/22367.opds</id>
            <title>Die Verwandlung (German)</title>
            <content>Franz Kafka</content></entry>""",
        )
        val query = Query("Die Verwandlung", "Franz Kafka", "de")
        assertEquals("Die Verwandlung", Gutenberg.offers(body, query).single().title)
    }

    @Test
    fun internetArchiveDownloadsPublicEpubFile() {
        val offer = offer("Pride and prejudice", link = "pride")
        val files = """{"result":[
            {"name":"hidden.epub","size":"5","private":"true"},
            {"name":"Pride and Prejudice.epub","size":"539764"},
            {"name":"pride.pdf","size":"9"}]}"""
        val epub = InternetArchive.details(offer, files)
        val link = "https://archive.org/download/pride/Pride%20and%20Prejudice.epub"
        assertEquals(link, epub?.link)
        assertEquals(539764L, epub?.bytes)
        assertNull(InternetArchive.details(offer, """{"result":[{"name":"a.pdf"}]}"""))
    }

    @Test
    fun internetArchiveSearchesFreeEpubsOnly() {
        val url = java.net.URLDecoder.decode(InternetArchive.searchUrl(PRIDE), "UTF-8")
        assertTrue("title:(pride and prejudice) AND creator:(austen)" in url)
        assertTrue("NOT access-restricted-item:true" in url)
        assertTrue("format:EPUB" in url)
        assertTrue("language:(eng OR English OR en)" in url)
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
}
