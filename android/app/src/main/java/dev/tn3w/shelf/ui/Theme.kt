package dev.tn3w.shelf.ui

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Global
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.Settings
import dev.tn3w.shelf.data.ThemeMode

val LocalReducedMotion = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4441F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondaryContainer = Color(0xFFF1E3D8),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceContainer = Color(0xFFF6EFE9),
    surfaceContainerHigh = Color(0xFFF0E8E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59C),
    onPrimary = Color(0xFF5C1A00),
    primaryContainer = Color(0xFF8A2F0C),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondaryContainer = Color(0xFF3D322B),
    background = Color(0xFF16120F),
    surface = Color(0xFF16120F),
    surfaceContainer = Color(0xFF221D19),
    surfaceContainerHigh = Color(0xFF2D2722),
)

private val EInkColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.Black,
    onPrimaryContainer = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.Black,
    onSecondaryContainer = Color.White,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color.Black,
    onTertiaryContainer = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color.Black,
    outlineVariant = Color.Black,
)

private val EInkTouchTarget = 56.dp

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Inter = FontFamily(listOf(400, 500, 600, 700, 800).map(::inter))

private fun TextStyle.inter(weight: FontWeight? = fontWeight, tracking: Double = -0.01) =
    copy(fontFamily = Inter, fontWeight = weight, letterSpacing = tracking.em)

private val InterTypography = Typography().run {
    Typography(
        displaySmall = displaySmall.inter(FontWeight.ExtraBold, -0.03),
        headlineMedium = headlineMedium.inter(FontWeight.Bold, -0.02),
        headlineSmall = headlineSmall.inter(FontWeight.Bold, -0.02),
        titleLarge = titleLarge.inter(FontWeight.SemiBold),
        titleMedium = titleMedium.inter(FontWeight.SemiBold),
        titleSmall = titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = bodyLarge.inter(),
        bodyMedium = bodyMedium.inter(),
        bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(FontWeight.Medium, 0.0),
        labelMedium = labelMedium.inter(FontWeight.Medium, 0.0),
        labelSmall = labelSmall.inter(FontWeight.Medium, 0.0),
    )
}

@Composable
fun isDark(settings: Settings) = !settings.eInk && when (settings.theme) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

val wallpaperColorsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private fun ColorScheme.black() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
)

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

@Composable
fun ShelfTheme(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isDark(settings)
    val dynamic = wallpaperColorsSupported && settings.wallpaperColors
    val colors = when {
        settings.eInk -> EInkColors
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val reducedMotion = systemReducedMotion() || settings.reduceMotion || settings.eInk
    val touchTarget = if (settings.eInk) EInkTouchTarget else 48.dp
    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalMinimumInteractiveComponentSize provides touchTarget,
    ) {
        MaterialTheme(
            colorScheme = if (dark && settings.blackTheme) colors.black() else colors,
            typography = InterTypography,
            content = content,
        )
    }
}
