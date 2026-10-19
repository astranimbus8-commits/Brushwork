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
 * large font scale. With its default [floor], at font scale 1 (or below) it is [Text] with these
 * very arguments, so v1.8's strips, pill, fields and gallery lay out as they did (a caller that
 * passes a lower floor keeps v1.8's own text at font scale 1, as the Array hint does). At a larger
 * font scale it is [Text] at
 * [fontSize] whenever that fits, else the largest size that fits its box (wide and tall, every
 * line whole, none cut with an ellipsis), stepped down by 0.25 sp to [floor]: by default
 * [scale1Size], the text's own size at font scale 1, the rule of A5's
 * `VectorEraseChooser.FittingFontScale` ("at least 11 sp" for an 11 sp text). A text never gets
 * smaller than at font scale 1, so whatever fits at font scale 1 fits at any scale, and a text
 * shortened at font scale 1 by design (an ellipsized name, the X / Y pill's far-off value at its
 * 110 dp cap) is shortened as it is there. While it shrinks, its line height (given, or the
 * style's) and its letter spacing (the style's) are kept as the same share of the size, so the
 * lines and the gaps shrink with the text and a fixed cell still holds them. Should even the floor
 * not fit, [overflow] applies as in [Text].
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
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
    floor: TextUnit = scale1Size(fontSize),
) {
    val sized = fontSize.isSp && fontSize.value > 0f && floor.isSp && floor.value < fontSize.value
    if (!sized) {
        // Font scale 1 or below (or a size not in sp): v1.8's Text, one layout, nothing converted.
        Text(
            text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            style = style,
        )
        return
    }
    val line = if (lineHeight.isSpecified) lineHeight else style.lineHeight
    val shared = if (line.isSp) (line.value / fontSize.value).em else line
    val spacing = style.letterSpacing
    val sharedSpacing = if (spacing.isSp) (spacing.value / fontSize.value).em else spacing
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = TextUnit.Unspecified,
        autoSize = FitSize(floor, fontSize),
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
 * [fontSize] as laid out at font scale 1, in sp at the current font scale, never above
 * [fontSize] (a font scale below 1): [FitText]'s floor, under which no text shrinks.
 */
@Composable
internal fun scale1Size(fontSize: TextUnit): TextUnit {
    if (!fontSize.isSp) return fontSize
    val sp = with(LocalDensity.current) { fontSize.value.dp.toSp() }
    return if (sp.value < fontSize.value) sp else fontSize
}

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
