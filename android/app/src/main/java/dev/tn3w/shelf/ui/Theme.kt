package dev.tn3w.shelf.ui

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Global
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.PageColor
import dev.tn3w.shelf.data.Settings
import dev.tn3w.shelf.data.ThemeMode

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalTouchTarget = staticCompositionLocalOf { 44.dp }

@Immutable
data class ShelfColors(
    val paper: Color,
    val raised: Color,
    val sunken: Color,
    val line: Color,
    val glow: Color,
    val ink: Color,
    val soft: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val wash: Color,
    val danger: Color,
    val dark: Boolean,
    val black: Boolean = false,
    val plain: Boolean = false,
)

@Immutable
class ShelfType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val subhead: TextStyle,
    val body: TextStyle,
    val note: TextStyle,
    val label: TextStyle,
    val eyebrow: TextStyle,
)

object Corners {
    val cover = RoundedCornerShape(2.dp, 6.dp, 6.dp, 2.dp)
    val well = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
}

object Space {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val wide = 24.dp
    val section = 36.dp
}

object Motion {
    const val QUICK = 140
    const val CALM = 320
    const val SLOW = 620
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> calm() = tween<T>(CALM, easing = ease)

    fun <T> slow() = tween<T>(SLOW, easing = ease)
}

object Look {
    val colors
        @Composable @ReadOnlyComposable
        get() = LocalColors.current
    val type
        @Composable @ReadOnlyComposable
        get() = LocalType.current
}

private val LightColors = ShelfColors(
    paper = Color(0xFFF8F4EC),
    raised = Color(0xFFFFFDF8),
    sunken = Color(0xFFEEE8DC),
    line = Color(0xFFE0D8CA),
    glow = Color(0xFFF8F4EC),
    ink = Color(0xFF1E1915),
    soft = Color(0xFF675E54),
    faint = Color(0xFF9B9185),
    accent = Color(0xFFAD3F1C),
    onAccent = Color.White,
    wash = Color(0xFFF1DCCE),
    danger = Color(0xFFA9281E),
    dark = false,
)

private val DarkColors = ShelfColors(
    paper = Color(0xFF14110E),
    raised = Color(0xFF1F1B17),
    sunken = Color(0xFF2A2520),
    line = Color(0xFF362F29),
    glow = Color(0xFF14110E),
    ink = Color(0xFFF0E8DD),
    soft = Color(0xFFADA397),
    faint = Color(0xFF7A7167),
    accent = Color(0xFFF2A887),
    onAccent = Color(0xFF3A1304),
    wash = Color(0xFF3B271E),
    danger = Color(0xFFF2B0AA),
    dark = true,
)

private val BlackColors = DarkColors.copy(
    paper = Color.Black,
    raised = Color(0xFF121110),
    sunken = Color(0xFF1C1A18),
    line = Color(0xFF2A2724),
    glow = Color.Black,
    black = true,
)

private val EInkColors = ShelfColors(
    paper = Color.White,
    raised = Color.White,
    sunken = Color.White,
    line = Color.Black,
    glow = Color.White,
    ink = Color.Black,
    soft = Color.Black,
    faint = Color.Black,
    accent = Color.Black,
    onAccent = Color.White,
    wash = Color.White,
    danger = Color.Black,
    dark = false,
    plain = true,
)

private val PaperPage = LightColors.copy(
    paper = Color(0xFFF4ECD8),
    raised = Color(0xFFFBF5E6),
    sunken = Color(0xFFE9DFC8),
    line = Color(0xFFDCD0B6),
    glow = Color(0xFFF4ECD8),
    ink = Color(0xFF3B2F22),
    soft = Color(0xFF6E5F4C),
    faint = Color(0xFF9A8B76),
    wash = Color(0xFFEBD8C0),
)

private val NightPage = BlackColors.copy(
    ink = Color(0xFFC8C8C8),
    soft = Color(0xFF8E8E8E),
    faint = Color(0xFF666666),
)

val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { type() }
private val EInkTouchTarget = 56.dp

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Inter = FontFamily(listOf(400, 500, 600, 700).map(::inter))

private fun serif(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )

private fun type() = ShelfType(
    display = serif(36, 42, FontWeight.Medium, -0.02),
    title = serif(27, 33, FontWeight.Medium, -0.015),
    heading = serif(21, 27, FontWeight.Medium, -0.01),
    subhead = sans(16, 22, FontWeight.SemiBold, -0.01),
    body = sans(16, 24, FontWeight.Normal, -0.005),
    note = sans(13, 18, FontWeight.Normal),
    label = sans(14, 18, FontWeight.SemiBold),
    eyebrow = sans(11, 14, FontWeight.SemiBold, 0.12),
)

@Composable
fun isDark(settings: Settings) = !settings.eInk && when (settings.theme) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

val wallpaperColorsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
private fun ShelfColors.withWallpaperAccent(): ShelfColors {
    val context = LocalContext.current
    val scheme =
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return copy(
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        wash = lerp(paper, scheme.primary, 0.18f),
    )
}

fun ShelfColors.tinted(tone: CoverTone?): ShelfColors {
    if (tone == null || plain) return this
    val tint = tintOf(tone, dark, black)
    return copy(
        paper = Color(tint.paper),
        raised = Color(tint.raised),
        sunken = Color(tint.sunken),
        line = Color(tint.line),
        glow = Color(tint.glow),
        accent = Color(tint.accent),
        onAccent = Color(tint.onAccent),
        wash = Color(tint.wash),
    )
}

fun ShelfColors.page(color: PageColor): ShelfColors {
    val page = when (color) {
        PageColor.Theme -> return this
        PageColor.Paper -> PaperPage
        PageColor.Night -> NightPage
    }
    val readable = readable(accent.toArgb(), page.paper.toArgb())
    return page.copy(accent = Color(readable), onAccent = Color(textOn(readable)))
}

@Composable
private fun ShelfColors.animated(): ShelfColors {
    @Composable
    fun calm(color: Color) = animateColorAsState(color, Motion.slow()).value
    return copy(
        paper = calm(paper),
        raised = calm(raised),
        sunken = calm(sunken),
        line = calm(line),
        glow = calm(glow),
        accent = calm(accent),
        onAccent = calm(onAccent),
        wash = calm(wash),
    )
}

@Composable
private fun systemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun reduced() = Global.getFloat(resolver, Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var value by remember { mutableStateOf(reduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = reduced()
            }
        }
        val uri = Global.getUriFor(Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return value
}

private fun ShelfColors.material() = (if (dark) darkColorScheme() else lightColorScheme())
    .copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = wash,
        onPrimaryContainer = ink,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        onSurfaceVariant = soft,
        surfaceContainerLow = raised,
        surfaceContainer = raised,
        surfaceContainerHigh = sunken,
        outline = faint,
        outlineVariant = line,
        error = danger,
    )

/** Shelf's colours for everything inside [content]. */
@Composable
fun Palette(colors: ShelfColors, content: @Composable () -> Unit) {
    val selection = TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f))
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalContentColor provides colors.ink,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}

/** Colours taken from a cover: its dominant hue on surfaces, its accent on controls. */
@Composable
fun CoverTheme(tone: CoverTone?, content: @Composable () -> Unit) {
    val tinted = Look.colors.tinted(tone)
    val colors = if (LocalReducedMotion.current) tinted else tinted.animated()
    Palette(colors, content)
}

@Composable
fun ShelfTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val base = when {
        settings.eInk -> EInkColors
        dark && settings.blackTheme -> BlackColors
        dark -> DarkColors
        else -> LightColors
    }
    val dynamic = wallpaperColorsSupported && settings.wallpaperColors && !settings.eInk
    val colors = if (dynamic) base.withWallpaperAccent() else base
    val type = remember { type() }
    val touchTarget = if (settings.eInk) EInkTouchTarget else 44.dp
    MaterialTheme(colorScheme = colors.material()) {
        CompositionLocalProvider(
            LocalReducedMotion provides (systemReducedMotion() || settings.eInk),
            LocalTouchTarget provides touchTarget,
            LocalMinimumInteractiveComponentSize provides touchTarget,
            LocalType provides type,
            LocalTextStyle provides type.body,
            LocalIndication provides PressIndication,
        ) { Palette(colors, content) }
    }
}
