package com.brushwork.paint.ui.points

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/**
 * Stateless: the caller owns the values and the pre-bevel copy (I22). Two lines, every control at
 * least 40 dp: the [PointLabels19.AMOUNT] slider and value; [PointLabels19.FEWER_SEGMENTS], the
 * count, [PointLabels19.MORE_SEGMENTS] and Done. The caller anchors it at the bottom, above the
 * bottom bar; it is not focusable, pan and zoom pass through, and the caller consumes the tap
 * outside that closes it (R18).
 *
 * The slider runs over 1 to 50 % in whole percent ([PointBevel.MIN_AMOUNT]..[PointBevel.MAX_AMOUNT]);
 * the count over 1..[PointBevel.MAX_SEGMENTS]. Tagged [V19Tags.BEVEL_POPUP].
 */
@Composable
fun BevelPopup(
    amount: Float,
    segments: Int,
    onAmount: (Float) -> Unit,
    onSegments: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = (PointBevel.clampAmount(amount) * 100f).roundToInt()
    val count = PointBevel.clampSegments(segments)
    Column(
        modifier
            .testTag(V19Tags.BEVEL_POPUP)
            .clip(RoundedCornerShape(12.dp))
            .background(BrushworkColors.Chrome)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                PointLabels19.AMOUNT,
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChrome,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Slider(
                value = percent.toFloat(),
                onValueChange = { v ->
                    val p = v.roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
                    if (p != percent) onAmount(p / 100f)
                },
                valueRange = MIN_PERCENT.toFloat()..MAX_PERCENT.toFloat(),
                colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
                // Up to SLIDER_WIDTH; narrower when large text leaves less room (the texts stay whole).
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = SLIDER_WIDTH)
                    .heightIn(min = 40.dp)
                    .semantics { contentDescription = PointLabels19.AMOUNT },
            )
            Text(
                "$percent %",
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 44.dp).padding(horizontal = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatIconButton(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                PointLabels19.FEWER_SEGMENTS,
                enabled = count > PointBevel.MIN_SEGMENTS,
            ) { if (count > PointBevel.MIN_SEGMENTS) onSegments(count - 1) }
            Box(Modifier.heightIn(min = 40.dp).widthIn(min = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrushworkColors.OnChrome,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "${PointLabels19.SEGMENTS} $count" },
                )
            }
            RepeatIconButton(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                PointLabels19.MORE_SEGMENTS,
                enabled = count < PointBevel.MAX_SEGMENTS,
            ) { if (count < PointBevel.MAX_SEGMENTS) onSegments(count + 1) }
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 64.dp)) {
                Text(DONE, color = BrushworkColors.Accent)
            }
        }
    }
}

private const val MIN_PERCENT = 1
private const val MAX_PERCENT = 50
private const val DONE = "Done"
private val SLIDER_WIDTH = 160.dp
