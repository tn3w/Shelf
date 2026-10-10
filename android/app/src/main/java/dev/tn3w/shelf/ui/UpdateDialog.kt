package dev.tn3w.shelf.ui

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
        val allowed = settings.checkUpdates && !settings.isOffline(context)
        if (!allowed || !due) return@LaunchedEffect
        app.library.updateSettings { it.copy(lastAppCheck = now) }
        release = runCatching { Updater.latest() }.getOrNull()
    }
    release?.let { UpdateDialog(it) { release = null } }
}

@Composable
fun UpdateDialog(release: Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
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
                progress?.let { value -> LinearProgressIndicator(progress = { value }) }
                if (failed) Text(stringResource(R.string.update_failed))
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
                        failed = result.isFailure
                        progress = null
                    }
                },
            ) { Text(stringResource(R.string.install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
        },
    )
}
