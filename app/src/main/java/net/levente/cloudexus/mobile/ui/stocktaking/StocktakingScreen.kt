package net.levente.cloudexus.mobile.ui.stocktaking

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.scanner.ScannerBroadcastEffect
import net.levente.cloudexus.mobile.data.scanner.ScannerConfig
import net.levente.cloudexus.mobile.ui.booking.formatQuantity
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.EmptyState
import net.levente.cloudexus.mobile.ui.components.Pill
import net.levente.cloudexus.mobile.ui.components.QuantityStepper
import net.levente.cloudexus.mobile.ui.components.ScanField
import net.levente.cloudexus.mobile.ui.components.ScanSignals
import net.levente.cloudexus.mobile.ui.components.SectionLabel
import net.levente.cloudexus.mobile.ui.components.WarehouseList
import net.levente.cloudexus.mobile.ui.scan.CameraScanDialog
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxSuccess
import net.levente.cloudexus.mobile.ui.theme.CxViolet
import net.levente.cloudexus.mobile.ui.work.AmountDialog
import net.levente.cloudexus.mobile.ui.work.DiscardDialog
import net.levente.cloudexus.mobile.ui.work.DonePage
import net.levente.cloudexus.mobile.ui.work.NoteInputDialog
import net.levente.cloudexus.mobile.ui.work.ResumeDialog
import net.levente.cloudexus.mobile.ui.work.ScanNote
import net.levente.cloudexus.mobile.ui.work.SubmitBar
import net.levente.cloudexus.mobile.ui.work.WorkBanner
import java.math.BigDecimal

@Composable
fun StocktakingScreen(viewModel: StocktakingViewModel, scanner: ScannerConfig, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.mode_stocktaking)
    ScanSignals(viewModel.signals, state.sound)

    state.resume?.let { ResumeDialog(it.lines.size, title, viewModel::resumeSaved, viewModel::dropSaved) }

    when (state.step) {
        CountStep.WAREHOUSE -> {
            BackHandler(onBack = onExit)
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CxHeader(title = title, subtitle = stringResource(R.string.pick_warehouse), onBack = onExit)
                WarehouseList(state.warehouses, state.loading, state.loadError, CxViolet, viewModel::selectWarehouse, viewModel::load)
            }
        }
        CountStep.COUNT -> CountingStep(state, viewModel, scanner, title, onExit)
        CountStep.DONE -> {
            BackHandler(onBack = onExit)
            DonePage(
                header = title,
                title = stringResource(R.string.done_stocktaking),
                text = state.number?.let { number ->
                    val differing = state.results.count { (it.diff.toBigDecimalOrNull()?.signum() ?: 0) != 0 }
                    pluralStringResource(R.plurals.stocktaking_summary, differing, number, differing)
                },
                queued = state.queued,
                color = CxViolet,
                again = stringResource(R.string.again_stocktaking),
                againIcon = Icons.Rounded.Checklist,
                onAgain = viewModel::startOver,
                onHome = onExit,
            ) {
                val differing = state.results.filter { (it.diff.toBigDecimalOrNull()?.signum() ?: 0) != 0 }
                if (differing.isNotEmpty()) {
                    CxCard(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        LazyColumn {
                            items(differing) { result ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(result.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.stocktaking_book_counted, formatQuantity(result.book), formatQuantity(result.counted)), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                                    }
                                    val diff = result.diff.toBigDecimalOrNull() ?: BigDecimal.ZERO
                                    Text((if (diff.signum() > 0) "+" else "") + formatQuantity(diff), style = MaterialTheme.typography.titleMedium, color = if (diff.signum() < 0) CxDanger else CxSuccess)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountingStep(state: StocktakingUiState, viewModel: StocktakingViewModel, scanner: ScannerConfig, title: String, onExit: () -> Unit) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CountLine?>(null) }
    var editNote by rememberSaveable { mutableStateOf(false) }
    var camera by rememberSaveable { mutableStateOf(false) }
    val dialogOpen = confirmDiscard || editing != null || editNote || camera
    val leave = { if (state.lines.isEmpty()) onExit() else confirmDiscard = true }

    ScannerBroadcastEffect(scanner) { code -> if (!dialogOpen) viewModel.onScan(code) }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        CxHeader(
            title = title,
            subtitle = state.warehouse?.name,
            onBack = leave,
            actions = {
                if (state.lines.isEmpty()) {
                    IconButton(onClick = viewModel::changeWarehouse) { Icon(Icons.Rounded.Warehouse, stringResource(R.string.change_warehouse), tint = Color.White) }
                }
            },
        ) {
            Pill(text = state.location?.code ?: stringResource(R.string.no_location), icon = Icons.Rounded.Place, onClick = null, modifier = Modifier.padding(top = 4.dp))
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScanField(onScan = viewModel::onScan, onCamera = { camera = true }, busy = state.lookingUp, focusKey = dialogOpen) }
            state.problem?.let { item { ScanNote(Icons.Rounded.ErrorOutline, CxDanger, stringResource(R.string.code_not_found_title), it.asString()) } }
            state.submitError?.let { item { WorkBanner(it, state.uncertain) } }
            if (state.lines.isEmpty()) {
                item { EmptyState(Icons.Rounded.QrCodeScanner, stringResource(R.string.stocktaking_empty_title), stringResource(R.string.stocktaking_empty_text), Modifier.fillMaxWidth()) }
            } else {
                item { SectionLabel(pluralStringResource(R.plurals.counted_products, state.lines.size, state.lines.size), Modifier.padding(top = 4.dp)) }
                items(state.lines, key = { it.productId }) { line ->
                    CxCard(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text((listOf(line.sku) + line.shelves).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                            }
                            IconButton(onClick = { viewModel.remove(line.productId) }, enabled = !state.uncertain) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.remove_line), tint = CxMuted)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = CxViolet, modifier = Modifier.padding(end = 6.dp))
                            Text(line.unit.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = CxMuted, modifier = Modifier.weight(1f))
                            if (state.uncertain) {
                                Text(formatQuantity(line.amount), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                            } else {
                                QuantityStepper(
                                    formatQuantity(line.amount),
                                    onMinus = { viewModel.setQuantity(line.productId, line.amount - BigDecimal.ONE) },
                                    onPlus = { viewModel.setQuantity(line.productId, line.amount + BigDecimal.ONE) },
                                    onEdit = { editing = line },
                                )
                            }
                        }
                    }
                }
            }
        }

        val summary = pluralStringResource(R.plurals.counted_products, state.lines.size, state.lines.size)
        SubmitBar(
            text = stringResource(R.string.submit_stocktaking) + if (state.lines.isEmpty()) "" else " · " + pluralStringResource(R.plurals.lines_short, state.lines.size, state.lines.size),
            color = CxViolet,
            enabled = state.lines.isNotEmpty(),
            submitting = state.submitting,
            uncertain = state.uncertain,
            onSubmit = viewModel::submit,
            onQueue = { viewModel.queue(listOfNotNull(title, state.warehouse?.name).joinToString(" · "), summary) },
            onUnlock = viewModel::unlock,
            onNote = { editNote = true },
            hasNote = state.note.isNotBlank(),
        )
    }

    if (confirmDiscard) DiscardDialog(state.lines.size, onDiscard = { confirmDiscard = false; viewModel.discard(); onExit() }, onDismiss = { confirmDiscard = false })
    editing?.let { line ->
        AmountDialog(line.name, line.unit, line.amount, allowZero = true, onConfirm = { viewModel.setQuantity(line.productId, it); editing = null }, onDismiss = { editing = null })
    }
    if (editNote) NoteInputDialog(state.note, onConfirm = { viewModel.setNote(it); editNote = false }, onDismiss = { editNote = false })
    if (camera) CameraScanDialog(onCode = { camera = false; viewModel.onScan(it) }, onDismiss = { camera = false })
}
