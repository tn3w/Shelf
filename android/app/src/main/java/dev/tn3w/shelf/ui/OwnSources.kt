package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private val SOURCE_TYPES =
    arrayOf("application/json", "text/plain", "application/octet-stream")
private val SEARCH_FIELDS = listOf("title", "author", "cover")
private const val TRACE_LINE_CHARACTERS = 3_000
private val EDITOR_MODES = mapOf(
    false to R.string.free_books_form,
    true to R.string.free_books_file,
)

class SourcesViewModel(app: ShelfApp) : ShelfViewModel(app) {
    private val _imported = MutableStateFlow<Result<Imported>?>(null)
    val imported = _imported.asStateFlow()
    private val _exportFailed = MutableStateFlow(false)
    val exportFailed = _exportFailed.asStateFlow()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            _imported.value = runSuspendCatching { checkNotNull(sources.import(uri)) }
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch { _exportFailed.value = !sources.export(uri) }
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

    fun test(recipe: Recipe, query: Query) {
        _running.value = true
        _lines.value = emptyList()
        viewModelScope.launch {
            trace(recipe, query).collect { line -> _lines.update { it + line } }
            _running.value = false
        }
    }
}

@Composable
private fun sourcesViewModel() = shelfViewModel(create = ::SourcesViewModel)

@Composable
fun OwnSourceRows(settings: Settings, update: SettingsUpdate, enabled: Boolean) {
    val viewModel = sourcesViewModel()
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
    ActionRow(R.string.free_books_add, Icons.Rounded.Add) { editing = STARTER_RECIPE }
    ActionRow(R.string.free_books_import, Icons.Rounded.FileOpen, status) {
        importer.launch(SOURCE_TYPES)
    }
    if (recipes.isNotEmpty()) {
        val secrets = recipes.any { it.headers.isNotEmpty() }
        val exportStatus = when {
            exportFailed -> stringResource(R.string.export_failed)
            secrets -> stringResource(R.string.free_books_export_headers)
            else -> null
        }
        ActionRow(R.string.free_books_export, Icons.Rounded.Upload, exportStatus) {
            exporter.launch(SOURCES_FILE)
        }
    }
    editing?.let { RecipeEditor(it) { editing = null } }
}

@Composable
private fun RecipeEditor(initial: Recipe, onClose: () -> Unit) {
    val viewModel = sourcesViewModel()
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
        Box(Modifier.fillMaxSize().background(Look.colors.paper)) {
            Column(Modifier.imePadding()) {
                EditorBar(initial, problems.isEmpty(), onClose, ::save)
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenPadding)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Segments(EDITOR_MODES.keys.toList(), asFile, onSelect = ::showFile) {
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
private fun codeStyle() = Look.type.note.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun EditorBar(
    recipe: Recipe,
    valid: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.Rounded.Close,
            stringResource(R.string.cancel),
            onClick = onClose,
        )
        val isNew = recipe.id.isEmpty()
        val title = if (isNew) R.string.free_books_add else R.string.provider_own
        Text(
            stringResource(title),
            Modifier.weight(1f).padding(horizontal = 6.dp),
            style = Look.type.heading,
        )
        Pill(stringResource(R.string.save), enabled = valid, onClick = onSave)
    }
}

@Composable
private fun RecipeText(text: String, onChange: (String) -> Unit) =
    Field(text, onChange, Modifier.fillMaxWidth(), lines = 12..60, mono = true)

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
    Segments(BOOK_FORMATS, recipe.format, onSelect = {
        onChange(recipe.copy(format = it))
    }) { it.uppercase() }
    RecipeLanguages(recipe.languages) { onChange(recipe.copy(languages = it)) }
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
            Text(name, Modifier.weight(1f), style = Look.type.subhead)
            onRemove?.let {
                val remove = stringResource(R.string.remove)
                IconAction(
                    Icons.Rounded.Close,
                    remove,
                    tint = Look.colors.soft,
                    onClick = it,
                )
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
) = Field(value.orEmpty(), onChange, Modifier.weight(1f), label, mono = true)

@Composable
private fun TextInput(
    @StringRes label: Int,
    value: String,
    monospace: Boolean = false,
    onChange: (String) -> Unit,
) = Field(
    value,
    onChange,
    Modifier.fillMaxWidth(),
    stringResource(label),
    lines = 1..6,
    mono = monospace,
)

@Composable
private fun Heading(@StringRes title: Int) = Heading(stringResource(title))

@Composable
private fun Heading(title: String, onRemove: (() -> Unit)? = null) {
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = Look.type.heading)
        onRemove?.let {
            val remove = stringResource(R.string.remove)
            IconAction(
                Icons.Rounded.DeleteOutline,
                remove,
                tint = Look.colors.soft,
                onClick = it,
            )
        }
    }
}

@Composable
private fun AddButton(@StringRes label: Int, onClick: () -> Unit) = Pill(
    stringResource(label),
    icon = Icons.Rounded.Add,
    kind = PillKind.Bare,
    onClick = onClick,
)

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
    val valid = trimmed.isNotEmpty() && error == null
    Sheet(onDismiss, stringResource(title)) {
        Field(
            name, { name = it },
            label = stringResource(R.string.free_books_name), error = error,
        )
        valueLabel?.let { Field(value, { value = it }, label = stringResource(it)) }
        SheetButtons {
            val cancel = stringResource(R.string.cancel)
            Pill(cancel, Modifier.weight(1f), kind = PillKind.Line, onClick = onDismiss)
            Pill(stringResource(R.string.save), Modifier.weight(1f), enabled = valid) {
                onConfirm(trimmed, value)
            }
        }
    }
}

@Composable
private fun HeaderRows(
    headers: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    headers.forEach { (name, value) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = Look.type.subhead)
                Hint(value)
            }
            val remove = stringResource(R.string.remove)
            IconAction(Icons.Rounded.Close, remove, tint = Look.colors.soft) {
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
private fun RecipeLanguages(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Eyebrow(stringResource(R.string.free_books_languages), Modifier.padding(top = 6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LANGUAGES.forEach { language ->
            val on = language in selected
            Tag(nativeName(language), selected = on) {
                onChange(if (on) selected - language else selected + language)
            }
        }
    }
}

@Composable
private fun Problem(text: String) = Hint(text, color = Look.colors.danger)

@Composable
private fun TestPanel(recipe: Recipe?) {
    val viewModel = sourcesViewModel()
    val settings = loadedSettings() ?: return
    val offline = settings.isOffline(LocalContext.current)
    val sampleTitle = stringResource(R.string.free_books_test_title)
    val sampleAuthor = stringResource(R.string.free_books_test_author)
    var title by rememberSaveable { mutableStateOf(sampleTitle) }
    var author by rememberSaveable { mutableStateOf(sampleAuthor) }
    var isbn by rememberSaveable { mutableStateOf("") }
    val running by viewModel.running.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()

    fun run(ready: Recipe) {
        val isbns = listOf(isbn.trim()).filter { it.isNotEmpty() }
            .map { normalizedIsbn(it) ?: it }
        val query = Query(title, author, viewModel.bookLanguage(settings), isbns)
        viewModel.test(ready, query)
    }

    Heading(R.string.free_books_test)
    Hint(stringResource(R.string.free_books_test_hint))
    TextInput(R.string.title, title) { title = it }
    TextInput(R.string.author, author) { author = it }
    TextInput(R.string.free_books_isbn, isbn, monospace = true) { isbn = it }
    val ready = recipe?.takeIf {
        !offline && !running && (title.isNotBlank() || isbn.isNotBlank())
    }
    Pill(
        stringResource(R.string.free_books_test),
        icon = Icons.Rounded.PlayArrow,
        kind = PillKind.Soft,
        enabled = ready != null,
    ) { ready?.let(::run) }
    if (running) Meter(null)
    lines.forEach { Text(it.take(TRACE_LINE_CHARACTERS), style = codeStyle()) }
}

private fun describe(error: Throwable) =
    "${error::class.java.simpleName}: ${firstLine(error)}"

private fun trace(recipe: Recipe, query: Query) = channelFlow {
    val source = Provider(recipe, log = { trySend(it) })
    runSuspendCatching { source.probe(query) }.onFailure { send("✕ ${describe(it)}") }
}.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

@Composable
private fun RemoveButton(onClick: () -> Unit) = Pill(
    stringResource(R.string.remove),
    Modifier.padding(top = 12.dp),
    Icons.Rounded.DeleteOutline,
    PillKind.Line,
    danger = true,
    onClick = onClick,
)
