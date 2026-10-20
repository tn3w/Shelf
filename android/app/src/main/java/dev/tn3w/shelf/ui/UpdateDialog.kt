package dev.tn3w.shelf.ui

import android.app.Application
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class UpdateViewModel(application: Application) : ShelfViewModel(application) {
    val progress = MutableStateFlow<Float?>(null)
    val failure = MutableStateFlow<Int?>(null)
    val committed = MutableStateFlow(false)

    fun install(release: Release) {
        if (progress.value != null) return
        progress.value = 0f
        detached {
            val result = runSuspendCatching {
                Updater.install(app, release) { progress.value = it }
            }
            failure.value = when (result.exceptionOrNull()) {
                null -> null
                is ReleaseChanged -> R.string.update_changed
                else -> R.string.update_failed
            }
            committed.value = result.isSuccess
            progress.value = null
        }
    }
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
    val viewModel: UpdateViewModel = viewModel(key = release.tag)
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val committed by viewModel.committed.collectAsStateWithLifecycle()
    val now = currentMillis()
    val waiting = now < release.readyAt
    val language = LocalConfiguration.current.locales[0].language
    val notes by produceState("", release, language) {
        val url = release.changelogUrl(language) ?: return@produceState
        value = runSuspendCatching { fetchText(url).trim() }.getOrDefault("")
    }
    LaunchedEffect(committed) { if (committed) onDismiss() }
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
                    Hint(stringResource(R.string.update_wait_note, changed))
                }
                progress?.let { value -> LinearProgressIndicator(progress = { value }) }
                failure?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            Button(
                enabled = progress == null,
                onClick = { viewModel.install(release) },
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
