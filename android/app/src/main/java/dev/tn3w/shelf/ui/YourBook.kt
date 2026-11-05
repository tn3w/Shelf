package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (reading) RatingRow(entry, onEdit)
            Writing.entries.filter { it.of(entry).isNotBlank() }.forEach {
                WrittenText(it, entry) { writing = it }
            }
            YourChips(entry, onOpenList, { writing = it }, { picking = it }) {
                addingList = true
            }
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dates.forEach { (date, day) ->
            val label = day?.let { "${stringResource(date.label)} · ${dateLabel(it)}" }
                ?: stringResource(date.empty)
            SmallChip(label, Icons.Outlined.Event) { onPick(date) }
        }
        entry.lists.forEach { name ->
            SmallChip(name, Icons.AutoMirrored.Outlined.Label) { onOpenList(name) }
        }
        Writing.entries.filter { it.of(entry).isBlank() }.forEach {
            SmallChip(stringResource(it.empty), Icons.Outlined.EditNote) { onWrite(it) }
        }
        val list = R.string.edit_lists.takeIf { entry.lists.isNotEmpty() }
            ?: R.string.add_to_list
        val icon = Icons.AutoMirrored.Outlined.PlaylistAdd
        SmallChip(stringResource(list), icon, onAddList)
    }
}

@Composable
private fun SmallChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
    )
}

@Composable
private fun StarRating(rating: Int, modifier: Modifier, onRate: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.offset(x = (-8).dp)) {
        (1..MAX_STARS).forEach { stars ->
            val filled = stars <= rating
            IconButton(onClick = { onRate(stars) }, Modifier.size(40.dp)) {
                Icon(
                    if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    stringResource(R.string.rate_stars, stars),
                    Modifier.size(28.dp),
                    tint = if (filled) colors.primary else colors.outline,
                )
            }
        }
    }
}

@Composable
private fun WrittenText(writing: Writing, saved: Saved, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Hint(stringResource(writing.title))
        Text(
            writing.of(saved),
            style = MaterialTheme.typography.bodyMedium,
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
    when (date) {
        ReadingDate.Started -> ReadingDatePicker(
            started ?: finished ?: today,
            LocalDate.MIN..(finished ?: today),
            onDismiss,
        ) { day -> onEdit { it.copy(started = millisOf(day)) } }

        ReadingDate.Finished -> ReadingDatePicker(
            finished ?: today,
            (started ?: LocalDate.MIN)..today,
            onDismiss,
        ) { day -> onEdit { it.copy(finished = millisOf(day)) } }
    }
}

private fun utcMillis(day: LocalDate) = millisOf(day, ZoneOffset.UTC)

private fun utcDay(millis: Long) =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingDatePicker(
    initial: LocalDate,
    range: ClosedRange<LocalDate>,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(initial.coerceIn(range)),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                utcDay(utcTimeMillis) in range

            override fun isSelectableYear(year: Int) =
                year in range.start.year..range.endInclusive.year
        },
    )
    fun confirm() {
        state.selectedDateMillis?.let { onPick(utcDay(it)) }
        onDismiss()
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = state.selectedDateMillis != null) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) { DatePicker(state) }
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
    TitledSheet(writing.title, ::close) {
        OutlinedTextField(
            draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(writing.hint)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            minLines = 5,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Lock,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Hint(stringResource(R.string.private_note), Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            Button(onClick = ::close) { Text(stringResource(R.string.done)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitledSheet(
    @StringRes title: Int,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.imePadding()
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
