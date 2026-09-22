package net.levente.cloudexus.mobile.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R

/** − quantity +, with the number itself tappable to type an exact (or fractional) amount. */
@Composable
fun QuantityStepper(
    text: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.quantity_decrease))
        }
        TextButton(onClick = onEdit, modifier = Modifier.widthIn(min = 64.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.quantity_increase))
        }
    }
}
