package dev.tn3w.shelf.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

private val SAMPLES = File("src/test/samples")

private fun spoken(file: File): List<String> {
    assumeTrue(file.exists())
    val document = openDocument(file) as TextDocument
    return speechOf(document).map { it.text }
}

class SpeechSamplesTest {
    private fun check(file: File, first: String, last: String) {
        val text = spoken(file)
        val name = file.name
        assertTrue("$name first: ${text.first()}", text.first().startsWith(first))
        assertTrue("${file.name} last: ${text.last()}", text.last().endsWith(last))
        println("${file.name}: ${text.size} passages, ${text.sumOf { it.length }} chars")
    }

    @Test
    fun prideAndPrejudiceEveryFormat() {
        val formats = listOf("txt", "md", "html", "epub", "mobi", "docx", "odt", "rtf")
        for (format in formats) {
            val file = SAMPLES.resolve("pride-and-prejudice.$format")
            check(file, "Chapter I", "uniting them.")
        }
    }

    @Test
    fun otherSamples() {
        check(SAMPLES.resolve("the-vampire.azw3"), "Introduction", "vampire!")
        val poem = SAMPLES.resolve("the-waste-land.fb2")
        check(poem, "I. THE BURIAL", "Shantih shantih shantih")
    }
}
