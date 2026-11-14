package dev.tn3w.shelf.data

import android.os.Process
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

const val POCKET_RATE = 24_000
const val POCKET_FRAME = 1920
private const val MAX_TOKENS = 50
private const val SHORT_WORDS = 4
private const val TEMPERATURE = 0.3f
private const val TERMINAL = ".!?…"
private const val WEAK = ",;:-–—"
private const val CLOSERS = "\"'”’)]»"
private val REPLACEMENT = Regex("^\\s+'((?:[^']|'')*)':\\s*'((?:[^']|'')*)'\\s*$")
private val SENTENCE_MARK_COMMA = Regex("([.!?…])\\s*[,;:]")
private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
private val CLAUSE_END = Regex("(?<=[,;:])\\s+")
private val SPACES = Regex("\\s+")

class PocketText(
    private val tokenizer: Unigram,
    private val replacements: Map<Char, String>,
    private val removeSemicolons: Boolean,
) {
    fun prepare(raw: String): String {
        var text = raw.trim()
        if (replacements.isNotEmpty()) {
            text = text.map { replacements[it] ?: it.toString() }.joinToString("")
                .split(SPACES).filter(String::isNotEmpty).joinToString(" ")
            text = text.replace(SENTENCE_MARK_COMMA, "$1")
        }
        text = text.replace('\n', ' ').replace('\r', ' ').replace("  ", " ")
        if (removeSemicolons) text = text.replace(';', ',')
        if (text.isEmpty()) return text
        text = text.replaceFirstChar { it.uppercaseChar() }
        return withTerminalPunctuation(text)
    }

    private fun withTerminalPunctuation(text: String): String {
        val core = text.trimEnd { it in CLOSERS || it == ' ' }
        val closers = text.substring(core.length).trim()
        if (core.isEmpty() || core.last() in TERMINAL) return text
        if (core.last() in
            WEAK
        ) {
            return core.trimEnd { it in WEAK || it == ' ' } + "." + closers
        }
        return "$text."
    }

    fun chunks(raw: String): List<PocketChunk> {
        val text = prepare(raw)
        if (text.isBlank()) return emptyList()
        val pieces = text.split(SENTENCE_END).flatMap { sentence ->
            if (tokenizer.encode(sentence).size <= MAX_TOKENS) {
                listOf(sentence)
            } else {
                sentence.split(CLAUSE_END)
            }
        }
        val grouped = mutableListOf<String>()
        for (piece in pieces.filter(String::isNotBlank)) {
            val last = grouped.lastOrNull()
            val joined = "$last $piece"
            if (last != null && tokenizer.encode(joined).size <= MAX_TOKENS) {
                grouped[grouped.lastIndex] = joined
            } else {
                grouped += piece
            }
        }
        return grouped.map { chunk ->
            val prepared = prepare(chunk)
            val short = prepared.split(' ').size <= SHORT_WORDS
            PocketChunk(tokenizer.encode(prepared), if (short) 5 else 3)
        }
    }

    companion object {
        fun from(metadata: Map<String, Any?>): PocketText? {
            val json = metadata["tokenizer.json"] as? String ?: return null
            val config = metadata["pocket.config"] as? String ?: return null
            val unigram = unigramOf(json) ?: return null
            return PocketText(
                unigram, replacementsOf(config),
                "remove_semicolons: true" in config,
            )
        }
    }
}

fun unigramOf(json: String): Unigram? {
    val vocabulary = Json.parseToJsonElement(json).jsonObject["model"]?.jsonObject
        ?.get("vocab")?.jsonArray ?: return null
    return Unigram(
        vocabulary.map { entry ->
            val pair = entry as JsonArray
            pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.float
        },
    )
}

class PocketChunk(val tokens: IntArray, val framesAfterEos: Int)

internal fun replacementsOf(config: String): Map<Char, String> {
    val lines = config.lines()
    val start = lines.indexOfFirst { it.trimEnd() == "replace_characters:" }
    if (start < 0) return emptyMap()
    return lines.drop(start + 1)
        .takeWhile { it.startsWith(" ") }
        .mapNotNull(REPLACEMENT::find)
        .map {
            it.groupValues[1].replace("''", "'") to it.groupValues[2].replace("''", "'")
        }
        .filter { it.first.length == 1 }
        .associate { it.first[0] to it.second }
}

@Suppress("UNCHECKED_CAST")
fun pocketVoices(metadata: Map<String, Any?>) =
    (metadata["pocket.voices"] as? List<String>).orEmpty()

class PocketModel private constructor(private var handle: Long) : AutoCloseable {
    private val samples = FloatArray(POCKET_FRAME)

    fun start(voice: String, chunk: PocketChunk, seed: Long) =
        start(handle, voice, chunk.tokens, chunk.framesAfterEos, TEMPERATURE, seed)

    fun next(): FloatArray? = if (next(handle, samples) > 0) samples else null

    override fun close() {
        if (handle != 0L) release(handle)
        handle = 0
    }

    companion object {
        fun open(file: File, threads: Int): PocketModel? {
            if (!SpeakerModel.available) return null
            val handle = withPriority(Process.THREAD_PRIORITY_AUDIO) {
                load(file.path, threads)
            }
            return if (handle == 0L) null else PocketModel(handle)
        }

        @JvmStatic private external fun load(path: String, threads: Int): Long

        @JvmStatic private external fun release(handle: Long)

        @JvmStatic
        private external fun start(
            handle: Long,
            voice: String,
            tokens: IntArray,
            after: Int,
            temperature: Float,
            seed: Long,
        ): Boolean

        @JvmStatic private external fun next(handle: Long, samples: FloatArray): Int
    }
}
