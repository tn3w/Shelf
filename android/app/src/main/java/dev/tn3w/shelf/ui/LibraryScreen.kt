package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
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
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*

@Composable
fun LibraryScreen(navigator: Navigator) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val saved by app.library.saved.collectAsStateWithLifecycle(null)
    val settings = loadedSettings()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var shelf by rememberSaveable { mutableStateOf<Shelf?>(null) }
    var tag by rememberSaveable { mutableStateOf<Int?>(null) }
    val tagsByWork by load(saved) {
        saved.orEmpty().associate { it.work to catalogue.facts(it.work)?.tags?.toList() }
    }
    val catalogueTags by load { catalogue.tags }
    val tagLists = tagsByWork?.values?.filterNotNull().orEmpty()
    val genres = (facets(tagLists) + listOfNotNull(tag))
        .distinct()
        .mapNotNull { catalogueTags?.getOrNull(it) }
    val sort = settings?.librarySort ?: LibrarySort.Added
    val list = settings?.libraryList == true
    val shown = saved
        ?.filtered(query, shelf, tag) { tagsByWork?.get(it).orEmpty() }
        ?.sorted(sort)
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { navigator.openImported(app.library.importFile(uri), app) }
    }
    var addingOwn by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    fun change(update: (Settings) -> Settings) =
        scope.launch { app.library.updateSettings(update) }

    fun toggleSearch() {
        searching = !searching
        query = ""
    }

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        LargeTitle(stringResource(R.string.library)) {
            if (!saved.isNullOrEmpty()) {
                val icon = with(Icons.Outlined) { if (searching) SearchOff else Search }
                IconAction(icon, stringResource(R.string.search_library), ::toggleSearch)
            }
            IconAction(Icons.Outlined.Add, stringResource(R.string.own_book)) {
                addingOwn = true
            }
            IconAction(Icons.Outlined.FileOpen, stringResource(R.string.import_book)) {
                picker.launch(arrayOf("*/*"))
            }
        }
        if (searching) {
            SearchField(
                query = query,
                onChange = { query = it },
                hint = stringResource(R.string.search_library),
                modifier = Modifier.focusRequester(focus),
            )
        }
        if (!saved.isNullOrEmpty()) {
            FilterRow(actions = { OptionsButton(tag != null) { options = true } }) {
                Shelf.entries.forEach { value ->
                    FilterChip(
                        selected = value == shelf,
                        onClick = { shelf = value.takeIf { it != shelf } },
                        label = { Text(stringResource(value.label)) },
                    )
                }
            }
        }
        if (shown?.isEmpty() == true) {
            val empty = when {
                saved.isNullOrEmpty() -> R.string.library_empty
                else -> R.string.no_results
            }
            EmptyState(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(empty))
        }
        if (list) {
            LibraryList(shown.orEmpty(), navigator)
        } else {
            BookGrid(shown?.map { it.toBook() }, "library", navigator::book)
        }
    }
    if (addingOwn) OwnBookSheet("", navigator) { addingOwn = false }
    if (!options) return
    LibraryOptions(
        sort = sort,
        onSort = { chosen -> change { it.copy(librarySort = chosen) } },
        genres = genres,
        tag = tag,
        onTag = { chosen -> tag = chosen.takeIf { it != tag } },
        list = list,
        onList = { chosen -> change { it.copy(libraryList = chosen) } },
        onDismiss = { options = false },
    )
}

@Composable
fun FilterRow(
    actions: @Composable RowScope.() -> Unit = {},
    chips: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = chips,
        )
        actions()
    }
}

@Composable
private fun OptionsButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (active) Badge() }) {
            Icon(Icons.Outlined.Tune, stringResource(R.string.sort_filter))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = ScreenPadding).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val sorts = LibrarySort.entries.map { stringResource(it.label) to it }
            OptionGroup(stringResource(R.string.sort), sorts, sort, onSort)
            if (genres.isNotEmpty()) {
                val labels = genres.map { it.label to it.id }
                OptionGroup(stringResource(R.string.genre), labels, tag, onTag)
            }
            val views = listOf(
                stringResource(R.string.grid_view) to false,
                stringResource(R.string.list_view) to true,
            )
            OptionGroup(stringResource(R.string.view), views, list, onList)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<Pair<String, T>>,
    current: T?,
    onSelect: (T) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = value == current,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
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
private fun LibraryList(entries: List<Saved>, navigator: Navigator) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(entries, key = { it.work }) { saved ->
            val detail = listOfNotNull(
                saved.author.ifEmpty { null },
                stringResource(saved.shelf.label),
                saved.rating.takeIf { it > 0 }?.let { "★ $it" },
            )
            Column(Modifier.animateItem()) {
                BookListItem(
                    saved.toBook(),
                    "library",
                    detail = detail.joinToString(" · "),
                    onOpen = navigator::book,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnBookSheet(query: String, navigator: Navigator, onDismiss: () -> Unit) {
    val library = shelfApp().library
    val scope = rememberCoroutineScope()
    val title = rememberTextFieldState(query.trim())
    val author = rememberTextFieldState()
    var shelf by rememberSaveable { mutableStateOf(Shelf.Reading) }
    var image by rememberSaveable { mutableStateOf<Uri?>(null) }
    val titleFocus = remember { FocusRequester() }
    val authorFocus = remember { FocusRequester() }
    val book = ownBook(title.text.trim().toString(), author.text.trim().toString())
    val shownTitle = book.title.ifEmpty { stringResource(R.string.title) }
    val shownAuthor = book.author.ifEmpty { stringResource(R.string.author) }
    val placeholder =
        book.copy(title = shownTitle, authors = listOf(Author(0, shownAuthor)))
    var preview by remember { mutableStateOf(placeholder) }
    LaunchedEffect(placeholder) {
        delay(300)
        preview = placeholder
    }
    LaunchedEffect(Unit) {
        if (query.isBlank()) titleFocus.requestFocus() else authorFocus.requestFocus()
    }

    fun add() {
        if (book.title.isEmpty()) return
        scope.launch {
            val cover = image?.let { library.saveCover(book.work, it) } ?: 0
            library.place(book.copy(cover = cover), shelf)
            onDismiss()
            navigator.book(book, "own")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.own_book),
                style = MaterialTheme.typography.titleLarge,
            )
            EditableCover({ image = it }, Modifier.padding(bottom = 8.dp)) {
                if (image == null) return@EditableCover BookCover(preview, 112.dp)
                AsyncImage(
                    model = image,
                    contentDescription = stringResource(R.string.cover_of, preview.title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(112.dp).aspectRatio(2f / 3f),
                )
            }
            OwnBookField(title, R.string.title, Modifier.focusRequester(titleFocus)) {
                authorFocus.requestFocus()
            }
            OwnBookField(author, R.string.author, Modifier.focusRequester(authorFocus)) {
                add()
            }
            ShelfPicker(shelf) { shelf = it }
            Button(
                onClick = ::add,
                enabled = book.title.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.add)) }
        }
    }
}

@Composable
private fun OwnBookField(
    state: TextFieldState,
    @StringRes label: Int,
    modifier: Modifier,
    onNext: () -> Unit,
) {
    OutlinedTextField(
        state = state,
        label = { Text(stringResource(label)) },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
        ),
        onKeyboardAction = { onNext() },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ShelfPicker(selected: Shelf?, onSelect: (Shelf) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Shelf.entries.forEachIndexed { index, shelf ->
            SegmentedButton(
                selected = shelf == selected,
                onClick = { onSelect(shelf) },
                shape = SegmentedButtonDefaults.itemShape(index, Shelf.entries.size),
                label = { Text(stringResource(shelf.label), maxLines = 1) },
            )
        }
    }
}
