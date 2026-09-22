package net.levente.cloudexus.mobile.ui.booking

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Outbox
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.ui.theme.CxPrimary
import net.levente.cloudexus.mobile.ui.theme.CxSuccess
import net.levente.cloudexus.mobile.ui.theme.CxWarning

/** Each movement type keeps one color and icon everywhere, like the dashboard's card tiles. */
data class ModeStyle(
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @StringRes val submit: Int,
    @StringRes val done: Int,
    @StringRes val again: Int,
    val icon: ImageVector,
    val color: Color,
)

val BookingMode.style: ModeStyle
    get() = when (this) {
        BookingMode.IN -> ModeStyle(R.string.mode_in, R.string.mode_in_subtitle, R.string.submit_in, R.string.done_in, R.string.again_in, Icons.Rounded.MoveToInbox, CxSuccess)
        BookingMode.OUT -> ModeStyle(R.string.mode_out, R.string.mode_out_subtitle, R.string.submit_out, R.string.done_out, R.string.again_out, Icons.Rounded.Outbox, CxWarning)
        BookingMode.TRANSFER -> ModeStyle(R.string.mode_transfer, R.string.mode_transfer_subtitle, R.string.submit_transfer, R.string.done_transfer, R.string.again_transfer, Icons.Rounded.SwapHoriz, CxPrimary)
    }
