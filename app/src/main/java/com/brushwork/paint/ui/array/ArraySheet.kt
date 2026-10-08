package com.brushwork.paint.ui.array

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.vector.Hint

/**
 * The Array sheet (v1.7 item 3, §3.3 a; area E): a hosted half-height sheet (the copies stay
 * visible above it) with the "Line | Circle | Curve | Transform" segments (spoken
 * [ArrayLabels.LINE] … [ArrayLabels.TRANSFORM]), "Count" (stepper and field, 1..200, the source
 * included) and the fields of the mode:
 *
 * - Line: "Relative X" / "Relative Y" (% of the source's size) and "Constant X" / "Constant Y"
 *   (px), added as in Blender; the canvas arrow edits the constant pair.
 * - Circle: "Sweep" and "Rotate copies"; the centre is a canvas handle.
 * - Curve: "Draw guide" / "Use a path" ([ArrayCurvePicker]), "Copy spacing" (px, 0 = spread
 *   evenly), "Align to curve".
 * - Transform: "Move X", "Move Y", "Turn", "Scale per copy"; the pivot and step arrow are canvas
 *   handles.
 *
 * Every field takes expressions (§3.15). A field or slider previews while it moves and records
 * ONE "Edit array" step when done; a segment or toggle is one step at once. A raster array also
 * offers "Edit source pixels" (and "Finish source edit" while its source is being edited). The
 * footer applies or removes the array (one step each).
 */
@Composable
fun ArraySheet(tool: ArrayTool, layer: Layer, spec: ArraySpec, onDismiss: () -> Unit) {
    val raster = ArraySources.kindOf(layer.dataSnapshot()) == ArraySources.Kind.PIXELS
    BwSheet(
        title = TITLE,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(V17Tags.ARRAY_SHEET),
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { tool.removeArray() }, modifier = Modifier.heightIn(min = 44.dp)) { Text(ArrayLabels.REMOVE) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { tool.applyArray() },
                    modifier = Modifier.heightIn(min = 44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent),
                ) { Text(ArrayLabels.APPLY) }
            }
        },
    ) {
        if (spec.editingSource) {
            Hint(EDITING_HINT)
            Button(onClick = { tool.finishSource() }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(ArrayLabels.FINISH_SOURCE) }
            return@BwSheet
        }
        ModeSegments(spec.mode) { m -> if (m != spec.mode) tool.commit(spec.copy(mode = m)) }
        NumberField(
            label = COUNT,
            value = spec.count.toDouble(),
            onValueChange = { v -> if (v.isFinite()) tool.preview(spec.copy(count = Math.round(v).toInt().coerceIn(1, ArraySpec.MAX_COUNT))) },
            decimals = 0,
            min = 1.0,
            max = ArraySpec.MAX_COUNT.toDouble(),
            step = 1.0,
            onValueChangeFinished = { tool.commitPreview() },
        )
        when (spec.mode) {
            ArrayMode.LINE -> LineFields(tool, spec)
            ArrayMode.CIRCLE -> CircleFields(tool, spec)
            ArrayMode.CURVE -> CurveFields(tool, spec)
            ArrayMode.TRANSFORM -> TransformFields(tool, spec)
        }
        if (raster) {
            SectionHeader(SOURCE)
            OutlinedButton(onClick = { tool.editSource() }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(ArrayLabels.EDIT_SOURCE) }
            Hint(EDIT_SOURCE_HINT)
        }
    }
}

/** "Line | Circle | Curve | Transform": one selectable segment per mode, 44 dp tall, spoken as [ArrayLabels]. */
@Composable
private fun ModeSegments(selected: ArrayMode, onSelect: (ArrayMode) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, BrushworkColors.OnChromeDim.copy(alpha = 0.5f), shape)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (m in ArrayMode.entries) {
            val on = m == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (on) BrushworkColors.AccentDim else Color.Transparent, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(m) })
                    .semantics { contentDescription = spokenName(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    modeWord(m),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    color = if (on) Color.White else BrushworkColors.OnChrome,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** What a mode's segment is called (the bare words are other tools' labels, §4.8). */
internal fun spokenName(m: ArrayMode): String = when (m) {
    ArrayMode.LINE -> ArrayLabels.LINE
    ArrayMode.CIRCLE -> ArrayLabels.CIRCLE
    ArrayMode.CURVE -> ArrayLabels.CURVE
    ArrayMode.TRANSFORM -> ArrayLabels.TRANSFORM
}

/** A field that previews while it changes and commits one step when done. */
@Composable
private fun SpecField(
    tool: ArrayTool,
    label: String,
    value: Double,
    suffix: String,
    decimals: Int,
    min: Double = Double.NEGATIVE_INFINITY,
    max: Double = Double.POSITIVE_INFINITY,
    step: Double? = null,
    change: (Double) -> ArraySpec,
) {
    NumberField(
        label = label,
        value = value,
        onValueChange = { v -> if (v.isFinite()) tool.preview(change(v)) },
        decimals = decimals,
        suffix = suffix,
        min = min,
        max = max,
        step = step,
        onValueChangeFinished = { tool.commitPreview() },
    )
}

@Composable
private fun LineFields(tool: ArrayTool, spec: ArraySpec) {
    SectionHeader(OFFSET)
    SpecField(tool, "Relative X", spec.relativeX * 100.0, "%", 0, -1000.0, 1000.0, 10.0) { spec.copy(relativeX = (it / 100.0).toFloat()) }
    SpecField(tool, "Relative Y", spec.relativeY * 100.0, "%", 0, -1000.0, 1000.0, 10.0) { spec.copy(relativeY = (it / 100.0).toFloat()) }
    SpecField(tool, "Constant X", spec.constantX.toDouble(), "px", 1) { spec.copy(constantX = it.toFloat()) }
    SpecField(tool, "Constant Y", spec.constantY.toDouble(), "px", 1) { spec.copy(constantY = it.toFloat()) }
    Hint(LINE_HINT)
}

@Composable
private fun CircleFields(tool: ArrayTool, spec: ArraySpec) {
    SpecField(tool, "Sweep", spec.sweepDeg.toDouble(), "°", 0, -360.0, 360.0, 15.0) { spec.copy(sweepDeg = it.toFloat()) }
    ToggleRow("Rotate copies", spec.rotateCopies, { on -> tool.commit(spec.copy(rotateCopies = on)) })
    Hint(CIRCLE_HINT)
}

@Composable
private fun CurveFields(tool: ArrayTool, spec: ArraySpec) {
    ArrayCurvePicker(tool, hasGuide = spec.guide != null)
    SpecField(tool, ArrayLabels.COPY_SPACING, spec.spacing.toDouble(), "px", 1, 0.0) { spec.copy(spacing = it.toFloat()) }
    Hint(SPACING_HINT)
    ToggleRow("Align to curve", spec.alignToCurve, { on -> tool.commit(spec.copy(alignToCurve = on)) })
}

@Composable
private fun TransformFields(tool: ArrayTool, spec: ArraySpec) {
    SpecField(tool, "Move X", spec.moveX.toDouble(), "px", 1) { spec.copy(moveX = it.toFloat()) }
    SpecField(tool, "Move Y", spec.moveY.toDouble(), "px", 1) { spec.copy(moveY = it.toFloat()) }
    SpecField(tool, "Turn", spec.turnDeg.toDouble(), "°", 1, -360.0, 360.0, 5.0) { spec.copy(turnDeg = it.toFloat()) }
    SpecField(
        tool, ArrayLabels.SCALE_PER_COPY, spec.scale.toDouble(), "×", 2,
        ArraySpec.MIN_SCALE.toDouble(), ArraySpec.MAX_SCALE.toDouble(), 0.05,
    ) { spec.copy(scale = it.toFloat()) }
    Hint(TRANSFORM_HINT)
}

private const val TITLE = "Array"
private const val COUNT = "Count"
private const val OFFSET = "Offset per copy"
private const val SOURCE = "Source"
private const val LINE_HINT = "Drag the arrow on the canvas to set the constant offset."
private const val CIRCLE_HINT = "Drag the centre on the canvas. 360° spreads the copies round the circle."
private const val SPACING_HINT = "0 spreads the copies evenly along the guide."
private const val TRANSFORM_HINT = "Each copy moves, turns and scales from the one before, about the pivot."
private const val EDIT_SOURCE_HINT = "Paint the source with any tool; the copies come back when you finish."
private const val EDITING_HINT = "The source is being edited: the copies come back when you finish."
