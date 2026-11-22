package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class StarDictTest {
    @get:Rule val folder = TemporaryFolder()

    private fun dictionary(vararg entries: Pair<String, String>): StarDict {
        val definitions = ByteArrayOutputStream()
        val index = DataOutputStream(folder.newFile("test.idx").outputStream())
        entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
            .forEach { (word, definition) ->
                val bytes = definition.toByteArray()
                index.write(word.toByteArray() + 0)
                index.writeInt(definitions.size())
                index.writeInt(bytes.size)
                definitions.write(bytes)
            }
        index.close()
        folder.newFile("test.dict").writeBytes(definitions.toByteArray())
        return StarDict(folder.root)
    }

    @Test
    fun findsWordsIgnoringCaseAndPunctuation() {
        val dictionary = dictionary(
            "apple" to "a fruit",
            "Berlin" to "a city",
            "über" to "over",
            "zebra" to "an animal",
        )
        assertEquals("a fruit", dictionary.define("Apple,"))
        assertEquals("a city", dictionary.define("“berlin”"))
        assertEquals("over", dictionary.define("Über"))
        assertEquals("an animal", dictionary.define("zebra"))
    }

    @Test
    fun missesUnknownWordsAndPrefixes() {
        val dictionary = dictionary("apple" to "a fruit", "applesauce" to "a sauce")
        assertNull(dictionary.define("app"))
        assertNull(dictionary.define("pear"))
        assertNull(dictionary.define("..."))
        assertEquals("a sauce", dictionary.define("applesauce"))
    }
}
