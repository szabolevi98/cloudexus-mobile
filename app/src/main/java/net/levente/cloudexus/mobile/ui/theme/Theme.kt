package net.levente.cloudexus.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val colors = lightColorScheme(
    primary = CxPrimary,
    onPrimary = CxSurface,
    primaryContainer = CxPrimarySoft,
    onPrimaryContainer = CxPrimaryDark,
    secondary = CxNavyTo,
    onSecondary = CxSurface,
    // Tonal buttons (the quantity stepper) use the web's soft indigo, not Material's default lavender.
    secondaryContainer = CxPrimarySoft,
    onSecondaryContainer = CxPrimaryDark,
    background = CxBackground,
    onBackground = CxText,
    surface = CxSurface,
    onSurface = CxText,
    surfaceVariant = CxBackground,
    onSurfaceVariant = CxMuted,
    surfaceContainerLowest = CxSurface,
    surfaceContainerLow = CxSurface,
    surfaceContainer = CxSurface,
    surfaceContainerHigh = CxSurface,
    surfaceContainerHighest = CxBackground,
    outline = CxBorder,
    outlineVariant = CxBorder,
    error = CxDanger,
    onError = CxSurface,
    errorContainer = CxDangerSoft,
    onErrorContainer = CxDanger,
)

// --cx-radius: 14px and --cx-radius-sm: 10px on the web.
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val base = Typography()

private val typography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

@Composable
fun CloudexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, typography = typography, content = content)
}
