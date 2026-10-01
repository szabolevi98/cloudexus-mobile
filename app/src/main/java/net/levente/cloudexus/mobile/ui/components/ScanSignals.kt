package net.levente.cloudexus.mobile.ui.components

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.Flow

/** What a scan did, as the worker hears and feels it. */
enum class Signal {
    /** Taken. */
    OK,

    /** Taken, but look: more than the stock, more than the order asks for. */
    WARN,

    /** Not taken: an unknown code, a product not on the list. */
    ERROR,
}

/**
 * A short beep and a tick for a taken scan, two quick beeps for one to look
 * at, and a low tone and a buzz for a rejected one: the worker is looking at
 * the goods, not the screen. [sound] off leaves the buzz.
 */
@Composable
fun ScanSignals(signals: Flow<Signal>, sound: Boolean) {
    val haptics = LocalHapticFeedback.current
    val withSound by rememberUpdatedState(sound)
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }
    DisposableEffect(tones) { onDispose { tones?.release() } }

    LaunchedEffect(signals) {
        signals.collect { signal ->
            haptics.performHapticFeedback(if (signal == Signal.OK) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)
            if (withSound) {
                when (signal) {
                    Signal.OK -> tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                    Signal.WARN -> tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                    Signal.ERROR -> tones?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
                }
            }
        }
    }
}
