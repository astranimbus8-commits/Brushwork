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
    /** v1.6: the ibisPaint canvas surround ([IbisColors.Surround]). */
    val CanvasBackdrop = IbisColors.Surround
    val Accent = Color(0xFF4DA3FF)
    val AccentDim = Color(0xFF2B5E93)
    val OnChrome = Color(0xFFE8EAED)
    val OnChromeDim = Color(0xFF9AA0A6)
    val Danger = Color(0xFFFF6B6B)
}

/**
 * The ibisPaint look (v1.6 §3.7.1; colors sampled from the reference screenshots, V13). The canvas
 * surround is light, panels are dark translucent. Areas change the look BY VALUE here (area E
 * owns this file), never by renaming a token.
 */
object IbisColors {
    /** Canvas surround (light grey). */
    val Surround = Color(0xFFBFBFBF)

    /** Top row circles: normal, disabled, on. */
    val TopButton = Color(0xFF9A9A9A)
    val TopButtonDisabled = Color(0xFFB1B1B1)
    val TopButtonOn = Color(0xFF396BB1)
    val TopGlyph = Color(0xFFFFFFFF)

    /** Bottom bar, and the open state of its tool / layers slots (box and 1 dp edge). */
    val BottomBar = Color(0xFF646464)
    val BottomBarOpen = Color(0xFF4C4C4C)
    val BottomBarOpenEdge = Color(0xFF7E7E7E)

    /** Brush slider rows: size fill on the track, −/+ buttons, value text, thumb. */
    val SizeFill = Color(0xFF396BB1)
    val SliderTrack = Color(0xFFA7A6A7)
    val SliderButton = Color(0xFF0D0D0D)
    val SliderValue = Color(0xFF1A1A1A)
    val SliderThumb = Color(0xFFFFFFFF)

    /** Tool menu, layer window frame (black α0.50, sampled). */
    val Panel = Color(0x80000000)

    /** Layer preview pane, empty list filler. */
    val PanelOpaque = Color(0xFF575757)

    /** Left buttons pane, right strip, blend row. */
    val PanelStrip = Color(0xFF000000)
    val OpacityRow = Color(0xFF343434)

    /** Layer list rows. */
    val ListRow = Color(0xFFE8E8E8)
    val ListRowSelected = Color(0xFFCBDAEC)
    val ListText = Color(0xFF111111)
    val ThumbSelectedBorder = Color(0xFF3F6BA3)

    /** BwSheet panels: black α0.78 (readable small text over a light canvas). */
    val Sheet = Color(0xC7000000)

    /** The floating options strip panel: black α0.55. */
    val OptionsStrip = Color(0x8C000000)

    /** X / Y pill and its beveled cells. */
    val Pill = Color(0x9E000000)
    val PillCell = Color(0xFF3A3A3A)
    val BevelLight = Color(0x2EFFFFFF)
    val BevelDark = Color(0x73000000)
    val PillPrefix = Color(0xFF9EC5FF)

    val Accent = Color(0xFF396BB1)

    /** The selected Path control point (Blender's orange). */
    val SplineSelected = Color(0xFFFFA000)

    /** Transparency display: light checker, dark checker (two shades each; white = [CheckerLight]). */
    val CheckerLight = Color(0xFFFFFFFF)
    val CheckerLight2 = Color(0xFFD2D2D2)
    val CheckerDark = Color(0xFF6E6E6E)
    val CheckerDark2 = Color(0xFF4A4A4A)
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
