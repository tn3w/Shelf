package dev.tn3w.shelf.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.AppRelease
import dev.tn3w.shelf.BuildConfig
import dev.tn3w.shelf.Download
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.Updater
import dev.tn3w.shelf.data.LANGUAGES
import dev.tn3w.shelf.data.PackInfo
import dev.tn3w.shelf.data.PackState
import dev.tn3w.shelf.data.Settings
import dev.tn3w.shelf.data.ThemeMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val SOURCE_URL = "https://github.com/tn3w/Shelf"
private const val LICENSE_URL = "https://github.com/tn3w/Shelf/blob/master/LICENSE"
private const val OPEN_LIBRARY_URL = "https://openlibrary.org/developers/licensing"
private val GOALS = listOf(5, 10, 20, 30, 50)
private val THEMES =
    listOf(
        ThemeMode.System to R.string.theme_system,
        ThemeMode.Light to R.string.theme_light,
        ThemeMode.Dark to R.string.theme_dark,
    )
private val PACK_LABELS =
    mapOf(
        "core" to R.string.pack_core,
        "fantasy" to R.string.pack_fantasy,
        "scifi" to R.string.pack_scifi,
        "mystery" to R.string.pack_mystery,
        "romance" to R.string.pack_romance,
        "kids" to R.string.pack_kids,
        "young-adult" to R.string.pack_young_adult,
        "nonfiction" to R.string.pack_nonfiction,
        "general" to R.string.pack_general,
    )

private fun nativeName(language: String) =
    Locale.forLanguageTag(language).let {
        it.getDisplayLanguage(it).replaceFirstChar(Char::uppercase)
    }

@Composable
private fun bytes(value: Long) =
    Formatter.formatShortFileSize(LocalContext.current, value)

@Composable
private fun rememberSettings(): Pair<Settings, ((Settings) -> Settings) -> Unit> {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val settings by app.library.settings.collectAsStateWithLifecycle(Settings())
    return settings to { change -> scope.launch { app.library.updateSettings(change) } }
}

@Composable
private fun packInfos(language: String): List<PackInfo>? {
    val app = shelfApp()
    val version by app.catalogueVersion.collectAsStateWithLifecycle()
    val downloads by app.downloads.collectAsStateWithLifecycle()
    return produceState<List<PackInfo>?>(null, language, version, downloads.size) {
            value =
                withContext(Dispatchers.IO) {
                    if (app.packs.manifest == null)
                        runCatching { app.packs.refreshManifest() }
                    app.packs.packs(language)
                }
        }
        .value
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(navigator: Navigator) {
    val app = shelfApp()
    val (settings, update) = rememberSettings()
    val language = settings.language
    val packs = packInfos(language)

    Column(
        Modifier.windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
    ) {
        BackBar(navigator::back, stringResource(R.string.settings))

        SectionHeader(
            stringResource(R.string.catalogue),
            stringResource(R.string.catalogue_hint),
        )
        CatalogueLanguageChoice(language) { chosen ->
            update { it.copy(language = chosen) }
            app.reload(chosen)
        }
        PackList(language, packs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SectionHeader(stringResource(R.string.app_language))
            AppLanguageChoice()
        }

        SectionHeader(stringResource(R.string.appearance))
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)
        ) {
            THEMES.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = settings.theme == mode,
                    onClick = { update { it.copy(theme = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, THEMES.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }

        SectionHeader(
            stringResource(R.string.daily_goal),
            stringResource(R.string.daily_goal_hint),
        )
        FlowRow(
            Modifier.padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GOALS.forEach { goal ->
                FilterChip(
                    selected = settings.dailyGoal == goal,
                    onClick = { update { it.copy(dailyGoal = goal) } },
                    label = { Text(stringResource(R.string.goal_pages, goal)) },
                )
            }
        }

        SectionHeader(stringResource(R.string.privacy))
        SwitchRow(
            R.string.online_covers,
            R.string.online_covers_hint,
            settings.onlineCovers,
        ) { value ->
            update { it.copy(onlineCovers = value) }
        }
        if (Updater.isEnabled(LocalContext.current)) {
            SwitchRow(
                R.string.check_updates,
                R.string.check_updates_hint,
                settings.checkUpdates,
            ) { value ->
                update { it.copy(checkUpdates = value) }
            }
        }

        LinkRow(R.string.about, navigator::about)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LanguageChoice(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    label: @Composable (String) -> String,
) {
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)
    ) {
        options.forEachIndexed { index, language ->
            SegmentedButton(
                selected = selected == language,
                onClick = { onSelect(language) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) {
                Text(label(language), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CatalogueLanguageChoice(selected: String, onSelect: (String) -> Unit) =
    LanguageChoice(LANGUAGES, selected, onSelect) { nativeName(it) }

@Composable
private fun AppLanguageChoice() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = LocalContext.current.getSystemService(LocaleManager::class.java)
    var current by remember {
        mutableStateOf(manager.applicationLocales.toLanguageTags())
    }
    val onSelect = { language: String ->
        current = language
        manager.applicationLocales = LocaleList.forLanguageTags(language)
    }
    LanguageChoice(listOf("") + LANGUAGES, current, onSelect) {
        if (it.isEmpty()) stringResource(R.string.theme_system) else it.uppercase()
    }
}

@Composable
private fun PackList(language: String, packs: List<PackInfo>?) {
    val app = shelfApp()
    val downloads by app.downloads.collectAsStateWithLifecycle()
    val storage by
        produceState(0L, packs) {
            value = withContext(Dispatchers.IO) { app.packs.storageBytes() }
        }
    Column(Modifier.padding(top = 8.dp)) {
        if (packs == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(ScreenPadding))
            return
        }
        packs.forEach { info ->
            PackRow(
                info,
                downloads["$language-${info.pack}"],
                onDownload = { app.download(language, info.pack) },
                onRemove = { app.remove(language, info.pack) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.storage_used, bytes(storage)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val pending = packs.filter { it.state != PackState.Installed }
            if (pending.isEmpty()) return@Row
            OutlinedButton(
                onClick = { pending.forEach { app.download(language, it.pack) } }
            ) {
                Text(
                    stringResource(
                        R.string.download_all,
                        bytes(pending.sumOf { it.bytes }),
                    )
                )
            }
        }
    }
}

@Composable
private fun PackRow(
    info: PackInfo,
    download: Download?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = stringResource(PACK_LABELS.getValue(info.pack))
    Row(
        Modifier.fillMaxWidth()
            .padding(start = ScreenPadding, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            val state =
                when (info.state) {
                    PackState.Installed -> R.string.pack_installed
                    PackState.Update -> R.string.pack_update
                    PackState.Available -> R.string.pack_available
                }
            Text(
                "${stringResource(state)} · ${bytes(info.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (download is Download.Running) {
                val progress by animateFloatAsState(download.progress)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        AnimatedContent(download to info.state, label = "pack") { (running, state) ->
            PackAction(label, running, state, info.pack, onDownload, onRemove)
        }
    }
}

@Composable
private fun PackAction(
    label: String,
    download: Download?,
    state: PackState,
    pack: String,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val action =
        when {
            download is Download.Running -> null
            download is Download.Failed ->
                PackButton(Icons.Outlined.ErrorOutline, R.string.retry_pack, onDownload)
            state == PackState.Available ->
                PackButton(Icons.Outlined.Download, R.string.download_pack, onDownload)
            state == PackState.Update ->
                PackButton(Icons.Outlined.Update, R.string.update_pack, onDownload)
            pack != "core" ->
                PackButton(Icons.Outlined.Delete, R.string.remove_pack, onRemove)
            else -> null
        } ?: return Box(Modifier.size(48.dp))
    IconAction(action.icon, stringResource(action.label, label), action.onClick)
}

private class PackButton(
    val icon: ImageVector,
    @StringRes val label: Int,
    val onClick: () -> Unit,
)

@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes hint: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkRow(@StringRes title: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

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
fun AboutScreen(navigator: Navigator) {
    val app = shelfApp()
    val uri = LocalUriHandler.current
    val months by
        produceState(emptyMap<String, String?>()) {
            value = withContext(Dispatchers.IO) { app.packs.months() }
        }
    var release by remember { mutableStateOf<UpdateCheck?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BackBar(navigator::back, stringResource(R.string.about))
        AppIcon(96)
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(
                R.string.version,
                BuildConfig.VERSION_NAME,
                BuildConfig.FLAVOR,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Updater.isEnabled(LocalContext.current)) {
            TextButton(
                onClick = {
                    release = UpdateCheck.Checking
                    scope.launch {
                        release =
                            runCatching { Updater.latest() }
                                .fold(
                                    {
                                        it?.let(UpdateCheck::Found) ?: UpdateCheck.Current
                                    },
                                    { UpdateCheck.Failed },
                                )
                    }
                }
            ) {
                Text(stringResource(R.string.check_now))
            }
            UpdateStatus(release) { release = null }
        }
        SectionHeader(stringResource(R.string.database))
        LANGUAGES.forEach { language ->
            AboutLine(nativeName(language), months[language] ?: "–")
        }
        SectionHeader(stringResource(R.string.privacy))
        AboutText(stringResource(R.string.privacy_note))
        SectionHeader(stringResource(R.string.data_source))
        AboutText(stringResource(R.string.open_library_note))
        LinkRow(R.string.open_library_license) { uri.openUri(OPEN_LIBRARY_URL) }
        LinkRow(R.string.source_code) { uri.openUri(SOURCE_URL) }
        LinkRow(R.string.license) { uri.openUri(LICENSE_URL) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AboutText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
    )
}

private sealed interface UpdateCheck {
    data object Checking : UpdateCheck

    data object Current : UpdateCheck

    data object Failed : UpdateCheck

    data class Found(val release: AppRelease) : UpdateCheck
}

@Composable
private fun UpdateStatus(check: UpdateCheck?, onDismiss: () -> Unit) {
    when (check) {
        null -> Unit
        UpdateCheck.Checking -> LinearProgressIndicator(Modifier.width(120.dp))
        UpdateCheck.Current -> AboutText(stringResource(R.string.up_to_date))
        UpdateCheck.Failed -> AboutText(stringResource(R.string.update_failed))
        is UpdateCheck.Found -> UpdateDialog(check.release, onDismiss)
    }
}

@Composable
fun UpdatePrompt(settings: Settings) {
    val app = shelfApp()
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val due = now - settings.lastAppCheck > DAY_MILLIS
        if (!settings.checkUpdates || !due || !Updater.isEnabled(context))
            return@LaunchedEffect
        app.library.updateSettings { it.copy(lastAppCheck = now) }
        release = runCatching { Updater.latest() }.getOrNull()
    }
    release?.let { UpdateDialog(it) { release = null } }
}

@Composable
private fun UpdateDialog(release: AppRelease, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(release.notes.take(600))
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
                        failed =
                            runCatching {
                                Updater.install(context, release) { progress = it }
                            }
                                .isFailure
                        progress = null
                    }
                },
            ) {
                Text(stringResource(R.string.install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
        },
    )
}

@Composable
fun OnboardingScreen() {
    val app = shelfApp()
    val (_, update) = rememberSettings()
    var language by remember { mutableStateOf(app.systemLanguage()) }
    val selected = remember(language) { mutableStateListOf<String>() }
    val packs = packInfos(language)

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(88)
        Text(
            stringResource(R.string.welcome),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        AboutText(stringResource(R.string.welcome_text))
        SectionHeader(stringResource(R.string.catalogue_language))
        CatalogueLanguageChoice(language) {
            language = it
            app.reload(it)
        }
        SectionHeader(
            stringResource(R.string.choose_packs),
            stringResource(R.string.core_offline),
        )
        OnboardingPacks(packs, selected)
        Row(
            Modifier.fillMaxWidth().padding(ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(
                onClick = { update { it.copy(onboarded = true, language = language) } }
            ) {
                Text(stringResource(R.string.later))
            }
            Button(
                enabled = selected.isNotEmpty(),
                onClick = {
                    selected.forEach { app.download(language, it) }
                    update { it.copy(onboarded = true, language = language) }
                },
            ) {
                Text(stringResource(R.string.download))
            }
        }
    }
}

@Composable
private fun OnboardingPacks(packs: List<PackInfo>?, selected: MutableList<String>) {
    if (packs == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(ScreenPadding))
        return
    }
    packs
        .filter { it.state == PackState.Available }
        .forEach { info ->
            val checked = info.pack in selected
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (checked) selected.remove(info.pack)
                        else selected.add(info.pack)
                    }
                    .padding(horizontal = ScreenPadding - 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    Modifier.padding(12.dp),
                )
                Text(
                    stringResource(PACK_LABELS.getValue(info.pack)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                val size = if (info.bytes > 0) bytes(info.bytes) else "–"
                Text(
                    size,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
}
