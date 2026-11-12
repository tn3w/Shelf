package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull

private val ListColumnWidth = 360.dp

class LibraryViewModel(app: ShelfApp) : ShelfViewModel(app) {
    val tagsByWork = combine(loaded.filterNotNull(), library.saved) { current, saved ->
        saved.associate { it.work to current.catalogue.facts(it.work)?.tags?.toList() }
    }.asState()
    val catalogueTags = catalogueState { catalogue.tags }

    fun importFile(uri: Uri) {
        detached { app.open(library.importFile(uri, app::findInCatalogue)) }
    }

    fun renameList(from: String, to: String?) {
        detached { library.renameList(from, to) }
    }

    fun toggleList(works: List<Int>, name: String) {
        detached { library.toggleList(works, name) }
    }

    suspend fun findInCatalogue(title: String, author: String) =
        withContext(Dispatchers.Default) { app.findInCatalogue(title, author) }

    fun addOwn(book: Book, shelf: Shelf, image: Uri?) {
        detached {
            val cover = image?.let { library.saveCover(book.work, it) } ?: 0
            library.place(book.copy(cover = cover), shelf)
        }
    }
}

@Composable
fun libraryViewModel() = shelfViewModel(create = ::LibraryViewModel)

@Composable
fun LibraryScreen(navigator: Navigator) {
    val viewModel = libraryViewModel()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val settings = loadedSettings()
    var query by rememberSaveable { mutableStateOf("") }
    var shelf by rememberSaveable { mutableStateOf<Shelf?>(null) }
    var tag by rememberSaveable { mutableStateOf<Int?>(null) }
    val lists = saved.orEmpty().bookLists()
    val tagsByWork by viewModel.tagsByWork.collectAsStateWithLifecycle()
    val catalogueTags by viewModel.catalogueTags.collectAsStateWithLifecycle()
    val tagLists = tagsByWork?.values?.filterNotNull().orEmpty()
    val genres = (facets(tagLists) + listOfNotNull(tag))
        .distinct()
        .mapNotNull { catalogueTags?.getOrNull(it) }
    val sort = settings?.librarySort ?: LibrarySort.Added
    val listView = settings?.libraryList == true
    val shown = saved
        ?.filtered(query, shelf, tag) { tagsByWork?.get(it).orEmpty() }
        ?.sorted(sort)
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::importFile)
    }
    var addingOwn by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var addingToList by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSelection()
    val empty = saved.isNullOrEmpty()

    fun change(update: (Settings) -> Settings) = viewModel.updateSettings(update)

    Column(Modifier.padding(top = statusBarHeight())) {
        SelectableTitle(
            stringResource(R.string.library),
            selection,
            navigator,
            selectionActions = {
                val label = stringResource(R.string.add_to_list)
                IconAction(Icons.AutoMirrored.Rounded.PlaylistAdd, label) {
                    addingToList = true
                }
            },
        ) {
            val soft = Look.colors.soft
            val online = stringResource(R.string.online_libraries)
            IconAction(Icons.Rounded.Public, online, tint = soft) {
                navigator.onlineLibraries()
            }
            IconAction(
                Icons.Rounded.FileOpen, stringResource(R.string.import_book), tint = soft,
            ) { picker.launch(arrayOf("*/*")) }
            IconAction(Icons.Rounded.Add, stringResource(R.string.own_book)) {
                addingOwn = true
            }
        }
        if (!empty) {
            SearchField(query, { query = it }, stringResource(R.string.search_library))
            FilterRow(actions = { OptionsButton(tag != null) { options = true } }) {
                Shelf.entries.forEach { value ->
                    Tag(stringResource(value.label), selected = value == shelf) {
                        shelf = value.takeIf { it != shelf }
                    }
                }
            }
        }
        val showLists =
            lists.isNotEmpty() && query.isBlank() && shelf == null && tag == null
        if (shown?.isEmpty() == true) {
            val text = if (empty) R.string.library_empty else R.string.no_results
            EmptyState(Icons.Rounded.CollectionsBookmark, stringResource(text))
        }
        if (listView) {
            LibraryList(shown.orEmpty(), navigator, selection) {
                if (showLists) fullWidth { ListsRow(lists, navigator::list) }
            }
        } else {
            BookGrid(
                shown?.map { it.toBook() },
                "library",
                navigator::book,
                selection,
                header = {
                    if (showLists) fullBleed { ListsRow(lists, navigator::list) }
                },
            )
        }
    }
    if (addingOwn) OwnBookSheet("", navigator) { addingOwn = false }
    if (addingToList && selection.active) {
        val chosen = saved.orEmpty().filter { it.work in selection.works }
        ListSheet(
            chosen.commonLists(),
            lists.map { it.name },
            onToggle = { viewModel.toggleList(selection.works, it) },
            onDismiss = { addingToList = false },
        )
    }
    if (!options) return
    LibraryOptions(
        sort = sort,
        onSort = { chosen -> change { it.copy(librarySort = chosen) } },
        genres = genres,
        tag = tag,
        onTag = { chosen -> tag = chosen.takeIf { it != tag } },
        list = listView,
        onList = { chosen -> change { it.copy(libraryList = chosen) } },
        onDismiss = { options = false },
    )
}

@Composable
private fun OptionsButton(active: Boolean, onClick: () -> Unit) {
    Box {
        IconAction(
            Icons.Rounded.Tune,
            stringResource(R.string.sort_filter),
            onClick = onClick,
        )
        if (!active) return@Box
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(8.dp)
                .size(8.dp)
                .background(Look.colors.accent, CircleShape),
        )
    }
}

@Composable
private fun LibraryOptions(
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
    genres: List<Tag>,
    tag: Int?,
    onTag: (Int) -> Unit,
    list: Boolean,
    onList: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = Sheet(onDismiss, stringResource(R.string.sort_filter)) {
    val sorts = LibrarySort.entries.map { stringResource(it.label) to it }
    TagGroup(stringResource(R.string.sort), sorts, sort, onSort)
    if (genres.isNotEmpty()) {
        val labels = genres.map { it.label to it.id }
        TagGroup(stringResource(R.string.genre), labels, tag, onTag)
    }
    val views = listOf(
        stringResource(R.string.grid_view) to false,
        stringResource(R.string.list_view) to true,
    )
    TagGroup(stringResource(R.string.view), views, list, onList)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TagGroup(
    title: String,
    options: List<Pair<String, T>>,
    current: T?,
    onSelect: (T) -> Unit,
) {
    Eyebrow(title, Modifier.padding(top = 6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            Tag(label, selected = value == current) { onSelect(value) }
        }
    }
}

@Composable
fun GenreChip(genres: List<Tag>, current: Int?, onSelect: (Int?) -> Unit) =
    FilterMenuChip(
        stringResource(R.string.genre),
        genres.map { it.label to it.id },
        current,
        onSelect,
    )

@Composable
private fun LibraryList(
    entries: List<Saved>,
    navigator: Navigator,
    selection: Selection,
    header: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(ListColumnWidth),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomSpace()),
    ) {
        header()
        items(entries, key = { it.work }) { saved ->
            val detail = listOfNotNull(
                saved.author.ifEmpty { null },
                saved.finishedOn()?.let { dateLabel(it) }
                    ?: stringResource(saved.shelf.label),
                saved.rating.takeIf { it > 0 }?.let { "★ $it" },
            )
            Column(Modifier.animateItem()) {
                BookListItem(
                    saved.toBook(),
                    "library",
                    detail = detail.joinToString(" · "),
                    selection = selection,
                    onOpen = navigator::book,
                )
            }
        }
    }
}

@Composable
fun OwnBookSheet(query: String, navigator: Navigator, onDismiss: () -> Unit) {
    val viewModel = libraryViewModel()
    var title by rememberSaveable { mutableStateOf(query.trim()) }
    var author by rememberSaveable { mutableStateOf("") }
    var shelf by rememberSaveable { mutableStateOf(Shelf.Reading) }
    var image by rememberSaveable { mutableStateOf<Uri?>(null) }
    val titleFocus = remember { FocusRequester() }
    val authorFocus = remember { FocusRequester() }
    val book = ownBook(title.trim(), author.trim())
    val shownTitle = book.title.ifEmpty { stringResource(R.string.title) }
    val shownAuthor = book.author.ifEmpty { stringResource(R.string.author) }
    val placeholder =
        book.copy(title = shownTitle, authors = listOf(Author(0, shownAuthor)))
    var preview by remember { mutableStateOf(placeholder) }
    var match by remember { mutableStateOf<Book?>(null) }
    var useMatch by rememberSaveable { mutableStateOf(true) }
    val found = match?.takeIf { useMatch }
    val words = KeyboardOptions(
        capitalization = KeyboardCapitalization.Words,
        imeAction = ImeAction.Next,
    )
    LaunchedEffect(placeholder) {
        delay(300)
        preview = placeholder
        match = book.title.takeIf(String::isNotEmpty)
            ?.let { viewModel.findInCatalogue(it, book.author) }
    }
    LaunchedEffect(Unit) {
        if (query.isBlank()) titleFocus.requestFocus() else authorFocus.requestFocus()
    }

    fun add() {
        if (book.title.isEmpty()) return
        when (found) {
            null -> viewModel.addOwn(book, shelf, image)
            else -> viewModel.place(found, shelf)
        }
        onDismiss()
        navigator.book(found ?: book, "own")
    }

    Sheet(onDismiss, stringResource(R.string.own_book)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (found != null) {
                BookCover(found, 112.dp, lift = 10.dp)
            } else {
                OwnCover(preview, image) { image = it }
            }
        }
        Field(
            title, { title = it }, Modifier.focusRequester(titleFocus),
            hint = stringResource(R.string.title), keyboard = words,
            onDone = authorFocus::requestFocus,
        )
        Field(
            author, { author = it }, Modifier.focusRequester(authorFocus),
            hint = stringResource(R.string.author), keyboard = words, onDone = ::add,
        )
        match?.let { CatalogueMatch(it, useMatch) { useMatch = it } }
        ShelfPicker(shelf) { shelf = it }
        Pill(
            stringResource(R.string.add), Modifier.fillMaxWidth(),
            enabled = book.title.isNotEmpty(), onClick = ::add,
        )
    }
}

@Composable
private fun OwnCover(preview: Book, image: Uri?, onImage: (Uri) -> Unit) =
    EditableCover(onImage) {
        if (image == null) return@EditableCover BookCover(preview, 112.dp, lift = 10.dp)
        AsyncImage(
            model = image,
            contentDescription = stringResource(R.string.cover_of, preview.title),
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(112.dp).aspectRatio(2f / 3f),
        )
    }

@Composable
private fun CatalogueMatch(book: Book, selected: Boolean, onSelect: (Boolean) -> Unit) {
    val year = book.year.takeIf { it > 0 }?.toString()
    val details = listOfNotNull(book.title, book.author.ifEmpty { null }, year)
    Row(
        Modifier.fillMaxWidth()
            .surface(Corners.well, Look.colors.wash)
            .clickable { onSelect(!selected) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.AutoStories, null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.found_in_catalogue), style = Look.type.label)
            Hint(details.joinToString(" · "))
        }
        Toggle(selected, null)
    }
}
