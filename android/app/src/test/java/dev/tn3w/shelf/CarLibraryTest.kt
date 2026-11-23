package dev.tn3w.shelf

import dev.tn3w.shelf.data.Progress
import dev.tn3w.shelf.data.Saved
import dev.tn3w.shelf.data.Shelf
import dev.tn3w.shelf.data.textDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLibraryTest {
    private val stored = Progress("book.txt", page = 3, pages = 10)

    @Test
    fun startsReadingAloudFromTheStoredPosition() {
        val document = textDocument("The first sentence.\n\nThe second sentence follows.")
        val source = sourceOf(document, stored)!!

        assertEquals(stored, source.progress)
    }

    @Test
    fun savesWhereTheNarrationReached() {
        val document = textDocument("The first sentence.\n\nThe second sentence follows.")
        val source = sourceOf(document, stored)!!
        val passage = source.passages.last()

        val heard = source.progressAt(passage, 0.5f)

        assertEquals(stored.file, heard.file)
        assertEquals(passage.section, heard.section)
        assertEquals(passage.offset, heard.offset)
        assertEquals(5, heard.page)
    }

    @Test
    fun hasNothingToReadAloudWithoutText() {
        assertNull(sourceOf(textDocument(""), stored))
    }

    private val books = listOf(
        Saved(1, Shelf.Reading, updated = 3, title = "Night Train", author = "Ada Holm"),
        Saved(2, Shelf.Reading, updated = 2, title = "Holm Garden", author = "Bo Lind"),
    )

    @Test
    fun voiceSearchPrefersTitlesOverAuthors() {
        assertEquals(2, bestMatch(books, "holm")?.work)
        assertEquals(1, bestMatch(books, " ada ")?.work)
    }

    @Test
    fun voiceSearchWithoutQueryResumesTheMostRecentBook() {
        assertEquals(1, bestMatch(books, "")?.work)
    }

    @Test
    fun voiceSearchFindsNothingForUnknownBooks() {
        assertNull(bestMatch(books, "cookbook"))
    }
}
