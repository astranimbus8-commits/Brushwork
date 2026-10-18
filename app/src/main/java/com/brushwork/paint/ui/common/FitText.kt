package com.brushwork.paint.ui.common

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * v1.9 Gate 2 (I27, design 3.4): a strip, bar, pill or field text that stays inside its box at a
 * large font scale. It is [Text] at [fontSize] whenever that fits (so a text that fits at font
 * scale 1 does not move), else the largest size that fits its box (wide and tall, every line
 * whole, none cut with an ellipsis), stepped down by 0.25 sp to [fitFloor]: [FIT_FLOOR_DP]
 * (11 sp as laid out at font scale 1), or the text's own size at font scale 1 when that is
 * smaller. Its line height (given, or the style's) and its letter spacing (the style's, 0.5 sp in
 * Material's body style) are kept as the same share of the size, so the lines and the gaps shrink
 * with the text and a fixed cell still holds them. Should even the floor not fit, [overflow]
 * applies as it did before.
 *
 * The strips' cells keep their declared widths (I18), so at 1.3 and 2.0 a label shrinks or wraps
 * in its cell instead of being cut (I27, `StripFontScaleTest`).
 */
@Composable
internal fun FitText(
    text: String,
    fontSize: TextUnit = LocalTextStyle.current.fontSize,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    softWrap: Boolean = maxLines > 1,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
) {
    val line = if (lineHeight.isSpecified) lineHeight else style.lineHeight
    val sized = fontSize.isSp && fontSize.value > 0f
    val shared = if (line.isSp && sized) (line.value / fontSize.value).em else line
    val spacing = style.letterSpacing
    val sharedSpacing = if (spacing.isSp && sized) (spacing.value / fontSize.value).em else spacing
    val floor = fitFloor(fontSize)
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = if (sized) TextUnit.Unspecified else fontSize,
        autoSize = if (sized) FitSize(floor, fontSize) else null,
        fontWeight = fontWeight,
        letterSpacing = sharedSpacing,
        textAlign = textAlign ?: TextAlign.Unspecified,
        lineHeight = shared,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        style = style,
    )
}

/**
 * The smallest size [FitText] steps [fontSize] down to, in sp at the current font scale:
 * [FIT_FLOOR_DP], or what [fontSize] measures at font scale 1 when that is smaller; never above
 * [fontSize] (a font scale below 1).
 */
@Composable
internal fun fitFloor(fontSize: TextUnit): TextUnit {
    if (!fontSize.isSp) return fontSize
    val floorSp = with(LocalDensity.current) { minOf(fontSize.value, FIT_FLOOR_DP).dp.toSp() }
    return if (floorSp.value < fontSize.value) floorSp else fontSize
}

/** I27: a strip text shrinks to 11 sp as laid out at font scale 1 (11 dp), no smaller. */
internal const val FIT_FLOOR_DP = 11f

/** [FitText]'s step, in sp. */
private const val FIT_STEP_SP = 0.25f

/**
 * [FitText]'s sizing: [max] when the text fits there, else the largest of [max] less a whole
 * number of 0.25 sp steps that fits (a binary search, as `TextAutoSize.StepBased` does), else
 * [min]. Unlike `StepBased`, which asks an ellipsized text only whether a line was cut, a text
 * fits here only when it is neither too wide, too tall, past its max lines nor ellipsized, so a
 * one-line value under a label (`StripValueButton`) shrinks to the height it is left.
 */
private data class FitSize(val min: TextUnit, val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        if (!overflows(performLayout(constraints, text, max))) return max
        val steps = ((max.value - min.value) / FIT_STEP_SP).toInt()
        var lo = 1
        var hi = steps
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (overflows(performLayout(constraints, text, size(mid)))) lo = mid + 1 else { best = mid; hi = mid - 1 }
        }
        return if (best < 0) min else size(best)
    }

    private fun size(step: Int): TextUnit = (max.value - step * FIT_STEP_SP).sp

    private fun overflows(r: TextLayoutResult): Boolean =
        r.didOverflowWidth || r.didOverflowHeight || (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1))
}
