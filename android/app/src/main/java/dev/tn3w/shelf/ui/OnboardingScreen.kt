package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*

@Composable
private fun AppIcon(size: Int) {
    Box(
        Modifier.size(size.dp)
            .background(colorResource(R.color.launcher_background), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AboutText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
    )
}

@Composable
fun OnboardingScreen() {
    val viewModel = settingsViewModel()
    val (settings, update) = rememberSettings() ?: return
    val offline = settings.isOffline(LocalContext.current)
    var language by rememberSaveable { mutableStateOf(viewModel.systemLanguage()) }
    var selected by rememberSaveable(language) { mutableStateOf(emptyList<String>()) }
    val packs = packInfos(language)
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val complete by viewModel.completeLanguage.collectAsStateWithLifecycle()
    val core = packs?.firstOrNull { it.pack == "core" }
    val required = core?.let { it.state == PackState.Available }
        ?: !BuildConfig.BUNDLED_CATALOGUE
    val coreDownload = downloads["$language-core"]
    var started by rememberSaveable(language) { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showPacks by rememberSaveable { mutableStateOf(false) }
    fun finish(chosen: String = language) {
        if (chosen != language) viewModel.reload(chosen)
        val now = System.currentTimeMillis()
        val stored = storedLanguage(chosen, viewModel.systemLanguage())
        update { it.copy(onboarded = true, language = stored, lastCatalogueCheck = now) }
    }

    LaunchedEffect(started, core?.state) {
        if (!started || core?.state == PackState.Available) return@LaunchedEffect
        finish()
    }
    LaunchedEffect(complete) { complete?.let(::finish) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(72)
            Text(
                stringResource(R.string.welcome),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            AboutText(
                stringResource(
                    if (required) R.string.welcome_download else R.string.welcome_text,
                ),
            )
            SettingsGroup(stringResource(R.string.catalogue_language)) {
                CatalogueLanguageChoice(language) {
                    language = it
                    viewModel.reload(it)
                }
            }
            SettingsGroup { OfflineRow(settings, update) }
            if (!offline) {
                val hint = if (required) R.string.core_required else R.string.core_offline
                SettingsGroup(stringResource(R.string.catalogue), stringResource(hint)) {
                    if (required) CoreRow(core, coreDownload)
                    ExpandRow(R.string.choose_packs, showPacks) { showPacks = !showPacks }
                    val skip = if (required) "core" else null
                    if (showPacks) {
                        OnboardingPacks(packs, selected, skip) { pack ->
                            selected =
                                if (pack in selected) selected - pack else selected + pack
                        }
                    }
                }
            }
            SettingsGroup {
                ExpandRow(R.string.own_sources, showSources) {
                    showSources = !showSources
                }
                if (showSources) SourceRows(language, settings)
            }
        }
        HorizontalDivider()
        if (offline) {
            Button(
                onClick = { finish() },
                modifier = Modifier.align(Alignment.End).padding(ScreenPadding),
            ) { Text(stringResource(R.string.start_offline)) }
            return@Column
        }
        OnboardingActions(
            available = packs.orEmpty().filter { it.state == PackState.Available },
            selected = if (required) listOf("core") + selected else selected,
            canSkip = !required || core?.state == PackState.Installed,
            waiting = started && coreDownload !is Download.Failed,
            onSkip = { finish() },
        ) { chosen ->
            chosen.forEach { viewModel.downloadPack(language, it) }
            started = true
        }
    }
}

@Composable
private fun CatalogueLanguageChoice(selected: String, onSelect: (String) -> Unit) =
    LanguageChips(
        LANGUAGES,
        selected,
        installed = installedLanguages(),
        onSelect = onSelect,
    )

@Composable
private fun ExpandRow(@StringRes title: Int, expanded: Boolean, onToggle: () -> Unit) =
    SettingRow(stringResource(title), onClick = onToggle) {
        val icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore
        Icon(icon, contentDescription = null)
    }

@Composable
private fun OnboardingActions(
    available: List<PackInfo>,
    selected: List<String>,
    canSkip: Boolean,
    waiting: Boolean,
    onSkip: () -> Unit,
    onDownload: (List<String>) -> Unit,
) {
    val total = available.sumOf { it.bytes }
    val pending = selected.filter { pack -> available.any { it.pack == pack } }
    Row(
        Modifier.fillMaxWidth().padding(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        if (total > 0) {
            OutlinedButton(
                enabled = !waiting, onClick = { onDownload(available.map { it.pack }) },
            ) { Text(stringResource(R.string.download_all, bytes(total))) }
        }
        if (pending.isEmpty()) {
            Button(enabled = canSkip, onClick = onSkip) {
                Text(stringResource(R.string.next))
            }
            return@Row
        }
        Button(enabled = !waiting, onClick = { onDownload(pending) }) {
            Text(stringResource(R.string.download))
        }
    }
}

@Composable
private fun CoreRow(info: PackInfo?, download: Download?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(PACKS.getValue("core")),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (info != null && info.bytes > 0) bytes(info.bytes) else "–",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        DownloadBar(download)
        if (download is Download.Failed) {
            Hint(
                stringResource(R.string.pack_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DownloadBar(download: Download?) {
    if (download !is Download.Running) return
    val progress by animateFloatAsState(download.progress)
    LinearProgressIndicator(
        progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun OnboardingPacks(
    packs: List<PackInfo>?,
    selected: List<String>,
    skip: String?,
    onToggle: (String) -> Unit,
) {
    if (packs == null) return LoadingBar()
    packs
        .filter { it.state == PackState.Available && it.pack != skip }
        .forEach { info ->
            val checked = info.pack in selected
            val size = if (info.bytes > 0) bytes(info.bytes) else "–"
            SettingRow(
                stringResource(PACKS.getValue(info.pack)),
                size,
                onClick = { onToggle(info.pack) },
            ) { Checkbox(checked = checked, onCheckedChange = null) }
        }
}
