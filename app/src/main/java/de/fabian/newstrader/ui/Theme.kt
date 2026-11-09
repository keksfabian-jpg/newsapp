package de.fabian.newstrader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0A1020)
    val Card = Color(0xFF121A2E)
    val Card2 = Color(0xFF1A2540)
    val Line = Color(0xFF243152)
    val Accent = Color(0xFF3B82F6)
    val AccentSoft = Color(0xFF93C5FD)
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFEF4444)
    val Amber = Color(0xFFF59E0B)
    val Text = Color(0xFFE6EAF2)
    val Muted = Color(0xFF8A96AD)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = Color.White,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Card,
            onSurface = C.Text,
            surfaceVariant = C.Card2,
            onSurfaceVariant = C.Muted,
            secondaryContainer = C.Accent.copy(alpha = 0.22f),
            onSecondaryContainer = C.Text,
            outline = C.Line,
        ),
        content = content,
    )
}
