package dev.tn3w.shelf.ui

import android.net.Uri
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.launch

private data class Details(
    val book: Book,
    val description: Description,
    val tags: List<Pair<Int, String>>,
)

private data class Related(
    val series: Pair<String, List<Book>>?,
    val byAuthor: List<Book>,
    val similar: List<Book>,
)

@Composable
fun BookScreen(work: Int, origin: String, navigator: Navigator) {
    val saved by shelfApp().library.saved.collectAsStateWithLifecycle(emptyList())
    val details by load(work) {
        val source = catalogueOf(work) ?: return@load null
        val book = source.book(work) ?: return@load null
        val tags = book.tags.mapNotNull { id ->
            source.tags.getOrNull(id)?.let { id to it.label }
        }
        Details(book, source.description(work), tags)
    }
    val entry = saved.firstOrNull { it.work == work }
    val book = details?.book ?: entry?.toBook()
    val related by load(work) {
        val found = book(work) ?: return@load null
        Related(
            recommender.series(found),
            recommender.byAuthor(found),
            recommender.similar(found),
        )
    }

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { BackBar(navigator::back) }
        if (book == null) return@LazyColumn
        item { BookHeader(book, entry?.shelf, origin, navigator) }
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
                BookSection(title, similar, "similar", navigator::book, shared = false)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BookHeader(book: Book, shelf: Shelf?, origin: String, navigator: Navigator) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val progress by app.library.progress.collectAsStateWithLifecycle(emptyMap())
    val position = progress[book.work]
    val hasFile by produceState(true, position) { value = app.library.hasFile(position) }
    val online = loadedSettings()?.isOffline(LocalContext.current) == false
    var confirmDelete by remember { mutableStateOf(false) }
    var findFree by remember { mutableStateOf(false) }
    val unplaced = if (book.isLocal) Shelf.Reading else null
    fun place(target: Shelf) = scope.launch {
        app.library.place(book, if (shelf == target) unplaced else target)
    }
    fun changeCover(image: Uri) = scope.launch {
        val cover = app.library.saveCover(book.work, image) ?: return@launch
        app.library.place(book.copy(cover = cover), shelf ?: Shelf.Reading)
    }

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
        book.authors
            .filter { it.number > 0 }
            .forEach { author ->
                TextButton(onClick = { navigator.author(author) }) {
                    Text(author.name, style = MaterialTheme.typography.titleMedium)
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
            ReadButton(book, position, online, navigator)
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
    if (findFree) {
        FreeBookSheet(book, onDismiss = { findFree = false }) {
            findFree = false
            navigator.reader(book.work)
        }
    }
    if (confirmDelete) {
        DeleteDialog(book, onDismiss = { confirmDelete = false }) {
            scope.launch {
                app.library.deleteBook(book.work, keepShelves = !book.isLocal)
                navigator.back()
            }
        }
    }
}

@Composable
private fun ReadButton(
    book: Book,
    position: Progress?,
    online: Boolean,
    navigator: Navigator,
) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val settings = loadedSettings()
    var download by remember { mutableStateOf<Float?>(null) }
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val work = book.work.takeIf { app.library.importBook(book, uri) }
            navigator.openImported(work, app)
        }
    }
    suspend fun downloaded(): Boolean {
        val chosen = settings?.takeIf { online && it.instantSource.isNotEmpty() }
            ?: return false
        download = 0f
        val done = app.downloadInstantly(book, chosen) { download = it }
        download = null
        if (!done) Toast.makeText(app, R.string.free_books_failed, LENGTH_SHORT).show()
        return done
    }
    fun open() = scope.launch {
        val ready = app.library.hasFile(position) || downloaded()
        if (ready) navigator.reader(book.work) else picker.launch(arrayOf("*/*"))
    }

    Button(onClick = ::open, enabled = download == null) {
        val progress = download
        if (progress == null) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp))
        } else {
            DownloadProgress(progress, 18.dp)
        }
        val label = position?.fraction?.let {
            stringResource(R.string.continue_percent, (it * 100).toInt())
        } ?: stringResource(if (position != null) R.string.resume else R.string.start)
        Text(label, Modifier.padding(start = 8.dp))
    }
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
    var expanded by remember { mutableStateOf(false) }
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
