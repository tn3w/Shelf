package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private val RESOURCES = File("src/main/res")

private fun translatable(folder: String): Set<String> {
    val file = RESOURCES.resolve("$folder/strings.xml")
    if (!file.exists()) return emptySet()
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(file).documentElement
    val nodes = root.childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        .filter { it.getAttribute("translatable") != "false" }
        .map { "${it.tagName}:${it.getAttribute("name")}" }
        .toSet()
}

private val PLURAL_FORMS = mapOf(
    listOf("one", "other") to listOf("de", "et", "sv", "fi"),
    listOf("one", "many", "other") to listOf("fr", "es", "pt", "it"),
    listOf("one", "few", "many", "other") to listOf("pl", "cs", "ru"),
    listOf("zero", "one", "other") to listOf("lv"),
    listOf("other") to listOf("ko", "ja"),
).flatMap { (forms, languages) -> languages.map { it to forms.toSet() } }.toMap()

private fun plurals(language: String): Map<String, Map<String, String>> {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(RESOURCES.resolve("values-$language/strings.xml")).documentElement
    val plurals = root.getElementsByTagName("plurals")
    return (0 until plurals.length).associate { index ->
        val plural = plurals.item(index) as Element
        val items = plural.getElementsByTagName("item")
        plural.getAttribute("name") to (0 until items.length).associate {
            val item = items.item(it) as Element
            item.getAttribute("quantity") to item.textContent
        }
    }
}

class TranslationsTest {
    @Test
    fun pluralsHaveEveryFormAndDifferWhereThereAreSeveral() {
        for (language in APP_LANGUAGES - "en") {
            val forms = PLURAL_FORMS.getValue(language)
            for ((name, items) in plurals(language)) {
                assertEquals("$language $name", forms, items.keys)
                val distinct = items.values.toSet().size > 1
                assertTrue("$language $name all forms equal", forms.size == 1 || distinct)
            }
        }
    }

    @Test
    fun appSpeaksEveryCatalogueLanguageAndMore() {
        assertTrue(APP_LANGUAGES.containsAll(LANGUAGES))
        assertTrue(APP_LANGUAGES.size > LANGUAGES.size)
    }

    @Test
    fun regionalLocalesOnlyOverrideKnownStrings() {
        val english = translatable("values")
        for (tag in APP_LOCALES - APP_LANGUAGES.toSet()) {
            val (language, region) = tag.split("-")
            val overrides = translatable("values-$language-r$region")
            assertTrue(tag, overrides.isNotEmpty() && english.containsAll(overrides))
        }
    }

    @Test
    fun everyAppLanguageIsFullyTranslated() {
        val english = translatable("values")
        for (language in APP_LANGUAGES - "en") {
            assertEquals(language, english, translatable("values-$language"))
        }
    }
}
