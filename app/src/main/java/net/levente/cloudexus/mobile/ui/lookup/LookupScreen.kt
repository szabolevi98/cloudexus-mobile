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
import net.levente.cloudexus.mobile.ui.components.RemoteImage
import net.levente.cloudexus.mobile.ui.components.ScanSignals
import net.levente.cloudexus.mobile.ui.components.Signal
import net.levente.cloudexus.mobile.data.work.WorkStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.rounded.WarningAmber
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

data class LookupUiState(
    val lookingUp: Boolean = false,
    val product: Product? = null,
    val notFound: String? = null,
    val error: UiText? = null,
)

class LookupViewModel(private val api: ApiClient, private val sessions: SessionManager, work: WorkStore) : ViewModel() {
    private val _state = MutableStateFlow(LookupUiState())
    val state = _state.asStateFlow()

    private val _signals = Channel<Signal>(Channel.BUFFERED)
    val signals = _signals.receiveAsFlow()

    val sound: StateFlow<Boolean> = sessions.current?.baseUrl
        ?.let { server -> work.prefs(server).map { it.sound }.stateIn(viewModelScope, SharingStarted.Eagerly, true) }
        ?: MutableStateFlow(true)

    fun onScan(code: String) {
        val connection = sessions.current?.connection() ?: return
        if (state.value.lookingUp) return
        _state.update { it.copy(lookingUp = true, notFound = null, error = null) }
        viewModelScope.launch {
            try {
                val product = api.lookup(connection, code)
                _state.update { LookupUiState(product = product, notFound = if (product == null) code else null) }
                _signals.trySend(if (product == null) Signal.ERROR else Signal.OK)
            } catch (e: ApiException) {
                if (e is ApiException.Unauthorized) sessions.expire()
                _state.update { it.copy(lookingUp = false, error = e.toUiText()) }
                _signals.trySend(Signal.ERROR)
            }
        }
    }
}

/** Scan anything to see where it is and how much there is, without booking. */
@Composable
fun LookupScreen(viewModel: LookupViewModel, scanner: ScannerConfig, http: OkHttpClient, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sound by viewModel.sound.collectAsStateWithLifecycle()
    ScanSignals(viewModel.signals, sound)
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
                    item { ProductCard(product, http) }
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
                                    // A negative shelf means stock was issued from it that was never booked onto it.
                                    val negative = (row.quantity.toBigDecimalOrNull()?.signum() ?: 0) < 0
                                    Text(formatQuantity(row.quantity), style = MaterialTheme.typography.bodyLarge, color = if (negative) CxDanger else MaterialTheme.colorScheme.onSurface)
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
private fun ProductCard(product: Product, http: OkHttpClient) {
    CxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val image = product.imageUrl
            if (image != null) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    RemoteImage(image, http, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            } else {
                IconTile(Icons.Rounded.Inventory2, CxNavyTo)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(product.sku, product.barcode).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.stock_total), style = MaterialTheme.typography.labelSmall, color = CxMuted)
                Text(
                    "${formatQuantity(product.stockTotal)} ${product.unit.orEmpty()}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (product.belowMinStock == true) CxDanger else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // The active price is the sale price when there is one, as on the web.
        val list = product.price?.toBigDecimalOrNull()
        val sale = product.salePrice?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val net = sale ?: list
        if (net != null) {
            val vat = product.vatRate?.toBigDecimalOrNull()
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lookup_net_price), style = MaterialTheme.typography.labelSmall, color = CxMuted)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatMoney(net), style = MaterialTheme.typography.titleLarge, color = if (sale != null) CxDanger else MaterialTheme.colorScheme.onSurface)
                        if (sale != null && list != null && list.compareTo(sale) != 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(formatMoney(list), style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough), color = CxMuted)
                        }
                    }
                }
                if (vat != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.lookup_gross_price, formatQuantity(vat)), style = MaterialTheme.typography.labelSmall, color = CxMuted)
                        val gross = net.multiply(BigDecimal.ONE + vat.movePointLeft(2))
                        Text(formatMoney(gross), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        if (product.belowMinStock == true) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CxDanger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    product.minStock?.toBigDecimalOrNull()?.let { stringResource(R.string.lookup_below_min_of, formatQuantity(it), product.unit.orEmpty()).trim() }
                        ?: stringResource(R.string.lookup_below_min),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CxDanger,
                )
            }
        }
    }
}

/** A price in the shop's currency, which the API does not name: grouped, with the cents only when there are any. */
private fun formatMoney(value: BigDecimal): String {
    val rounded = value.setScale(2, RoundingMode.HALF_UP)
    val format = NumberFormat.getNumberInstance(Locale.getDefault())
    format.minimumFractionDigits = if (rounded.stripTrailingZeros().scale() > 0) 2 else 0
    format.maximumFractionDigits = 2
    return format.format(rounded)
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
