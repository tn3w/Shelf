package dev.tn3w.shelf.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*

private val SECTIONS = listOf(
    "audience" to R.string.audience,
    "form" to R.string.formats,
    "topic" to R.string.topics,
)
private val FILTERS = listOf(
    R.string.all to null,
    R.string.reading to Shelf.Reading,
    R.string.want_to_read to Shelf.Want,
    R.string.finished to Shelf.Read,
)

@Composable
fun ExploreScreen(navigator: Navigator) {
    val popular by load { recommender.popular(16) }
    val tags by load {
        catalogue.tags
            .filter { catalogue.tagCount(it.id) > 0 }
            .sortedByDescending { catalogue.tagCount(it.id) }
            .groupBy { it.category }
    }

    val missing by load { catalogue.workCount == 0 }

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { LargeTitle(stringResource(R.string.explore)) }
        if (missing == true) {
            item { NoCatalogue { navigator.settings(SettingsPage.Catalogue) } }
            return@LazyColumn
        }
        item {
            BookSection(
                stringResource(R.string.popular_now),
                popular,
                "popular",
                navigator::book,
                subtitle = stringResource(R.string.popular_now_subtitle),
            )
        }
        tags?.get("genre")?.let { genres ->
            item { SectionHeader(stringResource(R.string.genres)) }
            item { GenreGrid(genres, navigator) }
        }
        SECTIONS.forEach { (category, title) ->
            val list = tags?.get(category) ?: return@forEach
            item { SectionHeader(stringResource(title)) }
            item { TagChips(list, navigator) }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreGrid(genres: List<Tag>, navigator: Navigator) {
    FlowRow(
        Modifier.padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
    ) {
        genres.forEach { tag ->
            Surface(
                onClick = { navigator.tag(tag.id) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.weight(1f).heightIn(min = 72.dp),
            ) {
                Box(Modifier.padding(16.dp), contentAlignment = Alignment.BottomStart) {
                    Text(tag.label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<Tag>, navigator: Navigator) {
    FlowRow(
        Modifier.padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            SuggestionChip(
                onClick = { navigator.tag(tag.id) }, label = { Text(tag.label) },
            )
        }
    }
}

@Composable
fun TagScreen(id: Int, navigator: Navigator) {
    val tag by load(id) { catalogue.tags.getOrNull(id) }
    val books by load(id) { recommender.popular(90, id) }
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back, tag?.label.orEmpty())
        BookGrid(books, "tag-$id", navigator::book)
    }
}

@Composable
fun AuthorScreen(author: Author, navigator: Navigator) {
    val groups by load(author) { recommender.authorShelf(author) }
    val born by load(author) { catalogue.authorBorn(author) }
    val count = groups?.sumOf { it.books.size }
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back)
        GroupedBookGrid(groups, "author-${author.number}", navigator::book) {
            fullWidth { AuthorHeader(author, born ?: 0, count) }
        }
    }
}

@Composable
private fun AuthorHeader(author: Author, born: Int, count: Int?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AuthorAvatar(author, 96.dp)
        Text(
            author.name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        val details = listOfNotNull(
            born.takeIf { it > 0 }?.let { stringResource(R.string.born, it) },
            count?.let { pluralStringResource(R.plurals.books, it, it) },
        )
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(navigator: Navigator) {
    val app = shelfApp()
    val saved by app.library.saved.collectAsStateWithLifecycle(null)
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shelf = FILTERS[filter].second
    val shown = saved?.filter { shelf == null || it.shelf == shelf }?.map { it.toBook() }
    val scope = rememberCoroutineScope()
    val unsupported = stringResource(R.string.unsupported_file)
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val work = app.library.importFile(uri)
            if (work != null) return@launch navigator.reader(work)
            Toast.makeText(app, unsupported, Toast.LENGTH_LONG).show()
        }
    }
    var addingOwn by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        LargeTitle(stringResource(R.string.library)) {
            IconAction(Icons.Outlined.Add, stringResource(R.string.own_book)) {
                addingOwn = true
            }
            IconAction(Icons.Outlined.FileOpen, stringResource(R.string.import_book)) {
                picker.launch(arrayOf("*/*"))
            }
        }
        FlowRow(
            Modifier.padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTERS.forEachIndexed { index, (label, _) ->
                FilterChip(
                    selected = index == filter,
                    onClick = { filter = index },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (shown?.isEmpty() == true) {
            EmptyState(
                Icons.AutoMirrored.Outlined.LibraryBooks,
                stringResource(R.string.library_empty),
            )
        }
        BookGrid(shown, "library", navigator::book)
    }
    if (addingOwn) OwnBookSheet("", navigator) { addingOwn = false }
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
    label: Int,
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
private fun ShelfPicker(selected: Shelf, onSelect: (Shelf) -> Unit) {
    val shelves = FILTERS.mapNotNull { (label, shelf) -> shelf?.let { label to it } }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        shelves.forEachIndexed { index, (label, shelf) ->
            SegmentedButton(
                selected = shelf == selected,
                onClick = { onSelect(shelf) },
                shape = SegmentedButtonDefaults.itemShape(index, shelves.size),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}
