package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.SimilarKind
import dev.tn3w.shelf.SimilarRoute
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.flow.*
import kotlin.math.max

private const val STARTS = 3
private const val SIMILAR_LIMIT = 40
private val DISLIKED = 1..2

private val SECTIONS = listOf(
    "audience" to R.string.audience,
    "form" to R.string.formats,
    "topic" to R.string.topics,
)

private fun Loaded.starts(saved: List<Saved>): List<Pair<String, SimilarRoute>> {
    val recent = saved
        .filter { it.shelf != Shelf.Want && it.rating !in DISLIKED }
        .sortedByDescending { max(it.updated, it.added) }
    val counts = recent.groupingBy { it.author }.eachCount()
    return recent
        .distinctBy { it.author }
        .asSequence()
        .mapNotNull { entry ->
            seedBook(entry.work)?.let { startOf(it, counts.getValue(entry.author)) }
        }
        .take(STARTS)
        .toList()
}

private fun startOf(book: Book, count: Int): Pair<String, SimilarRoute> {
    val author = book.authors.firstOrNull()
    if (author == null || author.number <= 0 || count < 2) {
        return book.title to SimilarRoute(SimilarKind.Books, listOf(book.work))
    }
    return author.name to
        SimilarRoute(SimilarKind.Writer, listOf(author.number), author.name)
}

private fun Loaded.seedOf(route: SimilarRoute): Pair<String, RecommendSeed>? {
    if (route.kind == SimilarKind.Writer) {
        val author = Author(route.ids.first(), route.name)
        return author.name to RecommendSeed.Writer(author)
    }
    val books = route.ids.mapNotNull(::seedBook)
    val first = books.firstOrNull() ?: return null
    return when {
        route.kind == SimilarKind.Saga ->
            (recommender.series(first)?.first ?: first.title) to RecommendSeed.Saga(first)

        books.size == 1 -> first.title to RecommendSeed.Work(first)

        else -> books.joinToString(", ") { it.title } to RecommendSeed.Mix(books)
    }
}

class ExploreViewModel(app: ShelfApp) : ShelfViewModel(app) {
    val starts = combine(loaded.filterNotNull(), library.saved) { current, saved ->
        current.starts(saved)
    }.asState()
    val tags = catalogueState {
        catalogue.tags
            .filter { catalogue.tagCount(it.id) > 0 }
            .sortedByDescending { catalogue.tagCount(it.id) }
            .groupBy { it.category }
    }
    val missing = catalogueState { catalogue.workCount == 0 }
}

class TagViewModel(app: ShelfApp, id: Int) : ShelfViewModel(app) {
    private val _sort = MutableStateFlow(TagSort.Popular)
    val sort = _sort.asStateFlow()
    val tag = catalogueState { catalogue.tags.getOrNull(id) }
    val books = combine(loaded.filterNotNull(), sort) { current, order ->
        current.recommender.popular(90, id, order)
    }.asState()

    fun sortBy(order: TagSort) {
        _sort.value = order
    }
}

class SeriesViewModel(app: ShelfApp, work: Int) : ShelfViewModel(app) {
    val series = catalogueState { book(work)?.let(recommender::series) }
    val after = catalogueState {
        seedBook(work)?.let { recommender.recommended(RecommendSeed.Saga(it)) }
    }
}

class AuthorViewModel(app: ShelfApp, author: Author) : ShelfViewModel(app) {
    val groups = catalogueState { recommender.authorShelf(author) }
    val born = catalogueState { catalogue.authorBorn(author) }
    val similar = catalogueState { recommender.recommended(RecommendSeed.Writer(author)) }
}

class SimilarViewModel(app: ShelfApp, route: SimilarRoute) : ShelfViewModel(app) {
    val similar = catalogueState {
        val (name, seed) = seedOf(route) ?: return@catalogueState "" to emptyList<Book>()
        name to recommender.recommended(seed, SIMILAR_LIMIT)
    }
}

@Composable
fun ExploreScreen(navigator: Navigator) {
    val viewModel = shelfViewModel(create = ::ExploreViewModel)
    val starts by viewModel.starts.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { LargeTitle(stringResource(R.string.explore)) }
        if (missing == true) {
            item { NoCatalogue { navigator.settings(SettingsPage.Catalogue) } }
            return@LazyColumn
        }
        if (!starts.isNullOrEmpty()) {
            item { SectionHeader(stringResource(R.string.start_from)) }
            items(starts.orEmpty()) { (name, route) ->
                val author = route.kind == SimilarKind.Writer
                StartRow(name, author) { navigator.similar(route) }
            }
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

@Composable
private fun StartRow(name: String, author: Boolean, onClick: () -> Unit) {
    val label =
        if (author) R.string.because_you_read_author else R.string.because_you_read
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label, name),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back, tag?.label.orEmpty())
        FilterRow {
            TagSort.entries.forEach { order ->
                FilterChip(
                    selected = order == sort,
                    onClick = { viewModel.sortBy(order) },
                    label = { Text(stringResource(order.label)) },
                )
            }
        }
        BookGrid(books, "tag-$id", navigator::book)
    }
}

private fun LazyGridScope.moreRow(
    @StringRes title: Int,
    books: List<Book>?,
    route: SimilarRoute,
    navigator: Navigator,
) {
    if (books.isNullOrEmpty()) return
    fullBleed {
        BookSection(
            stringResource(title), books, "more", navigator::book,
            onMore = { navigator.similar(route) }, shared = false,
        )
    }
}

@Composable
fun SeriesScreen(work: Int, navigator: Navigator) {
    val viewModel = shelfViewModel { SeriesViewModel(it, work) }
    val series by viewModel.series.collectAsStateWithLifecycle()
    val after by viewModel.after.collectAsStateWithLifecycle()
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back)
        BookGrid(
            series?.second,
            "series-$work",
            navigator::book,
            header = {
                series?.let { (name, books) ->
                    fullWidth { SeriesHeader(name, books.size) }
                }
            },
            footer = {
                val route = SimilarRoute(SimilarKind.Saga, listOf(work))
                moreRow(R.string.after_series, after, route, navigator)
            },
        )
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
    val similar by viewModel.similar.collectAsStateWithLifecycle()
    val count = groups?.sumOf { it.books.size }
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back)
        GroupedBookGrid(
            groups,
            "author-${author.number}",
            navigator::book,
            header = { fullWidth { AuthorHeader(author, born ?: 0, count) } },
            footer = {
                val route =
                    SimilarRoute(SimilarKind.Writer, listOf(author.number), author.name)
                moreRow(R.string.readers_also_like, similar, route, navigator)
            },
        )
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

@Composable
fun SimilarScreen(route: SimilarRoute, navigator: Navigator) {
    val similar by shelfViewModel { SimilarViewModel(it, route) }.similar
        .collectAsStateWithLifecycle()
    val name = similar?.first.orEmpty()
    val title = if (name.isEmpty()) "" else stringResource(R.string.like_title, name)
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back, title)
        BookGrid(similar?.second, "like-${route.ids.joinToString("-")}", navigator::book)
    }
}
