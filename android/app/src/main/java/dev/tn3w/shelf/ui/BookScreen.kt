package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.SimilarKind
import dev.tn3w.shelf.SimilarRoute
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class Details(
    val book: Book,
    val description: Description,
    val tags: List<Pair<Int, String>>,
)

data class Related(
    val series: Pair<String, List<Book>>?,
    val byAuthor: List<Book>,
    val similar: List<Book>,
)

class BookViewModel(app: ShelfApp, work: Int) : ShelfViewModel(app) {
    private val _offers = MutableStateFlow<Map<Any, Download>>(emptyMap())
    val offers = _offers.asStateFlow()
    val details = catalogueState {
        val source = catalogueOf(work) ?: return@catalogueState null
        val book = source.book(work) ?: return@catalogueState null
        val tags = book.tags.mapNotNull { id ->
            source.tags.getOrNull(id)?.let { id to it.label }
        }
        Details(book, source.description(work), tags)
    }
    val related = catalogueState {
        val found = book(work) ?: return@catalogueState null
        Related(
            recommender.series(found),
            recommender.byAuthor(found),
            recommender.recommended(RecommendSeed.Work(seedBook(work) ?: found)),
        )
    }

    fun changeCover(book: Book, shelf: Shelf?, image: Uri) {
        detached {
            val cover = library.saveCover(book.work, image) ?: return@detached
            library.place(book.copy(cover = cover), shelf ?: Shelf.Reading)
        }
    }

    fun edit(work: Int, change: (Saved) -> Saved) {
        detached { library.edit(work, change) }
    }

    fun delete(book: Book) {
        detached { library.deleteBook(book.work, keepShelves = !book.isLocal) }
    }

    val narrator = app.narrator
    val audiobooks = app.audiobooks

    fun listen(book: Book) = viewModelScope.launch {
        if (audiobooks.open(book.work, book.title)) audiobooks.play()
    }

    fun attachAudio(book: Book, shelf: Shelf?, uris: List<Uri>) {
        if (shelf == null) place(book, Shelf.Reading)
        audiobooks.attach(book.work, book.title, uris)
    }

    fun import(book: Book, uri: Uri) {
        detached { app.open(book.work.takeIf { library.importBook(book, uri) }) }
    }

    fun download(book: Book, offer: Offer) =
        fetch(book, offer) { library.download(book, offer, it) }

    fun download(book: Book, catalog: Catalog, file: Acquisition) = fetch(book, file) {
        library.download(book, catalogs.client(catalog), file, it)
    }

    private fun fetch(book: Book, key: Any, work: suspend ((Float) -> Unit) -> Boolean) {
        if (_offers.value.values.any { it is Download.Running }) return
        fun report(progress: Float) =
            _offers.update { it + (key to Download.Running(progress)) }
        report(0f)
        viewModelScope.launch {
            val done = transfer(::report) { work(it) }
            if (done) {
                report(1f)
                delay(DOWNLOAD_SETTLE_MILLIS)
            }
            _offers.update { if (done) it - key else it + (key to Download.Failed) }
            if (done) app.open(book.work)
        }
    }
}

@Composable
fun BookScreen(work: Int, origin: String, navigator: Navigator) {
    val viewModel = shelfViewModel { BookViewModel(it, work) }
    val saved by viewModel.library.saved.collectAsStateWithLifecycle(emptyList())
    val details by viewModel.details.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()
    val progress by viewModel.library.progress.collectAsStateWithLifecycle(emptyMap())
    val entry = saved.firstOrNull { it.work == work }
    val book = details?.book ?: entry?.toBook()
    val position = progress[work]
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    CoverTheme(coverTone(book)) {
        LazyColumn(Modifier.fillMaxSize().glow(), contentPadding = screenInsets()) {
            item {
                BackBar(navigator::back) {
                    if (book?.isLocal != true && position == null) return@BackBar
                    val label = stringResource(R.string.delete_book)
                    IconAction(
                        Icons.Rounded.DeleteOutline,
                        label,
                        tint = Look.colors.soft,
                    ) {
                        confirmDelete = true
                    }
                }
            }
            if (book == null) return@LazyColumn
            item {
                BookHeader(book, entry?.shelf, position, origin, navigator, viewModel)
            }
            val tags = details?.tags.orEmpty()
            if (tags.isNotEmpty()) item { TagFlow(tags, navigator) }
            val description = details?.description?.takeIf { it.text.isNotBlank() }
            if (description != null) {
                item { SectionHeader(stringResource(R.string.about_book)) }
                item { DescriptionText(description) }
            }
            if (entry != null) {
                item { SectionHeader(stringResource(R.string.your_book)) }
                item {
                    YourBookCard(entry, saved.listNames(), navigator::list) {
                        viewModel.edit(work, it)
                    }
                }
            }
            val series = related?.series?.takeIf { it.second.size > 1 }
            if (series != null) {
                item {
                    val (name, books) = series
                    val size = books.size
                    val count =
                        pluralStringResource(R.plurals.books_in_series, size, size)
                    val more = { navigator.series(work) }
                    BookSection(
                        name, books, "series", navigator::book,
                        count, more, shared = false,
                    )
                }
            }
            val byAuthor = related?.byAuthor.orEmpty()
            if (byAuthor.isNotEmpty()) {
                item {
                    val author = book.authors.first()
                    val title = stringResource(R.string.more_by, author.name)
                    val more = { navigator.author(author) }
                    BookSection(
                        title, byAuthor, "author", navigator::book,
                        onMore = more, shared = false,
                    )
                }
            }
            val similar = related?.similar.orEmpty()
            if (similar.isNotEmpty()) {
                item {
                    val title = stringResource(R.string.similar)
                    val more = {
                        navigator.similar(SimilarRoute(SimilarKind.Books, listOf(work)))
                    }
                    BookSection(
                        title, similar, "similar", navigator::book,
                        onMore = more, shared = false,
                    )
                }
            }
        }
        if (!confirmDelete || book == null) return@CoverTheme
        ConfirmSheet(
            stringResource(R.string.delete_book),
            stringResource(R.string.delete_book_confirm, book.title),
            stringResource(R.string.remove),
            onDismiss = { confirmDelete = false },
        ) {
            viewModel.delete(book)
            navigator.back()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookHeader(
    book: Book,
    shelf: Shelf?,
    position: Progress?,
    origin: String,
    navigator: Navigator,
    viewModel: BookViewModel,
) {
    val hasFile by produceState(true, position) {
        value = viewModel.library.hasFile(position)
    }
    val online = loadedSettings()?.isOffline(LocalContext.current) == false
    val audiobooks by viewModel.library.audiobooks.collectAsStateWithLifecycle(emptyMap())
    val hasAudio = book.work in audiobooks
    var listening by rememberSaveable { mutableStateOf(false) }
    val audioPicker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.attachAudio(book, shelf, uris)
        listening = true
    }
    fun listen() {
        if (!hasAudio) return audioPicker.launch(arrayOf("audio/*"))
        viewModel.listen(book)
        listening = true
    }
    var findFree by rememberSaveable { mutableStateOf(false) }
    val unplaced = if (book.isLocal) Shelf.Reading else null
    fun place(target: Shelf) =
        viewModel.place(book, if (shelf == target) unplaced else target)
    fun changeCover(image: Uri) = viewModel.changeCover(book, shelf, image)

    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sharedKey = "$origin-${book.work}"
        val space = Modifier.padding(top = 4.dp, bottom = 26.dp)
        if (book.isLocal) {
            EditableCover(::changeCover, space) {
                BookCover(book, 190.dp, sharedKey = sharedKey, lift = 22.dp)
            }
        } else {
            BookCover(book, 190.dp, space, sharedKey, lift = 22.dp)
        }
        Text(book.title, style = Look.type.title, textAlign = TextAlign.Center)
        if (book.subtitle.isNotBlank()) {
            Text(
                book.subtitle,
                Modifier.padding(top = 4.dp),
                Look.colors.soft,
                style = Look.type.body,
                textAlign = TextAlign.Center,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.Center) {
            book.authors.filter { it.number > 0 }.forEach { author ->
                Pill(author.name, kind = PillKind.Bare) { navigator.author(author) }
            }
        }
        if (book.isLocal && book.author.isNotBlank()) {
            Text(
                book.author,
                Modifier.padding(vertical = 8.dp),
                style = Look.type.subhead,
            )
        }
        Hint(facts(book), textAlign = TextAlign.Center)
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReadButton(book, position, hasFile, navigator, viewModel, Modifier.weight(1f))
            RoundAction(Icons.Rounded.Headphones, R.string.listen, ::listen)
            if (online && !hasFile) {
                RoundAction(Icons.Rounded.CloudDownload, R.string.free_ebook) {
                    findFree = true
                }
            }
        }
        ShelfPicker(shelf, onSelect = ::place)
    }
    if (findFree && !hasFile) FreeBookSheet(book, viewModel) { findFree = false }
    if (listening && hasAudio) {
        ListenSheet(
            book.work,
            book.title,
            viewModel.narrator,
            viewModel.audiobooks,
            onSettings = { navigator.settings(SettingsPage.Listening) },
        ) { listening = false }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: Int, onClick: () -> Unit) = IconAction(
    icon,
    stringResource(label),
    Modifier.surface(CircleShape, Look.colors.wash).padding(2.dp),
    onClick = onClick,
)

@Composable
private fun ReadButton(
    book: Book,
    position: Progress?,
    hasFile: Boolean,
    navigator: Navigator,
    viewModel: BookViewModel,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { viewModel.import(book, it) }
    }
    val label = position?.fraction?.let {
        stringResource(R.string.continue_percent, (it * 100).toInt())
    } ?: stringResource(if (position != null) R.string.resume else R.string.start)
    Pill(label, modifier, Icons.AutoMirrored.Rounded.MenuBook) {
        if (hasFile) navigator.reader(book.work) else picker.launch(arrayOf("*/*"))
    }
}

@Composable
private fun facts(book: Book): String {
    val popularity = book.popularity
    return listOfNotNull(
        book.year.takeIf { it > 0 }?.toString(),
        ratingLabel(book),
        popularity
            ?.readers
            ?.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.readers, it, it) },
        popularity
            ?.editions
            ?.takeIf { it > 1 }
            ?.let { pluralStringResource(R.plurals.editions, it, it) },
    )
        .joinToString("  ·  ")
}

@Composable
private fun DescriptionText(description: Description) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = ScreenPadding)) {
        Text(
            description.text,
            style = Look.type.body,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.animateContentSize().clickable { expanded = !expanded },
        )
        if (description.translated) {
            val translated = stringResource(R.string.machine_translated)
            Hint(translated, Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(tags: List<Pair<Int, String>>, navigator: Navigator) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { (id, label) -> Tag(label) { navigator.tag(id) } }
    }
}
