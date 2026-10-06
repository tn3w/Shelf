package dev.tn3w.shelf.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle

private val HOME_TAGS =
    listOf("fantasy", "mystery", "classics", "science-fiction", "romance")
private val Flame = Color(0xFFFF9600)

@Composable
fun HomeScreen(navigator: Navigator) {
    val app = shelfApp()
    val saved by app.library.saved.collectAsStateWithLifecycle(null)
    val progress by app.library.progress.collectAsStateWithLifecycle(emptyMap())
    val habit by app.library.habit.collectAsStateWithLifecycle(null)
    val dismissed by app.library.dismissed.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val rows by load(saved, dismissed) {
        saved?.let { recommender.rows(it, app.session, dismissed) }
    }
    val missing by load { catalogue.workCount == 0 }
    fun dismiss(book: Book) = scope.launch { app.library.dismiss(book.work) }
    val genres by load {
        HOME_TAGS.mapNotNull { catalogue.tagBySlug[it] }
            .map { it to recommender.popular(12, it.id) }
    }
    val reading = saved.orEmpty().filter { it.shelf == Shelf.Reading }
    val want = saved.orEmpty().filter { it.shelf == Shelf.Want }.map { it.toBook() }

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item {
            LargeTitle(stringResource(R.string.home)) {
                val label = stringResource(R.string.settings)
                IconAction(Icons.Outlined.Settings, label, navigator::settings)
            }
        }
        habit?.let { item { HabitCard(it) } }
        if (reading.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.continue_reading)) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(reading, key = { it.work }) { entry ->
                        val book = entry.toBook()
                        val position = progress[entry.work]
                        ContinueCard(book, position?.fraction) {
                            if (position != null) {
                                navigator.reader(entry.work)
                            } else {
                                navigator.book(book, "continue")
                            }
                        }
                    }
                }
            }
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
        if (rows == null) {
            item {
                BookSection(
                    stringResource(R.string.for_you),
                    null,
                    "for-you",
                    navigator::book,
                    subtitle = stringResource(R.string.for_you_subtitle),
                )
            }
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
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun rowTitle(row: Row) = when (row.kind) {
    RowKind.Series -> stringResource(R.string.next_in_series)

    RowKind.Author -> stringResource(R.string.more_by, row.author?.name.orEmpty())

    RowKind.Popular -> stringResource(R.string.popular)

    RowKind.Because ->
        row.sources.firstOrNull()?.let {
            stringResource(R.string.because_you_read, mainTitle(it.title))
        } ?: stringResource(R.string.for_you)
}

@Composable
private fun rowSubtitle(row: Row) = when (row.kind) {
    RowKind.Series -> stringResource(R.string.next_in_series_subtitle)

    RowKind.Popular -> stringResource(R.string.popular_subtitle)

    RowKind.Because ->
        if (row.sources.isEmpty()) stringResource(R.string.for_you_subtitle) else null

    else -> null
}

@Composable
private fun HabitCard(habit: Habit) {
    val fraction by animateFloatAsState(
        (habit.today.toFloat() / habit.goal).coerceIn(0f, 1f),
        spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier =
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        Column(
            Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StreakFlame(habit)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    StreakCount(habit.streak)
                    Text(
                        habitMessage(habit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GoalBar(
                fraction, stringResource(R.string.pages_of_goal, habit.today, habit.goal),
            )
            WeekRow(habit)
        }
    }
}

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
private fun StreakFlame(habit: Habit) {
    val pulsing = habit.done && !LocalReducedMotion.current
    val pulse by rememberInfiniteTransition()
        .animateFloat(
            initialValue = 1f,
            targetValue = if (pulsing) 1.15f else 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        )
    val lit = habit.done || habit.streak > 0
    val tint by
        animateColorAsState(if (lit) Flame else MaterialTheme.colorScheme.outlineVariant)
    Icon(
        Icons.Rounded.LocalFireDepartment,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(52.dp).scale(pulse),
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
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun GoalBar(fraction: Float, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(Flame),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekRow(habit: Habit) {
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        habit.week.forEachIndexed { index, pages ->
            val day = today.minusDays((6 - index).toLong())
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
    val ring = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent
    val scale by animateFloatAsState(
        if (done) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) ring else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.padding(top = 6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(ring)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(30.dp).scale(scale).clip(CircleShape).background(Flame),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
