package net.levente.cloudexus.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.ui.theme.CxBorder
import net.levente.cloudexus.mobile.ui.theme.CxMuted

/**
 * Where scans arrive.
 *
 * By default this is not a text field: a keyboard-wedge scanner sends key
 * events, and a text field would pull up the on-screen keyboard (covering half
 * a PDA screen) and route the Enter through the IME, where it can get lost. So
 * the field only takes focus and collects the typed characters itself; Enter
 * or Tab submits them. The keyboard button switches to a real text field for
 * typing a code by hand. A change of [focusKey] takes the focus back, e.g.
 * after a dialog closed.
 */
@Composable
fun ScanField(
    onScan: (String) -> Unit,
    onCamera: (() -> Unit)?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    focusKey: Any? = Unit,
    autoFocus: Boolean = true,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(focusKey, typing) {
        if (autoFocus || typing) runCatching { focus.requestFocus() }
    }

    val actions: @Composable () -> Unit = {
        Row {
            IconButton(onClick = { typing = !typing }) {
                Icon(
                    if (typing) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                    contentDescription = stringResource(R.string.scan_type_manually),
                )
            }
            if (onCamera != null) {
                IconButton(onClick = onCamera) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = stringResource(R.string.scan_with_camera), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (typing) {
        ManualField(onScan, busy, focus, actions, modifier)
    } else {
        WedgeField(onScan, busy, focus, actions, modifier)
    }
}

@Composable
private fun WedgeField(
    onScan: (String) -> Unit,
    busy: Boolean,
    focus: FocusRequester,
    actions: @Composable () -> Unit,
    modifier: Modifier,
) {
    var buffer by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = { runCatching { focus.requestFocus() } },
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { event ->
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.Tab -> {
                        if (event.type == KeyEventType.KeyDown) {
                            val code = buffer.trim()
                            buffer = ""
                            if (code.isNotEmpty()) onScan(code)
                        }
                        true
                    }
                    else -> {
                        val codePoint = event.utf16CodePoint
                        if (event.type == KeyEventType.KeyDown && codePoint >= 0x20 && codePoint != 0x7F) {
                            buffer += String(Character.toChars(codePoint))
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else CxBorder),
    ) {
        Row(Modifier.heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = if (focused) MaterialTheme.colorScheme.primary else CxMuted)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = buffer.ifEmpty { stringResource(if (focused) R.string.scan_ready else R.string.scan_tap_to_focus) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (buffer.isEmpty()) CxMuted else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

@Composable
private fun ManualField(
    onScan: (String) -> Unit,
    busy: Boolean,
    focus: FocusRequester,
    actions: @Composable () -> Unit,
    modifier: Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val code = text.trim()
        text = ""
        if (code.isNotEmpty()) onScan(code)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.scan_placeholder)) },
        leadingIcon = {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingIcon = actions,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = CxBorder,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
