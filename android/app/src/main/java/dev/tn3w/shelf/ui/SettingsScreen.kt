package dev.tn3w.shelf.ui

import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import kotlin.math.roundToInt

private const val SOURCE_URL = "https://github.com/tn3w/Shelf"
private const val LICENSE_URL = "https://github.com/tn3w/Shelf/blob/master/LICENSE"
private val DATA_LICENSES = listOf(
    R.string.open_library_license to "https://openlibrary.org/developers/licensing",
    R.string.dnb_license to "https://data.dnb.de/opendata/",
    R.string.erb_license to "https://doi.org/10.5281/zenodo.8228805",
)
private val GOALS = listOf(5, 10, 20, 30, 50)
private val SPACINGS = mapOf(
    1.35f to R.string.spacing_compact,
    1.55f to R.string.normal,
    1.8f to R.string.spacing_relaxed,
)
private val MARGINS =
    mapOf(16 to R.string.margin_narrow, 28 to R.string.normal, 44 to R.string.margin_wide)
private val TYPEFACES = mapOf(
    true to R.string.typeface_serif, false to R.string.typeface_sans,
)
private val PAGE_COLORS = PageColor.entries.associateWith { it.label }
private val THEMES = ThemeMode.entries.associateWith { it.label }

@Keep
enum class SettingsPage(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
) {
    Main(Icons.Outlined.Settings, R.string.settings),
    Catalogue(Icons.Outlined.AutoStories, R.string.catalogue, R.string.catalogue_hint),
    FreeBooks(
        Icons.Outlined.CloudDownload,
        R.string.free_books,
        R.string.free_books_hint,
    ),
    General(Icons.Outlined.Palette, R.string.general, R.string.general_hint),
    Reading(
        Icons.AutoMirrored.Outlined.MenuBook,
        R.string.reading_settings,
        R.string.reading_hint,
    ),
    Privacy(Icons.Outlined.Shield, R.string.privacy, R.string.privacy_hint),
    Data(Icons.Outlined.Inventory2, R.string.your_data, R.string.your_data_hint),
}

fun nativeName(language: String) = Locale.forLanguageTag(language).let {
    it.getDisplayLanguage(it).replaceFirstChar(Char::uppercase)
}

@Composable
fun bytes(value: Long) = Formatter.formatShortFileSize(LocalContext.current, value)

typealias SettingsUpdate = ((Settings) -> Settings) -> Unit

enum class SettingsAction {
    UpdateCheck,
    CatalogueCheck,
    CatalogueImport,
    BackupExport,
    BackupImport,
    CsvImport,
    CsvExport,
}

data class ActionStatus(val busy: Boolean = false, @StringRes val message: Int? = null)

class SettingsViewModel(app: ShelfApp) : ShelfViewModel(app) {
    private val _actions = MutableStateFlow<Map<SettingsAction, ActionStatus>>(emptyMap())
    val actions = _actions.asStateFlow()
    val packRevision = app.packs.revision
    private val _release = MutableStateFlow<Release?>(null)
    val release = _release.asStateFlow()
    private val _completeLanguage = MutableStateFlow<String?>(null)
    val completeLanguage = _completeLanguage.asStateFlow()
    private val _csvProgress = MutableStateFlow<Float?>(null)
    val csvProgress = _csvProgress.asStateFlow()
    private val _csvReport = MutableStateFlow<CsvReport?>(null)
    val csvReport = _csvReport.asStateFlow()

    private fun runAction(action: SettingsAction, work: suspend () -> Int?) {
        if (_actions.value[action]?.busy == true) return
        _actions.update { it + (action to ActionStatus(busy = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            var message: Int? = null
            try {
                message = work()
            } finally {
                _actions.update { it + (action to ActionStatus(message = message)) }
            }
        }
    }

    fun runFileAction(
        action: SettingsAction,
        @StringRes success: Int,
        @StringRes failure: Int,
        work: suspend () -> Boolean,
    ) = runAction(action) { if (transfer { work() }) success else failure }

    fun systemLanguage() = app.systemLanguage()

    fun reload(language: String) = app.reload(language)

    fun downloadPack(language: String, pack: String) = app.download(language, pack)

    fun removePacks(language: String, pack: String? = null) {
        app.removePacks(language, pack)
    }

    suspend fun packs(language: String) = withContext(Dispatchers.IO) {
        app.packs.packs(language)
    }

    suspend fun installedLanguages() = withContext(Dispatchers.IO) {
        app.packs.months().filterValues { it != null }.keys
    }

    suspend fun storage(language: String) = withContext(Dispatchers.IO) {
        val month = app.packs.months()[language].orEmpty()
        app.packs.storageBytes(language) to month
    }

    fun showUpdate(available: Release?) {
        if (available == null) checkUpdate() else _release.value = available
    }

    fun dismissUpdate() {
        _release.value = null
    }

    private fun checkUpdate() = runAction(SettingsAction.UpdateCheck) {
        runSuspendCatching { Updater.check(app) }.fold(
            onSuccess = { found ->
                _release.value = found
                R.string.up_to_date.takeIf { found == null }
            },
            onFailure = { R.string.update_failed },
        )
    }

    fun checkCatalogue(language: String) = runAction(SettingsAction.CatalogueCheck) {
        runSuspendCatching {
            app.packs.refreshManifest()
            val now = System.currentTimeMillis()
            library.updateSettings { it.copy(lastCatalogueCheck = now) }
            val current = app.packs.pendingUpdates(language).isEmpty()
            if (current) R.string.catalogue_current else R.string.catalogue_found
        }.getOrDefault(R.string.catalogue_check_failed)
    }

    fun changeCatalogueSource(source: String) = detached {
        app.changeSource(source)
        val offline = library.settings.first().isOffline(app)
        if (!offline) runSuspendCatching { app.packs.refreshManifest() }
    }

    fun importCatalogue(language: String, uris: List<Uri>) =
        runAction(SettingsAction.CatalogueImport) {
            val imported = transfer { app.importCatalogue(language, uris) }
            val candidates = listOf(language) + (LANGUAGES - language)
            val complete = candidates.firstOrNull(app.packs::isComplete)
            _completeLanguage.value = complete
            when {
                !imported -> R.string.import_failed
                complete == null -> R.string.catalogue_incomplete
                else -> R.string.catalogue_imported
            }
        }

    suspend fun restoreBackup(uri: Uri): Boolean {
        if (!library.importFrom(uri)) return false
        val settings = library.settings.first()
        if (settings.catalogueSource != app.packs.source) {
            app.changeSource(settings.catalogueSource)
        }
        app.reload(app.bookLanguage(settings))
        return true
    }

    fun chooseBackupFolder(folder: Uri?) = detached {
        val resolver = app.contentResolver
        val access = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.persistedUriPermissions.forEach {
            resolver.releasePersistableUriPermission(it.uri, access)
        }
        folder?.let { resolver.takePersistableUriPermission(it, access) }
        library.updateSettings { it.copy(backupFolder = folder?.toString().orEmpty()) }
        app.backUp()
    }

    fun importCsv(uri: Uri) = runAction(SettingsAction.CsvImport) {
        val catalogue = loaded.value
        val report = transfer({ _csvProgress.value = it }) { progress ->
            library.importCsv(uri, catalogue, progress)
        }
        _csvProgress.value = null
        _csvReport.value = report
        R.string.backup_failed.takeIf { report == null }
    }

    fun dismissCsvReport() {
        _csvReport.value = null
    }
}

@Composable
fun settingsViewModel() = shelfViewModel(create = ::SettingsViewModel)

@Composable
fun actionStatus(action: SettingsAction): Pair<Boolean, String?> {
    val actions by settingsViewModel().actions.collectAsStateWithLifecycle()
    val status = actions[action] ?: ActionStatus()
    return status.busy to status.message?.let { stringResource(it) }
}

val LocalSettings = compositionLocalOf<Settings?> { null }

@Composable
fun loadedSettings() = LocalSettings.current

@Composable
fun rememberSettings(): Pair<Settings, SettingsUpdate>? {
    val viewModel = shelfViewModel()
    val settings = loadedSettings() ?: return null
    return settings to viewModel::updateSettings
}

@Composable
fun SettingsScreen(page: SettingsPage, navigator: Navigator) {
    Column(
        Modifier.windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackBar(navigator::back)
        LargeTitle(stringResource(page.title))
        when (page) {
            SettingsPage.Main -> SettingsOverview(navigator)
            SettingsPage.Catalogue -> CatalogueSettings()
            SettingsPage.FreeBooks -> FreeBookSettings()
            SettingsPage.General -> GeneralSettings(navigator)
            SettingsPage.Reading -> ReadingSettings()
            SettingsPage.Privacy -> PrivacySettings()
            SettingsPage.Data -> DataSettings()
        }
    }
}

@Composable
private fun SettingsOverview(navigator: Navigator) {
    SettingsGroup {
        SettingsPage.entries.forEach { page ->
            val summary = page.summary ?: return@forEach
            LinkRow(stringResource(page.title), stringResource(summary), page.icon) {
                navigator.settings(page)
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.about), stringResource(R.string.settings_footer),
    ) { AboutRows() }
}

@Composable
private fun GeneralSettings(navigator: Navigator) {
    val viewModel = shelfViewModel()
    val (settings, update) = rememberSettings() ?: return
    SettingsGroup(stringResource(R.string.appearance)) {
        SwitchRow(R.string.e_ink, R.string.e_ink_hint, settings.eInk) { on ->
            update { it.copy(eInk = on) }
        }
        if (!settings.eInk) DisplaySettings(settings, update)
    }
    SettingsGroup(stringResource(R.string.language)) {
        AppLanguageChoice()
        val auto = stringResource(R.string.language_auto).takeIf {
            settings.language.isEmpty()
        }
        val book = listOfNotNull(auto, nativeName(viewModel.bookLanguage(settings)))
        LinkRow(stringResource(R.string.catalogue_language), book.joinToString(" · ")) {
            navigator.settings(SettingsPage.Catalogue)
        }
    }
}

@Composable
private fun DisplaySettings(settings: Settings, update: SettingsUpdate) {
    OptionChoice(THEMES, settings.theme) { mode -> update { it.copy(theme = mode) } }
    SwitchRow(
        R.string.black_theme, R.string.black_theme_hint, settings.blackTheme,
    ) { on -> update { it.copy(blackTheme = on) } }
    if (wallpaperColorsSupported) {
        SwitchRow(
            R.string.wallpaper_colors,
            R.string.wallpaper_colors_hint,
            settings.wallpaperColors,
        ) { on -> update { it.copy(wallpaperColors = on) } }
    }
}

@Composable
private fun ReadingSettings() {
    val (settings, update) = rememberSettings() ?: return
    var scale by rememberSaveable(settings.fontScale) {
        mutableFloatStateOf(settings.fontScale)
    }
    SettingsGroup(
        stringResource(R.string.daily_goal), stringResource(R.string.daily_goal_hint),
    ) { GoalChoice(settings.dailyGoal) { goal -> update { it.copy(dailyGoal = goal) } } }
    SettingsGroup(stringResource(R.string.reader), stringResource(R.string.reader_hint)) {
        ReaderPreview(settings.copy(fontScale = scale))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.text_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(scale * 100).roundToInt()} %",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            scale,
            { scale = it },
            Modifier.padding(horizontal = 16.dp),
            valueRange = FONT_SCALES,
            steps = 10,
            onValueChangeFinished = { update { it.copy(fontScale = scale) } },
        )
        OptionChoice(SPACINGS, settings.lineSpacing, R.string.line_spacing) { value ->
            update { it.copy(lineSpacing = value) }
        }
        OptionChoice(MARGINS, settings.margin, R.string.margins) { value ->
            update { it.copy(margin = value) }
        }
        OptionChoice(TYPEFACES, settings.serif, R.string.typeface) { value ->
            update { it.copy(serif = value) }
        }
        OptionChoice(PAGE_COLORS, settings.pageColor, R.string.page_color) { value ->
            update { it.copy(pageColor = value) }
        }
        SwitchRow(R.string.justify, R.string.justify_hint, settings.justify) { on ->
            update { it.copy(justify = on) }
        }
    }
    SettingsGroup {
        SwitchRow(
            R.string.keep_screen_on, R.string.keep_screen_on_hint, settings.keepScreenOn,
        ) { on -> update { it.copy(keepScreenOn = on) } }
        SwitchRow(
            R.string.volume_keys, R.string.volume_keys_hint, settings.volumeKeys,
        ) { on -> update { it.copy(volumeKeys = on) } }
        SwitchRow(
            R.string.brightness_swipe, R.string.brightness_swipe_hint,
            settings.brightnessSwipe,
        ) { on -> update { it.copy(brightnessSwipe = on, readerBrightness = null) } }
    }
}

@Composable
private fun GoalChoice(goal: Int, onChange: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val custom = goal.takeIf { it !in GOALS }
    fun select(option: Int?) {
        if (option == null || option == custom) editing = true else onChange(option)
    }
    Choice(GOALS + custom, goal, ::select) { it?.toString() ?: "…" }
    if (editing) {
        GoalDialog(goal, onDismiss = { editing = false }) {
            editing = false
            onChange(it)
        }
    }
}

@Composable
private fun GoalDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val goal = text.toIntOrNull()?.takeIf { it in 0..MAX_DAILY_GOAL }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.daily_goal)) },
        text = {
            OutlinedTextField(
                text,
                { text = it.filter(Char::isDigit).take(3) },
                suffix = { Text(stringResource(R.string.pages_suffix)) },
                singleLine = true,
                isError = goal == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = goal != null, onClick = { goal?.let(onSave) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ReaderPreview(settings: Settings) {
    val palette = pagePalette(settings)
    val shape = RoundedCornerShape(16.dp)
    Text(
        stringResource(R.string.reader_sample),
        style = readerStyle(settings, palette.text),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier =
        Modifier.fillMaxWidth()
            .padding(12.dp)
            .background(palette.background, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = (settings.margin * 0.6f).dp, vertical = 16.dp),
    )
}

@Composable
private fun <T> OptionChoice(
    options: Map<T, Int>,
    selected: T,
    @StringRes title: Int? = null,
    onSelect: (T) -> Unit,
) {
    Choice(options.keys.toList(), selected, onSelect, title) {
        stringResource(options.getValue(it))
    }
}

@Composable
private fun PrivacySettings() {
    val context = LocalContext.current
    val (settings, update) = rememberSettings() ?: return
    val offline = settings.isOffline(context)
    SettingsGroup(hint = stringResource(R.string.privacy_note)) {
        OfflineRow(settings, update)
    }
    SettingsGroup(hint = stringResource(R.string.privacy_requests_hint)) {
        SwitchRow(
            R.string.online_covers,
            R.string.online_covers_hint,
            settings.onlineCovers,
            enabled = !offline,
        ) { value -> update { it.copy(onlineCovers = value) } }
        SwitchRow(
            R.string.catalogue_updates,
            R.string.catalogue_updates_hint,
            settings.catalogueUpdates,
            enabled = !offline,
        ) { value -> update { it.copy(catalogueUpdates = value) } }
        if (Updater.supported(context)) {
            SwitchRow(
                R.string.check_updates,
                R.string.check_updates_hint,
                settings.checkUpdates,
                enabled = !offline,
            ) { value -> update { it.copy(checkUpdates = value) } }
        }
    }
}

@Composable
fun SettingsGroup(
    title: String? = null,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(top = 20.dp),
    ) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
        hint?.let { Hint(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
    }
}

@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val clickable = onClick?.let { Modifier.clickable(enabled, onClick = it) } ?: Modifier
    Row(
        clickable
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { IconBadge(it) }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Hint(it) }
        }
        Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.Center) {
            trailing()
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        Modifier.size(40.dp)
            .background(
                MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun LinkRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) = SettingRow(title, summary, icon, onClick = onClick) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ActionRow(
    @StringRes title: Int,
    icon: ImageVector,
    status: String? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
) = SettingRow(stringResource(title), status, onClick = { if (!busy) onClick() }) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(
    @StringRes title: Int,
    @StringRes hint: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) = SettingRow(
    stringResource(title),
    stringResource(hint),
    enabled = enabled,
    onClick = { onChange(!checked) },
) { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) }

@Composable
private fun ChoiceTitle(@StringRes title: Int) = Text(
    stringResource(title),
    style = MaterialTheme.typography.bodyLarge,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
)

@Composable
fun <T> Choice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    @StringRes title: Int? = null,
    label: @Composable (T) -> String,
) {
    title?.let { ChoiceTitle(it) }
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun AppLanguageChoice() {
    val chosen = AppCompatDelegate.getApplicationLocales()[0]?.language
    var current by rememberSaveable {
        mutableStateOf(chosen?.takeIf { it in LANGUAGES }.orEmpty())
    }
    LanguageChips(listOf("") + LANGUAGES, current, R.string.app_language) { language ->
        current = language
        val locales = LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

@Composable
private fun ChipIcon(icon: ImageVector) =
    Icon(icon, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize))

@Composable
fun LanguageChips(
    options: List<String>,
    selected: String,
    @StringRes title: Int? = null,
    installed: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
) {
    title?.let { ChoiceTitle(it) }
    val auto = stringResource(R.string.language_auto)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val main = { option: String ->
        val pinned = option.isEmpty() || option == selected || option in installed
        pinned || option in MAIN_LANGUAGES
    }
    val shown = if (expanded) options else options.filter(main)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { option ->
            val chosen = option == selected
            val check: @Composable () -> Unit = { ChipIcon(Icons.Outlined.Check) }
            val done: @Composable () -> Unit = { ChipIcon(Icons.Outlined.DownloadDone) }
            FilterChip(
                selected = chosen,
                onClick = { onSelect(option) },
                label = {
                    Text(if (option.isEmpty()) auto else nativeName(option), maxLines = 1)
                },
                leadingIcon = check.takeIf { chosen },
                trailingIcon = done.takeIf { option in installed },
            )
        }
        if (shown.size < options.size) {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(stringResource(R.string.show_all), maxLines = 1) },
                trailingIcon = { ChipIcon(Icons.Outlined.ExpandMore) },
            )
        }
    }
}

@Composable
fun OfflineRow(settings: Settings, update: SettingsUpdate) {
    val permitted = networkPermitted(LocalContext.current)
    SwitchRow(
        R.string.offline_mode,
        if (permitted) R.string.offline_mode_hint else R.string.offline_forced,
        checked = settings.offline || !permitted,
        enabled = permitted,
    ) { value -> update { it.copy(offline = value) } }
}

@Composable
private fun updateSummary(release: Release): String {
    val now = currentMillis()
    val ready = stringResource(R.string.update_available, release.version)
    if (now >= release.readyAt) return ready
    val wait = relativeTime(release.readyAt, now)
    return stringResource(R.string.update_waiting, ready, wait)
}

@Composable
private fun AboutRows() {
    val viewModel = settingsViewModel()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val settings = loadedSettings() ?: return
    val (checking, status) = actionStatus(SettingsAction.UpdateCheck)
    val release by viewModel.release.collectAsStateWithLifecycle()
    val available = settings.availableUpdate?.takeIf(Updater::isAvailable)
    val version =
        stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
    val found = available?.let { updateSummary(it) }
    val summary = listOfNotNull(version, found ?: status)

    if (Updater.supported(context) && !settings.isOffline(context)) {
        ActionRow(
            R.string.app_name,
            Icons.Outlined.Refresh,
            summary.joinToString(" · "),
            checking,
        ) {
            viewModel.showUpdate(available)
        }
    } else {
        SettingRow(stringResource(R.string.app_name), version)
    }
    LinkRow(stringResource(R.string.source_code)) { uri.openUri(SOURCE_URL) }
    LinkRow(stringResource(R.string.license)) { uri.openUri(LICENSE_URL) }
    DATA_LICENSES.forEach { (title, url) ->
        LinkRow(stringResource(title)) { uri.openUri(url) }
    }
    release?.let { UpdateDialog(it, viewModel::dismissUpdate) }
}
