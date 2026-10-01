package net.levente.cloudexus.mobile.ui.picking

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
import net.levente.cloudexus.mobile.data.api.PickLine
import net.levente.cloudexus.mobile.data.api.PickOrder
import net.levente.cloudexus.mobile.data.api.PickTask
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

/** How much of a product was taken from one shelf (null: stock booked without a shelf). */
@Serializable
data class Taken(val locationId: Int? = null, val locationCode: String? = null, val quantity: String) {
    val amount: BigDecimal get() = quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

/** A pick kept on the device between app starts. */
@Serializable
data class SavedPick(
    val orderId: Int,
    val warehouseId: Int,
    val taken: Map<Int, List<Taken>>,
    val key: String? = null,
    val uncertain: Boolean = false,
)

enum class OrderStep { LIST, WAREHOUSE, WORK, DONE }

data class PickingUiState(
    val step: OrderStep = OrderStep.LIST,
    val tasks: List<PickTask> = emptyList(),
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val task: PickTask? = null,
    val warehouses: List<Warehouse> = emptyList(),
    val warehouse: Warehouse? = null,
    val order: PickOrder? = null,
    val locations: List<Location> = emptyList(),
    val location: Location? = null,
    /** Product id to what was taken from which shelf. */
    val taken: Map<Int, List<Taken>> = emptyMap(),
    val problem: UiText? = null,
    val lastProductId: Int? = null,
    val submitting: Boolean = false,
    val submitError: UiText? = null,
    val uncertain: Boolean = false,
    val queued: Boolean = false,
    val resume: SavedPick? = null,
    val sound: Boolean = true,
) {
    fun total(productId: Int): BigDecimal = taken[productId].orEmpty().fold(BigDecimal.ZERO) { sum, t -> sum + t.amount }

    fun needed(line: PickLine): BigDecimal = line.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** Every line taken in full: the only way an order can be picked. */
    val complete: Boolean get() = order != null && order.lines.all { total(it.productId).compareTo(needed(it)) == 0 }

    val pickedLines: Int get() = order?.lines?.count { total(it.productId).compareTo(needed(it)) == 0 } ?: 0
}

/**
 * Picking a customer order: the orders waiting, one order's lines in the
 * order of the shelves that hold them, and the walk — a shelf label says where
 * the picker is, a product scanned there is taken from it. When every line is
 * taken in full, one button books it all out and marks the order picked.
 */
class PickingViewModel(
    private val api: ApiClient,
    private val sessions: SessionManager,
    private val work: WorkStore,
    private val outbox: Outbox,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(PickingUiState())
    val state: StateFlow<PickingUiState> = _state.asStateFlow()

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
                val tasks = api.pickTasks(connection)
                val warehouses = if (state.value.warehouses.isEmpty()) api.warehouses(connection) else state.value.warehouses
                _state.update { it.copy(tasks = tasks, warehouses = warehouses, loading = false) }
                val server = server ?: return@launch
                val saved = work.draft(server, WORK)?.let { runCatching { json.decodeFromString(SavedPick.serializer(), it) }.getOrNull() }
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
                resume = null, task = task, taken = saved.taken, uncertain = saved.uncertain,
                submitError = if (saved.uncertain) UiText.Res(R.string.booking_uncertain) else null,
            )
        }
        openOrder(warehouse)
    }

    fun dropSaved() {
        _state.update { it.copy(resume = null) }
        persist(clear = true)
    }

    fun selectTask(task: PickTask) {
        idempotencyKey = null
        _state.update { it.copy(task = task, taken = emptyMap(), problem = null, submitError = null, uncertain = false) }
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
        if (state.value.taken.isEmpty()) _state.update { it.copy(step = OrderStep.WAREHOUSE) }
    }

    /** The order's lines with the shelves of this warehouse that hold them. */
    private fun openOrder(warehouse: Warehouse) {
        val connection = sessions.current?.connection() ?: return
        val task = state.value.task ?: return
        _state.update { it.copy(warehouse = warehouse, step = OrderStep.WORK, order = null, loading = true, loadError = null, location = null) }
        viewModelScope.launch {
            try {
                val order = api.pickOrder(connection, task.id, warehouse.id)
                val locations = runCatching { api.locations(connection, warehouse.id) }.getOrDefault(emptyList())
                _state.update { it.copy(order = order, locations = locations, loading = false) }
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(loading = false, loadError = e.toUiText()) }
            }
        }
    }

    fun backToList() {
        idempotencyKey = null
        _state.update { it.copy(step = OrderStep.LIST, task = null, order = null, taken = emptyMap(), problem = null, submitError = null, uncertain = false) }
        loadTasks()
    }

    fun onScan(raw: String) {
        val code = raw.trim()
        val current = state.value
        val order = current.order ?: return
        if (code.isEmpty() || current.step != OrderStep.WORK || current.submitting || current.uncertain) return

        current.locations.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { shelf ->
            _state.update { it.copy(location = shelf, problem = null) }
            _signals.trySend(Signal.OK)
            return
        }

        val line = order.lines.firstOrNull { it.barcode.equals(code, ignoreCase = true) || it.sku.equals(code, ignoreCase = true) }
        if (line == null) {
            _state.update { it.copy(problem = UiText.Res(R.string.pick_not_on_order, listOf(code))) }
            _signals.trySend(Signal.ERROR)
            return
        }
        if (current.total(line.productId) >= current.needed(line)) {
            _state.update { it.copy(problem = UiText.Res(R.string.pick_already_done, listOf(line.productName)), lastProductId = line.productId) }
            _signals.trySend(Signal.WARN)
            return
        }

        take(line, BigDecimal.ONE, current.location?.let { Taken(it.id, it.code, "0") } ?: defaultShelf(line))
        _state.update { it.copy(problem = null, lastProductId = line.productId) }
        _signals.trySend(Signal.OK)
    }

    /** Where a product scanned without a shelf scan is taken from: the first of its shelves with some left. */
    private fun defaultShelf(line: PickLine): Taken {
        val taken = state.value.taken[line.productId].orEmpty()
        val shelf = line.shelves.firstOrNull { shelf ->
            val already = taken.filter { it.locationId == shelf.locationId }.fold(BigDecimal.ZERO) { s, t -> s + t.amount }
            (shelf.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO) > already
        } ?: line.shelves.firstOrNull()
        return Taken(shelf?.locationId, shelf?.locationCode, "0")
    }

    private fun take(line: PickLine, quantity: BigDecimal, at: Taken) {
        idempotencyKey = null
        _state.update { s ->
            val list = s.taken[line.productId].orEmpty()
            val existing = list.firstOrNull { it.locationId == at.locationId }
            val updated = if (existing != null) {
                list.map { if (it.locationId == at.locationId) it.copy(quantity = (it.amount + quantity).toPlainString()) else it }
            } else {
                list + at.copy(quantity = quantity.toPlainString())
            }
            s.copy(taken = s.taken + (line.productId to updated.filter { it.amount.signum() > 0 }))
        }
        persist()
    }

    /** A typed total for a line: the shelves keep what they had, the difference goes on the default shelf. */
    fun setTotal(line: PickLine, total: BigDecimal) {
        if (state.value.uncertain) return
        val target = total.min(state.value.needed(line)).max(BigDecimal.ZERO)
        val delta = target - state.value.total(line.productId)
        if (delta.signum() > 0) {
            take(line, delta, state.value.location?.let { Taken(it.id, it.code, "0") } ?: defaultShelf(line))
        } else if (delta.signum() < 0) {
            clear(line)
            if (target.signum() > 0) take(line, target, defaultShelf(line))
        }
    }

    fun clear(line: PickLine) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { it.copy(taken = it.taken - line.productId) }
        persist()
    }

    private fun body(): JsonObject? {
        val current = state.value
        val warehouse = current.warehouse ?: return null
        return buildJsonObject {
            put("warehouse_id", warehouse.id)
            put("items", buildJsonArray {
                current.taken.forEach { (productId, list) ->
                    list.forEach { taken ->
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
        if (!state.value.complete || state.value.submitting) return
        val key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }
        _state.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                api.post(connection, "picking/${task.id}", body, key)
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
            QueuedBooking(idempotencyKey ?: UUID.randomUUID().toString(), session.baseUrl, session.user.username, "picking/${task.id}", body.toString(), title, summary, System.currentTimeMillis()),
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
        val saved = if (clear || task == null || warehouse == null || current.taken.isEmpty()) null
        else SavedPick(task.id, warehouse.id, current.taken, idempotencyKey, current.uncertain)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (saved != null) delay(300)
            work.saveDraft(server, WORK, saved?.let { json.encodeToString(SavedPick.serializer(), it) })
        }
    }

    private fun handle(e: ApiException) {
        if (e is ApiException.Unauthorized) sessions.expire()
        if (e is ApiException.Http && e.status == 403) sessions.refreshUser()
    }

    private companion object {
        const val WORK = "PICKING"
    }
}
