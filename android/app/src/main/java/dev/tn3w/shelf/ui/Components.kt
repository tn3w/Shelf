package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.tn3w.shelf.R
import dev.tn3w.shelf.cover.*
import dev.tn3w.shelf.data.*
import kotlin.math.min

val ScreenPadding = 20.dp
private val BackBarPadding = 4.dp
private val TileWidth = 116.dp
private val GridTileWidth = 96.dp

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

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

private val PlaceholderHues =
    listOf(0xFF8C4A2F, 0xFF3F5B73, 0xFF5E6B3A, 0xFF7A3E5C, 0xFF4B4E6D, 0xFF9A6B2F)

private fun placeholderColor(work: Int) =
    Color(PlaceholderHues[work.mod(PlaceholderHues.size)])

@Composable
fun IconLabel(icon: ImageVector, @StringRes label: Int) {
    Icon(icon, null, Modifier.size(18.dp))
    Text(stringResource(label), Modifier.padding(start = 8.dp))
}

@Composable
fun BookCover(
    book: Book,
    width: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    sharedKey: String? = null,
) {
    val settings = loadedSettings()
    val offline = settings?.isOffline(LocalContext.current) ?: true
    var loaded by remember(book.work, book.cover) { mutableStateOf(false) }
    var failed by remember(book.work, book.cover) { mutableStateOf(false) }
    var alignment by remember(book.work, book.cover) { mutableStateOf(Alignment.Center) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .sharedCover(sharedKey)
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(placeholderColor(book.work)),
    ) {
        if (settings == null) return@Box
        if (book.isLocal && book.cover != 0 && !failed) {
            AsyncImage(
                model = shelfViewModel().library.coverFile(book),
                contentDescription = stringResource(R.string.cover_of, book.title),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        val url = book.coverUrl("L")
        if (offline || !settings.onlineCovers || url == null || failed) {
            DrawnCover(book)
            return@Box
        }
        if (!loaded) CoverPlaceholder(book)
        AsyncImage(
            model = url,
            contentDescription = stringResource(R.string.cover_of, book.title),
            contentScale = ContentScale.Crop,
            alignment = alignment,
            onSuccess = {
                loaded = true
                alignment = frontCover(it.painter.intrinsicSize)
            },
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

fun frontCover(size: Size): Alignment {
    if (size.width <= size.height) return Alignment.Center
    val spread = size.width / size.height * 1.5f
    return BiasAlignment(min(1f, 0.5f * spread / (spread - 1f)), 0f)
}

private fun initials(name: String) = name
    .split(" ")
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
    .take(2)
    .joinToString("")

@Composable
fun AuthorAvatar(author: Author, size: Dp, modifier: Modifier = Modifier) {
    val settings = loadedSettings()
    val offline = settings?.isOffline(LocalContext.current) ?: true
    var loaded by remember(author.number) { mutableStateOf(false) }
    val small = size < 64.dp
    val typography = MaterialTheme.typography
    val initialsStyle = if (small) typography.titleMedium else typography.headlineMedium
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!loaded) {
                Text(
                    initials(author.name),
                    style = initialsStyle,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (settings == null || offline || !settings.onlineCovers) return@Box
            val photo = if (small) "S" else "M"
            AsyncImage(
                model = author.photoUrl(photo),
                contentDescription = author.name,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DrawnCover(book: Book) {
    val catalogue = shelfViewModel().loaded.collectAsStateWithLifecycle().value?.catalogue
    val request = remember(book.work, book.title, book.author, book.tags, catalogue) {
        val tags = book.tags.ifEmpty {
            catalogue?.facts(book.work)?.tags?.toList().orEmpty()
        }
        val slugs = tags.mapNotNull { catalogue?.tags?.getOrNull(it)?.slug }
        CoverRequest(book.work, book.title, book.author, slugs)
    }
    AsyncImage(
        model = request,
        contentDescription = stringResource(R.string.cover_of, book.title),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CoverPlaceholder(book: Book) {
    Column(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(0.12f), Color.Transparent),
                ),
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
fun Hint(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) = Text(
    text,
    modifier,
    color,
    style = MaterialTheme.typography.bodySmall,
    textAlign = textAlign,
)

@Composable
fun LoadingBar() = LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))

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
            .padding(top = 28.dp, bottom = 12.dp),
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
    var menu by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.combinedClickable(
            onClick = { onOpen(book, origin) },
            onLongClick = onDismiss?.let { { menu = true } },
        ),
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
fun SkeletonTile(modifier: Modifier = Modifier.width(TileWidth)) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val reduced = LocalReducedMotion.current
    val pulse = rememberInfiniteTransition().animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
    )
    Column(modifier.graphicsLayer { alpha = if (reduced) 0.6f else pulse.value }) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
        Spacer(Modifier.size(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).background(color))
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).background(color))
    }
}

@Composable
fun ratingLabel(book: Book): String? {
    val locale = LocalConfiguration.current.locales[0]
    return book.popularity
        ?.takeIf { it.ratings > 0 }
        ?.let { "★ ${String.format(locale, "%.1f", it.rating)}" }
}

@Composable
fun BookListItem(
    book: Book,
    origin: String,
    shared: Boolean = true,
    detail: String? = null,
    onOpen: (Book, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onOpen(book, origin) }
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, 48.dp, sharedKey = "$origin-${book.work}".takeIf { shared })
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                detail ?: listOfNotNull(
                    book.author,
                    book.year.takeIf { it > 0 }?.toString(),
                    ratingLabel(book),
                ).joinToString(" · "),
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
                val label = progress?.let {
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
fun NoCatalogue(onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(Icons.Outlined.CloudOff, stringResource(R.string.no_catalogue))
        OutlinedButton(onClick = onSettings) {
            Text(stringResource(R.string.open_settings))
        }
    }
}

@Composable
fun EditableCover(
    onPicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { picked ->
        picked?.let(onPicked)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = stringResource(R.string.choose_cover)) {
                picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            },
    ) {
        cover()
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp),
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun <T> OptionsMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    options: List<Pair<String, T>>,
    current: T?,
    onSelect: (T) -> Unit,
) = DropdownMenu(open, onDismissRequest = onDismiss) {
    options.forEach { (text, value) ->
        DropdownMenuItem(
            text = { Text(text) },
            trailingIcon = { if (value == current) Icon(Icons.Outlined.Check, null) },
            onClick = {
                onDismiss()
                onSelect(value)
            },
        )
    }
}

@Composable
fun <T : Any> FilterMenuChip(
    name: String,
    options: List<Pair<String, T>>,
    current: T?,
    onSelect: (T?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = options.firstOrNull { it.second == current }?.first
    val icon = if (label == null) Icons.Outlined.ArrowDropDown else Icons.Outlined.Close
    Box {
        FilterChip(
            selected = label != null,
            onClick = { if (label == null) open = true else onSelect(null) },
            label = { Text(label ?: name, maxLines = 1) },
            trailingIcon = {
                Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize))
            },
        )
        OptionsMenu(open, { open = false }, options, current, onSelect)
    }
}

@Composable
fun <T> SortMenu(options: List<Pair<String, T>>, current: T, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconAction(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort)) {
            open = true
        }
        OptionsMenu(open, { open = false }, options, current, onSelect)
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
            Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack,
        )
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

@Composable
private fun TileGrid(content: LazyGridScope.() -> Unit) = LazyVerticalGrid(
    columns = GridCells.Adaptive(GridTileWidth),
    contentPadding = PaddingValues(ScreenPadding),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
    content = content,
)

private fun LazyGridScope.skeletonTiles() =
    items(12) { SkeletonTile(Modifier.fillMaxWidth()) }

private fun LazyGridScope.bookTiles(
    books: List<Book>,
    origin: String,
    onOpen: (Book, String) -> Unit,
) = items(books, key = { it.work }) {
    BookTile(it, origin, onOpen, Modifier.fillMaxWidth().animateItem())
}

@Composable
fun BookGrid(
    books: List<Book>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    header: LazyGridScope.() -> Unit = {},
) = TileGrid {
    header()
    if (books == null) skeletonTiles() else bookTiles(books, origin, onOpen)
}

@Composable
fun GroupedBookGrid(
    groups: List<AuthorGroup>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    header: LazyGridScope.() -> Unit = {},
) = TileGrid {
    header()
    if (groups == null) return@TileGrid skeletonTiles()
    val labelled = groups.size > 1 || groups.firstOrNull()?.series != null
    groups.forEach { group ->
        if (labelled) {
            fullWidth {
                Text(
                    group.series ?: stringResource(R.string.other_books),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
            }
        }
        bookTiles(group.books, origin, onOpen)
    }
}

fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
