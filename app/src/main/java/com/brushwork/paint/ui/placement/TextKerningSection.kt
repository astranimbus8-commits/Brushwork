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
import androidx.compose.runtime.remember
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

/** The description of the "Font kerning" switch. */
internal const val FONT_KERNING_NOTE = "The font's own spacing of letter pairs such as AV and To"

/**
 * The Kerning row's caption, disabled, where the gaps named sit beside a line break (to move into
 * `LabelsV17.KerningLabels`, frozen).
 */
internal const val KERNING_LINE_REFUSAL = "Kerning works between two letters of a line"

/**
 * The Kerning row's caption, disabled, where the gaps named are in a right-to-left or shaped
 * script, whose letters keep the font's shaping (to move into `LabelsV17.KerningLabels`, frozen).
 */
internal const val KERNING_SCRIPT_REFUSAL = "Kerning works on left-to-right text with separate letters"

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
 *   is at, or every gap inside its selection ([kerningRow]; [selection] is the text field's).
 *   The caption names the letters ("Between “A” and “V”"); a selection whose gaps differ shows
 *   "Mixed" (typing a number sets them all, −/+ move each by 10). Disabled, saying why, on
 *   vertical text, where no gap named can take a kern (beside a line break, in a right-to-left
 *   or shaped script) and where the cursor names no gap; text along a shape is kerned along it.
 *   Only for hosts that edit kerns ([KerningEditor]).
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
        val onPath = item.path.isActive
        val row = remember(text, item.kerns, selection, spec.vertical, onPath) {
            kerningRow(text, item.kerns, selection.start, selection.end, spec.vertical, onPath)
        }
        val active = row.active
        val mixed = active != null && row.common == null
        Row(Modifier.fillMaxWidth().testTag(KERNING_ROW_TAG), verticalAlignment = Alignment.CenterVertically) {
            Text(KerningLabels.KERNING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(KerningLabelWidth))
            // Mixed: the field is empty (typing sets every gap) and −/+ move each gap by a step.
            // One field call either way, so typing that ends "Mixed" keeps the field focused.
            if (mixed) {
                RepeatIconButton(Icons.Filled.Remove, "Decrease ${KerningLabels.KERNING}") { kerning.nudgeKerns(active, -KERNING_STEP) }
            }
            NumberField(
                label = KerningLabels.KERNING,
                value = if (mixed) Double.NaN else (row.common ?: 0).toDouble(),
                onValueChange = { v -> if (active != null) kerning.setKerns(active, v.roundToInt()) },
                modifier = Modifier.width(if (mixed) 72.dp else KerningFieldWidth),
                decimals = 0,
                min = TextKern.MIN_VALUE.toDouble(),
                max = TextKern.MAX_VALUE.toDouble(),
                step = if (mixed) null else KERNING_STEP.toDouble(),
                enabled = active != null,
                adjust = NumberAdjust.NONE,
            )
            if (mixed) {
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
            row.caption,
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(start = KerningLabelWidth, bottom = 4.dp),
        )
    }
    ToggleRow(KerningLabels.FONT_KERNING, spec.fontKerning, { on -> host.updateSpec { it.copy(fontKerning = on) } }, description = FONT_KERNING_NOTE)
}

/**
 * What the Kerning row shows for [text] (with [kerns]) and the text field's selection
 * [selStart]..[selEnd]: [active] are the gaps it edits (null = disabled), [common] their one
 * value (null = "Mixed"), [caption] the line under it.
 */
internal class KerningRowState(val active: List<Int>?, val common: Int?, val caption: String)

/**
 * The Kerning row's state (see [KerningRowState]). The gaps the selection names
 * ([TextKerns.gaps]: between whole letters) are edited where a kern applies to them
 * ([TextKerns.applying]), so no kern is stored where it changes nothing. Disabled, the caption
 * saying why, on vertical text (not along a shape, which is kerned along it) and when no gap
 * named can take a kern ([TextKerns.refusal]: beside a line break, in a right-to-left or shaped
 * script); with no gap named, the caption says how to pick one.
 */
internal fun kerningRow(text: String, kerns: List<TextKern>, selStart: Int, selEnd: Int, vertical: Boolean, onPath: Boolean): KerningRowState {
    val gaps = TextKerns.gaps(selStart, selEnd, text)
    if (vertical && !onPath) return KerningRowState(null, 0, KerningLabels.VERTICAL_REFUSAL)
    if (gaps == null) return KerningRowState(null, 0, KERNING_HINT)
    val active = TextKerns.applying(text, gaps)
    if (active.isEmpty()) {
        val why = if (TextKerns.refusal(text, gaps) == TextKerns.Refusal.SCRIPT) KERNING_SCRIPT_REFUSAL else KERNING_LINE_REFUSAL
        return KerningRowState(null, 0, why)
    }
    return KerningRowState(active, TextKerns.commonValue(kerns, active), gapCaption(text, gaps))
}

/**
 * "Between “A” and “V”": the letter before the first gap of [gaps] and the one after its last,
 * each a whole grapheme cluster (an emoji with its skin tone, a letter with its accent).
 */
internal fun gapCaption(text: String, gaps: Iterable<Int>): String {
    if (text.isEmpty()) return KerningLabels.between("", "")
    // The letter that ends at the first gap, and the one that starts after the last (clamped).
    val i = (gaps.first() + 1).coerceIn(1, text.length)
    val a = text.substring(TextKerns.clusterStart(text, i - 1), TextKerns.clusterEnd(text, TextKerns.clusterStart(text, i - 1)))
    val j = TextKerns.clusterStart(text, (gaps.last() + 1).coerceIn(0, text.length - 1))
    val b = text.substring(j, TextKerns.clusterEnd(text, j))
    return KerningLabels.between(shown(a), shown(b))
}

/** [letter] as the caption shows it (a line break as ↵). */
private fun shown(letter: String): String = when (letter) {
    "\n", "\r", "\r\n" -> "↵"
    else -> letter
}
