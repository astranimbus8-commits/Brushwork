package com.brushwork.paint.ui.remove

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.remove.CafSource
import com.brushwork.paint.tools.remove.RemoveTool
import com.brushwork.paint.ui.placement.StripMenu
import com.brushwork.paint.ui.placement.StripMenuButton
import com.brushwork.paint.ui.placement.StripTextButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripSpec
import kotlin.math.roundToInt

/**
 * The Remove tool's strip (v1.8, design §3.9 and §4.6; area G).
 *
 * Row 1 only, no More row (no toggle): "Remove brush size" (a readout of the size, "N px"; a tap
 * opens a popup with v1.7's slider, described as before), "Sample: …" (a dropdown of the sample
 * sources) and "Color adaptation" (a toggle). While a painted area is being filled the readout
 * shows a spinner (described "Removing") in place of its icon.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun removeStrip(tool: RemoveTool, c: EditorController): StripSpec {
    val s = tool.settings
    val items = listOf(
        StripItem(RemoveStripItems.SIZE, RemoveStripItems.SIZE_LABEL, RemoveStripItems.SIZE_DP.dp) { RemoveSizeReadout(tool) },
        StripItem(RemoveStripItems.SOURCE, RemoveStripItems.sourceLabel(s.source), RemoveStripItems.SOURCE_DP.dp) { SourceMenu(tool) },
        StripItem(RemoveStripItems.ADAPT, RemoveStripItems.ADAPT_LABEL, RemoveStripItems.ADAPT_DP.dp) {
            val on = tool.settings.colorAdaptation
            StripTextButton(
                RemoveStripItems.ADAPT_LABEL,
                RemoveStripItems.ADAPT_DP.dp,
                onClick = { tool.settings = tool.settings.copy(colorAdaptation = !on) },
                checked = on,
            )
        },
    )
    return StripSpec(items)
}

/** The Remove strip's item keys, widths (measured, rounded up to 4 dp) and handles (the v1.7 labels). */
internal object RemoveStripItems {
    const val SIZE = "remove.size"
    const val SOURCE = "remove.source"
    const val ADAPT = "remove.adaptation"

    /** The size readout: its icon and up to "600 px". */
    const val SIZE_DP = 64f

    /** "Sample: Current layer" on two lines at 11 sp, and ▾. */
    const val SOURCE_DP = 96f

    /** "Color adaptation" at 11 sp. */
    const val ADAPT_DP = 104f

    const val SIZE_LABEL = "Remove brush size"
    const val ADAPT_LABEL = "Color adaptation"
    const val BUSY_LABEL = "Removing"

    fun sourceLabel(source: CafSource) = "Sample: ${source.label}"
}

/**
 * The size readout: its icon (a spinner while busy) and "N px", described "Remove brush size"; a
 * tap opens a popup anchored here with the size slider. The popup is a dropdown: it never closes
 * the More row, and the readout leaves the semantics while it shows (one "Remove brush size").
 */
@Composable
private fun RemoveSizeReadout(tool: RemoveTool) {
    var open by remember { mutableStateOf(false) }
    val px = tool.size.roundToInt()
    Box {
        StripMenuButton(RemoveStripItems.SIZE_LABEL, RemoveStripItems.SIZE_DP.dp, open, onClick = { open = true }, state = "$px pixels", arrow = false) {
            if (tool.busy) {
                CircularProgressIndicator(
                    Modifier.size(14.dp).semantics { contentDescription = RemoveStripItems.BUSY_LABEL },
                    strokeWidth = 2.dp,
                    color = BrushworkColors.Accent,
                )
            } else {
                Icon(Icons.Filled.LineWeight, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(3.dp))
            Text("$px px", fontSize = 13.sp, color = BrushworkColors.OnChrome, maxLines = 1, softWrap = false)
        }
        StripMenu(open, onDismiss = { open = false }) {
            // Fixed width: the menu measures its content's intrinsic width.
            Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", style = MaterialTheme.typography.labelLarge, color = BrushworkColors.OnChromeDim, modifier = Modifier.weight(1f))
                    Text("$px px", style = MaterialTheme.typography.labelLarge, color = BrushworkColors.OnChrome)
                }
                RemoveSizeSlider(tool, Modifier.fillMaxWidth())
            }
        }
    }
}

/** "Sample: Current layer ▾" and its dropdown of the sample sources. */
@Composable
private fun SourceMenu(tool: RemoveTool) {
    var open by remember { mutableStateOf(false) }
    val source = tool.settings.source
    Box {
        StripTextButton(RemoveStripItems.sourceLabel(source), RemoveStripItems.SOURCE_DP.dp, onClick = { open = true }, arrow = true)
        StripMenu(open, onDismiss = { open = false }) {
            CafSourceEntries(source) {
                tool.settings = tool.settings.copy(source = it)
                open = false
            }
        }
    }
}
