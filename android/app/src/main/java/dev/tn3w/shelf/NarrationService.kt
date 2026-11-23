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
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.session.PlaybackState.STATE_PAUSED
import android.media.session.PlaybackState.STATE_PLAYING
import android.os.Bundle
import android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
import android.service.media.MediaBrowserService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.tn3w.shelf.data.Saved
import dev.tn3w.shelf.data.openDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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
private const val ROOT = "books"
private const val HIDDEN = "hidden"
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

class NarrationService : MediaBrowserService() {
    private val app get() = application as ShelfApp
    private val narrator get() = app.playback
    private lateinit var session: MediaSession
    private var watching: Job? = null
    private val idle = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
        .build()
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = narrator.pause()
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "shelf-narration").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = narrator.play()

                    override fun onPause() = narrator.pause()

                    override fun onSkipToNext() = narrator.skipChapter(1)

                    override fun onSkipToPrevious() = narrator.skipChapter(-1)

                    override fun onStop() = narrator.stop()

                    override fun onFastForward() = narrator.skip(1)

                    override fun onRewind() = narrator.skip(-1)

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
        session.setPlaybackState(idle)
        sessionToken = session.sessionToken
        val noisy = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(unplugged, noisy)
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> narrator.toggle()
            PREVIOUS -> narrator.skipChapter(-1)
            NEXT -> narrator.skipChapter(1)
            CLOSE -> narrator.stop()
            INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> search(intent.getStringExtra(QUERY))
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(narrator.state.value),
            FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        watching?.cancel()
        watching = app.scope.launch {
            narrator.state.collect { update(it) }
        }
        return START_NOT_STICKY
    }

    override fun onGetRoot(client: String, uid: Int, hints: Bundle?): BrowserRoot {
        val browses = client == packageName || client in CAR_BROWSERS
        return BrowserRoot(if (browses) ROOT else HIDDEN, null)
    }

    override fun onLoadChildren(parent: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        app.scope.launch {
            val items = if (parent == ROOT) books() else emptyList()
            result.sendResult(items.toMutableList())
        }
    }

    private suspend fun listenable(): List<Saved> {
        val library = app.library
        val works = library.audiobooks.first().keys + library.progress.first().keys
        return library.saved.first().filter { it.work in works }
    }

    private suspend fun books() = listenable().map {
        val description = MediaDescription.Builder()
            .setMediaId(it.work.toString())
            .setTitle(it.title)
            .setSubtitle(it.author)
            .build()
        MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    private fun search(query: String?) {
        app.scope.launch {
            val found = bestMatch(listenable(), query.orEmpty())
            if (found == null) return@launch fail(R.string.no_results)
            listen(found.work)
        }
    }

    private fun listen(work: Int) = app.scope.launch {
        val saved = app.library.saved.first().find { it.work == work } ?: return@launch
        if (app.audiobooks.open(work, saved.title)) {
            return@launch withContext(Dispatchers.Main) { app.audiobooks.play() }
        }
        val source = withContext(Dispatchers.IO) { readAloud(work) }
        withContext(Dispatchers.Main) {
            if (source == null) return@withContext fail(R.string.no_text)
            app.narrator.open(work, saved.title, source)
            app.narrator.play()
        }
    }

    private suspend fun readAloud(work: Int): Source? = runCatching {
        val progress = app.library.progressOf(work) ?: return null
        openDocument(app.library.bookFile(progress.file)).use { sourceOf(it, progress) }
    }.getOrNull()

    private fun fail(message: Int) = session.setPlaybackState(
        PlaybackState.Builder()
            .setState(PlaybackState.STATE_ERROR, 0, 0f)
            .setErrorMessage(getString(message))
            .build(),
    )

    private fun update(state: Narration) {
        if (!state.busy) {
            session.setPlaybackState(idle)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return stopSelf()
        }
        session.setPlaybackState(playback(state))
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapter)
                .build(),
        )
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(state))
    }

    private fun playback(state: Narration): PlaybackState {
        val mode = if (state.playing) STATE_PLAYING else STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(mode, state.index.toLong(), 0f)
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

    private fun notification(state: Narration): Notification {
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

    override fun onTimeout(startId: Int, fgsType: Int) = narrator.stop()

    override fun onDestroy() {
        watching?.cancel()
        unregisterReceiver(unplugged)
        session.release()
        super.onDestroy()
    }
}
