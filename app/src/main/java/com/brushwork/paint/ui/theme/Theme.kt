package com.brushwork.paint.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Shared UI colors for the editor chrome (dark, like most painting apps). */
object BrushworkColors {
    val Chrome = Color(0xFF1E1F22)
    val ChromeHigh = Color(0xFF2A2C30)
    val ChromeBorder = Color(0xFF3A3D42)
    val CanvasBackdrop = Color(0xFF4A4C50)
    val Accent = Color(0xFF4DA3FF)
    val AccentDim = Color(0xFF2B5E93)
    val OnChrome = Color(0xFFE8EAED)
    val OnChromeDim = Color(0xFF9AA0A6)
    val Danger = Color(0xFFFF6B6B)
}

private val scheme = darkColorScheme(
    primary = BrushworkColors.Accent,
    onPrimary = Color(0xFF002B55),
    primaryContainer = BrushworkColors.AccentDim,
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFFB4C7E7),
    background = Color(0xFF141517),
    onBackground = BrushworkColors.OnChrome,
    surface = BrushworkColors.Chrome,
    onSurface = BrushworkColors.OnChrome,
    surfaceVariant = BrushworkColors.ChromeHigh,
    onSurfaceVariant = BrushworkColors.OnChromeDim,
    surfaceContainer = BrushworkColors.Chrome,
    surfaceContainerHigh = BrushworkColors.ChromeHigh,
    surfaceContainerHighest = Color(0xFF33363B),
    surfaceContainerLow = Color(0xFF1A1B1E),
    outline = BrushworkColors.ChromeBorder,
    error = BrushworkColors.Danger,
)

@Composable
fun BrushworkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
