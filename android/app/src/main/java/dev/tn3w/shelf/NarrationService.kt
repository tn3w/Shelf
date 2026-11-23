package dev.tn3w.shelf

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.SearchManager.QUERY
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.JPEG
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaMetadata.METADATA_KEY_DURATION
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.session.PlaybackState.ACTION_FAST_FORWARD
import android.media.session.PlaybackState.ACTION_PAUSE
import android.media.session.PlaybackState.ACTION_PLAY
import android.media.session.PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
import android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH
import android.media.session.PlaybackState.ACTION_REWIND
import android.media.session.PlaybackState.ACTION_SEEK_TO
import android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
import android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
import android.media.session.PlaybackState.ACTION_STOP
import android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN
import android.media.session.PlaybackState.STATE_BUFFERING
import android.media.session.PlaybackState.STATE_ERROR
import android.media.session.PlaybackState.STATE_NONE
import android.media.session.PlaybackState.STATE_PAUSED
import android.media.session.PlaybackState.STATE_PLAYING
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
import android.service.media.MediaBrowserService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import dev.tn3w.shelf.data.Saved
import dev.tn3w.shelf.data.openDocument
import dev.tn3w.shelf.data.toBook
import dev.tn3w.shelf.widget.coverOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHANNEL = "narration"
private const val NOTIFICATION = 2
private const val TOGGLE = "dev.tn3w.shelf.narration.TOGGLE"
private const val PREVIOUS = "dev.tn3w.shelf.narration.PREVIOUS"
private const val NEXT = "dev.tn3w.shelf.narration.NEXT"
private const val ICON_PREVIOUS = android.R.drawable.ic_media_previous
private const val ICON_NEXT = android.R.drawable.ic_media_next
private const val ICON_CLOSE = android.R.drawable.ic_menu_close_clear_cancel
private const val CLOSE = "dev.tn3w.shelf.narration.CLOSE"
private const val ROOT = "root"
private const val BOOKS = "books"
private const val HIDDEN = "hidden"
private const val COVER_WIDTH = 256
private const val COVER_QUALITY = 85
private const val LISTED_BOOKS = 30
private const val IDLE_ACTIONS = ACTION_PLAY or ACTION_PLAY_FROM_MEDIA_ID or
    ACTION_PLAY_FROM_SEARCH
private const val PLAYER_ACTIONS = IDLE_ACTIONS or ACTION_PAUSE or ACTION_STOP or
    ACTION_SKIP_TO_NEXT or ACTION_SKIP_TO_PREVIOUS or ACTION_FAST_FORWARD or ACTION_REWIND
private val CAR_BROWSERS = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.carassistant",
    "com.android.car.media",
)

fun bestMatch(books: List<Saved>, query: String): Saved? {
    val wanted = query.trim()
    return books.firstOrNull { it.title.contains(wanted, ignoreCase = true) }
        ?: books.firstOrNull { it.author.contains(wanted, ignoreCase = true) }
}

fun sessionState(state: Narration) = when {
    !state.busy -> STATE_NONE
    state.loading -> STATE_BUFFERING
    state.playing -> STATE_PLAYING
    else -> STATE_PAUSED
}

fun sessionActions(state: Narration, timed: Boolean) = when {
    !state.busy -> IDLE_ACTIONS
    timed -> PLAYER_ACTIONS or ACTION_SEEK_TO
    else -> PLAYER_ACTIONS
}

class NarrationService : MediaBrowserService() {
    private val app get() = application as ShelfApp
    private val playback get() = app.playback
    private val scope = MainScope()
    private lateinit var session: MediaSession
    private val browsers = mutableSetOf<String>()
    private var art: Pair<Int, Bitmap?> = 0 to null
    private var artUri: Uri? = null
    private var shown: List<Any?> = emptyList()
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playback.pause()
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "shelf-narration").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = resume()

                    override fun onPause() = playback.pause()

                    override fun onSkipToNext() = playback.skipChapter(1)

                    override fun onSkipToPrevious() = playback.skipChapter(-1)

                    override fun onStop() = playback.stop()

                    override fun onFastForward() = playback.skip(1)

                    override fun onRewind() = playback.skip(-1)

                    override fun onSeekTo(position: Long) = seek(position)

                    override fun onPlayFromSearch(query: String?, extras: Bundle?) =
                        search(query)

                    override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                        mediaId.toIntOrNull()?.let(::listen)
                    }
                },
            )
            setSessionActivity(playerIntent())
            isActive = true
        }
        sessionToken = session.sessionToken
        val noisy = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(unplugged, noisy)
        scope.launch {
            combine(app.audiobooks.state, app.narrator.state) { _, _ -> playback }
                .collect { update(it) }
        }
        scope.launch {
            app.library.saved.map { books -> books.map(Saved::work) }
                .distinctUntilChanged()
                .drop(1)
                .collect { notifyChildrenChanged(BOOKS) }
        }
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> playback.toggle()
            PREVIOUS -> playback.skipChapter(-1)
            NEXT -> playback.skipChapter(1)
            CLOSE -> playback.stop()
            INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> search(intent.getStringExtra(QUERY))
        }
        val state = playback.state.value
        val cover = art.second.takeIf { art.first == state.work }
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(state, cover),
            FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    override fun onGetRoot(client: String, uid: Int, hints: Bundle?): BrowserRoot {
        val trusted = client == packageName || client in CAR_BROWSERS
        if (!trusted) return BrowserRoot(HIDDEN, null)
        browsers += client
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parent: String, result: Result<MutableList<MediaItem>>) {
        if (parent == ROOT) return result.sendResult(mutableListOf(folder()))
        if (parent != BOOKS) return result.sendResult(mutableListOf())
        result.detach()
        scope.launch {
            val items = withContext(Dispatchers.Default) { books() }
            result.sendResult(items.toMutableList())
        }
    }

    private fun folder(): MediaItem {
        val description = MediaDescription.Builder()
            .setMediaId(BOOKS)
            .setTitle(getString(R.string.library))
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private suspend fun listenable(): List<Saved> {
        val library = app.library
        val works = library.audiobooks.first().keys + library.progress.first().keys
        return library.saved.first().filter { it.work in works }
    }

    private suspend fun books() = coroutineScope {
        listenable().take(LISTED_BOOKS).map { async { book(it) } }.awaitAll()
    }

    private suspend fun book(saved: Saved): MediaItem {
        val description = MediaDescription.Builder()
            .setMediaId(saved.work.toString())
            .setTitle(saved.title)
            .setSubtitle(saved.author)
            .setIconUri(runCatching { coverUri(saved) }.getOrNull())
            .build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    private suspend fun cover(saved: Saved): Bitmap {
        val settings = app.library.settings.first()
        return coverOf(app, saved.toBook(), settings, COVER_WIDTH)
    }

    private suspend fun coverUri(saved: Saved) = share(saved.work, cover(saved))

    private suspend fun share(work: Int, bitmap: Bitmap): Uri {
        val file = cacheDir.resolve("shared/car/$work.jpg")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(JPEG, COVER_QUALITY, it) }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        browsers.forEach { grantUriPermission(it, uri, FLAG_GRANT_READ_URI_PERMISSION) }
        return uri
    }

    private suspend fun artOf(work: Int): Bitmap? {
        if (art.first == work) return art.second
        val saved = app.library.saved.first().find { it.work == work }
        val cover = saved?.let { runCatching { cover(it) }.getOrNull() }
        artUri = cover?.let { runCatching { share(work, it) }.getOrNull() }
        art = work to cover
        return cover
    }

    private fun seek(millis: Long) {
        if (playback is AudiobookPlayer) playback.seek((millis / 1000).toInt())
    }

    private fun resume() {
        val state = playback.state.value
        when {
            state.failed -> listen(state.work)
            state.busy -> playback.play()
            else -> search(null)
        }
    }

    private fun search(query: String?) {
        app.scope.launch(Dispatchers.Main) {
            val found = bestMatch(listenable(), query.orEmpty())
            if (found == null) return@launch fail(R.string.no_results)
            listen(found.work)
        }
    }

    private fun listen(work: Int) = app.scope.launch(Dispatchers.Main) {
        val saved = app.library.saved.first().find { it.work == work } ?: return@launch
        if (app.audiobooks.open(work, saved.title)) return@launch app.audiobooks.play()
        val source = withContext(Dispatchers.IO) { readAloud(work) }
        if (source == null) return@launch fail(R.string.no_text)
        app.narrator.open(work, saved.title, source)
        app.narrator.play()
    }

    private suspend fun readAloud(work: Int): Source? = runCatching {
        val progress = app.library.progressOf(work) ?: return null
        openDocument(app.library.bookFile(progress.file)).use { sourceOf(it, progress) }
    }.getOrNull()

    private fun failure(message: Int) = PlaybackState.Builder()
        .setActions(IDLE_ACTIONS)
        .setState(STATE_ERROR, PLAYBACK_POSITION_UNKNOWN, 0f)
        .setErrorMessage(getString(message))
        .build()

    private fun fail(message: Int) = session.setPlaybackState(failure(message))

    private suspend fun update(playback: Playback) {
        val state = playback.state.value
        val timed = playback is AudiobookPlayer
        session.setPlaybackState(playbackState(state, timed))
        if (!state.busy) {
            shown = emptyList()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            return stopSelf()
        }
        val cover = artOf(state.work)
        val showing = listOf(state.title, state.chapter, state.playing, cover)
        if (showing == shown) return
        shown = showing
        session.setMetadata(metadata(state, timed, cover))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(state, cover))
    }

    private fun metadata(state: Narration, timed: Boolean, cover: Bitmap?) =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapter)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artUri?.toString())
            .apply {
                if (timed) putLong(METADATA_KEY_DURATION, (state.count - 1) * 1000L)
            }
            .build()

    private fun playbackState(state: Narration, timed: Boolean): PlaybackState {
        val error = if (timed) R.string.no_audio else R.string.listen_failed
        if (state.failed) return failure(error)
        val mode = sessionState(state)
        val position = if (timed) state.index * 1000L else PLAYBACK_POSITION_UNKNOWN
        val speed = if (timed && mode == STATE_PLAYING) state.rate else 0f
        return PlaybackState.Builder()
            .setActions(sessionActions(state, timed))
            .setState(mode, position, speed)
            .build()
    }

    private fun playerIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, FLAG_IMMUTABLE)
    }

    private fun action(icon: Int, label: Int, name: String): Notification.Action {
        val intent = Intent(this, NarrationService::class.java).setAction(name)
        val pending = PendingIntent.getService(
            this, 0, intent,
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, icon), getString(label), pending,
        ).build()
    }

    private fun notification(state: Narration, cover: Bitmap? = null): Notification {
        val channel = NotificationChannelCompat
            .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.listen))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        val toggle = if (state.playing) {
            action(android.R.drawable.ic_media_pause, R.string.pause, TOGGLE)
        } else {
            action(android.R.drawable.ic_media_play, R.string.play, TOGGLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title)
            .setContentText(state.chapter)
            .setLargeIcon(cover)
            .setContentIntent(playerIntent())
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ICON_PREVIOUS, R.string.previous_chapter, PREVIOUS))
            .addAction(toggle)
            .addAction(action(ICON_NEXT, R.string.next_chapter, NEXT))
            .addAction(action(ICON_CLOSE, R.string.stop, CLOSE))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = playback.stop()

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(unplugged)
        session.release()
        super.onDestroy()
    }
}
