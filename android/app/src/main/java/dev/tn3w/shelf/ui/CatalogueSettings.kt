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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*

@Composable
fun packInfos(language: String): List<PackInfo>? {
    val viewModel = settingsViewModel()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val refreshes by viewModel.refreshes.collectAsStateWithLifecycle()
    val keys = arrayOf(language, loaded, downloads.size, refreshes)
    return produceState<List<PackInfo>?>(null, *keys) {
        value = viewModel.packs(language)
    }.value
}

@Composable
fun CatalogueSettings() {
    val viewModel = settingsViewModel()
    val (settings, update) = rememberSettings() ?: return
    val language = viewModel.bookLanguage(settings)
    val packs = packInfos(language)

    SettingsGroup(
        stringResource(R.string.catalogue_language),
        stringResource(R.string.book_language_hint),
    ) {
        LanguageChoice(settings.language) { chosen ->
            update { it.copy(language = chosen) }
        }
    }

    PackList(language, packs, settings.isOffline(LocalContext.current))
    SettingsGroup(
        stringResource(R.string.own_sources), stringResource(R.string.own_sources_hint),
    ) { SourceRows(language, settings) }
}

@Composable
private fun PackList(language: String, packs: List<PackInfo>?, offline: Boolean) {
    val viewModel = settingsViewModel()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val (storage, total, release) = produceState(Triple(0L, 0L, ""), packs, language) {
        value = viewModel.storage(language)
    }.value
    val details = listOfNotNull(
        release.ifEmpty { null }?.let { stringResource(R.string.release_date, it) },
        stringResource(R.string.storage_used, bytes(storage)),
    )
    val note = stringResource(R.string.open_library_note)
    val hint = details.joinToString(" · ") + "\n" + note
    val title = stringResource(R.string.packs_language, nativeName(language))
    SettingsGroup(title, hint) {
        if (packs == null) return@SettingsGroup LoadingBar()
        packs.forEach { info ->
            PackRow(
                info,
                downloads["$language-${info.pack}"],
                offline,
                onDownload = { viewModel.downloadPack(language, info.pack) },
                onRemove = { viewModel.removePacks(language, info.pack) },
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
            ) { pending.forEach { viewModel.downloadPack(language, it.pack) } }
        } else if (storage > 0) {
            ActionRow(R.string.delete_all, Icons.Outlined.Delete) {
                viewModel.removePacks(language)
            }
        }
        if (total > storage) {
            ActionRow(R.string.delete_all_languages, Icons.Outlined.Delete) {
                viewModel.removePacks(language, everywhere = true)
            }
        }
        if (!offline) CatalogueCheck(language)
    }
}

@Composable
private fun CatalogueCheck(language: String) {
    val viewModel = settingsViewModel()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    ActionRow(
        R.string.check_catalogue,
        Icons.Outlined.Refresh,
        statuses[R.string.check_catalogue]?.let { stringResource(it) },
        R.string.check_catalogue in busy,
    ) { viewModel.checkCatalogue(language) }
}

private class SourceField(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val default: String,
    val current: (Settings) -> String,
    val isValid: (String) -> Boolean,
    val save: SettingsViewModel.(String) -> Unit,
)

private val CATALOGUE_SOURCE = SourceField(
    R.string.catalogue_source,
    R.string.catalogue_source_hint,
    REPOSITORY,
    Settings::catalogueSource,
    ::isValidSource,
) { source -> changeCatalogueSource(source) }

private val COVER_SOURCE = SourceField(
    R.string.cover_source,
    R.string.cover_source_hint,
    COVERS,
    Settings::coverSource,
    ::isValidCoverSource,
) { source -> updateSettings { it.copy(coverSource = source) } }

private val DNB_COVER_SOURCE = SourceField(
    R.string.dnb_cover_source,
    R.string.dnb_cover_source_hint,
    DNB_COVERS,
    Settings::dnbCoverSource,
    ::isValidCoverSource,
) { source -> updateSettings { it.copy(dnbCoverSource = source) } }

private val DIGAR_COVER_SOURCE = SourceField(
    R.string.digar_cover_source,
    R.string.digar_cover_source_hint,
    DIGAR_COVERS,
    Settings::digarCoverSource,
    ::isValidCoverSource,
) { source -> updateSettings { it.copy(digarCoverSource = source) } }

private val SOURCE_FIELDS =
    listOf(CATALOGUE_SOURCE, COVER_SOURCE, DNB_COVER_SOURCE, DIGAR_COVER_SOURCE)

private val LIBRARY_COVER_SOURCES = mapOf(
    "de" to DNB_COVER_SOURCE,
    "et" to DIGAR_COVER_SOURCE,
)

@Composable
fun SourceRows(language: String, settings: Settings) {
    val viewModel = settingsViewModel()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importCatalogue(language, uris)
    }

    val libraryCovers = LIBRARY_COVER_SOURCES[language]
    listOfNotNull(CATALOGUE_SOURCE, COVER_SOURCE, libraryCovers).forEach { field ->
        val value = field.current(settings).ifEmpty { field.default }
        LinkRow(stringResource(field.title), value.removePrefix("https://")) {
            editing = field.title
        }
    }
    ActionRow(
        R.string.import_catalogue,
        Icons.Outlined.FileOpen,
        statuses[R.string.import_catalogue]?.let { stringResource(it) },
    ) { picker.launch(arrayOf("*/*")) }
    SOURCE_FIELDS.firstOrNull { it.title == editing }?.let { field ->
        SourceDialog(field, field.current(settings), onDismiss = { editing = null }) {
            editing = null
            field.save(viewModel, it)
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
    var text by rememberSaveable { mutableStateOf(current) }
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
