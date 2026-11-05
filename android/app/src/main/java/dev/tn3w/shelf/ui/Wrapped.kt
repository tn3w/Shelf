package dev.tn3w.shelf.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import java.time.format.TextStyle as DateStyle

private const val SLIDE_MILLIS = 6_000
private const val REVEAL_MILLIS = 600
private const val COUNT_MILLIS = 1_400
private const val FAN_SIZE = 5
private const val WALL_ROWS = 4
private const val WALL_COVERS = 8
private val White = Color.White
private val Gold = Color(0xFFFFD166)
private val CardScrim = Brush.verticalGradient(
    0f to Color(Slide.Intro.top).copy(alpha = 0.35f),
    0.35f to Color.Transparent,
    0.7f to Color(Slide.Intro.top).copy(alpha = 0.85f),
    1f to Color(Slide.Intro.top),
)

private enum class Slide(val top: Long, val bottom: Long) {
    Intro(0xFF2D1B69, 0xFFB5179E),
    Books(0xFF053B2F, 0xFF0FA36B),
    Pages(0xFF6B1D0C, 0xFFEA6A12),
    Months(0xFF0E2A6B, 0xFF2F7BEA),
    Genre(0xFF6B0F33, 0xFFE0335A),
    Author(0xFF0B3F3B, 0xFF139C8E),
    Days(0xFF0B1026, 0xFF4338CA),
    Bookends(0xFF3F0545, 0xFFB42BC4),
    Type(0xFF1A1206, 0xFFE07A00),
    Summary(0xFF14122B, 0xFF5B21B6),
    ;

    val brush
        get() = Brush.verticalGradient(listOf(Color(top), Color(bottom)))
}

private fun slidesOf(recap: Recap) = Slide.entries.filter {
    when (it) {
        Slide.Books -> recap.books.isNotEmpty()
        Slide.Pages, Slide.Months, Slide.Days -> recap.pages > 0
        Slide.Genre -> recap.genres.isNotEmpty()
        Slide.Author -> recap.topAuthor != null
        Slide.Bookends -> recap.books.size > 1
        else -> true
    }
}

class WrappedViewModel(app: ShelfApp, year: Int) : ShelfViewModel(app) {
    val recap = combine(library.saved, library.activity, loaded, ::Triple)
        .map { (saved, activity, current) -> recapState(saved, activity, current, year) }
        .asState()
    val author = combine(recap.filterNotNull(), loaded) { recap, current ->
        val name = recap.topAuthor ?: return@combine null
        val work = recap.authorBooks.first().work
        current?.book(work)?.authors?.firstOrNull { it.name == name } ?: Author(0, name)
    }.asState()
}

@Composable
fun WrappedScreen(year: Int, navigator: Navigator) {
    val viewModel = shelfViewModel("wrapped-$year") { WrappedViewModel(it, year) }
    val recap by viewModel.recap.collectAsStateWithLifecycle()
    val author by viewModel.author.collectAsStateWithLifecycle()
    LightBars()
    val current = recap
    if (current == null) {
        Box(Modifier.fillMaxSize().background(Slide.Intro.brush))
        return
    }
    Story(current, author, navigator::back)
}

@Composable
private fun LightBars() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val light = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = light }
    }
}

@Composable
private fun Story(recap: Recap, author: Author?, onClose: () -> Unit) {
    val slides = remember(recap) { slidesOf(recap) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val progress = remember(index) { Animatable(0f) }
    val last = index >= slides.lastIndex

    fun step(by: Int) {
        index = (index + by).coerceIn(0, slides.lastIndex)
    }

    LaunchedEffect(index, paused, reduced) {
        if (last) progress.snapTo(1f)
        if (paused || reduced || last) return@LaunchedEffect
        val left = ((1f - progress.value) * SLIDE_MILLIS).toInt()
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        step(1)
    }
    BackHandler(index > 0) { step(-1) }
    val slide = slides[index.coerceAtMost(slides.lastIndex)]
    Box(
        Modifier.fillMaxSize()
            .background(Color(Slide.Summary.top))
            .pointerInput(slides) {
                detectTapGestures(
                    onPress = {
                        paused = true
                        tryAwaitRelease()
                        paused = false
                    },
                    onTap = { step(if (it.x < size.width / 3) -1 else 1) },
                )
            },
    ) {
        AnimatedContent(
            slide,
            transitionSpec = { slideTransition(reduced) },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(it.brush)) {
                SlideContent(it, recap, author, onClose)
            }
        }
        StoryBar(slides.size, index, progress.value, onClose)
    }
}

private fun slideTransition(reduced: Boolean) = if (reduced) {
    EnterTransition.None togetherWith ExitTransition.None
} else {
    (fadeIn(tween(400)) + scaleIn(tween(400), 1.06f)) togetherWith fadeOut(tween(250))
}

@Composable
private fun StoryBar(count: Int, index: Int, progress: Float, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(count) {
                val filled = when {
                    it < index -> 1f
                    it == index -> progress
                    else -> 0f
                }
                StorySegment(filled, Modifier.weight(1f))
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = White)
        }
    }
}

@Composable
private fun StorySegment(filled: Float, modifier: Modifier) {
    Box(modifier.height(3.dp).clip(CircleShape).background(White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(White))
    }
}

@Composable
private fun SlideContent(
    slide: Slide,
    recap: Recap,
    author: Author?,
    onClose: () -> Unit,
) {
    val books = remember(recap) { recap.books.map { it.toBook() } }
    when (slide) {
        Slide.Intro -> IntroSlide(recap, books)
        Slide.Books -> BooksSlide(books)
        Slide.Pages -> PagesSlide(recap)
        Slide.Months -> MonthsSlide(recap)
        Slide.Genre -> GenreSlide(recap)
        Slide.Author -> AuthorSlide(recap, author)
        Slide.Days -> DaysSlide(recap)
        Slide.Bookends -> BookendsSlide(recap)
        Slide.Type -> TypeSlide(recap.readerType)
        Slide.Summary -> SummarySlide(recap, author, onClose)
    }
}

@Composable
private fun SlideColumn(
    arrangement: Arrangement.Vertical = Arrangement.Center,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)
        .padding(horizontal = 28.dp)
        .padding(top = 56.dp, bottom = 32.dp),
    verticalArrangement = arrangement,
    content = content,
)

@Composable
private fun rememberGrowth(order: Int): Animatable<Float, AnimationVector1D> {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(order * 180L)
        shown.animateTo(1f, tween(REVEAL_MILLIS, easing = FastOutSlowInEasing))
    }
    return shown
}

@Composable
private fun Modifier.reveal(order: Int): Modifier {
    val shown = rememberGrowth(order)
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 48.dp.toPx()
    }
}

@Composable
private fun Eyebrow(text: String, order: Int = 0) = Text(
    text,
    style = MaterialTheme.typography.headlineSmall,
    color = White.copy(alpha = 0.85f),
    modifier = Modifier.reveal(order),
)

@Composable
private fun Headline(text: String, order: Int, style: TextStyle = HeroStyle) = Text(
    text,
    style = style,
    fontWeight = FontWeight.Black,
    color = White,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.reveal(order),
)

private val HeroStyle = TextStyle(fontSize = 84.sp, lineHeight = 90.sp)
private val YearStyle = TextStyle(fontSize = 120.sp, lineHeight = 120.sp)
private val CardYearStyle = TextStyle(fontSize = 88.sp, lineHeight = 92.sp)
private val TitleStyle = TextStyle(fontSize = 48.sp, lineHeight = 54.sp)

@Composable
private fun Caption(text: String, order: Int, color: Color = White) = Text(
    text,
    style = MaterialTheme.typography.titleLarge,
    color = color,
    modifier = Modifier.reveal(order),
)

@Composable
internal fun formatted(value: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return String.format(locale, "%,d", value)
}

@Composable
private fun CountUp(target: Int, order: Int) {
    val reduced = LocalReducedMotion.current
    val value = remember { Animatable(if (reduced) target.toFloat() else 0f) }
    LaunchedEffect(target) {
        delay(order * 180L)
        val spec = tween<Float>(COUNT_MILLIS, easing = FastOutSlowInEasing)
        value.animateTo(target.toFloat(), spec)
    }
    Headline(formatted(value.value.toInt()), order)
}

@Composable
internal fun monthName(
    month: YearMonth,
    style: DateStyle = DateStyle.FULL_STANDALONE,
): String {
    val locale = LocalConfiguration.current.locales[0]
    return month.month.getDisplayName(style, locale)
        .replaceFirstChar { it.titlecase(locale) }
}

@Composable
internal fun dateLabel(day: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return day.format(format.withLocale(locale))
}

@Composable
internal fun days(count: Int) = pluralStringResource(R.plurals.days_count, count, count)

@Composable
private fun pages(count: Int) = pluralStringResource(R.plurals.pages_count, count, count)

@Composable
private fun booksCount(count: Int) = pluralStringResource(R.plurals.books, count, count)

@Composable
private fun IntroSlide(recap: Recap, books: List<Book>) {
    Box(Modifier.fillMaxSize()) {
        if (books.isNotEmpty()) CoverWall(books)
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(Slide.Intro.top),
                            Color.Transparent,
                            Color.Transparent,
                            Color(Slide.Intro.top),
                        ),
                    ),
                ),
        )
        SlideColumn(Arrangement.Bottom) {
            Eyebrow(stringResource(R.string.year_in_books))
            Headline("${recap.year}", 1, YearStyle)
            Caption(stringResource(R.string.wrapped_intro), 2, White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun CoverWall(books: List<Book>, cover: Dp = 96.dp, moving: Boolean = true) {
    val reduced = LocalReducedMotion.current
    val drift = if (reduced || !moving) {
        0f
    } else {
        rememberInfiniteTransition().animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse),
        ).value
    }
    val cycle = List(WALL_ROWS * WALL_COVERS) { books[it % books.size] }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                rotationZ = -12f
                scaleX = 1.4f
                scaleY = 1.4f
                alpha = 0.55f
            },
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        cycle.chunked(WALL_COVERS).forEachIndexed { row, covers ->
            val direction = if (row % 2 == 0) 1 else -1
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = direction * drift * 240.dp.toPx() },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                covers.forEachIndexed { column, book ->
                    BookCover(book, cover, Modifier.stagger(row, column))
                }
            }
        }
    }
}

private fun Modifier.stagger(row: Int, column: Int) =
    offset(x = if (row % 2 == 0) 0.dp else (-48).dp, y = (column % 2 * 6).dp)

@Composable
private fun CoverFan(books: List<Book>, width: Dp = 120.dp, order: Int = 2) {
    val shown = books.take(FAN_SIZE)
    val middle = (shown.size - 1) / 2f
    Box(
        Modifier.fillMaxWidth().height(width * 1.75f),
        contentAlignment = Alignment.Center,
    ) {
        shown.forEachIndexed { index, book ->
            val offset = index - middle
            BookCover(
                book,
                width,
                Modifier.reveal(order + index)
                    .graphicsLayer {
                        translationX = offset * width.toPx() * 0.55f
                        translationY = abs(offset) * 10.dp.toPx()
                        rotationZ = offset * 8f
                    }
                    .shadow(16.dp, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
private fun BooksSlide(books: List<Book>) {
    SlideColumn {
        Eyebrow(stringResource(R.string.wrapped_books))
        CountUp(books.size, 1)
        Caption(stringResource(R.string.books_metric), 1)
        Spacer(Modifier.height(32.dp))
        CoverFan(books.reversed())
    }
}

@Composable
private fun PagesSlide(recap: Recap) {
    val best = recap.bestDay
    SlideColumn {
        Eyebrow(stringResource(R.string.wrapped_pages))
        CountUp(recap.pages, 1)
        Caption(stringResource(R.string.pages_metric), 1)
        Spacer(Modifier.height(32.dp))
        val hours =
            pluralStringResource(R.plurals.wrapped_hours, recap.hours, recap.hours)
        Caption(hours, 3)
        if (best == null) return@SlideColumn
        Spacer(Modifier.height(16.dp))
        val day = dateLabel(best.first)
        val text = stringResource(R.string.wrapped_best_day, day, pages(best.second))
        Caption(text, 4, Gold)
    }
}

@Composable
private fun MonthsSlide(recap: Recap) {
    val best = recap.bestMonth ?: return
    SlideColumn {
        Eyebrow(stringResource(R.string.wrapped_month))
        Headline(monthName(best.month), 1, TitleStyle)
        Caption(pages(best.pages), 2, Gold)
        Spacer(Modifier.height(40.dp))
        GrowingBars(recap.months, best.month)
    }
}

@Composable
private fun GrowingBars(months: List<MonthStats>, best: YearMonth) {
    val highest = months.maxOf { it.pages }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(200.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        months.forEachIndexed { index, month ->
            val growth = rememberGrowth(3 + index / 2).value
            val color = if (month.month == best) Gold else White.copy(alpha = 0.35f)
            val fraction = (growth * month.pages / highest).coerceAtLeast(0.02f)
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color),
                    )
                }
                Text(
                    monthName(month.month, DateStyle.NARROW_STANDALONE),
                    style = MaterialTheme.typography.labelMedium,
                    color = White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GenreSlide(recap: Recap) {
    val top = recap.genres.first()
    SlideColumn {
        Eyebrow(stringResource(R.string.wrapped_genre))
        Headline(top.tag.label, 1, TitleStyle)
        Caption(booksCount(top.books.size), 2, Gold)
        Spacer(Modifier.height(24.dp))
        CoverFan(top.books.map { it.toBook() }, 104.dp, 3)
        Spacer(Modifier.height(24.dp))
        recap.genres.drop(1).take(4).forEachIndexed { index, genre ->
            RankRow(index + 2, genre.tag.label, booksCount(genre.books.size), 6 + index)
        }
    }
}

@Composable
private fun RankRow(rank: Int, label: String, detail: String, order: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).reveal(order),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = Gold,
            modifier = Modifier.width(36.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun AuthorSlide(recap: Recap, author: Author?) {
    val name = recap.topAuthor ?: return
    SlideColumn(Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Eyebrow(stringResource(R.string.wrapped_author))
        AuthorAvatar(
            author ?: Author(0, name),
            128.dp,
            Modifier.reveal(1).border(4.dp, Gold, CircleShape),
        )
        Headline(name, 2, TitleStyle)
        Caption(booksCount(recap.authorBooks.size), 3, Gold)
        CoverFan(recap.authorBooks.map { it.toBook() }, 96.dp, 4)
    }
}

@Composable
private fun DaysSlide(recap: Recap) {
    SlideColumn {
        Eyebrow(stringResource(R.string.wrapped_days))
        CountUp(recap.readingDays, 1)
        Caption(stringResource(R.string.reading_days), 1)
        Spacer(Modifier.height(28.dp))
        YearHeatmap(recap)
        Spacer(Modifier.height(24.dp))
        val label = stringResource(R.string.longest_streak)
        val streak = "$label: ${days(recap.longestStreak)}"
        Row(Modifier.reveal(4), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = Gold)
            Text(
                streak,
                style = MaterialTheme.typography.titleLarge,
                color = White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun YearHeatmap(recap: Recap) {
    val reduced = LocalReducedMotion.current
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(1_600, 300, LinearEasing)) }
    val highest = recap.days.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val labels = (1..12).map {
        monthName(YearMonth.of(recap.year, it), DateStyle.NARROW_STANDALONE)
    }
    Row {
        Column(Modifier.padding(end = 8.dp)) {
            labels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = White.copy(alpha = 0.7f),
                    modifier = Modifier.height(16.dp),
                )
            }
        }
        Canvas(Modifier.weight(1f).height(16.dp * 12)) {
            drawHeatmap(recap, highest, sweep.value)
        }
    }
}

private fun DrawScope.drawHeatmap(recap: Recap, highest: Int, sweep: Float) {
    val cell = size.width / 31
    val row = size.height / 12
    val dot = minOf(cell, row) * 0.78f
    (1..12).forEach { month ->
        val length = YearMonth.of(recap.year, month).lengthOfMonth()
        (1..length).forEach { dayOfMonth ->
            val visible = (month - 1) / 12f <= sweep
            val pages = recap.days[LocalDate.of(recap.year, month, dayOfMonth)] ?: 0
            val color = when {
                !visible -> Color.Transparent
                pages == 0 -> White.copy(alpha = 0.12f)
                else -> Gold.copy(alpha = 0.35f + 0.65f * pages / highest)
            }
            drawRoundRect(
                color,
                Offset((dayOfMonth - 1) * cell, (month - 1) * row + (row - dot) / 2),
                Size(dot, dot),
                CornerRadius(dot / 3),
            )
        }
    }
}

@Composable
private fun BookendsSlide(recap: Recap) {
    val first = recap.books.first()
    val last = recap.books.last()
    SlideColumn {
        Eyebrow(stringResource(R.string.wrapped_bookends))
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bookend(first, R.string.wrapped_first, Modifier.weight(1f).reveal(1))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                null,
                tint = Gold,
                modifier = Modifier.padding(horizontal = 8.dp).size(32.dp).reveal(2),
            )
            Bookend(last, R.string.wrapped_last, Modifier.weight(1f).reveal(3))
        }
    }
}

@Composable
private fun Bookend(saved: Saved, label: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = Gold,
        )
        BookCover(
            saved.toBook(),
            modifier = Modifier.fillMaxWidth()
                .padding(vertical = 12.dp)
                .shadow(16.dp, RoundedCornerShape(6.dp)),
        )
        Text(
            saved.title,
            style = MaterialTheme.typography.titleMedium,
            color = White,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        saved.finishedOn()?.let {
            Text(
                dateLabel(it),
                style = MaterialTheme.typography.bodySmall,
                color = White.copy(alpha = 0.7f),
            )
        }
    }
}

private val ReaderType.icon: ImageVector
    get() = when (this) {
        ReaderType.Marathoner -> Icons.Rounded.LocalFireDepartment
        ReaderType.Explorer -> Icons.Rounded.Explore
        ReaderType.Devotee -> Icons.Rounded.Favorite
        ReaderType.Weekender -> Icons.Rounded.Weekend
        ReaderType.Bookworm -> Icons.Rounded.AutoStories
    }

@Composable
private fun TypeSlide(type: ReaderType) {
    SlideColumn(Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Eyebrow(stringResource(R.string.wrapped_type))
        Box(
            Modifier.reveal(1).size(140.dp).clip(CircleShape).background(Gold),
            contentAlignment = Alignment.Center,
        ) {
            Icon(type.icon, null, Modifier.size(80.dp), tint = Color(Slide.Type.top))
        }
        Headline(stringResource(type.title), 2, TitleStyle)
        Caption(stringResource(type.description), 3, White.copy(alpha = 0.85f))
    }
}

@Composable
private fun SummarySlide(recap: Recap, author: Author?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val text = shareText(recap)
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
            .padding(top = 52.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SummaryCard(
            recap,
            author,
            Modifier.weight(1f, fill = false)
                .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                .reveal(0)
                .shadow(24.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        )
        Row(
            Modifier.padding(top = 16.dp).reveal(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onClose) {
                Text(stringResource(R.string.close), color = White)
            }
            Button(
                onClick = {
                    scope.launch {
                        context.shareImage(layer.toImageBitmap(), recap, text)
                    }
                },
                colors = ButtonDefaults.buttonColors(White, Color(Slide.Summary.top)),
            ) { IconLabel(Icons.Outlined.Share, R.string.share) }
        }
    }
}

@Composable
private fun SummaryCard(recap: Recap, author: Author?, modifier: Modifier) {
    val books = remember(recap) { recap.books.reversed().map { it.toBook() } }
    val stats = "${booksCount(recap.books.size)} · ${pages(recap.pages)}"
    val favorites = listOfNotNull(
        recap.genres.firstOrNull()?.tag?.label,
        author?.name ?: recap.topAuthor,
    )
    Box(modifier.background(Slide.Intro.brush).clipToBounds()) {
        if (books.isNotEmpty()) CoverWall(books, 72.dp, moving = false)
        Box(Modifier.fillMaxSize().background(CardScrim))
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Bottom) {
            Text(
                stringResource(R.string.year_in_books),
                style = MaterialTheme.typography.titleMedium,
                color = White.copy(alpha = 0.85f),
            )
            Text(
                "${recap.year}",
                style = CardYearStyle,
                fontWeight = FontWeight.Black,
                color = White,
            )
            Text(stats, style = MaterialTheme.typography.titleMedium, color = Gold)
            if (favorites.isNotEmpty()) {
                Text(
                    favorites.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            CardFooter()
        }
    }
}

@Composable
private fun CardFooter() {
    Row(
        Modifier.padding(top = 24.dp).offset(x = (-10).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_monochrome),
            null,
            Modifier.size(36.dp).padding(end = 4.dp),
            tint = White,
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = White,
            modifier = Modifier.weight(1f),
        )
        Text(
            "github.com/$REPOSITORY",
            style = MaterialTheme.typography.labelMedium,
            color = White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun shareText(recap: Recap) = stringResource(
    R.string.share_recap,
    recap.year,
    booksCount(recap.books.size),
    pages(recap.pages),
) + "\nhttps://github.com/$REPOSITORY"

private suspend fun Context.shareImage(image: ImageBitmap, recap: Recap, text: String) {
    val file = withContext(Dispatchers.IO) {
        val folder = cacheDir.resolve("shared").apply { mkdirs() }
        folder.resolve("shelf-${recap.year}.png").apply {
            val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

@Composable
fun WrappedBanner(recap: Recap, onPlay: () -> Unit) {
    val books = remember(recap) { recap.books.reversed().take(3).map { it.toBook() } }
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        Row(
            Modifier.background(Slide.Intro.brush).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.wrapped_banner, recap.year),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = White,
                )
                Text(
                    stringResource(R.string.wrapped_banner_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = White.copy(alpha = 0.85f),
                )
                Icon(
                    Icons.Rounded.PlayCircle,
                    null,
                    tint = Gold,
                    modifier = Modifier.padding(top = 8.dp).size(40.dp),
                )
            }
            if (books.isNotEmpty()) BannerCovers(books)
        }
    }
}

@Composable
private fun BannerCovers(books: List<Book>) {
    Box(Modifier.width(120.dp).height(110.dp), contentAlignment = Alignment.Center) {
        books.forEachIndexed { index, book ->
            val offset = index - (books.size - 1) / 2f
            BookCover(
                book,
                60.dp,
                Modifier.graphicsLayer {
                    translationX = offset * 28.dp.toPx()
                    rotationZ = offset * 10f
                }.shadow(8.dp, RoundedCornerShape(6.dp)),
            )
        }
    }
}
