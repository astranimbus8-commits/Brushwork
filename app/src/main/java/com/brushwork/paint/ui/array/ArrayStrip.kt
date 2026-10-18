package com.brushwork.paint.ui.array

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.FitText
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Array tool's strip (v1.8, design §3.9 and §4.6; area B): v1.7's scrolling row (V37) as one
 * fixed row 1 of 236 dp, with no More row and no toggle:
 * - [KEY_SETTINGS], 120 dp: the chip that shows the array (visible "Line × 3", spoken [SETTINGS],
 *   I10) and opens the Array sheet; [ArrayLabels.FINISH_SOURCE] in its place while a raster
 *   source is being edited (its visible text is its name, as in v1.7);
 * - [KEY_RENDERING], 112 dp: [ArrayLabels.RENDERING] while [ArrayTool.rendering] (a vector
 *   array's copies on their way, or a cache rendering for over 300 ms); its room is kept
 *   meanwhile, so the chip never moves;
 * - without an array on the active layer, [KEY_HINT], 236 dp: how to make one ([NO_ARRAY_HINT],
 *   up to two lines). It stays on row 1 as an item, not as [StripSpec.hint], which would add the
 *   toggle and a More row for one line of text.
 * Each visible text shrinks to fit its declared width (a long mode and count, a large font).
 * The look's reset and Apply stay in the Array sheet. [StripSpec.overlays] hosts the sheet and the
 * [ArrayLabels.APPLY_TEXT_ASK] confirmation, composed while the strip shows.
 */
@Composable
internal fun arrayStrip(tool: ArrayTool, c: EditorController): StripSpec {
    // Every edit, undo and layer change (the array lives on the layer, which is not observable).
    c.editCount
    c.layersVersion
    val layer = tool.target
    val spec = tool.shownSpec()
    val items = if (layer == null || spec == null) {
        listOf(StripItem(KEY_HINT, NO_ARRAY_HINT, HINT_DP.dp) { NoArrayHint() })
    } else {
        val editing = spec.editingSource
        listOf(
            StripItem(KEY_SETTINGS, if (editing) ArrayLabels.FINISH_SOURCE else SETTINGS, CHIP_DP.dp) {
                if (editing) {
                    StripChip(ArrayLabels.FINISH_SOURCE, description = null, icon = false) { tool.finishSource() }
                } else {
                    val strip = LocalStripContext.current
                    StripChip("${modeWord(spec.mode)} × ${spec.count}", description = SETTINGS, icon = true) {
                        strip.closeMore()
                        tool.sheetOpen = true
                    }
                }
            },
            StripItem(KEY_RENDERING, ArrayLabels.RENDERING, RENDERING_DP.dp, shown = tool.rendering) { RenderingNote() },
        )
    }
    return StripSpec(items, overlays = { ArrayOverlays(tool) })
}

/**
 * The strip's chip: at most [CHIP_DP] wide, its text one line that shrinks to fit. With a
 * [description] the chip is spoken by it, not by its text (I10).
 */
@Composable
private fun StripChip(text: String, description: String?, icon: Boolean, onClick: () -> Unit) {
    val style = MaterialTheme.typography.labelLarge
    AssistChip(
        onClick = onClick,
        label = {
            BasicText(
                text,
                style = style.copy(color = BrushworkColors.OnChrome),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_TEXT_SP.sp, maxFontSize = style.fontSize, stepSize = 0.25.sp),
                modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier,
            )
        },
        leadingIcon = if (icon) {
            { Icon(EditorIcons.tool(ToolId.ARRAY), contentDescription = null, modifier = Modifier.size(18.dp), tint = BrushworkColors.OnChrome) }
        } else {
            null
        },
        colors = AssistChipDefaults.assistChipColors(labelColor = BrushworkColors.OnChrome),
        modifier = Modifier
            .widthIn(max = CHIP_DP.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

/** [ArrayLabels.RENDERING], one line within [RENDERING_DP]. */
@Composable
private fun RenderingNote() {
    val style = MaterialTheme.typography.bodySmall
    BasicText(
        ArrayLabels.RENDERING,
        style = style.copy(color = BrushworkColors.OnChromeDim),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = MIN_TEXT_SP.sp, maxFontSize = style.fontSize, stepSize = 0.25.sp),
        modifier = Modifier.widthIn(max = RENDERING_DP.dp).padding(horizontal = 6.dp),
    )
}

/** [NO_ARRAY_HINT], up to two lines within [HINT_DP]. */
@Composable
private fun NoArrayHint() {
    val style = MaterialTheme.typography.bodySmall
    // v1.9 Gate 2 (I27): [FitText], whose floor is 11 sp as laid out at font scale 1; [MIN_TEXT_SP]
    // grows with the font scale, so at 2.0 the hint was cut.
    FitText(
        NO_ARRAY_HINT,
        fontSize = style.fontSize,
        style = style,
        color = BrushworkColors.OnChromeDim,
        maxLines = 2,
        modifier = Modifier.widthIn(max = HINT_DP.dp).padding(horizontal = 6.dp),
    )
}

/** The Array sheet and the "turn the text into pixels" confirmation of Apply: the strip's overlays. */
@Composable
private fun ArrayOverlays(tool: ArrayTool) {
    val c = tool.controller
    c.editCount
    c.layersVersion
    val layer = tool.target
    val spec = tool.shownSpec()
    if (tool.sheetOpen && layer != null && spec != null) ArraySheet(tool, layer, spec) { tool.closeSheet() }
    if (tool.pendingTextApply != null) {
        BwDialog(
            title = ArrayLabels.APPLY_TEXT_ASK,
            onDismiss = { tool.answerTextApply(false) },
            confirmText = APPLY_CONFIRM,
            onConfirm = { tool.answerTextApply(true) },
        ) {
            Text(APPLY_TEXT_DETAIL, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The visible word of [mode] (the segments' text; their spoken names are [ArrayLabels]). */
internal fun modeWord(mode: ArrayMode): String = when (mode) {
    ArrayMode.LINE -> "Line"
    ArrayMode.CIRCLE -> "Circle"
    ArrayMode.CURVE -> "Curve"
    ArrayMode.TRANSFORM -> "Transform"
}

/** The strip chip that opens the Array sheet (I10: the visible text changes, the name does not). */
internal const val SETTINGS = "Array settings"

/** What the strip says on a layer without an array. */
internal const val NO_ARRAY_HINT = "Select pixels or objects and tap Array, or use Array… in the layer menu"

/** The strip items' keys. */
internal const val KEY_SETTINGS = "array.settings"
internal const val KEY_RENDERING = "array.rendering"
internal const val KEY_HINT = "array.hint"

/** Declared widths (dp, §3.9): the chip and the note make row 1's 236, as the hint does alone. */
internal const val CHIP_DP = 120f
internal const val RENDERING_DP = 112f
internal const val HINT_DP = 236f

/** The smallest size a strip text shrinks to (sp). */
private const val MIN_TEXT_SP = 8f

private const val APPLY_CONFIRM = "Apply"
private const val APPLY_TEXT_DETAIL = "The copies stay as they look, but the text can no longer be edited. Undo brings the live array back."
