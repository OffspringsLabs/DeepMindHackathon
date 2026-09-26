package com.offspringslabs.disha.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Forest = Color(0xFF0B3D2E)
val Saffron = Color(0xFFF2B84B)
val Sand = Color(0xFFF7F1E3)
val Ink = Color(0xFF1E2A26)
val Muted = Color(0xFF6B7A74)
val Ok = Color(0xFF1B8A5A)
val Warn = Color(0xFFB8541F)

private val scheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    secondary = Saffron,
    onSecondary = Ink,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE6D3),
    onSurfaceVariant = Muted,
    primaryContainer = Color(0xFFD9EAE1),
    onPrimaryContainer = Forest,
)

@Composable
fun DishaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
