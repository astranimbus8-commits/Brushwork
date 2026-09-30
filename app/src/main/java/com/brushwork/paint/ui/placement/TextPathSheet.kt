package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.tools.text.TextPathAlign
import com.brushwork.paint.tools.text.TextPathGeometry
import com.brushwork.paint.tools.text.TextPathMode
import com.brushwork.paint.tools.text.TextPathSide
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.isClosed
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Largest number (px, either sign) a field takes: what the text path engine works with. */
private val LIMIT = TextPathGeometry.MAX_COORD.toDouble()

/** Labels of the direction chips (clockwise first). */
private val DIRECTIONS = listOf("Clockwise", "Counter-clockwise")

/**
 * Settings for text on a path: type (straight / line / circle / square-rectangle / curve), bend or
 * rotate letters, side, direction, alignment, offset, and exact numbers (with sliders) for the
 * path's shape. [dpi] is for unit display; [onChange] gets every edit live.
 *
 * Meant for the body of a sheet that already scrolls vertically (it has no scrolling of its own);
 * the choices wrap onto more lines on a narrow phone. For straight text only the shapes are shown
 * (the caller introduces the feature).
 *
 * Caller contract:
 * - picking a shape sends `spec.copy(type = newType)` with every other field untouched (numbers
 *   out of range, e.g. from a damaged file, are repaired as with every edit); the caller knows the
 *   text's center, width and size and should fit the new shape to the text with
 *   `TextOnPath.defaultFor` whenever the type changes;
 * - switching the direction of a circle or rectangle also turns the text position by 180°, so the
 *   text stays upright: clockwise text reads upright at the top, counter-clockwise at the bottom.
 */
@Composable
fun TextPathControls(spec: TextPathSpec, dpi: Float, onChange: (TextPathSpec) -> Unit) {
    var unit by remember { mutableStateOf(LengthUnit.PX) }
    val dpiD = dpi.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 72.0
    // Callbacks of fields and sliders may run between recompositions: they edit the newest spec.
    val current by rememberUpdatedState(spec)
    val send by rememberUpdatedState(onChange)
    // Every edit leaves usable numbers (a typed 1e39 would otherwise become infinity).
    fun edit(transform: (TextPathSpec) -> TextPathSpec) {
        val next = TextPathGeometry.sanitized(transform(current))
        if (next != current) send(next)
    }

    Column(Modifier.fillMaxWidth()) {
        WrapChips(TextPathType.entries.map { it.label }, spec.type.ordinal, { i -> edit { it.copy(type = TextPathType.entries[i]) } })
        // Straight text: only the shapes (the text editor explains them right above).
        if (!spec.isActive) return@Column
        PathLabel("Letters")
        WrapChips(TextPathMode.entries.map { it.label }, spec.mode.ordinal, { i -> edit { it.copy(mode = TextPathMode.entries[i]) } })

        if (spec.type.isClosed) {
            PathLabel("Side of the shape")
            WrapChips(TextPathSide.entries.map { it.label }, spec.side.ordinal, { i -> edit { it.copy(side = TextPathSide.entries[i]) } })
            PathLabel("Direction")
            WrapChips(DIRECTIONS, if (spec.clockwise) 0 else 1, { i ->
                edit {
                    val cw = i == 0
                    if (cw == it.clockwise) it
                    else it.copy(clockwise = cw, startAngleDeg = TextPathGeometry.normalizeDegrees(it.startAngleDeg + 180f))
                }
            })
            PathHint(if (spec.clockwise) "Reads upright along the top of the shape." else "Reads upright along the bottom of the shape.")
        }

        PathLabel(if (spec.type.isClosed) "Align to the text position" else "Align on the path")
        WrapChips(TextPathAlign.entries.map { it.label }, spec.align.ordinal, { i -> edit { it.copy(align = TextPathAlign.entries[i]) } })

        val length = remember(spec) { TextPathGeometry.pathLength(spec).toDouble() }
        val offsetRange = max(1.0, if (spec.type.isClosed) length / 2.0 else length)
        LengthField(
            label = "Offset",
            px = spec.offset.toDouble(),
            onPxChange = { v -> edit { it.copy(offset = v.toFloat()) } },
            unit = unit,
            dpi = dpiD,
            modifier = Modifier.padding(top = 8.dp),
            step = null,
            minPx = -LIMIT,
            maxPx = LIMIT,
            sliderMinPx = -offsetRange,
            sliderMaxPx = offsetRange,
        )
        val shiftRange = max(50.0, shapeScale(spec, length))
        LengthField(
            label = "Baseline shift",
            px = spec.baselineShift.toDouble(),
            onPxChange = { v -> edit { it.copy(baselineShift = v.toFloat()) } },
            unit = unit,
            dpi = dpiD,
            modifier = Modifier.padding(top = 8.dp),
            step = null,
            minPx = -LIMIT,
            maxPx = LIMIT,
            sliderMinPx = -shiftRange,
            sliderMaxPx = shiftRange,
        )
        PathHint("Offset slides the text along the path; baseline shift moves it away from the path.")

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Shape", Modifier.weight(1f))
            UnitSelector(unit, { unit = it })
        }
        val span = max(1000.0, 2.0 * shapeExtent(spec))
        val fields = ShapeFields(spec.type, unit, dpiD, span)
        when (spec.type) {
            TextPathType.NONE -> {}
            TextPathType.LINE -> {
                fields.Point("Start", spec.x1, spec.y1) { x, y -> edit { it.copy(x1 = x, y1 = y) } }
                fields.Point("End", spec.x2, spec.y2) { x, y -> edit { it.copy(x2 = x, y2 = y) } }
                ReverseButton { edit { it.copy(x1 = it.x2, y1 = it.y2, x2 = it.x1, y2 = it.y1) } }
            }
            TextPathType.CIRCLE -> {
                fields.Point("Center", spec.cx, spec.cy) { x, y -> edit { it.copy(cx = x, cy = y) } }
                fields.Size("Radius", spec.radius) { v -> edit { it.copy(radius = v) } }
                AngleField("Text position", spec.startAngleDeg) { v -> edit { it.copy(startAngleDeg = v) } }
            }
            TextPathType.RECT -> {
                fields.Point("Center", spec.cx, spec.cy) { x, y -> edit { it.copy(cx = x, cy = y) } }
                Row(Modifier.fillMaxWidth()) {
                    fields.Size("Width", spec.width, Modifier.weight(1f), step = false) { v ->
                        edit { s -> s.copy(width = v, height = if (s.keepSquare) v else s.height).clampCorner() }
                    }
                    Spacer(Modifier.width(8.dp))
                    fields.Size("Height", spec.height, Modifier.weight(1f), step = false) { v ->
                        edit { s -> s.copy(height = v, width = if (s.keepSquare) v else s.width).clampCorner() }
                    }
                }
                ToggleRow(
                    "Keep square", spec.keepSquare,
                    { on -> edit { s -> if (on) s.copy(keepSquare = true, height = s.width).clampCorner() else s.copy(keepSquare = false) } },
                    modifier = Modifier.padding(top = 4.dp),
                )
                val maxCorner = TextPathGeometry.maxCornerRadius(spec).toDouble()
                LengthField(
                    label = "Corner radius",
                    px = spec.cornerRadius.toDouble().coerceIn(0.0, maxCorner),
                    onPxChange = { v -> edit { it.copy(cornerRadius = v.toFloat()) } },
                    unit = unit,
                    dpi = dpiD,
                    modifier = Modifier.padding(top = 8.dp),
                    minPx = 0.0,
                    maxPx = maxCorner,
                )
                AngleField("Rotation", spec.rotationDeg) { v -> edit { it.copy(rotationDeg = v) } }
                AngleField("Text position", spec.startAngleDeg) { v -> edit { it.copy(startAngleDeg = v) } }
            }
            TextPathType.CURVE -> {
                fields.Point("Start", spec.x1, spec.y1) { x, y -> edit { it.copy(x1 = x, y1 = y) } }
                fields.Point("Control 1", spec.cx1, spec.cy1) { x, y -> edit { it.copy(cx1 = x, cy1 = y) } }
                fields.Point("Control 2", spec.cx2, spec.cy2) { x, y -> edit { it.copy(cx2 = x, cy2 = y) } }
                fields.Point("End", spec.x2, spec.y2) { x, y -> edit { it.copy(x2 = x, y2 = y) } }
                ReverseButton {
                    edit { it.copy(x1 = it.x2, y1 = it.y2, cx1 = it.cx2, cy1 = it.cy2, cx2 = it.cx1, cy2 = it.cy1, x2 = it.x1, y2 = it.y1) }
                }
            }
        }
    }
}

/** A rectangle's corner radius kept within its size. */
private fun TextPathSpec.clampCorner(): TextPathSpec = copy(cornerRadius = cornerRadius.coerceIn(0f, TextPathGeometry.maxCornerRadius(this)))

/** Rough size of the shape (px): the reach of the distance slider. */
private fun shapeScale(spec: TextPathSpec, length: Double): Double = when (spec.type) {
    TextPathType.CIRCLE -> spec.radius.toDouble()
    TextPathType.RECT -> min(spec.width, spec.height) / 2.0
    else -> length / 4.0
}

/** Largest dimension of the shape (px): position sliders span a few of these. */
private fun shapeExtent(spec: TextPathSpec): Double = when (spec.type) {
    TextPathType.NONE -> 0.0
    TextPathType.LINE -> hypot((spec.x2 - spec.x1).toDouble(), (spec.y2 - spec.y1).toDouble())
    TextPathType.CIRCLE -> 2.0 * spec.radius
    TextPathType.RECT -> max(spec.width, spec.height).toDouble()
    TextPathType.CURVE -> {
        val xs = floatArrayOf(spec.x1, spec.cx1, spec.cx2, spec.x2)
        val ys = floatArrayOf(spec.y1, spec.cy1, spec.cy2, spec.y2)
        max((xs.max() - xs.min()).toDouble(), (ys.max() - ys.min()).toDouble())
    }
}

/**
 * Numeric fields of one shape type. Positions have no natural range (the canvas size isn't known
 * here), so their sliders span a window around the value that stays put while the slider is
 * dragged and moves only when the value leaves it (a handle dragged on the canvas, a typed number).
 */
private class ShapeFields(val type: TextPathType, val unit: LengthUnit, val dpi: Double, val span: Double) {

    @Composable
    fun Point(label: String, x: Float, y: Float, onChange: (Float, Float) -> Unit) {
        val latestX by rememberUpdatedState(x)
        val latestY by rememberUpdatedState(y)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val wx = rememberWindow(type, x.toDouble(), span)
            LengthField(
                label = "$label X", px = x.toDouble(), onPxChange = { onChange(it.toFloat(), latestY) },
                unit = unit, dpi = dpi, modifier = Modifier.weight(1f), step = null,
                minPx = -LIMIT, maxPx = LIMIT,
                sliderMinPx = wx.first, sliderMaxPx = wx.second,
            )
            Spacer(Modifier.width(8.dp))
            val wy = rememberWindow(type, y.toDouble(), span)
            LengthField(
                label = "$label Y", px = y.toDouble(), onPxChange = { onChange(latestX, it.toFloat()) },
                unit = unit, dpi = dpi, modifier = Modifier.weight(1f), step = null,
                minPx = -LIMIT, maxPx = LIMIT,
                sliderMinPx = wy.first, sliderMaxPx = wy.second,
            )
        }
    }

    @Composable
    fun Size(label: String, value: Float, modifier: Modifier = Modifier.padding(top = 8.dp), step: Boolean = true, onChange: (Float) -> Unit) {
        val top = rememberCeiling(type, value.toDouble())
        LengthField(
            label = label, px = value.toDouble(), onPxChange = { onChange(it.toFloat()) },
            unit = unit, dpi = dpi,
            modifier = if (step) modifier else modifier.padding(top = 8.dp),
            step = if (step) unit.defaultStep else null,
            minPx = TextPathGeometry.MIN_EXTENT.toDouble(),
            maxPx = LIMIT,
            sliderMinPx = TextPathGeometry.MIN_EXTENT.toDouble(), sliderMaxPx = top,
        )
    }
}

/** Slider window [value] ± [halfSpan], kept while the value stays inside it. */
@Composable
private fun rememberWindow(key: Any, value: Double, halfSpan: Double): Pair<Double, Double> {
    val w = remember(key) { doubleArrayOf(value - halfSpan, value + halfSpan) }
    if (!(value >= w[0] && value <= w[1])) {
        w[0] = value - halfSpan
        w[1] = value + halfSpan
    }
    return w[0] to w[1]
}

/** Top of a size slider: at least 2000 px, raised (to twice the value) only when the value outgrows it. */
@Composable
private fun rememberCeiling(key: Any, value: Double): Double {
    val c = remember(key) { doubleArrayOf(max(2000.0, 2.0 * value)) }
    if (!(value <= c[0])) c[0] = max(2000.0, 2.0 * value)
    return c[0]
}

@Composable
private fun AngleField(label: String, deg: Float, onChange: (Float) -> Unit) {
    NumberField(
        label = label,
        value = TextPathGeometry.normalizeDegrees(deg).toDouble(),
        onValueChange = { onChange(TextPathGeometry.normalizeDegrees(it.toFloat())) },
        modifier = Modifier.padding(top = 8.dp),
        decimals = 1,
        suffix = "°",
        min = -180.0,
        max = 180.0,
        step = 1.0,
    )
}

/**
 * One-of-several chips that wrap onto more lines instead of scrolling sideways: on a narrow phone
 * every choice stays in view (a sideways-scrolling row would hide "Curve" off the edge).
 */
@Composable
private fun WrapChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
            )
        }
    }
}

@Composable
private fun ReverseButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Reverse direction")
    }
}

@Composable
private fun PathLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = BrushworkColors.OnChromeDim,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun PathHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BrushworkColors.OnChromeDim,
        modifier = Modifier.padding(top = 4.dp),
    )
}
