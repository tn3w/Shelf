package dev.tn3w.shelf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Habit
import dev.tn3w.shelf.data.Shelf
import dev.tn3w.shelf.data.toBook
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
    val recommendations by load(saved) { saved?.let { recommender.recommend(it) } }
    val genres by load {
        HOME_TAGS.mapNotNull { catalogue.tagBySlug[it] }
            .map { it to recommender.popular(12, it.id) }
    }
    val reading = saved.orEmpty().filter { it.shelf == Shelf.Reading }
    val want = saved.orEmpty().filter { it.shelf == Shelf.Want }.map { it.toBook() }

    LazyColumn(contentPadding = WindowInsets.statusBars.asPaddingValues()) {
        item {
            LargeTitle(stringResource(R.string.home)) {
                IconButton(onClick = navigator::settings) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                }
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
                            if (position != null) navigator.reader(entry.work)
                            else navigator.book(book, "continue")
                        }
                    }
                }
            }
        }
        if (want.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.want_to_read),
                    stringResource(R.string.want_to_read_subtitle),
                    navigator::library,
                )
            }
            item { BookRow(want, "want", navigator::book) }
        }
        item {
            val personal = !saved.isNullOrEmpty()
            SectionHeader(
                stringResource(if (personal) R.string.for_you else R.string.popular),
                stringResource(
                    if (personal) R.string.for_you_subtitle else R.string.popular_subtitle
                ),
            )
        }
        item { BookRow(recommendations, "for-you", navigator::book) }
        genres.orEmpty().forEach { (tag, books) ->
            item(key = tag.slug) {
                SectionHeader(tag.label, onMore = { navigator.tag(tag.id) })
                BookRow(books, tag.slug, navigator::book)
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HabitCard(habit: Habit) {
    val fraction by
        animateFloatAsState(
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
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                fraction,
                stringResource(R.string.pages_of_goal, habit.today, habit.goal),
            )
            WeekRow(habit)
        }
    }
}

@Composable
private fun habitMessage(habit: Habit) =
    when {
        habit.done -> stringResource(R.string.goal_reached)
        habit.today > 0 ->
            pluralStringResource(
                R.plurals.pages_left,
                habit.goal - habit.today,
                habit.goal - habit.today,
            )
        habit.streak > 0 ->
            pluralStringResource(R.plurals.keep_streak, habit.goal, habit.goal)
        else -> pluralStringResource(R.plurals.start_streak, habit.goal, habit.goal)
    }

@Composable
private fun StreakFlame(habit: Habit) {
    val pulsing = habit.done && !LocalReducedMotion.current
    val pulse by
        rememberInfiniteTransition()
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(Flame)
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
    val scale by
        animateFloatAsState(
            if (done) 1f else 0f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy),
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
