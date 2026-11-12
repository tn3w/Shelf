package dev.tn3w.shelf.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

private val content = "a model file with some bytes in it".toByteArray()
private val checksum = MessageDigest.getInstance("SHA-256").digest(content).toHexString()
private val file =
    ModelFile("model.gguf", "https://example.org/model", content.size.toLong(), checksum)

private fun model(licence: String = "Apache-2.0", name: String = "model.gguf"): Model {
    val files = listOf(file.copy(name = name))
    return Model("speaker", "speaker", "2026-11", licence, files = files)
}

private fun voice(id: String, gender: Gender, years: Int?, licence: String = "CC0-1.0") =
    ModelVoice(id, "model.gguf", gender, years, licence)

private fun manifest(vararg models: Model, format: Int = 1): String {
    val manifest = ModelManifest(format, models.toList())
    return json.encodeToString(ModelManifest.serializer(), manifest)
}

class ModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val requested = mutableListOf<Long>()

    private fun server(honoursRange: Boolean = true, body: ByteArray = content) =
        { offset: Long ->
            requested += offset
            val start = if (honoursRange) offset else 0
            Ranged(body.inputStream(start.toInt(), body.size), start)
        }

    private fun download(partial: java.io.File, open: suspend (Long) -> Ranged) =
        runBlocking { runCatching { resume(file, partial, open) {} } }

    @Test
    fun groupsVoicesByLanguageFromStandardToHigh() {
        fun tts(id: String, language: String, quality: String) =
            model().copy(id = id, kind = "tts", language = language, quality = quality)
        val high = tts("de-high", "de", "high")
        val standard = tts("de", "de", "standard")
        val french = tts("fr", "fr", "standard")
        val grouped = voicesByLanguage(listOf(high, model(), french, standard))
        val expected = mapOf("de" to listOf(standard, high), "fr" to listOf(french))
        assertEquals(expected, grouped)
    }

    @Test
    fun prefersInstalledModelOtherwiseFirst() {
        val standard = model().copy(id = "tts-de")
        val high = model().copy(id = "tts-de-24l")
        val choices = listOf(standard, high)
        assertEquals(standard, preferredModel(choices, emptySet()))
        assertEquals(high, preferredModel(choices, setOf(high.folder)))
        assertEquals(high, preferredModel(choices, setOf("tts-de-24l@old")))
    }

    @Test
    fun keepsOnlyOpenlyLicensedWellFormedModels() {
        val text = manifest(
            model(),
            model(licence = "CC-BY-NC-4.0"),
            model(name = "../escape"),
            model().copy(files = listOf(file.copy(sha256 = "short"))),
            model().copy(files = listOf(file.copy(url = "http://example.org/model"))),
        )
        assertEquals(listOf(model()), parseModels(text))
        assertTrue(parseModels(manifest(model(), format = 2)).isEmpty())
        assertTrue(parseModels("not json").isEmpty())
    }

    @Test
    fun rejectsClosedOrMissingVoices() {
        val anna = voice("anna", Gender.Female, 22)
        val jean = voice("jean", Gender.Male, 30, "CC-BY-NC-4.0")
        val open = model().copy(voices = listOf(anna))
        val closed = open.copy(voices = listOf(jean))
        val missing = open.copy(voices = listOf(anna.copy(file = "x")))
        assertEquals(listOf(open), parseModels(manifest(open, closed, missing)))
    }

    @Test
    fun turnsVoicesIntoCastingPool() {
        val voices =
            listOf(voice("eve", Gender.Female, 19), voice("paul", Gender.Male, null))
        val pool = voicePool(model().copy(voices = voices))
        assertEquals(listOf(Age.Teen, Age.Adult), pool.map { it.age })
        assertEquals(listOf(Gender.Female, Gender.Male), pool.map { it.gender })
    }

    @Test
    fun hidesModelsTooLargeForThePhone() {
        val large = model().copy(ram = 3_000_000_000)
        assertTrue(fits(large, 8_000_000_000))
        assertFalse(fits(large, 4_000_000_000))
    }

    @Test
    fun resumesPartialDownload() {
        val partial = folder.newFile().apply { writeBytes(content.copyOf(10)) }
        assertTrue(download(partial, server()).isSuccess)
        assertEquals(listOf(10L), requested)
        assertTrue(partial.readBytes().contentEquals(content))
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val partial = folder.newFile().apply { writeBytes(content.copyOf(10)) }
        assertTrue(download(partial, server(honoursRange = false)).isSuccess)
        assertTrue(partial.readBytes().contentEquals(content))
    }

    @Test
    fun skipsNetworkWhenAlreadyComplete() {
        val partial = folder.newFile().apply { writeBytes(content) }
        assertTrue(download(partial, server()).isSuccess)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun deletesFileWithWrongChecksum() {
        val partial = folder.newFile()
        val tampered = content.copyOf().also { it[0] = 'X'.code.toByte() }
        assertTrue(download(partial, server(body = tampered)).isFailure)
        assertFalse(partial.exists())
    }

    @Test
    fun checksumCoversBytesFromBeforeTheResume() {
        val tampered = content.copyOf(10).also { it[0] = 'X'.code.toByte() }
        val partial = folder.newFile().apply { writeBytes(tampered) }
        assertTrue(download(partial, server()).isFailure)
        assertFalse(partial.exists())
    }

    @Test
    fun keepsIncompleteFileForLaterResume() {
        val partial = folder.newFile()
        assertTrue(download(partial, server(body = content.copyOf(10))).isFailure)
        assertEquals(10, partial.length())
    }

    @Test
    fun rejectsFileLargerThanListed() {
        val partial = folder.newFile()
        assertTrue(download(partial, server(body = content + content)).isFailure)
    }

    @Test
    fun tellsInstalledUpdateAndAvailable() {
        assertEquals(PackState.Installed, stateOf(model(), setOf("speaker@2026-11")))
        assertEquals(PackState.Update, stateOf(model(), setOf("speaker@2026-10")))
        assertEquals(PackState.Available, stateOf(model(), setOf("speakers@2026-11")))
    }
}
