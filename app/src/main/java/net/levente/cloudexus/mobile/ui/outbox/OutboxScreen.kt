package net.levente.cloudexus.mobile.ui.outbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.work.Outbox
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.EmptyState
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxPrimary
import java.text.DateFormat
import java.util.Date

/**
 * The bookings waiting to be sent: they go by themselves when the network is
 * back. One the server refused stays here with the reason, to be sent again
 * once it is fixed on the web, or thrown away.
 */
@Composable
fun OutboxScreen(outbox: Outbox, onBack: () -> Unit) {
    val items by outbox.items.collectAsStateWithLifecycle()
    var discarding by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CxHeader(
            title = stringResource(R.string.outbox_title),
            subtitle = stringResource(R.string.outbox_subtitle),
            onBack = onBack,
            actions = { IconButton(onClick = outbox::kick) { Icon(Icons.Rounded.Refresh, stringResource(R.string.outbox_send_now), tint = Color.White) } },
        )
        if (items.isEmpty()) {
            EmptyState(Icons.Rounded.CloudDone, stringResource(R.string.outbox_empty_title), stringResource(R.string.outbox_empty_text))
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.navigationBarsPadding()) {
                items(items, key = { it.key }) { item ->
                    val failed = item.failed
                    CxCard(border = if (failed != null) CxDanger.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(if (failed != null) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudUpload, if (failed != null) CxDanger else CxPrimary, size = 40.dp, soft = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOf(item.summary, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.createdAt))).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CxMuted,
                                )
                            }
                        }
                        Text(
                            failed ?: stringResource(R.string.outbox_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (failed != null) CxDanger else CxMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(Modifier.padding(top = 4.dp)) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { discarding = item.key }) { Text(stringResource(R.string.outbox_discard), color = CxDanger) }
                            TextButton(onClick = { outbox.retry(item.key) }) { Text(stringResource(if (failed != null) R.string.outbox_retry else R.string.outbox_send_now)) }
                        }
                    }
                }
            }
        }
    }

    discarding?.let { key ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text(stringResource(R.string.outbox_discard_title)) },
            text = { Text(stringResource(R.string.outbox_discard_text)) },
            confirmButton = { TextButton(onClick = { outbox.discard(key); discarding = null }) { Text(stringResource(R.string.outbox_discard), color = CxDanger) } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
