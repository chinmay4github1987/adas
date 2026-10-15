package com.drivesentinel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Alert colours: saturated and high-contrast so they read at a glance while driving. */
object Neon {
    val Red = Color(0xFFFF1744)
    val Amber = Color(0xFFFFAB00)
    val Cyan = Color(0xFF00E5FF)
    val Green = Color(0xFF00E676)
    val Magenta = Color(0xFFE040FB)
    val Panel = Color(0xB3060B12)
}

/** Futuristic cockpit surfaces: deep space-blue glass with glowing cyan edges. */
object Cockpit {
    val Background = Color(0xFF03060A)
    val Glass = Color(0xB30A1420)
    val GlassStrong = Color(0xE60A1420)
    val Edge = Color(0x6600E5FF)
    val EdgeDim = Color(0x2600E5FF)
    val Grid = Color(0x1400E5FF)
    val Text = Color(0xFFE6F7FF)
    val TextMuted = Color(0xFF7FA3B8)
    val Track = Color(0x33FFFFFF)
}

private val DarkColors = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Color.Black,
    secondary = Neon.Green,
    onSecondary = Color.Black,
    tertiary = Neon.Amber,
    error = Neon.Red,
    background = Cockpit.Background,
    onBackground = Cockpit.Text,
    surface = Color(0xFF08111B),
    onSurface = Cockpit.Text,
    surfaceVariant = Color(0xFF0F1C2A),
    onSurfaceVariant = Cockpit.TextMuted,
    outline = Cockpit.Edge,
)

@Composable
fun DriveSentinelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = Typography(), content = content)
}
