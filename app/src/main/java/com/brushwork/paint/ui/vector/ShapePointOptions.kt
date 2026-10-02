package com.brushwork.paint.ui.vector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.vector.ShapeHandleSide
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderScale
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisDims

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
        ShapeHandlesGroup(tool, sel)
    }
    if (info.custom) ActionChip("Reset shape", Icons.Filled.RestartAlt) { tool.resetShape() }
}

/**
 * The Handles group (v1.6 §3.3, the shape tool's points): ⟷, ‹ "Shorter handles", the value
 * ("100 %"; tap: "Type handle scale", long-press: the Scale step), › "Longer handles", a 120 dp
 * "Handle scale" slider (10–400 %, logarithmic), the side ("In and out" / "In" / "Out") and "All
 * points". It acts on the [selected] point's handles, or every point's without a selection or with
 * "All points"; the value is relative to the handles when a change began and reads 100 % again
 * at rest. ‹ › multiply by 0.9 / 1.1, or step by the Scale increment, and repeat while held;
 * the slider lands on the Scale step's multiples while increments are on; a typed value is exact.
 * Each slider drag, held arrow or typed value is one in-tool undo step.
 */
@Composable
private fun ShapeHandlesGroup(tool: ShapeTool, selected: Int) {
    var typing by remember { mutableStateOf(false) }
    val percent = tool.handleScale * 100f
    Icon(
        Icons.Filled.SwapHoriz,
        contentDescription = null,
        tint = BrushworkColors.OnChromeDim,
        modifier = Modifier.padding(start = 8.dp, end = 2.dp).size(20.dp),
    )
    RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Shorter handles", onRelease = { tool.endHandleScale() }) { tool.stepHandles(longer = false) }
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .stepOnLongPress(IncrementKind.SCALE)
            .clickable(onClickLabel = "Type handle scale", role = Role.Button) { typing = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${Units.formatNumber(percent.toDouble(), 0)} %",
            style = MaterialTheme.typography.labelLarge,
            color = BrushworkColors.OnChrome,
            maxLines = 1,
        )
    }
    RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Longer handles", onRelease = { tool.endHandleScale() }) { tool.stepHandles(longer = true) }
    HandleScaleSlider(tool)
    for (side in ShapeHandleSide.entries) {
        OptionChip(sideLabel(side), tool.handleSide == side, { tool.handleSide = side })
    }
    // Without a selected point the group acts on all of them anyway.
    OptionChip("All points", tool.handlesAllPoints || selected < 0, { tool.handlesAllPoints = !tool.handlesAllPoints }, enabled = selected >= 0)
    if (typing) {
        ValueInputDialog(
            title = "Handle scale",
            label = "Scale",
            initial = 100f,
            format = { Units.formatNumber(it.toDouble(), 1) },
            parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(TYPED_MIN, TYPED_MAX) },
            step = { v, up -> if (up) v * 1.1f else v * 0.9f },
            toFraction = { v -> HandleScaleRange.fraction(v / 100.0) },
            fromFraction = { f -> (HandleScaleRange.value(f) * 100.0).toFloat() },
            rangeText = "10 – 400 % of the handles now",
            suffix = "%",
            onApply = { v -> tool.scaleHandles(v / 100f) },
            onDismiss = { typing = false },
            incrementKind = IncrementKind.SCALE,
        )
    }
}

/** The mini slider of the Handles group: 10–400 % of the handles when the drag began, logarithmic. */
@Composable
private fun HandleScaleSlider(tool: ShapeTool) {
    val scaleStep = LocalIncrements.current?.step(IncrementKind.SCALE)
    val k = tool.handleScale
    Slider(
        value = HandleScaleRange.fraction(k.toDouble()),
        onValueChange = { f ->
            if (tool.beginHandleScale()) {
                val raw = HandleScaleRange.value(f).toFloat()
                tool.scaleHandlesTo(if (scaleStep != null) IncrementMath.snapFactor(raw, scaleStep) else raw)
            }
        },
        onValueChangeFinished = { tool.endHandleScale() },
        colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
        modifier = Modifier
            .width(IbisDims.HandleScaleSlider)
            .semantics {
                contentDescription = "Handle scale"
                stateDescription = "${Units.formatNumber((k * 100f).toDouble(), 0)} %"
            },
    )
}

/** The Handles group's slider range: 10 % to 400 %, logarithmic (100 % a little past the middle). */
private val HandleScaleRange = SliderScale.log(0.1, 4.0)

/** Typed handle scales (percent) are kept within the factors the geometry allows. */
private const val TYPED_MIN = ShapePoints.MIN_HANDLE_SCALE * 100f
private const val TYPED_MAX = ShapePoints.MAX_HANDLE_SCALE * 100f

private fun sideLabel(side: ShapeHandleSide): String = when (side) {
    ShapeHandleSide.BOTH -> "In and out"
    ShapeHandleSide.IN -> "In"
    ShapeHandleSide.OUT -> "Out"
}

/**
 * "Editable": new shapes go into a layer of their own that can be edited again (hidden while a
 * shape layer is being edited, which already is one, and on a vector layer, where every shape
 * stays editable as an object of the layer).
 */
@Composable
internal fun ShapeEditableChip(tool: ShapeTool) {
    if (tool.editingLayer != null || tool.drawsOnVectorLayer) return
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
    val vector = tool.drawsOnVectorLayer
    if (tool.editingLayer == null && !vector) {
        ToggleRow(
            "Editable (own layer)", s.editable, { v -> tool.update { it.copy(editable = v) } },
            description = if (s.editable) "Each new shape goes into a layer of its own and can be edited again later"
            else "New shapes are painted into the active layer",
        )
    }
    if (vector) {
        Hint(
            "On a vector layer every shape is an object of the layer: tap it with the shape tool to edit it again " +
                "(✓ keeps the change, ✕ leaves it as it was). A drag always draws a new shape.",
            Modifier.padding(top = 4.dp),
        )
    }
    Hint(
        (if (vector) "" else "Tap a placed shape with the shape tool to edit it again (it is also in the layers window: Edit shape). " +
            "A drag always draws a new shape, also when it starts on a placed one; a tap outside the open shape places it. ") +
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
