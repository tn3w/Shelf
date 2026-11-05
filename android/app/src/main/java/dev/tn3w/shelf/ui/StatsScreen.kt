package dev.tn3w.shelf.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.*
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

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item { BackBar(navigator::back, stringResource(R.string.stats)) }
        if (years.orEmpty().size > 1) {
            item { YearChips(years.orEmpty(), year, viewModel::choose) }
        }
        if (current == null) return@LazyColumn
        if (current.isEmpty) {
            item {
                val text = stringResource(R.string.stats_empty)
                EmptyState(Icons.Outlined.Insights, text)
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
        if (current.books.isNotEmpty()) {
            item {
                BookSection(
                    stringResource(R.string.finished_in, year),
                    current.books.reversed().map { it.toBook() },
                    "stats-$year",
                    navigator::book,
                )
            }
        }
        item {
            Hint(
                stringResource(R.string.stats_private),
                Modifier.padding(horizontal = ScreenPadding, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun YearChips(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    FilterRow {
        years.forEach { year ->
            FilterChip(
                selected = year == selected,
                onClick = { onSelect(year) },
                label = { Text("$year") },
            )
        }
    }
}

@Composable
private fun MonthSection(recap: Recap) {
    var byBooks by rememberSaveable { mutableStateOf(false) }
    val active = recap.months.lastOrNull { it.pages > 0 || it.books.isNotEmpty() }
    var chosen by rememberSaveable(recap.year) {
        mutableStateOf(active?.month?.monthValue)
    }
    val selected = recap.months.firstOrNull { it.month.monthValue == chosen }
    val value = { month: MonthStats -> if (byBooks) month.books.size else month.pages }
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
    val options = listOf(R.string.pages_metric to false, R.string.books_metric to true)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, books) ->
            SegmentedButton(
                selected = books == byBooks,
                onClick = { onSelect(books) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label)) },
            )
        }
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
    val colors = MaterialTheme.colorScheme
    val color = when {
        selected -> colors.primary
        fraction > 0 -> colors.primary.copy(alpha = 0.35f)
        else -> colors.surfaceContainerHigh
    }
    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
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
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun MonthDetail(month: MonthStats) {
    val summary = listOf(
        pluralStringResource(R.plurals.pages_count, month.pages, month.pages),
        pluralStringResource(R.plurals.books, month.books.size, month.books.size),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(monthName(month.month), style = MaterialTheme.typography.titleMedium)
        Text(
            summary.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (month.genres.isEmpty()) return@Column
        Text(
            month.genres.joinToString(" · ") { it.label },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(
                        tag.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pluralStringResource(R.plurals.books, count, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { count.toFloat() / highest },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    drawStopIndicator = {},
                )
            }
        }
    }
}
