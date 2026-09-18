package dev.tn3w.shelf.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.tn3w.shelf.Loaded
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.Book
import dev.tn3w.shelf.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val ScreenPadding = 20.dp
val BackBarPadding = 4.dp
val TileWidth = 116.dp
private val GridTileWidth = 96.dp

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable fun shelfApp() = LocalContext.current.applicationContext as ShelfApp

@Composable
fun <T> load(vararg keys: Any?, block: suspend Loaded.() -> T): State<T?> {
    val loaded by shelfApp().loaded.collectAsStateWithLifecycle()
    return produceState<T?>(null, loaded, *keys) {
        val current = loaded ?: return@produceState
        value = withContext(Dispatchers.IO) { current.block() }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedCover(key: String?): Modifier {
    val shared = LocalSharedScope.current
    val animated = LocalAnimatedScope.current
    if (key == null || shared == null || animated == null || LocalReducedMotion.current) {
        return this
    }
    return with(shared) {
        sharedElement(rememberSharedContentState(key), animatedVisibilityScope = animated)
    }
}

private fun placeholderColor(work: Int): Color {
    val hues =
        listOf(0xFF8C4A2F, 0xFF3F5B73, 0xFF5E6B3A, 0xFF7A3E5C, 0xFF4B4E6D, 0xFF9A6B2F)
    return Color(hues[Math.floorMod(work, hues.size)])
}

@Composable
fun BookCover(
    book: Book,
    width: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    sharedKey: String? = null,
) {
    val settings by shelfApp().library.settings.collectAsStateWithLifecycle(Settings())
    var loaded by remember(book.work) { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .sharedCover(sharedKey)
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(placeholderColor(book.work))
    ) {
        if (!loaded) CoverPlaceholder(book)
        if (!settings.onlineCovers || book.cover == 0) return@Box
        AsyncImage(
            model = book.coverUrl("L"),
            contentDescription = stringResource(R.string.cover_of, book.title),
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CoverPlaceholder(book: Book) {
    Column(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(0.12f), Color.Transparent))
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            book.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
        )
    }
}

@Composable
fun LargeTitle(text: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = ScreenPadding, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    val clickable = if (onMore == null) Modifier else Modifier.clickable(onClick = onMore)
    Column(
        Modifier.fillMaxWidth()
            .then(clickable)
            .padding(horizontal = ScreenPadding)
            .padding(top = 28.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (onMore != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.show_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookTile(
    book: Book,
    origin: String,
    onOpen: (Book, String) -> Unit,
    modifier: Modifier = Modifier.width(TileWidth),
    shared: Boolean = true,
    onDismiss: ((Book) -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier.combinedClickable(
            onClick = { onOpen(book, origin) },
            onLongClick = onDismiss?.let { { menu = true } },
        )
    ) {
        BookCover(
            book,
            modifier = Modifier.fillMaxWidth(),
            sharedKey = "$origin-${book.work}".takeIf { shared },
        )
        Spacer(Modifier.size(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onDismiss == null) return@Column
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.not_for_me)) },
                onClick = {
                    menu = false
                    onDismiss(book)
                },
            )
        }
    }
}

@Composable
fun BookRow(
    books: List<Book>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    shared: Boolean = true,
    onDismiss: ((Book) -> Unit)? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = books != null,
    ) {
        if (books == null) {
            items(6) { SkeletonTile() }
            return@LazyRow
        }
        items(books, key = { it.work }) {
            Box(Modifier.animateItem()) {
                BookTile(it, origin, onOpen, shared = shared, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
fun BookSection(
    title: String,
    books: List<Book>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    subtitle: String? = null,
    onMore: (() -> Unit)? = null,
    shared: Boolean = true,
    onDismiss: ((Book) -> Unit)? = null,
) {
    SectionHeader(title, subtitle, onMore)
    BookRow(books, origin, onOpen, shared, onDismiss)
}

@Composable
private fun skeletonAlpha(): Float {
    if (LocalReducedMotion.current) return 0.6f
    val alpha by
        rememberInfiniteTransition()
            .animateFloat(
                initialValue = 0.35f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            )
    return alpha
}

@Composable
fun SkeletonTile(modifier: Modifier = Modifier.width(TileWidth)) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.alpha(skeletonAlpha())) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
        )
        Spacer(Modifier.size(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).background(color))
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).background(color))
    }
}

@Composable
fun BookListItem(book: Book, origin: String, onOpen: (Book, String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onOpen(book, origin) }
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, 48.dp, sharedKey = "$origin-${book.work}")
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                listOfNotNull(book.author, book.year.takeIf { it > 0 })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ContinueCard(book: Book, progress: Float?, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.width(320.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, 64.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                val label =
                    progress?.let {
                        stringResource(R.string.percent_read, (it * 100).toInt())
                    } ?: stringResource(R.string.import_to_start)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                LinearProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = Modifier.width(160.dp),
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
}

@Composable
fun BackBar(onBack: () -> Unit, title: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BackBarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.AutoMirrored.Filled.ArrowBack,
            stringResource(R.string.back),
            onBack,
        )
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

@Composable
fun BookGrid(
    books: List<Book>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    header: LazyGridScope.() -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GridTileWidth),
        contentPadding = PaddingValues(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        header()
        if (books == null) {
            items(12) { SkeletonTile(Modifier.fillMaxWidth()) }
            return@LazyVerticalGrid
        }
        items(books, key = { it.work }) {
            BookTile(it, origin, onOpen, Modifier.fillMaxWidth().animateItem())
        }
    }
}

fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
