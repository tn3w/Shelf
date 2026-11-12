package dev.tn3w.shelf

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.session.PlaybackState.STATE_PAUSED
import android.media.session.PlaybackState.STATE_PLAYING
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val CHANNEL = "narration"
private const val NOTIFICATION = 2
private const val TOGGLE = "dev.tn3w.shelf.narration.TOGGLE"
private const val PREVIOUS = "dev.tn3w.shelf.narration.PREVIOUS"
private const val NEXT = "dev.tn3w.shelf.narration.NEXT"
private const val ICON_PREVIOUS = android.R.drawable.ic_media_previous
private const val ICON_NEXT = android.R.drawable.ic_media_next
private const val ICON_CLOSE = android.R.drawable.ic_menu_close_clear_cancel
private const val CLOSE = "dev.tn3w.shelf.narration.CLOSE"

class NarrationService : Service() {
    private val narrator get() = (application as ShelfApp).playback
    private lateinit var session: MediaSession
    private var watching: Job? = null
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = narrator.pause()
    }

    override fun onBind(intent: Intent?) = null

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
                },
            )
            isActive = true
        }
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
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(narrator.state.value),
            FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        watching?.cancel()
        watching = (application as ShelfApp).scope.launch {
            narrator.state.collect { update(it) }
        }
        return START_NOT_STICKY
    }

    private fun update(state: Narration) {
        if (!state.active && !state.loading) return stopSelf()
        session.setPlaybackState(playback(state))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(state))
    }

    private fun playback(state: Narration): PlaybackState {
        val mode = if (state.playing) STATE_PLAYING else STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(mode, state.index.toLong(), 0f)
            .build()
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
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = PendingIntent.getActivity(this, 0, launch, FLAG_IMMUTABLE)
        val toggle = if (state.playing) {
            action(android.R.drawable.ic_media_pause, R.string.pause, TOGGLE)
        } else {
            action(android.R.drawable.ic_media_play, R.string.play, TOGGLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title)
            .setContentText(state.chapter)
            .setContentIntent(open)
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
