package com.brushwork.paint.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.select.ColorSelectSettings
import com.brushwork.paint.tools.select.LassoCommit
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import kotlin.math.roundToInt

// Color select's strip (v1.8, design §3.5 and §3.9, area C).

/** Item keys and declared widths (dp, rounded up to 4 dp from their measured widths). */
internal object ColorSelectStrip {
    const val RANGE = "color.range"
    const val FUZZINESS = "color.fuzz"
    const val PICKER = "color.picker"
    const val SELECT = "color.select"
    const val CURRENT = "color.current"
    const val CLEAR = "color.clear"
    const val INVERT = "color.invert"
    const val REFER = "color.refer"
    const val ANTI_ALIAS = "color.aa"

    const val RANGE_DP = 92
    const val FUZZINESS_DP = 64
    const val INVERT_DP = 96
    const val REFER_DP = 80

    /** The fuzziness panel's height (dp): one line, under the strip's row. */
    const val PANEL_DP = 44

    /** The fuzziness panel's test tag. */
    const val PANEL_TAG = "color.fuzz.panel"

    /** The preset popup's width (dp). */
    const val PRESETS_DP = 296
}

/**
 * Color select: row 1 the mode dropdown, the preset chip (its popup of presets), the fuzziness (AUTO 1:
 * it heads the More row when row 1 is full), the color picker's add and the check that selects.
 * The More row: the brush color's add, clearing the samples, invert, Refer, anti-alias and the
 * shortcuts. While the selection is computed, the hint says so.
 */
@Composable
internal fun colorSelectStrip(tool: ColorSelectTool, c: EditorController): StripSpec {
    val s = tool.settings
    var menu by remember(tool) { mutableStateOf(false) }
    var picker by remember(tool) { mutableStateOf(false) }
    val count = tool.sampleColors.size
    fun set(next: ColorSelectSettings) = tool.updateSettings(next)
    val items = listOf(
        selectionModeItem(tool.mode) { tool.mode = it },
        StripItem(ColorSelectStrip.RANGE, ColorSelectLabels18.COLOR_RANGE, ColorSelectStrip.RANGE_DP.dp) {
            ColorRangeChip(s.preset) { set(tool.settings.copy(preset = it)) }
        },
        StripItem(ColorSelectStrip.FUZZINESS, ColorSelectLabels18.FUZZINESS, ColorSelectStrip.FUZZINESS_DP.dp, overflowRank = 1) {
            FuzzinessChip(s.fuzziness) { set(tool.settings.copy(fuzziness = it)) }
        },
        StripItem(ColorSelectStrip.PICKER, ColorSelectLabels18.FROM_PICKER, IconItemWidth) {
            ToolIconButton(Icons.Outlined.Palette, ColorSelectLabels18.FROM_PICKER, onClick = { picker = true }, size = IconItemWidth)
        },
        StripItem(ColorSelectStrip.SELECT, ColorSelectLabels18.SELECT_COLORS, IconItemWidth) {
            ToolIconButton(Icons.Filled.Check, ColorSelectLabels18.SELECT_COLORS, onClick = { tool.selectColors() }, enabled = !tool.busy, size = IconItemWidth)
        },
        StripItem(ColorSelectStrip.CURRENT, ColorSelectLabels18.USE_CURRENT, IconItemWidth, row = StripRow.MORE) {
            CurrentColorButton(c.color) { tool.useCurrentColor() }
        },
        StripItem(ColorSelectStrip.CLEAR, ColorSelectLabels18.CLEAR_SAMPLES, IconItemWidth, row = StripRow.MORE) {
            ToolIconButton(Icons.Outlined.DeleteSweep, ColorSelectLabels18.CLEAR_SAMPLES, onClick = { tool.clearSamples() }, enabled = count > 0, size = IconItemWidth)
        },
        StripItem(ColorSelectStrip.INVERT, ColorSelectLabels18.INVERT_RANGE, ColorSelectStrip.INVERT_DP.dp, row = StripRow.MORE) {
            StripToggle(ColorSelectLabels18.INVERT_RANGE, s.invert, ColorSelectStrip.INVERT_DP.dp) { set(tool.settings.copy(invert = it)) }
        },
        StripItem(ColorSelectStrip.REFER, "Refer: ${s.source.label}", ColorSelectStrip.REFER_DP.dp, row = StripRow.MORE) {
            StripChoiceChip("Refer", SampleSource.entries, s.source, { it.label }, ColorSelectStrip.REFER_DP.dp) { set(tool.settings.copy(source = it)) }
        },
        StripItem(ColorSelectStrip.ANTI_ALIAS, "Anti-alias", ANTI_ALIAS_DP.dp, row = StripRow.MORE) {
            StripToggle("Anti-alias", s.antiAlias, ANTI_ALIAS_DP.dp) { set(tool.settings.copy(antiAlias = it)) }
        },
    ) + selectionShortcutItems(c) { menu = true }
    return StripSpec(
        items = items,
        hint = if (tool.busy) LassoCommit.BUSY_LABEL else null,
        overlays = {
            SelectionMenuOverlay(c, menu) { menu = false }
            if (picker) {
                ColorPickerDialog(
                    initial = tool.sampleColors.lastOrNull() ?: c.color,
                    onPick = { tool.addSample(it) },
                    onDismiss = { picker = false },
                )
            }
        },
        movedInto = modesMovedInto + ColorPreset.entries.associate { it.label to ColorSelectStrip.RANGE },
    )
}

/**
 * The preset chip: its name over the current preset (cut to the width; the state says it whole), a
 * popup of the thirteen presets as chips; one is on at a time, a tap picks it and closes the popup.
 */
@Composable
private fun ColorRangeChip(preset: ColorPreset, onPick: (ColorPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StripValueButton(
            ColorSelectLabels18.COLOR_RANGE, preset.label, ColorSelectLabels18.COLOR_RANGE, ColorSelectStrip.RANGE_DP.dp,
            onClick = { open = true }, state = preset.label, fixed = true,
        )
        StripDropdown(open, onDismiss = { open = false }) {
            FlowRow(
                Modifier.width(ColorSelectStrip.PRESETS_DP.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (p in ColorPreset.entries) {
                    FilterChip(
                        selected = p == preset,
                        onClick = {
                            onPick(p)
                            open = false
                        },
                        label = { Text(p.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = BrushworkColors.OnChrome,
                            selectedContainerColor = BrushworkColors.AccentDim,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The fuzziness readout (0..[ColorSelectSettings.MAX_FUZZINESS]), known by its name with its value
 * as its state. A tap opens a one-line opaque slider panel right under the strip's row, as wide as
 * the screen, so the canvas and its red preview stay in sight while the slider moves; Back, a tap
 * elsewhere or a second finger closes it.
 */
@Composable
private fun FuzzinessChip(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StripValueButton(
            ColorSelectLabels18.FUZZINESS, "$value", ColorSelectLabels18.FUZZINESS, ColorSelectStrip.FUZZINESS_DP.dp,
            onClick = { open = true }, state = "$value",
        )
        if (open) FuzzinessPanel(value, onChange) { open = false }
    }
}

/** Places a popup at the window's left edge, [gap] px under its anchor (the strip item). */
private class UnderRow(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(0, anchorBounds.bottom + gap)
}

@Composable
private fun FuzzinessPanel(value: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position = remember(gap) { UnderRow(gap) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ColorSelectStrip.PANEL_DP.dp)
                .testTag(ColorSelectStrip.PANEL_TAG)
                .background(IbisColors.Menu)
                .closeOnSecondFinger(onDismiss)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$value", fontSize = 13.sp, color = BrushworkColors.OnChrome, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(0, ColorSelectSettings.MAX_FUZZINESS)) },
                valueRange = 0f..ColorSelectSettings.MAX_FUZZINESS.toFloat(),
                colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = ColorSelectLabels18.FUZZINESS },
            )
        }
    }
}

/** The brush color's add: a dot of that color with a +, known by its handle. */
@Composable
private fun CurrentColorButton(color: Int, onClick: () -> Unit) {
    val shown = Color(color)
    Box(
        Modifier
            .size(IconItemWidth)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = ColorSelectLabels18.USE_CURRENT
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(shown)
                .border(1.5.dp, BrushworkColors.OnChrome, CircleShape),
        )
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = if (shown.luminance() > 0.5f) Color.Black else Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}
