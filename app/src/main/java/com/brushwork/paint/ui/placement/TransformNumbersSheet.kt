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
 * "Numbers" sheet of the transform tool: exact position, size, rotation and scale, plus a
 * nudge pad that moves by a chosen step. Values update live while the sheet is open.
 */
@Composable
fun TransformNumbersSheet(tool: TransformTool) {
    val st = tool.transformState ?: return
    val doc = tool.controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = tool.unit
    val bounds = st.bounds()
    BwSheet(
        title = "Numbers",
        onDismiss = { tool.numbersOpen = false },
        actions = { UnitSelector(unit, onUnitChange = { tool.unit = it }) },
    ) {
        SectionHeader("Position (top-left)")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LengthField("X", bounds.left.toDouble(), { tool.setPosition(left = it) }, unit, dpi, Modifier.weight(1f), step = null)
            LengthField("Y", bounds.top.toDouble(), { tool.setPosition(top = it) }, unit, dpi, Modifier.weight(1f), step = null)
        }

        SectionHeader("Size")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LengthField("Width", st.width.toDouble(), { tool.setSize(width = it) }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0)
            LengthField("Height", st.height.toDouble(), { tool.setSize(height = it) }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0)
        }
        ToggleRow("Keep aspect ratio", tool.keepAspect, { tool.keepAspect = it })
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
            modifier = Modifier.padding(top = 4.dp),
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
                    "Each arrow moves by ${Units.format(tool.nudgeStepPx, unit, dpi)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
