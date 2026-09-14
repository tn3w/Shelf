package dev.tn3w.shelf.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Book
import dev.tn3w.shelf.data.Shelf
import dev.tn3w.shelf.data.toBook
import kotlinx.coroutines.launch

private data class Details(
    val book: Book,
    val description: String,
    val tags: List<Pair<Int, String>>,
)

@Composable
fun BookScreen(work: Int, origin: String, navigator: Navigator) {
    val saved by shelfApp().library.saved.collectAsStateWithLifecycle(emptyList())
    val details by
        load(work) {
            val book = catalogue.book(work) ?: return@load null
            val tags =
                book.tags.mapNotNull { id ->
                    catalogue.tags.getOrNull(id)?.let { id to it.label }
                }
            Details(book, catalogue.description(work), tags)
        }
    val fallback = saved.firstOrNull { it.work == work }?.toBook()
    val book = details?.book ?: fallback
    val series by load(work) { catalogue.book(work)?.let(recommender::series) }
    val byAuthor by load(work) { catalogue.book(work)?.let(recommender::byAuthor) }
    val similar by load(work) { catalogue.book(work)?.let(recommender::similar) }

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { BackBar(navigator::back) }
        if (book == null) return@LazyColumn
        item { BookHeader(book, origin, navigator) }
        details
            ?.tags
            ?.takeIf { it.isNotEmpty() }
            ?.let { tags ->
                item { TagFlow(tags, navigator) }
            }
        details
            ?.description
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                item { SectionHeader(stringResource(R.string.about_book)) }
                item { Description(text) }
            }
        series
            ?.takeIf { it.second.size > 1 }
            ?.let { (name, books) ->
                item {
                    SectionHeader(
                        name,
                        pluralStringResource(
                            R.plurals.books_in_series,
                            books.size,
                            books.size,
                        ),
                    )
                }
                item { BookRow(books, "series", navigator::book, shared = false) }
            }
        byAuthor
            ?.takeIf { it.isNotEmpty() }
            ?.let { books ->
                val author = book.authors.first()
                item {
                    SectionHeader(
                        stringResource(R.string.more_by, author.name),
                        onMore = {
                            navigator.author(author)
                        },
                    )
                }
                item { BookRow(books, "author", navigator::book, shared = false) }
            }
        similar
            ?.takeIf { it.isNotEmpty() }
            ?.let { books ->
                item { SectionHeader(stringResource(R.string.similar)) }
                item { BookRow(books, "similar", navigator::book, shared = false) }
            }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BookHeader(book: Book, origin: String, navigator: Navigator) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val saved by app.library.saved.collectAsStateWithLifecycle(emptyList())
    val progress by app.library.progress.collectAsStateWithLifecycle(emptyMap())
    val shelf = saved.firstOrNull { it.work == book.work }?.shelf
    val position = progress[book.work]
    val unsupported = stringResource(R.string.unsupported_file)
    fun place(target: Shelf) = scope.launch {
        app.library.place(book, if (shelf == target) null else target)
    }
    val picker =
        rememberLauncherForActivityResult(OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                if (app.library.importBook(book, uri))
                    return@launch navigator.reader(book.work)
                Toast.makeText(app, unsupported, Toast.LENGTH_LONG).show()
            }
        }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BookCover(
            book,
            180.dp,
            Modifier.padding(vertical = 16.dp),
            "$origin-${book.work}",
        )
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
        Text(
            facts(book),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (position != null) navigator.reader(book.work)
                    else picker.launch(arrayOf("*/*"))
                }
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp))
                val label =
                    position?.fraction?.let {
                        stringResource(R.string.continue_percent, (it * 100).toInt())
                    }
                        ?: stringResource(
                            if (position != null) R.string.resume else R.string.start
                        )
                Text(label, Modifier.padding(start = 8.dp))
            }
            ShelfToggle(
                shelf == Shelf.Want,
                R.string.want_to_read,
                { place(Shelf.Want) },
            ) {
                if (it) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd
            }
            ShelfToggle(
                shelf == Shelf.Read,
                R.string.mark_finished,
                { place(Shelf.Read) },
            ) {
                if (it) Icons.Outlined.CheckCircle else Icons.Outlined.TaskAlt
            }
        }
    }
}

@Composable
private fun ShelfToggle(
    checked: Boolean,
    label: Int,
    onToggle: () -> Unit,
    icon: (Boolean) -> androidx.compose.ui.graphics.vector.ImageVector,
) {
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onToggle() }) {
        Icon(icon(checked), contentDescription = stringResource(label))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun facts(book: Book): String {
    val popularity = book.popularity
    val locale = LocalConfiguration.current.locales[0]
    return listOfNotNull(
            book.year.takeIf { it > 0 }?.toString(),
            popularity
                ?.takeIf { it.ratings > 0 }
                ?.let { "★ " + String.format(locale, "%.1f", it.rating) },
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
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = if (expanded) Int.MAX_VALUE else 6,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.padding(horizontal = ScreenPadding).animateContentSize().clickable {
                expanded = !expanded
            },
    )
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
