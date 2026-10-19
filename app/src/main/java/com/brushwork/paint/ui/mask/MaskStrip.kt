package com.brushwork.paint.ui.mask

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.common.FitText
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.placement.StripMenu
import com.brushwork.paint.ui.placement.StripMenuButton
import com.brushwork.paint.ui.placement.StripTextButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Masks tool's strip (v1.8, design §3.9 "Masks"; area G). Row 1: "+ Linear", "+ Radial",
 * "+ Brush" (each arms a part kind), "Invert", "Adjust…" (AUTO: the More row's front when row 1
 * is full) and, while a brush part is selected, "Done". The More row: "Choose what the mask is
 * for" (an opaque dropdown), "Brush" / "Erase" (while brush parts are drawn or one is selected),
 * "Add" / "Subtract" / "Intersect", the mask overlay toggle and "Components (N)". The Adjust and
 * components sheets, the filter browser and the replace question are its overlays ([MaskSheets]).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun maskStrip(tool: MaskTool, c: EditorController): StripSpec {
    val infoState = rememberMaskStripInfo(tool)
    val info by infoState
    val M = MaskStripItems
    val brushParts = tool.armed == MaskTool.Kind.BRUSH || info.brushSelected
    val mode = info.selectedMode ?: tool.newMode
    val items = buildList {
        add(addItem(tool, M.ADD_LINEAR, M.LINEAR, Icons.Filled.Gradient, MaskTool.Kind.LINEAR))
        add(addItem(tool, M.ADD_RADIAL, M.RADIAL, Icons.Filled.RadioButtonUnchecked, MaskTool.Kind.RADIAL))
        add(addItem(tool, M.ADD_BRUSH, M.BRUSH, Icons.Filled.Brush, MaskTool.Kind.BRUSH))
        add(
            StripItem(M.INVERT, M.INVERT_LABEL, M.ICON_DP.dp) {
                val on = info.selectedInvert
                StripTextButton(
                    M.INVERT_LABEL, M.ICON_DP.dp, onClick = { tool.toggleInvertSelected() },
                    enabled = info.hasSelection, checked = on, state = M.state(M.INVERT_LABEL, on), icon = Icons.Filled.SwapVert, stacked = true,
                )
            },
        )
        add(StripItem(M.ADJUST, M.ADJUST_LABEL, M.ADJUST_DP.dp, overflowRank = 1) { AdjustButton(tool, enabled = info.activeIsAdjustment) })
        if (info.brushSelected) {
            add(StripItem(M.DONE, M.DONE_LABEL, M.ICON_DP.dp) { StripTextButton(M.DONE_LABEL, M.ICON_DP.dp, onClick = { tool.select(null) }) })
        }
        add(StripItem(M.TARGET, M.TARGET_LABEL, M.TARGET_DP.dp, row = StripRow.MORE) { TargetMenu(tool, info) })
        if (brushParts) {
            add(
                StripItem(M.PAINT, M.BRUSH, M.PAINT_DP.dp, row = StripRow.MORE) {
                    val on = !tool.brushErase
                    StripTextButton(M.BRUSH, M.PAINT_DP.dp, onClick = { tool.brushErase = false }, checked = on, state = M.state(M.BRUSH, on))
                },
            )
            // Erasing takes paint away from a brush part: one has to be selected (its B pin).
            add(
                StripItem(M.ERASE, M.ERASE_LABEL, M.ERASE_DP.dp, row = StripRow.MORE) {
                    val on = tool.brushErase && info.brushSelected
                    StripTextButton(
                        M.ERASE_LABEL, M.ERASE_DP.dp, onClick = { tool.brushErase = true },
                        enabled = info.brushSelected, checked = on, state = M.state(M.ERASE_LABEL, on), icon = Icons.Filled.AutoFixOff,
                    )
                },
            )
        }
        for (m in MaskMode.entries) {
            val label = modeLabel(m)
            add(
                StripItem(M.modeKey(m), label, M.modeDp(m).dp, row = StripRow.MORE) {
                    val on = mode == m
                    StripTextButton(label, M.modeDp(m).dp, onClick = { tool.setMode(m) }, checked = on, state = M.state(label, on))
                },
            )
        }
        add(
            StripItem(M.OVERLAY, M.overlayLabel(tool.overlayAlways), M.ICON_DP.dp, row = StripRow.MORE) {
                ToolIconButton(
                    if (tool.overlayAlways) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = M.overlayLabel(tool.overlayAlways),
                    onClick = { tool.toggleOverlay() },
                    selected = tool.overlayAlways,
                    size = M.ICON_DP.dp,
                )
            },
        )
        add(
            StripItem(M.COMPONENTS, M.components(info.count), M.COMPONENTS_DP.dp, row = StripRow.MORE) {
                val strip = LocalStripContext.current
                StripTextButton(M.components(info.count), M.COMPONENTS_DP.dp, onClick = {
                    strip.closeMore()
                    tool.componentsOpen = true
                })
            },
        )
    }
    return StripSpec(items, overlays = { MaskSheets(tool) { infoState.value.activeIsAdjustment } })
}

/**
 * "+ Linear", "+ Radial", "+ Brush" ([kind]): arms a part kind, highlighted while armed. The next
 * drag on the canvas makes the part, so the More row closes (an open row takes that touch, D25).
 */
private fun addItem(tool: MaskTool, key: String, name: String, icon: ImageVector, kind: MaskTool.Kind): StripItem {
    val label = MaskStripItems.add(name)
    return StripItem(key, label, MaskStripItems.ICON_DP.dp) {
        val strip = LocalStripContext.current
        val armed = tool.armed == kind
        StripTextButton(
            label, MaskStripItems.ICON_DP.dp,
            onClick = {
                strip.closeMore()
                tool.arm(kind)
            },
            on = armed, state = MaskStripItems.addState(name, armed), icon = icon, stacked = true,
        )
    }
}

/**
 * "Adjust…": opens the Adjust sheet (an adjustment layer is active); pulses a few times after a
 * new adjustment layer was made (once it is drawn: on the More row, when the row opens).
 */
@Composable
private fun AdjustButton(tool: MaskTool, enabled: Boolean) {
    val pulse = remember { Animatable(0f) }
    val count = tool.adjustPulse
    LaunchedEffect(count) {
        // Once per new adjustment layer (not again when the strip is shown again later).
        if (count == 0 || count == tool.adjustPulseShown) return@LaunchedEffect
        tool.adjustPulseShown = count
        repeat(3) {
            pulse.animateTo(1f, tween(260))
            pulse.animateTo(0f, tween(260))
        }
    }
    val strip = LocalStripContext.current
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(BrushworkColors.Accent.copy(alpha = 0.45f * pulse.value))) {
        StripTextButton(
            MaskStripItems.ADJUST_LABEL, MaskStripItems.ADJUST_DP.dp,
            onClick = {
                strip.closeMore()
                tool.openAdjust()
            },
            enabled = enabled, on = tool.adjustOpen, icon = Icons.Filled.Tune,
        )
    }
}

/**
 * "Choose what the mask is for": the target's name ("Layer 2 mask", "New Tone") and ▾; an opaque
 * dropdown of v1.7's entries ([MaskTargetItems]). A dropdown: the More row stays open.
 */
@Composable
private fun TargetMenu(tool: MaskTool, info: MaskStripInfo) {
    var open by remember { mutableStateOf(false) }
    Box {
        StripMenuButton(MaskStripItems.TARGET_LABEL, MaskStripItems.TARGET_DP.dp, open, onClick = { open = true }) {
            Icon(Icons.Filled.Layers, contentDescription = null, tint = BrushworkColors.OnChrome, modifier = Modifier.size(16.dp))
            Box(Modifier.width(4.dp))
            // v1.9 Gate 2 (I27): shrinks at a large font scale rather than be cut.
            FitText(
                maskTargetLabel(tool, info),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = BrushworkColors.OnChrome,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        StripMenu(open, onDismiss = { open = false }) { MaskTargetItems(tool, info) { open = false } }
    }
}

/** The Masks strip's item keys, widths (measured, rounded up to 4 dp) and texts (v1.7's). */
internal object MaskStripItems {
    const val ADD_LINEAR = "mask.addLinear"
    const val ADD_RADIAL = "mask.addRadial"
    const val ADD_BRUSH = "mask.addBrush"
    const val INVERT = "mask.invert"
    const val ADJUST = "mask.adjust"
    const val DONE = "mask.done"
    const val TARGET = "mask.target"
    const val PAINT = "mask.paint"
    const val ERASE = "mask.erase"
    const val OVERLAY = "mask.overlay"
    const val COMPONENTS = "mask.components"

    fun modeKey(m: MaskMode) = "mask.mode." + m.name.lowercase()

    /** The add buttons, "Invert", "Done" and the overlay toggle (an icon over a short text, or an icon). */
    const val ICON_DP = 40f

    /** "Adjust…" after its icon. */
    const val ADJUST_DP = 72f

    /** The target's name on two lines after its icon, and ▾. */
    const val TARGET_DP = 152f

    /** "Brush". */
    const val PAINT_DP = 52f

    /** "Erase" after its icon. */
    const val ERASE_DP = 64f

    /** "Components (12)" on one line. */
    const val COMPONENTS_DP = 100f

    /** "Add", "Subtract", "Intersect". */
    fun modeDp(m: MaskMode): Float = when (m) {
        MaskMode.ADD -> 40f
        MaskMode.SUBTRACT -> 60f
        MaskMode.INTERSECT -> 64f
    }

    const val LINEAR = "Linear"
    const val RADIAL = "Radial"
    const val BRUSH = "Brush"
    const val ERASE_LABEL = "Erase"
    const val INVERT_LABEL = "Invert"
    const val ADJUST_LABEL = "Adjust…"
    const val DONE_LABEL = "Done"
    const val TARGET_LABEL = "Choose what the mask is for"

    /** "+ Linear". */
    fun add(name: String) = "+ $name"

    /** "Add Linear: armed, drag on the canvas" / "Add Linear". */
    fun addState(name: String, armed: Boolean) = if (armed) "Add $name: armed, drag on the canvas" else "Add $name"

    /** "Invert: on" / "Invert: off". */
    fun state(label: String, on: Boolean) = if (on) "$label: on" else "$label: off"

    fun overlayLabel(always: Boolean) = if (always) "Mask overlay: always on" else "Mask overlay: after edits"

    fun components(count: Int) = "Components ($count)"
}
