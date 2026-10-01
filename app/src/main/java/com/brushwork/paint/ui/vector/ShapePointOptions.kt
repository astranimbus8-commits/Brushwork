package com.brushwork.paint.ui.vector

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors

/*
 * The "Points" parts of the shape tool's options: the strip's point editing toggle and the
 * actions for the selected point, and the selected point's fields in the Numbers sheet.
 */

/** What the strip shows about the pending shape's points (only these changes recompose it). */
private data class PointsInfo(
    val pending: Boolean,
    val pointsMode: Boolean,
    val custom: Boolean,
    val count: Int,
    val selected: Int,
    val smooth: Boolean,
    val explicitHandles: Boolean,
)

@Composable
private fun rememberPointsInfo(tool: ShapeTool): PointsInfo {
    val info by remember(tool) {
        derivedStateOf {
            val pts = tool.points
            val sel = tool.selectedPoint
            val p = pts?.getOrNull(sel)
            PointsInfo(
                pending = tool.box != null,
                pointsMode = tool.pointsMode,
                custom = pts != null,
                count = pts?.size ?: 0,
                selected = if (p != null) sel else -1,
                smooth = p?.smooth == true,
                explicitHandles = p != null && (p.handleIn != null || p.handleOut != null),
            )
        }
    }
    return info
}

/**
 * Strip chips for the pending shape's points: "Points" (edit them), the in-tool undo / redo of
 * point edits, the selected point's actions (sharp / smooth, automatic tangent, delete) and
 * "Reset shape" (back to the regular outline). Nothing without a pending shape.
 */
@Composable
internal fun ShapePointsStrip(tool: ShapeTool) {
    val info = rememberPointsInfo(tool)
    if (!info.pending) return
    OptionChip("Points", info.pointsMode, { tool.setPointEditing(!info.pointsMode) }, icon = Icons.Filled.Timeline)
    if (info.pointsMode) {
        // The same steps as the app's undo / redo (one point edit at a time).
        ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo point edit", onClick = { tool.undoStep() }, enabled = tool.canUndoStep, size = 44.dp)
        ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo point edit", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 44.dp)
        val sel = info.selected
        if (sel >= 0) {
            if (info.smooth) ActionChip("Sharp corner", Icons.Filled.ChangeHistory) { tool.setPointSmooth(sel, false) }
            else ActionChip("Smooth", Icons.Filled.Gesture) { tool.setPointSmooth(sel, true) }
            if (info.smooth && info.explicitHandles) ActionChip("Auto tangent", Icons.Filled.Restore) { tool.resetTangent(sel) }
            ActionChip("Delete point", Icons.Outlined.Delete, tint = BrushworkColors.Danger, enabled = info.count > tool.minPoints) { tool.deletePoint(sel) }
            ToolIconButton(Icons.Filled.Deselect, "Deselect point", onClick = { tool.selectPoint(-1) }, size = 44.dp)
        }
    }
    if (info.custom) ActionChip("Reset shape", Icons.Filled.RestartAlt) { tool.resetShape() }
}

/**
 * "Editable": new shapes go into a layer of their own that can be edited again (hidden while a
 * shape layer is being edited, which already is one).
 */
@Composable
internal fun ShapeEditableChip(tool: ShapeTool) {
    if (tool.editingLayer != null) return
    val on = tool.settings.editable
    OptionChip("Editable", on, { tool.update { it.copy(editable = !on) } }, icon = Icons.Filled.Layers)
}

/**
 * Settings sheet: the "Editable (own layer)" switch, how to edit placed shapes and points, and
 * for an opened brush-stroked shape the brush it is painted with.
 */
@Composable
internal fun ShapeEditingSettings(tool: ShapeTool) {
    val s = tool.settings
    if (tool.editingLayer == null) {
        ToggleRow(
            "Editable (own layer)", s.editable, { v -> tool.update { it.copy(editable = v) } },
            description = if (s.editable) "Each new shape goes into a layer of its own and can be edited again later"
            else "New shapes are painted into the active layer",
        )
    }
    Hint(
        "Tap a placed shape with the shape tool to edit it again (it is also in the layers window: Edit shape). " +
            "A drag always draws a new shape, also when it starts on a placed one; a tap outside the open shape places it. " +
            "Points: drag a point to move it, tap the outline or a + to add one, tap a point for sharp / smooth / delete and its tangent handles; " +
            "drag inside the shape to move it.",
        Modifier.padding(top = 4.dp),
    )
}

/** Settings sheet, stroke section: an opened brush-stroked shape keeps the brush it was drawn with. */
@Composable
internal fun ShapeOwnBrushNote(tool: ShapeTool) {
    val own = tool.editedShapeBrush ?: return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Hint("Painted with its own brush \"${own.name}\" (${Units.formatNumber(own.size.toDouble(), 1)} px)", Modifier.weight(1f))
        TextButton(onClick = { tool.useCurrentBrush() }) { Text("Use current brush") }
    }
}

/**
 * Numbers sheet: X / Y of the selected point (with previous / next), or a way into point editing
 * for a shape with its own points.
 */
@Composable
internal fun ShapePointFields(tool: ShapeTool, unit: LengthUnit, dpi: Double) {
    val info = rememberPointsInfo(tool)
    if (!info.pending || !info.custom) return
    SectionHeader("Points")
    val sel = info.selected
    val anchors = tool.docAnchors() ?: return
    val a = anchors.getOrNull(sel)
    if (!info.pointsMode || a == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${info.count} points", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { tool.setPointEditing(true); tool.selectPoint(0) }) { Text("Edit points") }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Point ${sel + 1} of ${info.count}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous point", onClick = { tool.selectPoint((sel - 1 + info.count) % info.count) })
        ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next point", onClick = { tool.selectPoint((sel + 1) % info.count) })
    }
    val p = a.pos
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LengthField("X", p.x.toDouble(), { x -> tool.movePoint(sel, Vec2(x.toFloat(), p.y)) }, unit, dpi, Modifier.weight(1f), step = null, minPx = -MAX, maxPx = MAX)
        Spacer(Modifier.width(8.dp))
        LengthField("Y", p.y.toDouble(), { y -> tool.movePoint(sel, Vec2(p.x, y.toFloat())) }, unit, dpi, Modifier.weight(1f), step = null, minPx = -MAX, maxPx = MAX)
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ChoiceChips(listOf("Sharp corner", "Smooth"), if (info.smooth) 1 else 0, { i -> tool.setPointSmooth(sel, i == 1) }, Modifier.weight(1f))
        TextButton(onClick = { tool.deletePoint(sel) }, enabled = info.count > tool.minPoints) {
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Delete", color = BrushworkColors.Danger)
        }
    }
    if (info.smooth && info.explicitHandles) {
        TextButton(onClick = { tool.resetTangent(sel) }) { Text("Back to the automatic tangent") }
    }
}

private const val MAX = 100_000.0
