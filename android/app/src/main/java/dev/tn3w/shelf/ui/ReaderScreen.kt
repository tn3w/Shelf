package dev.tn3w.shelf.ui

import android.content.Context
import android.provider.Settings.System
import android.view.Window
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

private val PAGE_PADDING = 28.dp
private val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.25f, 4 to 1.1f)
private const val SEARCH_DELAY = 250L
private val HIGHLIGHT = Color(0x66FFC107)

private data class TextPage(
    val section: Int,
    val start: Int,
    val text: AnnotatedString = AnnotatedString(""),
    val image: String? = null,
)

data class Hits(val query: String = "", val matches: List<Match> = emptyList())

sealed interface Opened {
    data class Ready(val progress: Progress, val document: Document) : Opened

    data object Failed : Opened
}

class ReaderViewModel(app: ShelfApp, private val work: Int) : ShelfViewModel(app) {
    private val opening = app.scope.async(Dispatchers.IO) {
        runSuspendCatching {
            val progress = library.progress.first().getValue(work)
            Opened.Ready(progress, openDocument(library.bookFile(progress.file)))
        }.getOrDefault(Opened.Failed)
    }
    val opened = flow { emit(opening.await()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var chrome by mutableStateOf(false)
    var showChapters by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var query by mutableStateOf("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val hits = snapshotFlow { query.trim() }
        .debounce { if (it.length < MIN_QUERY) 0 else SEARCH_DELAY }
        .distinctUntilChanged()
        .mapLatest { needle ->
            val document = (opening.await() as? Opened.Ready)?.document
            Hits(needle, (document as? TextDocument)?.find(needle).orEmpty())
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Hits())

    val bookmarks = library.bookmarks.map { it[work].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleBookmark(onPage: List<Bookmark>, create: () -> Bookmark) = detached {
        library.changeBookmarks(work) { marks ->
            if (onPage.isEmpty()) marks + create() else marks - onPage.toSet()
        }
    }

    fun removeBookmark(bookmark: Bookmark) = detached {
        library.changeBookmarks(work) { it - bookmark }
    }

    fun closeSearch() {
        showSearch = false
        query = ""
    }

    fun save(progress: Progress, pagesRead: Int) {
        detached { library.saveProgress(work, progress, pagesRead) }
    }

    fun changeFont(change: Float) = updateSettings {
        it.copy(fontScale = (it.fontScale + change).coerceIn(FONT_SCALES))
    }

    fun saveBrightness(brightness: Float) = updateSettings {
        it.copy(readerBrightness = brightness)
    }

    override fun onCleared() {
        app.scope.launch(Dispatchers.IO) {
            (opening.await() as? Opened.Ready)?.document?.close()
        }
    }
}

@Composable
private fun readerViewModel(work: Int) = shelfViewModel { ReaderViewModel(it, work) }

@Immutable
private data class ReaderState(
    val viewModel: ReaderViewModel,
    val title: String,
    val navigator: Navigator,
    val onFontScale: ((Float) -> Unit)?,
    val volumeKeys: Boolean,
)

data class PagePalette(val background: Color, val text: Color)

@Composable
fun pagePalette(settings: Settings) = when (settings.pageColor) {
    PageColor.Theme ->
        PagePalette(
            MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface,
        )

    PageColor.Paper -> PagePalette(Color(0xFFF4ECD8), Color(0xFF3B2F22))

    PageColor.Night -> PagePalette(Color.Black, Color(0xFFC8C8C8))
}

fun readerStyle(settings: Settings, color: Color) = TextStyle(
    fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
    fontSize = (19 * settings.fontScale).sp,
    lineHeight = settings.lineSpacing.em,
    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
    color = color,
    lineBreak = LineBreak.Paragraph,
)

private fun pageStep(key: Key, volumeKeys: Boolean) = when (key) {
    Key.PageDown, Key.DirectionRight -> 1
    Key.PageUp, Key.DirectionLeft -> -1
    Key.VolumeDown -> if (volumeKeys) 1 else null
    Key.VolumeUp -> if (volumeKeys) -1 else null
    else -> null
}

private const val MAX_TURN = 3
private val MAX_LINE_WIDTH = 640.dp

internal fun sideMargin(width: Dp, margin: Dp) =
    maxOf(margin, (width - MAX_LINE_WIDTH) / 2)

private const val MIN_BRIGHTNESS = 0.01f
private const val MAX_LEVEL = 255

internal fun swipedBrightness(current: Float, dragged: Float, height: Int) =
    (current - dragged / height).coerceIn(MIN_BRIGHTNESS, 1f)

internal fun newPagesRead(previous: Int, settled: Int, furthest: Int) =
    if (settled - previous in 1..MAX_TURN) {
        (settled - maxOf(previous, furthest)).coerceAtLeast(0)
    } else {
        0
    }

@Composable
fun ReaderScreen(work: Int, navigator: Navigator) {
    val viewModel = readerViewModel(work)
    val settings = loadedSettings() ?: Settings()
    val saved by viewModel.library.saved.collectAsStateWithLifecycle(emptyList())
    val title = saved.firstOrNull { it.work == work }?.title.orEmpty()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val state = remember(viewModel, title, navigator, settings.volumeKeys) {
        ReaderState(
            viewModel, title, navigator, viewModel::changeFont, settings.volumeKeys,
        )
    }
    val palette = pagePalette(settings)
    Surface(
        Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text,
    ) {
        BrightnessSwipe(
            settings.brightnessSwipe,
            settings.readerBrightness,
            viewModel::saveBrightness,
        ) {
            when (val current = opened) {
                null -> Loading()

                Opened.Failed -> OpenFailed(navigator)

                is Opened.Ready ->
                    when (val document = current.document) {
                        is TextDocument ->
                            TextReader(state, document, current.progress, settings)

                        is PagedDocument ->
                            PagedReader(state, document, current.progress)
                    }
            }
        }
    }
}

private fun Window.setBrightness(brightness: Float?) {
    attributes = attributes.apply {
        screenBrightness = brightness ?: BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun Context.systemBrightness(): Float {
    val level = System.getInt(contentResolver, System.SCREEN_BRIGHTNESS, MAX_LEVEL / 2)
    return level / MAX_LEVEL.toFloat()
}

@Composable
private fun BrightnessSwipe(
    enabled: Boolean,
    saved: Float?,
    onSave: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val window = LocalActivity.current?.window
    val save by rememberUpdatedState(onSave)
    var brightness by remember(saved) { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf(false) }
    SideEffect { window?.setBrightness(brightness.takeIf { enabled }) }
    DisposableEffect(window) { onDispose { window?.setBrightness(null) } }

    fun finish() {
        dragging = false
        brightness?.let(save)
    }
    val swipe = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = {
                brightness = brightness ?: context.systemBrightness()
                dragging = true
            },
            onDragEnd = ::finish,
            onDragCancel = ::finish,
        ) { change, dragged ->
            change.consume()
            brightness = brightness?.let { swipedBrightness(it, dragged, size.height) }
        }
    }
    Box(if (enabled) swipe else Modifier) {
        content()
        if (dragging) brightness?.let { BrightnessBadge(it) }
    }
}

@Composable
private fun BoxScope.BrightnessBadge(brightness: Float) {
    Surface(
        Modifier.align(Alignment.Center),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.BrightnessMedium, contentDescription = null)
            Text("${(brightness * 100).roundToInt()} %")
        }
    }
}

@Composable
private fun OpenFailed(navigator: Navigator) {
    Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
        BackBar(navigator::back)
        EmptyState(Icons.Outlined.ErrorOutline, stringResource(R.string.open_failed))
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun AnnotatedString.Builder.appendBlock(block: Block, fontSize: Float) {
    val alignment = if (block.centered) TextAlign.Center else TextAlign.Unspecified
    withStyle(ParagraphStyle(textAlign = alignment)) {
        val start = length
        val heading = HEADING_SCALE[block.heading]?.let {
            SpanStyle(fontSize = (fontSize * it).sp, fontWeight = FontWeight.Bold)
        }
        if (heading != null) {
            withStyle(heading) { append(block.text) }
        } else {
            append(block.text)
        }
        block.spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
            addStyle(style, start + span.start, start + span.end)
        }
    }
}

private class PageLayout(
    val measurer: TextMeasurer,
    val style: TextStyle,
    val width: Int,
    val height: Int,
)

private fun textPages(
    section: Int,
    blocks: List<Block>,
    offset: Int,
    layout: PageLayout,
) = buildList {
    val text = buildAnnotatedString {
        blocks.forEach { appendBlock(it, layout.style.fontSize.value) }
    }
    val measured = layout.measurer.measure(
        text, layout.style, constraints = Constraints(maxWidth = layout.width),
    )
    var line = 0
    while (line < measured.lineCount) {
        val top = measured.getLineTop(line)
        var last = line
        while (
            last + 1 < measured.lineCount &&
            measured.getLineBottom(last + 1) - top <= layout.height
        ) {
            last++
        }
        val start = measured.getLineStart(line)
        val end = measured.getLineEnd(last)
        if (text.substring(start, end).isNotBlank()) {
            add(TextPage(section, offset + start, text.subSequence(start, end)))
        }
        line = last + 1
    }
}

private fun paginate(document: TextDocument, layout: PageLayout): List<TextPage> =
    document.sections.flatMapIndexed { section, blocks ->
        val starts = blockStarts(blocks)
        val pages = mutableListOf<TextPage>()
        var run = 0
        fun flush(end: Int) {
            if (end > run) {
                pages += textPages(section, blocks.subList(run, end), starts[run], layout)
            }
        }
        blocks.forEachIndexed { index, block ->
            if (block.image == null) return@forEachIndexed
            flush(index)
            pages += TextPage(section, starts[index], image = block.image)
            run = index + 1
        }
        flush(blocks.size)
        pages
    }

private fun List<TextPage>.indexAt(section: Int, offset: Int) =
    indexOfLast { it.section < section || (it.section == section && it.start <= offset) }
        .coerceAtLeast(0)

private fun AnnotatedString.highlighted(query: String): AnnotatedString {
    val ranges = matchRanges(text, query)
    if (ranges.isEmpty()) return this
    return buildAnnotatedString {
        append(this@highlighted)
        ranges.forEach {
            addStyle(SpanStyle(background = HIGHLIGHT), it.first, it.last + 1)
        }
    }
}

private class PageMarks(
    val placed: List<Pair<Bookmark, Int>>,
    val create: (Int) -> Bookmark,
) {
    fun on(page: Int) = placed.filter { it.second == page }.map { it.first }
}

private fun textMarks(bookmarks: List<Bookmark>, pages: List<TextPage>) = PageMarks(
    bookmarks.map { it to pages.indexAt(it.section, it.offset) },
) { index ->
    val page = pages[index]
    Bookmark(page.section, page.start, index, excerptOf(page.text))
}

@Composable
private fun TextReader(
    state: ReaderState,
    document: TextDocument,
    progress: Progress,
    settings: Settings,
) {
    val viewModel = state.viewModel
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val style = readerStyle(settings, pagePalette(settings).text)
    val margin = settings.margin.dp
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    var position by rememberSaveable(stateSaver = jsonSaver()) {
        mutableStateOf(progress)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val side = sideMargin(maxWidth, margin)
        val (horizontal, vertical) =
            with(LocalDensity.current) { side.roundToPx() to margin.roundToPx() }
        val width = constraints.maxWidth - 2 * horizontal
        val height = constraints.maxHeight - 2 * vertical
        val pages by produceState<List<TextPage>?>(null, document, width, height, style) {
            value = withContext(Dispatchers.Default) {
                paginate(document, PageLayout(measurer, style, width, height))
            }
        }
        val current = pages ?: return@BoxWithConstraints Loading()
        if (current.isEmpty()) return@BoxWithConstraints OpenFailed(state.navigator)
        val initial =
            remember(current) { current.indexAt(position.section, position.offset) }
        val chapters = remember(document, current) {
            document.chapters.map { chapter ->
                chapter.title to current.indexOfFirst { it.section >= chapter.section }
            }
        }
        val marks = remember(bookmarks, current) { textMarks(bookmarks, current) }
        key(current) {
            val pager = rememberPagerState(initial) { current.size }
            val scope = rememberCoroutineScope()
            Pages(
                pager,
                state,
                chapters,
                marks,
                onSearch = { viewModel.showSearch = true },
                save = { index ->
                    val page = current[index]
                    Progress(progress.file, page.section, page.start, index, current.size)
                        .also { position = it }
                },
            ) { index ->
                val page = current[index]
                if (page.image == null) {
                    val text = remember(page.text, hits.query) {
                        page.text.highlighted(hits.query)
                    }
                    Text(
                        text,
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = side, vertical = margin),
                    )
                } else {
                    PageImage(document, page.image)
                }
            }
            if (viewModel.showSearch) {
                SearchPanel(
                    viewModel,
                    hits,
                    chapters,
                    pageOf = { current.indexAt(it.section, it.offset) },
                ) { target ->
                    viewModel.showSearch = false
                    viewModel.chrome = false
                    scope.launch { pager.scrollToPage(target) }
                }
            }
        }
    }
}

@Composable
private fun PageImage(document: TextDocument, path: String) {
    val image by produceState<ByteArray?>(null, path) {
        value = withContext(Dispatchers.IO) { document.image(path) }
    }
    AsyncImage(
        image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(PAGE_PADDING),
    )
}

@Composable
private fun PagedReader(state: ReaderState, document: PagedDocument, progress: Progress) {
    val count = document.pageCount
    val pager =
        rememberPagerState(progress.page.coerceIn(0, maxOf(0, count - 1))) { count }
    val bookmarks by state.viewModel.bookmarks.collectAsStateWithLifecycle()
    val marks = remember(bookmarks, count) {
        PageMarks(bookmarks.map { it to it.page.coerceIn(0, maxOf(0, count - 1)) }) {
            Bookmark(page = it)
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        Pages(
            pager,
            state.copy(onFontScale = null),
            emptyList(),
            marks,
            onSearch = null,
            save = { progress.copy(page = it, pages = count) },
        ) {
            val page by produceState<Result<Any>?>(null, it, width, height) {
                value = withContext(Dispatchers.IO) {
                    runSuspendCatching { document.page(it, width, height) }
                }
            }
            var failed by remember(page) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val image = page?.getOrNull()
                if (page == null) return@Box CircularProgressIndicator()
                if (image == null || failed) {
                    return@Box EmptyState(
                        Icons.Outlined.BrokenImage, stringResource(R.string.page_failed),
                    )
                }
                AsyncImage(
                    image,
                    contentDescription = stringResource(R.string.page_of, it + 1, count),
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun chromeEnter(reduced: Boolean, offset: (Int) -> Int) = when {
    reduced -> EnterTransition.None
    else -> fadeIn() + slideInVertically(initialOffsetY = offset)
}

private fun chromeExit(reduced: Boolean, offset: (Int) -> Int) = when {
    reduced -> ExitTransition.None
    else -> fadeOut() + slideOutVertically(targetOffsetY = offset)
}

private fun chapterAt(chapters: List<Pair<String, Int>>, page: Int) =
    chapters.lastOrNull { it.second in 0..page }

@Composable
private fun Pages(
    pager: PagerState,
    state: ReaderState,
    chapters: List<Pair<String, Int>>,
    marks: PageMarks,
    onSearch: (() -> Unit)?,
    save: (Int) -> Progress,
    page: @Composable (Int) -> Unit,
) {
    val viewModel = state.viewModel
    val scope = rememberCoroutineScope()
    var chrome by viewModel::chrome
    var showChapters by viewModel::showChapters
    var furthest by rememberSaveable { mutableIntStateOf(pager.currentPage) }
    var previous by rememberSaveable { mutableIntStateOf(pager.currentPage) }
    val chapter = chapterAt(chapters, pager.currentPage)
    val focus = remember { FocusRequester() }
    val reduced = LocalReducedMotion.current
    fun turn(page: Int) = scope.launch {
        if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page)
    }

    LaunchedEffect(chrome, showChapters, viewModel.showSearch) {
        if (!chrome && !showChapters && !viewModel.showSearch) focus.requestFocus()
    }

    LaunchedEffect(pager.settledPage) {
        val settled = pager.settledPage
        val read = newPagesRead(previous, settled, furthest)
        viewModel.save(save(settled), read)
        furthest = maxOf(furthest, settled)
        previous = settled
    }

    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val step = pageStep(event.key, state.volumeKeys)
                if (step != null && event.type == KeyEventType.KeyDown) {
                    turn(pager.currentPage + step)
                }
                step != null
            },
    ) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
        ) { index ->
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val third = size.width / 3
                        when {
                            offset.x < third -> turn(index - 1)
                            offset.x > 2 * third -> turn(index + 1)
                            else -> chrome = !chrome
                        }
                    }
                },
            ) {
                page(index)
                if (marks.on(index).isNotEmpty()) BookmarkRibbon()
            }
        }
        AnimatedVisibility(
            chrome,
            enter = chromeEnter(reduced) { -it / 2 },
            exit = chromeExit(reduced) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val onPage = marks.on(pager.currentPage)
            val contents =
                if (chapters.isEmpty()) SheetTab.Bookmarks else SheetTab.Contents
            ReaderTopBar(
                state,
                bookmarked = onPage.isNotEmpty(),
                onBookmark = {
                    viewModel.toggleBookmark(onPage) { marks.create(pager.currentPage) }
                },
                onSearch = onSearch,
                contents = contents,
                onContents = { showChapters = true },
            )
        }
        AnimatedVisibility(
            chrome,
            enter = chromeEnter(reduced) { it },
            exit = chromeExit(reduced) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(pager, chapter?.first, state.onFontScale) {
                scope.launch { pager.scrollToPage(it) }
            }
        }
        if (showChapters) {
            ContentsSheet(
                chapters,
                chapter,
                marks.placed,
                pager.pageCount,
                onDismiss = { showChapters = false },
                onRemove = viewModel::removeBookmark,
            ) { target ->
                showChapters = false
                chrome = false
                scope.launch { pager.scrollToPage(target) }
            }
        }
    }
}

@Composable
private fun BoxScope.BookmarkRibbon() {
    Icon(
        Icons.Filled.Bookmark,
        contentDescription = stringResource(R.string.bookmarks),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp).size(24.dp),
    )
}

@Composable
private fun ReaderTopBar(
    state: ReaderState,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onSearch: (() -> Unit)?,
    contents: SheetTab,
    onContents: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.back),
                state.navigator::back,
            )
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            onSearch?.let {
                val search = stringResource(R.string.search_book)
                IconAction(Icons.Outlined.Search, search, it)
            }
            BookmarkToggle(bookmarked, onBookmark)
            val list = Icons.AutoMirrored.Outlined.List
            IconAction(list, stringResource(contents.label), onContents)
        }
    }
}

@Composable
private fun BookmarkToggle(bookmarked: Boolean, onToggle: () -> Unit) {
    val label = if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark
    val colors = MaterialTheme.colorScheme
    IconToggleButton(bookmarked, onCheckedChange = { onToggle() }) {
        Icon(
            if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
            contentDescription = stringResource(label),
            tint = if (bookmarked) colors.primary else LocalContentColor.current,
        )
    }
}

@Composable
private fun ReaderBottomBar(
    pager: PagerState,
    chapter: String?,
    onFontScale: ((Float) -> Unit)?,
    onJump: (Int) -> Unit,
) {
    var dragging by rememberSaveable { mutableStateOf<Float?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            chapter?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Slider(
                value = dragging ?: pager.currentPage.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onJump(it.roundToInt()) }
                    dragging = null
                },
                valueRange = 0f..maxOf(1, pager.pageCount - 1).toFloat(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val smaller = stringResource(R.string.smaller_text)
                onFontScale?.let {
                    IconAction(Icons.Outlined.TextDecrease, smaller) { it(-0.1f) }
                }
                val shown = (dragging?.roundToInt() ?: pager.currentPage) + 1
                Text(
                    stringResource(R.string.page_of, shown, pager.pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                val larger = stringResource(R.string.larger_text)
                onFontScale?.let {
                    IconAction(Icons.Outlined.TextIncrease, larger) { it(0.1f) }
                }
            }
        }
    }
}

private enum class SheetTab(@StringRes val label: Int) {
    Contents(R.string.contents),
    Bookmarks(R.string.bookmarks),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    chapters: List<Pair<String, Int>>,
    current: Pair<String, Int>?,
    bookmarks: List<Pair<Bookmark, Int>>,
    pageCount: Int,
    onDismiss: () -> Unit,
    onRemove: (Bookmark) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val listed = chapters.filter { it.second >= 0 }
    val tabs = if (listed.isEmpty()) listOf(SheetTab.Bookmarks) else SheetTab.entries
    var selected by rememberSaveable { mutableStateOf(tabs.first()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (tabs.size > 1) {
            PrimaryTabRow(
                selectedTabIndex = tabs.indexOf(selected),
                containerColor = Color.Transparent,
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        text = { Text(stringResource(tab.label)) },
                    )
                }
            }
        } else {
            Text(
                stringResource(selected.label),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
            )
        }
        when (selected) {
            SheetTab.Contents -> ChapterList(listed, current, onSelect)

            SheetTab.Bookmarks ->
                BookmarkList(bookmarks, chapters, pageCount, onRemove, onSelect)
        }
    }
}

@Composable
private fun ChapterList(
    chapters: List<Pair<String, Int>>,
    current: Pair<String, Int>?,
    onSelect: (Int) -> Unit,
) {
    LazyColumn {
        items(chapters) { chapter ->
            val colors = MaterialTheme.colorScheme
            val color = if (chapter == current) colors.primary else colors.onSurface
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(chapter.second) }
                    .padding(horizontal = ScreenPadding, vertical = 14.dp),
            ) {
                Text(
                    chapter.first,
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
                PageNumber(chapter.second)
            }
        }
    }
}

@Composable
private fun PageNumber(index: Int) {
    Text(
        "${index + 1}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val Bookmark.key
    get() = "$section:$offset:$page"

@Composable
private fun BookmarkList(
    bookmarks: List<Pair<Bookmark, Int>>,
    chapters: List<Pair<String, Int>>,
    pageCount: Int,
    onRemove: (Bookmark) -> Unit,
    onSelect: (Int) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        val empty = stringResource(R.string.no_bookmarks)
        return EmptyState(Icons.Outlined.BookmarkBorder, empty)
    }
    val sorted = remember(bookmarks) {
        bookmarks.sortedWith(compareBy({ it.second }, { it.first.offset }))
    }
    LazyColumn {
        items(sorted, key = { it.first.key }) { (mark, page) ->
            val title = chapterAt(chapters, page)?.first
                ?: stringResource(R.string.page_of, page + 1, pageCount)
            BookmarkRow(mark, title, page, { onRemove(mark) }) { onSelect(page) }
        }
    }
}

@Composable
private fun BookmarkRow(
    mark: Bookmark,
    title: String,
    page: Int,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = ScreenPadding, end = 4.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mark.excerpt.isNotBlank()) {
                Text(
                    mark.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PageNumber(page)
        val remove = stringResource(R.string.remove_bookmark)
        IconAction(Icons.Outlined.Close, remove, onRemove)
    }
}

@Composable
private fun SearchPanel(
    viewModel: ReaderViewModel,
    hits: Hits,
    chapters: List<Pair<String, Int>>,
    pageOf: (Match) -> Int,
    onSelect: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val needle = viewModel.query.trim()
    val searching = needle.length >= MIN_QUERY && hits.query != needle
    BackHandler(onBack = viewModel::closeSearch)
    LaunchedEffect(Unit) { if (viewModel.query.isEmpty()) focus.requestFocus() }
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.imePadding()) {
            Row(
                Modifier.padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.back),
                    viewModel::closeSearch,
                )
                SearchField(
                    viewModel.query,
                    onChange = { viewModel.query = it },
                    hint = stringResource(R.string.search_book),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    onSubmit = { keyboard?.hide() },
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                needle.length < MIN_QUERY -> {
                    val hint = stringResource(R.string.search_book_hint)
                    EmptyState(Icons.Outlined.Search, hint)
                }

                hits.query == needle && hits.matches.isEmpty() -> {
                    val none = stringResource(R.string.no_matches)
                    EmptyState(Icons.Outlined.SearchOff, none)
                }

                else -> SearchResults(hits, chapters, pageOf, onSelect)
            }
        }
    }
}

private fun snippet(match: Match) = buildAnnotatedString {
    append(match.before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = HIGHLIGHT)) {
        append(match.text)
    }
    append(match.after)
}

@Composable
private fun SearchResults(
    hits: Hits,
    chapters: List<Pair<String, Int>>,
    pageOf: (Match) -> Int,
    onSelect: (Int) -> Unit,
) {
    val results = remember(hits) { hits.matches.map { it to pageOf(it) } }
    val count = hits.matches.size
    Text(
        if (count >= MAX_MATCHES) {
            stringResource(R.string.many_matches, MAX_MATCHES)
        } else {
            pluralStringResource(R.plurals.matches, count, count)
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
    LazyColumn {
        items(results) { (match, page) ->
            Column(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(page) }
                    .padding(horizontal = ScreenPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chapterAt(chapters, page)?.first.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PageNumber(page)
                }
                Text(
                    snippet(match),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
