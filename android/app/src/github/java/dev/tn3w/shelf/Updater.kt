package dev.tn3w.shelf

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.os.Build
import dev.tn3w.shelf.data.connect
import dev.tn3w.shelf.data.copy
import dev.tn3w.shelf.data.sha256
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val RELEASES =
    "https://api.github.com/repos/tn3w/Shelf/releases?per_page=30"
private const val INSTALL_ACTION = "dev.tn3w.shelf.INSTALL_STATUS"
private val STORES =
    setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.looker.droidify",
        "com.machiav3lli.fdroid",
        "com.aurora.store",
    )
private val APK_NAME = Regex("shelf-github-(\\d+)\\.apk")
private val json = Json { ignoreUnknownKeys = true }

@Serializable private data class Asset(val name: String, val browser_download_url: String)

@Serializable
private data class GithubRelease(
    val tag_name: String,
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
)

private fun text(url: String) =
    connect(url).inputStream.use { it.readBytes().decodeToString() }

object Updater {
    fun isEnabled(context: Context): Boolean {
        val manager = context.packageManager
        val installer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                manager.getInstallerPackageName(context.packageName)
            }
        return installer !in STORES
    }

    suspend fun latest(): AppRelease? =
        withContext(Dispatchers.IO) {
            val release =
                json.decodeFromString<List<GithubRelease>>(text(RELEASES)).firstOrNull {
                    it.tag_name.startsWith("v") && !it.draft && !it.prerelease
                }
            val assets = release?.assets.orEmpty()
            val apk = assets.firstOrNull { APK_NAME.matches(it.name) }
            val sums = assets.firstOrNull { it.name == "SHA256SUMS" }
            if (release == null || apk == null || sums == null) return@withContext null
            val code = APK_NAME.matchEntire(apk.name)!!.groupValues[1].toInt()
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val version = release.tag_name.removePrefix("v")
            AppRelease(
                version,
                release.body,
                apk.browser_download_url,
                sums.browser_download_url,
            )
        }

    suspend fun install(
        context: Context,
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val name = release.apkUrl.substringAfterLast('/')
            val expected =
                text(release.checksumsUrl)
                    .lines()
                    .map { it.trim() }
                    .first { it.endsWith(name) }
                    .substringBefore(' ')
                    .lowercase()
            val installer = context.packageManager.packageInstaller
            val sessionId =
                installer.createSession(SessionParams(SessionParams.MODE_FULL_INSTALL))
            installer.openSession(sessionId).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                write(session, digest, release.apkUrl, onProgress)
                if (sha256(digest) != expected) {
                    session.abandon()
                    error("checksum mismatch")
                }
                session.commit(statusReceiver(context, sessionId).intentSender)
            }
        }

    private fun write(
        session: PackageInstaller.Session,
        digest: MessageDigest,
        url: String,
        onProgress: (Float) -> Unit,
    ) {
        val connection =
            connect(url).apply {
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
            }
        val status = connection.responseCode
        if (status != 200) error("download failed: $status")
        val total = connection.contentLengthLong
        DigestInputStream(connection.inputStream, digest).use { input ->
            session.openWrite("shelf.apk", 0, total).use { output ->
                copy(input, output) { if (total > 0) onProgress(it.toFloat() / total) }
                session.fsync(output)
            }
        }
    }

    private fun statusReceiver(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(INSTALL_ACTION)
        val mutable =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
        return PendingIntent.getActivity(context, sessionId, intent, flags)
    }

    fun onIntent(activity: Activity, intent: Intent) {
        if (intent.action != INSTALL_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        activity.startActivity(confirm)
    }
}
