package dev.tn3w.shelf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Author
import dev.tn3w.shelf.data.Shelf
import dev.tn3w.shelf.data.Tag
import dev.tn3w.shelf.data.toBook

private val SECTIONS =
    listOf(
        "audience" to R.string.audience,
        "form" to R.string.formats,
        "topic" to R.string.topics,
    )
private val FILTERS =
    listOf(
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

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { LargeTitle(stringResource(R.string.explore)) }
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
                onClick = { navigator.tag(tag.id) },
                label = { Text(tag.label) },
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
    val books by load(author) { recommender.authorBooks(author) }
    val born by load(author) { catalogue.authorBorn(author) }
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back)
        BookGrid(books, "author-${author.number}", navigator::book) {
            fullWidth { AuthorHeader(author.name, born ?: 0, books?.size) }
        }
    }
}

@Composable
private fun AuthorHeader(name: String, born: Int, count: Int?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name
                        .split(" ")
                        .mapNotNull { it.firstOrNull() }
                        .take(2)
                        .joinToString(""),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        val details =
            listOfNotNull(
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
    val saved by shelfApp().library.saved.collectAsStateWithLifecycle(null)
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shelf = FILTERS[filter].second
    val shown = saved?.filter { shelf == null || it.shelf == shelf }?.map { it.toBook() }

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        LargeTitle(stringResource(R.string.library))
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
}
