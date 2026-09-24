package net.levente.cloudexus.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiException

/** Text a ViewModel hands to the UI without holding a Context: a resource, or text from the server. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    @Composable
    fun asString(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Raw -> text
    }
}

/** The user-facing sentence for a failed request. Server messages are English, so they follow a translated lead. */
fun ApiException.toUiText(): UiText = when (this) {
    is ApiException.Network -> UiText.Res(R.string.error_network)
    is ApiException.Unauthorized -> UiText.Res(R.string.error_session_expired)
    is ApiException.BadResponse -> UiText.Res(R.string.error_bad_response)
    is ApiException.Http -> if (status == 403) {
        UiText.Res(R.string.error_forbidden)
    } else {
        UiText.Res(R.string.error_server, listOf(message ?: status.toString()))
    }
}
