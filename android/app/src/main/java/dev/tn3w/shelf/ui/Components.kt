package dev.tn3w.shelf.ui

import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.BitmapImage
import coil3.Image
import coil3.compose.AsyncImage
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.SimilarKind
import dev.tn3w.shelf.SimilarRoute
import dev.tn3w.shelf.cover.*
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

val ScreenPadding = 22.dp
private val TileWidth = 118.dp
private val GridTileWidth = 100.dp
private val GlowHeight = 460.dp
private const val TONE_WIDTH = 24
private const val TONE_HEIGHT = 36
private const val MAX_TONES = 600

val LocalDock = compositionLocalOf { 0.dp }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun statusBarHeight() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** Room a scrolling screen leaves below its last item for the dock and system bar. */
@Composable
fun bottomSpace(): Dp {
    val bars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return bars + LocalDock.current + 28.dp
}

@Composable
fun screenInsets() = PaddingValues(top = statusBarHeight(), bottom = bottomSpace())

/** The cover's glow fading into the page: the backdrop of every cover-led screen. */
@Composable
fun Modifier.glow(): Modifier {
    val colors = Look.colors
    return background(colors.paper).drawBehind {
        val fade = Brush.verticalGradient(
            listOf(colors.glow, colors.paper),
            endY = GlowHeight.toPx(),
        )
        drawRect(fade, size = Size(size.width, GlowHeight.toPx()))
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

private val PlaceholderHues =
    listOf(0xFF8C4A2F, 0xFF3F5B73, 0xFF5E6B3A, 0xFF7A3E5C, 0xFF4B4E6D, 0xFF9A6B2F)

private fun placeholderColor(work: Int) = PlaceholderHues[work.mod(PlaceholderHues.size)]

private val tones = mutableStateMapOf<Long, CoverTone>()

private fun toneKey(book: Book) =
    book.work.toLong() shl 32 or book.cover.toUInt().toLong()

private fun toneOf(image: Image): CoverTone? {
    val source = (image as? BitmapImage)?.bitmap ?: return null
    val hardware = source.config == Bitmap.Config.HARDWARE
    val readable = if (hardware) source.copy(Bitmap.Config.ARGB_8888, false) else source
    val small = Bitmap.createScaledBitmap(readable, TONE_WIDTH, TONE_HEIGHT, true)
    val pixels = IntArray(TONE_WIDTH * TONE_HEIGHT)
    small.getPixels(pixels, 0, TONE_WIDTH, 0, 0, TONE_WIDTH, TONE_HEIGHT)
    return coverTone(pixels)
}

private suspend fun learnTone(book: Book, image: Image) {
    val key = toneKey(book)
    if (key in tones) return
    val tone = withContext(Dispatchers.Default) {
        runCatching { toneOf(image) }.getOrNull()
    } ?: return
    if (tones.size >= MAX_TONES) tones.clear()
    tones[key] = tone
}

/** Colours of [book]'s cover once it has been shown, a steady stand-in until then. */
@Composable
fun coverTone(book: Book?): CoverTone? {
    if (book == null) return null
    val fallback = placeholderColor(book.work).toInt()
    return tones[toneKey(book)] ?: CoverTone(fallback, fallback)
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

private fun Modifier.bound() = drawWithContent {
    drawContent()
    val spine = size.width * 0.07f
    val shade = Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 0.2f),
        0.35f to Color.Transparent,
        0.6f to Color.White.copy(alpha = 0.14f),
        1f to Color.Transparent,
        endX = spine,
    )
    drawRect(shade, size = Size(spine, size.height))
}

@Composable
fun BookCover(
    book: Book,
    width: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    sharedKey: String? = null,
    lift: Dp = 3.dp,
) {
    val settings = loadedSettings()
    val colors = Look.colors
    val offline = settings?.isOffline(LocalContext.current) ?: true
    val scope = rememberCoroutineScope()
    var loaded by remember(book.work, book.cover) { mutableStateOf(false) }
    var failed by remember(book.work, book.cover) { mutableStateOf(false) }
    var direct by remember(book.work, book.cover) { mutableStateOf(false) }
    var alignment by remember(book.work, book.cover) { mutableStateOf(Alignment.Center) }
    val edge = if (colors.plain) colors.line else Color.Transparent
    val description = stringResource(R.string.cover_of, book.title)
    fun learn(image: Image) {
        scope.launch { learnTone(book, image) }
    }
    BoxWithConstraints(
        modifier
            .sharedCover(sharedKey)
            .width(width)
            .aspectRatio(2f / 3f)
            .shadow(if (colors.plain) 0.dp else lift, Corners.cover, clip = false)
            .clip(Corners.cover)
            .background(Color(placeholderColor(book.work)))
            .border(1.dp, edge, Corners.cover)
            .bound(),
    ) {
        if (settings == null) return@BoxWithConstraints
        if (book.isLocal && book.cover != 0 && !failed) {
            AsyncImage(
                model = shelfViewModel().library.coverFile(book),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                onSuccess = { learn(it.result.image) },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        val source = book.coverUrl("L")
        if (offline || !settings.onlineCovers || source == null || failed) {
            DrawnCover(book, description, ::learn)
            return@BoxWithConstraints
        }
        val pixels = with(LocalDensity.current) { maxWidth.roundToPx() }
        val base = settings.coverProxyBase()?.takeUnless { direct }
        val proxied = proxiedCover(book, base, pixels)
        if (!loaded) CoverPlaceholder(book)
        AsyncImage(
            model = proxied ?: source,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            alignment = alignment,
            onSuccess = {
                loaded = true
                alignment = frontCover(it.painter.intrinsicSize)
                learn(it.result.image)
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
    val colors = Look.colors
    val offline = settings?.isOffline(LocalContext.current) ?: true
    var loaded by remember(author.number) { mutableStateOf(false) }
    var direct by remember(author.number) { mutableStateOf(false) }
    val small = size < 64.dp
    Box(
        modifier.size(size).surface(CircleShape, colors.wash),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            val style = if (small) Look.type.label else Look.type.title
            Text(initials(author.name), color = colors.ink, style = style)
        }
        if (settings == null || offline || !settings.onlineCovers) return@Box
        val pixels = with(LocalDensity.current) { size.roundToPx() }
        val proxied = settings.coverProxyBase()
            ?.takeUnless { direct }
            ?.let { proxyPhotoUrl(it, author, pixels) }
        AsyncImage(
            model = proxied ?: author.photoUrl(if (small) "S" else "M"),
            contentDescription = author.name,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            onError = { if (proxied != null) direct = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DrawnCover(book: Book, description: String, onShown: (Image) -> Unit) {
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
        contentDescription = description,
        contentScale = ContentScale.Crop,
        onSuccess = { onShown(it.result.image) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CoverPlaceholder(book: Book) {
    Column(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(0.14f), Color.Transparent),
                ),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            book.title,
            color = Color.White,
            style = Look.type.heading.copy(fontSize = 13.sp, lineHeight = 16.sp),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author,
            color = Color.White.copy(alpha = 0.8f),
            style = Look.type.eyebrow.copy(letterSpacing = 0.em),
            maxLines = 2,
        )
    }
}

@Composable
fun Hint(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Look.colors.soft,
    textAlign: TextAlign? = null,
) = Text(text, modifier, color, style = Look.type.note, textAlign = textAlign)

@Composable
fun LargeTitle(text: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = ScreenPadding, end = 10.dp, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = Look.type.display,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action()
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    val clickable = if (onMore == null) Modifier else Modifier.clickable(onClick = onMore)
    Row(
        Modifier.fillMaxWidth()
            .then(clickable)
            .padding(
                start = ScreenPadding,
                end = 14.dp,
                top = Space.section,
                bottom = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = Look.type.heading,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { Hint(it, Modifier.padding(top = 2.dp)) }
        }
        if (onMore != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                stringResource(R.string.show_all),
                tint = Look.colors.faint,
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
fun SelectableTitle(
    text: String,
    selection: Selection,
    navigator: Navigator,
    selectionActions: @Composable RowScope.() -> Unit = {},
    action: @Composable RowScope.() -> Unit = {},
) {
    if (!selection.active) return LargeTitle(text, action)
    SelectionBar(selection, navigator, selectionActions)
}

@Composable
private fun SelectionBar(
    selection: Selection,
    navigator: Navigator,
    actions: @Composable RowScope.() -> Unit,
) {
    BackHandler(onBack = selection::clear)
    val count = selection.works.size
    val colors = Look.colors
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 10.dp)
            .surface(CircleShape, colors.wash)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.Rounded.Close,
            stringResource(R.string.clear),
            onClick = selection::clear,
        )
        Text(
            pluralStringResource(R.plurals.selected_count, count, count),
            Modifier.weight(1f).padding(start = 4.dp),
            style = Look.type.subhead,
            maxLines = 1,
        )
        actions()
        Pill(stringResource(R.string.find_similar), kind = PillKind.Bare) {
            navigator.similar(SimilarRoute(SimilarKind.Books, selection.works))
            selection.clear()
        }
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
                Check(true, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Text(
            book.title,
            Modifier.padding(top = 10.dp),
            style = Look.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author,
            color = Look.colors.soft,
            style = Look.type.note,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (!menu || onDismiss == null) return
    Sheet({ menu = false }, book.title, book.author, padded = false) {
        OptionRow(stringResource(R.string.not_for_me), icon = Icons.Rounded.Block) {
            menu = false
            onDismiss(book)
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
    val color = Look.colors.sunken
    val reduced = LocalReducedMotion.current
    val pulse = rememberInfiniteTransition().animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    )
    Column(modifier.graphicsLayer { alpha = if (reduced) 0.7f else pulse.value }) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(Corners.cover)
                .background(color),
        )
        Spacer(Modifier.size(10.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).background(color, CircleShape))
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
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, 52.dp, sharedKey = "$origin-${book.work}".takeIf { shared })
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                book.title,
                style = Look.type.subhead,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Hint(
                detail ?: listOfNotNull(
                    book.author,
                    book.year.takeIf { it > 0 }?.toString(),
                    ratingLabel(book),
                ).joinToString(" · "),
                Modifier.padding(top = 2.dp),
            )
        }
        if (selection?.contains(book) == true) Check(true)
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(36.dp), Look.colors.faint)
        Text(
            text,
            color = Look.colors.soft,
            style = Look.type.body,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyAction(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(icon, text, Modifier.padding(bottom = 0.dp))
        Pill(action, kind = PillKind.Line, onClick = onClick)
    }
}

@Composable
fun NoCatalogue(onSettings: () -> Unit) = EmptyAction(
    Icons.Rounded.CloudOff,
    stringResource(R.string.no_catalogue),
    stringResource(R.string.open_settings),
    onSettings,
)

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
        modifier.clickable(onClickLabel = stringResource(R.string.choose_cover)) {
            picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        },
    ) {
        cover()
        Box(
            Modifier.align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(30.dp)
                .surface(CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(16.dp)) }
    }
}

@Composable
fun FilterRow(
    actions: @Composable RowScope.() -> Unit = {},
    chips: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = chips,
        )
        actions()
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
    val icon = if (label == null) Icons.Rounded.ExpandMore else Icons.Rounded.Close
    Tag(label ?: name, selected = label != null, icon = icon) {
        if (label == null) open = true else onSelect(null)
    }
    if (open) OptionSheet(name, options, current, { open = false }, onSelect = onSelect)
}

@Composable
fun <T> SortMenu(options: List<Pair<String, T>>, current: T, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.sort)
    IconAction(Icons.AutoMirrored.Rounded.Sort, title) { open = true }
    if (open) OptionSheet(title, options, current, { open = false }, onSelect = onSelect)
}

@Composable
fun ShelfPicker(
    selected: Shelf?,
    modifier: Modifier = Modifier,
    onSelect: (Shelf) -> Unit,
) = Segments(Shelf.entries, selected, modifier, onSelect) { stringResource(it.label) }

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
    val remove = PackButton(Icons.Rounded.DeleteOutline, R.string.remove_pack, onRemove)
        .takeIf { state != PackState.Available }
    val action = when {
        offline -> remove

        download is Download.Failed ->
            PackButton(Icons.Rounded.ErrorOutline, R.string.retry_item, onDownload)

        download is Download.Paused ->
            PackButton(Icons.Rounded.Download, R.string.resume_item, onDownload)

        state == PackState.Available ->
            PackButton(Icons.Rounded.Download, R.string.download_item, onDownload)

        state == PackState.Update ->
            PackButton(Icons.Rounded.Update, R.string.update_pack, onDownload)

        else -> remove
    } ?: return Box(Modifier.size(LocalTouchTarget.current))
    val tint = if (action === remove) Look.colors.faint else Look.colors.accent
    IconAction(
        action.icon, stringResource(action.label, label), tint = tint,
        onClick = action.onClick,
    )
}

@Composable
private fun DownloadRing(value: Float, label: String, onPause: (() -> Unit)?) {
    val progress by animateFloatAsState(value)
    val pause = onPause?.let { Modifier.clip(CircleShape).clickable(onClick = it) }
    Box(
        Modifier.size(LocalTouchTarget.current).then(pause ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Ring(progress, size = 28.dp)
        if (onPause == null) return@Box
        Icon(
            Icons.Rounded.Pause,
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
fun BackBar(
    onBack: () -> Unit,
    title: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back),
            onClick = onBack,
        )
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 6.dp),
            style = Look.type.heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
private fun TileGrid(content: LazyGridScope.() -> Unit) = LazyVerticalGrid(
    columns = GridCells.Adaptive(GridTileWidth),
    contentPadding = PaddingValues(
        start = ScreenPadding,
        end = ScreenPadding,
        top = 14.dp,
        bottom = bottomSpace(),
    ),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalArrangement = Arrangement.spacedBy(22.dp),
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
                    Modifier.padding(top = 18.dp),
                    style = Look.type.heading,
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
