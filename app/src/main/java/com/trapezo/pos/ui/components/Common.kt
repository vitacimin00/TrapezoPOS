package com.trapezo.pos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space
import com.trapezo.pos.ui.theme.Touch
import com.trapezo.pos.ui.theme.TrapezoStatus
import com.trapezo.pos.utils.Money

/** Semantic tone used by badges and feedback surfaces. */
enum class Tone { NEUTRAL, SUCCESS, WARNING, DANGER, INFO }

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val status = TrapezoStatus
    return when (tone) {
        Tone.SUCCESS -> status.successContainer to status.success
        Tone.WARNING -> status.warningContainer to status.warning
        Tone.DANGER -> status.dangerContainer to status.danger
        Tone.INFO -> status.infoContainer to status.info
        Tone.NEUTRAL -> status.neutralContainer to status.onNeutralContainer
    }
}

/**
 * Status chip that always pairs color with a text label (and optionally an icon), so
 * meaning never depends on color perception alone.
 */
@Composable
fun StatusBadge(
    label: String,
    tone: Tone = Tone.NEUTRAL,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val (container, content) = toneColors(tone)
    Surface(color = container, shape = Radius.badge, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs)
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
        }
    }
}

/** Section title used inside panels and forms. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** Screen header: compact title plus optional subtitle and trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions?.invoke()
    }
}

/** Prominent search field used as the dominant control on data screens. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = Radius.field,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Hapus pencarian")
                    }
                }
                trailing?.invoke()
            }
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth().heightIn(min = Touch.control)
    )
}

/**
 * Meaningful empty state. Every call site supplies its own copy so no two screens
 * show the same generic message.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier.fillMaxSize().padding(Space.xxl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Space.xs))
                Button(onClick = onAction, shape = Radius.field) { Text(actionLabel) }
            }
        }
    }
}

/** Deliberate loading surface — never a blank screen. */
@Composable
fun LoadingState(message: String = "Memuat data…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Space.xxl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Inline row-level loading indicator for "load more" affordances. */
@Composable
fun InlineLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(Space.md), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
}

/** Metric block; `emphasis` promotes the primary business number. */
@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    support: String? = null,
    tone: Tone = Tone.NEUTRAL,
    emphasis: Boolean = false
) {
    val (container, content) = toneColors(tone)
    Surface(
        color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.surfaceContainerLow else container,
        shape = Radius.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else content,
                maxLines = 2
            )
            Text(
                value,
                style = if (emphasis) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (support != null) {
                Text(
                    support,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Right-aligned monetary value with tabular-ish alignment. Formatting always goes through
 * [Money.fmt] so no screen invents its own Rupiah rendering.
 */
@Composable
fun MoneyText(
    amount: Long,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    weight: FontWeight? = null
) {
    Text(
        Money.fmt(amount),
        style = style,
        color = color,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = modifier
    )
}

/** Label/amount row used in cart summaries, totals and detail panels. */
@Composable
fun AmountRow(
    label: String,
    amount: Long,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    tone: Tone = Tone.NEUTRAL
) {
    val (_, toneContent) = toneColors(tone)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else toneContent,
            modifier = Modifier.weight(1f)
        )
        MoneyText(
            amount = amount,
            style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else toneContent,
            weight = if (emphasize) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** Grouped form section with a heading and hairline separation. */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionHeader(title)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

/** Confirmation dialog for consequential actions only (refund, close shift, restore…). */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tone: Tone = Tone.DANGER,
    dismissLabel: String = "Batal"
) {
    val (_, content) = toneColors(tone)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radius.panel,
        icon = {
            Icon(
                when (tone) {
                    Tone.DANGER -> Icons.Default.ErrorOutline
                    Tone.WARNING -> Icons.Default.WarningAmber
                    Tone.SUCCESS -> Icons.Default.CheckCircle
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = content
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = Radius.field,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = content)
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}
