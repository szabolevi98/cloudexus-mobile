package net.levente.cloudexus.mobile.ui.booking

import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.Location
import net.levente.cloudexus.mobile.data.api.Warehouse
import net.levente.cloudexus.mobile.data.scanner.ScannerBroadcastEffect
import net.levente.cloudexus.mobile.data.scanner.ScannerConfig
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.components.CxButton
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.EmptyState
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.components.Pill
import net.levente.cloudexus.mobile.ui.components.QuantityStepper
import net.levente.cloudexus.mobile.ui.components.ScanField
import net.levente.cloudexus.mobile.ui.components.SectionLabel
import net.levente.cloudexus.mobile.ui.scan.CameraScanDialog
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxDangerSoft
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxWarning
import net.levente.cloudexus.mobile.ui.theme.CxWarningSoft
import java.math.BigDecimal

@Composable
fun BookingScreen(viewModel: BookingViewModel, scanner: ScannerConfig, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScanSignals(viewModel)

    when (state.step) {
        BookingStep.WAREHOUSE -> {
            BackHandler(onBack = onExit)
            WarehouseStep(
                state = state,
                title = stringResource(if (state.mode == BookingMode.TRANSFER) R.string.pick_source_warehouse else R.string.pick_warehouse),
                exclude = null,
                onSelect = viewModel::selectWarehouse,
                onRetry = viewModel::loadWarehouses,
                onBack = onExit,
            )
        }
        BookingStep.TARGET_WAREHOUSE -> {
            BackHandler(onBack = viewModel::backToSourceWarehouse)
            WarehouseStep(
                state = state,
                title = stringResource(R.string.pick_target_warehouse),
                exclude = state.warehouse,
                onSelect = viewModel::selectTargetWarehouse,
                onRetry = viewModel::loadWarehouses,
                onBack = viewModel::backToSourceWarehouse,
            )
        }
        BookingStep.SCAN -> ScanStep(state, viewModel, scanner, onExit)
        BookingStep.DONE -> {
            BackHandler(onBack = onExit)
            DoneStep(state, onAgain = viewModel::startOver, onExit = onExit)
        }
    }
}

/** A short beep and a tick for a taken scan, a low tone and a buzz for a rejected one: the worker is looking at the goods, not the screen. */
@Composable
private fun ScanSignals(viewModel: BookingViewModel) {
    val haptics = LocalHapticFeedback.current
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }
    DisposableEffect(tones) { onDispose { tones?.release() } }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BookingEvent.Scanned -> {
                    haptics.performHapticFeedback(if (event.ok) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)
                    tones?.startTone(if (event.ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, if (event.ok) 90 else 250)
                }
            }
        }
    }
}

@Composable
private fun WarehouseStep(
    state: BookingUiState,
    title: String,
    exclude: Warehouse?,
    onSelect: (Warehouse) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val style = state.mode.style
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CxHeader(title = stringResource(style.title), subtitle = title, onBack = onBack)
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError != null -> ErrorPanel(state.loadError, onRetry)
            else -> {
                val warehouses = state.warehouses.filter { it.id != exclude?.id }
                if (warehouses.isEmpty()) {
                    EmptyState(Icons.Rounded.Warehouse, stringResource(R.string.no_warehouses_title), stringResource(R.string.no_warehouses_text))
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    if (exclude != null) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(stringResource(R.string.transfer_from))
                                Spacer(Modifier.width(8.dp))
                                Text(exclude.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    items(warehouses, key = { it.id }) { warehouse ->
                        CxCard(onClick = { onSelect(warehouse) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(Icons.Rounded.Warehouse, style.color, soft = true)
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
    }
}

@Composable
private fun ScanStep(state: BookingUiState, viewModel: BookingViewModel, scanner: ScannerConfig, onExit: () -> Unit) {
    val style = state.mode.style
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickLocation by rememberSaveable { mutableStateOf<Boolean?>(null) } // false: source, true: target
    var editing by remember { mutableStateOf<DraftLine?>(null) }
    var editNote by rememberSaveable { mutableStateOf(false) }
    var camera by rememberSaveable { mutableStateOf(false) }
    val dialogOpen = confirmDiscard || pickLocation != null || editing != null || editNote || camera

    ScannerBroadcastEffect(scanner) { code -> if (!dialogOpen) viewModel.onScan(code) }
    BackHandler { if (state.draft.isEmpty) onExit() else confirmDiscard = true }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        CxHeader(
            title = stringResource(style.title),
            subtitle = if (state.mode == BookingMode.TRANSFER) "${state.warehouse?.name} → ${state.toWarehouse?.name}" else state.warehouse?.name,
            onBack = { if (state.draft.isEmpty) onExit() else confirmDiscard = true },
            actions = {
                if (state.draft.isEmpty) {
                    IconButton(onClick = viewModel::changeWarehouses) {
                        Icon(Icons.Rounded.Warehouse, stringResource(R.string.change_warehouse), tint = Color.White)
                    }
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Pill(
                    text = state.location?.code ?: stringResource(R.string.no_location),
                    icon = Icons.Rounded.Place,
                    onClick = { pickLocation = false },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.mode == BookingMode.TRANSFER) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
                    Pill(
                        text = state.toLocation?.code ?: stringResource(R.string.no_location),
                        icon = Icons.Rounded.Place,
                        onClick = { pickLocation = true },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScanField(
                    onScan = viewModel::onScan,
                    onCamera = { camera = true },
                    busy = state.lookingUp,
                    focusKey = dialogOpen,
                )
            }
            state.feedback?.let { feedback -> item { FeedbackCard(feedback, state.mode) } }
            state.submitError?.let { error -> item { Banner(error, uncertain = state.uncertain) } }

            if (state.draft.isEmpty) {
                item {
                    EmptyState(
                        Icons.Rounded.QrCodeScanner,
                        stringResource(R.string.empty_draft_title),
                        stringResource(R.string.empty_draft_text),
                        Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item {
                    SectionLabel(pluralStringResource(R.plurals.lines_count, state.draft.lines.size, state.draft.lines.size), Modifier.padding(top = 4.dp))
                }
                items(state.draft.lines, key = { it.key }) { line ->
                    LineCard(
                        line = line,
                        mode = state.mode,
                        error = state.lineErrors[line.key],
                        short = viewModel.isShort(line.productId),
                        locked = state.uncertain || state.submitting,
                        onMinus = { viewModel.step(line.key, -1) },
                        onPlus = { viewModel.step(line.key, +1) },
                        onEdit = { editing = line },
                        onRemove = { viewModel.remove(line.key) },
                    )
                }
            }
        }

        BottomBar(
            state = state,
            onSubmit = viewModel::submit,
            onUnlock = viewModel::unlockAfterUncertain,
            onNote = { editNote = true },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(pluralStringResource(R.plurals.discard_text, state.draft.lines.size, state.draft.lines.size)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onExit() }) { Text(stringResource(R.string.discard_confirm), color = CxDanger) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pickLocation?.let { target ->
        LocationPicker(
            hint = stringResource(if (target) R.string.pick_target_location_hint else R.string.pick_location_hint),
            locations = if (target) state.toLocations else state.locations,
            selected = if (target) state.toLocation else state.location,
            onPick = { location ->
                if (target) viewModel.setTargetLocation(location) else viewModel.setLocation(location)
                pickLocation = null
            },
            onDismiss = { pickLocation = null },
        )
    }

    editing?.let { line ->
        QuantityDialog(
            line = line,
            onConfirm = { quantity -> viewModel.setQuantity(line.key, quantity); editing = null },
            onDismiss = { editing = null },
        )
    }

    if (editNote) {
        NoteDialog(state.note, onConfirm = { viewModel.setNote(it); editNote = false }, onDismiss = { editNote = false })
    }

    if (camera) {
        CameraScanDialog(onCode = { code -> camera = false; viewModel.onScan(code) }, onDismiss = { camera = false })
    }
}

@Composable
private fun FeedbackCard(feedback: ScanFeedback, mode: BookingMode) {
    when (feedback) {
        is ScanFeedback.Added -> CxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Inventory2, mode.style.color)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(feedback.product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(feedback.product.sku, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.stock_here), style = MaterialTheme.typography.labelSmall, color = CxMuted)
                    Text(
                        "${formatQuantity(feedback.inWarehouse)} ${feedback.product.unit.orEmpty()}".trim(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (feedback.short) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().background(CxWarningSoft, MaterialTheme.shapes.small).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CxWarning)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.stock_short_hint, formatQuantity(feedback.drafted), formatQuantity(feedback.inWarehouse)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is ScanFeedback.LocationChanged -> CxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Place, MaterialTheme.colorScheme.primary, soft = true)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        stringResource(if (feedback.target) R.string.target_location_set else R.string.location_set, feedback.location.code),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        feedback.location.name ?: stringResource(R.string.location_set_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CxMuted,
                    )
                }
            }
        }
        is ScanFeedback.NotFound -> ProblemCard(stringResource(R.string.code_not_found_title), stringResource(R.string.code_not_found_text, feedback.code))
        is ScanFeedback.Failed -> ProblemCard(stringResource(R.string.lookup_failed), feedback.message.asString())
    }
}

@Composable
private fun ProblemCard(title: String, text: String) {
    CxCard(border = CxDanger.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.ErrorOutline, CxDanger, soft = true)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
        }
    }
}

@Composable
private fun Banner(text: UiText, uncertain: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (uncertain) CxWarningSoft else CxDangerSoft, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (uncertain) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline, contentDescription = null, tint = if (uncertain) CxWarning else CxDanger)
        Spacer(Modifier.width(10.dp))
        Text(text.asString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LineCard(
    line: DraftLine,
    mode: BookingMode,
    error: UiText?,
    short: Boolean,
    locked: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    CxCard(border = if (error != null) CxDanger.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline, contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val where = when (mode) {
                    BookingMode.TRANSFER -> "${line.from?.code ?: "—"} → ${line.to?.code ?: "—"}"
                    else -> line.from?.code ?: stringResource(R.string.no_location)
                }
                Text("${line.sku} · $where", style = MaterialTheme.typography.bodyMedium, color = CxMuted)
            }
            IconButton(onClick = onRemove, enabled = !locked) {
                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.remove_line), tint = CxMuted)
            }
        }
        if (error != null) {
            Text(error.asString(), style = MaterialTheme.typography.bodyMedium, color = CxDanger, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
        } else if (short) {
            Text(stringResource(R.string.line_short), style = MaterialTheme.typography.bodyMedium, color = CxWarning, modifier = Modifier.padding(top = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(line.unit.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = CxMuted, modifier = Modifier.weight(1f))
            if (locked) {
                Text(formatQuantity(line.quantity), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            } else {
                QuantityStepper(formatQuantity(line.quantity), onMinus, onPlus, onEdit)
            }
        }
    }
}

@Composable
private fun BottomBar(state: BookingUiState, onSubmit: () -> Unit, onUnlock: () -> Unit, onNote: () -> Unit) {
    val style = state.mode.style
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (state.uncertain) {
                CxButton(
                    text = stringResource(R.string.retry_submit),
                    onClick = onSubmit,
                    loading = state.submitting,
                    color = style.color,
                    icon = Icons.Rounded.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onUnlock, enabled = !state.submitting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.edit_anyway))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNote, enabled = !state.submitting) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Notes,
                            stringResource(R.string.note),
                            tint = if (state.note.isNotBlank()) MaterialTheme.colorScheme.primary else CxMuted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CxButton(
                        text = stringResource(style.submit) + if (state.draft.isEmpty) "" else " · " +
                            pluralStringResource(R.plurals.lines_short, state.draft.lines.size, state.draft.lines.size),
                        onClick = onSubmit,
                        enabled = !state.draft.isEmpty,
                        loading = state.submitting,
                        color = style.color,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneStep(state: BookingUiState, onAgain: () -> Unit, onExit: () -> Unit) {
    val style = state.mode.style
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        CxHeader(title = stringResource(style.title), subtitle = state.warehouse?.name)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = style.color, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(style.done), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val where = if (state.mode == BookingMode.TRANSFER) "${state.warehouse?.name} → ${state.toWarehouse?.name}" else state.warehouse?.name.orEmpty()
            Text(
                pluralStringResource(R.plurals.done_summary, state.bookedLines, state.bookedLines, where),
                style = MaterialTheme.typography.bodyLarge,
                color = CxMuted,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CxButton(stringResource(style.again), onClick = onAgain, color = style.color, icon = style.icon, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.back_to_home), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ErrorPanel(error: UiText, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconTile(Icons.Rounded.CloudOff, CxDanger, size = 64.dp, soft = true)
        Spacer(Modifier.height(16.dp))
        Text(error.asString(), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        CxButton(stringResource(R.string.retry), onClick = onRetry, icon = Icons.Rounded.Refresh)
    }
}

@Composable
private fun QuantityDialog(line: DraftLine, onConfirm: (BigDecimal) -> Unit, onDismiss: () -> Unit) {
    // Opens with the current amount selected, so typing replaces it.
    // Plain digits: a grouped "1,000" would read back as a decimal comma.
    val initial = line.quantity.stripTrailingZeros().toPlainString()
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val parsed = parseQuantity(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.quantity_label, line.unit.orEmpty())) },
                isError = parsed == null,
                supportingText = { if (parsed == null) Text(stringResource(R.string.quantity_invalid)) },
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
private fun NoteDialog(note: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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

@Composable
private fun LocationPicker(hint: String, locations: List<Location>, selected: Location?, onPick: (Location?) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = locations.filter {
        query.isBlank() || it.code.contains(query.trim(), ignoreCase = true) || it.name.orEmpty().contains(query.trim(), ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_location)) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    item { LocationRow(stringResource(R.string.no_location), null, selected == null) { onPick(null) } }
                    items(filtered, key = { it.id }) { location ->
                        LocationRow(location.code, location.name, selected?.id == location.id) { onPick(location) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LocationRow(code: String, name: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(code, style = MaterialTheme.typography.titleSmall)
            if (!name.isNullOrBlank()) Text(name, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
        }
    }
}
