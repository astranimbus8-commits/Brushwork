package com.brushwork.paint.ui.mask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskComponent
import com.brushwork.paint.masks.MaskGeometry
import com.brushwork.paint.masks.MaskLayerOps
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The Masks tool's Components sheet (v1.5 §4.3a; owned by A5), half-height and non-modal: one
 * row per component (kind, mode, invert, amount, visible, duplicate, delete; feather of radial
 * ones), the mask-wide controls (invert, density, start from hidden / visible), the mask brush's
 * hardness, and what the mask can be used for (selection, a filter through it, a pixel mask,
 * delete). Every change is one undo step (sliders on release).
 */
@Composable
internal fun MaskComponentsSheet(tool: MaskTool) {
    val c = tool.controller
    val layer = tool.editLayer
    BwSheet(title = if (layer != null) "Mask of ${layer.name}" else "Mask", onDismiss = { tool.componentsOpen = false }) {
        // Layer fields aren't Compose state; layersVersion and the tool's revision are.
        if (c.layersVersion < 0 || tool.revision < 0) return@BwSheet
        val l = tool.editLayer
        if (l == null) {
            Note("No mask yet. Tap + Linear, + Radial or + Brush and drag on the canvas: a new adjustment layer gets the mask.")
            return@BwSheet
        }
        if (tool.needsReplace) {
            Note("\"${l.name}\" has a painted mask. Replace it with an editable one to add linear, radial and brush parts (undo brings it back).")
            TextButton(onClick = { tool.chooseTarget(MaskTool.Target.ThisLayer) }) { Text("Replace with an editable mask") }
            MaskActions(tool)
            return@BwSheet
        }
        val shown = tool.displaySpec ?: return@BwSheet
        val committed = tool.spec ?: shown
        if (l.mask == null) {
            Note(if (l.isAdjustmentLayer) "No mask yet: the effect shows everywhere. Add a part to limit it." else "No mask yet: add a part to make one.")
        }
        if (shown.components.isEmpty()) {
            Note("No parts yet. Tap + Linear, + Radial or + Brush in the strip, then drag on the canvas.")
        }
        shown.components.forEach { comp -> ComponentRow(tool, shown, committed, comp) }

        SectionHeader("Whole mask")
        ToggleRow("Invert mask", shown.invert, { tool.commitSpec(committed.copy(invert = it), "Invert mask") })
        LabeledSlider(
            label = "Density",
            value = shown.density,
            onValueChange = { tool.previewSpec(committed.copy(density = it.coerceIn(0f, 1f))) },
            onValueChangeFinished = { tool.displaySpec?.let { tool.commitSpec(it, "Mask density") } },
            valueRange = 0f..1f,
            valueText = "${(shown.density * 100f).toInt()}%",
            typing = SliderTyping.Percent,
        )
        Text("Start from", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        ChoiceChips(listOf("Hidden", "Visible"), if (shown.startFull) 1 else 0, { tool.commitSpec(committed.copy(startFull = it == 1), "Mask start") })

        if (shown.components.any { it is BrushMask } || tool.armed == MaskTool.Kind.BRUSH) {
            SectionHeader("Mask brush")
            val brush = c.maskBrush
            LabeledSlider(
                label = "Hardness",
                value = brush.hardness,
                onValueChange = { c.updatePreset(ToolId.MASK, c.maskBrush.copy(hardness = it.coerceIn(0f, 1f))) },
                // Kept with the mask brush (like the side sliders' size and flow).
                onValueChangeFinished = { com.brushwork.paint.brush.BrushPresetStore.get(c.appContext).persist(c, ToolId.MASK) },
                valueRange = 0f..1f,
                valueText = "${(brush.hardness * 100f).toInt()}%",
                typing = SliderTyping.Percent,
            )
            Text(
                "Size and flow are the side sliders. Flow builds up: paint again to add more.",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
            )
        }
        MaskActions(tool)
        SectionHeader("Advanced")
        ToggleRow(
            "Safe compositing",
            AdjustmentStage.safeCompositing,
            { on ->
                c.settings.safeCompositing = on
                AdjustmentStage.safeCompositing = on
                c.invalidateDoc(null)
                tool.touch()
            },
            description = "Show adjustment layers on the canvas without their effect (if the canvas misbehaves). Exports, merging and the eyedropper still use the effect.",
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 8.dp))
}

private fun kindIcon(c: MaskComponent): ImageVector = when (c) {
    is LinearMask -> Icons.Filled.Gradient
    is RadialMask -> Icons.Filled.RadioButtonUnchecked
    is BrushMask -> Icons.Filled.Brush
}

/** One component: select by tapping; mode, invert, amount (+ feather), visible, duplicate, delete. */
@Composable
private fun ComponentRow(tool: MaskTool, shown: MaskSpec, committed: MaskSpec, comp: MaskComponent) {
    val selected = tool.selectedId == comp.id
    val base = committed.components.firstOrNull { it.id == comp.id } ?: comp
    fun live(next: MaskComponent) = tool.previewSpec(MaskGeometry.replaced(committed, next.id, next))
    fun finish(label: String) { tool.displaySpec?.let { tool.commitSpec(it, label) } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrushworkColors.AccentDim.copy(alpha = 0.35f) else BrushworkColors.ChromeHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = "Select ${MaskGeometry.displayName(shown, comp)}", role = Role.Button) { tool.select(if (selected) null else comp.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kindIcon(comp), contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(MaskGeometry.displayName(shown, comp), style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            IconButton(onClick = { tool.updateComponent(MaskGeometry.withCommon(base, visible = !base.visible), if (base.visible) "Hide mask part" else "Show mask part") }) {
                Icon(if (comp.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = if (comp.visible) "Hide ${MaskGeometry.displayName(shown, comp)}" else "Show ${MaskGeometry.displayName(shown, comp)}")
            }
            IconButton(onClick = { tool.duplicateComponent(comp.id) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate ${MaskGeometry.displayName(shown, comp)}")
            }
            IconButton(onClick = { tool.deleteComponent(comp.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${MaskGeometry.displayName(shown, comp)}", tint = BrushworkColors.Danger)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChoiceChips(
                MaskMode.entries.map { modeLabel(it) },
                comp.mode.ordinal,
                { i -> tool.updateComponent(MaskGeometry.withCommon(base, mode = MaskMode.entries[i]), "Mask mode") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = comp.invert,
                onClick = { tool.updateComponent(MaskGeometry.withCommon(base, invert = !base.invert), "Invert mask part") },
                label = { Text("Invert") },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = maskChipColors(),
                modifier = Modifier.padding(start = 6.dp).semantics { stateDescription = if (comp.invert) "Inverted" else "Not inverted" },
            )
        }
        LabeledSlider(
            label = "Amount",
            value = comp.amount,
            onValueChange = { live(MaskGeometry.withCommon(base, amount = it.coerceIn(0f, 1f))) },
            onValueChangeFinished = { finish("Mask amount") },
            valueRange = 0f..1f,
            valueText = "${(comp.amount * 100f).toInt()}%",
            typing = SliderTyping.Percent,
        )
        if (comp is RadialMask && base is RadialMask) {
            LabeledSlider(
                label = "Feather",
                value = comp.feather,
                onValueChange = { live(base.copy(feather = it.coerceIn(0f, 1f))) },
                onValueChangeFinished = { finish("Mask feather") },
                valueRange = 0f..1f,
                valueText = "${(comp.feather * 100f).toInt()}%",
                typing = SliderTyping.Percent,
            )
        }
    }
}

/** What the mask can be used for: a selection, a filter through it, a pixel mask; delete it. */
@Composable
private fun MaskActions(tool: MaskTool) {
    val c = tool.controller
    val layer = tool.editLayer ?: return
    if (layer.mask == null) return
    SectionHeader("Use the mask")
    ActionRow(Icons.Filled.HighlightAlt, "Use mask as selection") { MaskLayerOps.useAsSelection(c, layer) }
    ActionRow(Icons.Filled.PhotoFilter, "Apply a filter through this mask…") {
        if (MaskLayerOps.prepareFilterThroughMask(c, layer)) {
            tool.componentsOpen = false
            tool.filterBrowserOpen = true
        }
    }
    if (layer.maskSpec != null) ActionRow(Icons.Filled.FilterBAndW, "Convert to pixel mask") { MaskLayerOps.toPixelMask(c, layer); tool.touch() }
    ActionRow(Icons.Filled.Delete, "Delete mask", danger = true) {
        c.deleteMask(layer)
        tool.select(null)
        tool.touch()
    }
    if (layer.isAdjustmentLayer) {
        ActionRow(Icons.Filled.Layers, "Apply to layer below") {
            tool.componentsOpen = false
            com.brushwork.paint.ui.layers.LayerOps.mergeDown(c, layer)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = if (danger) ButtonDefaults.textButtonColors(contentColor = BrushworkColors.Danger) else ButtonDefaults.textButtonColors(contentColor = BrushworkColors.OnChrome),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}
