package com.trapezo.pos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width classes for the whole app. Responsive decisions are centralized here so screens
 * never scatter raw `if (screenWidth > …)` checks.
 */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** True when the layout has room for a persistent side-by-side pane (tablet/landscape). */
val WidthClass.isExpanded: Boolean get() = this == WidthClass.EXPANDED

/** True for phone-portrait-like widths where a bottom bar + sheets are the right pattern. */
val WidthClass.isCompact: Boolean get() = this == WidthClass.COMPACT

private val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

/** Current width class, provided by [ResponsiveScope]. */
val currentWidthClass: WidthClass
    @Composable @ReadOnlyComposable
    get() = LocalWidthClass.current

/**
 * Measures the available width once and publishes a [WidthClass] to the subtree.
 * Breakpoints follow Material window size classes (600dp / 840dp).
 */
@Composable
fun ResponsiveScope(
    modifier: Modifier = Modifier,
    content: @Composable (WidthClass) -> Unit
) {
    BoxWithConstraints(modifier) {
        val widthClass = widthClassFor(maxWidth)
        CompositionLocalProvider(LocalWidthClass provides widthClass) {
            content(widthClass)
        }
    }
}

/** Pure mapping from available width to a width class (unit-testable). */
fun widthClassFor(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.COMPACT
    width < 840.dp -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

/**
 * Two-pane layout that collapses to a single pane on narrow widths.
 * `primaryWeight` controls the split on expanded widths (e.g. 0.62f products / cart).
 */
@Composable
fun ResponsivePane(
    modifier: Modifier = Modifier,
    primaryWeight: Float = 0.62f,
    showSecondary: Boolean = true,
    secondary: @Composable () -> Unit,
    primary: @Composable () -> Unit
) {
    val widthClass = currentWidthClass
    if (widthClass.isExpanded && showSecondary) {
        androidx.compose.foundation.layout.Row(modifier) {
            Box(Modifier.weight(primaryWeight)) { primary() }
            androidx.compose.material3.VerticalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f - primaryWeight)) { secondary() }
        }
    } else {
        Box(modifier) { primary() }
    }
}
