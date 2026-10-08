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
    val app = shelfApp()
    val (settings, update) = rememberSettings()
    val offline = settings.isOffline(LocalContext.current)
    var language by remember { mutableStateOf(app.systemLanguage()) }
    val selected = remember(language) { mutableStateListOf<String>() }
    var refreshes by remember { mutableIntStateOf(0) }
    val packs = packInfos(language, refreshes)
    val downloads by app.downloads.collectAsStateWithLifecycle()
    val required = !BuildConfig.BUNDLED_CATALOGUE
    val core = packs?.firstOrNull { it.pack == "core" }
    val coreDownload = downloads["$language-core"]
    var started by remember(language) { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showPacks by remember { mutableStateOf(false) }
    fun finish(chosen: String = language) {
        if (chosen != language) app.reload(chosen)
        val now = System.currentTimeMillis()
        update { it.copy(onboarded = true, language = chosen, lastCatalogueCheck = now) }
    }

    LaunchedEffect(started, core?.state) {
        if (!started || core?.state == PackState.Available) return@LaunchedEffect
        finish()
    }

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
                    app.reload(it)
                }
            }
            SettingsGroup { OfflineRow(settings, update) }
            if (!offline) {
                val hint = if (required) R.string.core_required else R.string.core_offline
                SettingsGroup(stringResource(R.string.catalogue), stringResource(hint)) {
                    if (required) CoreRow(core, coreDownload)
                    ExpandRow(R.string.choose_packs, showPacks) { showPacks = !showPacks }
                    val skip = if (required) "core" else null
                    if (showPacks) OnboardingPacks(packs, selected, skip)
                }
            }
            SettingsGroup {
                ExpandRow(R.string.own_sources, showSources) {
                    showSources = !showSources
                }
                if (showSources) {
                    SourceRows(language, settings, { refreshes++ }) { complete ->
                        if (complete != null) finish(complete) else refreshes++
                    }
                }
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
            canDownload = if (required) core != null else selected.isNotEmpty(),
            waiting = started && coreDownload !is Download.Failed,
            onSkip = { finish() },
        ) { chosen ->
            chosen.forEach { app.download(language, it) }
            started = true
        }
    }
}

@Composable
private fun CatalogueLanguageChoice(selected: String, onSelect: (String) -> Unit) =
    Choice(LANGUAGES, selected, onSelect) { nativeName(it) }

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
    canDownload: Boolean,
    waiting: Boolean,
    onSkip: () -> Unit,
    onDownload: (List<String>) -> Unit,
) {
    val total = available.sumOf { it.bytes }
    Row(
        Modifier.fillMaxWidth().padding(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        if (canSkip) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.later)) }
        }
        if (total > 0) {
            OutlinedButton(
                enabled = !waiting, onClick = { onDownload(available.map { it.pack }) },
            ) { Text(stringResource(R.string.download_all, bytes(total))) }
        }
        Button(enabled = !waiting && canDownload, onClick = { onDownload(selected) }) {
            Text(stringResource(R.string.download))
        }
    }
}

@Composable
private fun CoreRow(info: PackInfo?, download: Download?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(PACK_LABELS.getValue("core")),
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
            Text(
                stringResource(R.string.pack_failed),
                style = MaterialTheme.typography.bodySmall,
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
    selected: MutableList<String>,
    skip: String?,
) {
    if (packs == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        return
    }
    packs
        .filter { it.state == PackState.Available && it.pack != skip }
        .forEach { info ->
            val checked = info.pack in selected
            val size = if (info.bytes > 0) bytes(info.bytes) else "–"
            SettingRow(
                stringResource(PACK_LABELS.getValue(info.pack)),
                size,
                onClick = {
                    if (checked) selected.remove(info.pack) else selected.add(info.pack)
                },
            ) { Checkbox(checked = checked, onCheckedChange = null) }
        }
}
