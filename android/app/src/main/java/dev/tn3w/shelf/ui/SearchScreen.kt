package dev.tn3w.shelf.ui

import android.Manifest.permission.CAMERA
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*

private const val RESULT_LIMIT = 50
private const val LIBRARY_LIMIT = 3

private data class Results(
    val completions: List<String>,
    val authors: List<Author>,
    val books: List<Book>,
    val facets: List<Tag>,
)

private fun Loaded.results(query: String, filter: SearchFilter, job: Job): Results {
    val lookup = searcher.lookup(query, job)
    val unfiltered = searcher.search(lookup, RESULT_LIMIT, job)
    val books = if (filter.narrows) {
        searcher.search(lookup, RESULT_LIMIT, job, filter)
    } else {
        unfiltered.sorted(filter.sort)
    }
    val isbn = catalogue.bookOfIsbn(query)
    val tags = facets(unfiltered.map { it.tags }) + listOfNotNull(filter.tag)
    val refining = filter != SearchFilter()
    return Results(
        if (refining) emptyList() else searcher.complete(lookup, unfiltered),
        if (refining) emptyList() else searcher.authors(lookup, job = job),
        listOfNotNull(isbn) + books.filter { it.work != isbn?.work },
        tags.distinct().mapNotNull { catalogue.tags.getOrNull(it) },
    )
}

@Composable
fun SearchScreen(navigator: Navigator) {
    val viewModel: LibraryViewModel = viewModel()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<Int?>(null) }
    var era by rememberSaveable { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(ResultSort.Relevance) }
    val filter = SearchFilter(tag, era?.let(ERAS::get), sort)
    var results by remember { mutableStateOf<Results?>(null) }
    val library = viewModel.library
    val saved by library.saved.collectAsStateWithLifecycle(emptyList())
    val recent by library.recentSearches.collectAsStateWithLifecycle(emptyList())
    val trending by load { recommender.popular(8) }
    var addingOwn by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(query, loaded, filter) {
        val current = loaded ?: return@LaunchedEffect
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(150)
        results = withContext(Dispatchers.Default) {
            current.results(query, filter, coroutineContext.job)
        }
    }

    fun saveQuery() = viewModel.remember(query)

    fun clearFilters() {
        tag = null
        era = null
        sort = ResultSort.Relevance
    }

    val owned = saved.filtered(query).take(LIBRARY_LIMIT)
        .takeIf { query.isNotBlank() }
        .orEmpty()

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        LargeTitle(stringResource(R.string.search))
        SearchField(
            query = query,
            onChange = { query = it },
            hint = stringResource(R.string.search_hint),
            onSubmit = { saveQuery() },
            onScan = scan(navigator),
        )
        val current = results
        if (current != null) {
            ResultFilters(
                facets = current.facets,
                filter = filter,
                onTag = { tag = it },
                onEra = { era = it },
                onSort = { sort = it },
            )
        }
        LazyColumn {
            if (current == null) {
                suggestions(recent, trending.orEmpty(), { query = it }) {
                    viewModel.clearSearches()
                }
                return@LazyColumn
            }
            val nothing = current.authors.isEmpty() && current.books.isEmpty()
            if (nothing && owned.isEmpty()) {
                item { NoResults(filter.narrows, ::clearFilters) }
            }
            val completions =
                current.completions.filter { it != query.trim().lowercase() }
            items(completions, key = { "completion-$it" }) {
                IconSuggestionRow(Icons.Outlined.Search, it, Modifier.animateItem()) {
                    query = "$it "
                }
            }
            items(current.authors, key = { "author-${it.number}" }) { author ->
                SuggestionRow(
                    author.name,
                    Modifier.animateItem(),
                    onClick = {
                        saveQuery()
                        navigator.author(author)
                    },
                ) { AuthorAvatar(author, 36.dp) }
            }
            val openBook = { opened: Book, origin: String ->
                saveQuery()
                navigator.book(opened, origin)
            }
            val ownedWorks = owned.map { it.work }.toSet()
            val found = current.books.filter { it.work !in ownedWorks }
            books(R.string.in_library, owned.map { it.toBook() }, "owned", openBook)
            val catalogue = R.string.catalogue.takeIf { owned.isNotEmpty() }
            books(catalogue, found, "search", openBook)
            item(key = "own") {
                IconSuggestionRow(
                    Icons.Outlined.LibraryAdd,
                    stringResource(R.string.add_own_book, query.trim()),
                    Modifier.animateItem(),
                ) { addingOwn = true }
            }
        }
    }
    if (addingOwn) OwnBookSheet(query, navigator) { addingOwn = false }
}

private fun LazyListScope.books(
    @StringRes header: Int?,
    books: List<Book>,
    origin: String,
    onOpen: (Book, String) -> Unit,
) {
    if (books.isEmpty()) return
    if (header != null) {
        item(key = "$origin-header") { SectionHeader(stringResource(header)) }
    }
    items(books, key = { "$origin-${it.work}" }) { book ->
        Column(Modifier.animateItem()) { BookListItem(book, origin, onOpen = onOpen) }
    }
}

@Composable
private fun eraLabel(era: IntRange) = when {
    era.first <= 1 -> stringResource(R.string.before_year, era.last + 1)
    era.last >= 9999 -> stringResource(R.string.since_year, era.first)
    else -> "${era.first}–${era.last}"
}

@Composable
private fun ResultFilters(
    facets: List<Tag>,
    filter: SearchFilter,
    onTag: (Int?) -> Unit,
    onEra: (Int?) -> Unit,
    onSort: (ResultSort) -> Unit,
) {
    val sorts = ResultSort.entries.map { stringResource(it.label) to it }
    FilterRow(actions = { SortMenu(sorts, filter.sort, onSort) }) {
        if (facets.isNotEmpty()) GenreChip(facets, filter.tag, onTag)
        FilterMenuChip(
            stringResource(R.string.published),
            ERAS.mapIndexed { index, era -> eraLabel(era) to index },
            filter.era?.let(ERAS::indexOf),
            onEra,
        )
    }
}

@Composable
private fun NoResults(filtered: Boolean, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.no_results))
        if (filtered) {
            OutlinedButton(onClick = onClear) {
                Text(stringResource(R.string.clear_filters))
            }
        }
    }
}

@Composable
private fun scan(navigator: Navigator): (() -> Unit)? {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) navigator.scan() else denied = true
    }
    if (denied) CameraDenied { denied = false }
    val manager = context.packageManager
    if (!manager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return null
    return {
        if (ContextCompat.checkSelfPermission(context, CAMERA) == PERMISSION_GRANTED) {
            navigator.scan()
        } else {
            permission.launch(CAMERA)
        }
    }
}

@Composable
private fun CameraDenied(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan)) },
        text = { Text(stringResource(R.string.camera_denied)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    onScan: (() -> Unit)? = null,
) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconAction(Icons.Outlined.Close, stringResource(R.string.clear)) {
                    onChange("")
                }
            } else if (onScan != null) {
                val label = stringResource(R.string.scan)
                IconAction(Icons.Outlined.DocumentScanner, label, onScan)
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors =
        TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier =
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

private fun LazyListScope.suggestions(
    recent: List<String>,
    trending: List<Book>,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (recent.isNotEmpty()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionHeader(stringResource(R.string.recent))
                }
                TextButton(onClick = onClear, Modifier.padding(end = 8.dp, top = 20.dp)) {
                    Text(stringResource(R.string.clear))
                }
            }
        }
        items(recent, key = { "recent-$it" }) {
            IconSuggestionRow(Icons.Outlined.History, it) { onQuery(it) }
        }
    }
    if (trending.isEmpty()) return
    item { SectionHeader(stringResource(R.string.trending)) }
    items(trending, key = { it.work }) {
        IconSuggestionRow(Icons.AutoMirrored.Outlined.TrendingUp, it.title) {
            onQuery(it.title)
        }
    }
}

@Composable
private fun IconSuggestionRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SuggestionRow(text, modifier, onClick) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
