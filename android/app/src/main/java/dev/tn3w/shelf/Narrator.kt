package dev.tn3w.shelf

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import dev.tn3w.shelf.data.Chapter
import dev.tn3w.shelf.data.Passage
import dev.tn3w.shelf.data.Progress
import dev.tn3w.shelf.data.Sleep
import dev.tn3w.shelf.data.chapterStarts
import dev.tn3w.shelf.data.fadeVolume
import dev.tn3w.shelf.data.languageOf
import dev.tn3w.shelf.data.secondsLeft
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

private const val AHEAD = 3
private const val SAVE_EVERY = 20
private const val TICK_MILLIS = 1000L
private const val IDLE_MILLIS = 10 * 60 * 1000L

data class SpeechVoice(val name: String, val label: String)

data class Narration(
    val work: Int = 0,
    val title: String = "",
    val chapter: String = "",
    val text: String = "",
    val section: Int = 0,
    val offset: Int = 0,
    val index: Int = 0,
    val count: Int = 0,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val missingVoice: Boolean = false,
    val rate: Float = 1f,
    val voice: String = "",
    val voices: List<SpeechVoice> = emptyList(),
    val sleep: Sleep = Sleep.Off,
    val sleepLeftSeconds: Long = 0,
    val secondsLeft: Long = 0,
) {
    val active get() = count > 0
}

class Source(
    val passages: List<Passage>,
    val chapters: List<Chapter>,
    val progress: Progress,
    val progressAt: (Passage, Float) -> Progress,
)

class Narrator(private val app: ShelfApp) {
    private val _state = MutableStateFlow(Narration())
    val state = _state.asStateFlow()
    private var source: Source? = null
    private var passages = emptyList<Passage>()
    private var chapters = emptyList<Pair<Chapter, Int>>()
    private var engine: TextToSpeech? = null
    private var ready = false

    @Volatile private var generation = 0
    private var anchor = 0
    private var sleepEndsAt = 0L
    private var sleepJob: Job? = null
    private var idleJob: Job? = null
    private var pending = false
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { if (it < 0) pause() }
        .build()

    fun open(work: Int, title: String, loaded: Source) {
        val current = _state.value
        if (current.work == work && (current.active || current.loading)) return
        stop()
        source = loaded
        passages = loaded.passages
        chapters = chapterStarts(passages, loaded.chapters)
        val start = passages.indexOfFirst { isAtOrAfter(it, loaded.progress) }
        _state.value = Narration(work = work, title = title, loading = true)
        app.scope.launch {
            val settings = app.library.settings.first()
            _state.update {
                it.copy(rate = settings.speechRate, voice = settings.speechVoice)
            }
            create(start.coerceAtLeast(0))
        }
    }

    private fun isAtOrAfter(passage: Passage, progress: Progress) =
        passage.section > progress.section ||
            (passage.section == progress.section && passage.offset >= progress.offset)

    private fun create(start: Int) {
        engine = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                _state.update { it.copy(loading = false, failed = true) }
                return@TextToSpeech
            }
            configure()
            ready = true
            moveTo(start)
            _state.update { it.copy(loading = false, count = passages.size) }
            if (pending) play()
        }
    }

    private fun configure() {
        val tts = engine ?: return
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(listener)
        val tag = languageOf(passages) ?: Locale.getDefault().language
        val locale = Locale.forLanguageTag(tag)
        val result = tts.setLanguage(locale)
        val missing = result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        val voices = tts.voices.orEmpty().filter { it.locale.language == locale.language }
        val chosen = voices.firstOrNull { it.name == _state.value.voice }
        chosen?.let { tts.voice = it }
        tts.setSpeechRate(_state.value.rate)
        _state.update { it.copy(missingVoice = missing, voices = labelled(voices)) }
    }

    private fun labelled(voices: List<Voice>) = voices
        .sortedWith(
            compareBy({
                it.isNetworkConnectionRequired
            }, { -it.quality }, { it.name }),
        )
        .mapIndexed { index, voice ->
            val place = voice.locale.displayCountry.ifBlank {
                voice.locale.displayLanguage
            }
            SpeechVoice(voice.name, "${index + 1} · $place")
        }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val (owner, index) = parse(utteranceId) ?: return
            if (owner == generation) app.scope.launch { started(index) }
        }

        override fun onDone(utteranceId: String) {
            val (owner, index) = parse(utteranceId) ?: return
            if (owner == generation) app.scope.launch { finished(index) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onDone(utteranceId)
    }

    private fun parse(id: String) = id.split(':').takeIf { it.size == 2 }
        ?.let { it[0].toInt() to it[1].toInt() }

    private fun started(index: Int) {
        moveTo(index)
        enqueue(index + AHEAD)
        if (index % SAVE_EVERY == 0) save()
    }

    private fun finished(index: Int) {
        if (index + 1 >= passages.size) return finish(passages.lastIndex)
        if (_state.value.sleep == Sleep.ChapterEnd && atChapterStart(index + 1, true)) {
            finish(index + 1)
        }
    }

    private fun finish(next: Int) {
        moveTo(next)
        pause()
        setSleep(Sleep.Off)
    }

    private fun atChapterStart(index: Int, past: Boolean) =
        chapters.any { it.second == index } && (!past || index > anchor)

    private fun moveTo(index: Int) {
        val passage = passages.getOrNull(index) ?: return
        val chapter = chapters.lastOrNull { it.second <= index }?.first?.title.orEmpty()
        val rate = _state.value.rate
        _state.update {
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

    private fun enqueue(index: Int, flush: Boolean = false) {
        val tts = engine ?: return
        if (index >= passages.size) return
        val capped = _state.value.sleep == Sleep.ChapterEnd && atChapterStart(index, true)
        if (capped) return
        val left = sleepEndsAt - SystemClock.elapsedRealtime()
        val volume = if (_state.value.sleep is Sleep.After) fadeVolume(left) else 1f
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(passages[index].text, mode, params, "$generation:$index")
    }

    fun play() {
        if (!ready) {
            pending = true
            return
        }
        pending = false
        idleJob?.cancel()
        audio.requestAudioFocus(focus)
        val service = Intent(app, NarrationService::class.java)
        ContextCompat.startForegroundService(app, service)
        _state.update { it.copy(playing = true) }
        speakFrom(_state.value.index)
    }

    fun pause() {
        pending = false
        generation++
        engine?.stop()
        audio.abandonAudioFocusRequest(focus)
        _state.update { it.copy(playing = false) }
        save()
        idleJob?.cancel()
        idleJob = app.scope.launch {
            delay(IDLE_MILLIS)
            stop()
        }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    private fun speakFrom(index: Int) {
        generation++
        anchor = index
        for (offset in 0 until AHEAD) enqueue(index + offset, flush = offset == 0)
    }

    fun seek(index: Int) {
        val target = index.coerceIn(0, passages.lastIndex.coerceAtLeast(0))
        moveTo(target)
        if (_state.value.playing) speakFrom(target) else save()
    }

    fun skip(delta: Int) = seek(_state.value.index + delta)

    fun skipChapter(delta: Int) {
        val index = _state.value.index
        val starts = chapters.map { it.second }
        val target = if (delta > 0) {
            starts.firstOrNull { it > index }
        } else {
            starts.lastOrNull { it < index - 1 } ?: 0
        }
        seek(target ?: passages.lastIndex)
    }

    fun setRate(rate: Float) {
        engine?.setSpeechRate(rate)
        _state.update { it.copy(rate = rate) }
        moveTo(_state.value.index)
        app.scope.launch { app.library.updateSettings { it.copy(speechRate = rate) } }
        if (_state.value.playing) speakFrom(_state.value.index)
    }

    fun setVoice(name: String) {
        val voice = engine?.voices.orEmpty().firstOrNull { it.name == name } ?: return
        engine?.voice = voice
        _state.update { it.copy(voice = name) }
        app.scope.launch { app.library.updateSettings { it.copy(speechVoice = name) } }
        if (_state.value.playing) speakFrom(_state.value.index)
    }

    fun setSleep(sleep: Sleep) {
        sleepJob?.cancel()
        anchor = _state.value.index
        _state.update { it.copy(sleep = sleep, sleepLeftSeconds = 0) }
        val minutes = (sleep as? Sleep.After)?.minutes ?: return
        sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepJob = app.scope.launch { countDown() }
    }

    private suspend fun countDown() {
        while (true) {
            val left = sleepEndsAt - SystemClock.elapsedRealtime()
            if (left <= 0) return finish(_state.value.index)
            _state.update { it.copy(sleepLeftSeconds = (left + 999) / 1000) }
            delay(TICK_MILLIS)
        }
    }

    private fun save() {
        val loaded = source ?: return
        val now = _state.value
        val passage = passages.getOrNull(now.index) ?: return
        val fraction = now.index.toFloat() / passages.size
        app.scope.launch {
            app.library.saveProgress(now.work, loaded.progressAt(passage, fraction))
        }
    }

    fun stop() {
        pending = false
        generation++
        sleepJob?.cancel()
        idleJob?.cancel()
        audio.abandonAudioFocusRequest(focus)
        if (_state.value.active) save()
        engine?.shutdown()
        engine = null
        ready = false
        _state.value = Narration()
        app.stopService(Intent(app, NarrationService::class.java))
    }
}
