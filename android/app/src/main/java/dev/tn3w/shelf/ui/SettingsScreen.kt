package dev.tn3w.shelf.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.text.format.Formatter
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
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
import java.util.Locale
import kotlin.math.roundToInt

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

@Keep
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

fun nativeName(language: String) = Locale.forLanguageTag(language).let {
    it.getDisplayLanguage(it).replaceFirstChar(Char::uppercase)
}

@Composable
fun bytes(value: Long) = Formatter.formatShortFileSize(LocalContext.current, value)

typealias SettingsUpdate = ((Settings) -> Settings) -> Unit

@Composable
fun rememberSettings(): Pair<Settings, SettingsUpdate> {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val settings by app.library.settings.collectAsStateWithLifecycle(Settings())
    return settings to { change -> scope.launch { app.library.updateSettings(change) } }
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
    }
}

@Composable
private fun GoalChoice(goal: Int, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
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
        SwitchRow(
            R.string.check_updates,
            R.string.check_updates_hint,
            settings.checkUpdates,
            enabled = !offline,
        ) { value -> update { it.copy(checkUpdates = value) } }
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
fun <T> Choice(
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
fun LanguageChoice(
    selected: String,
    @StringRes title: Int? = null,
    onSelect: (String) -> Unit,
) = Choice(listOf("") + LANGUAGES, selected, onSelect, title) {
    if (it.isEmpty()) stringResource(R.string.language_auto) else it.uppercase()
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
private fun AboutRows() {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val (settings, _) = rememberSettings()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }
    val version =
        stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
    val summary = listOfNotNull(version, status?.let { stringResource(it) })

    if (!settings.isOffline(context)) {
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
