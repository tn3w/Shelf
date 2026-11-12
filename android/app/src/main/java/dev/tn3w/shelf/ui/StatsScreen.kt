package dev.tn3w.shelf.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

private const val GENRE = "genre"
private const val TOP_GENRES = 5

private fun Loaded?.genresOf(work: Int): List<Tag> {
    val catalogue = this?.catalogueOf(work) ?: return emptyList()
    val tags = catalogue.facts(work)?.tags ?: return emptyList()
    return tags.toList()
        .mapNotNull(catalogue.tags::getOrNull)
        .filter { it.category == GENRE }
}

internal fun recapState(
    saved: List<Saved>,
    activity: Map<String, Int>,
    loaded: Loaded?,
    year: Int,
) = recapOf(year, saved, activity, genresOf = { loaded.genresOf(it) })

class StatsViewModel(app: ShelfApp) : ShelfViewModel(app) {
    private val _year = MutableStateFlow(LocalDate.now().year)
    val year = _year.asStateFlow()
    val years = combine(library.saved, library.activity) { saved, activity ->
        statsYears(saved, activity)
    }.asState()
    val recap = combine(library.saved, library.activity, loaded, year, ::recapState)
        .asState()

    fun choose(year: Int) {
        _year.value = year
    }
}

@Composable
fun StatsScreen(navigator: Navigator) {
    val viewModel = shelfViewModel(create = ::StatsViewModel)
    val years by viewModel.years.collectAsStateWithLifecycle()
    val year by viewModel.year.collectAsStateWithLifecycle()
    val recap by viewModel.recap.collectAsStateWithLifecycle()
    val current = recap?.takeIf { it.year == year }

    LazyColumn(contentPadding = screenInsets()) {
        item { BackBar(navigator::back) }
        item { LargeTitle(stringResource(R.string.stats)) }
        if (years.orEmpty().size > 1) {
            item { YearChips(years.orEmpty(), year, viewModel::choose) }
        }
        if (current == null) return@LazyColumn
        if (current.isEmpty) {
            item {
                val text = stringResource(R.string.stats_empty)
                EmptyState(Icons.Rounded.Insights, text)
            }
            return@LazyColumn
        }
        item { WrappedBanner(current) { navigator.wrapped(year) } }
        item { SectionHeader(stringResource(R.string.month_by_month)) }
        item { MonthSection(current) }
        if (current.genres.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.genres)) }
            item { GenreBars(current.genres) }
        }
        if (current.books.isNotEmpty()) readingLog(current, navigator)
        item {
            Hint(
                stringResource(R.string.stats_private),
                Modifier.padding(horizontal = ScreenPadding, vertical = 24.dp),
            )
        }
    }
}

private fun LazyListScope.readingLog(recap: Recap, navigator: Navigator) {
    item {
        val count = recap.books.size
        val average = recap.averageRating?.let {
            val locale = LocalConfiguration.current.locales[0]
            stringResource(R.string.average_rating, "★ %.1f".format(locale, it))
        }
        val books = pluralStringResource(R.plurals.books, count, count)
        SectionHeader(
            stringResource(R.string.finished_in, recap.year),
            listOfNotNull(books, average).joinToString(" · "),
        )
    }
    recap.months.reversed().filter { it.books.isNotEmpty() }.forEach { month ->
        item(key = "month-${month.month}") {
            Eyebrow(
                monthName(month.month),
                Modifier.padding(horizontal = ScreenPadding)
                    .padding(top = 16.dp, bottom = 6.dp),
                Look.colors.accent,
            )
        }
        items(month.books.reversed(), key = { it.work }) { saved ->
            BookListItem(
                saved.toBook(),
                "stats-${recap.year}",
                detail = logDetail(saved),
                onOpen = navigator::book,
            )
        }
    }
}

@Composable
private fun logDetail(saved: Saved) = listOfNotNull(
    saved.finishedOn()?.let { dateLabel(it) },
    saved.rating.takeIf { it > 0 }?.let { "★".repeat(it) },
    saved.daysToFinish()?.let { days(it) },
).joinToString(" · ")

@Composable
private fun YearChips(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    FilterRow {
        years.forEach { year ->
            Tag("$year", selected = year == selected) { onSelect(year) }
        }
    }
}

@Composable
private fun MonthSection(recap: Recap) {
    var byBooks by rememberSaveable { mutableStateOf(false) }
    val active = recap.months.lastOrNull { it.pages > 0 || it.allBooks.isNotEmpty() }
    var chosen by rememberSaveable(recap.year) {
        mutableStateOf(active?.month?.monthValue)
    }
    val selected = recap.months.firstOrNull { it.month.monthValue == chosen }
    val value = { month: MonthStats -> if (byBooks) month.allBooks.size else month.pages }
    Column(
        Modifier.padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MetricPicker(byBooks) { byBooks = it }
        MonthChart(recap.months, value, selected?.month) { chosen = it.monthValue }
        selected?.let { MonthDetail(it) }
    }
}

@Composable
private fun MetricPicker(byBooks: Boolean, onSelect: (Boolean) -> Unit) {
    Segments(listOf(false, true), byBooks, onSelect = onSelect) {
        stringResource(if (it) R.string.books_metric else R.string.pages_metric)
    }
}

@Composable
private fun MonthChart(
    months: List<MonthStats>,
    value: (MonthStats) -> Int,
    selected: YearMonth?,
    onSelect: (YearMonth) -> Unit,
) {
    val highest = months.maxOf(value).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        months.forEach { month ->
            MonthBar(
                monthName(month.month, TextStyle.NARROW_STANDALONE),
                value(month).toFloat() / highest,
                month.month == selected,
                Modifier.weight(1f),
            ) { onSelect(month.month) }
        }
    }
}

@Composable
private fun MonthBar(
    label: String,
    fraction: Float,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val height by animateFloatAsState(fraction.coerceAtLeast(0.03f))
    val colors = Look.colors
    val color = when {
        selected -> colors.accent
        fraction > 0 && colors.plain -> colors.soft
        fraction > 0 -> colors.accent.copy(alpha = 0.32f)
        else -> colors.sunken
    }
    Column(
        modifier.fillMaxHeight()
            .clip(Corners.well)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color),
            )
        }
        Eyebrow(
            label,
            Modifier.padding(top = 8.dp),
            if (selected) colors.accent else colors.faint,
        )
    }
}

@Composable
private fun MonthDetail(month: MonthStats) {
    val summary = listOf(
        pluralStringResource(R.plurals.pages_count, month.pages, month.pages),
        pluralStringResource(R.plurals.books, month.allBooks.size, month.allBooks.size),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(monthName(month.month), style = Look.type.heading)
        Hint(summary.joinToString(" · "))
        if (month.genres.isEmpty()) return@Column
        Hint(month.genres.joinToString(" · ") { it.label }, color = Look.colors.accent)
    }
}

@Composable
private fun GenreBars(genres: List<GenreStats>) {
    val highest = genres.maxOf { it.books.size }
    Column(
        Modifier.padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        genres.take(TOP_GENRES).forEach { (tag, books) ->
            val count = books.size
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tag.label, Modifier.weight(1f), style = Look.type.subhead)
                    Hint(pluralStringResource(R.plurals.books, count, count))
                }
                Meter(count.toFloat() / highest, height = 8.dp)
            }
        }
    }
}
