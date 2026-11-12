package dev.tn3w.shelf

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.tn3w.shelf.data.Sleep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TICK_MILLIS = 1000L
private const val IDLE_MILLIS = 10 * 60 * 1000L

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
    val natural: Boolean = false,
    val characterVoices: Boolean = false,
    val canVoiceCharacters: Boolean = false,
    val preparing: Boolean = false,
    val speakers: Int = 0,
    val rate: Float = 1f,
    val voice: String = "",
    val voices: List<SpeechVoice> = emptyList(),
    val sleep: Sleep = Sleep.Off,
    val sleepLeftSeconds: Long = 0,
    val secondsLeft: Long = 0,
) {
    val active get() = count > 0
}

val SPEECH_ATTRIBUTES = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

abstract class Playback(protected val app: ShelfApp) {
    protected val current = MutableStateFlow(Narration())
    val state = current.asStateFlow()
    private var sleepEndsAt = 0L
    private var sleepJob: Job? = null
    private var idleJob: Job? = null
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        .setOnAudioFocusChangeListener { if (it < 0) pause() }
        .build()

    protected val sleepLeftMillis get() = sleepEndsAt - SystemClock.elapsedRealtime()

    abstract fun play()

    abstract fun pause()

    abstract fun seek(index: Int)

    abstract fun skip(delta: Int)

    abstract fun skipChapter(delta: Int)

    abstract fun setRate(rate: Float)

    abstract fun stop()

    fun toggle() = if (current.value.playing) pause() else play()

    open fun setSleep(sleep: Sleep) {
        sleepJob?.cancel()
        current.update { it.copy(sleep = sleep, sleepLeftSeconds = 0) }
        val minutes = (sleep as? Sleep.After)?.minutes ?: return
        sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepJob = app.scope.launch(Dispatchers.Main) { countDown() }
    }

    private suspend fun countDown() {
        while (true) {
            val left = sleepLeftMillis
            if (left <= 0) return finish()
            current.update { it.copy(sleepLeftSeconds = (left + 999) / 1000) }
            delay(TICK_MILLIS)
        }
    }

    protected fun finish() {
        pause()
        setSleep(Sleep.Off)
    }

    protected fun startPlaying() {
        idleJob?.cancel()
        audio.requestAudioFocus(focus)
        val service = Intent(app, NarrationService::class.java)
        ContextCompat.startForegroundService(app, service)
        current.update { it.copy(playing = true) }
    }

    protected fun stopPlaying() {
        audio.abandonAudioFocusRequest(focus)
        current.update { it.copy(playing = false) }
        idleJob?.cancel()
        idleJob = app.scope.launch(Dispatchers.Main) {
            delay(IDLE_MILLIS)
            stop()
        }
    }

    protected fun release() {
        sleepJob?.cancel()
        idleJob?.cancel()
        audio.abandonAudioFocusRequest(focus)
        current.value = Narration()
        app.stopService(Intent(app, NarrationService::class.java))
    }
}
