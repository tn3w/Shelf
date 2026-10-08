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
import dev.tn3w.shelf.*
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

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
