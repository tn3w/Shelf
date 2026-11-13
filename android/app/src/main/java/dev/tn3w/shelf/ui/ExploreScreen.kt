package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.SimilarKind
import dev.tn3w.shelf.SimilarRoute
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.max

private const val STARTS = 3
private const val SIMILAR_LIMIT = 40
private const val FAN_POOL = 24
private const val HERO_FAN = 5
private const val CARD_FAN = 3
private val DISLIKED = 1..2
private val HeroCover = 84.dp
private val CardCover = 56.dp
private val StartCover = 64.dp

private val SECTIONS = listOf(
    "audience" to R.string.audience,
    "form" to R.string.formats,
    "topic" to R.string.topics,
)

data class Start(val name: String, val route: SimilarRoute, val book: Book)

private fun Loaded.starts(saved: List<Saved>): List<Start> {
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

private fun startOf(book: Book, count: Int): Start {
    val author = book.authors.firstOrNull()
    if (author == null || author.number <= 0 || count < 2) {
        return Start(book.title, SimilarRoute(SimilarKind.Books, listOf(book.work)), book)
    }
    val route = SimilarRoute(SimilarKind.Writer, listOf(author.number), author.name)
    return Start(author.name, route, book)
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

data class Genre(val tag: Tag, val books: List<Book>, val count: Int)

private fun Loaded.genres(tags: List<Tag>): List<Genre> {
    val shown = mutableSetOf<Int>()
    return tags.map { tag ->
        val books = recommender.popular(FAN_POOL, tag.id)
            .asSequence()
            .filter { shown.add(it.work) }
            .take(HERO_FAN)
            .toList()
        Genre(tag, books, catalogue.tagCount(tag.id))
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
    val genres = catalogueState {
        val tags = catalogue.tags
            .filter { it.category == "genre" && catalogue.tagCount(it.id) > 0 }
            .sortedByDescending { catalogue.tagCount(it.id) }
        genres(tags).filter { it.books.isNotEmpty() }
    }
    val missing = catalogueState { catalogue.workCount == 0 }
}

class TagViewModel(app: ShelfApp, id: Int) : ShelfViewModel(app) {
    private val _sort = MutableStateFlow(TagSort.Popular)
    val sort = _sort.asStateFlow()
    val tag = catalogueState { catalogue.tags.getOrNull(id) }
    val missingPack = catalogueState {
        val pack = catalogue.tags.getOrNull(id)?.let(::packFor)
            ?: return@catalogueState null
        val language = catalogue.language
        val missing = app.packs.packs(language)
            .any { it.pack == pack && it.state == PackState.Available }
        if (missing) language to pack else null
    }
    val books = combine(loaded.filterNotNull(), sort) { current, order ->
        current.recommender.popular(90, id, order)
    }.asState()

    fun sortBy(order: TagSort) {
        _sort.value = order
    }

    fun download(language: String, pack: String) = app.download(language, pack)
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
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val hero = genres?.firstOrNull()

    LazyColumn(contentPadding = screenInsets()) {
        item { LargeTitle(stringResource(R.string.explore)) }
        if (missing == true) {
            item { NoCatalogue { navigator.settings(SettingsPage.Catalogue) } }
            return@LazyColumn
        }
        hero?.let { item { HeroCard(it) { navigator.tag(it.tag.id) } } }
        if (!starts.isNullOrEmpty()) {
            item { SectionHeader(stringResource(R.string.start_from)) }
            item { StartRow(starts.orEmpty()) { navigator.similar(it) } }
        }
        val rows = genres.orEmpty().drop(1).chunked(2)
        if (rows.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.genres)) }
            items(rows, key = { it.first().tag.id }) { row ->
                GenreRow(row) { navigator.tag(it) }
            }
        }
        SECTIONS.forEach { (category, title) ->
            val list = tags?.get(category) ?: return@forEach
            item { SectionHeader(stringResource(title)) }
            item { TagChips(list, navigator) }
        }
    }
}

/** Covers spread like a hand of cards, the first one on top in the middle. */
@Composable
private fun CoverFan(books: List<Book>, width: Dp, modifier: Modifier = Modifier) {
    val placed = books.mapIndexed { rank, book ->
        val spread = if (rank % 2 == 1) -(rank + 1) / 2 else rank / 2
        spread to book
    }
    Box(
        modifier.fillMaxWidth().height(width * 1.6f),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val backToFront = placed.sortedByDescending { (spread, _) -> abs(spread) }
        backToFront.forEach { (spread, book) ->
            val step = abs(spread)
            BookCover(
                book,
                width * (1f - step * 0.12f),
                Modifier.offset(x = width * 0.62f * spread, y = -(4.dp * step))
                    .rotate(spread * 6f),
                lift = 10.dp,
            )
        }
    }
}

@Composable
private fun Modifier.tintedCard(onClick: () -> Unit): Modifier {
    val colors = Look.colors
    val backdrop = Brush.verticalGradient(listOf(colors.glow, colors.raised))
    return surface().background(backdrop).clickable(onClick = onClick)
}

@Composable
private fun HeroCard(genre: Genre, onClick: () -> Unit) {
    CoverTheme(coverTone(genre.books.first())) {
        Column(
            Modifier.padding(horizontal = ScreenPadding)
                .padding(top = 8.dp)
                .tintedCard(onClick)
                .padding(top = 28.dp, bottom = 20.dp, start = 20.dp, end = 20.dp),
        ) {
            CoverFan(genre.books, HeroCover)
            Eyebrow(
                stringResource(R.string.popular),
                Modifier.padding(top = 24.dp),
                color = Look.colors.accent,
            )
            GenreTitle(genre, Look.type.title)
        }
    }
}

@Composable
private fun GenreTitle(genre: Genre, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(
                genre.tag.label,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Hint(pluralStringResource(R.plurals.books, genre.count, genre.count))
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = Look.colors.faint,
        )
    }
}

@Composable
private fun GenreRow(genres: List<Genre>, onOpen: (Int) -> Unit) {
    Row(
        Modifier.padding(horizontal = ScreenPadding).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        genres.forEach { genre ->
            CoverTheme(coverTone(genre.books.first())) {
                Column(
                    Modifier.weight(1f)
                        .tintedCard { onOpen(genre.tag.id) }
                        .padding(top = 20.dp, bottom = 14.dp, start = 14.dp, end = 8.dp),
                ) {
                    CoverFan(genre.books.take(CARD_FAN), CardCover)
                    Spacer(Modifier.height(14.dp))
                    GenreTitle(genre, Look.type.subhead)
                }
            }
        }
    }
}

@Composable
private fun StartRow(starts: List<Start>, onOpen: (SimilarRoute) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(starts, key = { it.route.ids.first() }) { start ->
            StartCard(start) { onOpen(start.route) }
        }
    }
}

@Composable
private fun StartCard(start: Start, onClick: () -> Unit) {
    val author = start.route.kind == SimilarKind.Writer
    val label =
        if (author) R.string.because_you_read_author else R.string.because_you_read
    CoverTheme(coverTone(start.book)) {
        Row(
            Modifier.width(280.dp)
                .tintedCard(onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(start.book, StartCover, lift = 10.dp)
            Column(Modifier.weight(1f)) {
                val icon = if (author) Icons.Rounded.Person else Icons.Rounded.AutoAwesome
                Icon(icon, null, Modifier.size(18.dp), Look.colors.accent)
                Text(
                    stringResource(label, start.name),
                    Modifier.padding(top = 8.dp),
                    style = Look.type.heading,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag -> Tag(tag.label) { navigator.tag(tag.id) } }
    }
}

@Composable
fun TagScreen(id: Int, navigator: Navigator) {
    val viewModel = shelfViewModel { TagViewModel(it, id) }
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val missingPack by viewModel.missingPack.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val offline = loadedSettings()?.isOffline(LocalContext.current) ?: true
    Column(Modifier.padding(top = statusBarHeight())) {
        BackBar(navigator::back)
        Text(
            tag?.label.orEmpty(),
            Modifier.padding(horizontal = ScreenPadding).padding(bottom = 14.dp),
            style = Look.type.display,
        )
        FilterRow {
            TagSort.entries.forEach { order ->
                Tag(stringResource(order.label), selected = order == sort) {
                    viewModel.sortBy(order)
                }
            }
        }
        BookGrid(books, "tag-$id", navigator::book, footer = {
            val (language, pack) = missingPack?.takeIf { !offline } ?: return@BookGrid
            fullWidth {
                MissingPack(pack, downloads["$language-$pack"]) {
                    viewModel.download(language, pack)
                }
            }
        })
    }
}

@Composable
private fun MissingPack(pack: String, download: Download?, onDownload: () -> Unit) {
    val label = stringResource(PACKS.getValue(pack))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val text = stringResource(R.string.pack_suggestion, label)
        EmptyState(Icons.Rounded.LibraryAdd, text)
        if (download is Download.Running) {
            return@Column Meter(download.progress, Modifier.width(160.dp))
        }
        val action = stringResource(R.string.download_item, label)
        Pill(action, kind = PillKind.Line, onClick = onDownload)
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
    CoverTheme(coverTone(series?.second?.firstOrNull())) {
        Column(Modifier.glow().padding(top = statusBarHeight())) {
            BackBar(navigator::back)
            BookGrid(
                series?.second,
                "series-$work",
                navigator::book,
                header = {
                    series?.let { (name, books) ->
                        val count = books.size
                        fullWidth {
                            val summary = R.plurals.books_in_series
                            PageHeader(name, pluralStringResource(summary, count, count))
                        }
                    }
                },
                footer = {
                    val route = SimilarRoute(SimilarKind.Saga, listOf(work))
                    moreRow(R.string.after_series, after, route, navigator)
                },
            )
        }
    }
}

@Composable
private fun PageHeader(title: String, summary: String, top: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        top()
        Text(title, style = Look.type.display, textAlign = TextAlign.Center)
        Hint(summary)
    }
}

@Composable
fun AuthorScreen(author: Author, navigator: Navigator) {
    val viewModel = shelfViewModel { AuthorViewModel(it, author) }
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val born by viewModel.born.collectAsStateWithLifecycle()
    val similar by viewModel.similar.collectAsStateWithLifecycle()
    val count = groups?.sumOf { it.books.size }
    val details = listOfNotNull(
        born?.takeIf { it > 0 }?.let { stringResource(R.string.born, it) },
        count?.let { pluralStringResource(R.plurals.books, it, it) },
    )
    Column(Modifier.padding(top = statusBarHeight())) {
        BackBar(navigator::back)
        GroupedBookGrid(
            groups,
            "author-${author.number}",
            navigator::book,
            header = {
                fullWidth {
                    PageHeader(author.name, details.joinToString(" · ")) {
                        AuthorAvatar(author, 104.dp, Modifier.padding(bottom = 10.dp))
                    }
                }
            },
            footer = {
                val route =
                    SimilarRoute(SimilarKind.Writer, listOf(author.number), author.name)
                moreRow(R.string.readers_also_like, similar, route, navigator)
            },
        )
    }
}

@Composable
fun SimilarScreen(route: SimilarRoute, navigator: Navigator) {
    val similar by shelfViewModel { SimilarViewModel(it, route) }.similar
        .collectAsStateWithLifecycle()
    val name = similar?.first.orEmpty()
    val title = if (name.isEmpty()) "" else stringResource(R.string.like_title, name)
    Column(Modifier.padding(top = statusBarHeight())) {
        BackBar(navigator::back, title)
        BookGrid(similar?.second, "like-${route.ids.joinToString("-")}", navigator::book)
    }
}
