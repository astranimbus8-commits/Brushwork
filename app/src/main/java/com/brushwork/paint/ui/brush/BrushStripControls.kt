package com.brushwork.paint.ui.brush

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow

/**
 * v1.8 (design §3.9): the brush controls of a painting tool's strip as [StripItem]s, each at a
 * fixed width (measured, rounded up to 4 dp): "Choose brush", "Brush size", "Opacity" ("Strength"
 * for smudge and blur) and "Pressure changes size". Shared by the Brush, Eraser, Smudge and Blur
 * strips (row 1), the vector eraser (some on the More row) and the clone stamp (More row). The
 * v1.7 handles are unchanged; "All brush settings" stays inside the size and opacity popups.
 */
internal object BrushStripItems {
    const val CHOOSE = "brush.choose"
    const val SIZE = "brush.size"
    const val OPACITY = "brush.opacity"
    const val PRESSURE = "brush.pressure"

    /** "Choose brush": the preset's name (ellipsized) and ▾. */
    const val CHOOSE_DP = 96f

    /** "Brush size": up to "1000 px" after its icon. */
    const val SIZE_DP = 76f

    /** "Opacity" / "Strength": up to "100%" (13 sp) after its icon. */
    const val OPACITY_DP = 64f

    /** "Pressure changes size: on / off": a 40 dp icon toggle, as in v1.7. */
    const val PRESSURE_DP = 40f

    const val CHOOSE_BRUSH = "Choose brush"
    const val BRUSH_SIZE = "Brush size"
    const val OPACITY_LABEL = "Opacity"
    const val STRENGTH = "Strength"
    const val ALL_SETTINGS = "All brush settings"

    fun pressureLabel(on: Boolean) = if (on) "Pressure changes size: on" else "Pressure changes size: off"

    /** The four controls of [tool] (null without a preset); [openPanel] opens the brush panel (an overlay). */
    @Composable
    fun of(tool: BrushTool, openPanel: () -> Unit): BrushItems? {
        val controller = tool.controller
        val preset = controller.presetFor(tool.id) ?: return null
        val store = remember(controller) { BrushPresetStore.get(controller.appContext) }
        val kind = StrokeKind.of(tool.id, preset)
        val strengthLike = kind == StrokeKind.SMUDGE || kind == StrokeKind.BLUR
        val onEdit: PresetEdit = { persist, transform -> store.edit(controller, tool.id, persist, transform) }
        val done = { onEdit(true) { it } }
        val choose = StripItem(CHOOSE, CHOOSE_BRUSH, CHOOSE_DP.dp) { ChooseBrushChip(preset.name, CHOOSE_DP.dp, openPanel) }
        val size = StripItem(SIZE, BRUSH_SIZE, SIZE_DP.dp) {
            StripReadout(Icons.Filled.LineWeight, formatSize(preset.size), BRUSH_SIZE, SIZE_DP.dp, onMore = openPanel, onClose = done) {
                BrushSizeField(preset.size, { v -> onEdit(false) { it.copy(size = v) } }, done, label = BRUSH_SIZE)
            }
        }
        val opacity = if (strengthLike) {
            StripItem(OPACITY, STRENGTH, OPACITY_DP.dp) {
                StripReadout(Icons.Filled.Opacity, percent(preset.mixing), STRENGTH, OPACITY_DP.dp, onMore = openPanel, onClose = done) {
                    PercentField(STRENGTH, preset.mixing, { v -> onEdit(false) { it.copy(mixing = v) } }, done)
                }
            }
        } else {
            StripItem(OPACITY, OPACITY_LABEL, OPACITY_DP.dp) {
                StripReadout(Icons.Filled.Opacity, percent(preset.opacity), OPACITY_LABEL, OPACITY_DP.dp, onMore = openPanel, onClose = done) {
                    PercentField(OPACITY_LABEL, preset.opacity, { v -> onEdit(false) { it.copy(opacity = v) } }, done)
                }
            }
        }
        val pressureOn = preset.pressureSize
        val pressure = StripItem(PRESSURE, pressureLabel(pressureOn), PRESSURE_DP.dp) {
            ToolIconButton(
                icon = Icons.Filled.Gesture,
                contentDescription = pressureLabel(pressureOn),
                onClick = { store.edit(controller, tool.id, persist = true) { it.copy(pressureSize = !it.pressureSize) } },
                selected = pressureOn,
                size = PRESSURE_DP.dp,
            )
        }
        return BrushItems(choose, size, opacity, pressure)
    }
}

/** The brush controls of one tool (see [BrushStripItems.of]). */
internal class BrushItems(val choose: StripItem, val size: StripItem, val opacity: StripItem, val pressure: StripItem) {
    val all: List<StripItem> get() = listOf(choose, size, opacity, pressure)
}

/** This item on [row], with [overflowRank] (AUTO when above 0). */
internal fun StripItem.placed(row: StripRow, overflowRank: Int = 0): StripItem =
    StripItem(key = key, label = label, width = width, row = row, overflowRank = overflowRank, shown = shown, content = content)

/**
 * v1.7's "Choose brush" chip, [width] wide: the preset's name (11 sp, up to two lines, so every
 * library name fits whole, "Mechanical pencil" the longest) and ▾ (described "Choose brush").
 */
@Composable
internal fun ChooseBrushChip(name: String, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .width(width)
            .heightIn(min = 40.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = BrushworkColors.OnChrome,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = BrushStripItems.CHOOSE_BRUSH, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
    }
}

/**
 * v1.7's number readout ([icon] described [description], then [text]), [width] wide: a tap opens
 * a popup anchored here with [editor] (a field and its slider) and "All brush settings" ([onMore]).
 */
@Composable
internal fun StripReadout(
    icon: ImageVector,
    text: String,
    description: String,
    width: Dp,
    onMore: () -> Unit,
    onClose: () -> Unit,
    editor: @Composable () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    // Saves whatever was typed but not yet committed with Done.
    val close = { editing = false; onClose() }
    Box(Modifier.width(width)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Edit $description") { editing = true }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = description, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text(text, fontSize = 13.sp, color = BrushworkColors.OnChrome, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        DropdownMenu(expanded = editing, onDismissRequest = close, modifier = Modifier.closeOnSecondFinger(close), containerColor = IbisColors.Menu) {
            // Fixed width: the menu measures its content's intrinsic width.
            Column(Modifier.width(300.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                editor()
                TextButton(onClick = { close(); onMore() }, modifier = Modifier.align(Alignment.End)) { Text(BrushStripItems.ALL_SETTINGS) }
            }
        }
    }
}
