package dev.tn3w.shelf

import android.media.MediaPlayer
import android.net.Uri
import androidx.core.net.toUri
import dev.tn3w.shelf.data.Audiobook
import dev.tn3w.shelf.data.Sleep
import dev.tn3w.shelf.data.fadeVolume
import dev.tn3w.shelf.data.heardSeconds
import dev.tn3w.shelf.data.pagesHeard
import dev.tn3w.shelf.data.secondsOfPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SKIP_SECONDS = 10
private const val RESTART_SECONDS = 3
private const val SAVE_EVERY_SECONDS = 15
private const val POSITION_MILLIS = 1000L

class AudiobookPlayer(app: ShelfApp) : Playback(app) {
    private var book = Audiobook(emptyList())
    private var player: MediaPlayer? = null
    private var ready = false
    private var ticker: Job? = null
    private var heard = 0

    suspend fun open(work: Int, title: String): Boolean {
        val audiobook = app.library.audiobooks.first()[work] ?: return false
        val now = current.value
        if (now.work == work && now.active && !now.failed) return true
        val rate = app.library.settings.first().speechRate
        withContext(Dispatchers.Main) {
            stop()
            app.stopOthers(this@AudiobookPlayer)
            book = audiobook
            current.value = Narration(
                work,
                title,
                count = book.seconds + 1,
                loading = true,
                rate = rate,
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
        player?.release()
        ready = false
        book = book.copy(track = track, millis = millis)
        moved()
        player = MediaPlayer().apply {
            setAudioAttributes(SPEECH_ATTRIBUTES)
            setOnPreparedListener { prepared() }
            setOnCompletionListener { completed() }
            setOnErrorListener { _, _, _ -> failed() }
            val started = runCatching {
                setDataSource(app, book.tracks[track].uri.toUri())
                prepareAsync()
            }
            if (started.isFailure) failed()
        }
    }

    private fun prepared() {
        val media = player ?: return
        ready = true
        media.seekTo(book.millis.toInt())
        current.update { it.copy(loading = false) }
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
            reachedEnd()
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
        ticker = app.scope.launch(Dispatchers.Main) { tick() }
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
        if (current.value.active) {
            book = book.copy(millis = position())
            save()
        }
        player?.release()
        player = null
        ready = false
        book = Audiobook(emptyList())
        release()
    }
}
