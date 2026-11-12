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
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    Share(R.string.share_markdown, Icons.Rounded.IosShare),
    Markdown(R.string.save_markdown, Icons.Rounded.Description),
    Readwise(R.string.save_readwise, Icons.Rounded.TableChart),
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
                .background(Look.colors.accent),
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
    val lift = if (Look.colors.plain) 0.dp else 12.dp
    Box(Modifier.fillMaxSize().floating(anchor, gap)) {
        Row(
            Modifier.shadow(lift, CircleShape)
                .surface(CircleShape)
                .blockTouches()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { SelectionButtons(onHighlight, onNote, onCopy, onShare) }
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
    Box(
        Modifier.padding(horizontal = 4.dp)
            .size(1.dp, 24.dp)
            .background(Look.colors.line),
    )
    IconAction(
        Icons.Rounded.EditNote,
        stringResource(R.string.add_note),
        onClick = onNote,
    )
    IconAction(Icons.Rounded.ContentCopy, stringResource(R.string.copy), onClick = onCopy)
    IconAction(Icons.Rounded.IosShare, stringResource(R.string.share), onClick = onShare)
}

@Composable
private fun ColorDot(
    color: HighlightColor,
    selected: Boolean,
    size: Int,
    onClick: () -> Unit,
) {
    val label = stringResource(color.label)
    Box(
        Modifier.size(size.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(26.dp)
                .clip(CircleShape)
                .background(color.swatch)
                .border(1.dp, Look.colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    null,
                    Modifier.size(16.dp),
                    Color.Black.copy(0.7f),
                )
            }
        }
    }
}

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
    Sheet(::close) {
        Quote(highlight.text, highlight.chapter, color)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HighlightColor.entries.forEach {
                ColorDot(it, selected = it == color, size = 48) { color = it }
            }
        }
        Field(
            note,
            { note = it },
            Modifier.focusRequester(focus),
            label = stringResource(R.string.note),
            hint = stringResource(R.string.note_hint),
            lines = 3..8,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                stringResource(R.string.remove),
                icon = Icons.Rounded.DeleteOutline,
                kind = PillKind.Bare,
                danger = true,
                onClick = onDelete,
            )
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Rounded.IosShare, stringResource(R.string.share)) {
                onShare(draft)
            }
            Pill(stringResource(R.string.done), onClick = ::close)
        }
    }
}

@Composable
private fun Quote(text: String, chapter: String, color: HighlightColor) {
    Row(
        Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ColorStrip(color)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (chapter.isNotBlank()) Eyebrow(chapter, color = Look.colors.accent)
            Text(
                text,
                style = Look.type.heading,
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
        return EmptyState(Icons.Rounded.BorderColor, empty)
    }
    var exporting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(
            pluralStringResource(R.plurals.highlights, notes.size, notes.size),
            Modifier.weight(1f),
        )
        IconAction(Icons.Rounded.IosShare, stringResource(R.string.export_notes)) {
            exporting = true
        }
    }
    LazyColumn {
        items(notes, key = { it.first.created }) { (highlight, page) ->
            NoteRow(highlight, page, { onEdit(highlight) }) { onSelect(page) }
        }
    }
    if (!exporting) return
    OptionSheet(
        stringResource(R.string.export_notes),
        NotesAction.entries.map { stringResource(it.label) to it },
        null,
        { exporting = false },
        { it.icon },
        onExport,
    )
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
            .padding(start = ScreenPadding, end = 10.dp)
            .padding(vertical = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ColorStrip(highlight.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(highlight.chapter, Modifier.weight(1f), Look.colors.accent)
                PageNumber(page)
            }
            Text(
                highlight.text,
                style = Look.type.body,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (highlight.note.isNotBlank()) NoteLine(highlight.note)
        }
        val noted = highlight.note.isNotEmpty()
        val label = if (noted) R.string.edit_note else R.string.add_note
        IconAction(
            Icons.Rounded.EditNote, stringResource(label),
            tint = Look.colors.soft, onClick = onEdit,
        )
    }
}

@Composable
private fun NoteLine(note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            Icons.AutoMirrored.Rounded.Notes,
            null,
            Modifier.padding(top = 2.dp).size(16.dp),
            Look.colors.faint,
        )
        Text(
            note,
            color = Look.colors.soft,
            style = Look.type.note,
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
