package dev.tn3w.shelf

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val CHANNEL = "transfers"
private const val NOTIFICATION = 1

class TransferService : Service() {
    override fun onBind(intent: Intent?) = null

    @SuppressLint("InlinedApi", "ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(), FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val app = application as ShelfApp
        app.scope.launch {
            app.activeTransfers.first { it == 0 }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    private fun notification(): Notification {
        val channel = NotificationChannelCompat
            .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.transfers))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = PendingIntent.getActivity(this, 0, launch, FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.transfer_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: IllegalStateException) {
                Log.w("TransferService", "Not started in background", error)
            }
        }
    }
}
