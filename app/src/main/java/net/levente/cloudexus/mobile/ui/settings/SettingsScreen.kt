package net.levente.cloudexus.mobile.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.levente.cloudexus.mobile.BuildConfig
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.scanner.ScannerBroadcastEffect
import net.levente.cloudexus.mobile.data.scanner.ScannerConfig
import net.levente.cloudexus.mobile.data.scanner.ScannerPreset
import net.levente.cloudexus.mobile.data.scanner.ScannerSettings
import net.levente.cloudexus.mobile.data.session.Session
import net.levente.cloudexus.mobile.ui.components.CxButton
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.components.ScanField
import net.levente.cloudexus.mobile.ui.components.SectionLabel
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxPrimary
import net.levente.cloudexus.mobile.ui.theme.CxSuccess

@Composable
fun SettingsScreen(
    session: Session,
    config: ScannerConfig,
    settings: ScannerSettings,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lastScan by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    fun save(new: ScannerConfig) = scope.launch { settings.save(new) }

    ScannerBroadcastEffect(config) { lastScan = it }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CxHeader(title = stringResource(R.string.settings), onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_account), Modifier.padding(start = 4.dp))
            CxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Person, CxPrimary, soft = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.user.fullName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(session.user.username, session.user.roleName, session.baseUrl).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CxMuted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                CxButton(
                    stringResource(R.string.sign_out),
                    onClick = { confirmSignOut = true },
                    color = CxDanger,
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionLabel(stringResource(R.string.settings_scanner), Modifier.padding(start = 4.dp, top = 8.dp))
            CxCard {
                Text(stringResource(R.string.settings_scanner_hint), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                Spacer(Modifier.height(8.dp))
                for (preset in ScannerPreset.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = config.preset == preset, role = Role.RadioButton) { save(config.copy(preset = preset)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = config.preset == preset, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(preset.label), style = MaterialTheme.typography.bodyLarge)
                            if (preset.action.isNotEmpty()) {
                                Text(preset.action, style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                            }
                        }
                    }
                }
                if (config.preset == ScannerPreset.CUSTOM) {
                    // Local copies, so typing does not wait for the round trip through DataStore.
                    var action by rememberSaveable { mutableStateOf(config.customAction) }
                    var extra by rememberSaveable { mutableStateOf(config.customExtra) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it; save(config.copy(customAction = it, customExtra = extra)) },
                        label = { Text(stringResource(R.string.settings_intent_action)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extra,
                        onValueChange = { extra = it; save(config.copy(customAction = action, customExtra = it)) },
                        label = { Text(stringResource(R.string.settings_intent_extra)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (config.preset == ScannerPreset.ZEBRA) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_zebra_hint, ScannerPreset.ZEBRA.action), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                }
            }

            SectionLabel(stringResource(R.string.settings_test), Modifier.padding(start = 4.dp, top = 8.dp))
            CxCard {
                Text(stringResource(R.string.settings_test_hint), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                Spacer(Modifier.height(12.dp))
                ScanField(onScan = { lastScan = it }, onCamera = null, busy = false, autoFocus = false)
                lastScan?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CxSuccess)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_test_received, it), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Text(
                stringResource(R.string.login_footer, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = CxMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(stringResource(R.string.sign_out_confirm)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.sign_out), color = CxDanger) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@get:StringRes
private val ScannerPreset.label: Int
    get() = when (this) {
        ScannerPreset.KEYBOARD -> R.string.preset_keyboard
        ScannerPreset.ZEBRA -> R.string.preset_zebra
        ScannerPreset.HONEYWELL -> R.string.preset_honeywell
        ScannerPreset.UROVO -> R.string.preset_urovo
        ScannerPreset.NEWLAND -> R.string.preset_newland
        ScannerPreset.SUNMI -> R.string.preset_sunmi
        ScannerPreset.IDATA -> R.string.preset_idata
        ScannerPreset.CUSTOM -> R.string.preset_custom
    }
