package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.text.KerningEditor
import com.brushwork.paint.tools.text.TextEditorHost
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextKerns
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.NumberAdjust
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/** The −/+ step of the Kerning row (1/1000 em). */
internal const val KERNING_STEP = 10

/** The unit beside the Kerning field. */
internal const val KERNING_UNIT = "/1000 em"

/** The Kerning row's caption while the cursor names no gap (at an end of the text, one letter selected). */
internal const val KERNING_HINT = "Put the cursor between two letters, or select letters"

/** The Kerning row's caption on text along a shape (kerning applies to straight text). */
internal const val KERNING_PATH_REFUSAL = "Kerning works on straight text"

/** The description of the "Font kerning" switch. */
internal const val FONT_KERNING_NOTE = "The font's own spacing of letter pairs such as AV and To"

/** Test tag of the Kerning row. */
const val KERNING_ROW_TAG = "textKerningRow"

/** Width of the Kerning row's label (the −/+ buttons are 40 dp, the field between them 72 dp). */
private val KerningLabelWidth = 96.dp

/** The −/+ buttons and the field between them. */
private val KerningFieldWidth = 40.dp + 72.dp + 40.dp

/**
 * v1.7 manual kerning in the text editor dialog (item 17, area D), under "Letter spacing":
 *
 * - "Kerning": −/+ (10 at a time) and a field in 1/1000 em, for the gap the text field's cursor
 *   is at, or every gap inside its selection ([TextKerns.gaps]; [selection] is the text field's).
 *   The caption names the letters ("Between “A” and “V”"); a selection whose gaps differ shows
 *   "Mixed" (typing a number sets them all, −/+ move each by 10). Disabled, saying why, on
 *   vertical text, on text along a shape, and where the cursor names no gap. Only for hosts that
 *   edit kerns ([KerningEditor]).
 * - "Font kerning": the font's own pair kerning (on by default; off sets the letters by their
 *   plain advances, and the text exports as outlines).
 */
@Composable
internal fun TextKerningSection(host: TextEditorHost, selection: TextRange) {
    val item = host.item ?: return
    val spec = item.spec
    val kerning = host as? KerningEditor
    if (kerning != null) {
        val text = item.text
        val gaps = TextKerns.gaps(selection.start, selection.end, text.length)
        val refusal = when {
            spec.vertical -> KerningLabels.VERTICAL_REFUSAL
            item.path.isActive -> KERNING_PATH_REFUSAL
            else -> null
        }
        val active = if (refusal == null) gaps else null
        val common = active?.let { TextKerns.commonValue(item.kerns, it) }
        val mixed = active != null && common == null
        Row(Modifier.fillMaxWidth().testTag(KERNING_ROW_TAG), verticalAlignment = Alignment.CenterVertically) {
            Text(KerningLabels.KERNING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(KerningLabelWidth))
            // Mixed: the field is empty (typing sets every gap) and −/+ move each gap by a step.
            // One field call either way, so typing that ends "Mixed" keeps the field focused.
            if (mixed && active != null) {
                RepeatIconButton(Icons.Filled.Remove, "Decrease ${KerningLabels.KERNING}") { kerning.nudgeKerns(active, -KERNING_STEP) }
            }
            NumberField(
                label = KerningLabels.KERNING,
                value = if (mixed) Double.NaN else (common ?: 0).toDouble(),
                onValueChange = { v -> if (active != null) kerning.setKerns(active, v.roundToInt()) },
                modifier = Modifier.width(if (mixed) 72.dp else KerningFieldWidth),
                decimals = 0,
                min = TextKern.MIN_VALUE.toDouble(),
                max = TextKern.MAX_VALUE.toDouble(),
                step = if (mixed) null else KERNING_STEP.toDouble(),
                enabled = active != null,
                adjust = NumberAdjust.NONE,
            )
            if (mixed && active != null) {
                RepeatIconButton(Icons.Filled.Add, "Increase ${KerningLabels.KERNING}") { kerning.nudgeKerns(active, KERNING_STEP) }
            }
            Text(
                if (mixed) PointLabels.MIXED else KERNING_UNIT,
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            refusal ?: gaps?.let { gapCaption(text, it) } ?: KERNING_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(start = KerningLabelWidth, bottom = 4.dp),
        )
    }
    ToggleRow(KerningLabels.FONT_KERNING, spec.fontKerning, { on -> host.updateSpec { it.copy(fontKerning = on) } }, description = FONT_KERNING_NOTE)
}

/** "Between “A” and “V”": the letters before the first gap of [gaps] and after its last. */
internal fun gapCaption(text: String, gaps: IntRange): String {
    val a = text.codePointBefore((gaps.first + 1).coerceIn(1, text.length))
    val b = text.codePointAt((gaps.last + 1).coerceIn(0, text.length - 1))
    return KerningLabels.between(shown(a), shown(b))
}

/** [cp] as the caption shows it (a line break as ↵). */
private fun shown(cp: Int): String = when (cp) {
    '\n'.code, '\r'.code -> "↵"
    else -> String(Character.toChars(cp))
}
