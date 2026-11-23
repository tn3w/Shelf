package dev.tn3w.shelf

import android.media.session.PlaybackState
import dev.tn3w.shelf.data.Progress
import dev.tn3w.shelf.data.Saved
import dev.tn3w.shelf.data.Shelf
import dev.tn3w.shelf.data.textDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun resumingTriesTheLastPlayedBookBeforeNewerOnes() {
        assertEquals(listOf(2, 1), resumeOrder(books, last = 2).map { it.work })
        assertEquals(listOf(1, 2), resumeOrder(books, last = 0).map { it.work })
    }

    @Test
    fun voiceSearchFindsNothingForUnknownBooks() {
        assertNull(bestMatch(books, "cookbook"))
    }

    private val idle = Narration()
    private val loading = Narration(work = 1, loading = true)
    private val paused = Narration(work = 1, count = 60)
    private val playing = paused.copy(playing = true)

    private fun allows(actions: Long, action: Long) = actions and action == action

    @Test
    fun voiceSearchAndResumeWorkBeforeAnythingPlays() {
        val actions = sessionActions(idle, timed = false)

        assertTrue(allows(actions, PlaybackState.ACTION_PLAY_FROM_SEARCH))
        assertTrue(allows(actions, PlaybackState.ACTION_PLAY))
        assertFalse(allows(actions, PlaybackState.ACTION_PAUSE))
    }

    @Test
    fun onlyAudiobooksCanBeSeekedByTime() {
        val actions = sessionActions(playing, timed = true)

        assertTrue(allows(actions, PlaybackState.ACTION_SEEK_TO))
        assertTrue(allows(actions, PlaybackState.ACTION_PLAY_FROM_SEARCH))
        assertFalse(allows(sessionActions(playing, false), PlaybackState.ACTION_SEEK_TO))
    }

    @Test
    fun showsLoadingPlayingAndPausedToTheCar() {
        assertEquals(PlaybackState.STATE_NONE, sessionState(idle))
        assertEquals(PlaybackState.STATE_BUFFERING, sessionState(loading))
        assertEquals(PlaybackState.STATE_PLAYING, sessionState(playing))
        assertEquals(PlaybackState.STATE_PAUSED, sessionState(paused))
    }
}
