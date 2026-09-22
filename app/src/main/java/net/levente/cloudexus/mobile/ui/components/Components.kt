package net.levente.cloudexus.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.ui.theme.CxBorder
import net.levente.cloudexus.mobile.ui.theme.CxMuted
import net.levente.cloudexus.mobile.ui.theme.CxNavyGradient
import net.levente.cloudexus.mobile.ui.theme.CxNavyText
import net.levente.cloudexus.mobile.ui.theme.CxPrimary
import net.levente.cloudexus.mobile.ui.theme.CxPrimaryDark

/**
 * The navy gradient header every screen starts with, like the web app's
 * sidebar. It runs under the status bar; [content] goes below the title.
 */
@Composable
fun CxHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CxNavyGradient)
            .statusBarsPadding()
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CxNavyText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
        Column(Modifier.padding(start = if (onBack != null) 16.dp else 0.dp, end = 12.dp), content = content)
    }
}

/** A white card with the web app's soft border and shadow. */
@Composable
fun CxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: Color = CxBorder,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val cardModifier = modifier.shadow(6.dp, shape, ambientColor = SHADOW, spotColor = SHADOW)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = cardModifier, shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, border)) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = cardModifier, shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, border)) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

private val SHADOW = Color(0x331D2146)

/** The colored rounded-square icon of the web dashboard's cards. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 48.dp, soft: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(if (soft) color.copy(alpha = 0.12f) else color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (soft) color else Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** The app mark: a box on the brand gradient, next to the name. */
@Composable
fun CxLogo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(CxPrimary, CxPrimaryDark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Cloudexus", style = MaterialTheme.typography.headlineSmall, color = Color.White)
    }
}

/** A large, glove-friendly button. */
@Composable
fun CxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = CxPrimary,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.35f), disabledContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The small upper-case group label of the web sidebar ("KÉSZLETKEZELÉS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = CxMuted)
}

/** A rounded pill, e.g. the current shelf in the header. */
@Composable
fun Pill(text: String, icon: ImageVector?, onClick: (() -> Unit)?, modifier: Modifier = Modifier, dark: Boolean = true) {
    val background = if (dark) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(onClick = onClick ?: {}, enabled = onClick != null, modifier = modifier, shape = RoundedCornerShape(50), color = background) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = foreground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Centered icon and text for an empty list. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconTile(icon, CxPrimary, size = 64.dp, soft = true)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CxMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
