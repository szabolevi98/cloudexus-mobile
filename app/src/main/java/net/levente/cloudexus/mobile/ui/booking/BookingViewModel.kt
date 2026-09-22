package net.levente.cloudexus.mobile.ui.booking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.api.Location
import net.levente.cloudexus.mobile.data.api.Product
import net.levente.cloudexus.mobile.data.api.Warehouse
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.ui.UiText
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

sealed interface BookingEvent {
    /** A scan was taken (true) or rejected (false): the screen beeps or buzzes. */
    data class Scanned(val ok: Boolean) : BookingEvent
}

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
     * Idempotency-Key, never books twice) or explicitly chooses to edit.
     */
    val uncertain: Boolean = false,
    val bookedLines: Int = 0,
) {
    /** Stock-out and transfer are checked against stock; stock-in is not. */
    val checksStock: Boolean get() = mode != BookingMode.IN
}

class BookingViewModel(
    mode: BookingMode,
    private val api: ApiClient,
    private val sessions: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BookingUiState(mode))
    val state: StateFlow<BookingUiState> = _state.asStateFlow()

    private val _events = Channel<BookingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Stock of each scanned product in the source warehouse, for the "not enough" hint. */
    private val available = mutableMapOf<Int, BigDecimal>()

    /** Kept across retries of one booking; see [BookingUiState.uncertain]. */
    private var idempotencyKey: String? = null

    init {
        loadWarehouses()
    }

    fun loadWarehouses() {
        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val warehouses = api.warehouses(connection)
                _state.update { it.copy(warehouses = warehouses, loading = false) }
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(loading = false, loadError = e.toUiText()) }
            }
        }
    }

    fun selectWarehouse(warehouse: Warehouse) {
        available.clear()
        val next = if (state.value.mode == BookingMode.TRANSFER) BookingStep.TARGET_WAREHOUSE else BookingStep.SCAN
        _state.update { it.copy(warehouse = warehouse, location = null, locations = emptyList(), step = next) }
        loadLocations(warehouse, target = false)
    }

    fun selectTargetWarehouse(warehouse: Warehouse) {
        _state.update { it.copy(toWarehouse = warehouse, toLocation = null, toLocations = emptyList(), step = BookingStep.SCAN) }
        loadLocations(warehouse, target = true)
    }

    /**
     * The warehouse's shelves, for the picker and for recognising a scanned
     * shelf label. Without them the app still works: every code is then looked
     * up as a product, and the location stays "none".
     */
    private fun loadLocations(warehouse: Warehouse, target: Boolean) {
        val connection = sessions.current?.connection() ?: return
        viewModelScope.launch {
            try {
                val locations = api.locations(connection, warehouse.id)
                _state.update {
                    when {
                        target && it.toWarehouse?.id == warehouse.id -> it.copy(toLocations = locations)
                        !target && it.warehouse?.id == warehouse.id -> it.copy(locations = locations)
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
    }

    fun setTargetLocation(location: Location?) {
        _state.update { it.copy(toLocation = location) }
    }

    fun setNote(note: String) {
        _state.update { it.copy(note = note.take(200)) }
    }

    /**
     * A scanned or typed code. A shelf label of the (source or target)
     * warehouse switches the location for the following scans; anything else
     * is looked up as a product and added with quantity 1.
     */
    fun onScan(rawCode: String) {
        val code = rawCode.trim()
        val current = state.value
        if (code.isEmpty() || current.step != BookingStep.SCAN || current.lookingUp || current.submitting || current.uncertain) return

        current.locations.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { location ->
            _state.update { it.copy(location = location, feedback = ScanFeedback.LocationChanged(location, target = false)) }
            _events.trySend(BookingEvent.Scanned(true))
            return
        }
        if (current.mode == BookingMode.TRANSFER) {
            current.toLocations.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { location ->
                _state.update { it.copy(toLocation = location, feedback = ScanFeedback.LocationChanged(location, target = true)) }
                _events.trySend(BookingEvent.Scanned(true))
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
                    _events.trySend(BookingEvent.Scanned(false))
                    return@launch
                }
                val warehouseId = state.value.warehouse?.id
                val inWarehouse = product.stock
                    .filter { it.warehouseId == warehouseId }
                    .fold(BigDecimal.ZERO) { sum, row -> sum + (row.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
                available[product.id] = inWarehouse

                _state.update {
                    val draft = it.draft.add(product, it.location, if (it.mode == BookingMode.TRANSFER) it.toLocation else null)
                    val drafted = draft.totalFor(product.id)
                    it.copy(
                        draft = draft,
                        lookingUp = false,
                        feedback = ScanFeedback.Added(product, inWarehouse, drafted, it.checksStock && drafted > inWarehouse),
                        submitError = null,
                    )
                }
                idempotencyKey = null
                _events.trySend(BookingEvent.Scanned(true))
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(lookingUp = false, feedback = ScanFeedback.Failed(e.toUiText())) }
                _events.trySend(BookingEvent.Scanned(false))
            }
        }
    }

    fun setQuantity(key: String, quantity: BigDecimal) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { it.copy(draft = it.draft.setQuantity(key, quantity), lineErrors = it.lineErrors - key, submitError = null) }
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
                _state.update {
                    it.copy(submitting = false, uncertain = false, step = BookingStep.DONE, bookedLines = receipt.data.lineCount)
                }
            } catch (e: ApiException.Network) {
                // Maybe booked: keep the key, lock the draft, offer a retry.
                _state.update { it.copy(submitting = false, uncertain = true, submitError = UiText.Res(R.string.booking_uncertain)) }
            } catch (e: ApiException.Http) {
                idempotencyKey = null
                _state.update { it.copy(submitting = false, uncertain = false, submitError = e.toUiText(), lineErrors = lineErrors(e, it.draft)) }
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(submitting = false, submitError = e.toUiText()) }
            }
        }
    }

    /** The worker chose to change the draft after an unanswered submit, accepting that it may already be booked. */
    fun unlockAfterUncertain() {
        idempotencyKey = null
        _state.update { it.copy(uncertain = false, submitError = null) }
    }

    /** After a successful booking: an empty draft for the same warehouse(s) and location(s). */
    fun startOver() {
        available.clear()
        _state.update { it.copy(step = BookingStep.SCAN, draft = Draft(), feedback = null, note = "", bookedLines = 0, submitError = null, lineErrors = emptyMap()) }
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
    }
}
