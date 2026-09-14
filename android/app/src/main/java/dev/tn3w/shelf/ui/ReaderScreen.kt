package dev.tn3w.shelf.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Block
import dev.tn3w.shelf.data.Chapter
import dev.tn3w.shelf.data.Document
import dev.tn3w.shelf.data.PagedDocument
import dev.tn3w.shelf.data.Progress
import dev.tn3w.shelf.data.Settings
import dev.tn3w.shelf.data.TextDocument
import dev.tn3w.shelf.data.decodeImage
import dev.tn3w.shelf.data.openDocument
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

@Composable
fun ReaderScreen(work: Int, navigator: Navigator) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val settings by app.library.settings.collectAsStateWithLifecycle(Settings())
    val saved by app.library.saved.collectAsStateWithLifecycle(emptyList())
    val title = saved.firstOrNull { it.work == work }?.title.orEmpty()
    val opened by
        produceState<Opened?>(null, work) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val progress = app.library.progress.first().getValue(work)
                            Opened.Ready(
                                progress,
                                openDocument(app.library.bookFile(progress.file)),
                            )
                        }
                        .getOrDefault(Opened.Failed)
                }
        }
    DisposableEffect(opened) {
        onDispose { (opened as? Opened.Ready)?.document?.close() }
    }
    fun changeFont(change: Float) = scope.launch {
        app.library.updateSettings {
            it.copy(fontScale = (it.fontScale + change).coerceIn(0.7f, 1.8f))
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (val current = opened) {
            null -> Loading()
            Opened.Failed ->
                Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                    BackBar(navigator::back)
                    EmptyState(
                        Icons.Outlined.ErrorOutline,
                        stringResource(R.string.open_failed),
                    )
                }
            is Opened.Ready ->
                when (val document = current.document) {
                    is TextDocument ->
                        TextReader(
                            ReaderState(work, title, navigator, ::changeFont),
                            document,
                            current.progress,
                            settings,
                        )
                    is PagedDocument ->
                        PagedReader(
                            ReaderState(work, title, navigator, null),
                            document,
                            current.progress,
                        )
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
    val alignment = if (block.centered) TextAlign.Center else TextAlign.Start
    withStyle(ParagraphStyle(textAlign = alignment)) {
        val start = length
        val heading =
            HEADING_SCALE[block.heading]?.let {
                SpanStyle(fontSize = (fontSize * it).sp, fontWeight = FontWeight.Bold)
            }
        if (heading != null) withStyle(heading) { append(block.text) }
        else append(block.text)
        block.spans.forEach { span ->
            val style =
                SpanStyle(
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
        val measured =
            layout.measurer.measure(
                text,
                layout.style,
                constraints = Constraints(maxWidth = layout.width),
            )
        var line = 0
        while (line < measured.lineCount) {
            val top = measured.getLineTop(line)
            var last = line
            while (
                last + 1 < measured.lineCount &&
                    measured.getLineBottom(last + 1) - top <= layout.height
            ) last++
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
    val style =
        TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = (19 * settings.fontScale).sp,
            lineHeight = 1.55.em,
            color = MaterialTheme.colorScheme.onSurface,
            lineBreak = LineBreak.Paragraph,
        )
    var position by remember { mutableStateOf(progress) }

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
    ) {
        val padding = with(LocalDensity.current) { PAGE_PADDING.roundToPx() }
        val width = constraints.maxWidth - 2 * padding
        val height = constraints.maxHeight - 2 * padding
        val pages by
            produceState<List<TextPage>?>(null, document, width, height, style) {
                value =
                    withContext(Dispatchers.Default) {
                        paginate(document, Layout(measurer, style, width, height))
                    }
            }
        val current = pages ?: return@BoxWithConstraints Loading()
        val initial =
            remember(current) {
                current
                    .indexOfLast {
                        it.section < position.section ||
                            (it.section == position.section &&
                                it.start <= position.offset)
                    }
                    .coerceAtLeast(0)
            }
        val chapters =
            document.chapters.map { chapter: Chapter ->
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
                        modifier = Modifier.fillMaxWidth().padding(PAGE_PADDING),
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
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
    ) {
        val width = constraints.maxWidth
        Pages(
            pager,
            state,
            emptyList(),
            save = { progress.copy(page = it, pages = count) },
        ) {
            val bitmap by
                produceState<Bitmap?>(null, it) {
                    value = withContext(Dispatchers.IO) { document.render(it, width) }
                }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val image = bitmap ?: return@Box CircularProgressIndicator()
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

    LaunchedEffect(pager.settledPage) {
        app.library.saveProgress(state.work, save(pager.settledPage))
        val newPages = pager.settledPage - furthest
        if (newPages > 0) app.library.addPages(newPages)
        furthest = maxOf(furthest, pager.settledPage)
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) {
            index ->
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val third = size.width / 3
                        when {
                            offset.x < third ->
                                scope.launch { pager.animateScrollToPage(index - 1) }
                            offset.x > 2 * third ->
                                scope.launch {
                                    pager.animateScrollToPage(index + 1)
                                }
                            else -> chrome = !chrome
                        }
                    }
                }
            ) {
                page(index)
            }
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
            ChapterSheet(chapters, chapter, onDismiss = { showChapters = false }) { target
                ->
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
            IconButton(onClick = { state.navigator.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            onChapters?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.AutoMirrored.Outlined.List,
                        stringResource(R.string.contents),
                    )
                }
            }
            state.onFontScale?.let { change ->
                IconButton(onClick = { change(-0.1f) }) {
                    Icon(
                        Icons.Outlined.TextDecrease,
                        stringResource(R.string.smaller_text),
                    )
                }
                IconButton(onClick = { change(0.1f) }) {
                    Icon(
                        Icons.Outlined.TextIncrease,
                        stringResource(R.string.larger_text),
                    )
                }
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
                val selected = chapter == current
                val color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(chapter.second) }
                        .padding(horizontal = ScreenPadding, vertical = 14.dp)
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
