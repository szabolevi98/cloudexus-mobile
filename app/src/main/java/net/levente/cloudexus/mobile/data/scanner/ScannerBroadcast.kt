package net.levente.cloudexus.mobile.data.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Listens for the scanner's broadcast while the calling screen is on screen.
 * The receiver has to be exported, because the scanner service is another app.
 */
@Composable
fun ScannerBroadcastEffect(config: ScannerConfig, onScan: (String) -> Unit) {
    val context = LocalContext.current
    val currentOnScan by rememberUpdatedState(onScan)

    DisposableEffect(context, config.action, config.extra) {
        if (!config.usesBroadcast) {
            return@DisposableEffect onDispose { }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val code = readCode(intent, config.extra)
                if (!code.isNullOrBlank()) currentOnScan(code.trim())
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(config.action), ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

/** Scanners put the code in a String extra, some in a byte array. */
internal fun readCode(intent: Intent, extra: String): String? {
    intent.getStringExtra(extra)?.let { return it }
    return intent.getByteArrayExtra(extra)?.toString(Charsets.UTF_8)
}
