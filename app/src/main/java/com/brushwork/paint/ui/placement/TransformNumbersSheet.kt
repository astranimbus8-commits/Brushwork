package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NudgePad
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * "Numbers" sheet of the transform tool: a 3 x 3 reference point (what stays in place when a
 * size, scale or rotation is typed or slid, e.g. the center to scale from the center) and its
 * exact position, size, rotation and scale, a nudge pad that moves by a chosen step, snapping
 * options and Delete. Values update live while the sheet is open.
 */
@Composable
fun TransformNumbersSheet(tool: TransformTool) {
    val st = tool.transformState ?: return
    val doc = tool.controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = tool.unit
    val anchor = tool.anchor
    val at = st.anchorPoint(anchor)
    // Slider ranges (document px): positions from one canvas size before the canvas to two
    // after it, sizes up to twice its longer side. Any finite number can still be typed.
    val w = doc.width.toDouble()
    val h = doc.height.toDouble()
    val maxSize = 2.0 * maxOf(w, h, 1.0)
    val finished = { tool.endNumericEdit() }
    BwSheet(
        title = "Numbers",
        onDismiss = { tool.numbersOpen = false },
        actions = { UnitSelector(unit, onUnitChange = { tool.unit = it }) },
    ) {
        SectionHeader("Reference point & position")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TransformAnchorPicker(anchor, onSelect = { tool.anchor = it })
                Text(
                    anchor.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LengthField(
                    "X", at.x.toDouble(), { tool.setAnchorPosition(x = it) }, unit, dpi, step = null,
                    sliderMinPx = -w, sliderMaxPx = 2.0 * w,
                )
                LengthField(
                    "Y", at.y.toDouble(), { tool.setAnchorPosition(y = it) }, unit, dpi, step = null,
                    sliderMinPx = -h, sliderMaxPx = 2.0 * h,
                )
            }
        }
        Text(
            "The ${anchor.label.lowercase()} point stays in place when you change the size, scale or rotation; X and Y are its position.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionHeader("Size")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LengthField(
                "Width", st.width.toDouble(), { tool.setSize(width = it) }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0,
                sliderMinPx = 1.0, sliderMaxPx = maxSize, onValueChangeFinished = finished,
            )
            LengthField(
                "Height", st.height.toDouble(), { tool.setSize(height = it) }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0,
                sliderMinPx = 1.0, sliderMaxPx = maxSize, onValueChangeFinished = finished,
            )
        }
        // v1.7 (§3.11): a text kept as text always keeps its aspect ratio.
        if (!tool.uniformOnly) ToggleRow("Keep aspect ratio", tool.keepAspect, { tool.keepAspect = it })
        Text(
            "Original: ${Units.format(st.srcW.toDouble(), unit, dpi)} × ${Units.format(st.srcH.toDouble(), unit, dpi)}",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
        )

        SectionHeader("Rotation & scale")
        NumberField(
            label = "Rotation",
            value = st.rotationDeg.toDouble(),
            onValueChange = { tool.setRotation(it) },
            decimals = 1,
            suffix = "°",
            min = -360.0,
            max = 360.0,
            step = 1.0,
            sliderMin = -180.0,
            sliderMax = 180.0,
            onValueChangeFinished = finished,
        )
        NumberField(
            label = "Scale",
            value = st.scalePercent.toDouble(),
            onValueChange = { tool.setScalePercent(it) },
            decimals = 1,
            suffix = "%",
            min = 0.1,
            max = 10000.0,
            step = 1.0,
            // 1 % .. 10000 % on a logarithmic slider: 100 % sits in the middle.
            sliderMin = 1.0,
            sliderMax = 10000.0,
            logSlider = true,
            modifier = Modifier.padding(top = 4.dp),
            onValueChangeFinished = finished,
            // v1.6: a Transform scale steps by the Scale step (100, 110, 120 % of the original), not the Percent one.
            incrementKind = IncrementKind.SCALE,
        )

        SectionHeader("Nudge")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NudgePad(onNudge = { dx, dy -> tool.nudge(dx, dy) })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                LengthField(
                    "Step",
                    tool.nudgeStepPx,
                    { tool.nudgeStepPx = it },
                    unit,
                    dpi,
                    step = null,
                    minPx = 0.01,
                    maxPx = maxOf(doc.width, doc.height).toDouble(),
                )
                Text(
                    "Each arrow moves by ${Units.format(tool.nudgeStepPx, unit, dpi)}" +
                        if (tool.snapToObjects) ", stopping where it lines up with something on the way" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionHeader("Handles & guides")
        ToggleRow(
            "Snap to objects",
            tool.snapToObjects,
            { tool.snapToObjects = it },
            description = "While dragging, edges and centers line up with the canvas, other layers and (with grid snapping) the grid",
        )
        ToggleRow(
            "Resize from the center",
            tool.scaleFromCenter,
            { tool.scaleFromCenter = it },
            description = "Corner and side handles scale around the center",
        )

        TransformDeleteButton(tool, Modifier.padding(top = 8.dp))
    }
}
