package dev.tn3w.shelf.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*

private const val BACKUP_FILE = "shelf-library.zip"
private val BACKUP_TYPES = arrayOf("application/zip", "application/octet-stream")
private const val CSV_FILE = "shelf-library.csv"

@Composable
fun DataSettings() {
    SettingsGroup(stringResource(R.string.backup), stringResource(R.string.backup_hint)) {
        BackupRows()
    }
    SettingsGroup(
        stringResource(R.string.auto_backup), stringResource(R.string.auto_backup_hint),
    ) { AutomaticBackupRow() }
    SettingsGroup(stringResource(R.string.csv), stringResource(R.string.csv_hint)) {
        CsvRows()
    }
    SettingsGroup(stringResource(R.string.notes), stringResource(R.string.notes_hint)) {
        NotesRows()
    }
}

@Composable
private fun NotesRows() {
    NotesRow(R.string.notes_markdown, SettingsAction.NotesExport, NotesFormat.Markdown)
    NotesRow(R.string.notes_readwise, SettingsAction.ReadwiseExport, NotesFormat.Readwise)
}

@Composable
private fun NotesRow(@StringRes title: Int, action: SettingsAction, format: NotesFormat) {
    val viewModel = settingsViewModel()
    FileRow(
        title,
        action,
        Icons.Rounded.Upload,
        CreateDocument(format.mimeType),
        notesFileName("shelf", format),
        R.string.notes_exported,
        R.string.export_failed,
    ) { viewModel.library.exportNotes(it, format) }
}

@Composable
private fun BackupRows() {
    val viewModel = settingsViewModel()
    FileRow(
        R.string.backup_export,
        SettingsAction.BackupExport,
        Icons.Rounded.Upload,
        CreateDocument("application/zip"),
        BACKUP_FILE,
        R.string.backup_exported,
        R.string.export_failed,
        viewModel.library::exportTo,
    )
    FileRow(
        R.string.backup_import,
        SettingsAction.BackupImport,
        Icons.Rounded.Download,
        OpenDocument(),
        BACKUP_TYPES,
        R.string.backup_done,
        work = viewModel::restoreBackup,
    )
}

@Composable
private fun AutomaticBackupRow() {
    val viewModel = settingsViewModel()
    val settings = loadedSettings() ?: return
    val folder = settings.backupFolder
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let(viewModel::chooseBackupFolder)
    }
    val summary = folder.ifEmpty { null }?.let(::folderName)
        ?: stringResource(R.string.auto_backup_off)
    SettingRow(
        stringResource(R.string.auto_backup_folder),
        summary,
        onClick = { picker.launch(null) },
    ) {
        Toggle(folder.isNotEmpty(), { on ->
            if (on) picker.launch(null) else viewModel.chooseBackupFolder(null)
        })
    }
}

private fun folderName(folder: String): String {
    val id = DocumentsContract.getTreeDocumentId(folder.toUri())
    return id.substringAfter(':').ifEmpty { id }
}

@Composable
private fun <I> FileRow(
    @StringRes title: Int,
    action: SettingsAction,
    icon: ImageVector,
    contract: ActivityResultContract<I, Uri?>,
    input: I,
    @StringRes success: Int,
    @StringRes failure: Int = R.string.backup_failed,
    work: suspend (Uri) -> Boolean,
) {
    val viewModel = settingsViewModel()
    val (_, status) = actionStatus(action)
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.runFileAction(action, success, failure) { work(uri) }
    }
    ActionRow(title, icon, status) {
        launcher.launch(input)
    }
}

@Composable
private fun CsvRows() {
    val viewModel = settingsViewModel()
    val progress by viewModel.csvProgress.collectAsStateWithLifecycle()
    val report by viewModel.csvReport.collectAsStateWithLifecycle()
    val (_, csvStatus) = actionStatus(SettingsAction.CsvImport)
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::importCsv)
    }
    val importStatus =
        progress?.let { stringResource(R.string.csv_progress, (it * 100).toInt()) }
            ?: csvStatus
    ActionRow(
        R.string.csv_import, Icons.Rounded.Download, importStatus, progress != null,
    ) { import.launch(arrayOf("*/*")) }
    FileRow(
        R.string.csv_export,
        SettingsAction.CsvExport,
        Icons.Rounded.Upload,
        CreateDocument("text/csv"),
        CSV_FILE,
        R.string.csv_exported,
        R.string.export_failed,
        viewModel.library::exportCsv,
    )
    report?.let { CsvReportDialog(it, viewModel::dismissCsvReport) }
}

@Composable
private fun CsvReportDialog(report: CsvReport, onDismiss: () -> Unit) {
    val found = report.total - report.missing.size
    Sheet(onDismiss, stringResource(R.string.csv_imported)) {
        Text(
            stringResource(R.string.csv_found, found, report.total),
            style = Look.type.body,
        )
        if (report.missing.isNotEmpty()) {
            Eyebrow(stringResource(R.string.csv_missing), Modifier.padding(top = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                report.missing.forEach { Hint(it) }
            }
        }
        Pill(stringResource(R.string.done), Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}
