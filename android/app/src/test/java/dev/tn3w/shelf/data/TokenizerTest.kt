package dev.tn3w.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val QWEN_CASES = listOf(
    "»Komm her«, rief Anna. „Nein“, sagte Jürgen – müde." to intArrayOf(
        12992, 42, 19786, 1059, 23703, 11, 435, 4738, 23223, 13, 14835, 8813, 258, 50686,
        85389, 619, 2391, 1984, 268, 1365, 58285, 450, 13,
    ),
    "“Don't,” said Harry's friend. They'll go at 12:30 in 1984!" to intArrayOf(
        2073, 8002, 944, 2419, 1053, 13928, 594, 4238, 13, 2379, 3278, 728, 518, 220, 16,
        17, 25, 18, 15, 304, 220, 16, 24, 23, 19, 0,
    ),
    "Line one\n\nLine   two\twith  tabs\r\n  indented" to intArrayOf(
        2460, 825, 271, 2460, 256, 1378, 46948, 220, 22398, 319, 220, 1257, 15864,
    ),
    "<|im_start|>user\nWer spricht ⟦Hallo⟧?<|im_end|>\n<|im_start|>assistant\n" to
        intArrayOf(
            151644, 872, 198, 91992, 8151, 4865, 34410, 99, 78078, 150722, 30, 151645,
            198, 151644, 77091, 198,
        ),
    "{\"speaker\": \"Ginny Weasley\"} 🙂 ça va? 東京 Ελληνικά" to intArrayOf(
        4913, 64476, 788, 330, 38, 258, 3834, 1205, 73011, 9207, 27484, 37943, 11164, 30,
        60596, 109, 46553, 7851, 243, 33486, 33486, 41424, 33269, 29762, 67337, 74134,
    ),
)

private class GgufWriter {
    private val output = ByteArrayOutputStream()

    private fun bytes(size: Int, fill: ByteBuffer.() -> Unit) = output.write(
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array(),
    )

    private fun string(text: String) {
        val encoded = text.encodeToByteArray()
        bytes(8) { putLong(encoded.size.toLong()) }
        output.write(encoded)
    }

    fun file(values: Map<String, List<Any>>, target: File) {
        output.write("GGUF".encodeToByteArray())
        bytes(20) {
            putInt(3)
            putLong(0)
            putLong(values.size.toLong())
        }
        for ((key, items) in values) {
            string(key)
            val type = if (items.first() is String) 8 else 5
            bytes(16) {
                putInt(9)
                putInt(type)
                putLong(items.size.toLong())
            }
            items.forEach {
                if (it is String) string(it) else bytes(4) { putInt(it as Int) }
            }
        }
        target.writeBytes(output.toByteArray())
    }
}

class TokenizerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun tokenizer(tokens: List<String>, merges: List<String>): Tokenizer {
        val file = folder.newFile()
        GgufWriter().file(
            mapOf(
                "tokenizer.ggml.tokens" to tokens,
                "tokenizer.ggml.token_type" to
                    tokens.map { if (it == "<|end|>") 3 else 1 },
                "tokenizer.ggml.merges" to merges,
            ),
            file,
        )
        return Tokenizer.from(ggufMetadata(file))
    }

    @Test
    fun mergesByRankAndSplitsSpecialTokens() {
        val tokenizer = tokenizer(
            listOf("h", "e", "l", "o", "Ġ", "he", "ll", "hell", "hello", "Ġh", "<|end|>"),
            listOf("h e", "l l", "he ll", "hell o", "Ġ h"),
        )
        val ids = tokenizer.encode("hello<|end|> hell")
        assertArrayEquals(intArrayOf(8, 10, 4, 7), ids)
        assertEquals("hello<|end|> hell", tokenizer.decode(ids))
    }

    @Test
    fun mergesRepeatedPairsFromTheLeft() {
        val tokenizer = tokenizer(listOf("a", "aa", "aaa"), listOf("a a", "aa a"))
        assertArrayEquals(intArrayOf(1, 2), tokenizer.encode("aaaaa"))
        assertArrayEquals(intArrayOf(1, 1), tokenizer.encode("aaaa"))
    }

    @Test
    fun matchesQwenOnTheRealModel() {
        val model = System.getenv("SHELF_SPEAKER_MODEL")?.let(::File)
        assumeTrue(model?.exists() == true)
        val tokenizer = Tokenizer.from(ggufMetadata(model!!))
        for ((text, expected) in QWEN_CASES) {
            assertArrayEquals(text, expected, tokenizer.encode(text))
            assertEquals(text, tokenizer.decode(expected))
        }
    }

    @Test
    fun reusesTheSharedPromptPrefix() {
        assertEquals(2, sharedPrefix(intArrayOf(1, 2, 3), intArrayOf(1, 2, 4, 5)))
        assertEquals(0, sharedPrefix(intArrayOf(), intArrayOf(1)))
        assertEquals(3, sharedPrefix(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3)))
    }
}
