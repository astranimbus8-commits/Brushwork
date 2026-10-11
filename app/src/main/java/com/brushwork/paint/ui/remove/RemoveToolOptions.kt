package com.brushwork.paint.ui.remove

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.remove.CafSource
import com.brushwork.paint.tools.remove.RemoveSettings
import com.brushwork.paint.tools.remove.RemoveTool
import com.brushwork.paint.ui.common.IncrementStepping
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Remove tool strip: brush size (a logarithmic slider, document pixels), where to sample from,
 * color adaptation, and a spinner while a painted area is being filled.
 *
 * v1.8: the v1.7 layout, kept for the screens that show it whole; the editor's strip is
 * [removeStrip], built from the same pieces ([RemoveSizeSlider], [CafSourceEntries]).
 */
@Composable
fun RemoveToolOptions(tool: RemoveTool) {
    val s = tool.settings
    val size = tool.size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Size", style = MaterialTheme.typography.labelLarge, color = BrushworkColors.OnChromeDim)
        RemoveSizeSlider(tool, Modifier.width(150.dp))
        Text(
            "${size.roundToInt()} px",
            style = MaterialTheme.typography.labelLarge,
            color = BrushworkColors.OnChrome,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 52.dp),
        )
        StripDivider()
        SourceChip(s.source) { tool.settings = tool.settings.copy(source = it) }
        FilterChip(
            selected = s.colorAdaptation,
            onClick = { tool.settings = tool.settings.copy(colorAdaptation = !s.colorAdaptation) },
            label = { Text(RemoveStripItems.ADAPT_LABEL) },
            leadingIcon = if (s.colorAdaptation) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = BrushworkColors.AccentDim,
                selectedLabelColor = Color.White,
                selectedLeadingIconColor = Color.White,
            ),
        )
        if (tool.busy) {
            CircularProgressIndicator(
                Modifier.size(20.dp).semantics { contentDescription = RemoveStripItems.BUSY_LABEL },
                strokeWidth = 2.dp,
                color = BrushworkColors.Accent,
            )
        }
    }
}

/**
 * The Remove brush size slider (described "Remove brush size", its state "N pixels"):
 * logarithmic, live while dragging (the canvas shows the brush), saved on release.
 */
@Composable
internal fun RemoveSizeSlider(tool: RemoveTool, modifier: Modifier) {
    val size = tool.size
    val sizeStep = LocalIncrements.current?.step(IncrementKind.SIZE)
    Slider(
        value = RemoveSizeScale.toPosition(size),
        // Live while dragging (the canvas shows the brush), saved on release. v1.6 §3.4: a px
        // size, so on the Size increment's multiples while increments are on (ends reachable).
        onValueChange = { pos ->
            val v = RemoveSizeScale.fromPosition(pos)
            val step = sizeStep?.toDouble()
            val min = RemoveSettings.MIN_SIZE.toDouble()
            val max = RemoveSettings.MAX_SIZE.toDouble()
            tool.previewSize(if (step == null) v else IncrementStepping.snapSlider(v.toDouble(), step, min, max).toFloat())
        },
        onValueChangeFinished = { tool.commitSize() },
        colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
        modifier = modifier.semantics {
            contentDescription = RemoveStripItems.SIZE_LABEL
            stateDescription = "${size.roundToInt()} pixels"
        },
    )
}

/** "Sample: Current layer ▾" chip. */
@Composable
private fun SourceChip(source: CafSource, onChange: (CafSource) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(RemoveStripItems.sourceLabel(source)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }) {
            CafSourceEntries(source) { onChange(it); open = false }
        }
    }
}

/** The sample sources as menu entries, [source] checked. */
@Composable
internal fun CafSourceEntries(source: CafSource, onPick: (CafSource) -> Unit) {
    CafSource.entries.forEach { o ->
        DropdownMenuItem(
            text = { Text(o.label) },
            onClick = { onPick(o) },
            leadingIcon = { if (o == source) Icon(Icons.Filled.Check, contentDescription = null) },
        )
    }
}

@Composable
private fun StripDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(BrushworkColors.ChromeBorder))
}

/** Logarithmic mapping of the Remove brush size to a 0..1 slider position. */
internal object RemoveSizeScale {
    private val lnMin = ln(RemoveSettings.MIN_SIZE)
    private val lnMax = ln(RemoveSettings.MAX_SIZE)

    fun toPosition(size: Float): Float = ((ln(size.coerceIn(RemoveSettings.MIN_SIZE, RemoveSettings.MAX_SIZE)) - lnMin) / (lnMax - lnMin)).coerceIn(0f, 1f)

    fun fromPosition(pos: Float): Float {
        val v = exp(lnMin + pos.coerceIn(0f, 1f) * (lnMax - lnMin))
        // Whole pixels; finer steps are not visible at this size range.
        return v.roundToInt().toFloat().coerceIn(RemoveSettings.MIN_SIZE, RemoveSettings.MAX_SIZE)
    }
}
