package dev.tn3w.shelf.ui

import android.app.ActivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Download
import dev.tn3w.shelf.data.Model
import dev.tn3w.shelf.data.fits
import dev.tn3w.shelf.data.isSupported
import dev.tn3w.shelf.data.modelKey
import dev.tn3w.shelf.data.preferredModel
import dev.tn3w.shelf.data.stateOf
import dev.tn3w.shelf.data.voicesByLanguage
import kotlin.math.roundToInt

@Composable
fun VoiceModels(offline: Boolean, language: String, showOthers: Boolean = true) {
    val viewModel = settingsViewModel()
    val models by viewModel.models.available.collectAsStateWithLifecycle()
    val memory = totalMemory()
    val usable = models.filter { isSupported(it) && fits(it, memory) }
    LaunchedEffect(offline) { if (!offline) viewModel.refreshModels() }
    if (usable.isEmpty()) return
    val voices = voicesByLanguage(usable)
    val others = voices - language
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.voice_models),
        stringResource(R.string.voice_models_hint),
    ) {
        voices[language]?.let { LanguageRow(language, it, offline) }
        usable.firstOrNull { it.kind == "speaker" }?.let { speaker ->
            ModelRow(stringResource(R.string.model_speaker), listOf(speaker), offline)
        }
        if (!showOthers || others.isEmpty()) return@SettingsGroup
        val icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore
        SettingRow(
            stringResource(R.string.other_languages),
            onClick = { expanded = !expanded },
        ) { Icon(icon, null, tint = Look.colors.faint) }
        if (!expanded) return@SettingsGroup
        others.forEach { (other, choices) -> LanguageRow(other, choices, offline) }
    }
}

@Composable
private fun LanguageRow(language: String, choices: List<Model>, offline: Boolean) =
    ModelRow(nativeName(language), choices, offline)

@Composable
private fun ModelRow(title: String, choices: List<Model>, offline: Boolean) {
    val viewModel = settingsViewModel()
    val installed by viewModel.models.installed.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var chosen by rememberSaveable(choices.first().id) {
        mutableStateOf(preferredModel(choices, installed).id)
    }
    val model = choices.firstOrNull { it.id == chosen } ?: choices.first()
    val state = stateOf(model, installed)
    val download = remember(downloads, installed, model) {
        downloads[modelKey(model)] ?: viewModel.models.paused(model)
    }
    val paused = (download as? Download.Paused)?.let { "${percent(it.progress)} %" }
    val summary = listOfNotNull(
        stringResource(state.label),
        paused,
        bytes(model.size),
        model.licence,
    )
    Row(
        Modifier.fillMaxWidth()
            .padding(start = ScreenPadding, end = 10.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Look.type.subhead)
            Hint(summary.joinToString(" · "), Modifier.padding(top = 2.dp))
            if (choices.size > 1) QualityChips(choices, model) { chosen = it.id }
        }
        PackAction(
            title,
            download,
            state,
            offline,
            onDownload = { viewModel.downloadModel(model) },
            onRemove = { viewModel.removeModel(model) },
            onPause = { viewModel.pauseModel(model) },
        )
    }
}

@Composable
private fun QualityChips(
    choices: List<Model>,
    selected: Model,
    onSelect: (Model) -> Unit,
) {
    Row(
        Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { model ->
            val label = if (model.quality == "high") {
                R.string.quality_high
            } else {
                R.string.quality_standard
            }
            Tag(stringResource(label), selected = model == selected) { onSelect(model) }
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
