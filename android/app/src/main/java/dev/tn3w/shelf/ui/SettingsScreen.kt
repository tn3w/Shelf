package dev.tn3w.shelf.ui

import android.app.LocaleManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.roundToInt

private const val BACKUP_FILE = "shelf-library.zip"
private val BACKUP_TYPES = arrayOf("application/zip", "application/octet-stream")
private const val CSV_FILE = "shelf-library.csv"
private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val SOURCE_URL = "https://github.com/tn3w/Shelf"
private const val LICENSE_URL = "https://github.com/tn3w/Shelf/blob/master/LICENSE"
private const val OPEN_LIBRARY_URL = "https://openlibrary.org/developers/licensing"
private val GOALS = listOf(5, 10, 20, 30, 50)
private const val MAX_GOAL = 500
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
private val PAGE_COLORS = mapOf(
    PageColor.Theme to R.string.page_app,
    PageColor.Paper to R.string.page_paper,
    PageColor.Night to R.string.page_night,
)
private val THEMES = mapOf(
    ThemeMode.System to R.string.theme_system,
    ThemeMode.Light to R.string.theme_light,
    ThemeMode.Dark to R.string.theme_dark,
)
private val PACK_LABELS = mapOf(
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

enum class SettingsPage {
    Main,
    Catalogue,
    General,
    Reading,
    Privacy,
    Data,
}

private class Category(
    val page: SettingsPage,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val summary: Int,
)

private val CATEGORIES = listOf(
    Category(
        SettingsPage.Catalogue,
        Icons.Outlined.AutoStories,
        R.string.catalogue,
        R.string.catalogue_hint,
    ),
    Category(
        SettingsPage.General,
        Icons.Outlined.Palette,
        R.string.general,
        R.string.general_hint,
    ),
    Category(
        SettingsPage.Reading,
        Icons.AutoMirrored.Outlined.MenuBook,
        R.string.reading_settings,
        R.string.reading_hint,
    ),
    Category(
        SettingsPage.Privacy,
        Icons.Outlined.Shield,
        R.string.privacy,
        R.string.privacy_hint,
    ),
    Category(
        SettingsPage.Data,
        Icons.Outlined.Inventory2,
        R.string.your_data,
        R.string.your_data_hint,
    ),
)

private fun nativeName(language: String) = Locale.forLanguageTag(language).let {
    it.getDisplayLanguage(it).replaceFirstChar(Char::uppercase)
}

@Composable
private fun bytes(value: Long) =
    Formatter.formatShortFileSize(LocalContext.current, value)

private typealias SettingsUpdate = ((Settings) -> Settings) -> Unit

@Composable
private fun rememberSettings(): Pair<Settings, SettingsUpdate> {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val settings by app.library.settings.collectAsStateWithLifecycle(Settings())
    return settings to { change -> scope.launch { app.library.updateSettings(change) } }
}

@Composable
private fun packInfos(language: String, refreshes: Int = 0): List<PackInfo>? {
    val app = shelfApp()
    val loaded by app.loaded.collectAsStateWithLifecycle()
    val downloads by app.downloads.collectAsStateWithLifecycle()
    val keys = arrayOf(language, loaded, downloads.size, refreshes)
    return produceState<List<PackInfo>?>(null, *keys) {
        value = withContext(Dispatchers.IO) { app.packs.packs(language) }
    }
        .value
}

@Composable
fun SettingsScreen(page: SettingsPage, navigator: Navigator) {
    val title = CATEGORIES.firstOrNull { it.page == page }?.title ?: R.string.settings
    Column(
        Modifier.windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackBar(navigator::back)
        LargeTitle(stringResource(title))
        when (page) {
            SettingsPage.Main -> SettingsOverview(navigator)
            SettingsPage.Catalogue -> CatalogueSettings()
            SettingsPage.General -> GeneralSettings(navigator)
            SettingsPage.Reading -> ReadingSettings()
            SettingsPage.Privacy -> PrivacySettings()
            SettingsPage.Data -> DataSettings()
        }
    }
}

@Composable
private fun SettingsOverview(navigator: Navigator) {
    LibraryStats(navigator::library)
    SettingsGroup {
        CATEGORIES.forEach { category ->
            LinkRow(
                stringResource(category.title),
                stringResource(category.summary),
                category.icon,
            ) { navigator.settings(category.page) }
        }
    }
    SettingsGroup(
        stringResource(R.string.about), stringResource(R.string.settings_footer),
    ) { AboutRows() }
}

@Composable
private fun CatalogueSettings() {
    val app = shelfApp()
    val (settings, update) = rememberSettings()
    val language = app.bookLanguage(settings)
    var refreshes by remember { mutableIntStateOf(0) }
    val packs = packInfos(language, refreshes)

    SettingsGroup(
        stringResource(R.string.catalogue_language),
        stringResource(R.string.book_language_hint),
    ) {
        LanguageChoice(settings.language) { chosen ->
            update { it.copy(language = chosen) }
        }
    }

    PackList(language, packs, settings.isOffline(LocalContext.current)) { refreshes++ }
    SettingsGroup(
        stringResource(R.string.own_sources), stringResource(R.string.own_sources_hint),
    ) { SourceRows(language, settings, { refreshes++ }) { refreshes++ } }
}

@Composable
private fun LibraryStats(onOpen: () -> Unit) {
    val app = shelfApp()
    val saved by app.library.saved.collectAsStateWithLifecycle(emptyList())
    val habit by app.library.habit.collectAsStateWithLifecycle(null)
    val stats = listOf(
        saved.size to R.string.stat_books,
        saved.count { it.shelf == Shelf.Read } to R.string.finished,
        (habit?.streak ?: 0) to R.string.stat_streak,
    )
    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 16.dp),
        ) {
            stats.forEach { (value, label) ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralSettings(navigator: Navigator) {
    val app = shelfApp()
    val (settings, update) = rememberSettings()
    SettingsGroup(stringResource(R.string.appearance)) {
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
        SwitchRow(
            R.string.reduce_motion, R.string.reduce_motion_hint, settings.reduceMotion,
        ) { on -> update { it.copy(reduceMotion = on) } }
    }
    SettingsGroup(stringResource(R.string.language)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AppLanguageChoice()
        val auto = stringResource(R.string.language_auto).takeIf {
            settings.language.isEmpty()
        }
        val book = listOfNotNull(auto, nativeName(app.bookLanguage(settings)))
        LinkRow(stringResource(R.string.catalogue_language), book.joinToString(" · ")) {
            navigator.settings(SettingsPage.Catalogue)
        }
    }
}

@Composable
private fun ReadingSettings() {
    val (settings, update) = rememberSettings()
    var scale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
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
            valueRange = 0.7f..1.8f,
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
    }
}

@Composable
private fun GoalChoice(goal: Int, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val custom = goal.takeIf { it !in GOALS } ?: 0
    Choice(GOALS + custom, goal, { if (it == custom) editing = true else onChange(it) }) {
        if (it == 0) "…" else it.toString()
    }
    if (editing) {
        GoalDialog(goal, onDismiss = { editing = false }) {
            editing = false
            onChange(it)
        }
    }
}

@Composable
private fun GoalDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val goal = text.toIntOrNull()?.takeIf { it in 1..MAX_GOAL }
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
    val (settings, update) = rememberSettings()
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
            R.string.author_images,
            R.string.author_images_hint,
            settings.authorImages,
            enabled = !offline,
        ) { value -> update { it.copy(authorImages = value) } }
        SwitchRow(
            R.string.catalogue_updates,
            R.string.catalogue_updates_hint,
            settings.catalogueUpdates,
            enabled = !offline,
        ) { value -> update { it.copy(catalogueUpdates = value) } }
        if (Updater.isEnabled(context)) {
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
private fun DataSettings() {
    SettingsGroup(stringResource(R.string.backup), stringResource(R.string.backup_hint)) {
        BackupRows()
    }
    SettingsGroup(stringResource(R.string.csv), stringResource(R.string.csv_hint)) {
        CsvRows()
    }
}

@Composable
private fun SettingsGroup(
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
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(
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
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun LinkRow(
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
private fun ActionRow(
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
private fun SwitchRow(
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
private fun <T> Choice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    @StringRes title: Int? = null,
    label: @Composable (T) -> String,
) {
    title?.let {
        Text(
            stringResource(it),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
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
private fun CatalogueLanguageChoice(selected: String, onSelect: (String) -> Unit) =
    Choice(LANGUAGES, selected, onSelect) { nativeName(it) }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AppLanguageChoice() {
    val manager = LocalContext.current.getSystemService(LocaleManager::class.java)
    var current by remember {
        mutableStateOf(manager.applicationLocales.toLanguageTags())
    }
    LanguageChoice(current, R.string.app_language) { language ->
        current = language
        manager.applicationLocales = LocaleList.forLanguageTags(language)
    }
}

@Composable
private fun LanguageChoice(
    selected: String,
    @StringRes title: Int? = null,
    onSelect: (String) -> Unit,
) = Choice(listOf("") + LANGUAGES, selected, onSelect, title) {
    if (it.isEmpty()) stringResource(R.string.language_auto) else it.uppercase()
}

@Composable
private fun PackList(
    language: String,
    packs: List<PackInfo>?,
    offline: Boolean,
    onChecked: () -> Unit,
) {
    val app = shelfApp()
    val downloads by app.downloads.collectAsStateWithLifecycle()
    val storage by produceState(0L, packs) {
        value = withContext(Dispatchers.IO) { app.packs.storageBytes() }
    }
    val release by produceState<String?>(null, packs) {
        value = withContext(Dispatchers.IO) { app.packs.months()[language] }
    }
    val details = listOfNotNull(
        release?.let { stringResource(R.string.release_date, it) },
        stringResource(R.string.storage_used, bytes(storage)),
    )
    val note = stringResource(R.string.open_library_note)
    val hint = details.joinToString(" · ") + "\n" + note
    val title = "${stringResource(R.string.packs)} · ${nativeName(language)}"
    SettingsGroup(title, hint) {
        if (packs == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            return@SettingsGroup
        }
        packs.forEach { info ->
            PackRow(
                info,
                downloads["$language-${info.pack}"],
                offline,
                onDownload = { app.download(language, info.pack) },
                onRemove = { app.remove(language, info.pack) },
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val pending = packs.filter { it.state != PackState.Installed }
        val pendingBytes = pending.sumOf { it.bytes }
        if (!offline && pendingBytes > 0) {
            ActionRow(
                R.string.download_all_short, Icons.Outlined.Download, bytes(pendingBytes),
            ) { pending.forEach { app.download(language, it.pack) } }
        } else if (storage > 0) {
            ActionRow(R.string.delete_all, Icons.Outlined.Delete) {
                app.removeAll(language)
            }
        }
        if (!offline) CatalogueCheck(language, onChecked)
    }
}

@Composable
private fun CatalogueCheck(language: String, onChecked: () -> Unit) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }

    suspend fun check(): Int {
        app.packs.refreshManifest()
        val now = System.currentTimeMillis()
        app.library.updateSettings { it.copy(lastCatalogueCheck = now) }
        val updates = withContext(Dispatchers.IO) { app.packs.pendingUpdates(language) }
        if (updates.isEmpty()) return R.string.catalogue_current
        return R.string.catalogue_found
    }

    ActionRow(
        R.string.check_catalogue,
        Icons.Outlined.Refresh,
        status?.let { stringResource(it) },
        checking,
    ) {
        checking = true
        status = null
        scope.launch {
            status = runCatching { check() }.getOrDefault(R.string.catalogue_check_failed)
            checking = false
            onChecked()
        }
    }
}

private class Source(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val default: String,
    val current: (Settings) -> String,
    val isValid: (String) -> Boolean,
)

private val CATALOGUE_SOURCE = Source(
    R.string.catalogue_source,
    R.string.catalogue_source_hint,
    REPOSITORY,
    Settings::catalogueSource,
    ::isValidSource,
)

private val COVER_SOURCE = Source(
    R.string.cover_source, R.string.cover_source_hint, COVERS, Settings::coverSource,
) { it.isEmpty() || it.startsWith("https://") }

@Composable
private fun SourceRows(
    language: String,
    settings: Settings,
    onChange: () -> Unit,
    onImported: (complete: String?) -> Unit,
) {
    val app = shelfApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Source?>(null) }
    var status by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = app.importCatalogue(language, uris)
            val complete = (listOf(language) + LANGUAGES).firstOrNull {
                withContext(Dispatchers.IO) { app.packs.isComplete(it) }
            }
            status = when {
                !imported -> R.string.import_failed
                complete == null -> R.string.catalogue_incomplete
                else -> R.string.catalogue_imported
            }
            onImported(complete)
        }
    }
    fun save(source: Source, value: String) = scope.launch {
        editing = null
        if (source == COVER_SOURCE) {
            app.library.updateSettings { it.copy(coverSource = value) }
            return@launch
        }
        app.changeSource(value)
        if (!settings.isOffline(context)) runCatching { app.packs.refreshManifest() }
        onChange()
    }

    listOf(CATALOGUE_SOURCE, COVER_SOURCE).forEach { source ->
        val value = source.current(settings).ifEmpty { source.default }
        LinkRow(stringResource(source.title), value.removePrefix("https://")) {
            editing = source
        }
    }
    ActionRow(
        R.string.import_catalogue,
        Icons.Outlined.FileOpen,
        status?.let { stringResource(it) },
    ) { picker.launch(arrayOf("*/*")) }
    editing?.let { source ->
        SourceDialog(source, source.current(settings), onDismiss = { editing = null }) {
            save(source, it)
        }
    }
}

@Composable
private fun SourceDialog(
    source: Source,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    val valid = source.isValid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(source.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(source.hint))
                OutlinedTextField(
                    text,
                    { text = it.trim() },
                    placeholder = { Text(source.default) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.reset)) }
        },
    )
}

@Composable
private fun PackRow(
    info: PackInfo,
    download: Download?,
    offline: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = stringResource(PACK_LABELS.getValue(info.pack))
    val state = when (info.state) {
        PackState.Installed -> R.string.pack_installed
        PackState.Update -> R.string.pack_update
        PackState.Available -> R.string.pack_available
    }
    val size = if (info.bytes > 0) " · ${bytes(info.bytes)}" else ""
    SettingRow(label, stringResource(state) + size) {
        PackAction(label, download, info.state, info.pack, offline, onDownload, onRemove)
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
private fun PackAction(
    label: String,
    download: Download?,
    state: PackState,
    pack: String,
    offline: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    if (download is Download.Running) {
        val progress by animateFloatAsState(download.progress)
        return Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp,
            )
        }
    }
    val removable = state != PackState.Available && pack != "core"
    val remove = PackButton(Icons.Outlined.Delete, R.string.remove_pack, onRemove)
    val action = when {
        offline -> remove.takeIf { removable }

        download is Download.Failed ->
            PackButton(Icons.Outlined.ErrorOutline, R.string.retry_pack, onDownload)

        state == PackState.Available ->
            PackButton(Icons.Outlined.Download, R.string.download_pack, onDownload)

        state == PackState.Update ->
            PackButton(Icons.Outlined.Update, R.string.update_pack, onDownload)

        removable -> remove

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
private fun OfflineRow(settings: Settings, update: SettingsUpdate) {
    val permitted = networkPermitted(LocalContext.current)
    SwitchRow(
        R.string.offline_mode,
        if (permitted) R.string.offline_mode_hint else R.string.offline_forced,
        checked = settings.offline || !permitted,
        enabled = permitted,
    ) { value -> update { it.copy(offline = value) } }
}

@Composable
private fun BackupRows() {
    val app = shelfApp()
    FileRow(
        R.string.backup_export,
        Icons.Outlined.Upload,
        CreateDocument("application/zip"),
        BACKUP_FILE,
        R.string.csv_exported,
        app.library::exportTo,
    )
    FileRow(
        R.string.backup_import,
        Icons.Outlined.Download,
        OpenDocument(),
        BACKUP_TYPES,
        R.string.backup_done,
    ) { uri ->
        val done = app.library.importFrom(uri)
        if (done) restoreSource(app)
        done
    }
}

private suspend fun restoreSource(app: ShelfApp) {
    val settings = app.library.settings.first()
    if (settings.catalogueSource != app.packs.source) {
        app.changeSource(settings.catalogueSource)
    }
    app.reload(app.bookLanguage(settings))
}

@Composable
private fun <I> FileRow(
    @StringRes title: Int,
    icon: ImageVector,
    contract: ActivityResultContract<I, Uri?>,
    input: I,
    @StringRes success: Int,
    action: suspend (Uri) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Int?>(null) }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { status = if (action(uri)) success else R.string.backup_failed }
    }
    ActionRow(title, icon, status?.let { stringResource(it) }) { launcher.launch(input) }
}

@Composable
private fun CsvRows() {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var report by remember { mutableStateOf<CsvReport?>(null) }
    var imported by remember { mutableStateOf<Int?>(null) }
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        progress = 0f
        imported = null
        scope.launch {
            val searcher = app.loaded.value?.searcher
            report = app.library.importCsv(uri, searcher) { progress = it }
            imported = if (report == null) R.string.backup_failed else null
            progress = null
        }
    }
    val importStatus =
        progress?.let { stringResource(R.string.csv_progress, (it * 100).toInt()) }
            ?: imported?.let { stringResource(it) }
    ActionRow(
        R.string.csv_import, Icons.Outlined.Download, importStatus, progress != null,
    ) { import.launch(arrayOf("*/*")) }
    FileRow(
        R.string.csv_export,
        Icons.Outlined.Upload,
        CreateDocument("text/csv"),
        CSV_FILE,
        R.string.csv_exported,
        app.library::exportCsv,
    )
    report?.let { CsvReportDialog(it) { report = null } }
}

@Composable
private fun CsvReportDialog(report: CsvReport, onDismiss: () -> Unit) {
    val found = report.total - report.missing.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.csv_imported)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.csv_found, found, report.total))
                if (report.missing.isEmpty()) return@Column
                Text(
                    stringResource(R.string.csv_missing),
                    Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                report.missing.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
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
private fun AboutRows() {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val (settings, _) = rememberSettings()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    val version =
        stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
    val summary = listOfNotNull(version, status?.let { stringResource(it) })

    if (Updater.isEnabled(context) && !settings.isOffline(context)) {
        ActionRow(
            R.string.app_name,
            Icons.Outlined.Refresh,
            summary.joinToString(" · "),
            checking,
        ) {
            checking = true
            scope.launch {
                runCatching { Updater.latest() }
                    .onSuccess {
                        release = it
                        status = if (it == null) R.string.up_to_date else null
                    }
                    .onFailure { status = R.string.update_failed }
                checking = false
            }
        }
    } else {
        SettingRow(stringResource(R.string.app_name), version)
    }
    LinkRow(stringResource(R.string.source_code)) { uri.openUri(SOURCE_URL) }
    LinkRow(stringResource(R.string.license)) { uri.openUri(LICENSE_URL) }
    LinkRow(stringResource(R.string.open_library_license)) {
        uri.openUri(OPEN_LIBRARY_URL)
    }
    release?.let { UpdateDialog(it) { release = null } }
}

@Composable
fun UpdatePrompt(settings: Settings) {
    val app = shelfApp()
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val due = now - settings.lastAppCheck > DAY_MILLIS
        val allowed = settings.checkUpdates && !settings.isOffline(context)
        if (!allowed || !due || !Updater.isEnabled(context)) return@LaunchedEffect
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
                        failed = runCatching {
                            Updater.install(context, release) { progress = it }
                        }
                            .isFailure
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
