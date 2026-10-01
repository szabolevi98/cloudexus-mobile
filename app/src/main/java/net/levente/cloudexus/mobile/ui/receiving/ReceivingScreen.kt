package net.levente.cloudexus.mobile.ui.receiving

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.CircularProgressIndicator
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
import net.levente.cloudexus.mobile.data.api.ReceiveLine
import net.levente.cloudexus.mobile.data.scanner.ScannerBroadcastEffect
import net.levente.cloudexus.mobile.data.scanner.ScannerConfig
import net.levente.cloudexus.mobile.ui.booking.formatQuantity
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.EmptyState
import net.levente.cloudexus.mobile.ui.components.ErrorPanel
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.components.Pill
import net.levente.cloudexus.mobile.ui.components.ScanField
import net.levente.cloudexus.mobile.ui.components.ScanSignals
import net.levente.cloudexus.mobile.ui.components.WarehouseList
import net.levente.cloudexus.mobile.ui.picking.OrderStep
import net.levente.cloudexus.mobile.ui.scan.CameraScanDialog
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxOrange
import net.levente.cloudexus.mobile.ui.theme.CxSuccess
import net.levente.cloudexus.mobile.ui.work.AmountDialog
import net.levente.cloudexus.mobile.ui.work.DiscardDialog
import net.levente.cloudexus.mobile.ui.work.DonePage
import net.levente.cloudexus.mobile.ui.work.NoteInputDialog
import net.levente.cloudexus.mobile.ui.work.ResumeDialog
import net.levente.cloudexus.mobile.ui.work.ScanNote
import net.levente.cloudexus.mobile.ui.work.SubmitBar
import net.levente.cloudexus.mobile.ui.work.WorkBanner

@Composable
fun ReceivingScreen(viewModel: ReceivingViewModel, scanner: ScannerConfig, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.mode_receiving)
    ScanSignals(viewModel.signals, state.sound)

    state.resume?.let { saved ->
        val number = state.tasks.firstOrNull { it.id == saved.orderId }?.poNumber.orEmpty()
        ResumeDialog(saved.arrivals.size, "$title · $number", viewModel::resumeSaved, viewModel::dropSaved)
    }

    when (state.step) {
        OrderStep.LIST -> {
            BackHandler(onBack = onExit)
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CxHeader(
                    title = title,
                    subtitle = stringResource(R.string.receiving_list_subtitle),
                    onBack = onExit,
                    actions = { IconButton(onClick = viewModel::loadTasks) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = Color.White) } },
                )
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.loadError != null -> ErrorPanel(state.loadError!!, viewModel::loadTasks)
                    state.tasks.isEmpty() -> EmptyState(Icons.Rounded.LocalShipping, stringResource(R.string.receiving_empty_title), stringResource(R.string.receiving_empty_text))
                    else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.navigationBarsPadding()) {
                        items(state.tasks, key = { it.id }) { task ->
                            CxCard(onClick = { viewModel.selectTask(task) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconTile(Icons.Rounded.LocalShipping, CxSuccess, soft = true)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(task.poNumber, style = MaterialTheme.typography.titleMedium)
                                        Text(task.partnerName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(
                                                task.orderDate,
                                                pluralStringResource(R.plurals.lines_count, task.lineCount, task.lineCount),
                                                if (task.partlyReceived) stringResource(R.string.receiving_partly) else null,
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CxMuted,
                                        )
                                    }
                                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = CxMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
        OrderStep.WAREHOUSE -> {
            BackHandler(onBack = viewModel::backToList)
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CxHeader(title = state.task?.poNumber ?: title, subtitle = stringResource(R.string.receiving_pick_warehouse), onBack = viewModel::backToList)
                WarehouseList(state.warehouses, false, null, CxSuccess, viewModel::selectWarehouse, viewModel::loadTasks)
            }
        }
        OrderStep.WORK -> WorkStep(state, viewModel, scanner, title)
        OrderStep.DONE -> {
            BackHandler(onBack = onExit)
            DonePage(
                header = title,
                title = stringResource(R.string.done_receiving),
                text = stringResource(R.string.done_receiving_text, state.task?.poNumber.orEmpty(), state.warehouse?.name.orEmpty()),
                queued = state.queued,
                color = CxSuccess,
                again = stringResource(R.string.again_receiving),
                againIcon = Icons.Rounded.LocalShipping,
                onAgain = viewModel::backToList,
                onHome = onExit,
            )
        }
    }
}

@Composable
private fun WorkStep(state: ReceivingUiState, viewModel: ReceivingViewModel, scanner: ScannerConfig, title: String) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReceiveLine?>(null) }
    var editNote by rememberSaveable { mutableStateOf(false) }
    var camera by rememberSaveable { mutableStateOf(false) }
    val dialogOpen = confirmLeave || editing != null || editNote || camera
    val leave = { if (!state.anything) viewModel.backToList() else confirmLeave = true }

    ScannerBroadcastEffect(scanner) { code -> if (!dialogOpen) viewModel.onScan(code) }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        CxHeader(
            title = state.task?.poNumber ?: title,
            subtitle = listOfNotNull(state.task?.partnerName, state.warehouse?.name).joinToString(" · "),
            onBack = leave,
            actions = {
                if (!state.anything) {
                    IconButton(onClick = viewModel::chooseWarehouse) { Icon(Icons.Rounded.Warehouse, stringResource(R.string.change_warehouse), tint = Color.White) }
                }
            },
        ) {
            Pill(text = state.location?.code ?: stringResource(R.string.no_location), icon = Icons.Rounded.Place, onClick = null, modifier = Modifier.padding(top = 4.dp))
        }

        val order = state.order
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError != null -> Box(Modifier.weight(1f)) { ErrorPanel(state.loadError, viewModel::backToList) }
            order != null -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ScanField(onScan = viewModel::onScan, onCamera = { camera = true }, busy = false, focusKey = dialogOpen) }
                if (state.locations.isNotEmpty() && state.location == null) {
                    item { ScanNote(Icons.Rounded.Place, CxMuted, stringResource(R.string.receiving_shelf_hint)) }
                }
                state.problem?.let {
                    item {
                        if (state.warning) ScanNote(Icons.Rounded.WarningAmber, CxOrange, it.asString())
                        else ScanNote(Icons.Rounded.ErrorOutline, CxDanger, it.asString())
                    }
                }
                state.submitError?.let { item { WorkBanner(it, state.uncertain) } }
                items(order.lines, key = { it.productId }) { line ->
                    ReceiveLineCard(line, state, highlighted = state.lastProductId == line.productId, onEdit = { if (!state.uncertain) editing = line })
                }
            }
            else -> Spacer(Modifier.weight(1f))
        }

        val count = order?.lines?.count { state.arrived(it.productId).signum() > 0 } ?: 0
        SubmitBar(
            text = stringResource(R.string.submit_receiving) + if (count == 0) "" else " · " + pluralStringResource(R.plurals.lines_count, count, count),
            color = CxSuccess,
            enabled = state.anything,
            submitting = state.submitting,
            uncertain = state.uncertain,
            onSubmit = viewModel::submit,
            onQueue = { viewModel.queue(listOfNotNull(title, state.task?.poNumber).joinToString(" · "), state.warehouse?.name.orEmpty()) },
            onUnlock = viewModel::unlock,
            onNote = { editNote = true },
            hasNote = state.note.isNotBlank(),
        )
    }

    if (confirmLeave) DiscardDialog(state.arrivals.size, onDiscard = { confirmLeave = false; viewModel.discard(); viewModel.backToList() }, onDismiss = { confirmLeave = false })
    editing?.let { line ->
        AmountDialog(line.productName, line.unit, state.arrived(line.productId), allowZero = true, onConfirm = { viewModel.setTotal(line, it); editing = null }, onDismiss = { editing = null })
    }
    if (editNote) NoteInputDialog(state.note, onConfirm = { viewModel.setNote(it); editNote = false }, onDismiss = { editNote = false })
    if (camera) CameraScanDialog(onCode = { camera = false; viewModel.onScan(it) }, onDismiss = { camera = false })
}

@Composable
private fun ReceiveLineCard(line: ReceiveLine, state: ReceivingUiState, highlighted: Boolean, onEdit: () -> Unit) {
    val arrived = state.arrived(line.productId)
    val remaining = state.remaining(line)
    val done = arrived.signum() > 0 && arrived.compareTo(remaining) == 0
    val over = arrived > remaining
    CxCard(onClick = onEdit, border = when {
        over -> CxOrange.copy(alpha = 0.7f)
        done -> CxSuccess.copy(alpha = 0.6f)
        highlighted -> CxSuccess.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outline
    }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(line.productName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(line.sku, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
            if (done) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CxSuccess)
            if (over) Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CxOrange)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.receiving_ordered_received, formatQuantity(line.ordered), formatQuantity(line.received)),
                style = MaterialTheme.typography.bodyMedium,
                color = CxMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatQuantity(arrived)} / ${formatQuantity(remaining)} ${line.unit.orEmpty()}".trim(),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    over -> CxOrange
                    done -> CxSuccess
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        val shelves = state.arrivals[line.productId].orEmpty()
        if (shelves.isNotEmpty()) {
            Text(
                stringResource(R.string.receiving_put_on, shelves.joinToString(", ") { "${it.locationCode ?: "—"}: ${formatQuantity(it.amount)}" }),
                style = MaterialTheme.typography.bodyMedium,
                color = CxMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
