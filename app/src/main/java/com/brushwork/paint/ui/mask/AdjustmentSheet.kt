package com.brushwork.paint.ui.mask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentHistogram
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.filters.FilterParamControl
import com.brushwork.paint.ui.filters.LuminanceHistogram
import com.brushwork.paint.ui.filters.ParamHost
import com.brushwork.paint.ui.filters.showsHistogram
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The Masks tool's Adjust sheet (v1.5 §4.3a; owned by A5): the adjustment layer's effect (any
 * pointwise filter; Tone shows the luminance histogram of what is below it), its sliders
 * (generated like a filter's) and Amount (the layer's opacity). Half-height and non-modal: the
 * canvas stays usable. Changes show at once with no undo step; ONE step "Edit adjustment" is
 * recorded when the sheet closes or is minimized, when the tool goes, or before any other step.
 * v1.9 (R8): every finished change is its own step "Change <label>", and every control has the
 * reset icon ("Reset <label>", one step) while it differs from the effect's default; "Reset the
 * effect" stays.
 */
@Composable
internal fun AdjustmentSheet(tool: MaskTool) {
    val c = tool.controller
    // Layers aren't Compose state: layersVersion is (a layer switch bumps it).
    val layer = remember(c.layersVersion) { c.activeLayer }
    if (!layer.isAdjustmentLayer) return
    val edit = remember(layer) { tool.adjustmentEdit(layer) }
    val host = LocalSheetHost.current
    if (host != null) {
        LaunchedEffect(host, edit) { snapshotFlow { host.minimized }.collect { if (it) edit.flush() } }
    }
    // Closed (✕, Back, another tool, another layer): the step is recorded.
    DisposableEffect(edit) { onDispose { edit.flush() } }
    // The app leaves the screen (home, app switch, screen off): the step is recorded now, so the
    // autosave that follows sees an edit and saves it (a pending change bumps no edit count, and
    // the process may be killed in the background).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { edit.flush() }

    BwSheet(title = "Adjust: ${layer.name}", onDismiss = { edit.flush(); tool.adjustOpen = false }) {
        AdjustmentBody(c, layer, edit)
    }
}

@Composable
private fun AdjustmentBody(c: EditorController, layer: Layer, edit: AdjustmentEdit) {
    val preview: (com.brushwork.paint.masks.AdjustmentSpec?, Float, String) -> Unit = edit::preview
    // The layer's fields aren't Compose state; the edit's version is (every preview bumps it,
    // v1.6: without recomposing the layer UI), and layersVersion follows undo / redo.
    if (edit.version < 0 || c.layersVersion < 0) return
    val spec = layer.adjustment ?: return
    val filter = AdjustmentEffects.filterOf(spec)
    val enabled = !c.doc.effectiveLocked(layer)
    if (!enabled) {
        Text(
            "\"${layer.name}\" is locked: unlock it in the layers window to change its effect.",
            style = MaterialTheme.typography.bodyMedium,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    // Picking an effect or resetting it is a discrete change: the canvas refines at once (v1.9:
    // one step "Edit adjustment" now, after the step of a change still pending).
    EffectPicker(c, layer, filter, enabled) { s, o, n -> edit.flush(); edit.preview(s, o, n); edit.record(AdjustmentEdit.LABEL) }
    if (filter == null) {
        Text(
            "This adjustment uses an effect this version doesn't have (${spec.filterId}), so it shows no effect. Pick another effect to use it.",
            style = MaterialTheme.typography.bodyMedium,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        if (showsHistogram(filter.id)) BelowHistogram(c, layer)
        val paramHost = remember(layer, filter) { adjustmentParamHost(c, layer, filter, edit) }
        if (filter.params.isEmpty()) {
            Text("${filter.name} has no settings.", style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 8.dp))
        }
        filter.params.forEach { p -> FilterParamControl(paramHost, p, enabled = enabled) }
    }
    LabeledSlider(
        label = PointLabels19.AMOUNT,
        value = layer.opacity,
        onValueChange = { edit.changing(PointLabels19.AMOUNT); preview(layer.adjustment, it.coerceIn(0f, 1f), layer.name) },
        // v1.9 (R8): one step "Change Amount" (the canvas refines to the exact image at once).
        onValueChangeFinished = { edit.finish(PointLabels19.AMOUNT) },
        valueRange = 0f..1f,
        valueText = "${(layer.opacity * 100f).toInt()}%",
        typing = SliderTyping.Percent,
        enabled = enabled,
        modifier = Modifier.padding(top = 4.dp),
        onReset = if (layer.opacity == 1f) null else ({ resetAmount(layer, edit) }),
        resetTag = V19Tags.FILTER_RESET_PREFIX + AMOUNT_KEY,
    )
}

/** The Amount slider's reset tag ([V19Tags.FILTER_RESET_PREFIX] + it); no filter parameter uses the key. */
internal const val AMOUNT_KEY = "layer.amount"

/** v1.9 (R8): Amount back to 100 %: one step "Reset Amount". */
internal fun resetAmount(layer: Layer, edit: AdjustmentEdit) {
    edit.changing(PointLabels19.AMOUNT)
    edit.preview(layer.adjustment, 1f, layer.name)
    edit.record(String.format(FilterLabels19.RESET_ONE, PointLabels19.AMOUNT))
}

/**
 * The controls' host for [layer]'s [filter] in its Adjust sheet (v1.9: built outside composition so
 * a test can drive it). v1.9 (R8): a finished change is one step "Change <label>" ([AdjustmentEdit.finish]),
 * a run of − / + presses one step ([AdjustmentEdit.changing]) and a control's reset one step "Reset
 * <label>"; the reset icon shows while a value differs from the effect's default.
 */
internal fun adjustmentParamHost(c: EditorController, layer: Layer, filter: Filter, edit: AdjustmentEdit): ParamHost {
    // Settings refused because they can't be live (said once per setting while the sheet is up).
    val refused = mutableSetOf<String>()
    fun labelOf(key: String) = filter.params.firstOrNull { it.key == key }?.label ?: key
    fun current() = layer.adjustment?.takeIf { it.filterId == filter.id }?.let { AdjustmentEffects.valuesOf(it, filter) } ?: filter.defaultValues()
    return ParamHost(
        valuesOf = {
            // Read in composition: the controls follow every change.
            if (edit.version < 0 || c.layersVersion < 0) filter.defaultValues() else current()
        },
        update = { key, value ->
            val next = current().copy().set(key, value)
            // A setting that looks at neighbours or the whole picture (Levels' Auto, Black &
            // White's smoothing) would turn the live effect off without a word: refused.
            if (!AdjustmentEffects.isLive(filter, next)) {
                if (refused.add(key)) c.toast(AdjustmentEffects.notLiveMessage(filter, filter.params.firstOrNull { it.key == key }?.label))
                return@ParamHost
            }
            edit.changing(labelOf(key))
            edit.preview(AdjustmentEffects.spec(filter, next), layer.opacity, layer.name)
        },
        resetParam = { key ->
            val p = filter.params.firstOrNull { it.key == key } ?: return@ParamHost
            val cur = layer.adjustment?.let { AdjustmentEffects.valuesOf(it, filter) } ?: filter.defaultValues()
            val def = AdjustmentEffects.defaultValues(filter, c.color).raw(p.key) ?: p.defaultValue()
            edit.changing(p.label)
            edit.preview(AdjustmentEffects.spec(filter, cur.copy().set(key, def)), layer.opacity, layer.name)
            edit.record(String.format(FilterLabels19.RESET_ONE, p.label))
        },
        // A control finished its change: one step now, and the canvas refines to the exact image
        // at once (§3.1a).
        onChangeFinished = { key -> edit.finish(labelOf(key)) },
        // A − / + press let go: the run goes on until another control changes or the sheet flushes.
        onNudgeFinished = { edit.settled() },
        defaultOf = { p -> AdjustmentEffects.defaultValues(filter, c.color).raw(p.key) ?: p.defaultValue() },
    )
}

/** "Effect ▾": every pointwise filter; switching starts from its defaults (the default name follows). */
@Composable
private fun EffectPicker(c: EditorController, layer: Layer, filter: Filter?, enabled: Boolean, preview: (com.brushwork.paint.masks.AdjustmentSpec?, Float, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(filter?.name ?: "Unknown effect", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose the effect")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }) {
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
            IconButton(onClick = { preview(AdjustmentEffects.defaultSpec(filter, c.color), layer.opacity, layer.name) }, enabled = enabled) {
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
    LuminanceHistogram(hist)
}

