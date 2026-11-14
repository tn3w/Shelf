package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun prose(subject: String) =
    "$subject walked along the river for a long time, thinking about everything " +
        "that had happened since the winter. It was a quiet evening. Nobody spoke. " +
        "The water moved slowly and the light faded behind the hills. "

private fun chapter(title: String, subject: String) = listOf(
    Block(title, heading = 1),
    Block(prose(subject).repeat(3)),
)

private fun document(vararg blocks: Block) = byHeadings(blocks.toList())

private fun spoken(vararg blocks: Block) = speechOf(document(*blocks)).map { it.text }

class SpeechTest {
    @Test
    fun skipsFrontAndBackMatterAroundNumberedChapters() {
        val text = spoken(
            Block("Copyright 2020. All rights reserved. ISBN 123", heading = 1),
            Block("Contents", heading = 1),
            Block("Chapter 1", heading = 2),
            Block("Chapter 2", heading = 2),
            *chapter("Chapter 1", "Anna").toTypedArray(),
            *chapter("Chapter 2", "Ben").toTypedArray(),
            Block("Acknowledgements", heading = 1),
            Block("Thanks to everybody who helped me write this book."),
            Block("About the author", heading = 1),
            Block(prose("Zed").repeat(2)),
        )
        assertEquals("Chapter 1", text.first())
        assertTrue(text.any { it.contains("Ben") })
        assertFalse(text.any { it.contains("Zed") || it.contains("ISBN") })
    }

    @Test
    fun keepsPrologueAndEpilogue() {
        val text = spoken(
            *chapter("Prologue", "Ann").toTypedArray(),
            *chapter("Chapter 1", "Bob").toTypedArray(),
            *chapter("Epilogue", "Cy").toTypedArray(),
        )
        assertEquals("Prologue", text.first())
        assertTrue(text.any { it.startsWith("Cy") })
    }

    @Test
    fun cutsAtAnyStartAndEndBanner() {
        val text = spoken(
            Block("Some licence text from a site."),
            Block("=== BEGIN OF TEXT ==="),
            *chapter("Chapter 1", "Dee").toTypedArray(),
            Block("*** END OF THIS EBOOK ***"),
            Block("Donate to keep this site running."),
        )
        assertEquals("Chapter 1", text.first())
        assertFalse(
            text.toString(),
            text.any {
                it.contains("Donate") || it.contains("licence")
            },
        )
    }

    @Test
    fun keepsBannerLookalikesInProse() {
        val text = spoken(
            *chapter("Chapter 1", "Eve").toTypedArray(),
            Block("End of Chapter 1 was near, and the end of the road too."),
        )
        assertTrue(text.last().startsWith("End of Chapter 1"))
    }

    @Test
    fun dropsRunningHeadersAndNotes() {
        val blocks = (1..6).flatMap {
            listOf(Block("MY BOOK $it"), Block(prose("Fay") + "[3]"))
        }
        val text = speechOf(
            textDocument(
                blocks.joinToString("\n\n") {
                    it.text
                },
            ),
        ).map { it.text }
        assertFalse(text.any { it.contains("MY BOOK") || it.contains("[3]") })
    }

    @Test
    fun splitsSentencesButNotAbbreviations() {
        assertEquals(
            listOf("Mr. Bennet said hello.", "“Why?” asked J. K. Rowling.", "Fine!"),
            sentences("Mr. Bennet said hello. “Why?” asked J. K. Rowling. Fine!"),
        )
    }

    @Test
    fun splitsHugeSentences() {
        assertTrue(sentences("a".repeat(8000)).all { it.length <= 3500 })
    }

    @Test
    fun passagesKnowTheirPlace() {
        val passages = speechOf(document(*chapter("Chapter 1", "Gus").toTypedArray()))
        assertEquals(0, passages.first().section)
        assertTrue(passages.map { it.offset }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun readsPdfPagesWithoutHeadersAndPageNumbers() {
        val pages = (1..8).map { page ->
            "BOOK TITLE\n${prose("Hal${'a' + page}").trim()}\n$page"
        }
        val text = speechOf(pages).map { it.text }
        assertFalse(text.any { it.contains("BOOK TITLE") })
        assertTrue(text.none { it.trim().all(Char::isDigit) })
        assertTrue(text.toString(), text.isNotEmpty())
    }

    @Test
    fun detectsLanguage() {
        fun language(text: String) = languageOf(listOf(Passage(text.repeat(5), 0, 0)))
        assertEquals("en", language("It was the best of times and the girl had to go. "))
        assertEquals("de", language("Der Mann ist nicht da und die Frau sagt ein Wort. "))
        assertEquals("ru", language("Он был здесь и она тоже была здесь. "))
        assertEquals("ja", language("これは日本語の文章です。私は本を読みます。"))
    }
}
