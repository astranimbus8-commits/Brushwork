package com.brushwork.paint.ui.remove

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.inpaint.InpaintParams
import com.brushwork.paint.inpaint.SamplingArea
import com.brushwork.paint.tools.remove.CafOptions
import com.brushwork.paint.tools.remove.CafOutput
import com.brushwork.paint.tools.remove.CafSource
import com.brushwork.paint.tools.remove.ContentAwareFillJob
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/**
 * Options of the selection's content-aware fill (sampling area, expansion, color adaptation,
 * where to sample from and where the result goes) with Fill and Refill. Both close the sheet
 * ([onStart], default [onDismiss]) and run under the editor's busy overlay, which shows the
 * progress and a Stop button; Refill replaces the previous fill with a different one.
 */
@Composable
fun ContentAwareFillSheet(controller: EditorController, onDismiss: () -> Unit, onStart: () -> Unit = onDismiss) {
    var options by remember(controller) { mutableStateOf(ContentAwareFillJob.options(controller)) }
    fun update(o: CafOptions) {
        options = o
        ContentAwareFillJob.setOptions(controller, o)
    }
    // Refill is offered while the latest step is still the previous fill.
    val canRefill = remember(controller, controller.editCount) { ContentAwareFillJob.canRefill(controller) }
    val hasSelection = controller.selection != null

    BwSheet(
        title = ContentAwareFillJob.FILL_LABEL,
        onDismiss = onDismiss,
        maxHeightFraction = 0.6f,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val o = options
                        onStart()
                        ContentAwareFillJob.fillSelection(controller, o, refill = true)
                    },
                    enabled = canRefill && hasSelection,
                    border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrushworkColors.Accent),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refill")
                }
                Button(
                    onClick = {
                        val o = options
                        onStart()
                        ContentAwareFillJob.fillSelection(controller, o)
                    },
                    enabled = hasSelection,
                    colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent, contentColor = BrushworkColors.Chrome),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fill")
                }
            }
        },
    ) {
        Text(
            if (hasSelection) "Fills the selected area with texture and structure from around it."
            else "Select the area to fill first (for example the object to remove).",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
        )
        if (canRefill) {
            Text(
                "Refill replaces the last fill with a different one.",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionHeader("Sampling area")
        ChoiceChips(
            options = SAMPLING.map { it.second },
            selected = SAMPLING.indexOfFirst { it.first == options.sampling }.coerceAtLeast(0),
            onSelect = { update(options.copy(sampling = SAMPLING[it].first)) },
        )
        Text(
            when (options.sampling) {
                SamplingArea.AUTO -> "Takes texture from a band around the selection."
                SamplingArea.RECTANGLE -> "Takes texture from a rectangle around the selection."
                SamplingArea.WHOLE -> "Takes texture from anywhere in the picture."
            },
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.heightIn(min = 8.dp))
        LabeledSlider(
            label = "Expand selection",
            value = options.expand.toFloat(),
            onValueChange = { update(options.copy(expand = it.roundToInt().coerceIn(0, InpaintParams.MAX_EXPAND))) },
            valueRange = 0f..InpaintParams.MAX_EXPAND.toFloat(),
            steps = InpaintParams.MAX_EXPAND - 1,
            valueText = "${options.expand} px",
            typing = SliderTyping(suffix = "px"),
        )
        ToggleRow(
            label = "Color adaptation",
            checked = options.colorAdaptation,
            onCheckedChange = { update(options.copy(colorAdaptation = it)) },
            description = "Blends brightness and color into the surroundings",
        )

        SectionHeader("Sample from")
        ChoiceChips(
            options = CafSource.entries.map { it.label },
            selected = options.source.ordinal,
            onSelect = { update(options.copy(source = CafSource.entries[it])) },
        )

        SectionHeader("Output to")
        ChoiceChips(
            options = CafOutput.entries.map { it.label },
            selected = options.output.ordinal,
            onSelect = { update(options.copy(output = CafOutput.entries[it])) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (options.output == CafOutput.NEW_LAYER) "The fill goes on a new layer above; the original stays untouched."
                else "The fill replaces the selected pixels of the current layer.",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val SAMPLING = listOf(
    SamplingArea.AUTO to "Auto",
    SamplingArea.RECTANGLE to "Rectangle",
    SamplingArea.WHOLE to "Whole layer",
)
