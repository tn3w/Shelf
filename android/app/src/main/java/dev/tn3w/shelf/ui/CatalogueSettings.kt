package dev.tn3w.shelf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*

@Composable
fun installedLanguages(): Set<String> {
    val viewModel = settingsViewModel()
    val revision by viewModel.packRevision.collectAsStateWithLifecycle()
    return produceState(emptySet(), revision) {
        value = viewModel.installedLanguages()
    }.value
}

@Composable
fun packInfos(language: String): List<PackInfo>? {
    val viewModel = settingsViewModel()
    val revision by viewModel.packRevision.collectAsStateWithLifecycle()
    return produceState<List<PackInfo>?>(null, language, revision) {
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
        LanguageChips(
            listOf("") + LANGUAGES,
            settings.language,
            installed = installedLanguages(),
        ) { chosen -> update { it.copy(language = chosen) } }
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
    val (storage, release) = produceState(0L to "", packs, language) {
        value = viewModel.storage(language)
    }.value
    val details = listOfNotNull(
        release.ifEmpty { null }?.let { stringResource(R.string.release_date, it) },
        stringResource(R.string.storage_used, bytes(storage)),
    )
    val note = stringResource(LIBRARY_NOTES[language] ?: R.string.open_library_note)
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
            Rule(Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp))
        }
        if (!offline && pendingBytes > 0) {
            ActionRow(
                R.string.download_all_short, Icons.Rounded.Download, bytes(pendingBytes),
            ) { pending.forEach { viewModel.downloadPack(language, it.pack) } }
        } else if (storage > 0) {
            ActionRow(R.string.delete_all, Icons.Rounded.Delete) {
                viewModel.removePacks(language)
            }
        }
        if (!offline) CatalogueCheck(language)
    }
}

@Composable
private fun CatalogueCheck(language: String) {
    val viewModel = settingsViewModel()
    val (busy, status) = actionStatus(SettingsAction.CatalogueCheck)
    ActionRow(R.string.check_catalogue, Icons.Rounded.Refresh, status, busy) {
        viewModel.checkCatalogue(language)
    }
}

private val LIBRARY_NOTES = mapOf(
    "de" to R.string.dnb_note,
    "et" to R.string.erb_note,
    "sv" to R.string.libris_note,
    "pl" to R.string.bnp_note,
    "cs" to R.string.nkp_note,
    "ko" to R.string.nlk_note,
    "fi" to R.string.fennica_note,
)

@Composable
fun SourceRows(language: String, settings: Settings) {
    val viewModel = settingsViewModel()
    val (_, importStatus) = actionStatus(SettingsAction.CatalogueImport)
    var editing by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importCatalogue(language, uris)
    }

    val source = settings.catalogueSource.ifEmpty { REPOSITORY }
    LinkRow(stringResource(R.string.catalogue_source), source.removePrefix("https://")) {
        editing = true
    }
    ActionRow(
        R.string.import_catalogue,
        Icons.Rounded.FileOpen,
        importStatus,
    ) { picker.launch(arrayOf("*/*")) }
    if (!editing) return
    AddressSheet(
        R.string.catalogue_source,
        R.string.catalogue_source_hint,
        settings.catalogueSource,
        REPOSITORY,
        ::isValidSource,
        onDismiss = { editing = false },
    ) { viewModel.changeCatalogueSource(it) }
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
        PackAction(label, download, info.state, offline, onDownload, onRemove)
    }
}
