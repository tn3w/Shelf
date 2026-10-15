package dev.tn3w.shelf.ui

import android.text.format.DateUtils
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

@Composable
fun UpdatePrompt(settings: Settings) {
    val app = shelfApp()
    val context = LocalContext.current
    var release by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val due = now - settings.lastAppCheck > DAY_MILLIS
        val allowed = Updater.supported(context) && settings.checkUpdates &&
            !settings.isOffline(context)
        if (!allowed || !due) return@LaunchedEffect
        app.library.updateSettings { it.copy(lastAppCheck = now) }
        val found = runCatching { Updater.check(app) }.getOrNull()
        release = found?.takeIf { now >= it.readyAt }
    }
    release?.let { UpdateDialog(it) { release = null } }
}

@Composable
fun currentMillis() = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(MINUTE_IN_MILLIS)
        value = System.currentTimeMillis()
    }
}.value

fun relativeTime(time: Long, now: Long) =
    DateUtils.getRelativeTimeSpanString(time, now, MINUTE_IN_MILLIS).toString()
        .replaceFirstChar { it.lowercase() }

@Composable
fun UpdateDialog(release: Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val now = currentMillis()
    val waiting = now < release.readyAt
    val language = LocalConfiguration.current.locales[0].language
    val notes by produceState("", release, language) {
        val url = release.changelogUrl(language) ?: return@produceState
        value = runCatching { withContext(Dispatchers.IO) { fetchText(url).trim() } }
            .getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (notes.isNotEmpty()) {
                    Text(
                        notes,
                        modifier = Modifier.weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (waiting) {
                    val changed = relativeTime(release.changedAt, now)
                    Text(
                        stringResource(R.string.update_wait_note, changed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                progress?.let { value -> LinearProgressIndicator(progress = { value }) }
                failure?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            Button(
                enabled = progress == null,
                onClick = {
                    progress = 0f
                    scope.launch {
                        val result = runCatching {
                            Updater.install(context, release) { progress = it }
                        }
                        failure = when (result.exceptionOrNull()) {
                            null -> null
                            is ReleaseChanged -> R.string.update_changed
                            else -> R.string.update_failed
                        }
                        progress = null
                    }
                },
            ) {
                val label = if (waiting) R.string.install_anyway else R.string.install
                Text(stringResource(label))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
        },
    )
}
