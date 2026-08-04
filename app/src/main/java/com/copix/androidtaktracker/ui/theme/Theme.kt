package com.copix.androidtaktracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// WinTAKTracker-inspired dark green accent palette
private val Accent = Color(0xFF3DDC84)
private val AccentDim = Color(0xFF2A9B5C)
private val DarkBg = Color(0xFF121412)
private val DarkSurface = Color(0xFF1C1F1C)
private val DarkCard = Color(0xFF242824)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00391A),
    secondary = AccentDim,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onBackground = Color(0xFFE6EBE6),
    onSurface = Color(0xFFE6EBE6),
    onSurfaceVariant = Color(0xFFB0B8B0),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = AccentDim,
    onPrimary = Color.White,
    secondary = Accent,
    background = Color(0xFFF4F7F4),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EFE8),
    onBackground = Color(0xFF121412),
    onSurface = Color(0xFF121412),
    onSurfaceVariant = Color(0xFF4A554A),
)

@Composable
fun AndroidTakTrackerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
