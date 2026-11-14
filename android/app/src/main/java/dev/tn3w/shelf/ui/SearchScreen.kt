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
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*

private const val RESULT_LIMIT = 50
private const val LIBRARY_LIMIT = 3

data class Results(
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

class SearchViewModel(app: ShelfApp) : ShelfViewModel(app) {
    val trending = catalogueState { recommender.popular(8) }
    val recent = library.recentSearches.asState()
    var results by mutableStateOf<Results?>(null)
    var searched: Any? = null

    fun remember(query: String) {
        if (query.isNotBlank()) detached { library.remember(query) }
    }

    fun clearSearches() {
        detached { library.clearSearches() }
    }
}

@Composable
fun SearchScreen(navigator: Navigator) {
    val viewModel = shelfViewModel(create = ::SearchViewModel)
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<Int?>(null) }
    var era by rememberSaveable { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(ResultSort.Relevance) }
    val filter = SearchFilter(tag, era?.let(ERAS::get), sort)
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val trending by viewModel.trending.collectAsStateWithLifecycle()
    var addingOwn by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSelection()

    LaunchedEffect(query, loaded, filter) {
        val current = loaded ?: return@LaunchedEffect
        val key = Triple(query, filter, current)
        if (viewModel.searched == key) return@LaunchedEffect
        viewModel.results = if (query.isBlank()) {
            null
        } else {
            delay(150)
            withContext(Dispatchers.Default) {
                current.results(query, filter, coroutineContext.job)
            }
        }
        viewModel.searched = key
    }

    fun saveQuery() = viewModel.remember(query)

    fun clearFilters() {
        tag = null
        era = null
        sort = ResultSort.Relevance
    }

    val owned = saved.orEmpty().filtered(query).take(LIBRARY_LIMIT)
        .takeIf { query.isNotBlank() }
        .orEmpty()

    Column(Modifier.padding(top = statusBarHeight()).imePadding()) {
        SelectableTitle(stringResource(R.string.search), selection, navigator)
        SearchField(
            query = query,
            onChange = { query = it },
            hint = stringResource(R.string.search_hint),
            onSubmit = { saveQuery() },
            onScan = scan(navigator),
        )
        val current = viewModel.results
        if (current != null) {
            ResultFilters(
                facets = current.facets,
                filter = filter,
                onTag = { tag = it },
                onEra = { era = it },
                onSort = { sort = it },
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = bottomSpace())) {
            if (current == null) {
                suggestions(recent.orEmpty(), trending.orEmpty(), { query = it }) {
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
                IconSuggestionRow(Icons.Rounded.Search, it, Modifier.animateItem()) {
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
            val ownedBooks = owned.map { it.toBook() }
            books(R.string.in_library, ownedBooks, "owned", selection, openBook)
            val catalogue = R.string.catalogue.takeIf { owned.isNotEmpty() }
            books(catalogue, found, "search", selection, openBook)
            item(key = "own") {
                IconSuggestionRow(
                    Icons.Rounded.LibraryAdd,
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
    selection: Selection,
    onOpen: (Book, String) -> Unit,
) {
    if (books.isEmpty()) return
    if (header != null) {
        item(key = "$origin-header") { SectionHeader(stringResource(header)) }
    }
    items(books, key = { "$origin-${it.work}" }) { book ->
        Column(Modifier.animateItem()) {
            BookListItem(book, origin, selection = selection, onOpen = onOpen)
        }
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
    val text = stringResource(R.string.no_results)
    if (!filtered) return EmptyState(Icons.Rounded.SearchOff, text)
    EmptyAction(
        Icons.Rounded.SearchOff, text, stringResource(R.string.clear_filters), onClear,
    )
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
    if (!(context.applicationContext as ShelfApp).canScan) return null
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
    ConfirmSheet(
        stringResource(R.string.scan),
        stringResource(R.string.camera_denied),
        stringResource(R.string.open_settings),
        onDismiss,
        danger = false,
    ) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}

@Composable
fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    onScan: (() -> Unit)? = null,
) = Field(
    query,
    onChange,
    modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
    hint = hint,
    keyboard = KeyboardOptions(imeAction = ImeAction.Search),
    onDone = onSubmit,
    leading = {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), Look.colors.faint)
    },
    trailing = {
        val soft = Look.colors.soft
        if (query.isNotEmpty()) {
            IconAction(Icons.Rounded.Close, stringResource(R.string.clear), tint = soft) {
                onChange("")
            }
        } else if (onScan != null) {
            val label = stringResource(R.string.scan)
            IconAction(Icons.Rounded.CenterFocusWeak, label, onClick = onScan)
        }
    },
)

private fun LazyListScope.suggestions(
    recent: List<String>,
    trending: List<Book>,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (recent.isNotEmpty()) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    SectionHeader(stringResource(R.string.recent))
                }
                Pill(
                    stringResource(R.string.clear),
                    Modifier.padding(end = 10.dp, bottom = 4.dp),
                    kind = PillKind.Bare,
                    onClick = onClear,
                )
            }
        }
        items(recent, key = { "recent-$it" }) {
            IconSuggestionRow(Icons.Rounded.History, it) { onQuery(it) }
        }
    }
    if (trending.isEmpty()) return
    item { SectionHeader(stringResource(R.string.trending)) }
    items(trending, key = { it.work }) {
        IconSuggestionRow(Icons.AutoMirrored.Rounded.TrendingUp, it.title) {
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
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), Look.colors.faint)
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
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(text, Modifier.padding(start = 14.dp), style = Look.type.body, maxLines = 1)
    }
}
