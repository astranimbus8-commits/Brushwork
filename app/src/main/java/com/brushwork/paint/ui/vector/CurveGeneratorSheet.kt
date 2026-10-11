package com.brushwork.paint.ui.vector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * v1.8 (item 6, design §3.7; area E): the generator sheet, opened by the generator chip
 * ([CurveGenLabels18.CHANGE]). Its settings, by kind, rebuild the open curve
 * ([CurveTool.changeGenerator]); a slider drag is ONE in-tool step (begin / end numeric edit) and
 * its last value becomes the last-used settings.
 */

/** Spirals: the turn count's slider step. */
internal const val GEN_TURNS_STEP = 0.25f

/** Sine: the wave count's slider step. */
internal const val GEN_WAVES_STEP = 0.5f

/** Spirals: the turn count's range on the slider (typing reaches [CurveGenerator.MAX_TURNS] too). */
internal val GEN_TURNS_RANGE = 0.25f..CurveGenerator.MAX_TURNS
internal val GEN_INNER_RANGE = 0f..0.9f
internal val GEN_GROWTH_RANGE = 1.05f..10f
internal val GEN_WAVES_RANGE = 0.5f..50f

/** Sine: the wave height as a fraction of the wave's length. */
internal val GEN_HEIGHT_RANGE = 0f..1f
internal const val GEN_MAX_PETALS = 24
internal const val GEN_MAX_DIVISOR = 12

/** "Points per turn": 1 to [CurveGenerator.MAX_DENSITY] (0, the default, shows the default's number). */
internal const val GEN_MIN_DENSITY = 1

/** The generator kinds that are spirals ("Turns", "Clockwise"). */
internal val CurveGenKind.isSpiral: Boolean
    get() = this == CurveGenKind.ARCHIMEDEAN || this == CurveGenKind.LOGARITHMIC || this == CurveGenKind.GOLDEN || this == CurveGenKind.FERMAT

/** The settings the generator sheet shows for [kind], in order (by their [CurveGenLabels18] labels). */
internal fun generatorSettings(kind: CurveGenKind): List<String> = buildList {
    if (kind.isSpiral) add(CurveGenLabels18.TURNS)
    if (kind == CurveGenKind.ARCHIMEDEAN) add(CurveGenLabels18.INNER)
    if (kind == CurveGenKind.LOGARITHMIC) add(CurveGenLabels18.GROWTH)
    if (kind == CurveGenKind.SINE) {
        add(CurveGenLabels18.WAVES)
        add(CurveGenLabels18.WAVE_HEIGHT)
    }
    if (kind == CurveGenKind.ROSE) {
        add(CurveGenLabels18.PETALS)
        add(CurveGenLabels18.PETAL_DIVISOR)
    }
    if (kind.isSpiral || kind == CurveGenKind.SINE) add(CurveGenLabels18.CLOCKWISE)
    add(CurveGenLabels18.POINTS_PER_TURN)
}

/** The points per turn [g] is built with on a [kind] tool (its default while 0). */
internal fun densityShown(g: CurveGenerator, kind: CurveKind): Int =
    if (g.density > 0) g.density else if (kind == CurveKind.PATH) CurveGenerators.DEFAULT_PATH_DENSITY else CurveGenerators.DEFAULT_CURVE_DENSITY

/** [v] on a [step] grid within [range]. */
internal fun snapTo(v: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float =
    ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)

/** [g] with its wave height [fraction] × its length, on the side it is now. */
internal fun withWaveHeight(g: CurveGenerator, fraction: Float): CurveGenerator {
    val sign = if (g.amplitude < 0f) -1f else 1f
    return g.copy(amplitude = sign * fraction.coerceIn(GEN_HEIGHT_RANGE.start, GEN_HEIGHT_RANGE.endInclusive) * g.size)
}

/** [g]'s wave height as a fraction of its length. */
internal fun waveHeightOf(g: CurveGenerator): Float = if (g.size > 0f) (abs(g.amplitude) / g.size).coerceIn(0f, 1f) else 0f

/**
 * The generator sheet of [tool]'s open curve, titled by its kind ([CurveGenLabels18]); it closes
 * itself ([onDismiss]) once the curve has no generator (a hand edit, an undo before it, a commit).
 */
@Composable
internal fun CurveGeneratorSheet(tool: CurveTool, onDismiss: () -> Unit) {
    val g = tool.generator
    LaunchedEffect(g == null) { if (g == null) onDismiss() }
    if (g == null) return
    // A slider drag: the first change begins one in-tool step, the release ends it.
    val held = remember(tool) { BooleanArray(1) }
    fun change(next: CurveGenerator) {
        if (!held[0]) {
            held[0] = true
            tool.beginNumericEdit()
        }
        tool.changeGenerator(next)
    }
    val finished: () -> Unit = {
        if (held[0]) {
            held[0] = false
            tool.endNumericEdit()
        }
    }
    DisposableEffect(tool) { onDispose { finished() } }
    BwSheet(title = g.kind.label, onDismiss = {
        finished()
        onDismiss()
    }) {
        val k = g.kind
        if (k.isSpiral) {
            LabeledSlider(
                label = CurveGenLabels18.TURNS,
                value = g.turns,
                onValueChange = { v -> tool.generator?.let { change(it.copy(turns = snapTo(v, GEN_TURNS_STEP, GEN_TURNS_RANGE))) } },
                valueRange = GEN_TURNS_RANGE,
                valueText = Units.formatNumber(g.turns.toDouble(), 2),
                onValueChangeFinished = finished,
                typing = SliderTyping(1f, 2),
            )
        }
        if (k == CurveGenKind.ARCHIMEDEAN) {
            LabeledSlider(
                label = CurveGenLabels18.INNER,
                value = g.inner,
                onValueChange = { v -> tool.generator?.let { change(it.copy(inner = v.coerceIn(GEN_INNER_RANGE.start, GEN_INNER_RANGE.endInclusive))) } },
                valueRange = GEN_INNER_RANGE,
                valueText = "${(g.inner * 100f).roundToInt()} %",
                onValueChangeFinished = finished,
                typing = SliderTyping.Percent,
            )
        }
        if (k == CurveGenKind.LOGARITHMIC) {
            LabeledSlider(
                label = CurveGenLabels18.GROWTH,
                value = g.growth,
                onValueChange = { v -> tool.generator?.let { change(it.copy(growth = v.coerceIn(GEN_GROWTH_RANGE.start, GEN_GROWTH_RANGE.endInclusive))) } },
                valueRange = GEN_GROWTH_RANGE,
                valueText = "× " + Units.formatNumber(g.growth.toDouble(), 2),
                onValueChangeFinished = finished,
                typing = SliderTyping(1f, 2),
            )
        }
        if (k == CurveGenKind.SINE) {
            LabeledSlider(
                label = CurveGenLabels18.WAVES,
                value = g.waves,
                onValueChange = { v -> tool.generator?.let { change(it.copy(waves = snapTo(v, GEN_WAVES_STEP, GEN_WAVES_RANGE))) } },
                valueRange = GEN_WAVES_RANGE,
                valueText = Units.formatNumber(g.waves.toDouble(), 1),
                onValueChangeFinished = finished,
                typing = SliderTyping(1f, 1),
            )
            val height = waveHeightOf(g)
            LabeledSlider(
                label = CurveGenLabels18.WAVE_HEIGHT,
                value = height,
                onValueChange = { v -> tool.generator?.let { change(withWaveHeight(it, v)) } },
                valueRange = GEN_HEIGHT_RANGE,
                valueText = "${(height * 100f).roundToInt()} %",
                onValueChangeFinished = finished,
                typing = SliderTyping.Percent,
            )
        }
        if (k == CurveGenKind.ROSE) {
            LabeledSlider(
                label = CurveGenLabels18.PETALS,
                value = g.petalsN.toFloat(),
                onValueChange = { v -> tool.generator?.let { change(it.copy(petalsN = v.roundToInt().coerceIn(1, GEN_MAX_PETALS))) } },
                valueRange = 1f..GEN_MAX_PETALS.toFloat(),
                steps = GEN_MAX_PETALS - 2,
                valueText = g.petalsN.toString(),
                onValueChangeFinished = finished,
                typing = SliderTyping(1f, 0),
            )
            LabeledSlider(
                label = CurveGenLabels18.PETAL_DIVISOR,
                value = g.petalsD.toFloat(),
                onValueChange = { v -> tool.generator?.let { change(it.copy(petalsD = v.roundToInt().coerceIn(1, GEN_MAX_DIVISOR))) } },
                valueRange = 1f..GEN_MAX_DIVISOR.toFloat(),
                steps = GEN_MAX_DIVISOR - 2,
                valueText = g.petalsD.toString(),
                onValueChangeFinished = finished,
                typing = SliderTyping(1f, 0),
            )
        }
        if (k.isSpiral || k == CurveGenKind.SINE) {
            ToggleRow(
                label = CurveGenLabels18.CLOCKWISE,
                checked = g.clockwise,
                onCheckedChange = { on -> tool.generator?.let { tool.changeGenerator(it.copy(clockwise = on)) } },
            )
        }
        val density = densityShown(g, tool.kind)
        LabeledSlider(
            label = CurveGenLabels18.POINTS_PER_TURN,
            value = density.toFloat(),
            onValueChange = { v -> tool.generator?.let { change(it.copy(density = v.roundToInt().coerceIn(GEN_MIN_DENSITY, CurveGenerator.MAX_DENSITY))) } },
            valueRange = GEN_MIN_DENSITY.toFloat()..CurveGenerator.MAX_DENSITY.toFloat(),
            steps = CurveGenerator.MAX_DENSITY - GEN_MIN_DENSITY - 1,
            valueText = density.toString(),
            onValueChangeFinished = finished,
            typing = SliderTyping(1f, 0),
        )
    }
}
