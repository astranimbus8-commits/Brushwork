package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.transform.MeshDeform
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Options strip of the transform tool: mode, Delete, aspect lock, scaling from the center,
 * snapping to objects (smart guides), flips, quarter turns, fit, reset, interpolation and the
 * "Numbers" sheet. Emits items straight into the (scrolling) tool bar row.
 */
@Composable
fun TransformToolOptions(tool: TransformTool) {
    // transformState changes on every drag frame; the strip only cares whether one exists.
    val active by remember(tool) { derivedStateOf { tool.transformState != null } }
    if (!active) {
        Text(
            tool.statusText,
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    TransformTool.Mode.entries.forEach { m -> ModeChip(tool, m) }
    BarDivider()
    TransformDeleteButton(tool)
    BarDivider()
    // v1.7 (§3.16): Free deform has its own controls; the box's are not shown.
    val mesh by remember(tool) { derivedStateOf { tool.isMeshShown } }
    if (mesh) {
        MeshOptions(tool)
        BarDivider()
        InterpolationMenu(tool)
        return
    }
    ToolIconButton(
        if (tool.keepAspect) Icons.Filled.Link else Icons.Filled.LinkOff,
        contentDescription = if (tool.keepAspect) "Keep aspect ratio: on" else "Keep aspect ratio: off",
        onClick = { tool.keepAspect = !tool.keepAspect },
        selected = tool.keepAspect,
        enabled = tool.mode == TransformTool.Mode.FREE && !tool.uniformOnly,
    )
    FilterChip(
        selected = tool.scaleFromCenter,
        onClick = { tool.scaleFromCenter = !tool.scaleFromCenter },
        label = { Text("From center") },
        leadingIcon = { Icon(Icons.Filled.FilterCenterFocus, contentDescription = null, modifier = Modifier.size(18.dp)) },
        enabled = tool.mode == TransformTool.Mode.FREE,
        colors = chipColors(),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    FilterChip(
        selected = tool.snapToObjects,
        onClick = { tool.snapToObjects = !tool.snapToObjects },
        label = { Text("Snap to objects") },
        leadingIcon = { Icon(Icons.Filled.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = chipColors(),
        modifier = Modifier
            .padding(end = 4.dp)
            .semantics { stateDescription = if (tool.snapToObjects) "Snap to objects: on" else "Snap to objects: off" },
    )
    BarDivider()
    // v1.7 (§3.11): a text kept as text is never mirrored.
    if (tool.flipsAllowed) {
        ToolIconButton(Icons.Filled.Flip, "Flip horizontally", onClick = { tool.flip(horizontal = true) })
        ToolIconButton(Icons.Filled.Flip, "Flip vertically", onClick = { tool.flip(horizontal = false) }, modifier = Modifier.rotate(90f))
    }
    ToolIconButton(Icons.Filled.Rotate90DegreesCcw, "Rotate 90° counter-clockwise", onClick = { tool.rotate90(clockwise = false) })
    ToolIconButton(Icons.Filled.Rotate90DegreesCw, "Rotate 90° clockwise", onClick = { tool.rotate90(clockwise = true) })
    ToolIconButton(Icons.Filled.FitScreen, "Fit to canvas", onClick = { tool.fitToCanvas() })
    ToolIconButton(Icons.Filled.RestartAlt, "Reset transform", onClick = { tool.reset() })
    BarDivider()
    InterpolationMenu(tool)
    TextButton(onClick = { tool.numbersOpen = true }) { Text("Numbers") }

    if (tool.numbersOpen) TransformNumbersSheet(tool)
}

/**
 * One mode chip (v1.7, design §3.11 and §3.16). A mode that what is lifted can't take is shown
 * dimmed with its caption as the chip's state ("Rasterize to deform", "Apply the array to
 * deform"...); a tap shows the caption, plus "Rasterize and deform" when rasterizing the layer
 * first makes the mode available.
 */
@Composable
private fun ModeChip(tool: TransformTool, m: TransformTool.Mode) {
    val refusal = tool.modeRefusal(m)
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = tool.mode == m,
            onClick = { if (refusal == null) tool.mode = m else open = true },
            label = { Text(m.label, color = if (refusal != null) BrushworkColors.OnChromeDim else Color.Unspecified) },
            colors = chipColors(),
            modifier = Modifier
                .padding(end = 6.dp)
                .semantics { if (refusal != null) stateDescription = refusal },
        )
        DropdownMenu(expanded = open && refusal != null, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(refusal.orEmpty(), style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim) },
                onClick = {},
                enabled = false,
            )
            if (tool.canRasterizeFor(m)) {
                DropdownMenuItem(
                    text = { Text(TransformLabels17.RASTERIZE_AND_DEFORM) },
                    onClick = { open = false; tool.rasterizeAndDeform(m) },
                )
            }
        }
    }
}

/** v1.7 (§3.16): the labels of the Free deform steppers' buttons (the values are "Mesh columns" / "Mesh rows"). */
object MeshStepperLabels {
    const val FEWER_COLUMNS = "Fewer mesh columns"; const val MORE_COLUMNS = "More mesh columns"
    const val FEWER_ROWS = "Fewer mesh rows"; const val MORE_ROWS = "More mesh rows"
}

/**
 * The Free deform controls (v1.7, design §3.1 and §3.16), tagged [V17Tags.MESH]: "Select several"
 * (with its hint while on), "Select all points" / "Deselect all points", the "Mesh columns" and
 * "Mesh rows" steppers (1..12), "Smooth mesh" and "Reset mesh". Each value is read through
 * derivedStateOf: the mesh changes on every drag frame, the strip only when a shown value does.
 */
@Composable
private fun MeshOptions(tool: TransformTool) {
    val cols by remember(tool) { derivedStateOf { tool.meshColumns } }
    val rows by remember(tool) { derivedStateOf { tool.meshRows } }
    val all by remember(tool) { derivedStateOf { tool.allPointsSelected } }
    val changed by remember(tool) { derivedStateOf { tool.isMeshChanged } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(V17Tags.MESH)) {
        FilterChip(
            selected = tool.selectSeveral,
            onClick = { tool.selectSeveral = !tool.selectSeveral },
            label = { Text(PointLabels.SELECT_SEVERAL) },
            colors = chipColors(),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { stateDescription = if (tool.selectSeveral) "On" else "Off" },
        )
        TextButton(onClick = { tool.selectAllPoints() }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (all) PointLabels.DESELECT_ALL else PointLabels.SELECT_ALL)
        }
        if (tool.selectSeveral) {
            Text(
                PointLabels.SEVERAL_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                maxLines = 2,
                modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 4.dp),
            )
        }
        BarDivider()
        MeshStepper(TransformLabels17.COLUMNS, cols, MeshStepperLabels.FEWER_COLUMNS, MeshStepperLabels.MORE_COLUMNS) { tool.setMeshCells(it, tool.meshRows) }
        MeshStepper(TransformLabels17.ROWS, rows, MeshStepperLabels.FEWER_ROWS, MeshStepperLabels.MORE_ROWS) { tool.setMeshCells(tool.meshColumns, it) }
        FilterChip(
            selected = tool.smoothMesh,
            onClick = { tool.smoothMesh = !tool.smoothMesh },
            label = { Text(TransformLabels17.SMOOTH) },
            colors = chipColors(),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ToolIconButton(Icons.Filled.RestartAlt, TransformLabels17.RESET, onClick = { tool.resetMesh() }, enabled = changed)
    }
}

/** "[label] [value]" between − and + buttons ([fewer], [more]), held to 1..12. */
@Composable
private fun MeshStepper(label: String, value: Int, fewer: String, more: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        ToolIconButton(Icons.Filled.Remove, fewer, onClick = { onChange(value - 1) }, enabled = value > 1, size = 40.dp)
        Text(
            "$label $value",
            style = MaterialTheme.typography.bodyMedium,
            color = BrushworkColors.OnChrome,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = label; stateDescription = "$value" },
        )
        ToolIconButton(Icons.Filled.Add, more, onClick = { onChange(value + 1) }, enabled = value < MeshDeform.MAX_CELLS, size = 40.dp)
    }
}

@Composable
private fun BarDivider() {
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = BrushworkColors.ChromeBorder)
}

@Composable
private fun chipColors() =
    FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White)

/**
 * Deletes what is being transformed (one undo step): the lifted pixels, or the picture being
 * placed together with its layer. Shared by the options strip and the Numbers sheet.
 */
@Composable
fun TransformDeleteButton(tool: TransformTool, modifier: Modifier = Modifier) {
    TextButton(
        onClick = { tool.deleteContent() },
        colors = ButtonDefaults.textButtonColors(contentColor = BrushworkColors.Danger),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (tool.isPlacement) "Delete picture" else "Delete")
    }
}

@Composable
private fun InterpolationMenu(tool: TransformTool) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(tool.interpolation.label)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose interpolation")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TransformTool.Interpolation.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(mode.label)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                        }
                    },
                    leadingIcon = {
                        if (mode == tool.interpolation) Icon(Icons.Filled.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = { tool.interpolation = mode; open = false },
                )
            }
        }
    }
    Spacer(Modifier.width(2.dp))
}
