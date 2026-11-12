package dev.tn3w.shelf.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val PRESSED_ALPHA = 0.14f
private const val DISABLED_ALPHA = 0.4f
private val ThumbRadius = 9.dp

object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource)

    override fun equals(other: Any?) = other === this

    override fun hashCode() = -1
}

private class PressNode(private val source: InteractionSource) :
    Modifier.Node(),
    DrawModifierNode {
    private val pressed = Animatable(0f)
    private var focused by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press ->
                        launch { pressed.animateTo(1f, tween(Motion.QUICK / 2)) }

                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        launch { pressed.animateTo(0f, tween(Motion.CALM)) }

                    is FocusInteraction.Focus -> focused = true

                    is FocusInteraction.Unfocus -> focused = false
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val veil = Color.Gray.copy(alpha = PRESSED_ALPHA * pressed.value)
        if (pressed.value > 0f) drawRect(veil)
        if (focused) drawRect(Color.Gray, style = Stroke(2.dp.toPx()))
    }
}

fun Modifier.blockTouches() = pointerInput(Unit) {}

/** A filled shape in [color]; outlined so it also reads on paper-white and e-ink. */
@Composable
fun Modifier.surface(
    shape: Shape = Corners.card,
    color: Color = Look.colors.raised,
): Modifier = clip(shape).background(color).border(1.dp, Look.colors.line, shape)

@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Look.colors.soft,
) = Text(text.uppercase(), modifier, color, style = Look.type.eyebrow, maxLines = 1)

@Composable
fun Rule(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(Look.colors.line))

@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(LocalTouchTarget.current)
            .clip(CircleShape)
            .clickable(enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(22.dp), tint) }
}

enum class PillKind { Solid, Soft, Line, Bare }

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: PillKind = PillKind.Solid,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Look.colors
    val tone = if (danger) colors.danger else colors.accent
    val fill = when (kind) {
        PillKind.Solid -> tone
        PillKind.Soft -> if (colors.plain) colors.paper else colors.wash
        else -> Color.Transparent
    }
    val content = when (kind) {
        PillKind.Solid -> if (danger) colors.paper else colors.onAccent
        PillKind.Soft -> colors.ink
        PillKind.Line -> if (danger) colors.danger else colors.ink
        PillKind.Bare -> tone
    }
    val outlined = kind == PillKind.Line || (kind == PillKind.Soft && colors.plain)
    val border = if (outlined) colors.line else Color.Transparent
    val side = if (kind == PillKind.Bare) 12.dp else 20.dp
    Row(
        modifier.heightIn(min = maxOf(LocalTouchTarget.current, 46.dp))
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, border, CircleShape)
            .clickable(enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = side),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), content) }
        Text(
            text,
            color = content,
            style = Look.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small rounded label: a filter when [selected] is used, a link otherwise. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = Look.colors
    val fill = if (selected) colors.ink else Color.Transparent
    val content = if (selected) colors.paper else colors.soft
    Row(
        modifier.heightIn(min = maxOf(LocalTouchTarget.current - 8.dp, 36.dp))
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, if (selected) fill else colors.line, CircleShape)
            .selectable(selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(16.dp), content) }
        Text(text, color = content, style = Look.type.label, maxLines = 1)
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val colors = Look.colors
    val motion: AnimationSpec<Float> =
        if (LocalReducedMotion.current) snap() else tween(Motion.QUICK)
    val position by animateFloatAsState(if (checked) 1f else 0f, motion)
    val track = lerp(colors.sunken, colors.accent, position)
    val thumb = lerp(colors.soft, colors.onAccent, position)
    val toggle = onChange?.let {
        Modifier.toggleable(checked, enabled, Role.Switch, onValueChange = it)
    }
    Box(
        Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(50.dp, 30.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, lerp(colors.line, colors.accent, position), CircleShape)
            .then(toggle ?: Modifier)
            .padding(4.dp),
    ) {
        Box(
            Modifier.size(22.dp)
                .offset { IntOffset((position * 20.dp.toPx()).roundToInt(), 0) }
                .background(thumb, CircleShape),
        )
    }
}

@Composable
fun Check(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = Look.colors
    val fill = if (checked) colors.accent else Color.Transparent
    Box(
        modifier.size(24.dp)
            .background(fill, CircleShape)
            .border(1.5.dp, if (checked) fill else colors.faint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Rounded.Check,
                null,
                Modifier.size(16.dp),
                colors.onAccent,
            )
        }
    }
}

@Composable
fun <T> Segments(
    options: List<T>,
    selected: T?,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    val colors = Look.colors
    Row(
        modifier.fillMaxWidth()
            .clip(CircleShape)
            .background(colors.sunken)
            .border(
                1.dp,
                if (colors.plain) colors.line else Color.Transparent,
                CircleShape,
            )
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val chosen = option == selected
            val fill = when {
                !chosen -> Color.Transparent
                colors.plain -> colors.ink
                else -> colors.raised
            }
            val content = when {
                !chosen -> colors.soft
                colors.plain -> colors.paper
                else -> colors.ink
            }
            Box(
                Modifier.weight(1f)
                    .heightIn(min = LocalTouchTarget.current - 4.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(chosen, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = content,
                    style = Look.type.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String = "",
    error: String? = null,
    invalid: Boolean = error != null,
    lines: IntRange = 1..1,
    mono: Boolean = false,
    secret: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onDone: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Look.colors
    val code = Look.type.note.copy(fontFamily = FontFamily.Monospace)
    val base = if (mono) code else Look.type.body
    val style = base.copy(color = colors.ink)
    val single = lines.last == 1
    val centered = Alignment.CenterVertically
    val edge = when {
        invalid -> colors.danger
        colors.plain -> colors.line
        else -> Color.Transparent
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Eyebrow(it, Modifier.padding(start = 4.dp)) }
        BasicTextField(
            value,
            onChange,
            textStyle = style,
            singleLine = single,
            minLines = lines.first,
            maxLines = lines.last,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboard,
            keyboardActions = onDone?.let { KeyboardActions { it() } }
                ?: KeyboardActions.Default,
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        ) { inner ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(Corners.well)
                    .background(colors.sunken)
                    .border(1.dp, edge, Corners.well)
                    .heightIn(min = 50.dp)
                    .padding(horizontal = 14.dp, vertical = if (single) 0.dp else 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = if (single) centered else Alignment.Top,
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            hint,
                            color = colors.faint,
                            style = style,
                            maxLines = lines.last,
                        )
                    }
                    inner()
                }
                trailing?.invoke()
            }
        }
        error?.let {
            Text(
                it,
                Modifier.padding(start = 4.dp),
                colors.danger,
                style = Look.type.note,
            )
        }
    }
}

@Composable
fun Scrubber(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onFinished: () -> Unit = {},
) {
    val colors = Look.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)

    fun valueAt(x: Float, width: Int, thumb: Float): Float {
        val across = ((x - thumb) / (width - 2 * thumb)).coerceIn(0f, 1f)
        val share = if (rtl) 1 - across else across
        val stops = steps + 1
        val snapped = if (steps >
            0
        ) {
            (share * stops).roundToInt() / stops.toFloat()
        } else {
            share
        }
        return range.start + snapped * span
    }

    Canvas(
        modifier.fillMaxWidth()
            .height(LocalTouchTarget.current)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
                setProgress { target ->
                    change(target.coerceIn(range))
                    finished()
                    true
                }
            }
            .pointerInput(range, steps, rtl) {
                detectTapGestures {
                    change(valueAt(it.x, size.width, ThumbRadius.toPx()))
                    finished()
                }
            }
            .pointerInput(range, steps, rtl) {
                detectHorizontalDragGestures(
                    onDragEnd = { finished() },
                    onDragCancel = { finished() },
                ) { touch, _ ->
                    touch.consume()
                    change(valueAt(touch.position.x, size.width, ThumbRadius.toPx()))
                }
            },
    ) {
        val thumb = ThumbRadius.toPx()
        val across = if (rtl) 1 - fraction else fraction
        val start = Offset(thumb, center.y)
        val end = Offset(size.width - thumb, center.y)
        val knob = Offset(thumb + (size.width - 2 * thumb) * across, center.y)
        val width = 4.dp.toPx()
        drawLine(colors.line, start, end, width, StrokeCap.Round)
        drawLine(colors.accent, if (rtl) end else start, knob, width, StrokeCap.Round)
        drawCircle(colors.accent, thumb, knob)
        drawCircle(colors.onAccent, thumb / 3, knob)
    }
}

@Composable
private fun spin(): Float {
    if (LocalReducedMotion.current) return 0.35f
    val turn by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
    )
    return turn
}

/** A thin progress line; moving on its own while [progress] is unknown. */
@Composable
fun Meter(
    progress: Float?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    color: Color = Look.colors.accent,
    height: Dp = 4.dp,
) {
    val track = Look.colors.line
    val phase = if (progress == null) spin() else 0f
    Canvas(modifier.height(height).clip(CircleShape).background(track)) {
        if (progress != null) {
            val filled = Size(size.width * progress.coerceIn(0f, 1f), size.height)
            return@Canvas drawRoundRect(color, size = filled)
        }
        val length = size.width * 0.35f
        val left = (size.width + length) * phase - length
        drawRoundRect(color, Offset(left, 0f), Size(length, size.height))
    }
}

@Composable
fun Ring(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = Look.colors.accent,
) {
    val track = Look.colors.line
    val turn = if (progress == null) spin() else 0f
    Canvas(modifier.size(size)) {
        val stroke = Stroke(size.toPx() / 8, cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val box = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        val corner = Offset(inset, inset)
        drawArc(track, 0f, 360f, false, corner, box, style = stroke)
        val sweep = 360f * (progress?.coerceIn(0f, 1f) ?: 0.28f)
        drawArc(color, turn * 360f - 90f, sweep, false, corner, box, style = stroke)
    }
}

@Composable
fun LoadingBar() = Meter(null, Modifier.fillMaxWidth().padding(ScreenPadding))

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Ring(null, size = 32.dp)
    }
}
