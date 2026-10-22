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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class UpdateViewModel(app: ShelfApp, private val release: Release, language: String) :
    ShelfViewModel(app) {
    private val _progress = MutableStateFlow<Float?>(null)
    val progress = _progress.asStateFlow()
    private val _failure = MutableStateFlow<Int?>(null)
    val failure = _failure.asStateFlow()
    private val _committed = MutableStateFlow(false)
    val committed = _committed.asStateFlow()
    val notes = flow {
        val url = release.changelogUrl(language) ?: return@flow
        emit(runSuspendCatching { fetchText(url).trim() }.getOrDefault(""))
    }.asState()

    fun install() {
        if (_progress.value != null) return
        _progress.value = 0f
        viewModelScope.launch {
            val result = runSuspendCatching {
                transfer({ _progress.value = it }) { Updater.install(app, release, it) }
            }
            _failure.value = when (result.exceptionOrNull()) {
                null -> null
                is ReleaseChanged -> R.string.update_changed
                else -> R.string.update_failed
            }
            _committed.value = result.isSuccess
            _progress.value = null
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
    val language = LocalConfiguration.current.locales[0].language
    val viewModel = shelfViewModel(release.tag) { UpdateViewModel(it, release, language) }
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val committed by viewModel.committed.collectAsStateWithLifecycle()
    val now = currentMillis()
    val waiting = now < release.readyAt
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    LaunchedEffect(committed) { if (committed) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                notes?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
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
                onClick = viewModel::install,
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
