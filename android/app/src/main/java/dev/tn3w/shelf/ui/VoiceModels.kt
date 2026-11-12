package dev.tn3w.shelf.ui

import android.app.ActivityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Download
import dev.tn3w.shelf.data.Model
import dev.tn3w.shelf.data.fits
import dev.tn3w.shelf.data.isSupported
import dev.tn3w.shelf.data.modelKey
import dev.tn3w.shelf.data.stateOf
import kotlin.math.roundToInt

@Composable
fun VoiceModels(offline: Boolean) {
    val viewModel = settingsViewModel()
    val models by viewModel.models.available.collectAsStateWithLifecycle()
    val installed by viewModel.models.installed.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val memory = totalMemory()
    val available = models.filter { isSupported(it) && fits(it, memory) }
    LaunchedEffect(offline) { if (!offline) viewModel.refreshModels() }
    if (available.isEmpty()) return
    SettingsGroup(
        stringResource(R.string.voice_models),
        stringResource(R.string.voice_models_hint),
    ) {
        available.forEach { model ->
            val download = remember(downloads, installed) {
                downloads[modelKey(model)] ?: viewModel.models.paused(model)
            }
            val label = modelLabel(model)
            val state = stateOf(model, installed)
            val status = (download as? Download.Paused)
                ?.let { stringResource(R.string.download_paused, percent(it.progress)) }
                ?: stringResource(state.label)
            val summary = listOf(
                status,
                bytes(model.size),
                model.licence,
                "SHA-256 ${model.files.first().sha256.take(8)}",
            ).joinToString(" · ")
            SettingRow(label, summary) {
                PackAction(
                    label,
                    download,
                    state,
                    offline,
                    onDownload = { viewModel.downloadModel(model) },
                    onRemove = { viewModel.removeModel(model) },
                    onPause = { viewModel.pauseModel(model) },
                )
            }
        }
    }
}

private fun percent(progress: Float) = (progress * 100).roundToInt()

@Composable
private fun totalMemory(): Long {
    val context = LocalContext.current
    return remember {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }
}

@Composable
private fun modelLabel(model: Model) = when {
    model.id == "speaker" -> stringResource(R.string.model_speaker)

    model.language.isNotEmpty() -> {
        val voices = stringResource(R.string.model_voices, nativeName(model.language))
        if (model.quality ==
            "high"
        ) {
            stringResource(R.string.high_quality, voices)
        } else {
            voices
        }
    }

    else -> model.id
}
