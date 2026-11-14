package dev.tn3w.shelf

import android.os.Process
import dev.tn3w.shelf.data.Attributor
import dev.tn3w.shelf.data.Character
import dev.tn3w.shelf.data.ModelJudge
import dev.tn3w.shelf.data.Passage
import dev.tn3w.shelf.data.SpeakerModel
import dev.tn3w.shelf.data.Spoken
import dev.tn3w.shelf.data.Tokenizer
import dev.tn3w.shelf.data.UNKNOWN_SPEAKER
import dev.tn3w.shelf.data.Utterance
import dev.tn3w.shelf.data.ggufMetadata
import dev.tn3w.shelf.data.json
import dev.tn3w.shelf.data.paragraphsOf
import dev.tn3w.shelf.data.quoteStyleOf
import dev.tn3w.shelf.data.replaceFile
import dev.tn3w.shelf.data.segmentsOf
import dev.tn3w.shelf.data.utterancesOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

private const val STYLE_SAMPLE = 400
private const val LEARN_WINDOW = 20
private const val LOOKAHEAD = 40
private const val READY_PARAGRAPHS = 2
private const val READY_MILLIS = 30_000L
private const val SAVE_EVERY = 8
private const val SPEAKER_CONTEXT = 2048
private const val SPEAKER_THREADS = 3

@Serializable
private class VoiceCache(
    val paragraphs: Int,
    val characters: List<Character>,
    val checked: Set<String>,
    val speakers: Map<Int, List<String?>>,
)

class Voicing(
    private val app: ShelfApp,
    work: Int,
    private val passages: List<Passage>,
    private val model: File,
) {
    private val paragraphs = paragraphsOf(passages)
    private val starts = IntArray(paragraphs.size).also { starts ->
        passages.asReversed().forEach { starts[it.paragraph] = it.offset }
    }
    private val style = quoteStyleOf(paragraphs.take(STYLE_SAMPLE))
    private val parts = arrayOfNulls<List<Utterance>>(paragraphs.size)
    private val speakers = ConcurrentHashMap<Int, List<String?>>()
    private val cacheFile = app.library.voiceCache(work)
    private val wanted = MutableStateFlow(0)
    private val progress = MutableStateFlow(0)
    private val _characters = MutableStateFlow(listOf(Character(UNKNOWN_SPEAKER)))
    val characters = _characters.asStateFlow()
    private var cached = loadCache()

    @Volatile private var finished = false
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "voicing") }
        .asCoroutineDispatcher()
    private val job = app.scope.launch(thread) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        runCatching { direct() }
            .onFailure { android.util.Log.w("Voicing", "speaker detection failed", it) }
        finished = true
        progress.value++
    }.apply { invokeOnCompletion { thread.close() } }

    private fun loadCache(): VoiceCache? = runCatching {
        json.decodeFromString<VoiceCache>(cacheFile.readText())
    }.getOrNull()?.takeIf { it.paragraphs == paragraphs.size }?.also { cache ->
        speakers.putAll(cache.speakers)
        _characters.value = listOf(Character(UNKNOWN_SPEAKER)) + cache.characters
    }

    private fun utterances(paragraph: Int) = parts[paragraph]
        ?: utterancesOf(paragraphs[paragraph], style).also { parts[paragraph] = it }

    private fun hasDialogue(paragraph: Int) =
        utterances(paragraph).any { it.kind == Utterance.Kind.Dialogue }

    private fun isReady(paragraph: Int) =
        speakers.containsKey(paragraph) || !hasDialogue(paragraph)

    fun follow(passage: Int) {
        passages.getOrNull(passage)?.let { wanted.value = it.paragraph }
    }

    suspend fun awaitReady(passage: Int) {
        val first = passages.getOrNull(passage)?.paragraph ?: return
        val last = (first + READY_PARAGRAPHS).coerceAtMost(paragraphs.size)
        follow(passage)
        withTimeoutOrNull(READY_MILLIS) {
            progress.first { finished || (first until last).all(::isReady) }
        }
    }

    fun segments(index: Int): List<Spoken> {
        val passage = passages[index]
        val names =
            speakers[passage.paragraph] ?: return listOf(Spoken(passage.text, null))
        val paragraph = paragraphs[passage.paragraph]
        val start = passage.offset - starts[passage.paragraph]
        val end = start + passage.text.length
        return segmentsOf(paragraph, utterances(passage.paragraph), names, start, end)
            .ifEmpty { listOf(Spoken(passage.text, null)) }
    }

    fun close() {
        job.cancel()
    }

    private suspend fun direct() {
        val speaker =
            checkNotNull(SpeakerModel.open(model, SPEAKER_CONTEXT, SPEAKER_THREADS)) {
                "cannot load ${model.name}"
            }
        speaker.use {
            val tokenizer = Tokenizer.from(ggufMetadata(model))
            val attributor = Attributor(
                ModelJudge(speaker, tokenizer),
                style,
                cached?.characters.orEmpty().toMutableList(),
                cached?.checked.orEmpty().toMutableSet(),
            )
            cached = null
            try {
                attribute(attributor)
            } finally {
                save(attributor)
            }
        }
    }

    private suspend fun attribute(attributor: Attributor) {
        var cursor = wanted.value
        var learned = -1
        while (true) {
            coroutineContext.ensureActive()
            val from = wanted.value
            if (cursor < from || cursor > from + LOOKAHEAD) cursor = from
            if (cursor >= paragraphs.size || cursor >= from + LOOKAHEAD) {
                save(attributor)
                wanted.first { it != from }
                continue
            }
            if (cursor >= learned) {
                learned = (cursor + LEARN_WINDOW).coerceAtMost(paragraphs.size)
                attributor.learn(paragraphs.subList(cursor, learned))
                _characters.value =
                    listOf(Character(UNKNOWN_SPEAKER)) + attributor.characters
            }
            if (!speakers.containsKey(cursor) && hasDialogue(cursor)) {
                speakers[cursor] = attributor.speakers(paragraphs, cursor)
                if (speakers.size % SAVE_EVERY == 0) save(attributor)
            }
            cursor++
            progress.value++
        }
    }

    private fun save(attributor: Attributor) {
        val cache = VoiceCache(
            paragraphs.size,
            attributor.characters.toList(),
            attributor.checked.toSet(),
            speakers.toMap(),
        )
        cacheFile.parentFile?.mkdirs()
        replaceFile(cacheFile) {
            it.writeText(json.encodeToString(VoiceCache.serializer(), cache))
        }
    }
}
