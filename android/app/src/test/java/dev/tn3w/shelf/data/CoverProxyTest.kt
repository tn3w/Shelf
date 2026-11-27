package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverProxyTest {
    @Test
    fun coverAddressesRoundUpToAServedWidth() {
        val url = { pixels: Int -> proxyCoverUrl(COVER_PROXY, "de", 42, pixels) }
        assertEquals("$COVER_PROXY/b/42/de/160.avif", url(100))
        assertEquals("$COVER_PROXY/b/42/de/320.avif", url(161))
        assertEquals("$COVER_PROXY/b/42/de/640.avif", url(2000))
    }

    @Test
    fun photosOnlyForOpenLibraryAuthors() {
        assertEquals(
            "$COVER_PROXY/a/7/192.avif",
            proxyPhotoUrl(COVER_PROXY, Author(7, "Jeff Kinney"), 150),
        )
        assertNull(proxyPhotoUrl(COVER_PROXY, Author(SYNTHETIC + 1, "Jeff Kinney"), 96))
        assertNull(proxyPhotoUrl(COVER_PROXY, Author(0, "Jeff Kinney"), 96))
    }

    @Test
    fun onlyPlainHttpsAddressesAreAccepted() {
        assertTrue(isValidProxy(""))
        assertTrue(isValidProxy("https://images.example.org/shelf"))
        assertFalse(isValidProxy("http://images.example.org"))
        assertFalse(isValidProxy("https://images.example.org/?key=1"))
        assertFalse(isValidProxy("images.example.org"))
    }

    @Test
    fun editionCoversKeepEachRealCoverOnce() {
        val text = """{"size": 3, "entries": [{"covers": [12, -1]}, {"title": "x"},
            {"covers": [12, 34]}]}"""
        assertEquals(listOf(12, 34), parseEditionCovers(text))
    }

    @Test
    fun coverVariantsStartWithTheCatalogueCoverAndSkipRepeats() {
        val book = Book(7, "Title", cover = 12, libraryCover = LibraryCover(DNB, "isbn"))
        val isbns = List(40) { "97800000000$it" }
        val variants = coverVariants(book, isbns, listOf(12, 34))
        assertEquals("$COVERS/b/id/12-L.jpg", variants.first())
        assertEquals(1, variants.count { it == "$COVERS/b/id/12-L.jpg" })
        assertTrue("$COVERS/b/id/34-L.jpg" in variants)
        assertTrue(book.libraryCover!!.url in variants)
        assertTrue(variants.size < isbns.size)
    }

    @Test
    fun chosenCoverFollowsAMergedWork() {
        val state = LibraryState(covers = mapOf(5 to "https://images.example/c.jpg"))
        val moved = state.redirect { if (it == 5) 9 else null }
        assertEquals(mapOf(9 to "https://images.example/c.jpg"), moved.covers)
    }
}
