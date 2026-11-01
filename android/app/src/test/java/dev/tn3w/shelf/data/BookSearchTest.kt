package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchTest {
    private val document = TextDocument(
        listOf(
            listOf(Block("Call me Ishmael."), Block("Some years ago.")),
            listOf(
                Block("ab"),
                Block("", image = "a.png"),
                Block("cd"),
                Block("Ishmael"),
            ),
        ),
        listOf(Chapter("One", 0), Chapter("Two", 1)),
    )

    @Test
    fun blockStartsMatchPageOffsets() {
        val blocks = listOf(
            Block("ab"),
            Block("cde"),
            Block("", image = "a.png"),
            Block("fg"),
            Block("", image = "b.png"),
        )
        assertEquals(listOf(0, 2, 7, 8, 11), blockStarts(blocks))
    }

    @Test
    fun findsEveryMatchIgnoringCase() {
        val matches = document.find("ishmael")
        assertEquals(listOf(0 to 8, 1 to 6), matches.map { it.section to it.offset })
        assertEquals("Ishmael", matches.first().text)
        assertEquals("Call me ", matches.first().before)
        assertEquals(".", matches.first().after)
    }

    @Test
    fun ignoresShortQueries() {
        assertEquals(emptyList<Match>(), document.find(" a "))
    }

    @Test
    fun stopsAtLimit() {
        assertEquals(2, document.find("me").size)
        assertEquals(1, document.find("me", limit = 1).size)
    }

    @Test
    fun clipsLongSnippetsAtWords() {
        val text = "word ".repeat(40) + "needle" + " tail".repeat(40)
        val at = text.indexOf("needle")
        val before = snippetBefore(text, at)
        val after = snippetAfter(text, at + "needle".length)
        assertTrue(before.startsWith("...word"))
        assertTrue(after.endsWith("tail..."))
        assertTrue(before.length < 40 && after.length < 95)
    }

    @Test
    fun rangesCoverEveryOccurrence() {
        assertEquals(listOf(0..1, 6..7), matchRanges("Ab cd ab", "ab"))
        assertEquals(emptyList<IntRange>(), matchRanges("Ab", "a"))
    }

    @Test
    fun excerptCollapsesSpaceAndClips() {
        assertEquals("a b", excerptOf("  a \n b "))
        assertEquals("one two...", excerptOf("one two three", length = 9))
    }
}
