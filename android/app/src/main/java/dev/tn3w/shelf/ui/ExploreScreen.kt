package dev.tn3w.shelf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*

private val SECTIONS = listOf(
    "audience" to R.string.audience,
    "form" to R.string.formats,
    "topic" to R.string.topics,
)

class ExploreViewModel(app: ShelfApp) : ShelfViewModel(app) {
    val popular = catalogueState { recommender.popular(16) }
    val tags = catalogueState {
        catalogue.tags
            .filter { catalogue.tagCount(it.id) > 0 }
            .sortedByDescending { catalogue.tagCount(it.id) }
            .groupBy { it.category }
    }
    val missing = catalogueState { catalogue.workCount == 0 }
}

class TagViewModel(app: ShelfApp, id: Int) : ShelfViewModel(app) {
    val tag = catalogueState { catalogue.tags.getOrNull(id) }
    val books = catalogueState { recommender.popular(90, id) }
}

class SeriesViewModel(app: ShelfApp, work: Int) : ShelfViewModel(app) {
    val series = catalogueState { book(work)?.let(recommender::series) }
}

class AuthorViewModel(app: ShelfApp, author: Author) : ShelfViewModel(app) {
    val groups = catalogueState { recommender.authorShelf(author) }
    val born = catalogueState { catalogue.authorBorn(author) }
}

@Composable
fun ExploreScreen(navigator: Navigator) {
    val viewModel = shelfViewModel(create = ::ExploreViewModel)
    val popular by viewModel.popular.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()

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
        item { Spacer(Modifier.height(24.dp)) }
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
    val viewModel = shelfViewModel { TagViewModel(it, id) }
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back, tag?.label.orEmpty())
        BookGrid(books, "tag-$id", navigator::book)
    }
}

@Composable
fun SeriesScreen(work: Int, navigator: Navigator) {
    val series by shelfViewModel { SeriesViewModel(it, work) }.series
        .collectAsStateWithLifecycle()
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back)
        BookGrid(series?.second, "series-$work", navigator::book) {
            series?.let { (name, books) -> fullWidth { SeriesHeader(name, books.size) } }
        }
    }
}

@Composable
private fun SeriesHeader(name: String, count: Int) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            pluralStringResource(R.plurals.books_in_series, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AuthorScreen(author: Author, navigator: Navigator) {
    val viewModel = shelfViewModel { AuthorViewModel(it, author) }
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val born by viewModel.born.collectAsStateWithLifecycle()
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
