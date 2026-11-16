package dev.tn3w.shelf

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
}
