package net.levente.cloudexus.mobile.data.scanner

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * How a handheld's built-in scanner hands over a read code.
 *
 * Nearly every PDA can do "keyboard wedge" (types the code and presses Enter),
 * which the scan field takes without any setup. Most can also send it as a
 * broadcast intent, which works even when no field has focus; the action and
 * the extra that carries the code differ per brand, hence the presets.
 */
enum class ScannerPreset(val action: String, val extra: String) {
    /** Keyboard wedge only; no broadcast receiver. */
    KEYBOARD("", ""),

    /** DataWedge: set the profile's Intent output to this action, delivery "Broadcast intent". */
    ZEBRA("net.levente.cloudexus.mobile.SCAN", "com.symbol.datawedge.data_string"),
    HONEYWELL("com.honeywell.decode.intent.action.EDIT_DATA", "data"),
    UROVO("android.intent.ACTION_DECODE_DATA", "barcode_string"),
    NEWLAND("nlscan.action.SCANNER_RESULT", "SCAN_BARCODE1"),
    SUNMI("com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED", "data"),
    IDATA("android.intent.action.SCANRESULT", "value"),
    CUSTOM("", ""),
}

data class ScannerConfig(
    val preset: ScannerPreset = ScannerPreset.KEYBOARD,
    val customAction: String = "",
    val customExtra: String = "",
) {
    val action: String get() = if (preset == ScannerPreset.CUSTOM) customAction.trim() else preset.action
    val extra: String get() = if (preset == ScannerPreset.CUSTOM) customExtra.trim() else preset.extra

    /** Whether a broadcast receiver should listen at all. */
    val usesBroadcast: Boolean get() = action.isNotEmpty() && extra.isNotEmpty()
}

class ScannerSettings(private val dataStore: DataStore<Preferences>) {

    val config: Flow<ScannerConfig> = dataStore.data.map { prefs ->
        ScannerConfig(
            preset = prefs[PRESET]?.let { name -> ScannerPreset.entries.firstOrNull { it.name == name } } ?: ScannerPreset.KEYBOARD,
            customAction = prefs[CUSTOM_ACTION].orEmpty(),
            customExtra = prefs[CUSTOM_EXTRA].orEmpty(),
        )
    }

    suspend fun save(config: ScannerConfig) {
        dataStore.edit {
            it[PRESET] = config.preset.name
            it[CUSTOM_ACTION] = config.customAction
            it[CUSTOM_EXTRA] = config.customExtra
        }
    }

    private companion object {
        val PRESET = stringPreferencesKey("scanner_preset")
        val CUSTOM_ACTION = stringPreferencesKey("scanner_custom_action")
        val CUSTOM_EXTRA = stringPreferencesKey("scanner_custom_extra")
    }
}
