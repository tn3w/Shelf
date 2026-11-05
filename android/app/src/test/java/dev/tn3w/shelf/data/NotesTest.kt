package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val quote = Highlight(1, 10, 20, "It was a dark night", created = 1)

    @Test
    fun rangeOnClipsHighlightToPage() {
        assertEquals(5 until 15, quote.rangeOn(1, 5, 30))
        assertEquals(0 until 5, quote.rangeOn(1, 15, 30))
        assertEquals(0 until 4, quote.rangeOn(1, 12, 4))
        assertNull(quote.rangeOn(1, 20, 30))
        assertNull(quote.rangeOn(1, 0, 10))
        assertNull(quote.rangeOn(2, 0, 30))
    }

    @Test
    fun atFindsNewestHighlightUnderOffset() {
        val newer = quote.copy(start = 15, end = 25, created = 2)
        val highlights = listOf(quote, newer)
        assertEquals(quote, highlights.at(1, 12))
        assertEquals(newer, highlights.at(1, 18))
        assertNull(highlights.at(1, 25))
        assertNull(highlights.at(0, 12))
    }

    @Test
    fun paragraphsInBreaksLinesAtParagraphStarts() {
        val text = "First one.Second one.Third."
        assertEquals("one.\nSecond", paragraphsIn(text, listOf(0, 10, 21), 6, 16))
        assertEquals("Second one.", paragraphsIn(text, listOf(0, 10, 21), 10, 21))
    }

    @Test
    fun markdownGroupsByChapterInReadingOrder() {
        val later =
            Highlight(2, 0, 5, "Dawn", note = "Hope.", chapter = "Two", created = 3)
        val first = quote.copy(text = "Line one\nLine two", chapter = "One")
        val same = first.copy(start = 30, end = 35, text = "Rain", created = 4)
        val book = BookNotes("Night", "Ann Lee", listOf(later, same, first))
        val expected = """
            |# Night
            |
            |*Ann Lee*
            |
            |## One
            |
            |> Line one
            |> Line two
            |
            |> Rain
            |
            |## Two
            |
            |> Dawn
            |
            |Hope.
            |
        """.trimMargin()
        assertEquals(expected, notesMarkdown(listOf(book)))
    }

    @Test
    fun readwiseCsvQuotesFieldsAndNumbersLocations() {
        val noted = quote.copy(text = "Say \"hi\"", note = "=sum", created = 0)
        val csv = readwiseCsv(listOf(BookNotes("Night", "Ann", listOf(noted, quote))))
        val lines = csv.lines()
        val header = listOf("Highlight", "Title", "Author", "Note", "Location", "Date")
        assertEquals(header.joinToString(",") { "\"$it\"" }, lines[0])
        val first = listOf("Say \"\"hi\"\"", "Night", "Ann", "'=sum", "1", "")
        assertEquals(first.joinToString(",") { "\"$it\"" }, lines[1])
        assertTrue(lines[2].endsWith("\"2\",\"1970-01-01 00:00:00\""))
    }

    @Test
    fun bookNotesSkipsBooksMissingFromLibrary() {
        val saved = listOf(Saved(1, Shelf.Read, 0, "b"), Saved(2, Shelf.Read, 0, "A"))
        val highlights = mapOf(1 to listOf(quote), 2 to listOf(quote), 3 to listOf(quote))
        assertEquals(listOf("A", "b"), bookNotes(saved, highlights).map { it.title })
    }

    @Test
    fun quoteTextCitesSourceAndNote() {
        val noted = quote.copy(note = "Mood.")
        assertEquals(
            "“It was a dark night”\n— Night, Ann\n\nMood.",
            quoteText(noted, "Night", "Ann"),
        )
        assertEquals("“It was a dark night”", quoteText(quote, "", ""))
    }

    @Test
    fun changedDropsWorkWhenLastHighlightGoes() {
        val stored = mapOf(4 to listOf(quote))
        assertEquals(emptyMap<Int, List<Highlight>>(), stored.changed(4) { emptyList() })
        val added = stored.changed(5) { it + quote }
        assertEquals(mapOf(4 to listOf(quote), 5 to listOf(quote)), added)
    }

    @Test
    fun mergeAndRedirectCarryHighlights() {
        val other = quote.copy(start = 40, end = 50, created = 9)
        val current = ReadingState(highlights = mapOf(2 to listOf(quote)))
        val imported = mapOf(2 to listOf(quote.copy(note = "older"), other))
        val merged = current.merge(Backup(highlights = imported))
        assertEquals(mapOf(2 to listOf(quote, other)), merged.highlights)
        val moved = merged.redirect(mapOf(2 to 7)::get)
        assertEquals(mapOf(7 to listOf(quote, other)), moved.highlights)
    }

    @Test
    fun oldReadingStateWithoutHighlightsStillDecodes() {
        val state = json.decodeFromString<ReadingState>("""{"progress":{}}""")
        assertEquals(emptyMap<Int, List<Highlight>>(), state.highlights)
    }
}
