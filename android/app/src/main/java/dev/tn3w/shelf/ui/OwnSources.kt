package dev.tn3w.shelf.ui

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
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.BuildConfig
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.UUID

private val FORMATS = listOf("epub", "pdf", "fb2", "cbz", "txt")
private val SOURCE_TYPES =
    arrayOf("application/json", "text/plain", "application/octet-stream")
private val SEARCH_FIELDS = listOf("title", "author", "cover")
private const val DEBUG_FILE = "shelf-source-debug.txt"
private const val TRACE_LINE_CHARACTERS = 3_000
private val EDITOR_MODES = mapOf(
    false to R.string.free_books_form,
    true to R.string.free_books_file,
)

@Composable
fun OwnSourceRows(settings: Settings, update: SettingsUpdate, enabled: Boolean) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val recipes by app.ownSources.recipes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.import_failed)
    val importer = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = app.ownSources.import(uri)
            if (imported == null) {
                status = failed
                return@launch
            }
            val ids = imported.added.map { it.id }
            update { it.copy(providers = it.providers + ids) }
            val total = imported.total
            status = app.resources
                .getQuantityString(R.plurals.free_books_imported, total, ids.size, total)
        }
    }
    val exporter = rememberLauncherForActivityResult(CreateDocument("application/json")) {
        if (it != null) scope.launch { app.ownSources.export(it) }
    }

    recipes.forEach { recipe ->
        ProviderSwitch(OwnSource(recipe), settings, update, enabled) { editing = recipe }
    }
    ActionRow(R.string.free_books_add, Icons.Outlined.Add) { editing = STARTER_RECIPE }
    ActionRow(R.string.free_books_import, Icons.Outlined.FileOpen, status) {
        importer.launch(SOURCE_TYPES)
    }
    if (recipes.isNotEmpty()) {
        ActionRow(R.string.free_books_export, Icons.Outlined.Upload) {
            exporter.launch(SOURCES_FILE)
        }
    }
    editing?.let { RecipeEditor(it, update) { editing = null } }
}

@Composable
private fun RecipeEditor(initial: Recipe, update: SettingsUpdate, onClose: () -> Unit) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    var recipe by remember { mutableStateOf(initial) }
    var text by remember { mutableStateOf(initial.toText()) }
    var asFile by remember { mutableStateOf(false) }
    val parsed = if (asFile) parseRecipe(text) else Result.success(recipe)
    val problems = parsed.fold({ it.problems() }, { listOf(firstLine(it)) })

    fun save() = scope.launch {
        val ready = parsed.getOrNull() ?: return@launch
        val id = initial.id.ifEmpty { UUID.randomUUID().toString() }
        app.ownSources.save(ready.copy(id = id))
        if (initial.id.isEmpty()) update { it.copy(providers = it.providers + id) }
        onClose()
    }
    fun remove() = scope.launch {
        app.ownSources.remove(initial.id)
        update { it.copy(providers = it.providers - initial.id) }
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
                        RecipeForm(recipe) {
                            recipe =
                                it
                        }
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

private fun firstLine(error: Throwable) =
    error.message?.lineSequence()?.firstOrNull().orEmpty().take(200)

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
        val title = R.string.free_books_add.takeIf { recipe.id.isEmpty() }
            ?: R.string.provider_own
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
            onChange(
                recipe.copy(
                    steps = recipe.steps.filterIndexed { at, _ ->
                        at != index
                    },
                ),
            )
        }
        StepForm(step, emptyList()) { changed ->
            onChange(
                recipe.copy(
                    steps = recipe.steps.mapIndexed { at, it ->
                        if (at ==
                            index
                        ) {
                            changed
                        } else {
                            it
                        }
                    },
                ),
            )
        }
    }
    AddButton(R.string.free_books_step_add) {
        onChange(
            recipe.copy(
                steps =
                recipe.steps + RecipeStep(""),
            ),
        )
    }
    Heading(R.string.download)
    TextInput(R.string.free_books_url, recipe.download, monospace = true) {
        onChange(recipe.copy(download = it))
    }
    Choice(FORMATS, recipe.format, {
        onChange(recipe.copy(format = it))
    }) { it.uppercase() }
    LanguageChips(recipe.languages) { onChange(recipe.copy(languages = it)) }
    Heading(R.string.free_books_headers)
    HeaderRows(recipe.headers) { onChange(recipe.copy(headers = it)) }
    Text(
        stringResource(R.string.free_books_form_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        val others = step.fields - name
        onChange(
            step.copy(
                fields = if (pick ==
                    null
                ) {
                    others
                } else {
                    step.fields + (name to pick)
                },
            ),
        )
    }
    fixed.forEach { name ->
        RuleInput(name, step.fields[name] ?: Pick()) {
            change(name, it.takeIf(Pick::isSet))
        }
    }
    step.fields.keys.filterNot { it in fixed }.forEach { name ->
        RuleInput(name, step.fields.getValue(name), onRemove = { change(name, null) }) {
            change(name, it)
        }
    }
    VariableAdder(step.fields.keys + fixed) { change(it, Pick()) }
}

private val Pick.isSet
    get() = listOfNotNull(css, json, attr, regex).isNotEmpty()

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
    TextButton(onClick = onClick) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Text(stringResource(label), Modifier.padding(start = 8.dp))
    }

@Composable
private fun VariableAdder(taken: Set<String>, onAdd: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    AddButton(R.string.free_books_variable_add) { adding = true }
    if (!adding) return
    NameDialog(R.string.free_books_variable_add, onDismiss = {
        adding = false
    }) { name, _ ->
        if (!VARIABLE_NAME.matches(name) || name in taken) return@NameDialog
        adding = false
        onAdd(name)
    }
}

private val VARIABLE_NAME = Regex("""\w+""")

@Composable
private fun NameDialog(
    @StringRes title: Int,
    @StringRes valueLabel: Int? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, value: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = {
                    Text(stringResource(R.string.free_books_name))
                })
                valueLabel?.let { label ->
                    OutlinedTextField(value, { value = it }, singleLine = true, label = {
                        Text(stringResource(label))
                    })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, value) }) {
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
    var adding by remember { mutableStateOf(false) }
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
        if (name.isEmpty()) return@NameDialog
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
private fun Problem(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
)

@Composable
private fun TestPanel(recipe: Recipe?) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val (settings, _) = rememberSettings()
    val offline = settings.isOffline(LocalContext.current)
    var title by remember { mutableStateOf("Pride and Prejudice") }
    var author by remember { mutableStateOf("Jane Austen") }
    var isbn by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Int?>(null) }
    val lines = remember { mutableStateListOf<String>() }
    val saver = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        val text = report
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val done = app.writeText(uri, text)
            saved = if (done) R.string.free_books_debug_saved else R.string.export_failed
        }
    }

    fun run(ready: Recipe, debug: Boolean) = scope.launch {
        running = true
        saved = null
        lines.clear()
        val source = OwnSource(ready).apply {
            log = { lines += it }
            verbose = debug
        }
        val isbns = listOf(isbn.trim()).filter { it.isNotEmpty() }
            .map { normalizedIsbn(it) ?: it }
        val query = Query(title, author, app.bookLanguage(settings), isbns)
        withContext(Dispatchers.IO) {
            runCatching { source.probe(query) }.onFailure { lines += "✕ ${describe(it)}" }
        }
        running = false
        if (!debug) return@launch
        report = debugReport(ready, query, lines.toList())
        saver.launch(DEBUG_FILE)
    }

    Heading(R.string.free_books_test)
    Text(
        stringResource(R.string.free_books_test_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextInput(R.string.title, title) { title = it }
    TextInput(R.string.author, author) { author = it }
    TextInput(R.string.free_books_isbn, isbn, monospace = true) { isbn = it }
    val ready = recipe?.takeIf {
        !offline && !running && (title.isNotBlank() || isbn.isNotBlank())
    }
    val enabled = ready != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RunButton(R.string.free_books_test, Icons.Outlined.PlayArrow, enabled, true) {
            ready?.let { run(it, debug = false) }
        }
        RunButton(R.string.free_books_debug, Icons.Outlined.BugReport, enabled, false) {
            ready?.let { run(it, debug = true) }
        }
    }
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
    saved?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    lines.forEach { Text(it.take(TRACE_LINE_CHARACTERS), style = codeStyle()) }
}

@Composable
private fun RunButton(
    @StringRes label: Int,
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Text(stringResource(label), Modifier.padding(start = 8.dp))
    }
    if (filled) {
        Button(onClick, enabled = enabled, content = content)
    } else {
        OutlinedButton(onClick, enabled = enabled, content = content)
    }
}

private fun describe(error: Throwable) =
    "${error::class.java.simpleName}: ${firstLine(error)}"

private fun debugReport(recipe: Recipe, query: Query, lines: List<String>) = buildString {
    appendLine("Shelf ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) source debug")
    appendLine("Time: ${OffsetDateTime.now()}")
    appendLine("Query: ${query.title} · ${query.author} · ${query.language}")
    appendLine()
    appendLine("Recipe (header values hidden):")
    appendLine(recipe.redacted().toText())
    appendLine()
    appendLine("Trace:")
    lines.forEach(::appendLine)
}

private suspend fun ShelfApp.writeText(uri: Uri, text: String) =
    withContext(Dispatchers.IO) {
        runCatching {
            val output = contentResolver.openOutputStream(uri)
            output?.use { it.write(text.toByteArray()) } != null
        }.getOrDefault(false)
    }

@Composable
private fun RemoveButton(onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.padding(top = 12.dp),
) {
    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
    Text(stringResource(R.string.remove), Modifier.padding(start = 8.dp))
}
