package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.frame.FrameSettings
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_GRID = 12

/** Border / margins / gutters / fill settings, "Create frame layer" and "apply to current frame". */
@Composable
fun FrameSettingsSheet(tool: FrameDividerTool, status: FrameDividerTool.Status) {
    val s = tool.settings
    val doc = tool.controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = tool.unit
    val maxLen = min(doc.width, doc.height) / 2.0
    var pickColor by remember { mutableStateOf(false) }
    fun set(transform: (FrameSettings) -> FrameSettings) { tool.settings = transform(tool.settings) }

    BwSheet(
        title = "Frame layer",
        onDismiss = { tool.settingsOpen = false },
        actions = { UnitSelector(unit, { tool.unit = it }) },
        // The actions stay in view under the settings, which scroll in the half-height sheet.
        footer = {
            Button(onClick = { if (tool.createFrameLayer()) tool.settingsOpen = false }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Create frame layer")
            }
            if (status == FrameDividerTool.Status.READY) {
                OutlinedButton(onClick = { if (tool.applyStyleToCurrent()) tool.settingsOpen = false }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Apply border & fill to current frame")
                }
            }
        },
    ) {
        SectionHeader("Border")
        LengthField("Border width", s.borderWidth.toDouble(), { v -> set { it.copy(borderWidth = v.toFloat()) } }, unit, dpi, minPx = 0.0, maxPx = maxLen)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            ColorSwatch(s.borderColor, size = 36.dp, onClick = { pickColor = true })
            Spacer(Modifier.width(12.dp))
            Text("Border color", style = MaterialTheme.typography.bodyMedium)
        }

        SectionHeader("Page margins")
        ToggleRow("Same on all sides", s.uniformMargins, { on ->
            set { if (on) it.copy(uniformMargins = true).withUniformMargin(it.marginTop) else it.copy(uniformMargins = false) }
        })
        if (s.uniformMargins) {
            LengthField("Margin", s.marginTop.toDouble(), { v -> set { it.withUniformMargin(v.toFloat()) } }, unit, dpi, minPx = 0.0, maxPx = maxLen)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LengthField("Top", s.marginTop.toDouble(), { v -> set { it.copy(marginTop = v.toFloat()) } }, unit, dpi, Modifier.weight(1f), step = null, minPx = 0.0, maxPx = maxLen)
                LengthField("Bottom", s.marginBottom.toDouble(), { v -> set { it.copy(marginBottom = v.toFloat()) } }, unit, dpi, Modifier.weight(1f), step = null, minPx = 0.0, maxPx = maxLen)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                LengthField("Left", s.marginLeft.toDouble(), { v -> set { it.copy(marginLeft = v.toFloat()) } }, unit, dpi, Modifier.weight(1f), step = null, minPx = 0.0, maxPx = maxLen)
                LengthField("Right", s.marginRight.toDouble(), { v -> set { it.copy(marginRight = v.toFloat()) } }, unit, dpi, Modifier.weight(1f), step = null, minPx = 0.0, maxPx = maxLen)
            }
        }

        SectionHeader("Gutters")
        LengthField("Between rows", s.gutterH.toDouble(), { v -> set { it.copy(gutterH = v.toFloat()) } }, unit, dpi, minPx = 0.0, maxPx = maxLen)
        LengthField("Between columns", s.gutterV.toDouble(), { v -> set { it.copy(gutterV = v.toFloat()) } }, unit, dpi, Modifier.padding(top = 8.dp), minPx = 0.0, maxPx = maxLen)
        Text(
            "Mostly horizontal cuts leave the row gap, mostly vertical cuts the column gap. Cuts within 5° of level snap straight.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionHeader("Outside the panels")
        ChoiceChips(listOf("White", "Transparent"), if (s.fillOutside) 0 else 1, { i -> set { it.copy(fillOutside = i == 0) } })

        SectionHeader("New frame layout")
        NumberField("Rows", s.rows.toDouble(), { v -> set { it.copy(rows = v.roundToInt().coerceIn(1, MAX_GRID)) } }, decimals = 0, min = 1.0, max = MAX_GRID.toDouble(), step = 1.0)
        NumberField("Columns", s.cols.toDouble(), { v -> set { it.copy(cols = v.roundToInt().coerceIn(1, MAX_GRID)) } }, Modifier.padding(top = 8.dp), decimals = 0, min = 1.0, max = MAX_GRID.toDouble(), step = 1.0)
    }

    if (pickColor) {
        ColorPickerDialog(
            initial = s.borderColor,
            onPick = { c -> set { it.copy(borderColor = c) } },
            onDismiss = { pickColor = false },
            title = "Border color",
            showAlpha = true,
        )
    }
}

/** Replaces the panels of the current frame with an even rows x columns grid. */
@Composable
fun FrameGridDialog(tool: FrameDividerTool) {
    var rows by remember { mutableIntStateOf(if (tool.settings.rows * tool.settings.cols <= 1) 3 else tool.settings.rows) }
    var cols by remember { mutableIntStateOf(if (tool.settings.rows * tool.settings.cols <= 1) 2 else tool.settings.cols) }
    BwDialog(
        title = "Split into rows × columns",
        onDismiss = { tool.gridOpen = false },
        confirmText = "Apply",
        onConfirm = { if (tool.applyGrid(rows, cols)) tool.gridOpen = false },
    ) {
        Text(
            "Replaces all panels of the current frame with an even grid (uses the gutter settings).",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
        )
        NumberField("Rows", rows.toDouble(), { rows = it.roundToInt().coerceIn(1, MAX_GRID) }, Modifier.padding(top = 8.dp), decimals = 0, min = 1.0, max = MAX_GRID.toDouble(), step = 1.0)
        NumberField("Columns", cols.toDouble(), { cols = it.roundToInt().coerceIn(1, MAX_GRID) }, Modifier.padding(top = 8.dp), decimals = 0, min = 1.0, max = MAX_GRID.toDouble(), step = 1.0)
    }
}
