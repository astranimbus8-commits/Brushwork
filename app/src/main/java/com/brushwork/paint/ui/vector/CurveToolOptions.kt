package com.brushwork.paint.ui.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.ArrowHeadStyle
import com.brushwork.paint.tools.vector.ArrowHeads
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.SnapToObjectsChip
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NudgePad
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Options strip and sheets of the curve and polyline tools (split out of VectorOptions.kt in v1.5; owned by A4).
 */

// ====================================================================== curve / polyline tools

/**
 * Options strip of the curve and polyline tools: undo last point, closed path, actions for the
 * selected anchor (sharp / smooth / automatic tangent / delete), stroke mode, fill, and the
 * "Numbers" / settings sheets.
 */
@Composable
fun CurveToolOptions(tool: CurveTool) {
    val s = tool.settings
    // Only what the row shows about the selected point: dragging anchors doesn't recompose it.
    val selInfo by remember(tool) {
        derivedStateOf {
            val i = tool.selected
            tool.anchors.getOrNull(i)?.let { SelectedAnchor(i, it.sharp, it.hasCustomTangent) }
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)

    // The same steps as the app's undo / redo (which take back one point edit at a time too).
    ToolIconButton(Icons.AutoMirrored.Filled.Undo, "Undo last point", onClick = { tool.undoStep() }, enabled = tool.canUndoStep, size = 44.dp)
    ToolIconButton(Icons.AutoMirrored.Filled.Redo, "Redo point", onClick = { tool.redoStep() }, enabled = tool.redoCount > 0, size = 44.dp)
    val a = selInfo
    if (a != null) {
        val sel = a.index
        if (!tool.polyline) {
            if (a.sharp) ActionChip("Smooth", Icons.Filled.Gesture) { tool.setSharp(sel, false) }
            else ActionChip("Sharp corner", Icons.Filled.ChangeHistory) { tool.setSharp(sel, true) }
            if (a.customTangent) ActionChip("Auto tangent", Icons.Filled.Restore) { tool.resetTangent(sel) }
        }
        ActionChip("Delete point", Icons.Outlined.Delete, tint = BrushworkColors.Danger) { tool.deleteAnchor(sel) }
        ToolIconButton(Icons.Filled.Deselect, "Deselect point", onClick = { tool.deselect() }, size = 44.dp)
    }
    OptionChip("Closed", s.closed, { set { it.copy(closed = !it.closed) } }, icon = Icons.Filled.Loop)
    DropdownChip(
        label = s.stroke.label,
        options = CurveStroke.entries,
        selected = s.stroke,
        optionLabel = { it.label },
        onSelect = { m -> set { it.copy(stroke = m) } },
        leading = { Icon(Icons.Filled.LineWeight, contentDescription = null, modifier = Modifier.size(18.dp)) },
        contentDescription = "Stroke",
    )
    OptionChip("Fill", s.fill, { set { it.copy(fill = !it.fill) } }, icon = Icons.Filled.FormatColorFill)
    SnapToObjectsChip(tool.controller)
    ActionChip("Numbers", Icons.Filled.Pin) { showNumbers = true }
    ActionChip("Settings", Icons.Filled.Tune) { showSettings = true }

    if (showSettings) CurveSettingsSheet(tool) { showSettings = false }
    if (showNumbers) CurveNumbersSheet(tool) { showNumbers = false }
}

/** What the curve options row shows about the selected anchor. */
private data class SelectedAnchor(val index: Int, val sharp: Boolean, val customTangent: Boolean)

@Composable
private fun CurveSettingsSheet(tool: CurveTool, onDismiss: () -> Unit) {
    val s = tool.settings
    val controller = tool.controller
    val dpi = controller.doc.dpi
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)

    BwSheet(title = if (tool.polyline) "Polyline" else "Curve", onDismiss = onDismiss) {
        SectionHeader("Stroke")
        ChoiceChips(CurveStroke.entries.map { it.label }, s.stroke.ordinal, { i -> set { it.copy(stroke = CurveStroke.entries[i]) } })
        when (s.stroke) {
            CurveStroke.BRUSH -> {
                val paintTool = controller.lastPaintTool
                val preset = controller.presetFor(paintTool)
                Hint(
                    (if (preset != null) "Painted with ${paintTool.label.lowercase()} \"${preset.name}\" at full pressure" else "Painted with the ${paintTool.label.lowercase()}") +
                        ". The stroke shows while you edit the points.",
                )
                ToggleRow("Taper ends", s.taper, { v -> set { it.copy(taper = v) } }, description = "Pressure fades in and out along the path")
                if (s.taper) {
                    LabeledSlider(
                        label = "Taper length",
                        value = s.taperPercent,
                        onValueChange = { v -> set { it.copy(taperPercent = v) } },
                        valueRange = 1f..50f,
                        valueText = "${s.taperPercent.roundToInt()} % of the path",
                    )
                }
            }
            CurveStroke.PLAIN -> {
                MainColorNote(controller.color, "The line uses the main drawing color")
                LengthEditor(
                    label = "Line width", px = s.plainWidth, onPx = { w -> set { it.copy(plainWidth = w) } },
                    unit = s.unit, onUnit = { u -> set { it.copy(unit = u) } }, dpi = dpi,
                    minPx = ShapeSettings.MIN_STROKE, maxPx = ShapeSettings.MAX_STROKE,
                )
            }
            CurveStroke.NONE -> Hint("Only the fill is painted")
        }

        SectionHeader("Fill")
        ToggleRow(
            "Fill the path", s.fill, { v -> set { it.copy(fill = v) } },
            description = if (s.closed) "Fills the inside of the closed path" else "An open path is filled as if it were closed",
        )
        if (s.fill) FillColorRow(s.fillColor, controller.color) { col -> set { it.copy(fillColor = col) } }

        SectionHeader("Path")
        ToggleRow("Closed path", s.closed, { v -> set { it.copy(closed = v) } }, description = "Joins the last point back to the first")
        if (!tool.polyline) {
            LabeledSlider(
                label = "Tension",
                value = s.tension,
                onValueChange = { v -> set { it.copy(tension = v) } },
                valueRange = 0f..1f,
                valueText = "${(s.tension * 100f).roundToInt()} %",
            )
            Hint("0 % is a smooth curve through every point, 100 % straight lines")
        }
        Hint(
            (if (tool.polyline) "Tap to add points, drag any point to move it, long-press a point to select it."
            else "Tap to add points (on the path to insert one), drag any point to move it, long-press a point for corner / smooth / delete. A selected smooth point shows tangent handles you can drag.") +
                " Undo takes back the last point edit.",
            Modifier.padding(top = 8.dp),
        )
    }
}

/** Numeric editing of the curve / polyline anchors (hosted by [CurveToolOptions]). */
@Composable
private fun CurveNumbersSheet(tool: CurveTool, onDismiss: () -> Unit) {
    val s = tool.settings
    val controller = tool.controller
    val anchors = tool.anchors
    val sel = tool.selected
    val dpi = controller.doc.dpi.toDouble()
    val unit = s.unit
    fun set(f: (CurveSettings) -> CurveSettings) = tool.update(f)
    // Where "Add point" puts the next anchor: starts at the last point (or the canvas center).
    val start = anchors.lastOrNull()?.pos ?: Vec2(controller.doc.width / 2f, controller.doc.height / 2f)
    var addX by rememberSaveable { mutableFloatStateOf(start.x) }
    var addY by rememberSaveable { mutableFloatStateOf(start.y) }

    BwSheet(
        title = "Numbers",
        onDismiss = onDismiss,
        actions = { UnitSelector(unit, { u -> set { it.copy(unit = u) } }) },
    ) {
        val a = anchors.getOrNull(sel)
        if (a != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Point ${sel + 1} of ${anchors.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous point", onClick = { tool.select((sel - 1 + anchors.size) % anchors.size) })
                ToolIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next point", onClick = { tool.select((sel + 1) % anchors.size) })
            }
            FieldPair(
                "X", a.x, { x -> tool.moveAnchor(sel, Vec2(x, a.y)) },
                "Y", a.y, { y -> tool.moveAnchor(sel, Vec2(a.x, y)) },
                unit, dpi,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!tool.polyline) {
                    ChoiceChips(listOf("Smooth", "Sharp corner"), if (a.sharp) 1 else 0, { i -> tool.setSharp(sel, i == 1) }, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = { tool.deleteAnchor(sel) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = BrushworkColors.Danger)
                }
            }
            if (!tool.polyline && a.hasCustomTangent) {
                TextButton(onClick = { tool.resetTangent(sel) }) { Text("Back to the automatic tangent") }
            }
        } else if (anchors.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${anchors.size} point${if (anchors.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { tool.select(0) }) { Text("Edit points") }
            }
            Hint("No point is selected: the arrows move the whole path")
        } else {
            Hint("No points yet. Tap the canvas or add points by coordinates below.", Modifier.padding(top = 8.dp))
        }

        SectionHeader("Add point")
        PanelCard {
            FieldPair(
                "X", addX, { if (it.isFinite()) addX = it },
                "Y", addY, { if (it.isFinite()) addY = it },
                unit, dpi,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { tool.addAnchor(Vec2(addX, addY)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add point")
                }
            }
        }

        if (anchors.isNotEmpty()) {
            SectionHeader("Nudge")
            NudgeRow(
                stepPx = s.nudgeStepPx,
                onStep = { v -> set { it.copy(nudgeStepPx = v) } },
                unit = unit, dpi = dpi,
                hint = if (a != null) "Each arrow moves point ${sel + 1} by one step" else "Each arrow moves the whole path by one step",
                onNudge = { dx, dy -> tool.nudge(dx, dy) },
            )
        }
    }
}
