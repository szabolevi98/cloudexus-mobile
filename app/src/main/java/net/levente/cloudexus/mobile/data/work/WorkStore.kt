package net.levente.cloudexus.mobile.data.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the device remembers between bookings, and the settings that go with it. */
data class WorkPrefs(
    /** The warehouse every booking starts in, set in Settings; null: the last one used. */
    val fixedWarehouseId: Int? = null,
    val lastWarehouseId: Int? = null,
    /** Beep on a scan; the buzz stays either way. */
    val sound: Boolean = true,
) {
    /** The warehouse to start in without asking, if any. */
    val startWarehouseId: Int? get() = fixedWarehouseId ?: lastWarehouseId
}

/**
 * The device's memory of its work: which warehouse it is in (a PDA usually
 * stays in one), the last shelf per kind of work and warehouse, the beep
 * setting, and the lists being scanned, so a list survives the app being
 * closed. Everything is kept per server: warehouse and shelf ids mean nothing
 * on another Cloudexus.
 */
class WorkStore(private val dataStore: DataStore<Preferences>) {

    fun prefs(server: String): Flow<WorkPrefs> = dataStore.data.map { prefs ->
        WorkPrefs(
            fixedWarehouseId = prefs[intPreferencesKey(key(server, "fixed_warehouse"))],
            lastWarehouseId = prefs[intPreferencesKey(key(server, "last_warehouse"))],
            sound = prefs[SOUND] ?: true,
        )
    }

    suspend fun current(server: String): WorkPrefs = prefs(server).first()

    suspend fun setFixedWarehouse(server: String, warehouseId: Int?) {
        dataStore.edit {
            val k = intPreferencesKey(key(server, "fixed_warehouse"))
            if (warehouseId == null) it.remove(k) else it[k] = warehouseId
        }
    }

    suspend fun rememberWarehouse(server: String, warehouseId: Int) {
        dataStore.edit { it[intPreferencesKey(key(server, "last_warehouse"))] = warehouseId }
    }

    suspend fun setSound(on: Boolean) {
        dataStore.edit { it[SOUND] = on }
    }

    /** The shelf last used for [work] (a booking mode, "stocktaking", …) in a warehouse. */
    suspend fun lastLocation(server: String, work: String, warehouseId: Int): Int? =
        dataStore.data.first()[intPreferencesKey(key(server, "last_location_${work}_$warehouseId"))]

    suspend fun rememberLocation(server: String, work: String, warehouseId: Int, locationId: Int?) {
        dataStore.edit {
            val k = intPreferencesKey(key(server, "last_location_${work}_$warehouseId"))
            if (locationId == null) it.remove(k) else it[k] = locationId
        }
    }

    /** A list being scanned, as JSON, or null. */
    suspend fun draft(server: String, work: String): String? = dataStore.data.first()[stringPreferencesKey(key(server, "draft_$work"))]

    suspend fun saveDraft(server: String, work: String, json: String?) {
        dataStore.edit {
            val k = stringPreferencesKey(key(server, "draft_$work"))
            if (json == null) it.remove(k) else it[k] = json
        }
    }

    private fun key(server: String, name: String) = "work_${server.hashCode().toUInt()}_$name"

    private companion object {
        val SOUND = booleanPreferencesKey("work_sound")
    }
}
