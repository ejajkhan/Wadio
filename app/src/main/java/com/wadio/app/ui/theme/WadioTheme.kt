package com.wadio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B1B33),
    background = Color(0xFF0B0F14), onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF141A22), onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1D2530), onSurfaceVariant = Color(0xFFB4BECC),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1B5FC1), onPrimary = Color.White,
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF10151C),
    surface = Color.White, onSurface = Color(0xFF10151C),
    surfaceVariant = Color(0xFFE7ECF3), onSurfaceVariant = Color(0xFF48525F),
    error = Color(0xFFB3261E),
)

@Composable
fun WadioTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
