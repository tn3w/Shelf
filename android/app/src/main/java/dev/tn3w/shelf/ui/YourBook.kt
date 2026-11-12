package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import java.time.LocalDate

private const val MAX_STARS = 5

private enum class Writing(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    @StringRes val empty: Int,
) {
    Review(R.string.review, R.string.review_hint, R.string.write_review),
    Notes(R.string.notes, R.string.book_notes_hint, R.string.add_notes),
    ;

    fun of(saved: Saved) = if (this == Review) saved.review else saved.notes

    fun into(saved: Saved, text: String) =
        if (this == Review) saved.copy(review = text) else saved.copy(notes = text)
}

private enum class ReadingDate(@StringRes val label: Int, @StringRes val empty: Int) {
    Started(R.string.started_on, R.string.add_started),
    Finished(R.string.finished_on, R.string.add_finished),
}

@Composable
fun YourBookCard(
    entry: Saved,
    allLists: List<String>,
    onOpenList: (String) -> Unit,
    onEdit: ((Saved) -> Saved) -> Unit,
) {
    var writing by rememberSaveable { mutableStateOf<Writing?>(null) }
    var picking by rememberSaveable { mutableStateOf<ReadingDate?>(null) }
    var addingList by rememberSaveable { mutableStateOf(false) }
    val reading = entry.shelf != Shelf.Want

    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .surface()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (reading) RatingRow(entry, onEdit)
        Writing.entries.filter { it.of(entry).isNotBlank() }.forEach {
            WrittenText(it, entry) { writing = it }
        }
        YourChips(entry, onOpenList, { writing = it }, { picking = it }) {
            addingList = true
        }
    }
    writing?.let { open ->
        WritingSheet(
            open,
            open.of(entry),
            onSave = { text -> onEdit { open.into(it, text) } },
            onDismiss = { writing = null },
        )
    }
    picking?.let { DatePicking(it, entry, onEdit) { picking = null } }
    if (addingList) {
        ListSheet(
            entry.lists,
            allLists,
            onToggle = { name ->
                onEdit { it.copy(lists = toggledList(it.lists, name)) }
            },
            onDismiss = { addingList = false },
        )
    }
}

@Composable
private fun RatingRow(entry: Saved, onEdit: ((Saved) -> Saved) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StarRating(entry.rating, Modifier.weight(1f)) { stars ->
            onEdit { it.copy(rating = if (it.rating == stars) 0 else stars) }
        }
        entry.daysToFinish()?.let { Hint(stringResource(R.string.read_in, days(it))) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YourChips(
    entry: Saved,
    onOpenList: (String) -> Unit,
    onWrite: (Writing) -> Unit,
    onPick: (ReadingDate) -> Unit,
    onAddList: () -> Unit,
) {
    val dates = when (entry.shelf) {
        Shelf.Want -> emptyMap()

        Shelf.Reading -> mapOf(ReadingDate.Started to entry.startedOn())

        Shelf.Read -> mapOf(
            ReadingDate.Started to entry.startedOn(),
            ReadingDate.Finished to entry.finishedOn(),
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dates.forEach { (date, day) ->
            val label = day?.let { "${stringResource(date.label)} · ${dateLabel(it)}" }
                ?: stringResource(date.empty)
            Tag(label, icon = Icons.Rounded.Event) { onPick(date) }
        }
        entry.lists.forEach { name ->
            Tag(name, icon = Icons.AutoMirrored.Rounded.Label) { onOpenList(name) }
        }
        Writing.entries.filter { it.of(entry).isBlank() }.forEach {
            Tag(stringResource(it.empty), icon = Icons.Rounded.EditNote) { onWrite(it) }
        }
        val list = R.string.edit_lists.takeIf { entry.lists.isNotEmpty() }
            ?: R.string.add_to_list
        Tag(stringResource(list), icon = Icons.AutoMirrored.Rounded.PlaylistAdd) {
            onAddList()
        }
    }
}

@Composable
private fun StarRating(rating: Int, modifier: Modifier, onRate: (Int) -> Unit) {
    val colors = Look.colors
    Row(modifier.offset(x = (-10).dp)) {
        (1..MAX_STARS).forEach { stars ->
            val filled = stars <= rating
            IconAction(
                if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                stringResource(R.string.rate_stars, stars),
                tint = if (filled) colors.accent else colors.faint,
            ) { onRate(stars) }
        }
    }
}

@Composable
private fun WrittenText(writing: Writing, saved: Saved, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow(stringResource(writing.title))
        Text(
            writing.of(saved),
            style = Look.type.body,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DatePicking(
    date: ReadingDate,
    entry: Saved,
    onEdit: ((Saved) -> Saved) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val started = entry.startedOn()
    val finished = entry.finishedOn()
    val title = stringResource(date.label)
    when (date) {
        ReadingDate.Started -> DateSheet(
            title,
            started ?: finished ?: today,
            LocalDate.of(1900, 1, 1)..(finished ?: today),
            onDismiss,
        ) { day -> onEdit { it.copy(started = millisOf(day)) } }

        ReadingDate.Finished -> DateSheet(
            title,
            finished ?: today,
            (started ?: LocalDate.of(1900, 1, 1))..today,
            onDismiss,
        ) { day -> onEdit { it.copy(finished = millisOf(day)) } }
    }
}

@Composable
private fun WritingSheet(
    writing: Writing,
    text: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(writing) { mutableStateOf(text) }
    val focus = remember { FocusRequester() }
    fun close() {
        if (draft.trim() != text) onSave(draft.trim())
        onDismiss()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Sheet(::close, stringResource(writing.title)) {
        Field(
            draft,
            { draft = it },
            Modifier.focusRequester(focus),
            hint = stringResource(writing.hint),
            lines = 5..12,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), Look.colors.faint)
            Hint(
                stringResource(R.string.private_note),
                Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Pill(stringResource(R.string.done), onClick = ::close)
        }
    }
}
