package net.levente.cloudexus.mobile.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// The web app's design tokens (cloudexus/src/View/Css/base/variables.css), so
// the handheld looks like the same product.
val CxPrimary = Color(0xFF4F5BD5)
val CxPrimaryDark = Color(0xFF3C46B8)
val CxPrimarySoft = Color(0xFFEEF0FD)

val CxNavyFrom = Color(0xFF1B1F3B)
val CxNavyTo = Color(0xFF2D2F6B)
val CxNavyText = Color(0xFFB7BCE0)

val CxBackground = Color(0xFFF2F4FB)
val CxSurface = Color(0xFFFFFFFF)
val CxText = Color(0xFF1C2333)
val CxMuted = Color(0xFF7C8494)
val CxBorder = Color(0xFFE7E9F2)

val CxSuccess = Color(0xFF1FA971)
val CxSuccessSoft = Color(0xFFE3F6EE)
val CxWarning = Color(0xFFE2A13A)
val CxWarningSoft = Color(0xFFFCF3E3)
val CxDanger = Color(0xFFE0526A)
val CxDangerSoft = Color(0xFFFCE8EC)

/** The sidebar gradient of the web app, used for screen headers. */
val CxNavyGradient = Brush.linearGradient(listOf(CxNavyFrom, CxNavyTo))
