package com.brushwork.paint.ui.placement

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Units
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.text.LetterRamp
import com.brushwork.paint.tools.text.LetterScaleAlign
import com.brushwork.paint.tools.text.LetterScaleCurve
import com.brushwork.paint.tools.text.LetterScaleDirection
import com.brushwork.paint.tools.text.LetterScaleScope
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextEditorHost
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/** Shown when the text's script can't be drawn one letter at a time (§3.5a). */
const val LETTER_SCALING_LTR_ONLY = "Letter scaling works with left-to-right scripts"

/** Shown for vertical text: the Align choices don't apply there (§3.5a). */
const val LETTER_SCALING_VERTICAL = "Vertical text: letters stay centered"

/**
 * "Letter scaling" (v1.6 §3.5a): the letters of the text get smaller one by one from the
 * beginning to the end (or bigger), proportionally, with a slider; they stay centred on one
 * horizontal line, sit on the base line or hang from one top line.
 * - "Scale letters" turns it on at 60 % (close to the user's ELTON JOHN example) or off;
 * - "Smallest letter" 5–100 % (100 = off; the value can be typed; the Percent increment steps it);
 * - Direction, Align (Center / Baseline / Top; disabled for vertical text, whose letters stay
 *   centred on the column), Steps (even steps or the same ratio) and Scope (the whole text or
 *   each paragraph).
 *
 * Everything goes through [host] (the Text tool, or a linked story's editor), live, as part of
 * the pending text edit: ✓ commits it in one step, Cancel / ✕ takes it back. The font size stays
 * the size of the largest letter.
 */
@Composable
fun TextLetterScaleSection(host: TextEditorHost) {
    val item = host.item ?: return
    val spec = item.spec
    val ls = spec.letterScale
    // A linked story ramps over the whole story; its scripts decide too.
    val source = if (item.thread.isOn) item.thread.story else item.text
    val supported = remember(source) { LetterRamp.supports(source) }
    val onPath = item.path.isActive
    val vertical = spec.vertical && !onPath
    fun set(t: (LetterScaleSpec) -> LetterScaleSpec) = host.updateSpec { s -> s.copy(letterScale = t(s.letterScale).sanitized()) }

    ToggleRow(
        "Scale letters",
        ls.isOn,
        { on -> set { if (on) it.copy(smallestPercent = LetterScaleSpec.DEFAULT_ON_PERCENT) else it.copy(smallestPercent = 100f) } },
        description = "Each letter a little smaller than the one before it, like a title that fades away",
    )
    LabeledSlider(
        label = "Smallest letter",
        value = ls.smallestPercent,
        // Tenths of a percent are plenty (the stored value stays tidy).
        onValueChange = { v -> set { it.copy(smallestPercent = (v * 10f).roundToInt() / 10f) } },
        valueRange = LetterScaleSpec.MIN_PERCENT..100f,
        valueText = if (ls.isOn) Units.formatNumber(ls.smallestPercent.toDouble(), 0) + " %" else "Off",
        typing = SliderTyping(decimals = 0, suffix = "%"),
        incrementKind = IncrementKind.PERCENT,
    )
    if (source.isNotBlank() && !supported) Note(LETTER_SCALING_LTR_ONLY)
    if (!ls.isOn) return

    Caption("Direction")
    ChoiceChips(LetterScaleDirection.entries.map { it.label }, ls.direction.ordinal, { i -> set { it.copy(direction = LetterScaleDirection.entries[i]) } })
    Caption("Align")
    AlignChips(ls.align, enabled = !vertical) { a -> set { it.copy(align = a) } }
    Note(
        when {
            vertical -> LETTER_SCALING_VERTICAL
            ls.align == LetterScaleAlign.CENTER -> "Letters stay centered on one line."
            ls.align == LetterScaleAlign.BASELINE -> "Letters sit on the base line."
            else -> "Letters hang from one top line."
        }
    )
    Caption("Steps")
    ChoiceChips(LetterScaleCurve.entries.map { it.label }, ls.curve.ordinal, { i -> set { it.copy(curve = LetterScaleCurve.entries[i]) } })
    Caption("Scope")
    ChoiceChips(LetterScaleScope.entries.map { it.label }, ls.scope.ordinal, { i -> set { it.copy(scope = LetterScaleScope.entries[i]) } })
}

/**
 * The accessible label of the Align chip [a] (I10): "Letters: Center", "Letters: Baseline",
 * "Letters: Top". The chips show the design's short words, but the editor also has the text's
 * own alignment buttons ("Center"; "Top" for vertical text), so the chips are told apart.
 */
fun letterAlignLabel(a: LetterScaleAlign): String = "Letters: ${a.label}"

/** Center / Baseline / Top as chips that can be disabled (vertical text). */
@Composable
private fun AlignChips(selected: LetterScaleAlign, enabled: Boolean, onSelect: (LetterScaleAlign) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LetterScaleAlign.entries.forEach { a ->
            FilterChip(
                selected = enabled && a == selected,
                onClick = { onSelect(a) },
                // The visible word stays short; the chip's label is unique (see letterAlignLabel).
                label = { Text(a.label, modifier = Modifier.clearAndSetSemantics {}) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = letterAlignLabel(a) },
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 2.dp))
}

/**
 * The Text tool's small "Letter scaling" sheet, opened by the options strip's "Letters" chip:
 * the same controls as the editor's section, while the text stays draggable on the canvas.
 */
@Composable
fun TextLetterScaleSheet(tool: TextTool) {
    if (tool.item == null) return
    BwSheet(title = "Letter scaling", onDismiss = { tool.lettersSheetOpen = false }) {
        TextLetterScaleSection(tool)
    }
}
