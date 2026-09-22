package net.levente.cloudexus.mobile.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.ui.theme.CxBorder

/**
 * Where scans arrive. A keyboard-wedge scanner types the code into the focused
 * field and presses Enter (or Tab), which submits it; the field then clears and
 * keeps focus for the next scan. The on-screen keyboard stays hidden unless the
 * worker asks for it, because on a PDA it would cover half the screen for
 * nothing. A change of [focusKey] grabs the focus again, e.g. after a dialog closed.
 */
@Composable
fun ScanField(
    onScan: (String) -> Unit,
    onCamera: (() -> Unit)?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    focusKey: Any? = Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        val code = text.trim()
        text = ""
        if (code.isNotEmpty()) onScan(code)
    }

    LaunchedEffect(focusKey, typing) {
        runCatching { focus.requestFocus() }
        if (typing) keyboard?.show() else keyboard?.hide()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            // Some wedges send the terminator as a character instead of a key event.
            if (value.endsWith('\n') || value.endsWith('\t')) {
                text = value.trimEnd('\n', '\t', '\r')
                submit()
            } else {
                text = value
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val terminator = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab
                if (terminator && event.type == KeyEventType.KeyUp) submit()
                terminator
            },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.scan_placeholder)) },
        leadingIcon = {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingIcon = {
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
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = CxBorder,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
