package dev.tn3w.shelf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
fun packInfos(language: String, refreshes: Int = 0): List<PackInfo>? {
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
fun CatalogueSettings() {
    val app = shelfApp()
    val (settings, update) = rememberSettings() ?: return
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
private fun PackList(
    language: String,
    packs: List<PackInfo>?,
    offline: Boolean,
    onChecked: () -> Unit,
) {
    val app = shelfApp()
    val downloads by app.downloads.collectAsStateWithLifecycle()
    val (storage, total, release) = produceState(Triple(0L, 0L, ""), packs, language) {
        value = withContext(Dispatchers.IO) {
            val month = app.packs.months()[language].orEmpty()
            Triple(app.packs.storageBytes(language), app.packs.storageBytes(), month)
        }
    }.value
    val details = listOfNotNull(
        release.ifEmpty { null }?.let { stringResource(R.string.release_date, it) },
        stringResource(R.string.storage_used, bytes(storage)),
    )
    val note = stringResource(R.string.open_library_note)
    val hint = details.joinToString(" · ") + "\n" + note
    val title = "${stringResource(R.string.packs)} · ${nativeName(language)}"
    SettingsGroup(title, hint) {
        if (packs == null) return@SettingsGroup LoadingBar()
        packs.forEach { info ->
            PackRow(
                info,
                downloads["$language-${info.pack}"],
                offline,
                onDownload = { app.download(language, info.pack) },
                onRemove = { app.removePacks(language, info.pack) },
            )
        }
        val pending = packs.filter { it.state != PackState.Installed }
        val pendingBytes = pending.sumOf { it.bytes }
        if (!offline || storage > 0) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (!offline && pendingBytes > 0) {
            ActionRow(
                R.string.download_all_short, Icons.Outlined.Download, bytes(pendingBytes),
            ) { pending.forEach { app.download(language, it.pack) } }
        } else if (storage > 0) {
            ActionRow(R.string.delete_all, Icons.Outlined.Delete) {
                app.removePacks(language)
            }
        }
        if (total > storage) {
            ActionRow(R.string.delete_all_languages, Icons.Outlined.Delete) {
                app.removePacks(language, everywhere = true)
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

private class SourceField(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val default: String,
    val current: (Settings) -> String,
    val isValid: (String) -> Boolean,
    val save: suspend ShelfApp.(String) -> Unit,
)

private val CATALOGUE_SOURCE = SourceField(
    R.string.catalogue_source,
    R.string.catalogue_source_hint,
    REPOSITORY,
    Settings::catalogueSource,
    ::isValidSource,
) { source ->
    changeSource(source)
    if (!library.settings.first().isOffline(this)) runCatching { packs.refreshManifest() }
}

private val COVER_SOURCE = SourceField(
    R.string.cover_source,
    R.string.cover_source_hint,
    COVERS,
    Settings::coverSource,
    ::isValidCoverSource,
) { source -> library.updateSettings { it.copy(coverSource = source) } }

private val DNB_COVER_SOURCE = SourceField(
    R.string.dnb_cover_source,
    R.string.dnb_cover_source_hint,
    DNB_COVERS,
    Settings::dnbCoverSource,
    ::isValidCoverSource,
) { source -> library.updateSettings { it.copy(dnbCoverSource = source) } }

@Composable
fun SourceRows(
    language: String,
    settings: Settings,
    onChange: () -> Unit,
    onImported: (complete: String?) -> Unit,
) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SourceField?>(null) }
    var status by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = app.importCatalogue(language, uris)
            val complete = (listOf(language) + (LANGUAGES - language)).firstOrNull {
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
    fun save(field: SourceField, value: String) = scope.launch {
        editing = null
        field.save(app, value)
        onChange()
    }

    val german = DNB_COVER_SOURCE.takeIf { language == "de" }
    listOfNotNull(CATALOGUE_SOURCE, COVER_SOURCE, german).forEach { field ->
        val value = field.current(settings).ifEmpty { field.default }
        LinkRow(stringResource(field.title), value.removePrefix("https://")) {
            editing = field
        }
    }
    ActionRow(
        R.string.import_catalogue,
        Icons.Outlined.FileOpen,
        status?.let { stringResource(it) },
    ) { picker.launch(arrayOf("*/*")) }
    editing?.let { field ->
        SourceDialog(field, field.current(settings), onDismiss = { editing = null }) {
            save(field, it)
        }
    }
}

@Composable
private fun SourceDialog(
    field: SourceField,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    val valid = field.isValid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(field.hint))
                OutlinedTextField(
                    text,
                    { text = it.trim() },
                    placeholder = { Text(field.default) },
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
    val label = stringResource(PACKS.getValue(info.pack))
    val size = if (info.bytes > 0) " · ${bytes(info.bytes)}" else ""
    SettingRow(label, stringResource(info.state.label) + size) {
        PackAction(label, download, info.state, info.pack, offline, onDownload, onRemove)
    }
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
            PackButton(Icons.Outlined.ErrorOutline, R.string.retry_item, onDownload)

        state == PackState.Available ->
            PackButton(Icons.Outlined.Download, R.string.download_item, onDownload)

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
