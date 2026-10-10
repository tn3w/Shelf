package dev.tn3w.shelf.ui

import android.Manifest.permission.CAMERA
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
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
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*

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
    var addingOwn by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(query, loaded) {
        val current = loaded ?: return@LaunchedEffect
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(150)
        results = withContext(Dispatchers.Default) {
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
        SearchField(query, { query = it }, { saveQuery() }, scan(navigator))
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
                        Icons.Outlined.SearchOff, stringResource(R.string.no_results),
                    )
                }
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
            items(current.books, key = { "book-${it.work}" }) { book ->
                Column(Modifier.animateItem()) {
                    BookListItem(book, "search") { opened, origin ->
                        saveQuery()
                        navigator.book(opened, origin)
                    }
                }
            }
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

@Composable
private fun scan(navigator: Navigator): (() -> Unit)? {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
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
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScan: (() -> Unit)?,
) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
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
