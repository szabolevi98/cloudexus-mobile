package net.levente.cloudexus.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.Warehouse
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted

/** The warehouses to choose from, with the loading and the failed states. */
@Composable
fun WarehouseList(
    warehouses: List<Warehouse>,
    loading: Boolean,
    error: UiText?,
    color: Color,
    onSelect: (Warehouse) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> ErrorPanel(error, onRetry)
        warehouses.isEmpty() -> EmptyState(Icons.Rounded.Warehouse, stringResource(R.string.no_warehouses_title), stringResource(R.string.no_warehouses_text))
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(warehouses, key = { it.id }) { warehouse ->
                CxCard(onClick = { onSelect(warehouse) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Warehouse, color, soft = true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(warehouse.name, style = MaterialTheme.typography.titleMedium)
                            if (!warehouse.address.isNullOrBlank()) {
                                Text(warehouse.address, style = MaterialTheme.typography.bodyMedium, color = CxMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = CxMuted)
                    }
                }
            }
        }
    }
}

/** A request that failed, and the button to try it again. */
@Composable
fun ErrorPanel(error: UiText, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconTile(Icons.Rounded.CloudOff, CxDanger, size = 64.dp, soft = true)
        Spacer(Modifier.height(16.dp))
        Text(error.asString(), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        CxButton(stringResource(R.string.retry), onClick = onRetry, icon = Icons.Rounded.Refresh)
    }
}
