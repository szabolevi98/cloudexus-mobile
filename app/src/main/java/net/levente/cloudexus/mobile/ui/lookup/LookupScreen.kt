package net.levente.cloudexus.mobile.ui.lookup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.api.Product
import net.levente.cloudexus.mobile.data.scanner.ScannerBroadcastEffect
import net.levente.cloudexus.mobile.data.scanner.ScannerConfig
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.booking.formatQuantity
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.EmptyState
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.components.ScanField
import net.levente.cloudexus.mobile.ui.scan.CameraScanDialog
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxNavyTo
import net.levente.cloudexus.mobile.ui.toUiText

data class LookupUiState(
    val lookingUp: Boolean = false,
    val product: Product? = null,
    val notFound: String? = null,
    val error: UiText? = null,
)

class LookupViewModel(private val api: ApiClient, private val sessions: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(LookupUiState())
    val state = _state.asStateFlow()

    fun onScan(code: String) {
        val connection = sessions.current?.connection() ?: return
        if (state.value.lookingUp) return
        _state.update { it.copy(lookingUp = true, notFound = null, error = null) }
        viewModelScope.launch {
            try {
                val product = api.lookup(connection, code)
                _state.update { LookupUiState(product = product, notFound = if (product == null) code else null) }
            } catch (e: ApiException) {
                if (e is ApiException.Unauthorized) sessions.expire()
                _state.update { it.copy(lookingUp = false, error = e.toUiText()) }
            }
        }
    }
}

/** Scan anything to see where it is and how much there is, without booking. */
@Composable
fun LookupScreen(viewModel: LookupViewModel, scanner: ScannerConfig, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var camera by rememberSaveable { mutableStateOf(false) }
    ScannerBroadcastEffect(scanner) { code -> if (!camera) viewModel.onScan(code) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CxHeader(title = stringResource(R.string.mode_lookup), subtitle = stringResource(R.string.mode_lookup_subtitle), onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item { ScanField(onScan = viewModel::onScan, onCamera = { camera = true }, busy = state.lookingUp, focusKey = camera) }

            val product = state.product
            when {
                state.error != null -> item { Problem(stringResource(R.string.lookup_failed), state.error!!.asString()) }
                state.notFound != null -> item {
                    Problem(stringResource(R.string.code_not_found_title), stringResource(R.string.code_not_found_text, state.notFound!!))
                }
                product != null -> {
                    item { ProductCard(product) }
                    val byWarehouse = product.stock.groupBy { it.warehouseId }
                    if (byWarehouse.isEmpty()) {
                        item { Text(stringResource(R.string.lookup_no_stock), color = CxMuted, modifier = Modifier.padding(4.dp)) }
                    }
                    items(byWarehouse.values.toList(), key = { it.first().warehouseId }) { rows ->
                        CxCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(Icons.Rounded.Warehouse, CxNavyTo, size = 40.dp, soft = true)
                                Spacer(Modifier.width(12.dp))
                                Text(rows.first().warehouseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                val total = rows.sumOf { it.quantity.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO }
                                Text("${formatQuantity(total)} ${product.unit.orEmpty()}".trim(), style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.padding(4.dp))
                            rows.forEachIndexed { index, row ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Text(row.locationCode ?: stringResource(R.string.no_location), color = CxMuted, modifier = Modifier.weight(1f))
                                    Text(formatQuantity(row.quantity), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                else -> item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.ManageSearch,
                        stringResource(R.string.lookup_empty_title),
                        stringResource(R.string.lookup_empty_text),
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (camera) {
        CameraScanDialog(onCode = { code -> camera = false; viewModel.onScan(code) }, onDismiss = { camera = false })
    }
}

@Composable
private fun ProductCard(product: Product) {
    CxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Inventory2, CxNavyTo)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(product.sku, product.barcode).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.stock_total), style = MaterialTheme.typography.labelSmall, color = CxMuted)
                Text("${formatQuantity(product.stockTotal)} ${product.unit.orEmpty()}".trim(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Problem(title: String, text: String) {
    CxCard(border = CxDanger.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = CxDanger)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
        }
    }
}
