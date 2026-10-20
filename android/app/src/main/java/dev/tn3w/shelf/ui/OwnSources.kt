package dev.tn3w.shelf.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tn3w.shelf.BuildConfig
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.UUID

private val SOURCE_TYPES =
    arrayOf("application/json", "text/plain", "application/octet-stream")
private val SEARCH_FIELDS = listOf("title", "author", "cover")
private const val DEBUG_FILE = "shelf-source-debug.txt"
private const val TRACE_LINE_CHARACTERS = 3_000
private val EDITOR_MODES = mapOf(
    false to R.string.free_books_form,
    true to R.string.free_books_file,
)

class SourcesViewModel(application: Application) : ShelfViewModel(application) {
    val imported = MutableStateFlow<Result<Imported>?>(null)
    val exportFailed = MutableStateFlow(false)
    val lines = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    val report = MutableStateFlow<String?>(null)
    val reportStatus = MutableStateFlow<Int?>(null)

    fun import(uri: Uri) {
        detached {
            imported.value = runSuspendCatching { checkNotNull(sources.import(uri)) }
        }
    }

    fun export(uri: Uri) {
        detached { exportFailed.value = !sources.export(uri) }
    }

    fun save(initial: Recipe, ready: Recipe) {
        detached {
            val id = initial.id.ifEmpty { UUID.randomUUID().toString() }
            sources.save(ready.copy(id = id))
            if (initial.id.isNotEmpty()) return@detached
            library.updateSettings { it.copy(providers = it.providers + id) }
        }
    }

    fun remove(id: String) {
        detached {
            sources.remove(id)
            library.updateSettings { it.copy(providers = it.providers - id) }
        }
    }

    fun test(recipe: Recipe, query: Query, debug: Boolean) {
        running.value = true
        reportStatus.value = null
        lines.value = emptyList()
        viewModelScope.launch {
            trace(recipe, query, debug).collect { line -> lines.update { it + line } }
            running.value = false
            if (debug) report.value = debugReport(recipe, query, lines.value)
        }
    }

    fun saveReport(uri: Uri?) {
        val text = report.value ?: return
        report.value = null
        if (uri == null) return
        detached {
            val done = app.writeTo(uri) { it.write(text.toByteArray()) }
            reportStatus.value =
                if (done) R.string.free_books_debug_saved else R.string.export_failed
        }
    }
}

@Composable
fun OwnSourceRows(settings: Settings, update: SettingsUpdate, enabled: Boolean) {
    val viewModel: SourcesViewModel = viewModel()
    val recipes by viewModel.sources.own.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val exportFailed by viewModel.exportFailed.collectAsStateWithLifecycle()
    var editing by rememberSaveable(stateSaver = jsonSaver<Recipe?>()) {
        mutableStateOf(null)
    }
    val importer = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val exporter = rememberLauncherForActivityResult(CreateDocument("application/json")) {
        it?.let(viewModel::export)
    }
    val status = imported?.fold(
        onSuccess = {
            val (added, total) = it.added.size to it.total
            pluralStringResource(R.plurals.free_books_imported, total, added, total)
        },
        onFailure = { stringResource(R.string.import_failed) },
    )

    recipes.forEach { recipe ->
        ProviderSwitch(Provider(recipe), settings, update, enabled) { editing = recipe }
    }
    ActionRow(R.string.free_books_add, Icons.Outlined.Add) { editing = STARTER_RECIPE }
    ActionRow(R.string.free_books_import, Icons.Outlined.FileOpen, status) {
        importer.launch(SOURCE_TYPES)
    }
    if (recipes.isNotEmpty()) {
        val secrets = recipes.any { it.headers.isNotEmpty() }
        val exportStatus = when {
            exportFailed -> stringResource(R.string.export_failed)
            secrets -> stringResource(R.string.free_books_export_headers)
            else -> null
        }
        ActionRow(R.string.free_books_export, Icons.Outlined.Upload, exportStatus) {
            exporter.launch(SOURCES_FILE)
        }
    }
    editing?.let { RecipeEditor(it) { editing = null } }
}

@Composable
private fun RecipeEditor(initial: Recipe, onClose: () -> Unit) {
    val viewModel: SourcesViewModel = viewModel()
    var recipe by rememberSaveable(stateSaver = jsonSaver()) { mutableStateOf(initial) }
    var text by rememberSaveable { mutableStateOf(initial.toText()) }
    var asFile by rememberSaveable { mutableStateOf(false) }
    val parsed = remember(text, recipe, asFile) {
        if (asFile) parseRecipe(text) else Result.success(recipe)
    }
    val problems = remember(parsed) {
        parsed.fold({ it.problems() }, { listOf(firstLine(it)) })
    }

    fun save() {
        val ready = parsed.getOrNull() ?: return
        viewModel.save(initial, ready)
        onClose()
    }
    fun remove() {
        viewModel.remove(initial.id)
        onClose()
    }
    fun showFile(file: Boolean) {
        if (file) text = recipe.toText()
        if (!file) recipe = parsed.getOrNull() ?: return
        asFile = file
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.imePadding()) {
                EditorBar(initial, problems.isEmpty(), onClose, ::save)
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenPadding)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Choice(EDITOR_MODES.keys.toList(), asFile, ::showFile) {
                        stringResource(EDITOR_MODES.getValue(it))
                    }
                    if (asFile) {
                        RecipeText(text) { text = it }
                    } else {
                        RecipeForm(recipe) { recipe = it }
                    }
                    problems.forEach { Problem(it) }
                    TestPanel(parsed.getOrNull()?.takeIf { problems.isEmpty() })
                    if (initial.id.isNotEmpty()) RemoveButton(::remove)
                }
            }
        }
    }
}

@Composable
private fun codeStyle() =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun EditorBar(
    recipe: Recipe,
    valid: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.Outlined.Close, stringResource(R.string.cancel), onClose)
        val isNew = recipe.id.isEmpty()
        val title = if (isNew) R.string.free_books_add else R.string.provider_own
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave, enabled = valid) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun RecipeText(text: String, onChange: (String) -> Unit) = OutlinedTextField(
    text,
    onChange,
    Modifier.fillMaxWidth(),
    textStyle = codeStyle(),
    minLines = 12,
)

@Composable
private fun RecipeForm(recipe: Recipe, onChange: (Recipe) -> Unit) {
    TextInput(R.string.free_books_name, recipe.name) { onChange(recipe.copy(name = it)) }
    Heading(R.string.search)
    StepForm(recipe.search, SEARCH_FIELDS) { onChange(recipe.copy(search = it)) }
    recipe.steps.forEachIndexed { index, step ->
        Heading(stringResource(R.string.free_books_step, index + 1)) {
            val steps = recipe.steps.toMutableList().apply { removeAt(index) }
            onChange(recipe.copy(steps = steps))
        }
        StepForm(step, emptyList()) { changed ->
            val steps = recipe.steps.toMutableList().apply { set(index, changed) }
            onChange(recipe.copy(steps = steps))
        }
    }
    AddButton(R.string.free_books_step_add) {
        onChange(recipe.copy(steps = recipe.steps + RecipeStep("")))
    }
    Heading(R.string.download)
    TextInput(R.string.free_books_url, recipe.download, monospace = true) {
        onChange(recipe.copy(download = it))
    }
    Choice(BOOK_FORMATS, recipe.format, { onChange(recipe.copy(format = it)) }) {
        it.uppercase()
    }
    LanguageChips(recipe.languages) { onChange(recipe.copy(languages = it)) }
    Heading(R.string.free_books_headers)
    HeaderRows(recipe.headers) { onChange(recipe.copy(headers = it)) }
    Hint(stringResource(R.string.free_books_form_help))
}

@Composable
private fun StepForm(
    step: RecipeStep,
    fixed: List<String>,
    onChange: (RecipeStep) -> Unit,
) {
    TextInput(R.string.free_books_url, step.url, monospace = true) {
        onChange(step.copy(url = it))
    }
    TextInput(R.string.free_books_body, step.body.orEmpty(), monospace = true) {
        onChange(step.copy(body = it.ifEmpty { null }))
    }
    if (fixed.isNotEmpty()) {
        RuleInput(stringResource(R.string.free_books_items), step.items ?: Pick()) {
            onChange(step.copy(items = it))
        }
    }
    fun change(name: String, pick: Pick?) {
        val fields = step.fields.toMutableMap()
        if (pick == null) fields.remove(name) else fields[name] = pick
        onChange(step.copy(fields = fields))
    }
    fixed.forEach { name ->
        RuleInput(name, step.fields[name] ?: Pick()) {
            change(name, it.takeUnless(Pick::isEmpty))
        }
    }
    step.fields.keys.filterNot { it in fixed }.forEach { name ->
        RuleInput(name, step.fields.getValue(name), onRemove = { change(name, null) }) {
            change(name, it)
        }
    }
    VariableAdder(step.fields.keys + fixed) { change(it, Pick()) }
}

@Composable
private fun RuleInput(
    name: String,
    pick: Pick,
    onRemove: (() -> Unit)? = null,
    onChange: (Pick) -> Unit,
) {
    fun clean(value: String) = value.ifEmpty { null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            onRemove?.let {
                IconAction(Icons.Outlined.Close, stringResource(R.string.remove), it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleField("CSS", pick.css) { onChange(pick.copy(css = clean(it))) }
            RuleField("JSON", pick.json) { onChange(pick.copy(json = clean(it))) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleField(stringResource(R.string.free_books_attribute), pick.attr) {
                onChange(pick.copy(attr = clean(it)))
            }
            RuleField("Regex", pick.regex) { onChange(pick.copy(regex = clean(it))) }
        }
    }
}

@Composable
private fun RowScope.RuleField(
    label: String,
    value: String?,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value.orEmpty(),
    onChange,
    Modifier.weight(1f),
    label = { Text(label) },
    singleLine = true,
    textStyle = codeStyle(),
)

@Composable
private fun TextInput(
    @StringRes label: Int,
    value: String,
    monospace: Boolean = false,
    onChange: (String) -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        textStyle = if (monospace) codeStyle() else style,
    )
}

@Composable
private fun Heading(@StringRes title: Int) = Heading(stringResource(title))

@Composable
private fun Heading(title: String, onRemove: (() -> Unit)? = null) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        onRemove?.let {
            IconAction(Icons.Outlined.Delete, stringResource(R.string.remove), it)
        }
    }
}

@Composable
private fun AddButton(@StringRes label: Int, onClick: () -> Unit) =
    TextButton(onClick = onClick) { IconLabel(Icons.Outlined.Add, label) }

@Composable
private fun VariableAdder(taken: Set<String>, onAdd: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val invalid = stringResource(R.string.free_books_variable_invalid)
    val duplicate = stringResource(R.string.free_books_variable_taken)
    fun problem(name: String) = when {
        !VARIABLE_NAME.matches(name) -> invalid
        name in taken || name in QUERY_VARIABLES -> duplicate
        else -> null
    }
    AddButton(R.string.free_books_variable_add) { adding = true }
    if (!adding) return
    NameDialog(
        R.string.free_books_variable_add,
        problem = ::problem,
        onDismiss = { adding = false },
    ) { name, _ ->
        adding = false
        onAdd(name)
    }
}

private val VARIABLE_NAME = Regex("""\w+""")

@Composable
private fun NameDialog(
    @StringRes title: Int,
    @StringRes valueLabel: Int? = null,
    problem: (String) -> String? = { null },
    onDismiss: () -> Unit,
    onConfirm: (name: String, value: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    val error = problem(trimmed).takeIf { trimmed.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.free_books_name)) },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                valueLabel?.let { label ->
                    OutlinedTextField(
                        value,
                        { value = it },
                        singleLine = true,
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
        confirmButton = {
            val valid = trimmed.isNotEmpty() && error == null
            TextButton(onClick = { onConfirm(trimmed, value) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun HeaderRows(
    headers: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    headers.forEach { (name, value) ->
        SettingRow(name, value) {
            IconAction(Icons.Outlined.Close, stringResource(R.string.remove)) {
                onChange(headers - name)
            }
        }
    }
    AddButton(R.string.free_books_header_add) { adding = true }
    if (!adding) return
    NameDialog(
        R.string.free_books_header_add,
        R.string.free_books_header_value,
        onDismiss = { adding = false },
    ) { name, value ->
        adding = false
        onChange(headers + (name to value))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChips(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Text(
        stringResource(R.string.free_books_languages),
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGUAGES.forEach { language ->
            val on = language in selected
            FilterChip(
                selected = on,
                onClick = {
                    onChange(if (on) selected - language else selected + language)
                },
                label = { Text(nativeName(language)) },
            )
        }
    }
}

@Composable
private fun Problem(text: String) = Hint(text, color = MaterialTheme.colorScheme.error)

@Composable
private fun TestPanel(recipe: Recipe?) {
    val viewModel: SourcesViewModel = viewModel()
    val settings = loadedSettings() ?: return
    val offline = settings.isOffline(LocalContext.current)
    val sampleTitle = stringResource(R.string.free_books_test_title)
    val sampleAuthor = stringResource(R.string.free_books_test_author)
    var title by rememberSaveable { mutableStateOf(sampleTitle) }
    var author by rememberSaveable { mutableStateOf(sampleAuthor) }
    var isbn by rememberSaveable { mutableStateOf("") }
    val running by viewModel.running.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val saved by viewModel.reportStatus.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val saver = rememberLauncherForActivityResult(CreateDocument("text/plain")) {
        viewModel.saveReport(it)
    }
    LaunchedEffect(report) { if (report != null) saver.launch(DEBUG_FILE) }

    fun run(ready: Recipe, debug: Boolean) {
        val isbns = listOf(isbn.trim()).filter { it.isNotEmpty() }
            .map { normalizedIsbn(it) ?: it }
        val query = Query(title, author, viewModel.bookLanguage(settings), isbns)
        viewModel.test(ready, query, debug)
    }

    Heading(R.string.free_books_test)
    Hint(stringResource(R.string.free_books_test_hint))
    TextInput(R.string.title, title) { title = it }
    TextInput(R.string.author, author) { author = it }
    TextInput(R.string.free_books_isbn, isbn, monospace = true) { isbn = it }
    val ready = recipe?.takeIf {
        !offline && !running && (title.isNotBlank() || isbn.isNotBlank())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ ready?.let { run(it, debug = false) } }, enabled = ready != null) {
            IconLabel(Icons.Outlined.PlayArrow, R.string.free_books_test)
        }
        OutlinedButton({
            ready?.let { run(it, debug = true) }
        }, enabled = ready != null) {
            IconLabel(Icons.Outlined.BugReport, R.string.free_books_debug)
        }
    }
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
    saved?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    lines.forEach { Text(it.take(TRACE_LINE_CHARACTERS), style = codeStyle()) }
}

private fun describe(error: Throwable) =
    "${error::class.java.simpleName}: ${firstLine(error)}"

private fun debugReport(recipe: Recipe, query: Query, lines: List<String>) = buildString {
    appendLine("Shelf ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) source debug")
    appendLine("Time: ${OffsetDateTime.now()}")
    appendLine("Query: ${query.title} · ${query.author} · ${query.language}")
    appendLine()
    appendLine("Recipe (headers, query values, bodies and cookies hidden):")
    appendLine(recipe.redacted().toText())
    appendLine()
    appendLine("Trace:")
    lines.forEach { appendLine(recipe.redact(it)) }
}

private fun trace(recipe: Recipe, query: Query, verbose: Boolean) = channelFlow {
    val source = Provider(recipe, log = { trySend(it) }, verbose = verbose)
    runSuspendCatching { source.probe(query) }.onFailure { send("✕ ${describe(it)}") }
}.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

@Composable
private fun RemoveButton(onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.padding(top = 12.dp),
) {
    IconLabel(Icons.Outlined.Delete, R.string.remove)
}
