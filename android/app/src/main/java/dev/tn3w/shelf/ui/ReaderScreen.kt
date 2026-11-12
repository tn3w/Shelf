package dev.tn3w.shelf.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.view.Window
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import dev.tn3w.shelf.Narration
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.Source
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt
import android.provider.Settings.System as SystemSettings

private val PAGE_PADDING = 28.dp
private val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.25f, 4 to 1.1f)
private const val SEARCH_DELAY = 250L
private const val SPOKEN_ALPHA = 0.22f
private val SEARCH_MATCH = Color(0x669575CD)

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

class ReaderViewModel(app: ShelfApp, val work: Int) : ShelfViewModel(app) {
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
    var showListen by mutableStateOf(false)
    val narrator = app.narrator
    val audiobooks = app.audiobooks
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

    val highlights = library.highlights.map { it[work].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var editing by mutableStateOf<Editing?>(null)
    var removed by mutableStateOf<Highlight?>(null)

    fun addHighlight(highlight: Highlight) = detached {
        library.changeHighlights(work) { it + highlight }
    }

    fun updateHighlight(highlight: Highlight) = detached {
        library.changeHighlights(work) { marks ->
            marks.map { if (it.created == highlight.created) highlight else it }
        }
    }

    fun removeHighlight(highlight: Highlight) {
        editing = null
        removed = highlight
        detached {
            library.changeHighlights(work) { marks ->
                marks.filter { it.created != highlight.created }
            }
        }
    }

    fun restoreHighlight() {
        removed?.let(::addHighlight)
        removed = null
    }

    fun exportNotes(uri: Uri, format: NotesFormat) = detached {
        val saved = library.exportNotes(uri, format, work)
        val message = if (saved) R.string.notes_exported else R.string.export_failed
        withContext(Dispatchers.Main) { toast(message) }
    }

    fun copy(text: String) {
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.copied)
    }

    private fun toast(@StringRes message: Int) =
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()

    fun listen(title: String, build: suspend () -> Source) {
        showListen = true
        viewModelScope.launch {
            if (audiobooks.open(work, title)) return@launch audiobooks.play()
            val current = narrator.state.value
            if (current.work == work && (current.active || current.loading)) return@launch
            val source = withContext(Dispatchers.Default) { build() }
            if (source.passages.isEmpty()) {
                showListen = false
                return@launch toast(R.string.no_text)
            }
            narrator.open(work, title, source)
            narrator.play()
        }
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
    val author: String,
    val book: Book?,
    val navigator: Navigator,
    val onFontScale: ((Float) -> Unit)?,
    val volumeKeys: Boolean,
)

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
    val entry = saved.firstOrNull { it.work == work }
    val title = entry?.title.orEmpty()
    val author = entry?.author.orEmpty()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val book = remember(entry) { entry?.toBook() }
    val state = remember(viewModel, book, navigator, settings.volumeKeys) {
        ReaderState(
            viewModel,
            title,
            author,
            book,
            navigator,
            viewModel::changeFont,
            settings.volumeKeys,
        )
    }
    val page = Look.colors.page(settings.pageColor)
    Palette(page) {
        Box(Modifier.fillMaxSize().background(page.paper)) {
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
}

private fun Window.setBrightness(brightness: Float?) {
    attributes = attributes.apply {
        screenBrightness = brightness ?: BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun Context.systemBrightness(): Float {
    val key = SystemSettings.SCREEN_BRIGHTNESS
    val level = SystemSettings.getInt(contentResolver, key, MAX_LEVEL / 2)
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
    val colors = Look.colors
    Row(
        Modifier.align(Alignment.Center)
            .background(colors.ink, CircleShape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.BrightnessMedium, null, tint = colors.paper)
        Text(
            "${(brightness * 100).roundToInt()} %",
            color = colors.paper,
            style = Look.type.label,
        )
    }
}

@Composable
private fun OpenFailed(navigator: Navigator) {
    Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
        BackBar(navigator::back)
        EmptyState(Icons.Rounded.ErrorOutline, stringResource(R.string.open_failed))
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

private fun TextPage.marked(
    highlights: List<Highlight>,
    query: String,
    selected: TextRange?,
    selectionColor: Color,
): AnnotatedString {
    val matches = matchRanges(text.text, query)
    val marks = highlights.mapNotNull { highlight ->
        highlight.rangeOn(section, start, text.length)?.let { highlight to it }
    }
    if (matches.isEmpty() && marks.isEmpty() && selected == null) return text
    return buildAnnotatedString {
        append(text)
        marks.forEach { (highlight, range) ->
            val underline = TextDecoration.Underline.takeIf {
                highlight.note.isNotBlank()
            }
            val style =
                SpanStyle(background = highlight.color.wash, textDecoration = underline)
            addStyle(style, range.first, range.last + 1)
        }
        matches.forEach {
            addStyle(SpanStyle(background = SEARCH_MATCH), it.first, it.last + 1)
        }
        selected?.let { addStyle(SpanStyle(background = selectionColor), it.min, it.max) }
    }
}

private fun spokenRange(page: TextPage, narration: Narration, work: Int): IntRange? {
    if (!narration.active || narration.work != work) return null
    if (narration.section != page.section) return null
    val start = narration.offset - page.start
    val end = minOf(start + narration.text.length, page.text.length)
    return if (start in 0 until end) start until end else null
}

private fun AnnotatedString.withSpoken(range: IntRange?, color: Color): AnnotatedString {
    if (range == null) return this
    return buildAnnotatedString {
        append(this@withSpoken)
        addStyle(SpanStyle(background = color), range.first, range.last + 1)
    }
}

private fun TextPage.selectedText(range: TextRange) =
    paragraphsIn(text.text, text.paragraphStyles.map { it.start }, range.min, range.max)

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
    val style = readerStyle(settings, Look.colors.ink)
    val margin = settings.margin.dp
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
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
        val padding = PaddingValues(horizontal = side, vertical = margin)
        val origin = Offset(horizontal.toFloat(), vertical.toFloat())
        key(current) {
            val pager = rememberPagerState(initial) { current.size }
            val scope = rememberCoroutineScope()
            val annotator = remember(origin) { Annotator(current, chapters, origin) }
            val export = rememberNotesExport(state)
            LaunchedEffect(pager.currentPage) { annotator.selection = null }
            val narration by viewModel.narrator.state.collectAsStateWithLifecycle()
            val spokenPage = if (narration.playing && narration.work == viewModel.work) {
                current.indexAt(narration.section, narration.offset)
            } else {
                null
            }
            LaunchedEffect(spokenPage) {
                spokenPage?.let {
                    if (it !=
                        pager.currentPage
                    ) {
                        pager.animateScrollToPage(it)
                    }
                }
            }
            BackHandler(annotator.selection != null) { annotator.selection = null }
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
                notes = { onSelect -> NotesTab(state, current, export, onSelect) },
                onTap = { index, offset ->
                    annotator.tap(index, offset, highlights) {
                        viewModel.editing = Editing(it.created)
                    }
                },
                onListen = {
                    viewModel.listen(state.title) {
                        val passages = speechOf(document)
                        Source(passages, document.chapters, position) {
                                passage,
                                fraction,
                            ->
                            position.copy(
                                section = passage.section,
                                offset = passage.offset,
                                page = (fraction * position.pages).toInt(),
                            )
                        }
                    }
                },
            ) { index ->
                val page = current[index]
                if (page.image == null) {
                    AnnotatedPage(state, index, page, annotator, style, padding)
                } else {
                    PageImage(document, page.image)
                }
            }
            SelectionActions(state, annotator)
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
            HighlightEditor(state)
            UndoRemove(viewModel, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun UndoRemove(viewModel: ReaderViewModel, modifier: Modifier) {
    if (viewModel.removed == null) return
    Notice(
        stringResource(R.string.highlight_removed),
        stringResource(R.string.undo),
        modifier.navigationBarsPadding(),
        onAction = viewModel::restoreHighlight,
    ) { viewModel.removed = null }
}

@Stable
private class Annotator(
    val pages: List<TextPage>,
    val chapters: List<Pair<String, Int>>,
    val origin: Offset,
) {
    var selection by mutableStateOf<PageSelection?>(null)
    val layouts = mutableMapOf<Int, TextLayoutResult>()

    fun select(page: Int, range: TextRange, settled: Boolean) {
        selection =
            PageSelection(page, range, settled).takeUnless { settled && range.collapsed }
    }

    fun tap(
        index: Int,
        offset: Offset,
        highlights: List<Highlight>,
        onEdit: (Highlight) -> Unit,
    ): Boolean {
        if (selection != null) {
            selection = null
            return true
        }
        val char = layouts[index]?.charAt(offset - origin) ?: return false
        val page = pages[index]
        val hit = highlights.at(page.section, page.start + char) ?: return false
        onEdit(hit)
        return true
    }

    fun moveEdge(start: Boolean, char: Int, settled: Boolean) {
        val chosen = selection ?: return
        val (min, max) = chosen.range.min to chosen.range.max
        val range = if (start) {
            TextRange(minOf(char, max - 1), max)
        } else {
            TextRange(min, maxOf(char, min + 1))
        }
        selection = chosen.copy(range = range, settled = settled)
    }

    fun bounds(chosen: PageSelection) = layouts[chosen.page]
        ?.getPathForRange(chosen.range.min, chosen.range.max)
        ?.getBounds()
        ?.translate(origin)

    fun textOf(chosen: PageSelection) = pages[chosen.page].selectedText(chosen.range)

    fun highlightOf(
        chosen: PageSelection,
        color: HighlightColor = HighlightColor.Yellow,
    ): Highlight {
        val start = pages[chosen.page].start
        return Highlight(
            pages[chosen.page].section,
            start + chosen.range.min,
            start + chosen.range.max,
            textOf(chosen),
            color = color,
            chapter = chapterAt(chapters, chosen.page)?.first.orEmpty(),
            created = System.currentTimeMillis(),
        )
    }
}

@Composable
private fun AnnotatedPage(
    state: ReaderState,
    index: Int,
    page: TextPage,
    annotator: Annotator,
    style: TextStyle,
    padding: PaddingValues,
) {
    val viewModel = state.viewModel
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val selectionColor = LocalTextSelectionColors.current.backgroundColor
    val selected = annotator.selection?.takeIf { it.page == index }?.range
    val narration by viewModel.narrator.state.collectAsStateWithLifecycle()
    val spoken = spokenRange(page, narration, viewModel.work)
    val spokenColor = Look.colors.accent.copy(alpha = SPOKEN_ALPHA)
    val text = remember(page, highlights, hits.query, selected, selectionColor, spoken) {
        page.marked(highlights, hits.query, selected, selectionColor)
            .withSpoken(spoken, spokenColor)
    }
    Text(
        text,
        style = style,
        onTextLayout = { annotator.layouts[index] = it },
        modifier = Modifier.fillMaxWidth()
            .padding(padding)
            .wordSelection({ annotator.layouts[index] }) { range, settled ->
                if (annotator.selection == null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.chrome = false
                }
                annotator.select(index, range, settled)
            },
    )
}

@Composable
private fun SelectionActions(state: ReaderState, annotator: Annotator) {
    val chosen = annotator.selection ?: return
    val layout = annotator.layouts[chosen.page] ?: return
    val origin = annotator.origin
    listOf(true, false).forEach { start ->
        val edge = if (start) chosen.range.min else chosen.range.max
        SelectionHandle(layout.getCursorRect(edge).translate(origin)) { point, settled ->
            val char = layout.getOffsetForPosition(point - origin)
            annotator.moveEdge(start, char, settled)
        }
    }
    if (!chosen.settled) return
    val anchor = annotator.bounds(chosen) ?: return
    val viewModel = state.viewModel
    val context = LocalContext.current
    fun add(color: HighlightColor, writing: Boolean) {
        val highlight = annotator.highlightOf(chosen, color)
        viewModel.addHighlight(highlight)
        if (writing) viewModel.editing = Editing(highlight.created, writing = true)
        annotator.selection = null
    }
    SelectionBar(
        anchor,
        onHighlight = { add(it, writing = false) },
        onNote = { add(HighlightColor.Yellow, writing = true) },
        onCopy = {
            viewModel.copy(annotator.textOf(chosen))
            annotator.selection = null
        },
        onShare = {
            val quote =
                quoteText(annotator.highlightOf(chosen), state.title, state.author)
            context.shareText(quote, state.title)
            annotator.selection = null
        },
    )
}

@Composable
private fun rememberNotesExport(state: ReaderState): (NotesAction) -> Unit {
    val viewModel = state.viewModel
    val context = LocalContext.current
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    fun launcher(format: NotesFormat) = CreateDocument(format.mimeType)
    val markdown = rememberLauncherForActivityResult(launcher(NotesFormat.Markdown)) {
        it?.let { uri -> viewModel.exportNotes(uri, NotesFormat.Markdown) }
    }
    val readwise = rememberLauncherForActivityResult(launcher(NotesFormat.Readwise)) {
        it?.let { uri -> viewModel.exportNotes(uri, NotesFormat.Readwise) }
    }
    return { action ->
        when (action) {
            NotesAction.Share -> {
                val notes = BookNotes(state.title, state.author, highlights)
                context.shareText(notesMarkdown(listOf(notes)), state.title)
            }

            NotesAction.Markdown ->
                markdown.launch(notesFileName(state.title, NotesFormat.Markdown))

            NotesAction.Readwise ->
                readwise.launch(notesFileName(state.title, NotesFormat.Readwise))
        }
    }
}

@Composable
private fun NotesTab(
    state: ReaderState,
    pages: List<TextPage>,
    onExport: (NotesAction) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val viewModel = state.viewModel
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val placed = remember(highlights, pages) {
        highlights.inReadingOrder().map { it to pages.indexAt(it.section, it.start) }
    }
    NotesList(
        placed,
        onSelect,
        onEdit = {
            viewModel.showChapters = false
            viewModel.editing = Editing(it.created)
        },
        onExport = onExport,
    )
}

@Composable
private fun HighlightEditor(state: ReaderState) {
    val viewModel = state.viewModel
    val context = LocalContext.current
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val editing = viewModel.editing ?: return
    val highlight = highlights.firstOrNull { it.created == editing.created } ?: return
    HighlightSheet(
        highlight,
        editing.writing,
        onSave = viewModel::updateHighlight,
        onDelete = { viewModel.removeHighlight(highlight) },
        onShare = {
            context.shareText(quoteText(it, state.title, state.author), state.title)
        },
        onDismiss = { viewModel.editing = null },
    )
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
    val narration by state.viewModel.narrator.state.collectAsStateWithLifecycle()
    val spoken = narration.section.takeIf {
        narration.playing && narration.work == state.viewModel.work
    }
    LaunchedEffect(spoken) {
        spoken?.let { if (it != pager.currentPage) pager.animateScrollToPage(it) }
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
            onListen = (document as? PdfDocument)?.let { pdf ->
                {
                    state.viewModel.listen(state.title) {
                        val start = progress.copy(section = progress.page)
                        val pages = pdf.texts().orEmpty()
                        Source(speechOf(pages), emptyList(), start) { passage, _ ->
                            progress.copy(page = passage.section, pages = count)
                        }
                    }
                }
            },
        ) {
            val page by produceState<Result<Any>?>(null, it, width, height) {
                value = withContext(Dispatchers.IO) {
                    runSuspendCatching { document.page(it, width, height) }
                }
            }
            var failed by remember(page) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val image = page?.getOrNull()
                if (page == null) return@Box Ring(null, size = 32.dp)
                if (image == null || failed) {
                    return@Box EmptyState(
                        Icons.Rounded.BrokenImage, stringResource(R.string.page_failed),
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
    notes: (@Composable (onSelect: (Int) -> Unit) -> Unit)? = null,
    onTap: (Int, Offset) -> Boolean = { _, _ -> false },
    onListen: (() -> Unit)? = null,
    page: @Composable (Int) -> Unit,
) {
    val viewModel = state.viewModel
    val askNotifications = rememberNotificationRequest()
    val tapped by rememberUpdatedState(onTap)
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
                    detectTapGestures(onLongPress = {}) { offset ->
                        val third = size.width / 3
                        when {
                            tapped(index, offset) -> Unit
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
        Chrome(state.book) {
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
                        viewModel.toggleBookmark(onPage) {
                            marks.create(pager.currentPage)
                        }
                    },
                    onSearch = onSearch,
                    contents = contents,
                    onContents = { showChapters = true },
                    onListen = onListen?.let { start ->
                        {
                            askNotifications()
                            start()
                        }
                    },
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
                    notes,
                    onDismiss = { showChapters = false },
                    onRemove = viewModel::removeBookmark,
                ) { target ->
                    showChapters = false
                    chrome = false
                    scope.launch { pager.scrollToPage(target) }
                }
            }
        }
        if (viewModel.showListen) {
            ListenSheet(
                viewModel.work,
                state.title,
                viewModel.narrator,
                viewModel.audiobooks,
                onSettings = { state.navigator.settings(SettingsPage.Listening) },
            ) { viewModel.showListen = false }
        }
    }
}

/** Everything laid over the page takes the colours of the book's cover. */
@Composable
private fun Chrome(book: Book?, content: @Composable () -> Unit) =
    CoverTheme(coverTone(book), content)

@Composable
private fun Modifier.floating(shape: Shape): Modifier {
    val lift = if (Look.colors.plain) 0.dp else 14.dp
    return shadow(lift, shape).surface(shape).blockTouches()
}

@Composable
private fun rememberNotificationRequest(): () -> Unit {
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(RequestPermission()) {}
    return {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED
        if (needed) request.launch(POST_NOTIFICATIONS)
    }
}

@Composable
private fun BoxScope.BookmarkRibbon() {
    Icon(
        Icons.Rounded.Bookmark,
        stringResource(R.string.bookmarks),
        Modifier.align(Alignment.TopEnd).padding(end = 14.dp).size(26.dp),
        Look.colors.accent,
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
    onListen: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .floating(CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.AutoMirrored.Rounded.ArrowBack,
            stringResource(R.string.back),
            onClick = state.navigator::back,
        )
        state.book?.let { BookCover(it, 24.dp, lift = 0.dp) }
        Text(
            state.title,
            Modifier.weight(1f).padding(horizontal = 10.dp),
            style = Look.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        onSearch?.let {
            val search = stringResource(R.string.search_book)
            IconAction(Icons.Rounded.Search, search, onClick = it)
        }
        onListen?.let {
            val listen = stringResource(R.string.listen)
            IconAction(Icons.Rounded.Headphones, listen, onClick = it)
        }
        val mark = if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark
        IconAction(
            if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            stringResource(mark),
            tint = if (bookmarked) Look.colors.accent else Look.colors.ink,
            onClick = onBookmark,
        )
        val list = Icons.AutoMirrored.Rounded.Toc
        IconAction(list, stringResource(contents.label), onClick = onContents)
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
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .floating(Corners.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        chapter?.let { Eyebrow(it, color = Look.colors.accent) }
        Scrubber(
            value = dragging ?: pager.currentPage.toFloat(),
            onChange = { dragging = it },
            range = 0f..maxOf(1, pager.pageCount - 1).toFloat(),
            onFinished = {
                dragging?.let { onJump(it.roundToInt()) }
                dragging = null
            },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val smaller = stringResource(R.string.smaller_text)
            onFontScale?.let {
                IconAction(Icons.Rounded.TextDecrease, smaller) { it(-0.1f) }
            }
            val shown = (dragging?.roundToInt() ?: pager.currentPage) + 1
            Hint(
                stringResource(R.string.page_of, shown, pager.pageCount),
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            val larger = stringResource(R.string.larger_text)
            onFontScale?.let {
                IconAction(Icons.Rounded.TextIncrease, larger) { it(0.1f) }
            }
        }
    }
}

private enum class SheetTab(@StringRes val label: Int) {
    Contents(R.string.contents),
    Bookmarks(R.string.bookmarks),
    Notes(R.string.notes),
}

@Composable
private fun ContentsSheet(
    chapters: List<Pair<String, Int>>,
    current: Pair<String, Int>?,
    bookmarks: List<Pair<Bookmark, Int>>,
    pageCount: Int,
    notes: (@Composable (onSelect: (Int) -> Unit) -> Unit)?,
    onDismiss: () -> Unit,
    onRemove: (Bookmark) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val listed = chapters.filter { it.second >= 0 }
    val tabs = SheetTab.entries.filter {
        when (it) {
            SheetTab.Contents -> listed.isNotEmpty()
            SheetTab.Bookmarks -> true
            SheetTab.Notes -> notes != null
        }
    }
    var selected by rememberSaveable { mutableStateOf(tabs.first()) }
    val title = stringResource(selected.label).takeIf { tabs.size == 1 }
    Sheet(onDismiss, title, padded = false, scroll = false) {
        if (tabs.size > 1) {
            val side = Modifier.padding(horizontal = ScreenPadding)
            Segments(tabs, selected, side, { selected = it }) { stringResource(it.label) }
        }
        when (selected) {
            SheetTab.Contents -> ChapterList(listed, current, onSelect)

            SheetTab.Bookmarks ->
                BookmarkList(bookmarks, chapters, pageCount, onRemove, onSelect)

            SheetTab.Notes -> notes?.invoke(onSelect)
        }
    }
}

@Composable
private fun ChapterList(
    chapters: List<Pair<String, Int>>,
    current: Pair<String, Int>?,
    onSelect: (Int) -> Unit,
) {
    val start = chapters.indexOf(current).coerceAtLeast(0)
    LazyColumn(state = rememberLazyListState(start)) {
        items(chapters) { chapter ->
            val here = chapter == current
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(chapter.second) }
                    .padding(horizontal = ScreenPadding, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    chapter.first,
                    Modifier.weight(1f),
                    if (here) Look.colors.accent else Look.colors.ink,
                    style = if (here) Look.type.subhead else Look.type.body,
                )
                PageNumber(chapter.second)
            }
        }
    }
}

@Composable
fun PageNumber(index: Int) = Hint("${index + 1}", color = Look.colors.faint)

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
        return EmptyState(Icons.Rounded.BookmarkBorder, empty)
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
            .padding(start = ScreenPadding, end = 10.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bookmark, null, Modifier.size(20.dp), Look.colors.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = Look.type.subhead,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mark.excerpt.isNotBlank()) {
                Text(
                    mark.excerpt,
                    color = Look.colors.soft,
                    style = Look.type.note,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PageNumber(page)
        val remove = stringResource(R.string.remove_bookmark)
        IconAction(
            Icons.Rounded.Close,
            remove,
            tint = Look.colors.soft,
            onClick = onRemove,
        )
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
    Box(Modifier.fillMaxSize().background(Look.colors.paper).blockTouches()) {
        Column(Modifier.imePadding()) {
            Row(
                Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(R.string.back),
                    onClick = viewModel::closeSearch,
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
                if (searching) Meter(null)
            }
            when {
                needle.length < MIN_QUERY -> {
                    val hint = stringResource(R.string.search_book_hint)
                    EmptyState(Icons.Rounded.Search, hint)
                }

                hits.query == needle && hits.matches.isEmpty() -> {
                    val none = stringResource(R.string.no_matches)
                    EmptyState(Icons.Rounded.SearchOff, none)
                }

                else -> SearchResults(hits, chapters, pageOf, onSelect)
            }
        }
    }
}

private fun snippet(match: Match) = buildAnnotatedString {
    append(match.before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = SEARCH_MATCH)) {
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
    Eyebrow(
        if (count >= MAX_MATCHES) {
            stringResource(R.string.many_matches, MAX_MATCHES)
        } else {
            pluralStringResource(R.plurals.matches, count, count)
        },
        Modifier.padding(horizontal = ScreenPadding, vertical = 10.dp),
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
                    Eyebrow(
                        chapterAt(chapters, page)?.first.orEmpty(),
                        Modifier.weight(1f),
                        Look.colors.accent,
                    )
                    PageNumber(page)
                }
                Text(
                    snippet(match),
                    style = Look.type.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
