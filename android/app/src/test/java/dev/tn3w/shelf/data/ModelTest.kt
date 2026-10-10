package dev.tn3w.shelf.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ModelTest {
    @Test
    fun releaseLabelsSortByNumbers() {
        val labels =
            listOf("2026-10-01", "2025-12", "2026-10", "2026-09-30-2", "2026-09-30")
        assertEquals(
            listOf("2025-12", "2026-09-30", "2026-09-30-2", "2026-10", "2026-10-01"),
            labels.sortedWith(releaseOrder),
        )
    }

    @Test
    fun prefersNewestManifest() {
        val older = Manifest(CATALOGUE_FORMAT, "2026-01-02", emptyList())
        val newer = Manifest(CATALOGUE_FORMAT, "2026-01-03", emptyList())
        assertEquals(newer, newest(older, newer))
        assertEquals(newer, newest(newer, older))
        assertEquals(older, newest(older, null))
        assertEquals(null, newest(null, null))
    }

    @Test
    fun validatesSources() {
        assertTrue(isValidSource(""))
        assertTrue(isValidSource("tn3w/Shelf"))
        assertTrue(isValidSource("https://example.org/manifest.json"))
        assertFalse(isValidSource("http://example.org/manifest.json"))
        assertFalse(isValidSource("not a source"))
    }

    @Test
    fun resolvesReleaseUrls() {
        val official = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
        assertEquals(official, releasesUrl(""))
        assertEquals(official, RELEASES)
        assertEquals(
            "https://api.github.com/repos/me/fork/releases?per_page=30",
            releasesUrl("me/fork"),
        )
        val manifest = "https://example.org/manifest.json"
        assertEquals(manifest, releasesUrl(manifest))
    }

    @Test
    fun decodesGithubReleases() {
        val text = """
            [{"tag_name": "v9.8.7", "body": "Notes", "prerelease": true, "id": 7,
              "assets": [{"name": "shelf.apk", "size": 3,
                          "browser_download_url": "https://host/shelf.apk"}]},
             {"tag_name": "catalogue-2026-10"}]
        """
        val releases = json.decodeFromString<List<Release>>(text)
        val app = releases[0]
        assertEquals("v9.8.7", app.tag)
        assertEquals("9.8.7", app.version)
        assertTrue(app.prerelease)
        assertFalse(app.draft)
        assertEquals("https://host/shelf.apk", app.assetUrl("shelf.apk"))
        assertEquals(Release("catalogue-2026-10"), releases[1])
    }

    @Test
    fun picksChangelogInLanguageWithEnglishFallback() {
        val release = Release(
            "v9.8.7",
            assets = listOf("en", "de").map {
                Asset("changelog-$it.txt", "https://host/$it")
            },
        )
        assertEquals("https://host/de", release.changelogUrl("de"))
        assertEquals("https://host/en", release.changelogUrl("fr"))
        assertEquals(null, Release("v9.8.7").changelogUrl("de"))
    }

    @Test
    fun decodesSettingsWithEnumsAndUnknownKeys() {
        val settings = json.decodeFromString<Settings>(
            """{"theme": "Dark", "pageColor": "Night", "dailyGoal": 15, "removed": 1}""",
        )
        assertEquals(ThemeMode.Dark, settings.theme)
        assertEquals(PageColor.Night, settings.pageColor)
        assertEquals(15, settings.dailyGoal)
        assertEquals(COVERS, settings.coverHost)
        val encoded = json.encodeToString(settings)
        assertEquals(settings, json.decodeFromString<Settings>(encoded))
    }

    @Test
    fun backupRoundTrips() {
        val backup = Backup(
            entries = listOf(Saved(7, Shelf.Reading, 1, "Title", "Author", rating = 3)),
            progress = mapOf(7 to Progress("7.epub", page = 4, pages = 10)),
            activity = mapOf("2026-10-08" to 12),
            dismissed = setOf(9),
            settings = Settings(theme = ThemeMode.Light, pageColor = PageColor.Paper),
        )
        assertEquals(backup, json.decodeFromString<Backup>(json.encodeToString(backup)))
        assertEquals(0.5f, backup.progress.getValue(7).fraction)
    }

    @Test
    fun ownBooksAreLocalAndStable() {
        val book = ownBook("My Story", "Me")
        assertTrue(book.isLocal)
        assertEquals(book.work, ownBook("My Story", "Me").work)
        assertEquals("Me", book.author)
        assertEquals(book.work, book.toSaved(Shelf.Want).toBook().work)
    }

    @Test
    fun importedFilesGetContentIds() {
        val first = contentWork("first book".byteInputStream())
        assertEquals(first, contentWork("first book".byteInputStream()))
        assertNotEquals(first, contentWork("second book".byteInputStream()))
        assertTrue(first < 0)
        assertNotEquals(first, ownBook("first book", "").work)
    }

    @Test
    fun readsBinaryValues() {
        val bytes =
            byteArrayOf(
                0xAC.toByte(), 0x02, 3, 'a'.code.toByte(), 'b'.code.toByte(),
                'c'.code.toByte(), 0xFF.toByte(), 9, 8,
            )
        val reader = ByteReader(bytes)
        assertEquals(300, reader.varint())
        assertEquals("abc", reader.text())
        assertEquals(255, reader.byte())
        assertTrue(reader.hasMore)
        assertArrayEquals(byteArrayOf(9, 8), reader.rest())
        assertFalse(reader.hasMore)
    }

    @Test
    fun decodesDeltaPostings() {
        val deltas = byteArrayOf(1, 2, 3, 0xAC.toByte(), 0x02)
        assertArrayEquals(intArrayOf(1, 3, 6, 306), decodePostings(deltas))
        assertArrayEquals(IntArray(0), decodePostings(ByteArray(0)))
    }

    @Test
    fun intArrayListGrows() {
        val list = IntArrayList()
        repeat(100) { list.add(it) }
        assertArrayEquals(IntArray(100) { it }, list.toArray())
    }

    @Test
    fun downloadClaimedOnceUnderConcurrency() {
        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
        val claims = AtomicInteger()
        val threads =
            List(16) {
                thread { if (downloads.claim("en-core")) claims.incrementAndGet() }
            }
        threads.forEach { it.join() }
        assertEquals(1, claims.get())
    }

    @Test
    fun failedDownloadCanBeClaimedAgain() {
        val downloads =
            MutableStateFlow<Map<String, Download>>(mapOf("en-core" to Download.Failed))
        assertTrue(downloads.claim("en-core"))
        assertFalse(downloads.claim("en-core"))
    }

    @Test
    fun newBaseReplacesOlderChain() {
        fun pack(pack: String, month: String, base: String? = null) =
            segment(emptyList(), pack, month, base)
        val segments = listOf(
            pack("core", "2000-02", base = "2000-01"),
            pack("fantasy", "2000-03"),
            pack("core", "2000-01"),
            pack("core", "2000-04", base = "2000-03"),
            pack("core", "2000-03"),
            pack("scifi", "2000-04", base = "2000-03"),
        )
        assertEquals(
            listOf("core 2000-03", "fantasy 2000-03", "core 2000-04"),
            currentSegments(segments).map { "${it.pack} ${it.month}" },
        )
    }

    @Test
    fun refusesPlainHttpConnections() {
        assertThrows(IllegalArgumentException::class.java) {
            connect("http://example.org/pack.bin")
        }
    }
}
