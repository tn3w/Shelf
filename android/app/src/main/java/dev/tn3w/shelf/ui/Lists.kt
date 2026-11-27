package dev.tn3w.shelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
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
import androidx.compose.foundation.lazy.grid.items as gridItems

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
    val readable by viewModel.readable.collectAsStateWithLifecycle()
    var onlyReadable by rememberSaveable { mutableStateOf(false) }
    val listed = saved?.filter { it.lists.hasList(name) }?.sorted(sort)
    val entries = listed?.filter { !onlyReadable || it.work in readable.orEmpty() }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val soft = Look.colors.soft

    CoverTheme(coverTone(entries?.firstOrNull()?.toBook())) {
        Column(Modifier.glow().padding(top = statusBarHeight())) {
            BackBar(navigator::back) {
                IconAction(
                    Icons.Rounded.Edit, stringResource(R.string.rename_list), tint = soft,
                ) { renaming = true }
                IconAction(
                    Icons.Rounded.IosShare,
                    stringResource(R.string.share_list),
                    tint = soft,
                    enabled = !entries.isNullOrEmpty(),
                ) { context.shareText(listText(name, entries.orEmpty()), name) }
                IconAction(
                    Icons.Rounded.DeleteOutline,
                    stringResource(R.string.delete_list),
                    tint = soft,
                ) { deleting = true }
            }
            BookGrid(
                entries?.map(Saved::toBook),
                "list",
                navigator::book,
                header = {
                    fullWidth {
                        Column {
                            ListHeader(name, listed.orEmpty())
                            FilterRow { ReadableTag(onlyReadable) { onlyReadable = it } }
                        }
                    }
                },
            )
        }
    }
    if (renaming) {
        PromptSheet(
            stringResource(R.string.rename_list),
            name,
            onDismiss = { renaming = false },
            valid = { listName(it).isNotEmpty() },
        ) {
            val renamed = listName(it)
            viewModel.renameList(name, renamed)
            name = renamed
        }
    }
    if (deleting) {
        ConfirmSheet(
            stringResource(R.string.delete_list),
            stringResource(R.string.delete_list_confirm, name),
            stringResource(R.string.remove),
            onDismiss = { deleting = false },
        ) {
            viewModel.renameList(name, null)
            navigator.back()
        }
    }
}

@Composable
private fun ListHeader(name: String, entries: List<Saved>) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ListCollage(entries, 220.dp, Modifier.padding(bottom = 12.dp))
        Text(name, style = Look.type.display, textAlign = TextAlign.Center)
        Hint(listSummary(entries))
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
    Box(modifier.width(width).height(width * 0.8f), contentAlignment = Alignment.Center) {
        if (covers.isEmpty()) {
            Icon(
                Icons.AutoMirrored.Rounded.Label,
                null,
                Modifier.size(width / 4),
                Look.colors.faint,
            )
        }
        covers.withIndex().reversed().forEach { (index, book) ->
            val fan = FAN[index]
            BookCover(
                book,
                width * 0.46f,
                Modifier.offset(x = width * fan.shift).rotate(fan.angle).scale(fan.scale),
                lift = if (index == 0) 10.dp else 4.dp,
            )
        }
    }
}

@Composable
fun ListsRow(lists: List<BookList>, onAll: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onAll)
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(stringResource(R.string.lists), Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                stringResource(R.string.all_lists),
                Modifier.size(20.dp),
                Look.colors.soft,
            )
        }
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
fun ListsScreen(navigator: Navigator) {
    val saved by libraryViewModel().saved.collectAsStateWithLifecycle()
    val lists = saved.orEmpty().bookLists()
    Column(Modifier.padding(top = statusBarHeight())) {
        BackBar(navigator::back)
        LargeTitle(stringResource(R.string.lists))
        if (saved != null && lists.isEmpty()) {
            val empty = stringResource(R.string.no_lists)
            EmptyState(Icons.AutoMirrored.Rounded.Label, empty)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(ListCardWidth),
            contentPadding = PaddingValues(ScreenPadding, 8.dp, ScreenPadding, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItems(lists, key = { it.name }) { list ->
                ListCard(list, Modifier.animateItem()) { navigator.list(list.name) }
            }
        }
    }
}

@Composable
private fun ListCard(list: BookList, modifier: Modifier, onClick: () -> Unit) {
    val count = list.books.size
    Column(
        modifier.width(ListCardWidth).clip(Corners.card).clickable(onClick = onClick),
    ) {
        Box(Modifier.surface()) {
            ListCollage(list.books, ListCardWidth, Modifier.padding(vertical = 12.dp))
        }
        Text(
            list.name,
            Modifier.padding(start = 6.dp, top = 10.dp),
            style = Look.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Hint(
            pluralStringResource(R.plurals.books, count, count),
            Modifier.padding(start = 6.dp, bottom = 6.dp),
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
    var name by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shown = (allLists + current).distinctBy(String::lowercase)
    fun create() {
        val created = listName(name)
        if (created.isEmpty()) return
        if (!current.hasList(created)) onToggle(created)
        name = ""
    }
    LaunchedEffect(Unit) { if (allLists.isEmpty()) focus.requestFocus() }
    Sheet(onDismiss, stringResource(R.string.add_to_list)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEach { list ->
                val selected = current.hasList(list)
                val icon = Icons.Rounded.Check.takeIf { selected }
                Tag(list, selected = selected, icon = icon) { onToggle(list) }
            }
        }
        Field(
            name,
            { name = it },
            Modifier.focusRequester(focus),
            label = stringResource(R.string.new_list),
            hint = stringResource(R.string.list_hint),
            keyboard = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            onDone = ::create,
            trailing = {
                IconAction(
                    Icons.Rounded.Add, stringResource(R.string.add),
                    enabled = name.isNotBlank(), onClick = ::create,
                )
            },
        )
    }
}
