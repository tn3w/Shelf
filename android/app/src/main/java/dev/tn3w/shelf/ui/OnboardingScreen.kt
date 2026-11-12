package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        Look.colors.soft,
        style = Look.type.body,
        textAlign = TextAlign.Center,
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
                .padding(top = 44.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(84)
            Text(
                stringResource(R.string.welcome),
                Modifier.padding(horizontal = ScreenPadding)
                    .padding(top = 22.dp, bottom = 8.dp),
                style = Look.type.display,
                textAlign = TextAlign.Center,
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
                VoiceModels(offline = false, language, showOthers = false)
            }
            SettingsGroup {
                ExpandRow(R.string.own_sources, showSources) {
                    showSources = !showSources
                }
                if (showSources) SourceRows(language, settings)
            }
        }
        Rule()
        if (offline) {
            Pill(
                stringResource(R.string.start_offline),
                Modifier.fillMaxWidth().padding(ScreenPadding),
            ) { finish() }
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
        val icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore
        Icon(icon, null, tint = Look.colors.faint)
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (total > 0) {
            Pill(
                stringResource(R.string.download_all, bytes(total)),
                Modifier.weight(1f),
                kind = PillKind.Line,
                enabled = !waiting,
            ) { onDownload(available.map { it.pack }) }
        }
        if (pending.isEmpty()) {
            val next = stringResource(R.string.next)
            return@Row Pill(
                next,
                Modifier.weight(1f),
                enabled = canSkip,
                onClick = onSkip,
            )
        }
        Pill(stringResource(R.string.download), Modifier.weight(1f), enabled = !waiting) {
            onDownload(pending)
        }
    }
}

@Composable
private fun CoreRow(info: PackInfo?, download: Download?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(PACKS.getValue("core")),
                Modifier.weight(1f),
                style = Look.type.subhead,
            )
            Hint(if (info != null && info.bytes > 0) bytes(info.bytes) else "–")
        }
        if (download is Download.Running) {
            val progress by animateFloatAsState(download.progress)
            Meter(progress)
        }
        if (download is Download.Failed) {
            Hint(stringResource(R.string.pack_failed), color = Look.colors.danger)
        }
    }
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
            val size = if (info.bytes > 0) bytes(info.bytes) else "–"
            SettingRow(
                stringResource(PACKS.getValue(info.pack)),
                size,
                onClick = { onToggle(info.pack) },
            ) { Check(info.pack in selected) }
        }
}
