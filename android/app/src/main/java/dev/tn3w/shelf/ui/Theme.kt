package dev.tn3w.shelf.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.ThemeMode

val LocalReducedMotion = staticCompositionLocalOf { false }

private val LightColors =
    lightColorScheme(
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

private val DarkColors =
    darkColorScheme(
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

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) =
    Font(
        R.font.inter,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

private val Inter = FontFamily(listOf(400, 500, 600, 700, 800).map(::inter))

private fun TextStyle.inter(weight: FontWeight? = fontWeight, tracking: Double = -0.01) =
    copy(fontFamily = Inter, fontWeight = weight, letterSpacing = tracking.em)

private val InterTypography =
    Typography().run {
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
fun isDark(mode: ThemeMode) =
    when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

@Composable
fun ShelfTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors =
        when {
            dynamic && dark -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            dark -> DarkColors
            else -> LightColors
        }
    val reducedMotion = remember {
        val scale = Settings.Global.ANIMATOR_DURATION_SCALE
        Settings.Global.getFloat(context.contentResolver, scale, 1f) == 0f
    }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = colors,
            typography = InterTypography,
            content = content,
        )
    }
}
