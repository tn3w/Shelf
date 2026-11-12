package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

private fun file(name: String, track: Int = 0, disc: Int = 0, album: String = "") =
    AudioFile("content://$name", name, "", album, "", track, disc, millis = 60_000)

private fun saved(work: Int, title: String, author: String = "") =
    Saved(work, Shelf.Reading, 0, title, author)

private val book = Audiobook(
    listOf(
        Track("a", "One", 60_000),
        Track("b", "Two", 30_000),
        Track("c", "Three", 90_000),
    ),
)

private fun atom(type: String, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(ByteArray(0), ByteArray::plus)
    val size = ByteBuffer.allocate(4).putInt(8 + body.size).array()
    return size + type.toByteArray() + body
}

private fun ints(vararg values: Int) =
    ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()

private fun neroChapters(vararg marks: Mark): ByteArray {
    val entries = ByteArrayOutputStream()
    for (mark in marks) {
        entries.write(ByteBuffer.allocate(8).putLong(mark.millis * 10_000).array())
        val title = mark.title.toByteArray()
        entries.write(title.size)
        entries.write(title)
    }
    val header = ints(0x01000000, 0) + byteArrayOf(marks.size.toByte())
    return atom("moov", atom("udta", atom("chpl", header + entries.toByteArray())))
}

private fun sample(text: String): ByteArray {
    val bytes = text.toByteArray()
    return ByteBuffer.allocate(2).putShort(bytes.size.toShort()).array() + bytes
}

private fun chapterTrack(titles: List<String>, deltas: List<Int>, start: Int): ByteArray {
    val samples = titles.map(::sample)
    val offsets = samples.runningFold(start) { at, next -> at + next.size }.dropLast(1)
    val durations = deltas.flatMap { listOf(1, it) }.toIntArray()
    val table = atom(
        "stbl",
        atom("stts", ints(0, deltas.size, *durations)),
        atom("stsz", ints(0, 0, samples.size, *samples.map { it.size }.toIntArray())),
        atom("stsc", ints(0, 1, 1, 1, 1)),
        atom("stco", ints(0, offsets.size, *offsets.toIntArray())),
    )
    val media = atom("mdia", atom("mdhd", ints(0, 0, 0, 1000, 0)), atom("minf", table))
    val reference = atom("tref", atom("chap", ints(2)))
    val audio = atom("trak", atom("tkhd", ints(0, 0, 0, 1)), reference)
    val text = atom("trak", atom("tkhd", ints(0, 0, 0, 2)), media)
    return atom("moov", audio, text)
}

class AudiobooksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun chapters(bytes: ByteArray): List<Mark> {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return file.inputStream().channel.use(::mp4Chapters)
    }

    @Test
    fun ordersTracksByDiscTrackAndNaturalName() {
        val parts = audiobookOf(listOf(file("Part 10.mp3"), file("Part 2.mp3")))
        assertEquals(listOf("Part 2", "Part 10"), parts.tracks.map { it.title })
        val second = file("x.mp3", track = 1, disc = 2)
        val first = file("y.mp3", track = 5, disc = 1)
        val numbered = audiobookOf(listOf(second, first))
        assertEquals(listOf("y", "x"), numbered.tracks.map { it.title })
    }

    @Test
    fun mapsSecondsToTracksAndBack() {
        assertEquals(180, book.seconds)
        assertEquals(listOf(0 to "One", 60 to "Two", 90 to "Three"), book.chapters)
        val moved = book.at(75)
        assertEquals(1, moved.track)
        assertEquals(15_000, moved.millis)
        assertEquals(75, moved.second)
        assertEquals(2, book.at(500).track)
        assertEquals(90_000, book.at(500).millis)
    }

    @Test
    fun titleComesFromAlbumWithoutEditionNotes() {
        val files = listOf(
            file("1.mp3", album = "Der Feuerkelch (Ungekürzt)"),
            file("2.mp3", album = "Der Feuerkelch (Ungekürzt)"),
        )
        assertEquals("Der Feuerkelch", titleOf(files))
        assertEquals("Chapter 1", titleOf(listOf(file("Chapter 1.m4b"))))
    }

    @Test
    fun matchesTheBookTheAudioBelongsTo() {
        val books = listOf(
            saved(1, "Harry Potter", "J. K. Rowling"),
            saved(2, "Harry Potter and the Goblet of Fire", "J. K. Rowling"),
            saved(3, "It", "Stephen King"),
        )
        val title = "Harry Potter and the Goblet of Fire, Book 4"
        assertEquals(2, matchingBook(books, title, "Rowling")?.work)
        assertEquals(2, matchingBook(books, title, "Stephen Fry")?.work)
        assertNull(matchingBook(books, "Itchy Feet", "Stephen King"))
        assertNull(matchingBook(books, "The Hobbit", "Tolkien"))
    }

    @Test
    fun redirectMovesAudiobooks() {
        val stored = ReadingState(audiobooks = mapOf(2 to book))
        val moved = stored.redirect(mapOf(2 to 7)::get)
        assertEquals(mapOf(7 to book), moved.audiobooks)
    }

    @Test
    fun readsNeroChaptersOfM4b() {
        val marks = listOf(Mark("Prologue", 0), Mark("Chapter 1", 754_250))
        val head = atom("ftyp", "M4B ".toByteArray())
        val bytes = head + neroChapters(*marks.toTypedArray())
        assertEquals(marks, chapters(bytes))
    }

    @Test
    fun readsQuickTimeChapterTrack() {
        val titles = listOf("Opening", "Kapitel 2", "Épilogue")
        val head = atom("ftyp", "M4A ".toByteArray())
        val data = titles.map(::sample).fold(ByteArray(0), ByteArray::plus)
        val movie = chapterTrack(titles, listOf(90_000, 30_500, 1_000), head.size + 8)
        val bytes = head + atom("mdat", data) + movie
        val expected = listOf(Mark(titles[0], 0), Mark(titles[1], 90_000))
        assertEquals(expected + Mark(titles[2], 120_500), chapters(bytes))
    }

    @Test
    fun ignoresFilesWithoutChapters() {
        assertTrue(chapters(atom("ftyp", "M4A ".toByteArray())).isEmpty())
        assertTrue(chapters("not an mp4 file".toByteArray()).isEmpty())
    }

    @Test
    fun singleFileChaptersDriveNavigation() {
        val marks = listOf(Mark("One", 0), Mark("Two", 600_000))
        val track = Track("a", "Book", 900_000, marks)
        val single = Audiobook(listOf(track), millis = 650_000)
        assertEquals(listOf(0 to "One", 600 to "Two"), single.chapters)
        assertEquals(1, single.chapterAt(single.second))
        val files = listOf(file("a.m4b").copy(chapters = marks))
        assertEquals(marks, audiobookOf(files).tracks.single().chapters)
    }

    @Test
    fun repairsUtf8TagsReadAsWindows1252() {
        assertEquals("Fox Runner – Die Macht", repairedText("Fox Runner â€“ Die Macht"))
        assertEquals("Grüße", repairedText("GrÃ¼ÃŸe"))
        assertEquals("Müller", repairedText("Müller"))
        assertEquals("東京", repairedText("東京"))
        assertEquals("plain", repairedText("plain"))
    }
}
