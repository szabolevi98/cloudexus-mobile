package net.levente.cloudexus.mobile.ui.booking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.api.Location
import net.levente.cloudexus.mobile.data.api.Product
import net.levente.cloudexus.mobile.data.api.Warehouse
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.data.work.Outbox
import net.levente.cloudexus.mobile.data.work.QueuedBooking
import net.levente.cloudexus.mobile.data.work.WorkStore
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.components.Signal
import net.levente.cloudexus.mobile.ui.toUiText
import java.math.BigDecimal
import java.util.UUID

enum class BookingStep { WAREHOUSE, TARGET_WAREHOUSE, SCAN, DONE }

/** What the last scan did, for the card under the scan field. */
sealed interface ScanFeedback {
    /** [inWarehouse] is the product's stock in the (source) warehouse, [short] whether the draft now asks for more. */
    data class Added(val product: Product, val inWarehouse: BigDecimal, val drafted: BigDecimal, val short: Boolean) : ScanFeedback
    data class LocationChanged(val location: Location, val target: Boolean) : ScanFeedback
    data class NotFound(val code: String) : ScanFeedback
    data class Failed(val message: UiText) : ScanFeedback
}

/** A list being scanned, as it is kept on the device between app starts. */
@Serializable
data class SavedBooking(
    val warehouseId: Int,
    val toWarehouseId: Int? = null,
    val location: Location? = null,
    val toLocation: Location? = null,
    val note: String = "",
    val lines: List<SavedLine>,
    /** The Idempotency-Key of an unanswered send, and that it was unanswered. */
    val key: String? = null,
    val uncertain: Boolean = false,
)

@Serializable
data class SavedLine(
    val productId: Int,
    val sku: String,
    val name: String,
    val unit: String? = null,
    val from: Location? = null,
    val to: Location? = null,
    val quantity: String,
)

data class BookingUiState(
    val mode: BookingMode,
    val step: BookingStep = BookingStep.WAREHOUSE,
    val warehouses: List<Warehouse> = emptyList(),
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val warehouse: Warehouse? = null,
    val toWarehouse: Warehouse? = null,
    val locations: List<Location> = emptyList(),
    val toLocations: List<Location> = emptyList(),
    val location: Location? = null,
    val toLocation: Location? = null,
    val draft: Draft = Draft(),
    val feedback: ScanFeedback? = null,
    val lookingUp: Boolean = false,
    val note: String = "",
    val submitting: Boolean = false,
    val submitError: UiText? = null,
    /** Server-side problems per draft line key. */
    val lineErrors: Map<String, UiText> = emptyMap(),
    /**
     * The last submit failed without an answer, so the booking may already be
     * on the server. The draft is locked until the worker retries (same
     * Idempotency-Key, never books twice), queues it, or explicitly chooses to edit.
     */
    val uncertain: Boolean = false,
    val bookedLines: Int = 0,
    /** Put in the queue rather than booked: it goes when the network comes back. */
    val queued: Boolean = false,
    /** A list left from before, waiting for the worker to continue or drop it. */
    val resume: SavedBooking? = null,
    /** Beep on a scan (the settings). */
    val sound: Boolean = true,
) {
    /** Stock-out, transfer and relocation are checked against stock; stock-in is not. */
    val checksStock: Boolean get() = mode != BookingMode.IN

    /** Transfers and relocations have a target shelf. */
    val hasTarget: Boolean get() = mode == BookingMode.TRANSFER || mode == BookingMode.RELOCATE
}

class BookingViewModel(
    mode: BookingMode,
    private val api: ApiClient,
    private val sessions: SessionManager,
    private val work: WorkStore,
    private val outbox: Outbox,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(BookingUiState(mode))
    val state: StateFlow<BookingUiState> = _state.asStateFlow()

    private val _signals = Channel<Signal>(Channel.BUFFERED)
    val signals = _signals.receiveAsFlow()

    /** Stock of each scanned product in the source warehouse, for the "not enough" hint. */
    private val available = mutableMapOf<Int, BigDecimal>()

    /** Kept across retries of one booking; see [BookingUiState.uncertain]. */
    private var idempotencyKey: String? = null

    /** A relocation's source shelf was the last thing scanned: the next shelf is its target. */
    private var sourceJustScanned = false

    private val server: String? get() = sessions.current?.baseUrl
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            server?.let { s -> work.prefs(s).collect { prefs -> _state.update { it.copy(sound = prefs.sound) } } }
        }
        loadWarehouses()
    }

    fun loadWarehouses() {
        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val warehouses = api.warehouses(connection)
                _state.update { it.copy(warehouses = warehouses, loading = false) }
                start(warehouses)
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(loading = false, loadError = e.toUiText()) }
            }
        }
    }

    /**
     * Where a booking starts: a list left from before, offered to continue;
     * otherwise the device's warehouse — the one fixed in Settings, or the
     * last one used — without asking, as a PDA rarely leaves its warehouse.
     */
    private suspend fun start(warehouses: List<Warehouse>) {
        val server = server ?: return
        if (state.value.warehouse != null) return

        val saved = work.draft(server, state.value.mode.name)
            ?.let { runCatching { json.decodeFromString(SavedBooking.serializer(), it) }.getOrNull() }
        if (saved != null && saved.lines.isNotEmpty() && warehouses.any { it.id == saved.warehouseId }) {
            _state.update { it.copy(resume = saved) }
            return
        }

        val startId = work.current(server).startWarehouseId ?: return
        warehouses.firstOrNull { it.id == startId }?.let(::selectWarehouse)
    }

    /** The worker continues the list left from before. */
    fun resumeSaved() {
        val saved = state.value.resume ?: return
        val warehouse = state.value.warehouses.firstOrNull { it.id == saved.warehouseId } ?: return dropSaved()
        val toWarehouse = saved.toWarehouseId?.let { id -> state.value.warehouses.firstOrNull { it.id == id } }
        idempotencyKey = saved.key
        val draft = Draft(saved.lines.mapNotNull { line ->
            val quantity = line.quantity.toBigDecimalOrNull() ?: return@mapNotNull null
            DraftLine(line.productId, line.sku, line.name, line.unit, line.from, line.to, quantity)
        })
        _state.update {
            it.copy(
                resume = null,
                warehouse = warehouse,
                toWarehouse = toWarehouse,
                location = saved.location,
                toLocation = saved.toLocation,
                note = saved.note,
                draft = draft,
                uncertain = saved.uncertain,
                submitError = if (saved.uncertain) UiText.Res(R.string.booking_uncertain) else null,
                step = BookingStep.SCAN,
            )
        }
        loadLocations(warehouse, target = false)
        if (toWarehouse != null && toWarehouse.id != warehouse.id) loadLocations(toWarehouse, target = true)
    }

    /** The worker drops the list left from before. */
    fun dropSaved() {
        _state.update { it.copy(resume = null) }
        persist(clear = true)
        viewModelScope.launch {
            val server = server ?: return@launch
            val startId = work.current(server).startWarehouseId ?: return@launch
            state.value.warehouses.firstOrNull { it.id == startId }?.let(::selectWarehouse)
        }
    }

    fun selectWarehouse(warehouse: Warehouse) {
        available.clear()
        val next = if (state.value.mode == BookingMode.TRANSFER) BookingStep.TARGET_WAREHOUSE else BookingStep.SCAN
        _state.update {
            it.copy(
                warehouse = warehouse,
                location = null,
                locations = emptyList(),
                // A relocation stays in one warehouse: its target shelves are the same ones.
                toWarehouse = if (it.mode == BookingMode.RELOCATE) warehouse else it.toWarehouse,
                toLocation = null,
                step = next,
            )
        }
        viewModelScope.launch { server?.let { work.rememberWarehouse(it, warehouse.id) } }
        loadLocations(warehouse, target = false, restoreLast = true)
    }

    fun selectTargetWarehouse(warehouse: Warehouse) {
        _state.update { it.copy(toWarehouse = warehouse, toLocation = null, toLocations = emptyList(), step = BookingStep.SCAN) }
        loadLocations(warehouse, target = true)
    }

    /**
     * The warehouse's shelves, for the picker and for recognising a scanned
     * shelf label. Without them the app still works: every code is then looked
     * up as a product, and the location stays "none". With [restoreLast], the
     * shelf last worked at in this warehouse is taken up again.
     */
    private fun loadLocations(warehouse: Warehouse, target: Boolean, restoreLast: Boolean = false) {
        val connection = sessions.current?.connection() ?: return
        viewModelScope.launch {
            try {
                val locations = api.locations(connection, warehouse.id)
                val last = if (restoreLast) server?.let { work.lastLocation(it, state.value.mode.name, warehouse.id) } else null
                _state.update {
                    when {
                        !target && it.warehouse?.id == warehouse.id -> it.copy(
                            locations = locations,
                            toLocations = if (it.mode == BookingMode.RELOCATE) locations else it.toLocations,
                            location = it.location ?: locations.firstOrNull { l -> l.id == last },
                        )
                        target && it.toWarehouse?.id == warehouse.id -> it.copy(toLocations = locations)
                        else -> it
                    }
                }
            } catch (e: ApiException) {
                handle(e)
            }
        }
    }

    /** Back from the scan step to the warehouse choice; only offered while the draft is empty. */
    fun changeWarehouses() {
        _state.update { it.copy(step = BookingStep.WAREHOUSE, feedback = null) }
    }

    fun backToSourceWarehouse() {
        _state.update { it.copy(step = BookingStep.WAREHOUSE) }
    }

    fun setLocation(location: Location?) {
        _state.update { it.copy(location = location) }
        rememberLocation(location)
        persist()
    }

    fun setTargetLocation(location: Location?) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { it.copy(toLocation = location, draft = it.draft.withTarget(location), lineErrors = emptyMap()) }
        persist()
    }

    fun setNote(note: String) {
        _state.update { it.copy(note = note.take(200)) }
        persist()
    }

    /**
     * A scanned or typed code. A shelf label switches the location for the
     * following scans: of a transfer, the source warehouse's first and the
     * target's second; of a relocation, the source while nothing is on the
     * list and the target after. Anything else is looked up as a product and
     * added with quantity 1.
     */
    fun onScan(rawCode: String) {
        val code = rawCode.trim()
        val current = state.value
        if (code.isEmpty() || current.step != BookingStep.SCAN || current.lookingUp || current.submitting || current.uncertain) return

        val shelf = current.locations.firstOrNull { it.code.equals(code, ignoreCase = true) }
        // A relocation can be scanned either way: source, target, products — or
        // source, products, target. A second shelf scanned right after the source is the target.
        val relocateTarget = current.mode == BookingMode.RELOCATE && shelf != null &&
            (!current.draft.isEmpty || (sourceJustScanned && shelf.id != current.location?.id))
        sourceJustScanned = false
        if (shelf != null && !relocateTarget) {
            if (current.mode == BookingMode.RELOCATE) {
                if (current.toLocation != null) setTargetLocation(null)
                sourceJustScanned = true
            }
            setLocation(shelf)
            _state.update { it.copy(feedback = ScanFeedback.LocationChanged(shelf, target = false)) }
            _signals.trySend(Signal.OK)
            return
        }
        if (current.hasTarget) {
            current.toLocations.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { location ->
                setTargetLocation(location)
                _state.update { it.copy(feedback = ScanFeedback.LocationChanged(location, target = true)) }
                _signals.trySend(Signal.OK)
                return
            }
        }

        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(lookingUp = true) }
        viewModelScope.launch {
            try {
                val product = api.lookup(connection, code)
                if (product == null) {
                    _state.update { it.copy(lookingUp = false, feedback = ScanFeedback.NotFound(code)) }
                    _signals.trySend(Signal.ERROR)
                    return@launch
                }
                val warehouseId = state.value.warehouse?.id
                // A relocation takes from one shelf: what that shelf holds is what counts.
                val rows = product.stock.filter { it.warehouseId == warehouseId }
                    .filter { state.value.mode != BookingMode.RELOCATE || it.locationId == state.value.location?.id }
                val inWarehouse = rows.fold(BigDecimal.ZERO) { sum, row -> sum + (row.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
                available[product.id] = inWarehouse

                var short = false
                _state.update {
                    val draft = it.draft.add(product, it.location, if (it.hasTarget) it.toLocation else null)
                    val drafted = draft.totalFor(product.id)
                    short = it.checksStock && drafted > inWarehouse
                    it.copy(
                        draft = draft,
                        lookingUp = false,
                        feedback = ScanFeedback.Added(product, inWarehouse, drafted, short),
                        submitError = null,
                    )
                }
                idempotencyKey = null
                persist()
                _signals.trySend(if (short) Signal.WARN else Signal.OK)
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(lookingUp = false, feedback = ScanFeedback.Failed(e.toUiText())) }
                _signals.trySend(Signal.ERROR)
            }
        }
    }

    fun setQuantity(key: String, quantity: BigDecimal) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { it.copy(draft = it.draft.setQuantity(key, quantity), lineErrors = it.lineErrors - key, submitError = null) }
        persist()
    }

    fun step(key: String, delta: Int) {
        val line = state.value.draft.lines.firstOrNull { it.key == key } ?: return
        setQuantity(key, line.quantity + BigDecimal(delta))
    }

    fun remove(key: String) = setQuantity(key, BigDecimal.ZERO)

    /** Whether the drafted amount of a product is more than its stock in the source warehouse. */
    fun isShort(productId: Int): Boolean {
        val current = state.value
        if (!current.checksStock) return false
        val stock = available[productId] ?: return false
        return current.draft.totalFor(productId) > stock
    }

    fun submit() {
        val current = state.value
        val warehouse = current.warehouse ?: return
        val connection = sessions.current?.connection() ?: return
        if (current.draft.isEmpty || current.submitting) return

        val key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }
        val body = current.draft.requestBody(current.mode, warehouse, current.toWarehouse, current.note)
        _state.update { it.copy(submitting = true, submitError = null, lineErrors = emptyMap()) }

        viewModelScope.launch {
            try {
                val receipt = api.book(connection, current.mode.path, body, key)
                idempotencyKey = null
                persist(clear = true)
                _state.update {
                    it.copy(submitting = false, uncertain = false, step = BookingStep.DONE, bookedLines = receipt.data.lineCount, queued = false)
                }
            } catch (e: ApiException.Network) {
                // Maybe booked: keep the key, lock the draft, offer a retry or the queue.
                _state.update { it.copy(submitting = false, uncertain = true, submitError = UiText.Res(R.string.booking_uncertain)) }
                persist()
            } catch (e: ApiException.Http) {
                idempotencyKey = null
                val shortages = e.shortages().size
                val problems = e.lineProblems().size
                val message = when {
                    shortages > 0 -> UiText.Res(R.string.booking_rejected_short, listOf(shortages))
                    problems > 0 -> UiText.Res(R.string.booking_rejected_lines, listOf(problems))
                    else -> e.toUiText()
                }
                _state.update { it.copy(submitting = false, uncertain = false, submitError = message, lineErrors = lineErrors(e, it.draft)) }
                persist()
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(submitting = false, submitError = e.toUiText()) }
            }
        }
    }

    /**
     * No network: the booking goes into the queue, with the key of any
     * unanswered attempt, and is sent when the network comes back.
     */
    fun queue(title: String, summary: String) {
        val current = state.value
        val warehouse = current.warehouse ?: return
        val session = sessions.current ?: return
        if (current.draft.isEmpty || current.submitting) return

        val key = idempotencyKey ?: UUID.randomUUID().toString()
        val body = current.draft.requestBody(current.mode, warehouse, current.toWarehouse, current.note)
        outbox.enqueue(
            QueuedBooking(
                key = key,
                server = session.baseUrl,
                username = session.user.username,
                path = current.mode.path,
                body = body.toString(),
                title = title,
                summary = summary,
                createdAt = System.currentTimeMillis(),
            ),
        )
        idempotencyKey = null
        persist(clear = true)
        _state.update { it.copy(uncertain = false, submitError = null, step = BookingStep.DONE, bookedLines = current.draft.lines.size, queued = true) }
    }

    /** The worker chose to change the draft after an unanswered submit, accepting that it may already be booked. */
    fun unlockAfterUncertain() {
        idempotencyKey = null
        _state.update { it.copy(uncertain = false, submitError = null) }
        persist()
    }

    /** The worker left the list on purpose: it is not offered again. */
    fun discard() {
        persist(clear = true)
    }

    /** After a successful booking: an empty draft for the same warehouse(s) and location(s). */
    fun startOver() {
        available.clear()
        _state.update {
            it.copy(step = BookingStep.SCAN, draft = Draft(), feedback = null, note = "", bookedLines = 0, submitError = null, lineErrors = emptyMap(), queued = false)
        }
    }

    private fun rememberLocation(location: Location?) {
        val warehouse = state.value.warehouse ?: return
        viewModelScope.launch { server?.let { work.rememberLocation(it, state.value.mode.name, warehouse.id, location?.id) } }
    }

    /**
     * Keeps the list on the device, so closing the app — or Android closing
     * it — does not lose it; a moment after the last change, so a burst of
     * scans is one write.
     */
    private fun persist(clear: Boolean = false) {
        val server = server ?: return
        val current = state.value
        val warehouse = current.warehouse
        val saved = if (clear || warehouse == null || current.draft.isEmpty) null else SavedBooking(
            warehouseId = warehouse.id,
            toWarehouseId = current.toWarehouse?.id,
            location = current.location,
            toLocation = current.toLocation,
            note = current.note,
            lines = current.draft.lines.map { SavedLine(it.productId, it.sku, it.name, it.unit, it.from, it.to, it.quantity.toPlainString()) },
            key = idempotencyKey,
            uncertain = current.uncertain,
        )
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (saved != null) delay(300)
            work.saveDraft(server, current.mode.name, saved?.let { json.encodeToString(SavedBooking.serializer(), it) })
        }
    }

    /** Maps the server's per-item details back onto draft lines, which were sent in draft order. */
    private fun lineErrors(e: ApiException.Http, draft: Draft): Map<String, UiText> {
        val errors = mutableMapOf<String, UiText>()
        e.lineProblems().forEach { (index, message) ->
            draft.lines.getOrNull(index)?.let { errors[it.key] = UiText.Raw(message) }
        }
        e.shortages().forEach { (productId, amounts) ->
            available[productId] = amounts.first.toBigDecimalOrNull() ?: BigDecimal.ZERO
            draft.lines.filter { it.productId == productId }.forEach {
                errors[it.key] = UiText.Res(R.string.booking_short_line, listOf(formatQuantity(amounts.first), formatQuantity(amounts.second)))
            }
        }
        return errors
    }

    private fun handle(e: ApiException) {
        if (e is ApiException.Unauthorized) sessions.expire()
        // The role changed on the web: learn it, so the home screen stops offering bookings.
        if (e is ApiException.Http && e.status == 403) sessions.refreshUser()
    }
}
