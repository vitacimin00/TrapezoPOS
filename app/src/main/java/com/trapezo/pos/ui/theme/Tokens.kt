package com.trapezo.pos.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Trapezo design tokens.
 *
 * A predictable 4dp-based scale and a restrained radius hierarchy keep the retail POS
 * surfaces dense but readable. Screens must consume these instead of ad-hoc dp values.
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Restrained radius hierarchy — nothing is pill-shaped unless it is a chip/badge. */
object Radius {
    val control = RoundedCornerShape(8.dp)
    val field = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(12.dp)
    val panel = RoundedCornerShape(16.dp)
    val badge = RoundedCornerShape(6.dp)
}

/** Minimum comfortable touch sizes for primary POS controls. */
object Touch {
    val min = 44.dp
    val control = 48.dp
    val primaryAction = 56.dp
}

/**
 * Semantic status colors. Meaning is never carried by color alone: every consumer
 * pairs these with a label plus (where useful) an icon, per accessibility rules.
 */
data class StatusPalette(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color
)

private val LightStatus = StatusPalette(
    success = Color(0xFF11683F),
    onSuccess = Color.White,
    successContainer = Color(0xFFD6F2E2),
    warning = Color(0xFF8A5300),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFEBC7),
    danger = Color(0xFFA5262C),
    onDanger = Color.White,
    dangerContainer = Color(0xFFFBDDDE),
    info = Color(0xFF1B4ED1),
    onInfo = Color.White,
    infoContainer = Color(0xFFDDE5FF),
    neutralContainer = Color(0xFFEDEFF5),
    onNeutralContainer = Color(0xFF41465C)
)

private val DarkStatus = StatusPalette(
    success = Color(0xFF6FDCA9),
    onSuccess = Color(0xFF00311F),
    successContainer = Color(0xFF1B4534),
    warning = Color(0xFFF3C46A),
    onWarning = Color(0xFF3A2600),
    warningContainer = Color(0xFF4A3512),
    danger = Color(0xFFF08D91),
    onDanger = Color(0xFF450F12),
    dangerContainer = Color(0xFF542225),
    info = Color(0xFF9FB6FF),
    onInfo = Color(0xFF10265F),
    infoContainer = Color(0xFF23305C),
    neutralContainer = Color(0xFF262B40),
    onNeutralContainer = Color(0xFFC3C8D8)
)

/** Status palette for the active theme. */
val TrapezoStatus: StatusPalette
    @Composable @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) DarkStatus else LightStatus

internal val LocalIsDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }
