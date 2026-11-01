package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverProxyTest {
    @Test
    fun coverAddressesRoundUpToAServedWidth() {
        val url = { pixels: Int ->
            proxyCoverUrl(COVER_PROXY, "2026-10-31", "de", 42, pixels)
        }
        assertEquals("$COVER_PROXY/b/2026-10-31/de/42/160.avif", url(100))
        assertEquals("$COVER_PROXY/b/2026-10-31/de/42/320.avif", url(161))
        assertEquals("$COVER_PROXY/b/2026-10-31/de/42/640.avif", url(2000))
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
}
