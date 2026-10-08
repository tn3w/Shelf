package dev.tn3w.shelf

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.os.Build
import androidx.core.content.IntentCompat
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import java.security.DigestInputStream
import java.security.MessageDigest

private val STORES = setOf(
    "org.fdroid.fdroid",
    "org.fdroid.basic",
    "com.looker.droidify",
    "com.machiav3lli.fdroid",
    "com.aurora.store",
)
private const val APK_NAME = "shelf.apk"
private const val CHECKSUMS_NAME = "SHA256SUMS"

private fun numbers(version: String) = version.split(".").map { it.toIntOrNull() ?: 0 }

private fun isNewer(version: String): Boolean {
    val candidate = numbers(version)
    val installed = numbers(BuildConfig.VERSION_NAME)
    val length = maxOf(candidate.size, installed.size)
    val differing = (0 until length).firstOrNull {
        candidate.getOrElse(it) { 0 } != installed.getOrElse(it) { 0 }
    } ?: return false
    return candidate.getOrElse(differing) { 0 } > installed.getOrElse(differing) { 0 }
}

object Updater {
    fun isEnabled(context: Context): Boolean {
        val manager = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            manager.getInstallerPackageName(context.packageName)
        }
        return installer !in STORES
    }

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val release = json
            .decodeFromString<List<Release>>(fetchText(RELEASES))
            .firstOrNull { it.tag.startsWith("v") && !it.draft && !it.prerelease }
        val names = release?.assets.orEmpty().map { it.name }
        val complete = APK_NAME in names && CHECKSUMS_NAME in names
        release?.takeIf { complete && isNewer(it.version) }
    }

    suspend fun install(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val expected = checksum(fetchText(release.assetUrl(CHECKSUMS_NAME)), APK_NAME)
        val installer = context.packageManager.packageInstaller
        val sessionId =
            installer.createSession(SessionParams(SessionParams.MODE_FULL_INSTALL))
        installer.openSession(sessionId).use { session ->
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                write(session, digest, release.assetUrl(APK_NAME), onProgress)
                check(sha256(digest) == expected) { "checksum mismatch" }
            } catch (exception: Throwable) {
                session.abandon()
                throw exception
            }
            session.commit(statusReceiver(context, sessionId).intentSender)
        }
    }

    private fun checksum(sums: String, name: String): String = sums
        .lines()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }
        ?.first()
        ?.lowercase()
        ?: error("no checksum for $name")

    private fun write(
        session: PackageInstaller.Session,
        digest: MessageDigest,
        url: String,
        onProgress: (Float) -> Unit,
    ) {
        val connection = connect(url).apply {
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val status = connection.responseCode
        if (status != 200) error("download failed: $status")
        val total = connection.contentLengthLong
        DigestInputStream(connection.inputStream, digest).use { input ->
            session.openWrite(APK_NAME, 0, total).use { output ->
                copyWithProgress(input, output) {
                    if (total > 0) onProgress(it.toFloat() / total)
                }
                session.fsync(output)
            }
        }
    }

    private fun statusReceiver(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallReceiver::class.java)
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (modern) FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = IntentCompat.getParcelableExtra(
            intent, Intent.EXTRA_INTENT, Intent::class.java,
        ) ?: return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
