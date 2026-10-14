package com.brushwork.paint.ui.points

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R1, design 1 R1 (d)): the point thickness row of the Curve, Polyline and Path tools,
 * extracted from v1.6's `ThicknessControl`: "Thinner point" (‹), the value box ("Type the point
 * thickness"), "Thicker point" (›) and the blue "Point thickness slider", [ThicknessRowDefaults.WIDTH]
 * in all. Stateless: the caller owns the values, the in-tool steps and the slider's gestures (the
 * one-point double tap comes in through [sliderModifier]).
 *
 * Layout (R1 (a)): ‹ and › are 40 dp; the value box is at least [ThicknessRowDefaults.VALUE_MIN]
 * wide and its text stays on one line, shrinking down to [ThicknessRowDefaults.MIN_TEXT_SP] before
 * the box grows (up to [ThicknessRowDefaults.VALUE_MAX]); the slider takes the rest, at least
 * [ThicknessRowDefaults.SLIDER_MIN] (128 dp at font scale 1, as in v1.8).
 *
 * [text] is the value box's text ("100 %"), [textDescription] its content description ("Point
 * thickness 100 %"). [onThinner] / [onThicker] run on each tap and repeat while held; [onArrowRelease]
 * when the arrow is let go. [value] is the thumb's position in [range]; [onSlide] and [onSlideEnd]
 * are the slider's (v1.8's `onValueChange` and `onValueChangeFinished`), [steps] its discrete steps.
 * [incrementKind] / [incrementKey] give the value box its long-press Step popup.
 */
@Composable
internal fun ThicknessRow(
    value: Float,
    text: String,
    textDescription: String,
    onThinner: () -> Unit,
    onThicker: () -> Unit,
    onArrowRelease: () -> Unit,
    onType: () -> Unit,
    onSlide: (Float) -> Unit,
    onSlideEnd: () -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..ThicknessRowDefaults.MAX_PERCENT,
    steps: Int = 0,
    color: Color = BrushworkColors.Accent,
    incrementKind: IncrementKind? = IncrementKind.PERCENT,
    incrementKey: String? = null,
    sliderModifier: Modifier = Modifier,
) {
    val style = fittedValueStyle(text)
    Row(modifier.width(ThicknessRowDefaults.WIDTH), verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, ThicknessRowLabels.THINNER, onRelease = onArrowRelease) {
            onThinner()
        }
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = ThicknessRowDefaults.VALUE_MIN, max = ThicknessRowDefaults.VALUE_MAX)
                .clip(RoundedCornerShape(8.dp))
                .stepOnLongPress(incrementKind, incrementKey)
                .clickable(onClickLabel = ThicknessRowLabels.TYPE, role = Role.Button) { onType() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = style,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrushworkColors.ChromeHigh)
                    .padding(horizontal = ThicknessRowDefaults.TEXT_PADDING, vertical = 3.dp)
                    .semantics { contentDescription = textDescription },
            )
        }
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, ThicknessRowLabels.THICKER, onRelease = onArrowRelease) {
            onThicker()
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onSlide,
            onValueChangeFinished = onSlideEnd,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
            modifier = Modifier
                .weight(1f)
                .widthIn(min = ThicknessRowDefaults.SLIDER_MIN)
                .then(sliderModifier)
                .semantics { contentDescription = ThicknessRowLabels.SLIDER },
        )
    }
}

/** The texts of [ThicknessRow] (v1.6's, moved here from `CurveToolOptions.kt` in v1.9: rule L, one literal each). */
internal object ThicknessRowLabels {
    const val THINNER = "Thinner point"
    const val THICKER = "Thicker point"
    const val TYPE = "Type the point thickness"
    const val SLIDER = "Point thickness slider"
}

/** The sizes of [ThicknessRow] (R1 (a)). */
internal object ThicknessRowDefaults {
    /** The thickness range of a point in percent (§4.5). */
    const val MAX_PERCENT = 300f

    /** The whole row: the cell of the strip item (v1.8's `CURVE_THICKNESS_DP`). */
    val WIDTH = 260.dp

    /** The value box's least width (v1.6) and the most it grows to, so the slider keeps [SLIDER_MIN]. */
    val VALUE_MIN = 52.dp
    val VALUE_MAX = WIDTH - 40.dp - 40.dp - 96.dp

    /** The slider's least width (it is 128 dp while the value box is [VALUE_MIN] wide). */
    val SLIDER_MIN = 96.dp

    /** The value text's padding on each side, inside its box. */
    val TEXT_PADDING = 6.dp

    /** The smallest size the value text shrinks to before its box grows. */
    const val MIN_TEXT_SP = 11f
}

/**
 * The value text's style: `bodyMedium` when [text] fits a [ThicknessRowDefaults.VALUE_MIN] box
 * (every value at font scale 1), else the largest size down to [ThicknessRowDefaults.MIN_TEXT_SP]
 * (in half-sp steps) that fits; at that size the box grows.
 */
@Composable
private fun fittedValueStyle(text: String): TextStyle {
    val base = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(text, base, density, measurer) {
        val room = with(density) { (ThicknessRowDefaults.VALUE_MIN - ThicknessRowDefaults.TEXT_PADDING * 2).toPx() }
        ThicknessRowFit.fit(base, room) { s -> widthOf(measurer, text, s) }
    }
}

private fun widthOf(measurer: TextMeasurer, text: String, style: TextStyle): Float =
    measurer.measure(text, style, maxLines = 1, softWrap = false, constraints = Constraints()).size.width.toFloat()

/** The pure part of [fittedValueStyle] (tests). */
internal object ThicknessRowFit {
    /** [base] when [width] of it is at most [room] px, else the largest of 13.5, 13, … 11 sp that fits, else 11 sp. */
    fun fit(base: TextStyle, room: Float, width: (TextStyle) -> Float): TextStyle {
        if (width(base) <= room) return base
        val top = if (base.fontSize.isSp) base.fontSize.value else 14f
        var size = top - 0.5f
        while (size > ThicknessRowDefaults.MIN_TEXT_SP) {
            val s = base.copy(fontSize = size.sp)
            if (width(s) <= room) return s
            size -= 0.5f
        }
        return base.copy(fontSize = ThicknessRowDefaults.MIN_TEXT_SP.sp)
    }
}
