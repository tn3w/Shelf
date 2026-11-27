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
    fun editionCoversInTheBookLanguageComeFirst() {
        val text = """{"entries": [
            {"covers": [1], "languages": [{"key": "/languages/eng"}]},
            {"covers": [2], "languages": [{"key": "/languages/ger"}]},
            {"covers": [3]}]}"""
        assertEquals(listOf(2, 1, 3), parseEditionCovers(text, "de"))
        assertEquals(listOf(1, 2, 3), parseEditionCovers(text, "en"))
        assertEquals(listOf(1, 2, 3), parseEditionCovers(text))
    }

    @Test
    fun chosenCoverFollowsAMergedWork() {
        val state = LibraryState(
            covers = mapOf(
                5 to ChosenCover("5-cover-77", 1),
                6 to ChosenCover("https://images.example/c.jpg", 1),
            ),
        )
        val moved = state.redirect { if (it == 5) 9 else null }
        assertEquals(ChosenCover("9-cover-77", 1), moved.covers[9])
        assertEquals("https://images.example/c.jpg", moved.covers[6]?.image)
    }

    @Test
    fun newestCoverChoiceWinsInSyncAndRestore() {
        val older = ChosenCover("https://images.example/old.jpg", 1)
        val newer = ChosenCover("", 2)
        val state = LibraryState(covers = mapOf(4 to older, 8 to older))
        val peer = Backup(covers = mapOf(4 to newer))
        assertEquals(mapOf(4 to newer, 8 to older), state.synced(peer).covers)
        assertEquals(mapOf(4 to newer, 8 to older), state.merge(peer).covers)
        assertEquals(state.covers, backupOf(state, ReadingState()).covers)
    }

    @Test
    fun onlyUploadedCoversTravelAsFiles() {
        val covers = mapOf(
            1 to ChosenCover("1-cover-5", 1),
            2 to ChosenCover("https://images.example/c.jpg", 1),
            3 to ChosenCover("", 1),
        )
        assertEquals(setOf("1-cover-5"), uploadedCovers(covers))
    }
}
