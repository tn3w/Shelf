package dev.tn3w.shelf.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration

private fun archive(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

class LibraryTest {
    private val serializer = JsonSerializer(LibraryState(), LibraryState.serializer())

    private fun decode(text: String) = runBlocking {
        serializer.readFrom(text.byteInputStream())
    }

    @Test
    fun savedIsbnStaysOptionalBothWays() {
        val old = """{"work":1,"shelf":"Read","updated":2}"""
        assertEquals("", json.decodeFromString<Saved>(old).isbn)
        assertEquals(old, json.encodeToString(Saved(1, Shelf.Read, 2)))
        val future = old.dropLast(1) + ""","isbn":"9780441013593","x":0}"""
        assertEquals("9780441013593", json.decodeFromString<Saved>(future).isbn)
    }

    @Test
    fun undecodableLibraryIsReportedAsCorruptSoItIsNeverOverwritten() {
        val stored = """{"entries":[{"work":1,"shelf":"Unknown","updated":0}]}"""
        assertThrows(CorruptionException::class.java) { decode(stored) }
        assertThrows(CorruptionException::class.java) { decode("{") }
    }

    @Test
    fun libraryStateRoundTripsThroughSerializer() {
        val state = LibraryState(
            entries = listOf(Saved(2, Shelf.Read, 0)),
            recent = listOf("dune"),
            dismissed = setOf(4),
            settings = Settings(margin = 16),
        )
        val output = ByteArrayOutputStream()
        runBlocking { serializer.writeTo(state, output) }
        assertEquals(state, decode(output.toString()))
        assertEquals(LibraryState(), decode("{}"))
    }

    @Test
    fun legacyPreferencesMigrateIntoTypedStates() {
        val entries = listOf(Saved(1, Shelf.Want, 3))
        val progress = mapOf(1 to Progress("1.epub", page = 2))
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("entries") to json.encodeToString(entries),
            stringPreferencesKey("recent") to """["emma"]""",
            stringPreferencesKey("settings") to """{"theme":"Sepia","serif":false}""",
            stringPreferencesKey("dismissed") to "broken",
            stringPreferencesKey("progress") to json.encodeToString(progress),
            stringPreferencesKey("activity") to """{"2026-01-01":5}""",
        )
        val library = preferences.toLibraryState()
        assertEquals(entries, library.entries)
        assertEquals(listOf("emma"), library.recent)
        assertEquals(emptySet<Int>(), library.dismissed)
        assertEquals(Settings(serif = false), library.settings)
        val reading = preferences.toReadingState()
        assertEquals(ReadingState(progress, mapOf("2026-01-01" to 5)), reading)
        assertEquals(LibraryState(), mutablePreferencesOf().toLibraryState())
    }

    @Test
    fun suspendCatchingRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            runSuspendCatching { throw CancellationException("stopped") }
        }
        assertEquals("x", runSuspendCatching { error("x") }.exceptionOrNull()?.message)
    }

    @Test
    fun mergeDropsProgressOutsideBooks() {
        val imported = mapOf(
            1 to Progress("../datastore/library"),
            2 to Progress("-2.epub"),
        )
        val merged = ReadingState().merge(Backup(progress = imported))
        assertEquals(mapOf(2 to Progress("-2.epub")), merged.progress)
    }

    @Test
    fun mergeKeepsNetworkAndPrivacySettings() {
        val current = Settings(
            offline = true,
            onlineCovers = false,
            checkUpdates = false,
            catalogueSource = "owner/repo",
            coverSource = "https://a",
        )
        val imported = Settings(
            catalogueSource = "https://evil.example/manifest.json",
            coverSource = "https://evil.example",
            providers = setOf("gutenberg"),
            instantSource = "gutenberg",
            backupFolder = "content://other",
        )
        val merged = LibraryState(settings = current)
            .merge(Backup(settings = imported.copy(margin = 44)))
        assertEquals(current.copy(margin = 44), merged.settings)
    }

    @Test
    fun staleBackupsKeepNewestDatedZips() {
        val today = LocalDate.of(2030, 1, 10)
        val dated = (0L..9).map { automaticBackupName(today.minusDays(it)) }
        val others = listOf("shelf-library.zip", "notes.txt")
        val stale = staleBackups((dated + others).shuffled(), kept = 3)
        assertEquals(dated.drop(3), stale)
    }

    @Test
    fun failedBookExtractionLeavesNoFileAndRetries() {
        val directory = Files.createTempDirectory("books").toFile()
        val broken = object : InputStream() {
            private var sent = 0

            override fun read() = if (sent++ < 10) 1 else throw IOException("cut")
        }
        runCatching { extractBook(directory, "books/-2.epub", broken) }
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        extractBook(directory, "books/-2.epub", "book".byteInputStream())
        assertEquals("book", directory.resolve("-2.epub").readText())
        directory.deleteRecursively()
    }

    @Test
    fun mergeClampsImportedReaderSettings() {
        val imported = Settings(fontScale = 9f, lineSpacing = -1f, margin = -5)
        val merged = LibraryState().merge(Backup(settings = imported)).settings
        assertEquals(FONT_SCALES.endInclusive, merged.fontScale)
        assertEquals(SPACING_RANGE.start, merged.lineSpacing)
        assertEquals(MARGIN_RANGE.first, merged.margin)
        val wide = Backup(settings = Settings(lineSpacing = 50f, margin = 9999))
        val large = LibraryState().merge(wide).settings
        assertEquals(SPACING_RANGE.endInclusive, large.lineSpacing)
        assertEquals(MARGIN_RANGE.last, large.margin)
    }

    @Test
    fun redirectMovesBookFilesAndCovers() {
        val directory = Files.createTempDirectory("books").toFile()
        listOf("5-cover-100", "5.epub", "6-cover-200").forEach {
            directory.resolve(it).writeText("x")
        }
        moveBookFiles(directory) { if (it == 5) -9 else null }
        assertEquals(
            listOf("-9-cover-100", "-9.epub", "6-cover-200"),
            directory.list()!!.sorted(),
        )
        directory.deleteRecursively()
    }

    @Test
    fun redirectPointsProgressAtMovedFile() {
        val stored = mapOf(5 to Progress("5.epub", page = 3), 6 to Progress("6.pdf"))
        val moved = ReadingState(stored).redirect { if (it == 5) 42 else null }
        val expected = mapOf(42 to Progress("42.epub", page = 3), 6 to Progress("6.pdf"))
        assertEquals(expected, moved.progress)
    }

    @Test
    fun restoreWithoutLibraryJsonExtractsNothing() {
        val directory = Files.createTempDirectory("books").toFile()
        val missing = archive("books/1.epub" to "book")
        assertEquals(null, readArchive(missing.inputStream(), directory))
        val broken = archive("library.json" to "{", "books/1.epub" to "book")
        runCatching { readArchive(broken.inputStream(), directory) }
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        val valid = archive("library.json" to "{}", "books/1.epub" to "book")
        assertEquals(Backup(), readArchive(valid.inputStream(), directory))
        assertEquals(listOf("1.epub"), directory.list()!!.toList())
        directory.deleteRecursively()
    }

    @Test
    fun oversizedBookExtractionStops() {
        val directory = Files.createTempDirectory("books").toFile()
        val endless = object : InputStream() {
            override fun read() = 1

            override fun read(buffer: ByteArray, offset: Int, length: Int) = length
        }
        runCatching { extractBook(directory, "books/3.epub", endless, limit = 1024) }
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        directory.deleteRecursively()
    }

    @Test
    fun unknownEnumFallsBackToDefault() {
        val decoded = json.decodeFromString<Settings>("""{"theme":"Sepia","margin":12}""")
        assertEquals(Settings(margin = 12), decoded)
    }

    @Test
    fun settingsSurviveUnknownValues() {
        val decoded = decode("""{"settings":{"theme":"Sepia","serif":false}}""")
        assertEquals(Settings(serif = false), decoded.settings)
    }

    @Test
    fun habitTreatsNonPositiveGoalAsOne() {
        val today = LocalDate.of(2026, 1, 10)
        val activity = mapOf(today.toString() to 3, today.minusDays(1).toString() to 1)
        listOf(0, -5).forEach { goal ->
            val habit = habitOf(activity, goal, today)
            assertEquals(1, habit.goal)
            assertEquals(2, habit.streak)
        }
    }

    @Test
    fun datesEmitNewDayAfterMidnight() = runBlocking {
        val first = LocalDate.of(2026, 1, 10)
        val clock = listOf(first, first, first.plusDays(1)).iterator()
        val emitted = dates({ clock.next() }, Duration.ZERO).take(2).toList()
        assertEquals(listOf(first, first.plusDays(1)), emitted)
    }

    @Test
    fun habitCarriesItsDay() {
        val today = LocalDate.of(2026, 1, 10)
        val habit = habitOf(mapOf(today.minusDays(1).toString() to 5), 5, today)
        assertEquals(today, habit.day)
        assertEquals(listOf(0, 0, 0, 0, 0, 5, 0), habit.week)
    }

    @Test
    fun systemLanguageStoredAsAutomatic() {
        assertEquals("", storedLanguage("de", "de"))
        assertEquals("fr", storedLanguage("fr", "de"))
    }

    @Test
    fun mergeKeepsNewestEntryPerWork() {
        val current =
            listOf(Saved(1, Shelf.Read, 5, rating = 4), Saved(-7, Shelf.Want, 1))
        val imported = listOf(
            Saved(1, Shelf.Want, 3),
            Saved(2, Shelf.Reading, 1),
            Saved(-7, Shelf.Read, 9, "Own", "Me"),
        )
        val merged = LibraryState(current).merge(Backup(entries = imported))
        assertEquals(
            setOf(current[0], imported[1], imported[2]),
            merged.entries.toSet(),
        )
    }

    @Test
    fun mergeCombinesActivityProgressAndDismissed() {
        val current = Backup(
            progress = mapOf(1 to Progress("1.epub", page = 40)),
            activity = mapOf("2026-01-01" to 5, "2026-01-02" to 9),
            dismissed = setOf(3),
        )
        var library = LibraryState(settings = Settings(onboarded = true)).merge(current)
        var reading = ReadingState().merge(current)
        val imported = Backup(
            progress = mapOf(1 to Progress("1.epub", page = 2), 2 to Progress("2.pdf")),
            activity = mapOf("2026-01-01" to 7, "2026-01-03" to 1),
            dismissed = setOf(4),
            settings = Settings(dailyGoal = 30),
        )
        library = library.merge(imported)
        reading = reading.merge(imported)
        val expected = Backup(
            progress = mapOf(1 to Progress("1.epub", page = 40), 2 to Progress("2.pdf")),
            activity = mapOf("2026-01-01" to 7, "2026-01-02" to 9, "2026-01-03" to 1),
            dismissed = setOf(3, 4),
            settings = Settings(onboarded = true, dailyGoal = 30),
        )
        assertEquals(expected, backupOf(library, reading))
    }

    @Test
    fun streakSurvivesUntilTodayIsOver() {
        val today = LocalDate.of(2026, 3, 29)
        fun day(back: Long) = today.minusDays(back).toString()
        val activity = mapOf(day(1) to 10, day(2) to 12, day(3) to 2, day(4) to 10)
        assertEquals(
            2 to false,
            habitOf(activity, 10, today).let {
                it.streak to it.done
            },
        )
        val met = activity + (today.toString() to 10)
        assertEquals(3 to true, habitOf(met, 10, today).let { it.streak to it.done })
        assertEquals(0, habitOf(activity, 10, today.plusDays(1)).streak)
    }

    @Test
    fun reimportUnderNewExtensionPointsProgressAtKeptFile() {
        val progress = mapOf(7 to Progress("7.cbr", section = 2, page = 5))
        val updated = importedProgress(progress, work = 7, name = "7.cbz")
        assertEquals(Progress("7.cbz", section = 2, page = 5), updated[7])
        assertEquals(Progress("8.epub"), importedProgress(progress, 8, "8.epub")[8])
    }

    @Test
    fun deleteOtherFormatsKeepsOnlyNewFileAndOtherWorks() {
        val directory = Files.createTempDirectory("books").toFile()
        val names = listOf("7.epub", "7.pdf", "70.epub", "7-cover-5")
        names.forEach { directory.resolve(it).writeText("x") }

        deleteOtherFormats(directory, work = 7, keep = "7.pdf")

        val remaining = directory.list().orEmpty().sorted()
        assertEquals(listOf("7-cover-5", "7.pdf", "70.epub"), remaining)
    }

    @Test
    fun redirectMovesAliasedWorks() {
        val stored = listOf(Saved(SYNTHETIC + 1, Shelf.Read, 5), Saved(7, Shelf.Want, 1))
        val moved = LibraryState(stored, dismissed = setOf(SYNTHETIC + 1))
            .redirect { if (it == SYNTHETIC + 1) 42 else null }
        assertEquals(listOf(42, 7), moved.entries.map { it.work })
        assertEquals(setOf(42), moved.dismissed)
    }

    @Test
    fun savedKeepsLibraryCover() {
        val cover = LibraryCover(DNB, "9783833911316")
        val book = Book(SYNTHETIC + 1, "T", libraryCover = cover)
        assertEquals(cover, book.toSaved(Shelf.Want).toBook().libraryCover)
        val stored = """{"work":1,"shelf":"Read","updated":0}"""
        assertEquals(null, json.decodeFromString<Saved>(stored).libraryCover)
    }
}
