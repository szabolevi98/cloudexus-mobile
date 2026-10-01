package net.levente.cloudexus.mobile.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.components.CxButton
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxDangerSoft
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxWarning
import net.levente.cloudexus.mobile.ui.theme.CxWarningSoft
import java.math.BigDecimal
import java.math.RoundingMode

/** A list left from before: continue it, or drop it. */
@Composable
fun ResumeDialog(lines: Int, what: String, onContinue: () -> Unit, onDrop: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.resume_title)) },
        text = { Text(pluralStringResource(R.plurals.resume_text, lines, lines, what)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.resume_continue)) } },
        dismissButton = { TextButton(onClick = onDrop) { Text(stringResource(R.string.resume_drop), color = CxDanger) } },
    )
}

/** Leaving a list that is not sent: it is lost. */
@Composable
fun DiscardDialog(lines: Int, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(pluralStringResource(R.plurals.discard_text, lines, lines)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard_confirm), color = CxDanger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Parses a typed amount: "2", "1,5"; at most 3 decimals; zero only when [allowZero]. */
fun parseAmount(text: String, allowZero: Boolean): BigDecimal? {
    val value = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (value.signum() < 0 || (value.signum() == 0 && !allowZero)) return null
    if (value.stripTrailingZeros().scale() > 3 || value >= BigDecimal("100000000000")) return null
    return value.setScale(maxOf(0, value.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY)
}

/** Types an exact amount, the current one selected so typing replaces it. */
@Composable
fun AmountDialog(title: String, unit: String?, initial: BigDecimal, allowZero: Boolean, onConfirm: (BigDecimal) -> Unit, onDismiss: () -> Unit) {
    val start = initial.stripTrailingZeros().toPlainString()
    var value by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val parsed = parseAmount(value.text, allowZero)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.quantity_label, unit.orEmpty())) },
                isError = parsed == null,
                supportingText = { if (parsed == null) Text(stringResource(if (allowZero) R.string.count_invalid else R.string.quantity_invalid)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun NoteInputDialog(note: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(200) },
                placeholder = { Text(stringResource(R.string.note_placeholder)) },
                supportingText = { Text("${text.length}/200") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A problem in place: red for a refusal, amber for "maybe sent". */
@Composable
fun WorkBanner(text: UiText, uncertain: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(if (uncertain) CxWarningSoft else CxDangerSoft, MaterialTheme.shapes.medium).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (uncertain) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline, contentDescription = null, tint = if (uncertain) CxWarning else CxDanger)
        Spacer(Modifier.width(10.dp))
        Text(text.asString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The bar at the bottom: the send button — and, after a send without an
 * answer, sending again with the same key, putting it in the queue, or
 * editing anyway.
 */
@Composable
fun SubmitBar(
    text: String,
    color: Color,
    enabled: Boolean,
    submitting: Boolean,
    uncertain: Boolean,
    onSubmit: () -> Unit,
    onQueue: () -> Unit,
    onUnlock: () -> Unit,
    onNote: (() -> Unit)? = null,
    hasNote: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (uncertain) {
                CxButton(stringResource(R.string.retry_submit), onClick = onSubmit, loading = submitting, color = color, icon = Icons.Rounded.Refresh, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CxButton(stringResource(R.string.queue_booking), onClick = onQueue, enabled = !submitting, color = CxMuted, icon = Icons.Rounded.CloudOff, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onUnlock, enabled = !submitting, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_anyway)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onNote != null) {
                        IconButton(onClick = onNote, enabled = !submitting) {
                            Icon(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.note), tint = if (hasNote) MaterialTheme.colorScheme.primary else CxMuted)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    CxButton(text, onClick = onSubmit, enabled = enabled, loading = submitting, color = color, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The page after sending: done, or in the queue; the next one, or home. */
@Composable
fun DonePage(
    header: String,
    title: String,
    text: String?,
    queued: Boolean,
    color: Color,
    again: String?,
    againIcon: ImageVector?,
    onAgain: () -> Unit,
    onHome: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        CxHeader(title = header)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(if (queued) Icons.Rounded.CloudOff else Icons.Rounded.CheckCircle, contentDescription = null, tint = if (queued) CxWarning else color, modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (queued) stringResource(R.string.queued_title) else title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            val line = if (queued) stringResource(R.string.queued_text) else text
            if (line != null) {
                Spacer(Modifier.height(8.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = CxMuted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            extra()
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (again != null) CxButton(again, onClick = onAgain, color = color, icon = againIcon, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.back_to_home), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A short line under the scan field: what the last scan did. */
@Composable
fun ScanNote(icon: ImageVector, color: Color, title: String, text: String? = null) {
    CxCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, color, soft = true)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
        }
    }
}
