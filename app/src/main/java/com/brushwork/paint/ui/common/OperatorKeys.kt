package com.brushwork.paint.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.ui.theme.BrushworkColors

/*
 * v1.7 (item 15's UI, design §3.15; area I): the operator keys the numeric keypad lacks (V17:
 * `KeyboardType.Decimal` has no + − × ÷ ( )), and the live readout of what a typed expression
 * gives. The arithmetic itself is F4's (`core/Expressions`, the parse sites and the
 * `resolveRelative` calls at each field); this file only edits the field's text and reads it.
 *
 * Where the keys sit: under the field, inline, while it is typed in: in the value input dialog,
 * the Step popup, under a [NumberField] (the field grows while focused) and under a
 * [LabeledSlider]'s value row while its value editor is open ([ExpressionKeys]). No popup: a
 * Compose `Popup` polls its anchor every frame. The keys never take focus, so the keyboard stays.
 */

/** One operator key: what TalkBack says, what the key shows, what it types. */
class OperatorKey internal constructor(val label: String, val glyph: String, val inserts: String) {
    /** A binary operator: typed over a fully selected value it APPENDS ("120" becomes "120+"). */
    val appends: Boolean get() = inserts != "(" && inserts != ")"
}

/** The six keys, left to right (§3.15: 40 dp tall × 52 dp, 312 dp in all). */
val OPERATOR_KEYS: List<OperatorKey> = listOf(
    OperatorKey(ExpressionLabels.PLUS, "+", "+"),
    OperatorKey(ExpressionLabels.MINUS, "−", "-"),
    OperatorKey(ExpressionLabels.TIMES, "×", "×"),
    OperatorKey(ExpressionLabels.DIVIDED, "÷", "÷"),
    OperatorKey(ExpressionLabels.OPEN, "(", "("),
    OperatorKey(ExpressionLabels.CLOSE, ")", ")"),
)

/** Width of one key and height of the row (the row is [OPERATOR_KEYS] × [OperatorKeyWidth] = 312 dp). */
val OperatorKeyWidth = 52.dp
val OperatorKeyHeight = 40.dp

/** The text a field shows after [key] is typed into [field]. Pure (tests). */
object OperatorText {
    /**
     * [key] typed into [field]: with the WHOLE value selected (fields open that way) a binary
     * operator appends after it ("120" becomes "120+", so "+30" adds to it); otherwise the key
     * replaces the selection or goes in at the cursor, like a typed character. The cursor ends
     * after the key. Text at [Expressions.MAX_LENGTH] or longer takes no more keys.
     */
    fun insert(field: TextFieldValue, key: OperatorKey): TextFieldValue {
        val text = field.text
        val sel = field.selection
        val all = text.isNotEmpty() && sel.min == 0 && sel.max == text.length && !sel.collapsed
        if (text.length >= Expressions.MAX_LENGTH && (all && key.appends || sel.collapsed)) return field
        if (all && key.appends) {
            val out = text + key.inserts
            return TextFieldValue(out, TextRange(out.length))
        }
        val start = sel.min.coerceIn(0, text.length)
        val end = sel.max.coerceIn(start, text.length)
        val out = text.substring(0, start) + key.inserts + text.substring(end)
        return TextFieldValue(out, TextRange(start + key.inserts.length))
    }
}

/** What the readout under a field says about the text typed into it. */
sealed interface Readout {
    /** The value the text gives, in the field's own unit (what OK / Done applies). */
    data class Value(val value: Double) : Readout

    /** [ExpressionLabels.DIV_ZERO] or [ExpressionLabels.INVALID]: OK is disabled, nothing applies. */
    data class Error(val message: String) : Readout
}

object ExpressionReadout {
    /**
     * The readout of [text] in a field that reads text with [parse] (its own reading: relative
     * text resolved against [current], the v1.6 lenient fallback, clamping: exactly what it would
     * apply) and applies relative text to [current]. Null shows no readout: empty text and a
     * plain number ([Expressions.isPlainNumber]) behave as in v1.6.
     *
     * - Relative text ("*2", "/0") is checked by [Expressions.evaluate] against [current] FIRST:
     *   its error blocks even where the field's lenient fallback would read a number ("/0" is
     *   not 0, the gate 2 review's LabeledSlider bug).
     * - Other text shows the value [parse] gives, and the expression's error only when [parse]
     *   gives nothing (§3.15: "1 000" still reads 1000).
     */
    fun of(text: String, current: Double?, parse: (String) -> Double?): Readout? {
        val t = text.trim()
        if (t.isEmpty() || Expressions.isPlainNumber(t)) return null
        if (Expressions.isRelative(t)) {
            val r = Expressions.evaluate(t, current)
            if (r is Expressions.Result.Error) return Readout.Error(r.message)
        }
        val v = parse(text)?.takeIf { it.isFinite() }
        if (v != null) return Readout.Value(v)
        val message = (Expressions.evaluate(t, current) as? Expressions.Result.Error)?.message ?: ExpressionLabels.INVALID
        return Readout.Error(message)
    }

    /** "= 150 px" for a value ([format] shows the number, [suffix] the unit), or the error's message. */
    fun text(readout: Readout, format: (Double) -> String, suffix: String): String = when (readout) {
        is Readout.Value -> "= " + format(readout.value) + if (suffix.isEmpty()) "" else " $suffix"
        is Readout.Error -> readout.message
    }

    /** Whether [readout] blocks applying (OK disabled, Done refused). */
    fun blocks(readout: Readout?): Boolean = readout is Readout.Error
}

/**
 * The row of six operator keys ([OPERATOR_KEYS]); [onKey] gets the key tapped. The keys never
 * take focus (the field keeps the keyboard). At most 312 dp wide; in a narrower place the six
 * keys share its width down to [OperatorKeyMinWidth] each, and below that the row scrolls
 * sideways (a narrow field). A plain layout (no subcomposition), so a parent may ask its
 * intrinsic size (a dropdown menu does).
 */
@Composable
fun OperatorKeys(onKey: (OperatorKey) -> Unit, modifier: Modifier = Modifier) {
    // The width the row is shown in, taken just outside the scroll (which measures its content
    // unbounded) and read by the key layout in the same measure pass.
    val viewport = remember { IntArray(1) }
    Layout(
        content = { for (key in OPERATOR_KEYS) OperatorKeyButton(key, onKey) },
        modifier = modifier
            .testTag(V17Tags.OPERATOR_KEYS)
            .widthIn(max = OperatorKeyWidth * OPERATOR_KEYS.size)
            .fillMaxWidth()
            .height(OperatorKeyHeight)
            .layout { measurable, constraints ->
                viewport[0] = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { p.placeRelative(0, 0) }
            }
            .horizontalScroll(rememberScrollState()),
    ) { measurables, constraints ->
        val full = OperatorKeyWidth.roundToPx()
        val keyWidth = if (viewport[0] == Constraints.Infinity) full else (viewport[0] / measurables.size.coerceAtLeast(1)).coerceIn(OperatorKeyMinWidth.roundToPx(), full)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else OperatorKeyHeight.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(keyWidth, height)) }
        layout(keyWidth * placeables.size, height) {
            placeables.forEachIndexed { i, p -> p.placeRelative(i * keyWidth, 0) }
        }
    }
}

@Composable
private fun OperatorKeyButton(key: OperatorKey, onKey: (OperatorKey) -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrushworkColors.ChromeBorder)
            .clickable(role = Role.Button) { onKey(key) }
            .semantics { contentDescription = key.label },
        contentAlignment = Alignment.Center,
    ) {
        // The glyph is what the key shows; TalkBack reads the label ("Divided by").
        Text(
            key.glyph,
            color = BrushworkColors.OnChrome,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

val OperatorKeyMinWidth = 40.dp

/** The readout line: "= 150 px" (dim) or the error (in the danger colour). */
@Composable
fun ReadoutText(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) BrushworkColors.Danger else BrushworkColors.OnChromeDim,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Under a field while it is typed in ([NumberField], a [LabeledSlider]'s value editor): the live
 * [readout] ("= 150 px", or the error in the danger colour; null shows none, as for a plain
 * number), then the operator keys. Inline, in the field's own window: no popup (a Compose
 * `Popup` polls its anchor every frame, and a field is often typed in).
 */
@Composable
fun ExpressionKeys(readout: String?, isError: Boolean, onKey: (OperatorKey) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 2.dp)) {
        if (readout != null) ReadoutText(readout, isError, Modifier.padding(start = 4.dp, bottom = 2.dp))
        OperatorKeys(onKey)
    }
}
