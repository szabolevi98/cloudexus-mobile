package net.levente.cloudexus.mobile.ui.stocktaking

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.api.Location
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

/** One product counted: how many, and on which shelves it was found. */
@Serializable
data class CountLine(
    val productId: Int,
    val sku: String,
    val name: String,
    val unit: String? = null,
    val quantity: String,
    val shelves: List<String> = emptyList(),
) {
    val amount: BigDecimal get() = quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

/** A count kept on the device between app starts: a stocktaking takes a while. */
@Serializable
data class SavedCount(
    val warehouseId: Int,
    val note: String = "",
    val lines: List<CountLine>,
    val key: String? = null,
    val uncertain: Boolean = false,
)

/** A counted product's difference from the book, as the server booked it. */
data class CountResult(val sku: String, val name: String, val book: String, val counted: String, val diff: String)

enum class CountStep { WAREHOUSE, COUNT, DONE }

data class StocktakingUiState(
    val step: CountStep = CountStep.WAREHOUSE,
    val warehouses: List<Warehouse> = emptyList(),
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val warehouse: Warehouse? = null,
    val locations: List<Location> = emptyList(),
    val location: Location? = null,
    /** The product scanned last first. */
    val lines: List<CountLine> = emptyList(),
    val last: String? = null,
    val problem: UiText? = null,
    val lookingUp: Boolean = false,
    val note: String = "",
    val submitting: Boolean = false,
    val submitError: UiText? = null,
    val uncertain: Boolean = false,
    val number: String? = null,
    val results: List<CountResult> = emptyList(),
    val queued: Boolean = false,
    val resume: SavedCount? = null,
    val sound: Boolean = true,
)

/**
 * A stocktaking counted by scanning: every scan of a product adds one, a shelf
 * label says where the counting is (for the worker's own orientation — the
 * count is per warehouse, as on the web), and the total is typed in for a
 * product counted by the box. Booking it reads the book stock at that moment;
 * products not counted are left as they are.
 */
class StocktakingViewModel(
    private val api: ApiClient,
    private val sessions: SessionManager,
    private val work: WorkStore,
    private val outbox: Outbox,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(StocktakingUiState())
    val state: StateFlow<StocktakingUiState> = _state.asStateFlow()

    private val _signals = Channel<Signal>(Channel.BUFFERED)
    val signals = _signals.receiveAsFlow()

    private var idempotencyKey: String? = null
    private var saveJob: Job? = null
    private val server: String? get() = sessions.current?.baseUrl

    init {
        viewModelScope.launch { server?.let { s -> work.prefs(s).collect { p -> _state.update { it.copy(sound = p.sound) } } } }
        load()
    }

    fun load() {
        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val warehouses = api.warehouses(connection)
                _state.update { it.copy(warehouses = warehouses, loading = false) }
                val server = server ?: return@launch
                val saved = work.draft(server, WORK)?.let { runCatching { json.decodeFromString(SavedCount.serializer(), it) }.getOrNull() }
                if (saved != null && saved.lines.isNotEmpty() && warehouses.any { it.id == saved.warehouseId }) {
                    _state.update { it.copy(resume = saved) }
                } else {
                    work.current(server).startWarehouseId?.let { id -> warehouses.firstOrNull { it.id == id } }?.let(::selectWarehouse)
                }
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(loading = false, loadError = e.toUiText()) }
            }
        }
    }

    fun resumeSaved() {
        val saved = state.value.resume ?: return
        val warehouse = state.value.warehouses.firstOrNull { it.id == saved.warehouseId } ?: return dropSaved()
        idempotencyKey = saved.key
        _state.update {
            it.copy(
                resume = null, warehouse = warehouse, lines = saved.lines, note = saved.note, step = CountStep.COUNT,
                uncertain = saved.uncertain, submitError = if (saved.uncertain) UiText.Res(R.string.booking_uncertain) else null,
            )
        }
        loadLocations(warehouse)
    }

    fun dropSaved() {
        _state.update { it.copy(resume = null) }
        persist(clear = true)
    }

    fun selectWarehouse(warehouse: Warehouse) {
        _state.update { it.copy(warehouse = warehouse, step = CountStep.COUNT, location = null) }
        viewModelScope.launch { server?.let { work.rememberWarehouse(it, warehouse.id) } }
        loadLocations(warehouse)
    }

    fun changeWarehouse() {
        if (state.value.lines.isEmpty()) _state.update { it.copy(step = CountStep.WAREHOUSE) }
    }

    private fun loadLocations(warehouse: Warehouse) {
        val connection = sessions.current?.connection() ?: return
        viewModelScope.launch {
            runCatching { api.locations(connection, warehouse.id) }.onSuccess { locations ->
                _state.update { if (it.warehouse?.id == warehouse.id) it.copy(locations = locations) else it }
            }
        }
    }

    fun setLocation(location: Location?) = _state.update { it.copy(location = location) }

    fun setNote(note: String) {
        _state.update { it.copy(note = note.take(200)) }
        persist()
    }

    fun onScan(raw: String) {
        val code = raw.trim()
        val current = state.value
        if (code.isEmpty() || current.step != CountStep.COUNT || current.lookingUp || current.submitting || current.uncertain) return

        current.locations.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { shelf ->
            _state.update { it.copy(location = shelf, problem = null) }
            _signals.trySend(Signal.OK)
            return
        }

        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(lookingUp = true) }
        viewModelScope.launch {
            try {
                val product = api.lookup(connection, code)
                if (product == null) {
                    _state.update { it.copy(lookingUp = false, problem = UiText.Res(R.string.code_not_found_text, listOf(code))) }
                    _signals.trySend(Signal.ERROR)
                    return@launch
                }
                _state.update {
                    val shelf = it.location?.code
                    val existing = it.lines.firstOrNull { line -> line.productId == product.id }
                    val line = existing?.copy(
                        quantity = (existing.amount + BigDecimal.ONE).toPlainString(),
                        shelves = if (shelf != null && shelf !in existing.shelves) existing.shelves + shelf else existing.shelves,
                    ) ?: CountLine(product.id, product.sku, product.name, product.unit, "1", listOfNotNull(shelf))
                    it.copy(lines = listOf(line) + it.lines.filterNot { l -> l.productId == product.id }, last = product.name, lookingUp = false, problem = null)
                }
                idempotencyKey = null
                persist()
                _signals.trySend(Signal.OK)
            } catch (e: ApiException) {
                handle(e)
                _state.update { it.copy(lookingUp = false, problem = e.toUiText()) }
                _signals.trySend(Signal.ERROR)
            }
        }
    }

    /** A typed count; zero is a count too ("there is none"). */
    fun setQuantity(productId: Int, quantity: BigDecimal) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { s -> s.copy(lines = s.lines.map { if (it.productId == productId) it.copy(quantity = quantity.max(BigDecimal.ZERO).toPlainString()) else it }) }
        persist()
    }

    fun remove(productId: Int) {
        if (state.value.uncertain) return
        idempotencyKey = null
        _state.update { s -> s.copy(lines = s.lines.filterNot { it.productId == productId }) }
        persist()
    }

    private fun body(): JsonObject? {
        val current = state.value
        val warehouse = current.warehouse ?: return null
        return buildJsonObject {
            put("warehouse_id", warehouse.id)
            if (current.note.isNotBlank()) put("note", current.note.trim())
            put("items", buildJsonArray {
                current.lines.forEach { line ->
                    add(buildJsonObject {
                        put("product_id", line.productId)
                        put("counted_quantity", line.quantity)
                    })
                }
            })
        }
    }

    fun submit() {
        val connection = sessions.current?.connection() ?: return
        val body = body() ?: return
        if (state.value.lines.isEmpty() || state.value.submitting) return
        val key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }
        _state.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                val data = api.post(connection, PATH, body, key)["data"]?.jsonObject
                idempotencyKey = null
                persist(clear = true)
                val results = data?.get("items")?.jsonArray.orEmpty().map { item ->
                    val o = item.jsonObject
                    fun s(name: String) = o[name]?.jsonPrimitive?.contentOrNull.orEmpty()
                    CountResult(s("sku"), s("product_name"), s("book_quantity"), s("counted_quantity"), s("diff"))
                }.sortedByDescending { (it.diff.toBigDecimalOrNull() ?: BigDecimal.ZERO).abs() }
                _state.update {
                    it.copy(
                        submitting = false, uncertain = false, step = CountStep.DONE,
                        number = data?.get("stocktaking_number")?.jsonPrimitive?.contentOrNull, results = results, queued = false,
                    )
                }
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
        val body = body() ?: return
        outbox.enqueue(
            QueuedBooking(idempotencyKey ?: UUID.randomUUID().toString(), session.baseUrl, session.user.username, PATH, body.toString(), title, summary, System.currentTimeMillis()),
        )
        idempotencyKey = null
        persist(clear = true)
        _state.update { it.copy(uncertain = false, submitError = null, step = CountStep.DONE, queued = true) }
    }

    fun unlock() {
        idempotencyKey = null
        _state.update { it.copy(uncertain = false, submitError = null) }
        persist()
    }

    fun discard() = persist(clear = true)

    fun startOver() {
        _state.update { it.copy(step = CountStep.COUNT, lines = emptyList(), note = "", results = emptyList(), number = null, last = null, queued = false) }
    }

    private fun persist(clear: Boolean = false) {
        val server = server ?: return
        val current = state.value
        val warehouse = current.warehouse
        val saved = if (clear || warehouse == null || current.lines.isEmpty()) null
        else SavedCount(warehouse.id, current.note, current.lines, idempotencyKey, current.uncertain)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (saved != null) delay(300)
            work.saveDraft(server, WORK, saved?.let { json.encodeToString(SavedCount.serializer(), it) })
        }
    }

    private fun handle(e: ApiException) {
        if (e is ApiException.Unauthorized) sessions.expire()
        if (e is ApiException.Http && e.status == 403) sessions.refreshUser()
    }

    private companion object {
        const val WORK = "STOCKTAKING"
        const val PATH = "stocktakings"
    }
}
