package dev.tn3w.shelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Author
import dev.tn3w.shelf.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Results(
    val completions: List<String>,
    val authors: List<Author>,
    val books: List<Book>,
)

@Composable
fun SearchScreen(navigator: Navigator) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val loaded by app.loaded.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Results?>(null) }
    val recent by app.library.recentSearches.collectAsStateWithLifecycle(emptyList())
    val trending by load { recommender.popular(8) }

    LaunchedEffect(query, loaded) {
        val current = loaded ?: return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect run { results = null }
        delay(150)
        results =
            withContext(Dispatchers.Default) {
                val searcher = current.searcher
                Results(
                    searcher.complete(query),
                    searcher.authors(query),
                    searcher.search(query),
                )
            }
    }

    fun saveQuery() = scope.launch { if (query.isNotBlank()) app.library.remember(query) }

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        LargeTitle(stringResource(R.string.search))
        SearchField(query, onChange = { query = it }, onSubmit = { saveQuery() })
        LazyColumn {
            val current = results
            if (current == null) {
                suggestions(recent, trending.orEmpty(), { query = it }) {
                    scope.launch { app.library.clearSearches() }
                }
                return@LazyColumn
            }
            if (current.authors.isEmpty() && current.books.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.SearchOff,
                        stringResource(R.string.no_results),
                    )
                }
            }
            val completions =
                current.completions.filter { it != query.trim().lowercase() }
            items(completions, key = { "completion-$it" }) {
                SuggestionRow(Icons.Outlined.Search, it, Modifier.animateItem()) {
                    query = "$it "
                }
            }
            items(current.authors, key = { "author-${it.number}" }) {
                SuggestionRow(Icons.Outlined.Person, it.name, Modifier.animateItem()) {
                    saveQuery()
                    navigator.author(it)
                }
            }
            items(current.books, key = { "book-${it.work}" }) { book ->
                Column(Modifier.animateItem()) {
                    BookListItem(book, "search") { opened, origin ->
                        saveQuery()
                        navigator.book(opened, origin)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isEmpty()) return@TextField
            IconButton(onClick = { onChange("") }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.clear),
                )
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
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
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
        items(recent) { SuggestionRow(Icons.Outlined.History, it) { onQuery(it) } }
    }
    if (trending.isEmpty()) return
    item { SectionHeader(stringResource(R.string.trending)) }
    items(trending, key = { it.work }) {
        SuggestionRow(Icons.AutoMirrored.Outlined.TrendingUp, it.title) {
            onQuery(it.title)
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
