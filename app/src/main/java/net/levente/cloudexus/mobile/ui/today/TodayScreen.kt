package net.levente.cloudexus.mobile.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException
import net.levente.cloudexus.mobile.data.api.MyMovement
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.ui.UiText
import net.levente.cloudexus.mobile.ui.booking.formatQuantity
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.EmptyState
import net.levente.cloudexus.mobile.ui.components.ErrorPanel
import net.levente.cloudexus.mobile.ui.theme.CxDanger
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxSuccess
import net.levente.cloudexus.mobile.ui.toUiText

/**
 * One booking as the worker made it: the movements written in one go share
 * the minute, the warehouse and the note ("Kiszedés: REND-2026-0016").
 */
data class TodayGroup(val time: String, val warehouse: String, val note: String?, val movements: List<MyMovement>)

data class TodayUiState(val loading: Boolean = true, val error: UiText? = null, val groups: List<TodayGroup> = emptyList())

class TodayViewModel(private val api: ApiClient, private val sessions: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(TodayUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val connection = sessions.current?.connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val movements = api.myMovements(connection)
                _state.update { TodayUiState(loading = false, groups = group(movements)) }
            } catch (e: ApiException) {
                if (e is ApiException.Unauthorized) sessions.expire()
                _state.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    private fun group(movements: List<MyMovement>): List<TodayGroup> {
        val groups = mutableListOf<TodayGroup>()
        for (m in movements) {
            val time = m.createdAt.drop(11).take(5)
            val last = groups.lastOrNull()
            if (last != null && last.time == time && last.warehouse == m.warehouseName && last.note == m.note) {
                groups[groups.lastIndex] = last.copy(movements = last.movements + m)
            } else {
                groups += TodayGroup(time, m.warehouseName, m.note, listOf(m))
            }
        }
        // Newest booking first, but inside one in the order it was written: the out before the in of a move.
        return groups.map { it.copy(movements = it.movements.reversed()) }
    }
}

/** What the signed-in user booked today, newest first: to check a booking went through, or what was done on a shift. */
@Composable
fun TodayScreen(viewModel: TodayViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CxHeader(
            title = stringResource(R.string.today_title),
            subtitle = stringResource(R.string.today_subtitle),
            onBack = onBack,
            actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = Color.White) } },
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorPanel(state.error!!, viewModel::load)
            state.groups.isEmpty() -> EmptyState(Icons.Rounded.History, stringResource(R.string.today_empty_title), stringResource(R.string.today_empty_text))
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.navigationBarsPadding()) {
                items(state.groups) { group ->
                    CxCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(group.time, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                group.note ?: group.warehouse,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            listOf(group.warehouse, pluralStringResource(R.plurals.lines_count, group.movements.size, group.movements.size)).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CxMuted,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
                        group.movements.forEach { m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.productName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(m.sku, m.locationCode).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = CxMuted)
                                }
                                val incoming = m.type == "in"
                                Text(
                                    (if (incoming) "+" else "−") + "${formatQuantity(m.quantity)} ${m.unit.orEmpty()}".trim(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (incoming) CxSuccess else CxDanger,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
