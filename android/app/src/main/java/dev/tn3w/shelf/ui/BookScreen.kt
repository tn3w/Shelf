package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MAX_STARS = 5

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
    private val _offers = MutableStateFlow<Map<Offer, Download>>(emptyMap())
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

    fun import(book: Book, uri: Uri) {
        detached { app.open(book.work.takeIf { library.importBook(book, uri) }) }
    }

    fun download(book: Book, offer: Offer) {
        if (_offers.value.values.any { it is Download.Running }) return
        fun report(progress: Float) =
            _offers.update { it + (offer to Download.Running(progress)) }
        report(0f)
        viewModelScope.launch {
            val done = transfer(::report) { library.download(book, offer, it) }
            if (done) {
                report(1f)
                delay(DOWNLOAD_SETTLE_MILLIS)
            }
            _offers.update { if (done) it - offer else it + (offer to Download.Failed) }
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
    val entry = saved.firstOrNull { it.work == work }
    val book = details?.book ?: entry?.toBook()

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { BackBar(navigator::back) }
        if (book == null) return@LazyColumn
        item { BookHeader(book, entry?.shelf, origin, navigator, viewModel) }
        if (entry != null && entry.shelf != Shelf.Want) {
            item { ReadingCard(entry) { viewModel.edit(work, it) } }
        }
        val tags = details?.tags.orEmpty()
        if (tags.isNotEmpty()) item { TagFlow(tags, navigator) }
        val description = details?.description?.takeIf { it.text.isNotBlank() }
        if (description != null) {
            item { SectionHeader(stringResource(R.string.about_book)) }
            item { DescriptionText(description) }
        }
        val series = related?.series?.takeIf { it.second.size > 1 }
        if (series != null) {
            item {
                val (name, books) = series
                val size = books.size
                val count = pluralStringResource(R.plurals.books_in_series, size, size)
                val more = { navigator.series(work) }
                BookSection(
                    name, books, "series", navigator::book, count, more, shared = false,
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
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BookHeader(
    book: Book,
    shelf: Shelf?,
    origin: String,
    navigator: Navigator,
    viewModel: BookViewModel,
) {
    val progress by viewModel.library.progress.collectAsStateWithLifecycle(emptyMap())
    val position = progress[book.work]
    val hasFile by produceState(true, position) {
        value = viewModel.library.hasFile(position)
    }
    val online = loadedSettings()?.isOffline(LocalContext.current) == false
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
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
        if (book.isLocal) {
            EditableCover(::changeCover, Modifier.padding(vertical = 16.dp)) {
                BookCover(book, 180.dp, sharedKey = sharedKey)
            }
        } else {
            BookCover(book, 180.dp, Modifier.padding(vertical = 16.dp), sharedKey)
        }
        Text(
            book.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (book.subtitle.isNotBlank()) {
            Text(
                book.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.Center) {
            book.authors.filter { it.number > 0 }.forEach { author ->
                TextButton(onClick = { navigator.author(author) }) {
                    Text(author.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (book.isLocal && book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Hint(facts(book), textAlign = TextAlign.Center)
        FlowRow(
            Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReadButton(book, position, hasFile, navigator, viewModel)
            ShelfToggle(
                shelf == Shelf.Want, R.string.want_to_read, { place(Shelf.Want) },
            ) {
                if (it) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd
            }
            ShelfToggle(
                shelf == Shelf.Read, R.string.mark_finished, { place(Shelf.Read) },
            ) {
                if (it) Icons.Outlined.CheckCircle else Icons.Outlined.TaskAlt
            }
            if (online && !hasFile) {
                FilledTonalIconButton(onClick = { findFree = true }) {
                    val label = stringResource(R.string.free_ebook)
                    Icon(Icons.Outlined.CloudDownload, label)
                }
            }
            if (book.isLocal || position != null) {
                FilledTonalIconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.delete_book))
                }
            }
        }
    }
    if (findFree && !hasFile) FreeBookSheet(book, viewModel) { findFree = false }
    if (confirmDelete) {
        DeleteDialog(book, onDismiss = { confirmDelete = false }) {
            viewModel.delete(book)
            navigator.back()
        }
    }
}

@Composable
private fun ReadButton(
    book: Book,
    position: Progress?,
    hasFile: Boolean,
    navigator: Navigator,
    viewModel: BookViewModel,
) {
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { viewModel.import(book, it) }
    }
    fun open() =
        if (hasFile) navigator.reader(book.work) else picker.launch(arrayOf("*/*"))

    Button(onClick = ::open) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp))
        val label = position?.fraction?.let {
            stringResource(R.string.continue_percent, (it * 100).toInt())
        } ?: stringResource(if (position != null) R.string.resume else R.string.start)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

private enum class ReadingDate { Started, Finished }

@Composable
private fun ReadingCard(entry: Saved, onEdit: ((Saved) -> Saved) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<ReadingDate?>(null) }
    val started = entry.startedOn()
    val finished = entry.finishedOn()
    Surface(
        Modifier.fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .padding(bottom = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.your_reading),
                style = MaterialTheme.typography.titleMedium,
            )
            StarRating(entry.rating) { stars ->
                onEdit { it.copy(rating = if (it.rating == stars) 0 else stars) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val modifier = Modifier.weight(1f)
                DateTile(R.string.started_on, started, modifier) {
                    picking = ReadingDate.Started
                }
                if (entry.shelf == Shelf.Read) {
                    DateTile(R.string.finished_on, finished, modifier) {
                        picking = ReadingDate.Finished
                    }
                }
            }
            entry.daysToFinish()?.let { Hint(stringResource(R.string.read_in, days(it))) }
        }
    }
    val today = LocalDate.now()
    when (picking) {
        ReadingDate.Started -> ReadingDatePicker(
            started ?: finished ?: today,
            LocalDate.MIN..(finished ?: today),
            onDismiss = { picking = null },
        ) { day -> onEdit { it.copy(started = millisOf(day)) } }

        ReadingDate.Finished -> ReadingDatePicker(
            finished ?: today,
            (started ?: LocalDate.MIN)..today,
            onDismiss = { picking = null },
        ) { day -> onEdit { it.copy(finished = millisOf(day)) } }

        null -> Unit
    }
}

@Composable
private fun StarRating(rating: Int, onRate: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.offset(x = (-12).dp)) {
        (1..MAX_STARS).forEach { stars ->
            val filled = stars <= rating
            IconButton(onClick = { onRate(stars) }) {
                Icon(
                    if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    stringResource(R.string.rate_stars, stars),
                    Modifier.size(32.dp),
                    tint = if (filled) colors.primary else colors.outline,
                )
            }
        }
    }
}

@Composable
private fun DateTile(
    @StringRes label: Int,
    day: LocalDate?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Hint(stringResource(label))
            Text(
                day?.let { dateLabel(it) } ?: stringResource(R.string.add_date),
                style = MaterialTheme.typography.bodyLarge,
                color = if (day ==
                    null
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Unspecified
                },
            )
        }
    }
}

private fun utcMillis(day: LocalDate) = millisOf(day, ZoneOffset.UTC)

private fun utcDay(millis: Long) =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingDatePicker(
    initial: LocalDate,
    range: ClosedRange<LocalDate>,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(initial.coerceIn(range)),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                utcDay(utcTimeMillis) in range

            override fun isSelectableYear(year: Int) =
                year in range.start.year..range.endInclusive.year
        },
    )
    fun confirm() {
        state.selectedDateMillis?.let { onPick(utcDay(it)) }
        onDismiss()
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = state.selectedDateMillis != null) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) { DatePicker(state) }
}

@Composable
private fun DeleteDialog(book: Book, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_book)) },
        text = { Text(stringResource(R.string.delete_book_confirm, book.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ShelfToggle(
    checked: Boolean,
    @StringRes label: Int,
    onToggle: () -> Unit,
    icon: (Boolean) -> ImageVector,
) {
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onToggle() }) {
        Icon(icon(checked), contentDescription = stringResource(label))
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
            style = MaterialTheme.typography.bodyLarge,
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
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        tags.forEach { (id, label) ->
            SuggestionChip(onClick = { navigator.tag(id) }, label = { Text(label) })
        }
    }
}
