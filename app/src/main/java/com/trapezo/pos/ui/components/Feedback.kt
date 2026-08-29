package com.trapezo.pos.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.TrapezoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-wide transient feedback. Replaces per-screen "notice card" duplication:
 * success/error/warning/info all funnel through one Snackbar host with consistent tone.
 *
 * Field-level problems stay inline in their form; only transient outcomes come here.
 */
class Feedback internal constructor(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope
) {
    fun success(message: String) = show(message, Tone.SUCCESS)
    fun error(message: String) = show(message, Tone.DANGER)
    fun warning(message: String) = show(message, Tone.WARNING)
    fun info(message: String) = show(message, Tone.INFO)

    /** Shows a message whose tone is derived from an operation result. */
    fun result(ok: Boolean, successMessage: String, errorMessage: String?) {
        if (ok) success(successMessage) else error(errorMessage ?: "Operasi gagal")
    }

    private fun show(message: String, tone: Tone) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(
                message = TonedMessage.encode(tone, message),
                withDismissAction = tone == Tone.DANGER,
                duration = if (tone == Tone.DANGER) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }
}

/** Tone is carried in the snackbar message and decoded by the host renderer. */
private object TonedMessage {
    private const val SEP = "\u001F"
    fun encode(tone: Tone, message: String) = "${tone.name}$SEP$message"
    fun decode(raw: String): Pair<Tone, String> {
        val parts = raw.split(SEP, limit = 2)
        if (parts.size != 2) return Tone.NEUTRAL to raw
        val tone = runCatching { Tone.valueOf(parts[0]) }.getOrDefault(Tone.NEUTRAL)
        return tone to parts[1]
    }
}

val LocalFeedback: ProvidableCompositionLocal<Feedback?> = compositionLocalOf { null }

/** Provides a [Feedback] instance bound to the given host. */
@Composable
fun ProvideFeedback(
    host: SnackbarHostState,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val feedback = remember(host, scope) { Feedback(host, scope) }
    CompositionLocalProvider(LocalFeedback provides feedback, content = content)
}

/** Convenience accessor; returns a no-op-safe instance only when provided. */
@Composable
fun rememberFeedback(): Feedback? = LocalFeedback.current

/** Tone-aware snackbar host used by the app shell. */
@Composable
fun TrapezoSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    val status = TrapezoStatus
    SnackbarHost(host, modifier) { data ->
        val (tone, message) = TonedMessage.decode(data.visuals.message)
        val container: Color = when (tone) {
            Tone.SUCCESS -> status.success
            Tone.DANGER -> status.danger
            Tone.WARNING -> status.warning
            Tone.INFO -> status.info
            Tone.NEUTRAL -> MaterialTheme.colorScheme.inverseSurface
        }
        val content: Color = when (tone) {
            Tone.SUCCESS -> status.onSuccess
            Tone.DANGER -> status.onDanger
            Tone.WARNING -> status.onWarning
            Tone.INFO -> status.onInfo
            Tone.NEUTRAL -> MaterialTheme.colorScheme.inverseOnSurface
        }
        Snackbar(
            shape = Radius.field,
            containerColor = container,
            contentColor = content,
            action = if (data.visuals.withDismissAction) {
                { TextButton(onClick = { data.dismiss() }) { Text("TUTUP", color = content, fontWeight = FontWeight.SemiBold) } }
            } else null
        ) { Text(message) }
    }
}
