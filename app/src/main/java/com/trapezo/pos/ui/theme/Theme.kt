package com.trapezo.pos.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ---- Trapezo brand palette (original) — green primary, trapezoid identity ----
val Green900 = Color(0xFF00392A)
val Green700 = Color(0xFF00875A)
val Green500 = Color(0xFF00A96B)
val Green400 = Color(0xFF00C48C)
val Green100 = Color(0xFFCCF3E6)
val Blue700 = Color(0xFF1A3FD1)
val Blue100 = Color(0xFFDDE5FF)
val Amber500 = Color(0xFFFFB300)
val Red400 = Color(0xFFE85A5A)
val Ink900 = Color(0xFF101426)
val Ink600 = Color(0xFF4A5068)
val Paper = Color(0xFFF6F7FB)

private val LightColors = lightColorScheme(
    primary = Green700,
    onPrimary = Color.White,
    primaryContainer = Green100,
    onPrimaryContainer = Green900,
    secondary = Blue700,
    onSecondary = Color.White,
    secondaryContainer = Blue100,
    onSecondaryContainer = Color(0xFF12288F),
    tertiary = Amber500,
    error = Red400,
    background = Paper,
    onBackground = Ink900,
    surface = Color.White,
    onSurface = Ink900,
    surfaceVariant = Color(0xFFEAEDF6),
    onSurfaceVariant = Ink600,
    // Structured retail surfaces: flat containers separated by hairlines, not stacked shadows.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFE),
    surfaceContainer = Color(0xFFF2F4F9),
    surfaceContainerHigh = Color(0xFFEBEEF5),
    surfaceContainerHighest = Color(0xFFE4E8F1),
    outline = Color(0xFFB9BFD4),
    outlineVariant = Color(0xFFDFE3F0)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD9A6),
    onPrimary = Green900,
    primaryContainer = Green700,
    onPrimaryContainer = Green100,
    secondary = Color(0xFF9DB1FF),
    onSecondary = Color(0xFF12288F),
    error = Red400,
    background = Ink900,
    onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF181C30),
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF232841),
    onSurfaceVariant = Color(0xFFB7BDCF),
    surfaceContainerLowest = Color(0xFF12172A),
    surfaceContainerLow = Color(0xFF161B2E),
    surfaceContainer = Color(0xFF1C2136),
    surfaceContainerHigh = Color(0xFF232841),
    surfaceContainerHighest = Color(0xFF2A3050),
    outline = Color(0xFF61667F),
    outlineVariant = Color(0xFF353A52)
)

/**
 * Compact, strong typography for an operator-facing POS: page titles stay restrained
 * while metrics and totals get the visual weight.
 */
private val TrapezoTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    )
}

@Composable
fun TrapezoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TrapezoTypography,
            content = content
        )
    }
}
