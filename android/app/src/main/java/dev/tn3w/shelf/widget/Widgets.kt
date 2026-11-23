package dev.tn3w.shelf.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.tn3w.shelf.MainActivity
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Habit
import dev.tn3w.shelf.ui.readable
import dev.tn3w.shelf.ui.textOn
import java.time.format.TextStyle.NARROW

const val OPEN_BOOK = "dev.tn3w.shelf.OPEN_BOOK"
const val EXTRA_WORK = "work"
private val Flame = Color(0xFFF08A1C)
private val Pad = 14.dp
private val Thin = 4.dp
private val Play = 44.dp
private val White = ColorProvider(Color.White)
private val Faded = ColorProvider(Color.White.copy(alpha = 0.75f))
private val Track = ColorProvider(Color.White.copy(alpha = 0.3f))
private val Radius = android.R.dimen.system_app_widget_background_radius

val WIDGETS = listOf(ContinueWidget(), StreakWidget(), ShelfWidget())

abstract class ShelfAppWidget(private val books: Int, private val width: Int) :
    GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadWidgetState(context, books, width)
        provideContent { GlanceTheme { Content(state) } }
    }

    @Composable
    abstract fun Content(state: WidgetState)
}

class ContinueWidget : ShelfAppWidget(books = 1, width = 480) {
    @Composable
    override fun Content(state: WidgetState) {
        state.books.firstOrNull()?.let { Poster(it) } ?: Empty()
    }
}

class StreakWidget : ShelfAppWidget(books = 0, width = 0) {
    @Composable
    override fun Content(state: WidgetState) {
        val habit = state.habit ?: return Empty(R.string.widget_no_goal)
        val size = LocalSize.current
        val inner = size.height - Pad * 2
        val background = GlanceTheme.colors.widgetBackground
        Box(frame(openApp()).background(background).padding(Pad)) {
            if (size.height < 130.dp) {
                Row(
                    GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreakCount(habit)
                    Spacer(GlanceModifier.defaultWeight())
                    WeekBars(habit, size.width * 0.5f, inner)
                }
                return@Box
            }
            val message = size.width >= 200.dp
            Column(GlanceModifier.fillMaxSize()) {
                StreakCount(habit)
                if (message) {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        habitMessage(habit),
                        style = TextStyle(GlanceTheme.colors.onSurfaceVariant, 12.sp),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(12.dp))
                val header = if (message) 96.dp else 72.dp
                val bottom = Alignment.BottomStart
                Box(GlanceModifier.defaultWeight(), contentAlignment = bottom) {
                    WeekBars(habit, size.width - Pad * 2, inner - header)
                }
            }
        }
    }
}

class ShelfWidget : ShelfAppWidget(books = 8, width = 200) {
    @Composable
    override fun Content(state: WidgetState) {
        if (state.books.isEmpty()) return Empty()
        val colors = GlanceTheme.colors
        val size = LocalSize.current
        val bookHeight = size.height - Pad * 2 - 22.dp - 6.dp - Thin - 6.dp
        val bookWidth = bookHeight * ratio(state.books.first())
        val fits = ((size.width - Pad * 2) / bookWidth).toInt().coerceAtLeast(1)
        Column(frame(openApp()).background(colors.widgetBackground).padding(Pad)) {
            Row(
                GlanceModifier.fillMaxWidth().height(22.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(LocalContext.current.getString(R.string.reading), colors.onSurface)
                Spacer(GlanceModifier.defaultWeight())
                state.habit?.let { Streak(it, colors.onSurface) }
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                state.books.take(fits).forEach { book ->
                    Column(
                        GlanceModifier.clickable(openBook(book.work)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BookImage(book, bookHeight)
                        Spacer(GlanceModifier.height(6.dp))
                        val barWidth = bookWidth * 0.7f
                        Progress(book.progress, barWidth, colors.primary, colors.outline)
                    }
                }
            }
        }
    }
}

class ContinueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WIDGETS[0]
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WIDGETS[1]
}

class ShelfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WIDGETS[2]
}

@Composable
private fun frame(action: Action) = GlanceModifier.fillMaxSize()
    .appWidgetBackground()
    .cornerRadius(Radius)
    .clickable(action)

@Composable
private fun openBook(work: Int): Action {
    val intent = Intent(LocalContext.current, MainActivity::class.java)
        .setAction(OPEN_BOOK)
        .setData("shelf://book/$work".toUri())
        .putExtra(EXTRA_WORK, work)
    return actionStartActivity(intent)
}

@Composable
private fun openApp() =
    actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))

@Composable
private fun Empty(message: Int = R.string.widget_empty) {
    val colors = GlanceTheme.colors
    Box(
        frame(openApp()).background(colors.widgetBackground).padding(Pad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            LocalContext.current.getString(message),
            style = TextStyle(colors.onSurfaceVariant, 13.sp),
            maxLines = 3,
        )
    }
}

private fun ratio(book: WidgetBook) = book.book.width / book.book.height.toFloat()

@Composable
private fun BookImage(book: WidgetBook, height: Dp) = Image(
    ImageProvider(book.book),
    book.title,
    GlanceModifier.size(height * ratio(book), height),
    contentScale = ContentScale.Fit,
)

@Composable
private fun Progress(
    fraction: Float,
    width: Dp,
    color: ColorProvider,
    track: ColorProvider,
) {
    val corner = GlanceModifier.cornerRadius(Thin / 2)
    Row(corner.width(width).height(Thin).background(track)) {
        val filled = width * fraction.coerceIn(0f, 1f)
        Box(corner.width(filled).fillMaxHeight().background(color)) {}
    }
}

@Composable
private fun Label(text: String, color: ColorProvider, size: Int = 13) = Text(
    text,
    style = TextStyle(color, size.sp, FontWeight.Medium),
    maxLines = 1,
)

@Composable
private fun Title(text: String, color: ColorProvider, size: Int = 18, lines: Int = 2) =
    Text(
        text,
        style = TextStyle(color, size.sp, FontWeight.Bold, fontFamily = FontFamily.Serif),
        maxLines = lines,
    )

@Composable
private fun PlayIcon(color: ColorProvider, size: Dp) = Image(
    ImageProvider(R.drawable.widget_play),
    null,
    GlanceModifier.size(size),
    colorFilter = ColorFilter.tint(color),
)

@Composable
private fun PlayButton(accent: Int) = Box(
    GlanceModifier.size(Play).cornerRadius(Play / 2).background(Color(accent)),
    contentAlignment = Alignment.Center,
) { PlayIcon(ColorProvider(Color(textOn(accent))), 26.dp) }

/** The cover filling the widget, fading into its own colour beside the title. */
@Composable
private fun Poster(book: WidgetBook) {
    val size = LocalSize.current
    val tint = book.dark
    val accent = readable(tint.accent, tint.glow)
    val wide = size.width > size.height
    val bitmap = poster(
        book.cover,
        (size.width.value * 2).toInt(),
        (size.height.value * 2).toInt(),
        tint,
    )
    val textWidth = if (wide) {
        size.width - Pad * 2 - size.height * 2 / 3 * 0.9f
    } else {
        size.width - Pad * 2
    }
    val compact = size.height < 130.dp
    Box(frame(openBook(book.work))) {
        Image(
            ImageProvider(bitmap),
            book.title,
            GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Box(
            GlanceModifier.fillMaxSize().padding(Pad),
            contentAlignment = if (compact) Alignment.CenterEnd else Alignment.BottomEnd,
        ) {
            if (compact) {
                CompactDetails(book, textWidth, accent)
            } else {
                PosterDetails(book, textWidth, accent, wide)
            }
        }
    }
}

@Composable
private fun PosterDetails(book: WidgetBook, width: Dp, accent: Int, wide: Boolean) {
    Column(GlanceModifier.width(width)) {
        Title(book.title, White, size = if (wide) 20 else 16)
        Label(book.author, Faded)
        Spacer(GlanceModifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Progress(book.progress, width - Play - 10.dp, White, Track)
            Spacer(GlanceModifier.width(10.dp))
            PlayButton(accent)
        }
    }
}

@Composable
private fun CompactDetails(book: WidgetBook, width: Dp, accent: Int) {
    Row(GlanceModifier.width(width), verticalAlignment = Alignment.CenterVertically) {
        val textWidth = width - Play - 10.dp
        Column(GlanceModifier.width(textWidth)) {
            Title(book.title, White, size = 16, lines = 1)
            Label(book.author, Faded, size = 12)
            Spacer(GlanceModifier.height(8.dp))
            Progress(book.progress, textWidth, White, Track)
        }
        Spacer(GlanceModifier.width(10.dp))
        PlayButton(accent)
    }
}

@Composable
private fun Flame(habit: Habit, size: Dp) = Image(
    ImageProvider(R.drawable.widget_flame),
    null,
    GlanceModifier.size(size),
    colorFilter = ColorFilter.tint(
        if (habit.done ||
            habit.streak > 0
        ) {
            ColorProvider(Flame)
        } else {
            GlanceTheme.colors.outline
        },
    ),
)

@Composable
private fun habitMessage(habit: Habit): String {
    val resources = LocalContext.current.resources
    val left = habit.goal - habit.today
    return when {
        habit.done -> resources.getString(R.string.goal_reached)
        habit.today > 0 -> resources.getQuantityString(R.plurals.pages_left, left, left)
        else -> resources.getQuantityString(R.plurals.keep_streak, habit.goal, habit.goal)
    }
}

@Composable
private fun Streak(habit: Habit, color: ColorProvider) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Flame(habit, 16.dp)
        Spacer(GlanceModifier.width(4.dp))
        Label("${habit.streak}", color)
    }
}

@Composable
private fun StreakCount(habit: Habit) {
    val colors = GlanceTheme.colors
    val label = LocalContext.current.resources
        .getQuantityString(R.plurals.day_streak, habit.streak, habit.streak)
        .replace("${habit.streak}", "")
        .trim()
    val number = TextStyle(
        colors.onSurface,
        34.sp,
        FontWeight.Bold,
        fontFamily = FontFamily.Serif,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Flame(habit, 34.dp)
        Spacer(GlanceModifier.width(6.dp))
        Column {
            Text("${habit.streak}", style = number, maxLines = 1)
            Label(label, colors.onSurfaceVariant, size = 12)
        }
    }
}

@Composable
private fun WeekBars(habit: Habit, width: Dp, height: Dp) {
    val colors = GlanceTheme.colors
    val locale = LocalContext.current.resources.configuration.locales[0]
    val barWidth = minOf(10.dp, width / 7 - 8.dp)
    val labels = 20.dp
    val tallest = height - labels
    val top = maxOf(habit.goal, habit.week.max()).toFloat()
    Row(GlanceModifier.width(width).height(height)) {
        habit.week.forEachIndexed { index, pages ->
            val color = when {
                pages >= habit.goal -> ColorProvider(Flame)
                pages > 0 -> colors.primary
                else -> colors.secondaryContainer
            }
            val day = habit.day.minusDays(6L - index).dayOfWeek
            Column(
                GlanceModifier.defaultWeight().fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    GlanceModifier.width(barWidth)
                        .height(maxOf(barWidth, tallest * (pages / top)))
                        .cornerRadius(barWidth / 2)
                        .background(color),
                ) {}
                Spacer(GlanceModifier.height(4.dp))
                Label(
                    day.getDisplayName(NARROW, locale),
                    if (index == 6) colors.primary else colors.onSurfaceVariant,
                    size = 11,
                )
            }
        }
    }
}
