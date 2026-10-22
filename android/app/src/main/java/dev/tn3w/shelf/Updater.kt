package dev.tn3w.shelf

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import java.io.IOException

private const val APK_NAME = "shelf-update.apk"

private val STORE_INSTALLERS = setOf(
    "org.fdroid.fdroid",
    "org.fdroid.basic",
    "com.looker.droidify",
    "com.machiav3lli.fdroid",
)

fun isStoreInstaller(installer: String?) = installer in STORE_INSTALLERS

private fun installerOf(context: Context): String? {
    val manager = context.packageManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        manager.getInstallerPackageName(context.packageName)
    }
}

private fun isNewer(version: String) =
    releaseOrder.compare(version, BuildConfig.VERSION_NAME) > 0

class ReleaseChanged : IOException("release changed or withdrawn")

object Updater {
    fun supported(context: Context) =
        BuildConfig.UPDATER && !isStoreInstaller(installerOf(context))

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val release = json.decodeFromString<Release>(fetchText(LATEST_RELEASE))
        val names = release.assets.map { it.name }
        val usable = release.tag.startsWith("v") && APK_NAME in names
        release.takeIf { usable && isNewer(it.version) }
    }

    fun isAvailable(release: Release) = isNewer(release.version)

    suspend fun check(app: ShelfApp) = latest().also { found ->
        app.library.updateSettings { it.copy(availableUpdate = found) }
    }

    private suspend fun stillPublished(release: Release): Boolean {
        val url = "https://api.github.com/repos/$REPOSITORY/releases/tags/${release.tag}"
        val current = try {
            json.decodeFromString<Release>(fetchText(url))
        } catch (error: HttpException) {
            if (error.code != 404) throw error
            null
        }
        return release.isUnchanged(current, setOf(APK_NAME))
    }

    suspend fun install(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as ShelfApp
        if (!stillPublished(release)) {
            runSuspendCatching { check(app) }
            throw ReleaseChanged()
        }
        val installer = context.packageManager.packageInstaller
        val sessionId =
            installer.createSession(SessionParams(SessionParams.MODE_FULL_INSTALL))
        installer.openSession(sessionId).use { session ->
            try {
                write(session, release.assetUrl(APK_NAME)) { copied, total ->
                    ensureActive()
                    if (total > 0) onProgress(copied.toFloat() / total)
                }
            } catch (exception: Throwable) {
                session.abandon()
                throw exception
            }
            ensureActive()
            app.keepBundled()
            session.commit(statusReceiver(context, sessionId).intentSender)
        }
    }

    private suspend fun write(
        session: PackageInstaller.Session,
        url: String,
        onProgress: (Long, Long) -> Unit,
    ) = downloadClient.fetch(url) {
        header("Accept", "application/octet-stream")
        header("Accept-Encoding", "identity")
    }.use { response ->
        val total = response.body.contentLength()
        session.openWrite(APK_NAME, 0, total).use { output ->
            copyWithProgress(response.stream, output) { onProgress(it, total) }
            session.fsync(output)
        }
    }

    private fun statusReceiver(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallReceiver::class.java)
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (modern) FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

private val CONFIRM_ACTIONS = setOf(
    "android.content.pm.action.CONFIRM_INSTALL",
    "android.content.pm.action.CONFIRM_PERMISSIONS",
)

private fun isInstallConfirmation(intent: Intent) =
    intent.action in CONFIRM_ACTIONS && intent.`package` != BuildConfig.APPLICATION_ID

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ShelfApp
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_INTENT, Intent::class.java,
            )
            app.confirmInstall(confirmation?.takeIf(::isInstallConfirmation))
            return
        }
        app.removeBundledCopies()
        if (status == PackageInstaller.STATUS_SUCCESS) return
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val text = context.getString(R.string.update_install_failed, message.orEmpty())
        Toast.makeText(context, text.trim(), Toast.LENGTH_LONG).show()
    }
}
