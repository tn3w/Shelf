package dev.tn3w.shelf

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.tn3w.shelf.data.Age
import dev.tn3w.shelf.data.Cast
import dev.tn3w.shelf.data.Casting
import dev.tn3w.shelf.data.Character
import dev.tn3w.shelf.data.Gender
import dev.tn3w.shelf.data.POCKET_RATE
import dev.tn3w.shelf.data.PocketModel
import dev.tn3w.shelf.data.PocketText
import dev.tn3w.shelf.data.Spoken
import dev.tn3w.shelf.data.Voice
import dev.tn3w.shelf.data.castOf
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.speech.tts.Voice as SystemVoice

private const val BUFFER_SECONDS = 1
private const val POLL_MILLIS = 40L
private const val RETRY_MILLIS = 10L
private const val PARAGRAPH_PAUSE = 0.25f

data class SpeechVoice(val name: String, val label: String)

internal fun voiceLabel(id: String) =
    id.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

interface SpeechEvents {
    fun started(tag: Int, index: Int)

    fun done(tag: Int, index: Int)
}

interface SpeechOutput {
    val voices: List<SpeechVoice>

    val narrator: String

    fun speak(tag: Int, index: Int, segments: List<Spoken>, flush: Boolean, volume: Float)

    fun stop()

    fun setRate(rate: Float)

    fun setNarrator(name: String)

    fun recast(characters: List<Character>)

    fun close()
}

private class Caster(private val pool: List<Voice>, narrator: Voice) {
    var narrator = narrator
        private set
    private var characters = emptyList<Character>()

    @Volatile private var cast = Cast(Casting(narrator), emptyMap())

    fun recast(updated: List<Character>, voice: Voice = narrator) {
        if (updated == characters && voice == narrator) return
        characters = updated
        narrator = voice
        cast = castOf(updated, pool, voice)
    }

    fun narrate(voice: Voice) = recast(characters, voice)

    fun castingOf(speaker: String?): Casting {
        if (speaker == null) return cast.narrator
        val entry = cast.speakers.entries.firstOrNull { it.key.name == speaker }
        return entry?.value ?: cast.narrator
    }
}

class TtsOutput(
    private val engine: TextToSpeech,
    private val events: SpeechEvents,
    available: List<SystemVoice>,
    chosen: String,
) : SpeechOutput {
    private val system = available.sortedWith(
        compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }),
    )
    private val pool = system.map { Voice(it.name, Gender.Unknown, Age.Adult) }
    private val caster = Caster(
        pool,
        pool.firstOrNull { it.id == chosen }
            ?: pool.firstOrNull { it.id == engine.voice?.name }
            ?: Voice(engine.voice?.name.orEmpty(), Gender.Unknown, Age.Adult),
    )

    override val voices = system.mapIndexed { index, voice ->
        val place = voice.locale.displayCountry.ifBlank { voice.locale.displayLanguage }
        SpeechVoice(voice.name, "${index + 1} · $place")
    }

    override val narrator get() = caster.narrator.id

    init {
        engine.setAudioAttributes(SPEECH_ATTRIBUTES)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    val parts = utteranceId.split(':').map(String::toInt)
                    if (parts[2] == 0) events.started(parts[0], parts[1])
                }

                override fun onDone(utteranceId: String) {
                    val parts = utteranceId.split(':').map(String::toInt)
                    if (parts[2] == parts[3] - 1) events.done(parts[0], parts[1])
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = onDone(utteranceId)
            },
        )
    }

    override fun speak(
        tag: Int,
        index: Int,
        segments: List<Spoken>,
        flush: Boolean,
        volume: Float,
    ) {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        segments.forEachIndexed { part, segment ->
            val casting = caster.castingOf(segment.speaker)
            system.firstOrNull { it.name == casting.voice.id }?.let { engine.voice = it }
            engine.setPitch(casting.pitch)
            val mode = if (flush && part == 0) {
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            engine.speak(segment.text, mode, params, "$tag:$index:$part:${segments.size}")
        }
    }

    override fun stop() {
        engine.stop()
    }

    override fun setRate(rate: Float) {
        engine.setSpeechRate(rate)
    }

    override fun setNarrator(name: String) {
        pool.firstOrNull { it.id == name }?.let(caster::narrate)
    }

    override fun recast(characters: List<Character>) = caster.recast(characters)

    override fun close() {
        engine.shutdown()
    }
}

private class Request(
    val tag: Int,
    val index: Int,
    val segments: List<Spoken>,
    val volume: Float,
)

private class Marker(val frame: Long, val tag: Int, val index: Int, val start: Boolean)

class PocketOutput(
    private val model: PocketModel,
    private val text: PocketText,
    private val events: SpeechEvents,
    private val pool: List<Voice>,
    chosen: String,
) : SpeechOutput {
    private val caster = Caster(
        pool,
        pool.firstOrNull { it.id == chosen }
            ?: pool.firstOrNull { it.gender == Gender.Male && it.age == Age.Adult }
            ?: pool.first(),
    )
    private val queue = LinkedBlockingQueue<Request>()
    private val markers = ArrayDeque<Marker>()
    private val track = AudioTrack.Builder()
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(POCKET_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(POCKET_RATE * 4 * BUFFER_SECONDS)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    @Volatile private var tag = 0

    @Volatile private var closed = false
    private var written = 0L
    private var base = 0L
    private var seed = 1L
    private val worker = thread(name = "pocket") { loop() }

    override val voices = pool.map { SpeechVoice(it.id, voiceLabel(it.id)) }

    override val narrator get() = caster.narrator.id

    override fun speak(
        tag: Int,
        index: Int,
        segments: List<Spoken>,
        flush: Boolean,
        volume: Float,
    ) {
        if (flush) stop()
        this.tag = tag
        queue += Request(tag, index, segments, volume)
    }

    override fun stop() {
        queue.clear()
        tag = -1
        synchronized(track) {
            track.pause()
            track.flush()
            markers.clear()
            written = 0
            base = head()
        }
    }

    override fun setRate(rate: Float) {
        synchronized(track) { track.playbackParams = PlaybackParams().setSpeed(rate) }
    }

    override fun setNarrator(name: String) {
        pool.firstOrNull { it.id == name }?.let(caster::narrate)
    }

    override fun recast(characters: List<Character>) = caster.recast(characters)

    override fun close() {
        closed = true
        stop()
        worker.join()
        track.release()
        model.close()
    }

    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        while (!closed) {
            val request = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
            fire()
            if (request != null && request.tag == tag) render(request)
        }
    }

    private fun render(request: Request) {
        mark(request, start = true)
        track.setVolume(request.volume)
        for (segment in request.segments) {
            val voice = caster.castingOf(segment.speaker).voice.id
            for (chunk in text.chunks(segment.text)) {
                if (request.tag != tag || !model.start(voice, chunk, seed++)) return
                while (request.tag == tag) write(model.next() ?: break, request.tag)
            }
        }
        write(FloatArray((POCKET_RATE * PARAGRAPH_PAUSE).toInt()), request.tag)
        mark(request, start = false)
    }

    private fun mark(request: Request, start: Boolean) = synchronized(track) {
        if (request.tag == tag) {
            markers.addLast(Marker(written, request.tag, request.index, start))
        }
    }

    private fun write(samples: FloatArray, owner: Int) {
        var offset = 0
        while (offset < samples.size && owner == tag) {
            val count = synchronized(track) {
                if (owner != tag) return
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                val mode = AudioTrack.WRITE_NON_BLOCKING
                track.write(samples, offset, samples.size - offset, mode)
                    .also { if (it > 0) written += it }
            }
            if (count < 0) return
            offset += count
            fire()
            if (offset < samples.size) Thread.sleep(RETRY_MILLIS)
        }
    }

    private fun head() = track.playbackHeadPosition.toLong() and 0xffffffffL

    private fun fire() {
        val reached = synchronized(track) {
            val head = head() - base
            val due = mutableListOf<Marker>()
            while (markers.firstOrNull()?.let { it.frame <= head } == true) {
                due += markers.removeFirst()
            }
            due
        }
        for (marker in reached) {
            if (marker.start) {
                events.started(marker.tag, marker.index)
            } else {
                events.done(marker.tag, marker.index)
            }
        }
    }
}
