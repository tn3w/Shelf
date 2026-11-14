package dev.tn3w.shelf.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle

private val HOME_TAGS =
    listOf("fantasy", "mystery", "classics", "science-fiction", "romance")
private val Flame = Color(0xFFF08A1C)
private val HeroHeight = 196.dp

class HomeViewModel(app: ShelfApp) : ShelfViewModel(app) {
    val rows = combine(loaded.filterNotNull(), library.saved, library.dismissed, ::Triple)
        .map { (current, saved, dismissed) ->
            current.recommender.rows(saved, session, dismissed)
        }
        .asState()
    val missing = catalogueState { catalogue.workCount == 0 }
    val wrapped = combine(library.saved, library.activity, dates()) { saved, pages, day ->
        wrappedYear(day)?.let { recapOf(it, saved, pages) }?.takeUnless { it.isEmpty }
    }.asState()
    val habit = library.habit.asState()
    val progress = library.progress.asState()
    val genres = catalogueState {
        HOME_TAGS.mapNotNull { catalogue.tagBySlug[it] }
            .map { it to recommender.popular(12, it.id) }
    }

    fun dismiss(book: Book) {
        detached { library.dismiss(book.work) }
    }

    fun resumeAudio(entry: Saved) = detached {
        if (app.audiobooks.open(entry.work, entry.title)) app.audiobooks.play()
    }
}

@Composable
fun HomeScreen(navigator: Navigator) {
    val viewModel = shelfViewModel(create = ::HomeViewModel)
    val library = viewModel.library
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val habit by viewModel.habit.collectAsStateWithLifecycle()
    val settings = loadedSettings() ?: Settings()
    val scope = rememberCoroutineScope()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    fun dismiss(book: Book) = viewModel.dismiss(book)
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val wrapped by viewModel.wrapped.collectAsStateWithLifecycle()
    val reading = saved.orEmpty().filter { it.shelf == Shelf.Reading }
    val want = saved.orEmpty().filter { it.shelf == Shelf.Want }.map { it.toBook() }
    fun resume(entry: Saved) = scope.launch {
        if (library.hasFile(progress?.get(entry.work))) {
            navigator.reader(entry.work, listen = library.isListening(entry.work))
        } else {
            if (library.isListening(entry.work)) viewModel.resumeAudio(entry)
            navigator.book(entry.toBook(), "continue")
        }
    }

    LazyColumn(contentPadding = screenInsets()) {
        item { HomeTitle(navigator) }
        if (reading.isNotEmpty()) {
            item { ContinueReading(reading, progress.orEmpty(), ::resume) }
        }
        habit?.takeIf { settings.dailyGoal > 0 }?.let {
            item { HabitCard(it, navigator::stats) }
        }
        wrapped?.let { recap ->
            item { WrappedBanner(recap) { navigator.wrapped(recap.year) } }
        }
        if (want.isNotEmpty()) {
            item {
                BookSection(
                    stringResource(R.string.want_to_read),
                    want,
                    "want",
                    navigator::book,
                    subtitle = stringResource(R.string.want_to_read_subtitle),
                    onMore = navigator::library,
                )
            }
        }
        if (missing == true) {
            item { NoCatalogue { navigator.settings(SettingsPage.Catalogue) } }
            return@LazyColumn
        }
        rows.orEmpty().filter { it.books.isNotEmpty() }.forEach { row ->
            item(key = row.key) {
                BookSection(
                    rowTitle(row),
                    row.books,
                    row.key,
                    navigator::book,
                    subtitle = rowSubtitle(row),
                    onDismiss = ::dismiss,
                )
            }
        }
        genres.orEmpty().forEach { (tag, books) ->
            item(key = tag.slug) {
                BookSection(
                    tag.label,
                    books,
                    tag.slug,
                    navigator::book,
                    onMore = { navigator.tag(tag.id) },
                )
            }
        }
    }
}

@Composable
private fun HomeTitle(navigator: Navigator) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = ScreenPadding, end = 10.dp, top = 28.dp, bottom = 18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(dateLabel(LocalDate.now()))
            Text(stringResource(R.string.home), style = Look.type.display)
        }
        IconAction(
            Icons.Rounded.Insights, stringResource(R.string.stats),
            tint = Look.colors.soft, onClick = navigator::stats,
        )
        IconAction(
            Icons.Rounded.Tune, stringResource(R.string.settings),
            tint = Look.colors.soft,
        ) { navigator.settings() }
    }
}

@Composable
private fun ContinueReading(
    reading: List<Saved>,
    progress: Map<Int, Progress>,
    onOpen: (Saved) -> Unit,
) {
    val pager = rememberPagerState { reading.size }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            pager,
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            pageSpacing = 12.dp,
            key = { reading[it].work },
        ) { page ->
            val entry = reading[page]
            ContinueCard(entry.toBook(), progress[entry.work]?.fraction) { onOpen(entry) }
        }
        if (reading.size > 1) PageDots(reading.size, pager.currentPage)
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { index ->
            val color = if (index == current) Look.colors.ink else Look.colors.line
            Box(Modifier.size(6.dp).background(color, CircleShape))
        }
    }
}

/** The book being read, in its own colours: tap anywhere to pick it up again. */
@Composable
private fun ContinueCard(book: Book, progress: Float?, onOpen: () -> Unit) {
    CoverTheme(coverTone(book)) {
        val colors = Look.colors
        val backdrop = Brush.linearGradient(listOf(colors.glow, colors.raised))
        Row(
            Modifier.fillMaxWidth()
                .height(HeroHeight)
                .surface()
                .background(backdrop)
                .clickable(onClick = onOpen)
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BookCover(book, modifier = Modifier.fillMaxHeight(), lift = 14.dp)
            Column(Modifier.fillMaxHeight()) {
                Eyebrow(stringResource(R.string.continue_reading), color = colors.accent)
                Text(
                    book.title,
                    Modifier.padding(top = 8.dp),
                    style = Look.type.heading,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Hint(book.author, Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                val label = progress?.let {
                    stringResource(R.string.percent_read, (it * 100).toInt())
                } ?: stringResource(R.string.import_to_start)
                Text(label, Modifier.padding(bottom = 8.dp), style = Look.type.label)
                Meter(progress ?: 0f)
            }
        }
    }
}

@Composable
private fun rowTitle(row: HomeRow) = when (row.kind) {
    RowKind.Series -> stringResource(R.string.next_in_series)

    RowKind.Author -> stringResource(R.string.more_by, row.author?.name.orEmpty())

    RowKind.Popular -> stringResource(R.string.popular)

    RowKind.Because ->
        row.sources.firstOrNull()?.let {
            stringResource(R.string.because_you_read, mainTitle(it.title))
        } ?: stringResource(R.string.for_you)
}

@Composable
private fun rowSubtitle(row: HomeRow) = when (row.kind) {
    RowKind.Series -> stringResource(R.string.next_in_series_subtitle)

    RowKind.Popular -> stringResource(R.string.popular_subtitle)

    RowKind.Because ->
        if (row.sources.isEmpty()) stringResource(R.string.for_you_subtitle) else null

    else -> null
}

@Composable
private fun HabitCard(habit: Habit, onOpen: () -> Unit) {
    val fraction by animateFloatAsState(
        (habit.today.toFloat() / habit.goal).coerceIn(0f, 1f),
        spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
    )
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .padding(top = 16.dp)
            .surface()
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StreakFlame(habit)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                StreakCount(habit.streak)
                Hint(habitMessage(habit))
            }
            Box(contentAlignment = Alignment.Center) {
                Ring(fraction, size = 46.dp, color = flame())
                Text("${habit.today}", style = Look.type.label)
            }
        }
        WeekRow(habit)
    }
}

@Composable
private fun flame() = if (Look.colors.plain) Look.colors.ink else Flame

@Composable
private fun habitMessage(habit: Habit) = when {
    habit.done -> stringResource(R.string.goal_reached)

    habit.today > 0 -> {
        val left = habit.goal - habit.today
        pluralStringResource(R.plurals.pages_left, left, left)
    }

    habit.streak > 0 ->
        pluralStringResource(R.plurals.keep_streak, habit.goal, habit.goal)

    else -> pluralStringResource(R.plurals.start_streak, habit.goal, habit.goal)
}

@Composable
private fun rememberFlamePulse(): Float {
    val pulse by rememberInfiniteTransition()
        .animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        )
    return pulse
}

@Composable
private fun StreakFlame(habit: Habit) {
    val pulsing = habit.done && !LocalReducedMotion.current
    val pulse = if (pulsing) rememberFlamePulse() else 1f
    val lit = habit.done || habit.streak > 0
    val tint by animateColorAsState(if (lit) flame() else Look.colors.faint)
    Icon(
        Icons.Rounded.LocalFireDepartment,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(40.dp).scale(pulse),
    )
}

@Composable
private fun StreakCount(streak: Int) {
    AnimatedContent(
        streak,
        transitionSpec = {
            slideInVertically { it } togetherWith slideOutVertically { -it }
        },
    ) { value ->
        Text(
            pluralStringResource(R.plurals.day_streak, value, value),
            style = Look.type.heading,
        )
    }
}

@Composable
private fun WeekRow(habit: Habit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        habit.week.forEachIndexed { index, pages ->
            val day = habit.day.minusDays((6 - index).toLong())
            DayDot(
                label = day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                done = pages >= habit.goal,
                isToday = index == 6,
            )
        }
    }
}

@Composable
private fun DayDot(label: String, done: Boolean, isToday: Boolean) {
    val colors = Look.colors
    val scale by animateFloatAsState(
        if (done) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(label, color = if (isToday) colors.ink else colors.faint)
        Box(
            Modifier.size(28.dp)
                .background(colors.sunken, CircleShape)
                .border(1.5.dp, if (isToday) colors.ink else colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize().scale(scale).background(flame(), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), colors.paper) }
        }
    }
}
