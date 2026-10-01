package com.brushwork.paint.ui.mask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentHistogram
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.filters.FilterParamControl
import com.brushwork.paint.ui.filters.ParamHost
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The Masks tool's Adjust sheet (v1.5 §4.3a; owned by A5): the adjustment layer's effect (any
 * pointwise filter; Tone shows the luminance histogram of what is below it), its sliders
 * (generated like a filter's) and Amount (the layer's opacity). Half-height and non-modal: the
 * canvas stays usable. Changes show at once with no undo step; ONE step "Edit adjustment" is
 * recorded when the sheet closes or is minimized, when the tool goes, or before any other step.
 */
@Composable
internal fun AdjustmentSheet(tool: MaskTool) {
    val c = tool.controller
    // Layers aren't Compose state: layersVersion is (every preview bumps it).
    val layer = remember(c.layersVersion) { c.activeLayer }
    if (!layer.isAdjustmentLayer) return
    val edit = remember(layer) { tool.adjustmentEdit(layer) }
    val host = LocalSheetHost.current
    if (host != null) {
        LaunchedEffect(host, edit) { snapshotFlow { host.minimized }.collect { if (it) edit.flush() } }
    }
    // Closed (✕, Back, another tool, another layer): the step is recorded.
    DisposableEffect(edit) { onDispose { edit.flush() } }

    BwSheet(title = "Adjust: ${layer.name}", onDismiss = { edit.flush(); tool.adjustOpen = false }) {
        AdjustmentBody(c, layer, edit::preview)
    }
}

@Composable
private fun AdjustmentBody(c: EditorController, layer: Layer, preview: (com.brushwork.paint.masks.AdjustmentSpec?, Float, String) -> Unit) {
    // The layer's fields aren't Compose state; layersVersion is (every preview bumps it).
    if (c.layersVersion < 0) return
    val spec = layer.adjustment ?: return
    val filter = AdjustmentEffects.filterOf(spec)
    EffectPicker(c, layer, filter, preview)
    if (filter == null) {
        Text(
            "This adjustment uses an effect this version doesn't have (${spec.filterId}), so it shows no effect. Pick another effect to use it.",
            style = MaterialTheme.typography.bodyMedium,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        if (filter.id == AdjustmentEffects.DEFAULT_ID) BelowHistogram(c, layer)
        val paramHost = remember(layer, filter) {
            ParamHost(
                valuesOf = {
                    // Read in composition: the controls follow every change.
                    if (c.layersVersion < 0) filter.defaultValues()
                    else layer.adjustment?.takeIf { it.filterId == filter.id }?.let { AdjustmentEffects.valuesOf(it, filter) } ?: filter.defaultValues()
                },
                update = { key, value ->
                    val cur = layer.adjustment?.takeIf { it.filterId == filter.id }?.let { AdjustmentEffects.valuesOf(it, filter) } ?: filter.defaultValues()
                    preview(AdjustmentEffects.spec(filter, cur.copy().set(key, value)), layer.opacity, layer.name)
                },
                resetParam = { key ->
                    val p = filter.params.firstOrNull { it.key == key } ?: return@ParamHost
                    val cur = layer.adjustment?.let { AdjustmentEffects.valuesOf(it, filter) } ?: filter.defaultValues()
                    val def = AdjustmentEffects.defaultValues(filter, c.color).raw(p.key) ?: p.defaultValue()
                    preview(AdjustmentEffects.spec(filter, cur.copy().set(key, def)), layer.opacity, layer.name)
                },
            )
        }
        if (filter.params.isEmpty()) {
            Text("${filter.name} has no settings.", style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 8.dp))
        }
        filter.params.forEach { p -> FilterParamControl(paramHost, p, enabled = true) }
    }
    LabeledSlider(
        label = "Amount",
        value = layer.opacity,
        onValueChange = { preview(layer.adjustment, it.coerceIn(0f, 1f), layer.name) },
        valueRange = 0f..1f,
        valueText = "${(layer.opacity * 100f).toInt()}%",
        typing = SliderTyping.Percent,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** "Effect ▾": every pointwise filter; switching starts from its defaults (the default name follows). */
@Composable
private fun EffectPicker(c: EditorController, layer: Layer, filter: Filter?, preview: (com.brushwork.paint.masks.AdjustmentSpec?, Float, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(filter?.name ?: "Unknown effect", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose the effect")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (f in AdjustmentEffects.filters) {
                    DropdownMenuItem(
                        text = { Text(f.name) },
                        leadingIcon = { if (f.id == filter?.id) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                        onClick = {
                            open = false
                            if (f.id != filter?.id) preview(AdjustmentEffects.defaultSpec(f, c.color), layer.opacity, renamed(c, layer, filter, f))
                        },
                    )
                }
            }
        }
        if (filter != null) {
            IconButton(onClick = { preview(AdjustmentEffects.defaultSpec(filter, c.color), layer.opacity, layer.name) }) {
                Icon(Icons.Filled.RestartAlt, contentDescription = "Reset the effect")
            }
        }
    }
}

/** A default name ("Tone 2") follows a new effect ("Invert Color 1"); a name the user gave stays. */
private fun renamed(c: EditorController, layer: Layer, old: Filter?, new: Filter): String {
    val oldBase = old?.name ?: return layer.name
    if (!Regex("^${Regex.escape(oldBase)} \\d+$").matches(layer.name)) return layer.name
    var n = 1
    while (c.doc.layers.any { it !== layer && it.name == "${new.name} $n" }) n++
    return "${new.name} $n"
}

/** Luminance histogram of what the adjustment works on (Tone), measured when the sheet opens. */
@Composable
private fun BelowHistogram(c: EditorController, layer: Layer) {
    var hist by remember(layer) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(layer) { hist = AdjustmentHistogram.below(c, layer) }
    val h = hist ?: return
    val max = h.maxOrNull()?.takeIf { it > 0 } ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF141518))
            .semantics { contentDescription = "Luminance histogram" },
    ) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val w = size.width / h.size
            val color = Color(0xFF9AA0A6).copy(alpha = 0.8f)
            for (i in h.indices) {
                val v = kotlin.math.sqrt(h[i].toFloat() / max)
                if (v <= 0f) continue
                val x = i * w
                drawLine(color, Offset(x + w / 2f, size.height), Offset(x + w / 2f, size.height * (1f - v)), strokeWidth = w.coerceAtLeast(1f))
            }
        }
    }
}

