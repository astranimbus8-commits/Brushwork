package com.brushwork.paint.ui.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors

/** Brush library + all brush settings for the current paint tool (bottom sheet / dialog). */
@Composable
fun BrushPanel(controller: EditorController, onDismiss: () -> Unit) {
    val toolId = controller.sliderToolId
    val preset = controller.presetFor(toolId) ?: return
    val store = remember(controller) { BrushPresetStore.get(controller.appContext) }
    val library = remember(toolId) { BrushLibrary.presetsFor(toolId) }
    // Bumped whenever stored edits change so the other presets' thumbnails refresh.
    var storeVersion by remember { mutableIntStateOf(0) }
    val saved = remember(toolId, storeVersion) { library.associate { it.id to store.edited(it.id) } }
    var confirmReset by remember { mutableStateOf(false) }
    val defaults = remember(preset.id) { BrushLibrary.byId(preset.id) }
    val edited = defaults != null && defaults != preset

    val dismiss = {
        store.persist(controller, toolId)
        onDismiss()
    }
    val onEdit: PresetEdit = { persist, transform -> store.edit(controller, toolId, persist, transform) }

    BwSheet(
        title = toolId.label,
        onDismiss = dismiss,
        actions = {
            IconButton(onClick = { confirmReset = true }, enabled = edited) {
                Icon(Icons.Filled.RestartAlt, contentDescription = "Reset ${preset.name} to default")
            }
        },
    ) {
        BrushStrokePreview(preset, toolId, height = 88.dp, trueScale = true, debounceMs = 90L)
        Text(
            "${preset.name} · ${formatSize(preset.size)}" + if (edited) " · edited" else "",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionHeader("Presets")
        library.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in row) {
                    val selected = p.id == preset.id
                    val shown = if (selected) preset else saved[p.id] ?: p
                    PresetCell(
                        preset = shown,
                        toolId = toolId,
                        selected = selected,
                        edited = if (selected) edited else saved[p.id] != null,
                        onClick = {
                            if (!selected) {
                                store.select(controller, toolId, p.id)
                                storeVersion++
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        SectionHeader("Settings: ${preset.name}")
        BrushSettings(toolId, preset, onEdit)
    }

    if (confirmReset) {
        BwDialog(
            title = "Reset \"${preset.name}\"?",
            onDismiss = { confirmReset = false },
            confirmText = "Reset",
            onConfirm = {
                store.resetToDefault(controller, toolId)
                storeVersion++
                confirmReset = false
            },
        ) {
            Text("All settings of this brush go back to their defaults.")
        }
    }
}

@Composable
private fun PresetCell(
    preset: BrushPreset,
    toolId: ToolId,
    selected: Boolean,
    edited: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) BrushworkColors.AccentDim.copy(alpha = 0.45f) else BrushworkColors.ChromeHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) BrushworkColors.Accent else BrushworkColors.ChromeBorder, shape)
            .clickable(role = Role.RadioButton, onClickLabel = "Use ${preset.name}", onClick = onClick)
            .padding(6.dp),
    ) {
        BrushStrokePreview(preset, toolId, height = 40.dp, debounceMs = if (selected) 150L else 0L)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (edited) {
                Spacer(Modifier.width(4.dp))
                // Small dot: this preset has user edits.
                Spacer(Modifier.size(6.dp).clip(CircleShape).background(BrushworkColors.Accent))
            }
        }
    }
}

/** Compact options strip shown under the top bar while a paint tool is active. */
@Composable
fun BrushToolOptions(tool: BrushTool) {
    val controller = tool.controller
    val preset = controller.presetFor(tool.id) ?: return
    val store = remember(controller) { BrushPresetStore.get(controller.appContext) }
    var open by rememberSaveable { mutableStateOf(false) }
    val kind = StrokeKind.of(tool.id, preset)
    val strengthLike = kind == StrokeKind.SMUDGE || kind == StrokeKind.BLUR

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose brush", modifier = Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = BrushworkColors.OnChrome,
                leadingIconContentColor = BrushworkColors.Accent,
                trailingIconContentColor = BrushworkColors.OnChromeDim,
            ),
            modifier = Modifier.heightIn(min = 40.dp),
        )
        Readout(Icons.Filled.LineWeight, formatSize(preset.size), "Brush size") { open = true }
        if (strengthLike) {
            Readout(Icons.Filled.Opacity, percent(preset.mixing), "Strength") { open = true }
        } else {
            Readout(Icons.Filled.Opacity, percent(preset.opacity), "Opacity") { open = true }
        }
        ToolIconButton(
            icon = Icons.Filled.Gesture,
            contentDescription = if (preset.pressureSize) "Pressure changes size: on" else "Pressure changes size: off",
            onClick = { store.edit(controller, tool.id, persist = true) { it.copy(pressureSize = !it.pressureSize) } },
            selected = preset.pressureSize,
            size = 40.dp,
        )
    }

    if (open) BrushPanel(controller, onDismiss = { open = false })
}

@Composable
private fun Readout(icon: ImageVector, text: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Edit $description", onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = description, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChrome)
    }
}
