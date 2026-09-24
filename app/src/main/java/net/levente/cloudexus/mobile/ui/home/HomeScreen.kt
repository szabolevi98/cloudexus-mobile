package net.levente.cloudexus.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.session.Session
import net.levente.cloudexus.mobile.ui.booking.BookingMode
import net.levente.cloudexus.mobile.ui.booking.style
import net.levente.cloudexus.mobile.ui.components.CxCard
import net.levente.cloudexus.mobile.ui.components.CxHeader
import net.levente.cloudexus.mobile.ui.components.IconTile
import net.levente.cloudexus.mobile.ui.components.SectionLabel
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxNavyTo
import net.levente.cloudexus.mobile.ui.theme.CxPrimary
import java.net.URI

@Composable
fun HomeScreen(session: Session, onBooking: (BookingMode) -> Unit, onLookup: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CxHeader(
            // The whole name: in Hungarian order the first word is the family name.
            title = stringResource(R.string.home_greeting, session.user.fullName),
            subtitle = listOfNotNull(host(session.baseUrl), session.user.roleName).joinToString(" · "),
            actions = {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, stringResource(R.string.settings), tint = Color.White)
                }
                // The avatar looks like a button, so it is one: it opens the same page,
                // which starts with the account (who is signed in, and signing out).
                val accountLabel = stringResource(R.string.account_and_settings)
                IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = accountLabel }) {
                    Avatar(session.user.fullName)
                }
                Spacer(Modifier.width(4.dp))
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.home_section_movements), Modifier.padding(start = 4.dp, top = 4.dp))
            if (session.user.canMoveStock) {
                for (mode in BookingMode.entries) {
                    val style = mode.style
                    ActionTile(stringResource(style.title), stringResource(style.subtitle), style.icon, style.color) { onBooking(mode) }
                }
            } else {
                NoBookingNotice(session.user.roleName)
            }
            SectionLabel(stringResource(R.string.home_section_info), Modifier.padding(start = 4.dp, top = 8.dp))
            ActionTile(
                stringResource(R.string.mode_lookup),
                stringResource(R.string.mode_lookup_subtitle),
                Icons.AutoMirrored.Rounded.ManageSearch,
                CxNavyTo,
                onLookup,
            )
        }
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    CxCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, color, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CxMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = CxMuted)
        }
    }
}

/** Instead of the booking tiles, for a role that may not move stock: why, and who can change it. */
@Composable
private fun NoBookingNotice(roleName: String?) {
    CxCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Lock, CxMuted, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_no_booking_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (roleName != null) stringResource(R.string.home_no_booking_text_role, roleName) else stringResource(R.string.home_no_booking_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CxMuted,
                )
            }
        }
    }
}

/** The initial in a circle, like the user pill in the web app's top bar. */
@Composable
private fun Avatar(name: String) {
    // The letter is decoration: the button around it says what it does.
    Box(Modifier.size(36.dp).clip(CircleShape).background(CxPrimary).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

private fun host(baseUrl: String): String = runCatching { URI(baseUrl).host }.getOrNull() ?: baseUrl
