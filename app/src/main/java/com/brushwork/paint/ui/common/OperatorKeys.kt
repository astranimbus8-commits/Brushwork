package com.brushwork.paint.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.brushwork.paint.core.Expressions
import com.brushwork.paint.ui.theme.BrushworkColors

/*
 * v1.7 (item 15's UI, design §3.15; area I): the operator keys the numeric keypad lacks (V17:
 * `KeyboardType.Decimal` has no + − × ÷ ( )), and the live readout of what a typed expression
 * gives. The arithmetic itself is F4's (`core/Expressions`, the parse sites and the
 * `resolveRelative` calls at each field); this file only edits the field's text and reads it.
 *
 * Where the keys sit: inline under the field in the value input dialog and the Step popup (both
 * dialogs with room), and in a non-focusable popup next to a [NumberField] or a [LabeledSlider]'s
 * value editor while it is being typed in (those live in fixed-height bars and sheets, where an
 * inline row would push or clip the layout). The popup never takes focus, so the keyboard stays.
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
 * keys share its width.
 */
@Composable
fun OperatorKeys(onKey: (OperatorKey) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(V17Tags.OPERATOR_KEYS)
            .widthIn(max = OperatorKeyWidth * OPERATOR_KEYS.size)
            .fillMaxWidth()
            .height(OperatorKeyHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in OPERATOR_KEYS) {
            Box(
                Modifier
                    .weight(1f)
                    .height(OperatorKeyHeight)
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
    }
}

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
 * The readout and the keys in a popup next to the composable that calls this (its anchor): below
 * it when that fits above the keyboard, else above it; never focusable, so the field keeps focus
 * and the keyboard. [readout] null shows only the keys.
 */
@Composable
fun OperatorKeysPopup(readout: String?, isError: Boolean, onKey: (OperatorKey) -> Unit) {
    val density = LocalDensity.current
    val provider = remember(density) {
        with(density) { NextToAnchor(gapPx = 4.dp.roundToPx(), marginPx = 8.dp.roundToPx()) }
    }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = BrushworkColors.ChromeHigh,
            contentColor = BrushworkColors.OnChrome,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                if (readout != null) ReadoutText(readout, isError, Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp))
                OperatorKeys(onKey)
            }
        }
    }
}

/** Centred under the anchor when there is room above the keyboard (the window's visible size), else above it. */
internal class NextToAnchor(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(
            OperatorPopupMath.x(anchorBounds.left, anchorBounds.right, popupContentSize.width, windowSize.width, marginPx),
            OperatorPopupMath.y(anchorBounds.top, anchorBounds.bottom, popupContentSize.height, windowSize.height, gapPx),
        )
}

/** The popup's placement (pure, tests). */
internal object OperatorPopupMath {
    /** Centred on the anchor, kept [margin] inside the window when it fits. */
    fun x(left: Int, right: Int, width: Int, window: Int, margin: Int): Int {
        val centred = (left + right) / 2 - width / 2
        val hi = window - width - margin
        return if (hi < margin) ((window - width) / 2).coerceAtLeast(0) else centred.coerceIn(margin, hi)
    }

    /** Below the anchor when it fits in the window, else above it (never above the window's top). */
    fun y(top: Int, bottom: Int, height: Int, window: Int, gap: Int): Int {
        val below = bottom + gap
        return if (below + height <= window) below else (top - gap - height).coerceAtLeast(0)
    }
}
