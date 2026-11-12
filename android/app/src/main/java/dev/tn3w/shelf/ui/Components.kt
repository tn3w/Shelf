package dev.tn3w.shelf.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.SimilarKind
import dev.tn3w.shelf.SimilarRoute
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

/** Proxy cover address for [book], or null when the source is loaded directly. */
@Composable
private fun proxiedCover(book: Book, base: String?, pixels: Int): String? {
    if (base == null) return null
    val catalogue = shelfViewModel().loaded.collectAsStateWithLifecycle().value?.catalogue
    val language = remember(book.work, book.language, catalogue) {
        book.language.ifEmpty {
            catalogue?.language?.takeIf { catalogue.locate(book.work) != null }.orEmpty()
        }
    }
    if (language.isEmpty()) return null
    return proxyCoverUrl(base, language, book.work, pixels)
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
    var direct by remember(book.work, book.cover) { mutableStateOf(false) }
    var alignment by remember(book.work, book.cover) { mutableStateOf(Alignment.Center) }
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(
        modifier
            .sharedCover(sharedKey)
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(placeholderColor(book.work)),
    ) {
        if (settings == null) return@BoxWithConstraints
        if (book.isLocal && book.cover != 0 && !failed) {
            AsyncImage(
                model = shelfViewModel().library.coverFile(book),
                contentDescription = stringResource(R.string.cover_of, book.title),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        val source = book.coverUrl("L")
        if (offline || !settings.onlineCovers || source == null || failed) {
            DrawnCover(book)
            return@BoxWithConstraints
        }
        val pixels = with(LocalDensity.current) { maxWidth.roundToPx() }
        val base = settings.coverProxyBase()?.takeUnless { direct }
        val proxied = proxiedCover(book, base, pixels)
        if (!loaded) CoverPlaceholder(book)
        AsyncImage(
            model = proxied ?: source,
            contentDescription = stringResource(R.string.cover_of, book.title),
            contentScale = ContentScale.Crop,
            alignment = alignment,
            onSuccess = {
                loaded = true
                alignment = frontCover(it.painter.intrinsicSize)
            },
            onError = { if (proxied != null) direct = true else failed = true },
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
    var direct by remember(author.number) { mutableStateOf(false) }
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
            val pixels = with(LocalDensity.current) { size.roundToPx() }
            val proxied = settings.coverProxyBase()
                ?.takeUnless { direct }
                ?.let { proxyPhotoUrl(it, author, pixels) }
            AsyncImage(
                model = proxied ?: author.photoUrl(photo),
                contentDescription = author.name,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { if (proxied != null) direct = true },
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

class Selection(private val state: MutableState<List<Int>>) {
    val works get() = state.value
    val active get() = works.isNotEmpty()

    operator fun contains(book: Book) = book.work in works

    fun toggle(book: Book) {
        state.value = if (book in this) works - book.work else works + book.work
    }

    fun clear() {
        state.value = emptyList()
    }
}

@Composable
fun rememberSelection() = Selection(rememberSaveable { mutableStateOf(emptyList<Int>()) })

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.bookClicks(
    book: Book,
    selection: Selection?,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) = combinedClickable(
    onClick = { if (selection?.active == true) selection.toggle(book) else onOpen() },
    onLongClick = selection?.let { { it.toggle(book) } } ?: onLongClick,
)

@Composable
private fun SelectedMark(modifier: Modifier = Modifier) = Icon(
    Icons.Filled.CheckCircle,
    contentDescription = null,
    modifier = modifier.background(MaterialTheme.colorScheme.surface, CircleShape),
    tint = MaterialTheme.colorScheme.primary,
)

@Composable
fun SelectableTitle(
    text: String,
    selection: Selection,
    navigator: Navigator,
    selectionActions: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
) {
    if (!selection.active) return LargeTitle(text, action)
    SelectionBar(selection, navigator, selectionActions)
}

@Composable
private fun SelectionBar(
    selection: Selection,
    navigator: Navigator,
    actions: @Composable () -> Unit,
) {
    BackHandler(onBack = selection::clear)
    val count = selection.works.size
    Row(
        Modifier.fillMaxWidth()
            .padding(start = BackBarPadding, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.Outlined.Close, stringResource(R.string.clear), selection::clear)
        Text(
            pluralStringResource(R.plurals.selected_count, count, count),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        actions()
        TextButton(
            onClick = {
                navigator.similar(SimilarRoute(SimilarKind.Books, selection.works))
                selection.clear()
            },
        ) { Text(stringResource(R.string.find_similar)) }
    }
}

@Composable
fun BookTile(
    book: Book,
    origin: String,
    onOpen: (Book, String) -> Unit,
    modifier: Modifier = Modifier.width(TileWidth),
    shared: Boolean = true,
    onDismiss: ((Book) -> Unit)? = null,
    selection: Selection? = null,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val showMenu = onDismiss?.let { { menu = true } }
    Column(modifier.bookClicks(book, selection, { onOpen(book, origin) }, showMenu)) {
        Box {
            BookCover(
                book,
                modifier = Modifier.fillMaxWidth(),
                sharedKey = "$origin-${book.work}".takeIf { shared },
            )
            if (selection?.contains(book) == true) {
                SelectedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
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
    selection: Selection? = null,
    onOpen: (Book, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .bookClicks(book, selection, { onOpen(book, origin) })
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
        if (selection?.contains(book) == true) SelectedMark()
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
fun PackAction(
    label: String,
    download: Download?,
    state: PackState,
    offline: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onPause: (() -> Unit)? = null,
) {
    if (download is Download.Running) {
        return DownloadRing(download.progress, label, onPause)
    }
    val remove = PackButton(Icons.Outlined.Delete, R.string.remove_pack, onRemove)
        .takeIf { state != PackState.Available }
    val action = when {
        offline -> remove

        download is Download.Failed ->
            PackButton(Icons.Outlined.ErrorOutline, R.string.retry_item, onDownload)

        download is Download.Paused ->
            PackButton(Icons.Outlined.Download, R.string.resume_item, onDownload)

        state == PackState.Available ->
            PackButton(Icons.Outlined.Download, R.string.download_item, onDownload)

        state == PackState.Update ->
            PackButton(Icons.Outlined.Update, R.string.update_pack, onDownload)

        else -> remove
    } ?: return Box(Modifier.size(48.dp))
    IconAction(action.icon, stringResource(action.label, label), action.onClick)
}

@Composable
private fun DownloadRing(value: Float, label: String, onPause: (() -> Unit)?) {
    val progress by animateFloatAsState(value)
    val ring = @Composable {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(24.dp),
            strokeWidth = 3.dp,
        )
    }
    if (onPause == null) {
        return Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { ring() }
    }
    IconButton(onPause) {
        ring()
        Icon(
            Icons.Outlined.Pause,
            stringResource(R.string.pause_item, label),
            Modifier.size(14.dp),
        )
    }
}

private class PackButton(
    val icon: ImageVector,
    @StringRes val label: Int,
    val onClick: () -> Unit,
)

@Composable
fun BackBar(onBack: () -> Unit, title: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BackBarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    selection: Selection? = null,
) = items(books, key = { it.work }) {
    val modifier = Modifier.fillMaxWidth().animateItem()
    BookTile(it, origin, onOpen, modifier, selection = selection)
}

@Composable
fun BookGrid(
    books: List<Book>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    selection: Selection? = null,
    header: LazyGridScope.() -> Unit = {},
    footer: LazyGridScope.() -> Unit = {},
) = TileGrid {
    header()
    if (books == null) skeletonTiles() else bookTiles(books, origin, onOpen, selection)
    footer()
}

@Composable
fun GroupedBookGrid(
    groups: List<AuthorGroup>?,
    origin: String,
    onOpen: (Book, String) -> Unit,
    header: LazyGridScope.() -> Unit = {},
    footer: LazyGridScope.() -> Unit = {},
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
    footer()
}

fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun Modifier.bleed(padding: Dp) = layout { measurable, constraints ->
    val extra = padding.roundToPx() * 2
    val wider = constraints.copy(maxWidth = constraints.maxWidth + extra)
    val placeable = measurable.measure(wider)
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

fun LazyGridScope.fullBleed(content: @Composable () -> Unit) =
    fullWidth { Column(Modifier.bleed(ScreenPadding)) { content() } }
