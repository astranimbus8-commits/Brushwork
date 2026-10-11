package com.brushwork.paint.ui.array

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.filters.GradientEditor
import com.brushwork.paint.ui.vector.Hint
import com.brushwork.paint.vector.VStop

/**
 * The Array sheet's [ArrayLabels18.LOOK] tab (v1.8 item 2, design §3.3 a; area B), tagged
 * [V18Tags.ARRAY_LOOK]: how the copies change from the first to the last (the source keeps its
 * look). Pairs of fields side by side: [ArrayLabels18.START_OPACITY] / [ArrayLabels18.END_OPACITY]
 * (0..100 %), [ArrayLabels18.START_SIZE] / [ArrayLabels18.END_SIZE]
 * (`ArraySpec.MIN_SCALE..MAX_SCALE` as %), [ArrayLabels18.START_TURN] / [ArrayLabels18.END_TURN]
 * (±3600°); the [ArrayLabels18.PACE] chips (one per [ArrayEasing]); [ArrayLabels18.TINT_COLORS]
 * (the filters' [GradientEditor], 2 to [ArrayLook.MAX_STOPS] stops: a stop past them is not
 * added; empty shows the default stops) and [ArrayLabels18.TINT_STRENGTH] (0..100 %);
 * [ArrayLabels18.RESET_LOOK] (no look: plain copies).
 *
 * A field previews while it moves and records ONE step [ArrayLabels18.HISTORY_LOOK] when done
 * ([ArrayTool.preview] / [ArrayTool.commitPreview]); a chip and the reset are one step at once.
 * Two rules keep a tint from doing nothing: editing the colors while the strength is 0 sets the
 * strength to 100 % in the same step, and a strength above 0 with no colors takes the default
 * stops. A Pace alone is no look (the copies would not change) and records no step: its chip
 * lights ([ArrayTool.plainPace]) and the next field that changes the look starts from it.
 */
@Composable
internal fun ArrayLookTab(tool: ArrayTool, spec: ArraySpec) {
    // No look: the tint colors chosen before (a strength set to 0 keeps them), else the defaults.
    val look = spec.look ?: ArrayLook.IDENTITY.copy(tint = tool.plainTint)
    Column(Modifier.fillMaxWidth().testTag(V18Tags.ARRAY_LOOK)) {
        Hint(ArrayLabels18.LOOK_CAPTION)
        LookPair(
            tool,
            LookField(ArrayLabels18.START_OPACITY, look.opacityStart * 100.0, "%", 0, 0.0, 100.0) { l, v -> l.copy(opacityStart = (v / 100.0).toFloat()) },
            LookField(ArrayLabels18.END_OPACITY, look.opacityEnd * 100.0, "%", 0, 0.0, 100.0) { l, v -> l.copy(opacityEnd = (v / 100.0).toFloat()) },
        )
        LookPair(
            tool,
            LookField(ArrayLabels18.START_SIZE, look.sizeStart * 100.0, "%", 0, MIN_SIZE, MAX_SIZE) { l, v -> l.copy(sizeStart = (v / 100.0).toFloat()) },
            LookField(ArrayLabels18.END_SIZE, look.sizeEnd * 100.0, "%", 0, MIN_SIZE, MAX_SIZE) { l, v -> l.copy(sizeEnd = (v / 100.0).toFloat()) },
        )
        LookPair(
            tool,
            LookField(ArrayLabels18.START_TURN, look.turnStart.toDouble(), "°", 0, -MAX_TURN, MAX_TURN) { l, v -> l.copy(turnStart = v.toFloat()) },
            LookField(ArrayLabels18.END_TURN, look.turnEnd.toDouble(), "°", 0, -MAX_TURN, MAX_TURN) { l, v -> l.copy(turnEnd = v.toFloat()) },
        )
        SectionHeader(ArrayLabels18.PACE)
        ChoiceChips(
            options = PACES.map { it.second },
            selected = PACES.indexOfFirst { it.first == (spec.look?.easing ?: tool.plainPace) },
            onSelect = { i -> tool.commitLook { it.copy(easing = PACES[i].first) } },
        )
        SectionHeader(ArrayLabels18.TINT_COLORS)
        GradientEditor(
            stops = look.tint.map { GradientStop(it.offset, it.color) }.ifEmpty { DEFAULT_TINT },
            onChange = { stops ->
                if (stops.size in 2..ArrayLook.MAX_STOPS) {
                    tool.previewLook { l -> l.copy(tint = stops.map { VStop(it.position, it.color) }, tintStrength = if (l.tintStrength > 0f) l.tintStrength else 1f) }
                }
            },
            defaultStops = DEFAULT_TINT,
            onChangeFinished = { tool.commitPreview() },
        )
        NumberField(
            label = ArrayLabels18.TINT_STRENGTH,
            value = look.tintStrength * 100.0,
            onValueChange = { v ->
                if (v.isFinite()) {
                    tool.previewLook { l ->
                        val s = (v / 100.0).toFloat().coerceIn(0f, 1f)
                        l.copy(tintStrength = s, tint = if (s > 0f && l.tint.isEmpty()) DEFAULT_TINT.map { VStop(it.position, it.color) } else l.tint)
                    }
                }
            },
            decimals = 0,
            suffix = "%",
            min = 0.0,
            max = 100.0,
            onValueChangeFinished = { tool.commitPreview() },
        )
        OutlinedButton(
            onClick = { tool.resetLook() },
            enabled = spec.look != null || tool.plainPace != ArrayEasing.LINEAR,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) { Text(ArrayLabels18.RESET_LOOK) }
    }
}

/** One field of a [LookPair]: its label, value, unit and range, and how it changes the look. */
private class LookField(
    val label: String,
    val value: Double,
    val suffix: String,
    val decimals: Int,
    val min: Double,
    val max: Double,
    val change: (ArrayLook, Double) -> ArrayLook,
)

/** Two fields side by side (Start and End), each half the width. */
@Composable
private fun LookPair(tool: ArrayTool, start: LookField, end: LookField) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in listOf(start, end)) {
            NumberField(
                label = f.label,
                value = f.value,
                onValueChange = { v -> if (v.isFinite()) tool.previewLook { l -> f.change(l, v) } },
                modifier = Modifier.weight(1f),
                decimals = f.decimals,
                suffix = f.suffix,
                min = f.min,
                max = f.max,
                onValueChangeFinished = { tool.commitPreview() },
            )
        }
    }
}

/** The Pace chips: each easing and its word. */
private val PACES: List<Pair<ArrayEasing, String>> = listOf(
    ArrayEasing.LINEAR to ArrayLabels18.EVEN,
    ArrayEasing.EASE_IN to ArrayLabels18.SPEEDS_UP,
    ArrayEasing.EASE_OUT to ArrayLabels18.SLOWS_DOWN,
    ArrayEasing.EASE_IN_OUT to ArrayLabels18.S_CURVE,
)

/** The tint shown while there is none, and given by a strength set first: warm to cool. */
internal val DEFAULT_TINT: List<GradientStop> = listOf(GradientStop(0f, 0xFFFF6A3D.toInt()), GradientStop(1f, 0xFF3D7BFF.toInt()))

private val MIN_SIZE = ArraySpec.MIN_SCALE * 100.0
private val MAX_SIZE = ArraySpec.MAX_SCALE * 100.0
private const val MAX_TURN = 3600.0
