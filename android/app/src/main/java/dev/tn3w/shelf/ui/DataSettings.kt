package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
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
    SettingsGroup(stringResource(R.string.csv), stringResource(R.string.csv_hint)) {
        CsvRows()
    }
}

@Composable
private fun BackupRows() {
    val viewModel = settingsViewModel()
    FileRow(
        R.string.backup_export,
        SettingsAction.BackupExport,
        Icons.Outlined.Upload,
        CreateDocument("application/zip"),
        BACKUP_FILE,
        R.string.backup_exported,
        R.string.export_failed,
        viewModel.library::exportTo,
    )
    FileRow(
        R.string.backup_import,
        SettingsAction.BackupImport,
        Icons.Outlined.Download,
        OpenDocument(),
        BACKUP_TYPES,
        R.string.backup_done,
        work = viewModel::restoreBackup,
    )
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
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.runFileAction(action, success, failure) { work(uri) }
    }
    ActionRow(title, icon, statuses[action]?.let { stringResource(it) }) {
        launcher.launch(input)
    }
}

@Composable
private fun CsvRows() {
    val viewModel = settingsViewModel()
    val progress by viewModel.csvProgress.collectAsStateWithLifecycle()
    val report by viewModel.csvReport.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::importCsv)
    }
    val importStatus =
        progress?.let { stringResource(R.string.csv_progress, (it * 100).toInt()) }
            ?: statuses[SettingsAction.CsvImport]?.let { stringResource(it) }
    ActionRow(
        R.string.csv_import, Icons.Outlined.Download, importStatus, progress != null,
    ) { import.launch(arrayOf("*/*")) }
    FileRow(
        R.string.csv_export,
        SettingsAction.CsvExport,
        Icons.Outlined.Upload,
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
                report.missing.forEach { Hint(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}
