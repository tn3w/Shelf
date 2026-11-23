package dev.tn3w.shelf

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.core.net.toUri
import dev.tn3w.shelf.data.Audiobook
import dev.tn3w.shelf.data.Level
import dev.tn3w.shelf.data.Sleep
import dev.tn3w.shelf.data.StreamCache
import dev.tn3w.shelf.data.fadeVolume
import dev.tn3w.shelf.data.heardSeconds
import dev.tn3w.shelf.data.httpChunks
import dev.tn3w.shelf.data.loudness
import dev.tn3w.shelf.data.pagesHeard
import dev.tn3w.shelf.data.secondsOfPages
import dev.tn3w.shelf.data.silences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SKIP_SECONDS = 10
private const val RESTART_SECONDS = 3
private const val SAVE_EVERY_SECONDS = 15
private const val POSITION_MILLIS = 1000L
private const val SILENCE_CHECK_MILLIS = 100L
private const val BOOST_MILLIBELS = 800
private const val CODEC_TIMEOUT_MICROS = 10_000L
private const val SCAN_LEAD_MILLIS = 10 * 60 * 1000L
private const val SCAN_WAIT_MILLIS = 1000L
private const val STREAM_CACHE = "streamed-audio"

class AudiobookPlayer(app: ShelfApp) : Playback(app) {
    private var book = Audiobook(emptyList())
    private var player: MediaPlayer? = null
    private var ready = false
    private var ticker: Job? = null
    private var loading: Job? = null
    private var heard = 0
    private var enhancer: LoudnessEnhancer? = null
    private var silences = mutableListOf<LongRange>()
    private var scanning: Job? = null
    private var stream: StreamCache? = null
    private val streamFile get() = app.cacheDir.resolve(STREAM_CACHE)

    suspend fun open(work: Int, title: String): Boolean {
        val audiobook = app.library.audiobooks.first()[work] ?: return false
        val now = current.value
        if (now.work == work && now.active && !now.failed) return true
        val settings = app.library.settings.first()
        withContext(Dispatchers.Main) {
            stop()
            app.stopOthers(this@AudiobookPlayer)
            book = audiobook
            current.value = Narration(
                work,
                title,
                count = book.seconds + 1,
                loading = true,
                rate = settings.speechRate,
                skipSilence = settings.skipSilence,
                boost = settings.volumeBoost,
            )
            load(book.track, book.millis)
        }
        return true
    }

    fun attach(work: Int, title: String, uris: List<Uri>) =
        app.scope.launch(Dispatchers.Main) {
            if (!app.library.attachAudio(work, uris)) {
                return@launch app.show(R.string.no_audio)
            }
            stop()
            if (open(work, title)) play()
        }

    fun detach(work: Int) {
        if (current.value.work == work) stop()
        app.scope.launch { app.library.detachAudio(work) }
    }

    private fun load(track: Int, millis: Long) {
        loading?.cancel()
        releasePlayer()
        book = book.copy(track = track, millis = millis)
        moved()
        val uri = book.tracks[track].uri
        loading = app.scope.launch(Dispatchers.Main) {
            val headers = app.catalogs.streamHeaders(uri)
            val cached = openStream(uri, headers)
            stream = cached
            player = MediaPlayer().apply {
                setAudioAttributes(SPEECH_ATTRIBUTES)
                setOnPreparedListener { prepared() }
                setOnCompletionListener { completed() }
                setOnErrorListener { _, _, _ -> failed() }
                val started = runCatching {
                    if (cached != null) setDataSource(cached.asMediaSource())
                    if (cached == null) setDataSource(app, uri.toUri(), headers)
                    prepareAsync()
                }
                if (started.isFailure) failed()
            }
            boost()
        }
    }

    private suspend fun openStream(uri: String, headers: Map<String, String>) =
        withContext(Dispatchers.IO) {
            if (!current.value.skipSilence || !uri.isStreamed()) return@withContext null
            streamFile.delete()
            runCatching { StreamCache(streamFile, httpChunks(uri, headers)) }.getOrNull()
        }

    private fun String.isStreamed() = startsWith("http://") || startsWith("https://")

    private fun prepared() {
        val media = player ?: return
        ready = true
        media.seekTo(book.millis.toInt())
        current.update { it.copy(loading = false) }
        scanSilences()
        if (current.value.playing) begin()
    }

    private fun failed(): Boolean {
        ready = false
        pause()
        current.update { it.copy(failed = true) }
        return true
    }

    private fun completed() {
        val next = book.track + 1
        if (next > book.tracks.lastIndex) {
            book = book.copy(millis = book.tracks.last().millis)
            return finish()
        }
        load(next, 0)
        if (current.value.sleep == Sleep.ChapterEnd) finish()
    }

    private fun begin() {
        val media = player ?: return
        media.start()
        media.playbackParams = media.playbackParams.setSpeed(current.value.rate)
        ticker?.cancel()
        ticker = app.scope.launch(Dispatchers.Main) {
            launch { skipSilences() }
            tick()
        }
    }

    private suspend fun skipSilences() {
        while (true) {
            delay(SILENCE_CHECK_MILLIS)
            val position = position()
            val silence = silences.firstOrNull { position in it } ?: continue
            player?.seekTo(silence.last, MediaPlayer.SEEK_CLOSEST)
        }
    }

    private fun scanSilences() {
        scanning?.cancel()
        val found = mutableListOf<LongRange>()
        silences = found
        if (!current.value.skipSilence || !ready) return
        val uri = book.tracks[book.track].uri
        val cached = stream
        if (cached == null && uri.isStreamed()) return
        val from = book.millis
        scanning = app.scope.launch(Dispatchers.IO) {
            val headers = app.catalogs.streamHeaders(uri)
            val active = { isActive }
            runCatching {
                val levels = decodedLevels(uri.toUri(), headers, cached, from, active)
                    .onEach { waitForPlayback(it.millis, active) }
                silences(levels).forEach { withContext(Dispatchers.Main) { found += it } }
            }
        }
    }

    private fun waitForPlayback(millis: Long, active: () -> Boolean) {
        while (active() && millis > book.millis + SCAN_LEAD_MILLIS) {
            Thread.sleep(SCAN_WAIT_MILLIS)
        }
    }

    private fun decodedLevels(
        uri: Uri,
        headers: Map<String, String>,
        cached: StreamCache?,
        fromMillis: Long,
        active: () -> Boolean,
    ) = sequence {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (cached != null) extractor.setDataSource(cached.asMediaSource())
            if (cached == null) extractor.setDataSource(app, uri, headers)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).mime.startsWith("audio/")
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromMillis * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            while (active()) {
                feed(extractor, decoder)
                val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_MICROS)
                if (index < 0) continue
                val pcm = decoder.getOutputBuffer(index)!!
                val level = Level(info.presentationTimeUs / 1000, loudness(pcm))
                decoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                yield(level)
            }
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    private fun StreamCache.asMediaSource() = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) =
            this@asMediaSource.readAt(position, buffer, offset, size)

        override fun getSize() = this@asMediaSource.size

        override fun close() = Unit
    }

    private val MediaFormat.mime get() = getString(MediaFormat.KEY_MIME).orEmpty()

    private fun feed(extractor: MediaExtractor, codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
        if (index < 0) return
        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
    }

    private fun boost() {
        enhancer?.release()
        enhancer = null
        val media = player ?: return
        if (!current.value.boost) return
        enhancer = runCatching {
            LoudnessEnhancer(media.audioSessionId).apply {
                setTargetGain(BOOST_MILLIBELS)
                enabled = true
            }
        }.getOrNull()
    }

    fun setSkipSilence(enabled: Boolean) {
        current.update { it.copy(skipSilence = enabled) }
        val streamed = book.tracks.getOrNull(book.track)?.uri?.isStreamed() == true
        if (enabled && streamed && stream == null && player != null) {
            load(book.track, position())
        } else {
            scanSilences()
        }
        app.scope.launch { app.library.updateSettings { it.copy(skipSilence = enabled) } }
    }

    fun setBoost(enabled: Boolean) {
        current.update { it.copy(boost = enabled) }
        boost()
        app.scope.launch { app.library.updateSettings { it.copy(volumeBoost = enabled) } }
    }

    private fun releasePlayer() {
        scanning?.cancel()
        silences = mutableListOf()
        enhancer?.release()
        enhancer = null
        player?.release()
        player = null
        ready = false
        stream?.close()
        stream = null
        streamFile.delete()
    }

    private suspend fun tick() {
        var seconds = 0
        while (true) {
            delay(POSITION_MILLIS)
            val chapter = book.chapterAt(book.second)
            val before = book.second
            book = book.copy(millis = position())
            moved()
            countHeard(heardSeconds(before, book.second))
            val ended = book.chapterAt(book.second) != chapter
            if (ended && current.value.sleep == Sleep.ChapterEnd) return finish()
            val sleeping = current.value.sleep is Sleep.After
            val volume = if (sleeping) fadeVolume(sleepLeftMillis) else 1f
            player?.setVolume(volume, volume)
            if (++seconds % SAVE_EVERY_SECONDS == 0) save()
        }
    }

    private fun countHeard(seconds: Int) {
        heard += seconds
        val pages = pagesHeard(heard)
        if (pages == 0) return
        heard -= secondsOfPages(pages)
        app.scope.launch { app.library.addPagesRead(pages) }
    }

    private fun position() =
        player?.takeIf { ready }?.currentPosition?.toLong() ?: book.millis

    private fun moved() {
        val second = book.second
        val rate = current.value.rate
        val chapter = book.chapters.getOrNull(book.chapterAt(second))?.second.orEmpty()
        current.update {
            it.copy(
                index = second,
                chapter = chapter,
                secondsLeft = ((book.seconds - second) / rate).toLong(),
            )
        }
    }

    override fun play() {
        if (book.tracks.isEmpty() || current.value.failed) return
        startPlaying()
        if (ready) begin()
    }

    override fun pause() {
        ticker?.cancel()
        if (ready && player?.isPlaying == true) player?.pause()
        book = book.copy(millis = position())
        moved()
        stopPlaying()
        save()
    }

    override fun seek(index: Int) {
        val target = book.at(index.coerceIn(0, book.seconds))
        if (target.track != book.track || !ready) {
            load(target.track, target.millis)
        } else {
            book = target
            player?.seekTo(target.millis.toInt())
            moved()
            scanSilences()
        }
        if (!current.value.playing) save()
    }

    override fun skip(delta: Int) = seek(current.value.index + delta * SKIP_SECONDS)

    override fun skipChapter(delta: Int) {
        val index = current.value.index
        val starts = book.chapters.map { it.first }
        val target = if (delta > 0) {
            starts.firstOrNull { it > index } ?: return
        } else {
            starts.lastOrNull { it < index - RESTART_SECONDS } ?: 0
        }
        seek(target)
    }

    override fun setRate(rate: Float) {
        current.update { it.copy(rate = rate) }
        val media = player
        if (ready && media != null && media.isPlaying) {
            media.playbackParams = media.playbackParams.setSpeed(rate)
        }
        moved()
        app.scope.launch { app.library.updateSettings { it.copy(speechRate = rate) } }
    }

    private fun save() {
        val work = current.value.work
        val (track, millis) = book.track to book.millis
        if (book.tracks.isEmpty()) return
        app.scope.launch { app.library.saveAudioPosition(work, track, millis) }
    }

    override fun stop() {
        ticker?.cancel()
        loading?.cancel()
        if (current.value.active) {
            book = book.copy(millis = position())
            save()
        }
        releasePlayer()
        book = Audiobook(emptyList())
        release()
    }
}
