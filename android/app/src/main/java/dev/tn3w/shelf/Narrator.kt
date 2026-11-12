package dev.tn3w.shelf

import android.speech.tts.TextToSpeech
import android.util.Log
import dev.tn3w.shelf.data.Chapter
import dev.tn3w.shelf.data.Passage
import dev.tn3w.shelf.data.PocketModel
import dev.tn3w.shelf.data.PocketText
import dev.tn3w.shelf.data.Progress
import dev.tn3w.shelf.data.Settings
import dev.tn3w.shelf.data.Sleep
import dev.tn3w.shelf.data.Spoken
import dev.tn3w.shelf.data.chapterStarts
import dev.tn3w.shelf.data.fadeVolume
import dev.tn3w.shelf.data.ggufMetadata
import dev.tn3w.shelf.data.languageOf
import dev.tn3w.shelf.data.pocketVoices
import dev.tn3w.shelf.data.secondsLeft
import dev.tn3w.shelf.data.voicePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val AHEAD = 3
private const val POCKET_THREADS = 4
private const val LOG_TAG = "Narrator"

class Source(
    val passages: List<Passage>,
    val chapters: List<Chapter>,
    val progress: Progress,
    val progressAt: (Passage, Float) -> Progress,
)

class Narrator(app: ShelfApp) :
    Playback(app),
    SpeechEvents {
    private var source: Source? = null
    private var passages = emptyList<Passage>()
    private var chapters = emptyList<Pair<Chapter, Int>>()
    private var output: SpeechOutput? = null
    private var voicing: Voicing? = null
    private var casting: Job? = null
    private var language = ""

    @Volatile private var generation = 0
    private var anchor = 0
    private var pending = false

    fun open(work: Int, title: String, loaded: Source) {
        val now = current.value
        if (now.work == work && (now.active || now.loading)) return
        stop()
        app.stopOthers(this)
        source = loaded
        passages = loaded.passages
        chapters = chapterStarts(passages, loaded.chapters)
        language = languageOf(passages) ?: Locale.getDefault().language
        val start = passages.indexOfFirst {
            isAtOrAfter(it, loaded.progress)
        }.coerceAtLeast(0)
        current.value = Narration(work = work, title = title, loading = true)
        app.scope.launch {
            val settings = app.library.settings.first()
            current.update {
                it.copy(
                    rate = settings.speechRate,
                    voice = settings.speechVoice,
                    characterVoices = settings.characterVoices,
                    canVoiceCharacters = app.models.installed("speaker") != null,
                )
            }
            if (settings.characterVoices) startVoicing(work)
            val pocket = withContext(Dispatchers.IO) { pocketOutput(settings) }
            if (pocket ==
                null
            ) {
                createTts(work, settings, start)
            } else {
                ready(work, pocket, start)
            }
        }
    }

    private fun isAtOrAfter(passage: Passage, progress: Progress) =
        passage.section > progress.section ||
            (passage.section == progress.section && passage.offset >= progress.offset)

    private fun pocketOutput(settings: Settings): SpeechOutput? {
        val model = app.models.installed("tts", language) ?: return null
        return runCatching {
            val file = app.models.fileOf(model)
            val metadata = ggufMetadata(file)
            val text = checkNotNull(PocketText.from(metadata)) { "unsupported tokenizer" }
            val names = pocketVoices(metadata).toSet()
            val pool = voicePool(model).filter { it.id in names }
            check(pool.isNotEmpty()) { "no voices in ${file.name}" }
            val engine = checkNotNull(PocketModel.open(file, POCKET_THREADS)) {
                "cannot load ${file.name}"
            }
            PocketOutput(engine, text, this, pool, settings.speechVoice)
        }.onFailure { Log.w(LOG_TAG, "natural voices unavailable", it) }.getOrNull()
    }

    private fun createTts(work: Int, settings: Settings, start: Int) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(app) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                current.update { it.copy(loading = false, failed = true) }
                return@TextToSpeech
            }
            val locale = Locale.forLanguageTag(language)
            val result = tts.setLanguage(locale)
            val missing = result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            val voices = tts.voices.orEmpty().filter {
                it.locale.language ==
                    locale.language
            }
            current.update { it.copy(missingVoice = missing) }
            ready(work, TtsOutput(tts, this, voices, settings.speechVoice), start)
        }
    }

    private fun ready(work: Int, created: SpeechOutput, start: Int) {
        if (!current.value.loading || current.value.work != work) return created.close()
        output = created
        created.setRate(current.value.rate)
        moveTo(start)
        current.update {
            it.copy(
                loading = false,
                count = passages.size,
                voices = created.voices,
                voice = created.narrator,
                natural = created is PocketOutput,
            )
        }
        watchCast()
        if (pending) play()
    }

    private fun startVoicing(work: Int) {
        val model = app.models.installed("speaker") ?: return
        voicing = Voicing(app, work, passages, app.models.fileOf(model))
        watchCast()
    }

    private fun watchCast() {
        val voices = voicing ?: return
        val speech = output ?: return
        casting?.cancel()
        casting = app.scope.launch(Dispatchers.Main) {
            voices.characters.collect { characters ->
                speech.recast(characters)
                current.update { it.copy(speakers = characters.size - 1) }
            }
        }
    }

    override fun started(tag: Int, index: Int) {
        if (tag == generation) app.scope.launch(Dispatchers.Main) { started(index) }
    }

    override fun done(tag: Int, index: Int) {
        if (tag == generation) app.scope.launch(Dispatchers.Main) { finished(index) }
    }

    private fun started(index: Int) {
        moveTo(index)
        enqueue(index + AHEAD)
        save()
    }

    private fun finished(index: Int) {
        if (index + 1 >= passages.size) {
            reachedEnd()
            return finishAt(passages.lastIndex)
        }
        if (current.value.sleep == Sleep.ChapterEnd && atChapterStart(index + 1, true)) {
            finishAt(index + 1)
        }
    }

    private fun finishAt(next: Int) {
        moveTo(next)
        finish()
    }

    private fun atChapterStart(index: Int, past: Boolean) =
        chapters.any { it.second == index } && (!past || index > anchor)

    private fun moveTo(index: Int) {
        val passage = passages.getOrNull(index) ?: return
        val chapter = chapters.lastOrNull { it.second <= index }?.first?.title.orEmpty()
        val rate = current.value.rate
        voicing?.follow(index)
        current.update {
            it.copy(
                index = index,
                text = passage.text,
                section = passage.section,
                offset = passage.offset,
                chapter = chapter,
                secondsLeft = secondsLeft(passages, index, rate),
            )
        }
    }

    private fun segments(index: Int) =
        voicing?.segments(index) ?: listOf(Spoken(passages[index].text, null))

    private fun enqueue(index: Int, flush: Boolean = false) {
        val speech = output ?: return
        if (index >= passages.size) return
        val capped =
            current.value.sleep == Sleep.ChapterEnd && atChapterStart(index, true)
        if (capped) return
        val sleeping = current.value.sleep is Sleep.After
        val volume = if (sleeping) fadeVolume(sleepLeftMillis) else 1f
        speech.speak(generation, index, segments(index), flush, volume)
    }

    override fun play() {
        if (output == null) {
            pending = true
            return
        }
        pending = false
        startPlaying()
        val owner = ++generation
        val index = current.value.index
        app.scope.launch(Dispatchers.Main) {
            current.update { it.copy(preparing = true) }
            voicing?.awaitReady(index)
            current.update { it.copy(preparing = false) }
            if (owner == generation &&
                current.value.playing
            ) {
                speakFrom(current.value.index)
            }
        }
    }

    override fun pause() {
        pending = false
        generation++
        output?.stop()
        stopPlaying()
        save()
    }

    private fun speakFrom(index: Int) {
        generation++
        anchor = index
        for (offset in 0 until AHEAD) enqueue(index + offset, flush = offset == 0)
    }

    override fun seek(index: Int) {
        val target = index.coerceIn(0, passages.lastIndex.coerceAtLeast(0))
        moveTo(target)
        if (current.value.playing) speakFrom(target) else save()
    }

    override fun skip(delta: Int) = seek(current.value.index + delta)

    override fun skipChapter(delta: Int) {
        val index = current.value.index
        val starts = chapters.map { it.second }
        val target = if (delta > 0) {
            starts.firstOrNull { it > index }
        } else {
            starts.lastOrNull { it < index - 1 } ?: 0
        }
        seek(target ?: passages.lastIndex)
    }

    override fun setRate(rate: Float) {
        output?.setRate(rate)
        current.update { it.copy(rate = rate) }
        moveTo(current.value.index)
        app.scope.launch { app.library.updateSettings { it.copy(speechRate = rate) } }
        if (current.value.playing && output is TtsOutput) speakFrom(current.value.index)
    }

    fun setVoice(name: String) {
        val speech = output ?: return
        speech.setNarrator(name)
        current.update { it.copy(voice = speech.narrator) }
        app.scope.launch { app.library.updateSettings { it.copy(speechVoice = name) } }
        if (current.value.playing) speakFrom(current.value.index)
    }

    fun setCharacterVoices(enabled: Boolean) {
        current.update { it.copy(characterVoices = enabled) }
        app.scope.launch {
            app.library.updateSettings { it.copy(characterVoices = enabled) }
        }
        casting?.cancel()
        voicing?.close()
        voicing = null
        output?.recast(emptyList())
        current.update { it.copy(speakers = 0) }
        if (enabled && current.value.work != 0) startVoicing(current.value.work)
        if (current.value.playing) speakFrom(current.value.index)
    }

    override fun setSleep(sleep: Sleep) {
        anchor = current.value.index
        super.setSleep(sleep)
    }

    private fun save() {
        val loaded = source ?: return
        val now = current.value
        val passage = passages.getOrNull(now.index) ?: return
        val fraction = now.index.toFloat() / passages.size
        val progress = loaded.progressAt(passage, fraction)
        app.scope.launch { app.library.saveListened(now.work, progress) }
    }

    override fun stop() {
        pending = false
        generation++
        if (current.value.active) save()
        casting?.cancel()
        voicing?.close()
        voicing = null
        output?.close()
        output = null
        release()
    }
}
