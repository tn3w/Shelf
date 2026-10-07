package dev.tn3w.shelf.ui

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val PAGE_PADDING = 28.dp
private val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.25f, 4 to 1.1f)

private data class TextPage(
    val section: Int,
    val start: Int,
    val text: AnnotatedString = AnnotatedString(""),
    val image: ByteArray? = null,
)

private sealed interface Opened {
    data class Ready(val progress: Progress, val document: Document) : Opened

    data object Failed : Opened
}

private data class ReaderState(
    val work: Int,
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

@Composable
fun ReaderScreen(work: Int, navigator: Navigator) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val settings by app.library.settings.collectAsStateWithLifecycle(Settings())
    val saved by app.library.saved.collectAsStateWithLifecycle(emptyList())
    val title = saved.firstOrNull { it.work == work }?.title.orEmpty()
    val opened by produceState<Opened?>(null, work) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val progress = app.library.progress.first().getValue(work)
                Opened.Ready(
                    progress, openDocument(app.library.bookFile(progress.file)),
                )
            }
                .getOrDefault(Opened.Failed)
        }
    }
    val document = (opened as? Opened.Ready)?.document
    DisposableEffect(document) {
        onDispose {
            if (document != null) app.scope.launch(Dispatchers.IO) { document.close() }
        }
    }
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    fun changeFont(change: Float) = scope.launch {
        app.library.updateSettings {
            it.copy(fontScale = (it.fontScale + change).coerceIn(0.7f, 1.8f))
        }
    }

    val state = ReaderState(work, title, navigator, ::changeFont, settings.volumeKeys)
    val palette = pagePalette(settings)
    Surface(
        Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text,
    ) {
        when (val current = opened) {
            null -> Loading()

            Opened.Failed ->
                Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                    BackBar(navigator::back)
                    EmptyState(
                        Icons.Outlined.ErrorOutline, stringResource(R.string.open_failed),
                    )
                }

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

private class Layout(
    val measurer: TextMeasurer,
    val style: TextStyle,
    val width: Int,
    val height: Int,
)

private fun textPages(section: Int, blocks: List<Block>, offset: Int, layout: Layout) =
    buildList {
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

private fun paginate(document: TextDocument, layout: Layout): List<TextPage> =
    document.sections.flatMapIndexed { section, blocks ->
        val pages = mutableListOf<TextPage>()
        var offset = 0
        var run = mutableListOf<Block>()
        fun flush() {
            if (run.isEmpty()) return
            pages += textPages(section, run, offset, layout)
            offset += run.sumOf { it.text.length + 1 }
            run = mutableListOf()
        }
        for (block in blocks) {
            if (block.image == null) {
                run += block
                continue
            }
            flush()
            pages += TextPage(section, offset, image = block.image)
            offset++
        }
        flush()
        pages
    }

@Composable
private fun TextReader(
    state: ReaderState,
    document: TextDocument,
    progress: Progress,
    settings: Settings,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val style = readerStyle(settings, pagePalette(settings).text)
    val margin = settings.margin.dp
    var position by remember { mutableStateOf(progress) }

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val padding = with(LocalDensity.current) { margin.roundToPx() }
        val width = constraints.maxWidth - 2 * padding
        val height = constraints.maxHeight - 2 * padding
        val pages by produceState<List<TextPage>?>(null, document, width, height, style) {
            value = withContext(Dispatchers.Default) {
                paginate(document, Layout(measurer, style, width, height))
            }
        }
        val current = pages ?: return@BoxWithConstraints Loading()
        val initial = remember(current) {
            val (_, section, offset) = position
            current
                .indexOfLast {
                    it.section < section ||
                        (it.section == section && it.start <= offset)
                }
                .coerceAtLeast(0)
        }
        val chapters = document.chapters.map { chapter ->
            chapter.title to current.indexOfFirst { it.section >= chapter.section }
        }
        key(current) {
            val pager = rememberPagerState(initial) { current.size }
            Pages(
                pager,
                state,
                chapters,
                save = { index ->
                    val page = current[index]
                    Progress(progress.file, page.section, page.start, index, current.size)
                        .also { position = it }
                },
            ) { index ->
                val page = current[index]
                if (page.image == null) {
                    Text(
                        page.text,
                        style = style,
                        modifier = Modifier.fillMaxWidth().padding(margin),
                    )
                } else {
                    PageImage(page.image)
                }
            }
        }
    }
}

@Composable
private fun PageImage(bytes: ByteArray) {
    val bitmap = remember(bytes) { decodeImage(bytes)?.asImageBitmap() } ?: return
    Image(
        bitmap,
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
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        Pages(
            pager,
            state.copy(onFontScale = null),
            emptyList(),
            save = { progress.copy(page = it, pages = count) },
        ) {
            val rendered by produceState<Result<Bitmap>?>(null, it) {
                value = withContext(Dispatchers.IO) {
                    runCatching { document.render(it, width, height) }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val result = rendered ?: return@Box CircularProgressIndicator()
                val image = result.getOrNull() ?: return@Box EmptyState(
                    Icons.Outlined.BrokenImage, stringResource(R.string.page_failed),
                )
                Image(
                    image.asImageBitmap(),
                    contentDescription = stringResource(R.string.page_of, it + 1, count),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun Pages(
    pager: PagerState,
    state: ReaderState,
    chapters: List<Pair<String, Int>>,
    save: (Int) -> Progress,
    page: @Composable (Int) -> Unit,
) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var furthest by remember { mutableIntStateOf(pager.currentPage) }
    val chapter = chapters.lastOrNull { it.second in 0..pager.currentPage }
    val focus = remember { FocusRequester() }
    fun turn(page: Int) = scope.launch { pager.animateScrollToPage(page) }

    LaunchedEffect(chrome, showChapters) {
        if (!chrome && !showChapters) focus.requestFocus()
    }

    LaunchedEffect(pager.settledPage) {
        app.library.saveProgress(state.work, save(pager.settledPage))
        val newPages = pager.settledPage - furthest
        if (newPages > 0) app.library.addPages(newPages)
        furthest = maxOf(furthest, pager.settledPage)
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
            ) { page(index) }
        }
        AnimatedVisibility(
            chrome,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val onChapters = if (chapters.isEmpty()) null else ({ showChapters = true })
            ReaderTopBar(state, onChapters)
        }
        AnimatedVisibility(
            chrome,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(pager, chapter?.first) {
                scope.launch { pager.scrollToPage(it) }
            }
        }
        if (showChapters) {
            ChapterSheet(
                chapters, chapter, onDismiss = { showChapters = false },
            ) { target ->
                showChapters = false
                chrome = false
                scope.launch { pager.scrollToPage(target) }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(state: ReaderState, onChapters: (() -> Unit)?) {
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
                modifier = Modifier.weight(1f),
            )
            onChapters?.let {
                val contents = Icons.AutoMirrored.Outlined.List
                IconAction(contents, stringResource(R.string.contents), it)
            }
            state.onFontScale?.let { change ->
                IconAction(
                    Icons.Outlined.TextDecrease, stringResource(R.string.smaller_text),
                ) { change(-0.1f) }
                IconAction(
                    Icons.Outlined.TextIncrease, stringResource(R.string.larger_text),
                ) { change(0.1f) }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(pager: PagerState, chapter: String?, onJump: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
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
            val shown = (dragging?.roundToInt() ?: pager.currentPage) + 1
            Text(
                stringResource(R.string.page_of, shown, pager.pageCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    chapters: List<Pair<String, Int>>,
    current: Pair<String, Int>?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.contents),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
        )
        LazyColumn {
            items(chapters.filter { it.second >= 0 }) { chapter ->
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
                    Text(
                        "${chapter.second + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
