package com.brushwork.paint.ui.mask

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Options strip of the Masks tool (v1.5 §4.3a; owned by A5), horizontally scrollable:
 * `[Target ▾] [+ Linear] [+ Radial] [+ Brush] ([Brush|Erase] [Done]) [Add|Subtract|Intersect]
 * [Invert] [👁] [Adjust…] [Components (n)]`, plus the sheets and questions the tool asks for.
 */
@Composable
fun MaskToolOptions(tool: MaskTool) {
    val c = tool.controller
    // What the strip shows changes rarely; drags only move the live spec.
    val info by remember(tool) {
        derivedStateOf {
            val layer = tool.editLayer
            val sel = tool.selectedId?.let { id -> tool.spec?.components?.firstOrNull { it.id == id } }
            StripInfo(
                activeIsAdjustment = c.activeLayer.isAdjustmentLayer,
                layerName = layer?.name ?: c.activeLayer.name,
                count = tool.spec?.components?.size ?: 0,
                selectedMode = sel?.mode,
                selectedInvert = sel?.invert == true,
                hasSelection = sel != null,
                brushSelected = sel is BrushMask,
            )
        }
    }
    TargetChip(tool, info)
    BarDivider()
    AddChip("Linear", Icons.Filled.Gradient, tool.armed == MaskTool.Kind.LINEAR) { tool.arm(MaskTool.Kind.LINEAR) }
    AddChip("Radial", Icons.Filled.RadioButtonUnchecked, tool.armed == MaskTool.Kind.RADIAL) { tool.arm(MaskTool.Kind.RADIAL) }
    AddChip("Brush", Icons.Filled.Brush, tool.armed == MaskTool.Kind.BRUSH) { tool.arm(MaskTool.Kind.BRUSH) }
    if (tool.armed == MaskTool.Kind.BRUSH || info.brushSelected) {
        BarDivider()
        StripChip("Brush", selected = !tool.brushErase) { tool.brushErase = false }
        StripChip("Erase", selected = tool.brushErase, icon = Icons.Filled.AutoFixOff) { tool.brushErase = true }
        if (info.brushSelected) TextButton(onClick = { tool.select(null) }) { Text("Done") }
    }
    BarDivider()
    val mode = info.selectedMode ?: tool.newMode
    for (m in MaskMode.entries) StripChip(modeLabel(m), selected = mode == m) { tool.setMode(m) }
    StripChip("Invert", selected = info.selectedInvert, enabled = info.hasSelection, icon = Icons.Filled.SwapVert) { tool.toggleInvertSelected() }
    ToolIconButton(
        if (tool.overlayAlways) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
        contentDescription = if (tool.overlayAlways) "Mask overlay: always on" else "Mask overlay: after edits",
        onClick = { tool.toggleOverlay() },
        selected = tool.overlayAlways,
    )
    BarDivider()
    AdjustChip(tool, enabled = info.activeIsAdjustment)
    TextButton(onClick = { tool.componentsOpen = true }) { Text("Components (${info.count})") }

    if (tool.adjustOpen && info.activeIsAdjustment) AdjustmentSheet(tool)
    if (tool.componentsOpen) MaskComponentsSheet(tool)
    if (tool.filterBrowserOpen) FilterBrowser(c) { tool.filterBrowserOpen = false }
    tool.replacePrompt?.let { layer ->
        AlertDialog(
            onDismissRequest = { tool.answerReplace(false) },
            title = { Text("Replace the painted mask?") },
            text = { Text("\"${layer.name}\" has a painted mask. An editable mask (linear, radial and brush parts) replaces it; undo brings the painted one back.") },
            confirmButton = { TextButton(onClick = { tool.answerReplace(true) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { tool.answerReplace(false) }) { Text("Keep it") } },
            containerColor = BrushworkColors.ChromeHigh,
        )
    }
}

private data class StripInfo(
    val activeIsAdjustment: Boolean,
    val layerName: String,
    val count: Int,
    val selectedMode: MaskMode?,
    val selectedInvert: Boolean,
    val hasSelection: Boolean,
    val brushSelected: Boolean,
)

internal fun modeLabel(m: MaskMode): String = when (m) {
    MaskMode.ADD -> "Add"
    MaskMode.SUBTRACT -> "Subtract"
    MaskMode.INTERSECT -> "Intersect"
}

@Composable
private fun BarDivider() {
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = BrushworkColors.ChromeBorder)
}

@Composable
internal fun maskChipColors() =
    FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White)

@Composable
private fun StripChip(label: String, selected: Boolean, enabled: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        colors = maskChipColors(),
        modifier = Modifier
            .padding(end = 4.dp)
            .semantics { stateDescription = if (selected) "$label: on" else "$label: off" },
    )
}

/** "+ Linear": arms a component kind (highlighted while armed). */
@Composable
private fun AddChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, armed: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = armed,
        onClick = onClick,
        label = { Text("+ $label", maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = maskChipColors(),
        modifier = Modifier
            .padding(end = 4.dp)
            .semantics { stateDescription = if (armed) "Add $label: armed, drag on the canvas" else "Add $label" },
    )
}

/** Where creating gestures go: this adjustment's mask, this layer's mask, or a new adjustment layer. */
@Composable
private fun TargetChip(tool: MaskTool, info: StripInfo) {
    var open by remember { mutableStateOf(false) }
    val target = tool.target
    val label = when {
        info.activeIsAdjustment -> "${info.layerName} mask"
        target == MaskTool.Target.ThisLayer -> "${info.layerName} mask"
        else -> "New ${AdjustmentEffects.filters.firstOrNull { it.id == tool.newEffectId }?.name ?: "adjustment"}"
    }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.widthIn(max = 170.dp)) {
            Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose what the mask is for")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (info.activeIsAdjustment) {
                DropdownMenuItem(
                    text = { Text("This adjustment's mask") },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { open = false },
                )
                Text(
                    "Select a pixel layer to start another adjustment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 240.dp),
                )
                return@DropdownMenu
            }
            Text(
                "NEW ADJUSTMENT LAYER",
                style = MaterialTheme.typography.labelSmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (f in AdjustmentEffects.filters) {
                val chosen = target == MaskTool.Target.NewAdjustment(f.id)
                DropdownMenuItem(
                    text = { Text(f.name) },
                    leadingIcon = { if (chosen) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; tool.chooseTarget(MaskTool.Target.NewAdjustment(f.id)) },
                )
            }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            DropdownMenuItem(
                text = { Text("This layer's mask") },
                leadingIcon = { if (target == MaskTool.Target.ThisLayer) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                onClick = { open = false; tool.chooseTarget(MaskTool.Target.ThisLayer) },
            )
        }
    }
}

/** "Adjust…": opens the Adjust sheet; pulses a few times after a new adjustment layer was made. */
@Composable
private fun AdjustChip(tool: MaskTool, enabled: Boolean) {
    val pulse = remember { Animatable(0f) }
    val count = tool.adjustPulse
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        repeat(3) {
            pulse.animateTo(1f, tween(260))
            pulse.animateTo(0f, tween(260))
        }
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BrushworkColors.Accent.copy(alpha = 0.45f * pulse.value)),
    ) {
        FilterChip(
            selected = tool.adjustOpen,
            onClick = { tool.openAdjust() },
            enabled = enabled,
            label = { Text("Adjust…", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = maskChipColors(),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

