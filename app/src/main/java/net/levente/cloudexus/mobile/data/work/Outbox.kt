package net.levente.cloudexus.mobile.data.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.session.SessionManager

/**
 * A booking made without a network, waiting to be sent. [key] is its
 * Idempotency-Key — the one any earlier, unanswered attempt went with — so a
 * booking that did reach the server is never booked twice.
 */
@Serializable
data class QueuedBooking(
    val key: String,
    val server: String,
    val username: String,
    val path: String,
    val body: String,
    /** What the queue screen calls it: "Raktári kiadás · Központi raktár". */
    val title: String,
    /** "12 tétel". */
    val summary: String,
    val createdAt: Long,
    /** Why the server refused it; it then waits for the user to look at it. */
    val failed: String? = null,
)

/**
 * The queue of bookings made without a network. Whatever is in it is sent as
 * soon as it can be — at once, every half minute, and when the network comes
 * back — in the order it was made, and only for the user and the server it was
 * made for. A booking the server refuses (short of stock by then, say) stays,
 * marked, until the user looks at it: it is never silently dropped.
 *
 * It runs while the app does; the PDA is in the app all shift anyway.
 */
class Outbox(
    context: Context,
    private val dataStore: DataStore<Preferences>,
    private val api: ApiClient,
    private val sessions: SessionManager,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val _items = MutableStateFlow<List<QueuedBooking>>(emptyList())
    val items: StateFlow<List<QueuedBooking>> = _items.asStateFlow()

    private val lock = Mutex()

    init {
        scope.launch {
            _items.value = load()
            scope.launch {
                while (true) {
                    sendAll()
                    delay(30_000)
                }
            }
        }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    kick()
                }
            })
        }
    }

    fun enqueue(item: QueuedBooking) {
        scope.launch {
            lock.withLock { save(_items.value.filterNot { it.key == item.key } + item) }
            sendAll()
        }
    }

    /** Try again now — a refused one too, after the user fixed what was wrong on the web. */
    fun retry(key: String) {
        scope.launch {
            lock.withLock { save(_items.value.map { if (it.key == key) it.copy(failed = null) else it }) }
            sendAll()
        }
    }

    fun discard(key: String) {
        scope.launch { lock.withLock { save(_items.value.filterNot { it.key == key }) } }
    }

    fun kick() {
        scope.launch { sendAll() }
    }

    private suspend fun sendAll() = lock.withLock {
        val session = sessions.current ?: return@withLock
        for (item in _items.value) {
            if (item.failed != null || item.server != session.baseUrl || item.username != session.user.username) continue
            try {
                api.post(session.connection(), item.path, json.decodeFromString(JsonObject.serializer(), item.body), item.key)
                save(_items.value.filterNot { it.key == item.key })
            } catch (e: ApiException.Network) {
                return@withLock // Still offline: the rest waits too, in order.
            } catch (e: ApiException.Unauthorized) {
                sessions.expire()
                return@withLock
            } catch (e: ApiException.Http) {
                save(_items.value.map { if (it.key == item.key) it.copy(failed = e.message ?: "HTTP ${e.status}") else it })
            } catch (e: ApiException) {
                save(_items.value.map { if (it.key == item.key) it.copy(failed = e.message ?: "error") else it })
            }
        }
    }

    private suspend fun load(): List<QueuedBooking> =
        dataStore.data.first()[KEY]?.let { runCatching { json.decodeFromString(ListSerializer(QueuedBooking.serializer()), it) }.getOrNull() }.orEmpty()

    private suspend fun save(items: List<QueuedBooking>) {
        _items.value = items
        dataStore.edit { it[KEY] = json.encodeToString(ListSerializer(QueuedBooking.serializer()), items) }
    }

    private companion object {
        val KEY = stringPreferencesKey("outbox")
    }
}
