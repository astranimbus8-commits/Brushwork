package com.brushwork.paint.ui.clone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.brush.BrushToolOptions
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Options strip of the clone stamp (v1.5 §4.2):
 * `[⊕ Set source] [Aligned ☑] [Sample: This layer ▾] [Show source ☑] | brush`.
 * Without a source a hint chip comes first ("Long-press where to copy from"; tapping it arms
 * Set source too). The brush part is the brush tool's (preset, size, opacity, pressure).
 */
@Composable
fun CloneToolOptions(tool: CloneTool) {
    val hasSource = tool.anchor.source != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!hasSource && !tool.armed) {
            AssistChip(
                onClick = { tool.arm(true) },
                label = { Text(CloneTool.HINT, maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.TouchApp, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = BrushworkColors.OnChrome,
                    leadingIconContentColor = BrushworkColors.Accent,
                ),
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        FilterChip(
            selected = tool.armed,
            onClick = { tool.arm(!tool.armed) },
            label = { Text(if (tool.armed) "Tap the source" else "Set source", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.MyLocation, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            colors = chipColors(),
            modifier = Modifier
                .heightIn(min = 40.dp)
                .semantics { stateDescription = if (tool.armed) "Waiting for a tap on the canvas" else if (hasSource) "Source set" else "No source" },
        )
        CheckChip("Aligned", tool.aligned) { tool.setAligned(it) }
        SampleChip(tool.sampleAllLayers) { tool.setSampleAllLayers(it) }
        CheckChip("Show source", tool.showSource) { tool.setShowSource(it) }
        StripDivider()
        BrushToolOptions(tool.brush)
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = BrushworkColors.AccentDim,
    selectedLabelColor = Color.White,
    selectedLeadingIconColor = Color.White,
)

/** An on/off chip with a check mark while on. */
@Composable
private fun CheckChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        colors = chipColors(),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .semantics { stateDescription = if (checked) "On" else "Off" },
    )
}

/** "Sample: This layer ▾" with This layer / All layers. */
@Composable
private fun SampleChip(allLayers: Boolean, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = if (allLayers) ALL_LAYERS else THIS_LAYER
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("Sample: $current", maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = BrushworkColors.OnChrome, trailingIconContentColor = BrushworkColors.OnChromeDim),
            modifier = Modifier
                .heightIn(min = 40.dp)
                .semantics { contentDescription = "Sample from: $current" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }, containerColor = BrushworkColors.ChromeHigh) {
            for ((label, all) in listOf(THIS_LAYER to false, ALL_LAYERS to true)) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onChange(all); open = false },
                    leadingIcon = { if (all == allLayers) Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun StripDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(BrushworkColors.ChromeBorder))
}

private const val THIS_LAYER = "This layer"
private const val ALL_LAYERS = "All layers"
