package dev.tn3w.shelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*

private val ListCardWidth = 148.dp
private const val FAN_SIZE = 3

private data class FanCover(val shift: Float, val angle: Float, val scale: Float)

private val FAN = listOf(
    FanCover(0f, 0f, 1f),
    FanCover(-0.22f, -9f, 0.86f),
    FanCover(0.22f, 9f, 0.86f),
)

@Composable
fun ListScreen(initialName: String, navigator: Navigator) {
    val viewModel = libraryViewModel()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(initialName) }
    val sort = loadedSettings()?.librarySort ?: LibrarySort.Added
    val entries = saved?.filter { it.lists.hasList(name) }?.sorted(sort)
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        BackBar(navigator::back)
        BookGrid(
            entries?.map(Saved::toBook),
            "list",
            navigator::book,
            header = {
                fullWidth {
                    ListHeader(
                        name,
                        entries.orEmpty(),
                        onRename = { renaming = true },
                        onShare = {
                            context.shareText(listText(name, entries.orEmpty()), name)
                        },
                        onDelete = { deleting = true },
                    )
                }
            },
        )
    }
    if (renaming) {
        RenameListDialog(name, onDismiss = { renaming = false }) {
            viewModel.renameList(name, it)
            name = it
        }
    }
    if (deleting) {
        DeleteListDialog(name, onDismiss = { deleting = false }) {
            viewModel.renameList(name, null)
            navigator.back()
        }
    }
}

@Composable
private fun ListHeader(
    name: String,
    entries: List<Saved>,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ListCollage(entries, 220.dp, Modifier.padding(bottom = 12.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Hint(listSummary(entries))
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListAction(Icons.Outlined.Edit, R.string.rename_list, onRename)
            val shareable = entries.isNotEmpty()
            ListAction(Icons.Outlined.Share, R.string.share_list, onShare, shareable)
            ListAction(Icons.Outlined.Delete, R.string.delete_list, onDelete)
        }
    }
}

@Composable
private fun ListAction(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, stringResource(label))
    }
}

@Composable
private fun listSummary(entries: List<Saved>): String {
    val count = entries.size
    val books = pluralStringResource(R.plurals.books, count, count)
    val read = entries.count { it.shelf == Shelf.Read }
    if (read == 0 || read == count) return books
    return "$books · ${stringResource(R.string.read_of_list, read)}"
}

@Composable
fun ListCollage(entries: List<Saved>, width: Dp, modifier: Modifier = Modifier) {
    val covers = entries.take(FAN_SIZE).map(Saved::toBook)
    Box(
        modifier.width(width).height(width * 0.8f),
        contentAlignment = Alignment.Center,
    ) {
        if (covers.isEmpty()) {
            Icon(
                Icons.AutoMirrored.Outlined.Label,
                null,
                Modifier.size(width / 4),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        covers.withIndex().reversed().forEach { (index, book) ->
            val fan = FAN[index]
            BookCover(
                book,
                width * 0.46f,
                Modifier.offset(x = width * fan.shift)
                    .rotate(fan.angle)
                    .scale(fan.scale)
                    .shadow(if (index == 0) 6.dp else 2.dp, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
fun ListsRow(lists: List<BookList>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            stringResource(R.string.lists),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(lists, key = { it.name }) { list ->
                ListCard(list, Modifier.animateItem()) { onOpen(list.name) }
            }
        }
    }
}

@Composable
private fun ListCard(list: BookList, modifier: Modifier, onClick: () -> Unit) {
    val count = list.books.size
    Column(
        modifier.width(ListCardWidth)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            ListCollage(list.books, ListCardWidth, Modifier.padding(vertical = 12.dp))
        }
        Text(
            list.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Hint(
            pluralStringResource(R.plurals.books, count, count),
            Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListSheet(
    current: List<String>,
    allLists: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val shown = (allLists + current).distinctBy(String::lowercase)
    fun create() {
        val created = listName(name.text.toString())
        if (created.isEmpty()) return
        if (!current.hasList(created)) onToggle(created)
        name.clearText()
    }
    LaunchedEffect(Unit) { if (allLists.isEmpty()) focus.requestFocus() }
    TitledSheet(R.string.add_to_list, onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { list ->
                val selected = current.hasList(list)
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(list) },
                    label = { Text(list) },
                    leadingIcon = {
                        if (selected) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
        ListNameField(name, Modifier.focusRequester(focus), ::create)
    }
}

@Composable
private fun ListNameField(state: TextFieldState, modifier: Modifier, onDone: () -> Unit) {
    OutlinedTextField(
        state = state,
        label = { Text(stringResource(R.string.new_list)) },
        placeholder = { Text(stringResource(R.string.list_hint)) },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        onKeyboardAction = { onDone() },
        trailingIcon = {
            IconButton(onClick = onDone, enabled = state.text.isNotBlank()) {
                Icon(Icons.Outlined.Add, stringResource(R.string.add))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RenameListDialog(
    name: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val state = rememberTextFieldState(name)
    val renamed = listName(state.text.toString())
    fun confirm() {
        if (renamed.isEmpty()) return
        onRename(renamed)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_list)) },
        text = {
            OutlinedTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { confirm() },
            )
        },
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = renamed.isNotEmpty()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteListDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_list)) },
        text = { Text(stringResource(R.string.delete_list_confirm, name)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
