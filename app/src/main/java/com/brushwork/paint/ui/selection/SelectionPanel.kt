package com.brushwork.paint.ui.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.segmentation.SmartTarget
import com.brushwork.paint.tools.select.MaskMath
import com.brushwork.paint.tools.select.SelectionEdits
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/**
 * The selection menu: copy / cut / paste / deselect, select all / invert, grow / shrink /
 * feather, select from layer opacity, smart select (subject, sky, ...) and edits of the selected
 * pixels.
 *
 * Operations that run in the background or may report a problem close the sheet first, so the
 * editor's busy overlay (with its Stop button) and messages are visible.
 */
@Composable
fun SelectionPanel(controller: EditorController, onDismiss: () -> Unit) {
    val sel = controller.selection
    val hasSelection = sel != null
    var morphPx by rememberSaveable { mutableStateOf(4.0) }
    var featherPx by rememberSaveable { mutableStateOf(8.0) }
    var mode by rememberSaveable { mutableStateOf(SelectionMode.REPLACE) }
    var pickFillColor by remember { mutableStateOf(false) }

    /** Closes the sheet, then runs [action] (the editor shows its progress / messages). */
    fun closeThen(action: () -> Unit) {
        onDismiss()
        action()
    }

    BwSheet(title = "Selection", onDismiss = onDismiss) {
        Text(
            if (sel == null) "Nothing is selected" else "Selected area: ${sel.bounds.width()} × ${sel.bounds.height()} px",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
        )
        Spacer(Modifier.height(8.dp))
        // Clipboard first: copy / cut the selected pixels of the active layer (the whole layer's
        // painting without a selection) and paste them back as a new layer, in place.
        TileRow {
            ActionTile(Icons.Outlined.ContentCopy, if (hasSelection) "Copy" else "Copy layer") { closeThen { controller.copySelection() } }
            ActionTile(Icons.Outlined.ContentCut, if (hasSelection) "Cut" else "Cut layer") { closeThen { controller.cutSelection() } }
            ActionTile(Icons.Outlined.ContentPaste, "Paste", enabled = controller.clipboard != null) { closeThen { controller.paste() } }
            ActionTile(Icons.Outlined.Deselect, "Deselect", enabled = hasSelection) { controller.deselect() }
        }
        Spacer(Modifier.height(6.dp))
        TileRow {
            ActionTile(Icons.Outlined.SelectAll, "Select all") { controller.selectAll() }
            ActionTile(Icons.Outlined.InvertColors, "Invert") { controller.invertSelection() }
        }

        SectionHeader("Modify selection")
        PanelCard {
            NumberField(
                label = "Grow / shrink by",
                value = morphPx,
                onValueChange = { morphPx = it },
                decimals = 0,
                suffix = "px",
                min = 1.0,
                max = MaskMath.MAX_RADIUS.toDouble(),
                step = 1.0,
                enabled = hasSelection,
            )
            Spacer(Modifier.height(8.dp))
            TileRow {
                ActionTile(Icons.Outlined.OpenInFull, "Grow", enabled = hasSelection) {
                    val px = morphPx.roundToInt().coerceIn(1, MaskMath.MAX_RADIUS)
                    closeThen { SelectionEdits.growOrShrink(controller, px) }
                }
                ActionTile(Icons.Outlined.CloseFullscreen, "Shrink", enabled = hasSelection) {
                    val px = morphPx.roundToInt().coerceIn(1, MaskMath.MAX_RADIUS)
                    closeThen { SelectionEdits.growOrShrink(controller, -px) }
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberField(
                label = "Feather radius",
                value = featherPx,
                onValueChange = { featherPx = it },
                decimals = 1,
                suffix = "px",
                min = 0.5,
                max = MaskMath.MAX_RADIUS.toDouble(),
                step = 1.0,
                enabled = hasSelection,
            )
            Spacer(Modifier.height(8.dp))
            TileRow {
                ActionTile(Icons.Outlined.BlurOn, "Feather", enabled = hasSelection) {
                    val px = featherPx.toFloat().coerceIn(0.5f, MaskMath.MAX_RADIUS.toFloat())
                    closeThen { SelectionEdits.feather(controller, px) }
                }
            }
        }

        SectionHeader("Select by content")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionModeButtons(mode) { mode = it }
            Spacer(Modifier.width(8.dp))
            Text(modeDescription(mode), style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        TileRow {
            ActionTile(Icons.Outlined.Layers, "Layer opacity") {
                val m = mode
                closeThen { SelectionEdits.selectLayerOpacity(controller, m) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Smart select analyses the whole picture on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        SmartTarget.entries.chunked(3).forEach { row ->
            TileRow {
                row.forEach { target ->
                    ActionTile(smartIcon(target), smartLabel(target)) {
                        val m = mode
                        closeThen { SelectionEdits.smartSelect(controller, target, m) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        SectionHeader("Selected pixels")
        if (!hasSelection) {
            Text(
                "Make a selection to fill, clear, copy or cut part of the layer.",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        TileRow {
            ActionTile(
                label = "Fill",
                enabled = hasSelection,
                icon = { ColorSwatch(ColorModeOps.displayColor(controller.color, controller.doc.colorMode), size = 22.dp) },
            ) { closeThen { SelectionEdits.fillSelection(controller, controller.color) } }
            ActionTile(Icons.Outlined.Palette, "Fill with…", enabled = hasSelection) { pickFillColor = true }
            ActionTile(Icons.Outlined.LayersClear, "Clear", enabled = hasSelection) {
                closeThen { SelectionEdits.clearSelection(controller) }
            }
        }
        Spacer(Modifier.height(6.dp))
        TileRow {
            ActionTile(Icons.Outlined.ContentCopy, "Copy to new layer", enabled = hasSelection) {
                closeThen { SelectionEdits.copyToNewLayer(controller) }
            }
            ActionTile(Icons.Outlined.ContentCut, "Cut to new layer", enabled = hasSelection) {
                closeThen { SelectionEdits.cutToNewLayer(controller) }
            }
        }
    }

    if (pickFillColor) {
        ColorPickerDialog(
            initial = controller.secondaryColor,
            onPick = { c -> closeThen { SelectionEdits.fillSelection(controller, c or 0xFF000000.toInt()) } },
            onDismiss = { pickFillColor = false },
            title = "Fill selection with",
        )
    }
}

private fun smartIcon(target: SmartTarget): ImageVector = when (target) {
    SmartTarget.SUBJECT -> Icons.Outlined.CenterFocusStrong
    SmartTarget.BACKGROUND -> Icons.Outlined.Wallpaper
    SmartTarget.SKY -> Icons.Outlined.Cloud
    SmartTarget.NATURE -> Icons.Outlined.Park
    SmartTarget.BUILDINGS -> Icons.Outlined.Apartment
    SmartTarget.PEOPLE -> Icons.Outlined.People
    SmartTarget.WATER -> Icons.Outlined.WaterDrop
}

private fun smartLabel(target: SmartTarget): String = SelectionEdits.shortName(target).replaceFirstChar { it.uppercase() }

/** Equal-width tiles in one row. */
@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun RowScope.ActionTile(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) =
    ActionTile(label = label, enabled = enabled, icon = { Icon(icon, contentDescription = null, Modifier.size(22.dp)) }, onClick = onClick)

/** A labelled square-ish button (icon above text) filling its share of a [TileRow]. */
@Composable
private fun RowScope.ActionTile(label: String, enabled: Boolean = true, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = BrushworkColors.ChromeHigh,
        contentColor = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
