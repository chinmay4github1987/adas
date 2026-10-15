package com.drivesentinel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Neon {
    val Red = Color(0xFFFF1744)
    val Amber = Color(0xFFFFAB00)
    val Cyan = Color(0xFF00E5FF)
    val Green = Color(0xFF76FF03)
    val Magenta = Color(0xFFE040FB)
    val Panel = Color(0xCC0B0F14)
}

private val DarkColors = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Color.Black,
    secondary = Neon.Green,
    onSecondary = Color.Black,
    tertiary = Neon.Amber,
    error = Neon.Red,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0B0F14),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A222C),
    onSurfaceVariant = Color(0xFFB0BEC5),
)

@Composable
fun DriveSentinelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = Typography(), content = content)
}
