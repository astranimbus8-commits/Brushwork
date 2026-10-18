package com.brushwork.paint.ui.vector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.placement.StripMenu
import com.brushwork.paint.ui.placement.StripMenuButton
import com.brushwork.paint.ui.placement.StripTextButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.vector.draw.VectorEraseMode

/**
 * v1.9 (R11 a, design 2.4.12; owner A5): the erase-mode controls of the Eraser's strip and of the
 * four tools (Curve, Polyline, Path, Shape) while they erase a vector or a legacy shape layer:
 * - [item]: v1.8's three mode buttons in the Eraser's row 1 (120 dp, never leaves it), the
 *   selected mode's [VectorEraseMode.note] being the More row's hint (the strip's builder sets it);
 * - [pixelsItem]: [EraseLabels19.TURN_INTO_PIXELS], the last item of the Eraser's More row, in
 *   the warning tint, away from the mode buttons: it opens [ErasePixelsDialog];
 * - [modeCell]: the four tools' row-1 cell (56 dp, never leaves row 1), a dropdown whose face is
 *   the mode's icon: the three modes with their notes, a divider and
 *   [EraseLabels19.TURN_INTO_PIXELS] in the warning tint.
 *
 * Each is null when [Layer] holds no objects to erase (not a vector or shape layer): the strip
 * then is v1.8's raster one. The mode is the Eraser's, remembered as in v1.8
 * (`VectorEraserModes`); the tools' cell changes the same mode.
 */
object VectorEraseChooser {
    /** The key of the Eraser's v1.8 mode buttons ([VectorEraserStrip.KEY]). */
    const val KEY = VectorEraserStrip.KEY

    /** The warning tint of [EraseLabels19.TURN_INTO_PIXELS] (the editor's danger colour). */
    val WARNING_TINT: Color get() = BrushworkColors.Danger

    /** Set on what is drawn in [WARNING_TINT] (true), so a test can tell it without pixels. */
    val WarningTint = SemanticsPropertyKey<Boolean>("v19.erase.warningTint")

    /** [layer] holds objects the erase-mode controls apply to: a vector layer or a legacy shape layer. */
    fun holdsObjects(layer: Layer?): Boolean = layer != null && (layer.isVectorLayer || layer.isShapeLayer)

    /** The Eraser's row-1 chooser: v1.8's three mode buttons (120 dp), or null when [layer] is not a vector or shape layer. */
    @Suppress("UNUSED_PARAMETER")
    fun item(ctx: StripContext, layer: Layer?, mode: VectorEraseMode, onMode: (VectorEraseMode) -> Unit): StripItem? {
        if (!holdsObjects(layer)) return null
        val key = StripKeysV19.ERASE_MODES
        return StripItem(key.key, mode.label, key.widthDp.dp, StripRow.ROW1, overflowRank = key.rank) {
            VectorEraserStrip.ModeButtons(mode, Modifier.testTag(V19Tags.ERASE_CHOOSER), onMode)
        }
    }

    /**
     * The Eraser's More-row action [EraseLabels19.TURN_INTO_PIXELS] (StripKeysV19.ERASE_PIXELS,
     * last, warning tint), or null when [layer] is not a vector or shape layer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pixelsItem(ctx: StripContext, layer: Layer?, onPixels: () -> Unit): StripItem? {
        if (!holdsObjects(layer)) return null
        val key = StripKeysV19.ERASE_PIXELS
        return StripItem(key.key, EraseLabels19.TURN_INTO_PIXELS, key.widthDp.dp, StripRow.MORE) {
            Box(Modifier.testTag(V19Tags.ERASE_PIXELS).semantics(properties = warned)) {
                // (StripTextButton pads its text 4 dp on each side.)
                FittingFontScale(EraseLabels19.TURN_INTO_PIXELS, (key.widthDp - 8).dp, StripLayout.LINE_DP.dp) {
                    StripTextButton(EraseLabels19.TURN_INTO_PIXELS, key.widthDp.dp, onClick = onPixels, color = WARNING_TINT)
                }
            }
        }
    }

    /**
     * I27: [content] under the largest font scale, at most the device's and at least 1 (so the
     * text stays at least 11 sp), at which [text] laid out as `StripTextButton` lays it out (11 sp
     * on 13 sp lines, at most two lines) fits [width] × [height]: at a large font scale the button
     * keeps its text inside its declared width and its More-row line instead of cutting it.
     */
    @Composable
    internal fun FittingFontScale(text: String, width: Dp, height: Dp, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val style = LocalTextStyle.current.merge(TextStyle(fontSize = BUTTON_SP.sp, lineHeight = BUTTON_LINE_SP.sp))
        val scale = remember(text, width, height, density, style, measurer) {
            var s = density.fontScale
            while (s > 1f) {
                val at = Density(density.density, s)
                val r = measurer.measure(
                    text,
                    style = style,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = with(at) { width.roundToPx() }, maxHeight = with(at) { height.roundToPx() }),
                    density = at,
                )
                if (!r.hasVisualOverflow && !r.multiParagraph.didExceedMaxLines) break
                s = maxOf(1f, s - SCALE_STEP)
            }
            s
        }
        if (scale == density.fontScale) {
            content()
        } else {
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
        }
    }

    /**
     * The four tools' row-1 mode cell while erasing a vector or shape layer (StripKeysV19.ERASE_MODE_CELL,
     * 56 dp, overflow rank 0): a dropdown cell described by [EraseLabels19.MODE_CELL], whose menu
     * lists the three modes with their descriptions, a divider and [EraseLabels19.TURN_INTO_PIXELS]
     * ([onPixels]: the tool opens [ErasePixelsDialog]). Null when [layer] is not a vector or shape layer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun modeCell(ctx: StripContext, layer: Layer?, mode: VectorEraseMode, onMode: (VectorEraseMode) -> Unit, onPixels: () -> Unit): StripItem? {
        if (!holdsObjects(layer)) return null
        val key = StripKeysV19.ERASE_MODE_CELL
        val description = EraseLabels19.MODE_CELL.format(mode.label)
        return StripItem(key.key, description, key.widthDp.dp, StripRow.ROW1, overflowRank = key.rank) {
            ModeCell(description, mode, onMode, onPixels)
        }
    }

    private val warned: SemanticsPropertyReceiver.() -> Unit = { this[WarningTint] = true }

    @Composable
    private fun ModeCell(description: String, mode: VectorEraseMode, onMode: (VectorEraseMode) -> Unit, onPixels: () -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box(Modifier.testTag(V19Tags.ERASE_MODE_CELL)) {
            StripMenuButton(description, StripKeysV19.ERASE_MODE_CELL.widthDp.dp, open, onClick = { open = true }, announceContent = false) {
                Icon(iconOf(mode), contentDescription = null, tint = BrushworkColors.OnChrome, modifier = Modifier.size(22.dp))
            }
            StripMenu(open, onDismiss = { open = false }) {
                VectorEraseMode.entries.forEach { m ->
                    val chosen = m == mode
                    DropdownMenuItem(
                        text = {
                            // The mode's description under its name, wrapping (it is long at a large font scale).
                            Column(Modifier.widthIn(max = MENU_TEXT_MAX_DP.dp)) {
                                Text(m.label, color = if (chosen) BrushworkColors.Accent else BrushworkColors.OnChrome)
                                Text(m.note, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                            }
                        },
                        leadingIcon = { Icon(iconOf(m), contentDescription = null, tint = if (chosen) BrushworkColors.Accent else BrushworkColors.OnChrome) },
                        onClick = {
                            open = false
                            onMode(m)
                        },
                        modifier = Modifier.testTag(V19Tags.ERASE_MODE_PREFIX + m.name).semantics { selected = chosen },
                    )
                }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                DropdownMenuItem(
                    text = { Text(EraseLabels19.TURN_INTO_PIXELS, color = WARNING_TINT) },
                    onClick = {
                        open = false
                        onPixels()
                    },
                    modifier = Modifier.testTag(V19Tags.ERASE_PIXELS).semantics(properties = warned),
                )
            }
        }
    }

    /** Widest text column of the mode cell's menu (dp): a description wraps rather than widening the menu past a phone. */
    private const val MENU_TEXT_MAX_DP = 220

    /** `StripTextButton`'s text size and line height (sp). */
    private const val BUTTON_SP = 11
    private const val BUTTON_LINE_SP = 13

    /** [FittingFontScale] tries font scales this far apart. */
    private const val SCALE_STEP = 0.05f
}
