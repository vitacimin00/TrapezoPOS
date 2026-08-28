package com.trapezo.pos.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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
    outline = Color(0xFFB9BFd4),
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
    outline = Color(0xFF61667F),
    outlineVariant = Color(0xFF353A52)
)

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
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
