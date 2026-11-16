package dev.tn3w.shelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val ADDRESS = "shelf-feedback@tn3w.dev"
private const val ISSUES_URL = "https://github.com/tn3w/Shelf/issues/new"
private const val MARKER = "Sent from Shelf"
private const val LOG_MARKER = "--- log ---"
private const val CRASH_FILE = "last-crash.txt"
private const val LOG_LINES = 300
private const val MAX_CRASH = 20_000
private const val CRASH_MAX_AGE = 7L * 24 * 60 * 60 * 1000

private val PRIVATE_PATTERNS = listOf(
    Regex("""content://\S+""") to "<uri>",
    Regex("""(https?://[^/\s:]+)\S*""") to "$1/<path>",
    Regex("""(?:file://)?/(?:storage|sdcard|data|mnt)/\S+""") to "<path>",
    Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""") to "<email>",
    Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""") to "<ip>",
)

enum class FeedbackKind(
    val subject: String,
    @param:StringRes val title: Int,
    @param:StringRes val hint: Int,
) {
    Feature("feature request", R.string.suggest_feature, R.string.feature_hint),
    Problem("bug report", R.string.report_problem, R.string.problem_hint),
}

object Feedback {
    fun recordCrashes(context: Context) {
        val file = crashFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(error.stackTraceToString().take(MAX_CRASH)) }
            previous?.uncaughtException(thread, error)
        }
    }

    suspend fun send(context: Context, kind: FeedbackKind, message: String) {
        val log = if (kind == FeedbackKind.Problem) {
            withContext(Dispatchers.IO) { collectLog(context) }
        } else {
            ""
        }
        val body = report(message, deviceDetails(), log)
        val email = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            .putExtra(Intent.EXTRA_SUBJECT, subject(BuildConfig.VERSION_NAME, kind))
            .putExtra(Intent.EXTRA_TEXT, body)
            .apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }

        runCatching { context.startActivity(email) }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
        }
    }

    private fun crashFile(context: Context) = context.noBackupFilesDir.resolve(CRASH_FILE)

    private fun deviceDetails() = listOf(
        "Shelf ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})",
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Language ${Locale.getDefault().language}",
    )

    private fun collectLog(context: Context): String {
        val crash = recentCrash(crashFile(context))?.let {
            "Last crash:\n$it\n\n"
        }.orEmpty()
        val pid = "--pid=${Process.myPid()}"
        val command =
            arrayOf("logcat", "-d", "-t", "$LOG_LINES", "-v", "time", pid, "*:W")
        val log = runCatching {
            ProcessBuilder(*command).redirectErrorStream(true).start()
                .inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return crash + log
    }

    private fun recentCrash(file: File): String? {
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > CRASH_MAX_AGE) return null
        return file.readText()
    }
}

fun subject(version: String, kind: FeedbackKind) = "Shelf $version ${kind.subject}"

fun report(message: String, details: List<String>, log: String): String {
    val lines = listOf(message, "") + details + MARKER
    val redacted = redact(log.trim())
    val logPart = if (redacted.isEmpty()) emptyList() else listOf(LOG_MARKER, redacted)
    return (lines + logPart).joinToString("\n")
}

fun redact(text: String) = PRIVATE_PATTERNS.fold(text) { result, (pattern, replacement) ->
    pattern.replace(result, replacement)
}
