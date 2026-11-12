package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

private val POCKET_CASES = listOf(
    "Dex Jones verbrachte eine Menge Zeit im Garten hinter dem Haus." to intArrayOf(
        1598, 857, 2704, 270, 295, 284, 672, 263, 305, 1752, 428, 332,
        260, 1501, 260, 736, 288, 579, 269,
    ),
    "Größe, Übermut  und Spaß – 12:30 Uhr!" to intArrayOf(
        3188, 261, 260, 199, 160, 590, 989, 260, 264, 767, 1349, 260,
        230, 132, 151, 260, 53, 54, 323, 55, 52, 1712, 324,
    ),
    "Ein Smiley 🙂 und 東京." to intArrayOf(
        382, 376, 1136, 521, 444, 260, 244, 163, 157, 134, 264, 260,
        234, 161, 181, 232, 190, 176, 269,
    ),
)

private val letters = (('a'..'z') + ('A'..'Z') + " .,!?".toList()).map { "$it" }
private val characters = Unigram(
    listOf("<unk>" to 0f, "▁" to -1f) + letters.map { it to -1f } +
        letters.map { "▁$it" to -1f },
)

private fun text(
    replacements: Map<Char, String> = emptyMap(),
    semicolons: Boolean = false,
) = PocketText(characters, replacements, semicolons)

class PocketTest {
    @Test
    fun segmentsWordsByBestScoreWithByteFallback() {
        val pieces = listOf(
            "<unk>" to 0f, "▁" to -2f, "▁ab" to -1f, "a" to -1f, "b" to -1f, "▁a" to -1f,
            "<0xC3>" to -5f, "<0xA4>" to -5f,
        )
        val unigram = Unigram(pieces)
        assertArrayEquals(intArrayOf(2, 1, 6, 7), unigram.encode("ab ä"))
        assertArrayEquals(intArrayOf(5, 1, 2), unigram.encode("a  ab"))
    }

    @Test
    fun preparesTextLikePocket() {
        val german = text(mapOf('»' to "", '«' to "", '–' to ","), semicolons = true)
        assertEquals("Komm her! rief sie.", german.prepare("»komm her!«, rief sie"))
        assertEquals("Erst das, dann dies.", german.prepare("erst das; dann dies –"))
        assertEquals("Fertig?”", text().prepare("fertig?”"))
    }

    @Test
    fun groupsSentencesIntoChunksOfFiftyTokens() {
        val sentence = "This is a sentence of words."
        val chunks = text().chunks(List(4) { sentence }.joinToString(" "))
        assertEquals(2, chunks.size)
        assertEquals(3, chunks.first().framesAfterEos)
        assertEquals(5, text().chunks("Short one.").single().framesAfterEos)
        assertEquals(emptyList<PocketChunk>(), text().chunks("  "))
    }

    @Test
    fun readsReplacementsFromTheConfig() {
        val config = "remove_semicolons: true\nreplace_characters:\n  '\"': ''\n" +
            "  '’': ''''\n  '–': ','\n\nflow_lm:\n  dim: 1"
        assertEquals(mapOf('"' to "", '’' to "'", '–' to ","), replacementsOf(config))
        assertEquals(emptyMap<Char, String>(), replacementsOf("flow_lm:\n  dim: 1"))
    }

    @Test
    fun matchesTheRealTokenizer() {
        val model = System.getenv("SHELF_POCKET_MODEL")?.let(::File)
        assumeTrue(model?.exists() == true)
        val metadata = ggufMetadata(model!!)
        val unigram = unigramOf(metadata["tokenizer.json"] as String)!!
        for ((sentence, expected) in POCKET_CASES) {
            assertArrayEquals(sentence, expected, unigram.encode(sentence))
        }
        assertTrue(PocketText.from(metadata) != null)
        assertTrue(pocketVoices(metadata).isNotEmpty())
    }
}
