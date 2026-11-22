package dev.tn3w.shelf

import dev.tn3w.shelf.data.Progress
import dev.tn3w.shelf.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {
    private val workerSubject =
        Regex("""^Shelf (\d+\.\d+\.\d+) (bug report|feature request)$""")

    @Test
    fun subjectsMatchTheIssueWorker() {
        FeedbackKind.entries.forEach { kind ->
            assertTrue(workerSubject.matches(subject("3.14.15", kind)))
        }
    }

    @Test
    fun redactRemovesPrivateDetails() {
        val log = listOf(
            "open content://media/external/file/42 failed",
            "read /storage/emulated/0/Books/My Secret Novel.epub",
            "fetch https://example.org/search?q=private+title",
            "account reader.name@example.com",
            "connect 192.168.1.20 refused",
        ).joinToString("\n")

        val redacted = redact(log)

        val secrets =
            listOf("media/external", "Books", "private+title", "reader.name", "192.168")
        secrets.forEach { assertFalse(it, redacted.contains(it)) }
        assertTrue(redacted.contains("https://example.org/<path>"))
    }

    @Test
    fun reportStartsWithMessageThenDetailsMarkerAndLog() {
        val details = listOf("Shelf 3.14.15 (test)")
        val body = report("Crashes on open", details, "E Tag: failed at /data/user/0/x")

        val header = "Crashes on open\n\nShelf 3.14.15 (test)\nSent from Shelf\n"
        assertTrue(body.startsWith("$header--- log ---\n"))
        assertTrue(body.endsWith("E Tag: failed at <path>"))
    }

    @Test
    fun reportWithoutLogHasNoLogSection() {
        val body = report("Dark covers", listOf("Shelf 3.14.15 (test)"), "  ")

        assertEquals("Dark covers\n\nShelf 3.14.15 (test)\nSent from Shelf", body)
    }

    @Test
    fun redactHidesStoredDataInParserErrors() {
        val error = "Unexpected token\nJSON input: {\"title\":\"My Diary\"}"

        assertFalse(redact(error).contains("My Diary"))
    }

    @Test
    fun changedSettingsListOnlyChangedNonIdentifyingSwitches() {
        val settings = Settings(
            eInk = true,
            speechVoice = "rare-voice",
            dailyGoal = 37,
            backupFolder = "content://tree/private",
            coverProxyUrl = "https://proxy.example",
            catalogueSource = "https://mirror.example",
        )

        assertEquals("eInk=true", changedSettings(settings))
        assertEquals("default", changedSettings(Settings()))
    }

    @Test
    fun libraryShapeNamesFormatsWithoutCountsOrBooks() {
        val progress =
            listOf(Progress("12.epub"), Progress("34.EPUB"), Progress("56.pdf"))

        assertEquals("Formats epub pdf, audiobooks true", libraryShape(progress, true))
        assertEquals("Formats none, audiobooks false", libraryShape(emptyList(), false))
    }

    @Test
    fun screenNameDropsPackageAndArguments() {
        val route = "dev.tn3w.shelf.ReaderRoute/{work}?listen={listen}"

        assertEquals("ReaderRoute", screenName(route))
        assertEquals("", screenName(null))
    }

    @Test
    fun playbackLogNamesEachPlayerState() {
        val speech = Narration(count = 3, playing = true)

        assertEquals(
            "read aloud playing, audiobook idle",
            playbackLog(speech, Narration()),
        )
    }
}
