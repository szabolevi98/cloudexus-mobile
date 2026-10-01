package net.levente.cloudexus.mobile.ui.receiving

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.api.Location
import net.levente.cloudexus.mobile.data.api.ReceiveLine
import net.levente.cloudexus.mobile.data.api.ReceiveOrder
import net.levente.cloudexus.mobile.data.api.ReceiveTask
import net.levente.cloudexus.mobile.data.api.Warehouse
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.data.work.Outbox
import net.levente.cloudexus.mobile.data.work.QueuedBooking
import net.levente.cloudexus.mobile.data.work.WorkStore
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.components.Signal
import net.levente.cloudexus.mobile.ui.picking.OrderStep
import net.levente.cloudexus.mobile.ui.picking.Taken
import net.levente.cloudexus.mobile.ui.toUiText
import java.math.BigDecimal
import java.util.UUID

/** A receipt kept on the device between app starts. */
@Serializable
data class SavedReceipt(
    val orderId: Int,
    val warehouseId: Int,
    val arrivals: Map<Int, List<Taken>>,
    val note: String = "",
    val key: String? = null,
    val uncertain: Boolean = false,
)

data class ReceivingUiState(
    val step: OrderStep = OrderStep.LIST,
    val tasks: List<ReceiveTask> = emptyList(),
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val task: ReceiveTask? = null,
    val warehouses: List<Warehouse> = emptyList(),
    val warehouse: Warehouse? = null,
    val order: ReceiveOrder? = null,
    val locations: List<Location> = emptyList(),
    val location: Location? = null,
    /** Product id to what arrived, by the shelf it was put on. */
    val arrivals: Map<Int, List<Taken>> = emptyMap(),
    val note: String = "",
    val problem: UiText? = null,
    /** The problem is only a warning: more came than was still expected. */
    val warning: Boolean = false,
    val lastProductId: Int? = null,
    val submitting: Boolean = false,
    val submitError: UiText? = null,
    val uncertain: Boolean = false,
    val queued: Boolean = false,
    val resume: SavedReceipt? = null,
    val sound: Boolean = true,
) {
    fun arrived(productId: Int): BigDecimal = arrivals[productId].orEmpty().fold(BigDecimal.ZERO) { sum, t -> sum + t.amount }

    fun remaining(line: ReceiveLine): BigDecimal = line.remaining.toBigDecimalOrNull() ?: BigDecimal.ZERO

    val anything: Boolean get() = arrivals.values.any { list -> list.any { it.amount.signum() > 0 } }
}

/**
 * Receiving a purchase order: the purchase orders with goods still to come,
 * one order's lines with what was ordered and what has arrived before, and
 * the unloading — a shelf label says where the goods go, every product
 * scanned adds one there. More or less than ordered is fine: what was
 * scanned is what came, and the rest can arrive another day.
 */
class ReceivingViewModel(
    private val api: ApiClient,
    private val sessions: SessionManager,
    private val work: WorkStore,
    private val outbox: Outbox,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(ReceivingUiState())
    val state: StateFlow<ReceivingUiState> = _state.asStateFlow()

    private val _signals = Channel<Signal>(Channel.BUFFERED)
    val signals = _signals.receiveAsFlow()

    private var idempotencyKey: String? = null
    private var saveJob: Job? = null
    private val server: String? get() = sessions.current?.baseUrl

    init {
        viewModelScope.launch { server?.let { s -> work.prefs(s).collect { p -> _state.update { it.copy(sound = p.sound) } } } }
        loadTasks()
    }

    fun loadTasks() {
        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val tasks = api.receiveTasks(connection)
                val warehouses = if (state.value.warehouses.isEmpty()) api.warehouses(connection) else state.value.warehouses
                _state.update { it.copy(tasks = tasks, warehouses = warehouses, loading = false) }
                val server = server ?: return@launch
                val saved = work.draft(server, WORK)?.let { runCatching { json.decodeFromString(SavedReceipt.serializer(), it) }.getOrNull() }
                if (saved != null && tasks.any { it.id == saved.orderId } && state.value.task == null) _state.update { it.copy(resume = saved) }
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(loading = false, loadError = e.toUiText()) }
            }
        }
    }

    fun resumeSaved() {
        val saved = state.value.resume ?: return
        val task = state.value.tasks.firstOrNull { it.id == saved.orderId }
        val warehouse = state.value.warehouses.firstOrNull { it.id == saved.warehouseId }
        if (task == null || warehouse == null) return dropSaved()
        idempotencyKey = saved.key
        _state.update {
            it.copy(
                resume = null, task = task, arrivals = saved.arrivals, note = saved.note, uncertain = saved.uncertain,
                submitError = if (saved.uncertain) UiText.Res(R.string.booking_uncertain) else null,
            )
        }
        openOrder(warehouse)
    }

    fun dropSaved() {
        _state.update { it.copy(resume = null) }
        persist(clear = true)
    }

    fun selectTask(task: ReceiveTask) {
        idempotencyKey = null
        _state.update { it.copy(task = task, arrivals = emptyMap(), note = "", problem = null, submitError = null, uncertain = false) }
        viewModelScope.launch {
            val start = server?.let { work.current(it).startWarehouseId }
            val warehouse = state.value.warehouses.firstOrNull { it.id == start }
            if (warehouse != null) openOrder(warehouse) else _state.update { it.copy(step = OrderStep.WAREHOUSE) }
        }
    }

    fun selectWarehouse(warehouse: Warehouse) {
        viewModelScope.launch { server?.let { work.rememberWarehouse(it, warehouse.id) } }
        openOrder(warehouse)
    }

    fun chooseWarehouse() {
        if (!state.value.anything) _state.update { it.copy(step = OrderStep.WAREHOUSE) }
    }

    private fun openOrder(warehouse: Warehouse) {
        val connection = sessions.current?.connection() ?: return
        val task = state.value.task ?: return
        _state.update { it.copy(warehouse = warehouse, step = OrderStep.WORK, order = null, loading = true, loadError = null, location = null) }
        viewModelScope.launch {
            try {
                val order = api.receiveOrder(connection, task.id)
                val locations = runCatching { api.locations(connection, warehouse.id) }.getOrDefault(emptyList())
                // Where the last delivery in this warehouse was put, to start with.
                val last = server?.let { work.lastLocation(it, WORK, warehouse.id) }
                _state.update { it.copy(order = order, locations = locations, location = locations.firstOrNull { l -> l.id == last }, loading = false) }
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(loading = false, loadError = e.toUiText()) }
            }
        }
    }

    fun backToList() {
        idempotencyKey = null
        _state.update { it.copy(step = OrderStep.LIST, task = null, order = null, arrivals = emptyMap(), note = "", problem = null, submitError = null, uncertain = false) }
        loadTasks()
    }

    fun setNote(note: String) {
        _state.update { it.copy(note = note.take(200)) }
        persist()
    }

    fun onScan(raw: String) {
        val code = raw.trim()
        val current = state.value
        val order = current.order ?: return
        if (code.isEmpty() || current.step != OrderStep.WORK || current.submitting || current.uncertain) return

        current.locations.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { shelf ->
            _state.update { it.copy(location = shelf, problem = null) }
            val warehouse = current.warehouse
            if (warehouse != null) viewModelScope.launch { server?.let { work.rememberLocation(it, WORK, warehouse.id, shelf.id) } }
            _signals.trySend(Signal.OK)
            return
        }

        val line = order.lines.firstOrNull { it.barcode.equals(code, ignoreCase = true) || it.sku.equals(code, ignoreCase = true) }
        if (line == null) {
            _state.update { it.copy(problem = UiText.Res(R.string.receive_not_on_order, listOf(code)), warning = false) }
            _signals.trySend(Signal.ERROR)
            return
        }

        add(line, BigDecimal.ONE)
        // More than is still to come is accepted — what is scanned is what arrived — but heard.
        val over = state.value.arrived(line.productId) > current.remaining(line)
        _state.update { it.copy(problem = if (over) UiText.Res(R.string.receive_over, listOf(line.productName)) else null, warning = over, lastProductId = line.productId) }
        _signals.trySend(if (over) Signal.WARN else Signal.OK)
    }

    private fun add(line: ReceiveLine, quantity: BigDecimal) {
        idempotencyKey = null
        val shelf = state.value.location
        _state.update { s ->
            val list = s.arrivals[line.productId].orEmpty()
            val updated = if (list.any { it.locationId == shelf?.id }) {
                list.map { if (it.locationId == shelf?.id) it.copy(quantity = (it.amount + quantity).toPlainString()) else it }
            } else {
                list + Taken(shelf?.id, shelf?.code, quantity.toPlainString())
            }
            s.copy(arrivals = s.arrivals + (line.productId to updated.filter { it.amount.signum() > 0 }))
        }
        persist()
    }

    /** A typed total for a line: all of it on the current shelf. */
    fun setTotal(line: ReceiveLine, total: BigDecimal) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { it.copy(arrivals = it.arrivals - line.productId) }
        if (total.signum() > 0) add(line, total) else persist()
    }

    private fun body(): JsonObject? {
        val current = state.value
        val warehouse = current.warehouse ?: return null
        return buildJsonObject {
            put("warehouse_id", warehouse.id)
            if (current.note.isNotBlank()) put("note", current.note.trim())
            put("items", buildJsonArray {
                current.arrivals.forEach { (productId, list) ->
                    list.filter { it.amount.signum() > 0 }.forEach { taken ->
                        add(buildJsonObject {
                            put("product_id", productId)
                            put("quantity", taken.quantity)
                            put("location_id", taken.locationId?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    }
                }
            })
        }
    }

    fun submit() {
        val connection = sessions.current?.connection() ?: return
        val task = state.value.task ?: return
        val body = body() ?: return
        if (!state.value.anything || state.value.submitting) return
        val key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }
        _state.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                api.post(connection, "receiving/${task.id}", body, key)
                idempotencyKey = null
                persist(clear = true)
                _state.update { it.copy(submitting = false, uncertain = false, step = OrderStep.DONE, queued = false) }
            } catch (e: ApiException.Network) {
                _state.update { it.copy(submitting = false, uncertain = true, submitError = UiText.Res(R.string.booking_uncertain)) }
                persist()
            } catch (e: ApiException) {
                handle(e)
                idempotencyKey = null
                _state.update { it.copy(submitting = false, uncertain = false, submitError = e.toUiText()) }
            }
        }
    }

    fun queue(title: String, summary: String) {
        val session = sessions.current ?: return
        val task = state.value.task ?: return
        val body = body() ?: return
        outbox.enqueue(
            QueuedBooking(idempotencyKey ?: UUID.randomUUID().toString(), session.baseUrl, session.user.username, "receiving/${task.id}", body.toString(), title, summary, System.currentTimeMillis()),
        )
        idempotencyKey = null
        persist(clear = true)
        _state.update { it.copy(uncertain = false, submitError = null, step = OrderStep.DONE, queued = true) }
    }

    fun unlock() {
        idempotencyKey = null
        _state.update { it.copy(uncertain = false, submitError = null) }
        persist()
    }

    fun discard() = persist(clear = true)

    private fun persist(clear: Boolean = false) {
        val server = server ?: return
        val current = state.value
        val task = current.task
        val warehouse = current.warehouse
        val saved = if (clear || task == null || warehouse == null || !current.anything) null
        else SavedReceipt(task.id, warehouse.id, current.arrivals, current.note, idempotencyKey, current.uncertain)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (saved != null) delay(300)
            work.saveDraft(server, WORK, saved?.let { json.encodeToString(SavedReceipt.serializer(), it) })
        }
    }

    private fun handle(e: ApiException) {
        if (e is ApiException.Unauthorized) sessions.expire()
        if (e is ApiException.Http && e.status == 403) sessions.refreshUser()
    }

    private companion object {
        const val WORK = "RECEIVING"
    }
}
