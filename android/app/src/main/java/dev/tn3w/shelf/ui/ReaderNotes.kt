package dev.tn3w.shelf.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlin.math.roundToInt

private const val WASH_ALPHA = 0.4f
private val BAR_GAP = 20.dp
private val HANDLE_TOUCH = 40.dp
private val HANDLE_DOT = 16.dp

val HighlightColor.swatch
    get() = when (this) {
        HighlightColor.Yellow -> Color(0xFFFFD54F)
        HighlightColor.Green -> Color(0xFF81C784)
        HighlightColor.Blue -> Color(0xFF64B5F6)
        HighlightColor.Pink -> Color(0xFFF48FB1)
    }

val HighlightColor.wash
    get() = swatch.copy(alpha = WASH_ALPHA)

@get:StringRes
private val HighlightColor.label
    get() = when (this) {
        HighlightColor.Yellow -> R.string.color_yellow
        HighlightColor.Green -> R.string.color_green
        HighlightColor.Blue -> R.string.color_blue
        HighlightColor.Pink -> R.string.color_pink
    }

data class PageSelection(
    val page: Int,
    val range: TextRange,
    val settled: Boolean = false,
)

data class Editing(val created: Long, val writing: Boolean = false)

enum class NotesAction(@StringRes val label: Int, val icon: ImageVector) {
    Share(R.string.share_markdown, Icons.Outlined.Share),
    Markdown(R.string.save_markdown, Icons.Outlined.Description),
    Readwise(R.string.save_readwise, Icons.Outlined.TableChart),
}

fun TextLayoutResult.charAt(position: Offset): Int? {
    if (position.y !in 0f..size.height.toFloat()) return null
    val line = getLineForVerticalPosition(position.y)
    if (position.x !in getLineLeft(line)..getLineRight(line)) return null
    return getOffsetForPosition(position)
}

fun Modifier.wordSelection(
    layout: () -> TextLayoutResult?,
    onSelect: (TextRange, settled: Boolean) -> Unit,
) = pointerInput(Unit) {
    var anchor = TextRange.Zero
    var selected = TextRange.Zero
    fun wordAt(position: Offset) =
        layout()?.let { it.getWordBoundary(it.getOffsetForPosition(position)) }
    detectDragGesturesAfterLongPress(
        onDragStart = { position ->
            anchor = wordAt(position) ?: TextRange.Zero
            selected = anchor
            onSelect(selected, false)
        },
        onDragEnd = { onSelect(selected, true) },
        onDragCancel = { onSelect(selected, true) },
    ) { change, _ ->
        change.consume()
        val word = wordAt(change.position) ?: return@detectDragGesturesAfterLongPress
        selected = TextRange(minOf(anchor.min, word.min), maxOf(anchor.max, word.max))
        onSelect(selected, false)
    }
}

private fun Modifier.floating(anchor: Rect, gap: Float) = layout { measurable, limits ->
    val bar = measurable.measure(limits.copy(minWidth = 0, minHeight = 0))
    val above = anchor.top - gap - bar.height
    val top = if (above >= 0) above else anchor.bottom + gap
    val y = top.coerceIn(0f, maxOf(0, limits.maxHeight - bar.height).toFloat())
    val x = (anchor.center.x - bar.width / 2)
        .coerceIn(0f, maxOf(0, limits.maxWidth - bar.width).toFloat())
    layout(limits.maxWidth, limits.maxHeight) {
        bar.place(x.roundToInt(), y.roundToInt())
    }
}

@Composable
fun SelectionHandle(cursor: Rect, onMove: (Offset, settled: Boolean) -> Unit) {
    val current by rememberUpdatedState(cursor)
    val move by rememberUpdatedState(onMove)
    val half = with(LocalDensity.current) { (HANDLE_TOUCH / 2).toPx() }
    Box(
        Modifier
            .offset {
                val left = cursor.center.x - half
                IntOffset(left.roundToInt(), cursor.bottom.roundToInt())
            }
            .size(HANDLE_TOUCH)
            .pointerInput(Unit) {
                var point = Offset.Zero
                detectDragGestures(
                    onDragStart = { point = current.center },
                    onDragEnd = { move(point, true) },
                    onDragCancel = { move(point, true) },
                ) { change, drag ->
                    change.consume()
                    point += drag
                    move(point, false)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.size(HANDLE_DOT)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun SelectionBar(
    anchor: Rect,
    onHighlight: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val gap = with(LocalDensity.current) { BAR_GAP.toPx() }
    Box(Modifier.fillMaxSize().floating(anchor, gap)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { SelectionButtons(onHighlight, onNote, onCopy, onShare) }
        }
    }
}

@Composable
private fun SelectionButtons(
    onHighlight: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    HighlightColor.entries.forEach { color ->
        ColorDot(color, selected = false, size = 40) { onHighlight(color) }
    }
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
    IconAction(Icons.Outlined.EditNote, stringResource(R.string.add_note), onNote)
    IconAction(Icons.Outlined.ContentCopy, stringResource(R.string.copy), onCopy)
    IconAction(Icons.Outlined.Share, stringResource(R.string.share), onShare)
}

@Composable
private fun ColorDot(
    color: HighlightColor,
    selected: Boolean,
    size: Int,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val label = stringResource(color.label)
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp).semantics { contentDescription = label },
    ) {
        Box(
            Modifier.size(24.dp)
                .clip(CircleShape)
                .background(color.swatch)
                .border(1.dp, outline.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightSheet(
    highlight: Highlight,
    writing: Boolean,
    onSave: (Highlight) -> Unit,
    onDelete: () -> Unit,
    onShare: (Highlight) -> Unit,
    onDismiss: () -> Unit,
) {
    var color by rememberSaveable(highlight.created) { mutableStateOf(highlight.color) }
    var note by rememberSaveable(highlight.created) { mutableStateOf(highlight.note) }
    val draft = highlight.copy(color = color, note = note.trim())
    val focus = remember { FocusRequester() }
    fun close() {
        if (draft != highlight) onSave(draft)
        onDismiss()
    }
    LaunchedEffect(Unit) { if (writing) focus.requestFocus() }
    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = ScreenPadding).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Quote(highlight.text, highlight.chapter, color)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HighlightColor.entries.forEach {
                    ColorDot(it, selected = it == color, size = 48) { color = it }
                }
            }
            OutlinedTextField(
                note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.note)) },
                placeholder = { Text(stringResource(R.string.note_hint)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            SheetActions(onDelete, { onShare(draft) }, ::close)
        }
    }
}

@Composable
private fun SheetActions(onDelete: () -> Unit, onShare: () -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.remove))
        }
        Spacer(Modifier.weight(1f))
        IconAction(Icons.Outlined.Share, stringResource(R.string.share), onShare)
        Button(onClick = onDone) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun Quote(text: String, chapter: String, color: HighlightColor) {
    Row(
        Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ColorStrip(color)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (chapter.isNotBlank()) {
                Text(
                    chapter,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ColorStrip(color: HighlightColor) {
    Box(
        Modifier.width(4.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(2.dp))
            .background(color.swatch),
    )
}

@Composable
fun NotesList(
    notes: List<Pair<Highlight, Int>>,
    onSelect: (Int) -> Unit,
    onEdit: (Highlight) -> Unit,
    onExport: (NotesAction) -> Unit,
) {
    if (notes.isEmpty()) {
        val empty = stringResource(R.string.no_highlights)
        return EmptyState(Icons.Outlined.BorderColor, empty)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.highlights, notes.size, notes.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        ExportMenu(onExport)
    }
    LazyColumn {
        items(notes, key = { it.first.created }) { (highlight, page) ->
            NoteRow(highlight, page, { onEdit(highlight) }) { onSelect(page) }
        }
    }
}

@Composable
private fun ExportMenu(onExport: (NotesAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(Icons.Outlined.IosShare, stringResource(R.string.export_notes)) {
            open = true
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            NotesAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        onExport(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun NoteRow(
    highlight: Highlight,
    page: Int,
    onEdit: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = ScreenPadding, end = 4.dp)
            .padding(vertical = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ColorStrip(highlight.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlight.chapter,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PageNumber(page)
            }
            Text(
                highlight.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (highlight.note.isNotBlank()) NoteLine(highlight.note)
        }
        val noted = highlight.note.isNotEmpty()
        val label = if (noted) R.string.edit_note else R.string.add_note
        IconAction(Icons.Outlined.EditNote, stringResource(label), onEdit)
    }
}

@Composable
private fun NoteLine(note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            Icons.AutoMirrored.Outlined.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun Context.shareText(text: String, subject: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}

fun notesFileName(title: String, format: NotesFormat) =
    "${title.ifBlank { "shelf" }.replace('/', '-')} notes.${format.extension}"
