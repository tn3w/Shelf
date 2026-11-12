package dev.tn3w.shelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tn3w.shelf.R
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields

private const val NOTICE_MILLIS = 4500L
private const val WEEK = 7

/** The one modal surface: every menu, form, picker and confirmation rises from below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    locked: Boolean = false,
    padded: Boolean = true,
    scroll: Boolean = true,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Look.colors
    val isLocked by rememberUpdatedState(locked)
    val confirm = remember {
        { value: SheetValue ->
            value != SheetValue.Hidden ||
                !isLocked
        }
    }
    val side = if (padded) ScreenPadding else 0.dp
    val scrolled = Modifier.verticalScroll(rememberScrollState())
    val scrolling = if (scroll) scrolled else Modifier
    ModalBottomSheet(
        onDismissRequest = { if (!isLocked) onDismiss() },
        sheetState = rememberModalBottomSheetState(true, confirm),
        shape = Corners.sheet,
        containerColor = colors.raised,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { Grip() },
    ) {
        Column(
            Modifier.imePadding().then(scrolling).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null) SheetHeader(title, subtitle, action)
            Column(
                Modifier.padding(horizontal = side),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Grip() = Box(
    Modifier.padding(top = 12.dp, bottom = 16.dp)
        .size(40.dp, 4.dp)
        .background(Look.colors.line, CircleShape),
)

@Composable
private fun SheetHeader(
    title: String,
    subtitle: String?,
    action: (@Composable () -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Look.type.title)
            subtitle?.let {
                Text(
                    it,
                    color = Look.colors.soft,
                    style = Look.type.note,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun SheetButtons(content: @Composable RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().padding(top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
)

@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    onConfirm: () -> Unit,
) = Sheet(onDismiss, title) {
    Text(message, color = Look.colors.soft, style = Look.type.body)
    SheetButtons {
        val cancel = stringResource(R.string.cancel)
        Pill(cancel, Modifier.weight(1f), kind = PillKind.Line, onClick = onDismiss)
        Pill(confirm, Modifier.weight(1f), danger = danger) {
            onDismiss()
            onConfirm()
        }
    }
}

@Composable
fun OptionRow(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = Look.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(22.dp), colors.soft) }
        Text(
            label,
            Modifier.weight(1f),
            if (selected) colors.accent else colors.ink,
            style = if (selected) Look.type.subhead else Look.type.body,
        )
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(22.dp), colors.accent)
    }
}

@Composable
fun <T> OptionSheet(
    title: String,
    options: List<Pair<String, T>>,
    current: T?,
    onDismiss: () -> Unit,
    icon: (T) -> ImageVector? = { null },
    onSelect: (T) -> Unit,
) = Sheet(onDismiss, title, padded = false) {
    Column {
        options.forEach { (label, value) ->
            OptionRow(label, value == current, icon(value)) {
                onDismiss()
                onSelect(value)
            }
        }
    }
}

/** Asks for one value. [onReset] replaces Cancel where the value has a default. */
@Composable
fun PromptSheet(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    text: String? = null,
    hint: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    clean: (String) -> String = { it },
    valid: (String) -> Boolean = { true },
    onReset: (() -> Unit)? = null,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val ready = valid(value)
    fun save() {
        if (!ready) return
        onDismiss()
        onSave(value)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Sheet(onDismiss, title) {
        text?.let { Text(it, color = Look.colors.soft, style = Look.type.note) }
        Field(
            value,
            { value = clean(it) },
            Modifier.focusRequester(focus),
            hint = hint,
            invalid = !ready && value.isNotEmpty(),
            keyboard = keyboard,
            onDone = ::save,
        )
        SheetButtons {
            val quiet = if (onReset == null) R.string.cancel else R.string.reset
            Pill(stringResource(quiet), Modifier.weight(1f), kind = PillKind.Line) {
                onDismiss()
                onReset?.invoke()
            }
            Pill(stringResource(R.string.save), Modifier.weight(1f), enabled = ready) {
                save()
            }
        }
    }
}

/** A message that leaves on its own, with one way back. */
@Composable
fun Notice(
    message: String,
    action: String,
    modifier: Modifier = Modifier,
    onAction: () -> Unit,
    onTimeout: () -> Unit,
) {
    val colors = Look.colors
    LaunchedEffect(message) {
        delay(NOTICE_MILLIS)
        onTimeout()
    }
    Row(
        modifier.padding(ScreenPadding)
            .clip(CircleShape)
            .background(colors.ink)
            .blockTouches()
            .padding(start = 20.dp, end = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = colors.paper, style = Look.type.label)
        CompositionLocalProvider(
            LocalColors provides colors.copy(accent = colors.paper),
        ) {
            Pill(action, kind = PillKind.Bare, onClick = onAction)
        }
    }
}

private fun weekdays(first: java.time.DayOfWeek) = List(WEEK) { first.plus(it.toLong()) }

@Composable
fun DateSheet(
    title: String,
    initial: LocalDate,
    range: ClosedRange<LocalDate>,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val chosen = initial.coerceIn(range)
    var shown by rememberSaveable { mutableStateOf(YearMonth.from(chosen).toString()) }
    val month = YearMonth.parse(shown)
    val days = remember(locale) { weekdays(WeekFields.of(locale).firstDayOfWeek) }
    val lead = days.indexOf(month.atDay(1).dayOfWeek)

    fun show(target: YearMonth) {
        val first = YearMonth.from(range.start.coerceAtLeast(LocalDate.of(1, 1, 1)))
        shown = target.coerceIn(first, YearMonth.from(range.endInclusive)).toString()
    }

    Sheet(onDismiss, title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val earlier = month.minusMonths(1)
            val later = month.plusMonths(1)
            val left = Icons.AutoMirrored.Rounded.KeyboardArrowLeft
            val right = Icons.AutoMirrored.Rounded.KeyboardArrowRight
            IconAction(Icons.Rounded.KeyboardDoubleArrowLeft, "${month.year - 1}") {
                show(month.minusYears(1))
            }
            IconAction(left, monthName(earlier)) { show(earlier) }
            Text(
                "${monthName(month)} ${month.year}",
                Modifier.weight(1f),
                style = Look.type.subhead,
                textAlign = TextAlign.Center,
            )
            IconAction(right, monthName(later)) { show(later) }
            IconAction(Icons.Rounded.KeyboardDoubleArrowRight, "${month.year + 1}") {
                show(month.plusYears(1))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach {
                Eyebrow(
                    it.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                    Modifier.weight(1f).wrapContentWidth(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (0 until lead + month.lengthOfMonth()).chunked(WEEK).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        val day = (cell - lead + 1).takeIf { it > 0 }?.let(month::atDay)
                        DayCell(day, day == chosen, day != null && day in range) {
                            onDismiss()
                            day?.let(onPick)
                        }
                    }
                    repeat(WEEK - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    day: LocalDate?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Look.colors
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        if (day == null) return@Box
        Box(
            Modifier.size(LocalTouchTarget.current)
                .alpha(if (enabled) 1f else 0.3f)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .clickable(enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                color = if (selected) colors.onAccent else colors.ink,
                style = Look.type.label,
            )
        }
    }
}
