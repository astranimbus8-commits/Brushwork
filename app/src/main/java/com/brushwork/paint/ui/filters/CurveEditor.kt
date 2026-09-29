package com.brushwork.paint.ui.filters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

private enum class PressResult { UP, MOVED }

/**
 * Interactive tone-curve editor: tap to add a point, drag points, drag a point off the box or
 * long-press it to delete it. The curve is drawn with the same monotone cubic interpolation the
 * filters use, over the luminance [histogram] of the image (optional).
 */
@Composable
fun CurveEditor(
    points: List<CurvePoint>,
    onChange: (List<CurvePoint>) -> Unit,
    modifier: Modifier = Modifier,
    histogram: IntArray? = null,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
) {
    val current by rememberUpdatedState(CurveEditing.normalized(points))
    val emit by rememberUpdatedState(onChange)
    var selected by remember { mutableIntStateOf(-1) }
    var removing by remember { mutableStateOf(false) }
    val shown = current
    // Same interpolation as the Tone Curve filter, so the drawn curve is exactly what gets applied.
    val samples = remember(shown) { com.brushwork.paint.filters.adjust.AdjustMath.sampleCurve(shown, 161) }
    val heights = remember(histogram) { histogram?.let { CurveEditing.histogramHeights(it) } }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth(0.8f)
                .widthIn(max = 260.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF141518))
                .semantics { contentDescription = "Tone curve editor" }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val pad = PAD.toPx()
                    val hitR = 22.dp.toPx()
                    val deleteMargin = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val w = size.width - 2 * pad
                        val h = size.height - 2 * pad
                        fun nx(o: Offset) = (o.x - pad) / w
                        fun ny(o: Offset) = 1f - (o.y - pad) / h
                        val start = current
                        val hit = CurveEditing.hitTest(start, nx(down.position), ny(down.position), hitR / w, hitR / h)
                        var working = start
                        var index = hit
                        if (hit < 0) {
                            val added = CurveEditing.add(start, nx(down.position), ny(down.position))
                            if (added == null) {
                                selected = -1
                                return@awaitEachGesture
                            }
                            working = added.first
                            index = added.second
                            emit(working)
                        }
                        selected = index
                        val id: PointerId = down.id
                        if (hit >= 0) {
                            // Long-press on an existing interior point deletes it.
                            val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var result = PressResult.UP
                                while (true) {
                                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                    if (!ch.pressed) { ch.consume(); break }
                                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { result = PressResult.MOVED; break }
                                    ch.consume()
                                }
                                result
                            }
                            when (press) {
                                null -> {
                                    if (CurveEditing.canRemove(working, index)) { emit(CurveEditing.remove(working, index)); selected = -1 }
                                    while (true) {
                                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                        ch.consume()
                                        if (!ch.pressed) break
                                    }
                                    return@awaitEachGesture
                                }
                                PressResult.UP -> return@awaitEachGesture
                                PressResult.MOVED -> {}
                            }
                        }
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            ch.consume()
                            val p = ch.position
                            val outside = p.x < -deleteMargin || p.y < -deleteMargin ||
                                p.x > size.width + deleteMargin || p.y > size.height + deleteMargin
                            if (outside && CurveEditing.canRemove(working, index)) {
                                if (!removing) { removing = true; selected = -1; emit(CurveEditing.remove(working, index)) }
                            } else {
                                removing = false
                                selected = index
                                working = CurveEditing.move(working, index, nx(p), ny(p))
                                emit(working)
                            }
                        }
                        removing = false
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val pad = PAD.toPx()
                val l = pad; val t = pad
                val w = size.width - 2 * pad; val h = size.height - 2 * pad
                heights?.let { hs ->
                    val path = Path().apply {
                        moveTo(l, t + h)
                        for (i in hs.indices) lineTo(l + w * i / hs.lastIndex, t + h - hs[i] * h)
                        lineTo(l + w, t + h)
                        close()
                    }
                    drawPath(path, Color.White.copy(alpha = 0.13f))
                }
                val grid = Color.White.copy(alpha = 0.10f)
                for (i in 0..4) {
                    val x = l + w * i / 4f; val y = t + h * i / 4f
                    drawLine(grid, Offset(x, t), Offset(x, t + h), 1f)
                    drawLine(grid, Offset(l, y), Offset(l + w, y), 1f)
                }
                drawLine(Color.White.copy(alpha = 0.22f), Offset(l, t + h), Offset(l + w, t), 1.dp.toPx())
                val curve = Path()
                samples.forEachIndexed { i, v ->
                    val x = l + w * i / samples.lastIndex
                    val y = t + h - v * h
                    if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                }
                drawPath(curve, BrushworkColors.Accent, style = Stroke(width = 2.dp.toPx()))
                val r = 6.dp.toPx()
                shown.forEachIndexed { i, p ->
                    val c = Offset(l + p.x * w, t + h - p.y * h)
                    drawCircle(Color.Black.copy(alpha = 0.6f), r + 1.5.dp.toPx(), c)
                    drawCircle(if (i == selected) BrushworkColors.Accent else Color.White, r, c)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val sel = selected.takeIf { it in shown.indices }
            val info = when {
                removing -> "Release to delete the point"
                sel != null -> "Input ${(shown[sel].x * 255).roundToInt()}  →  Output ${(shown[sel].y * 255).roundToInt()}"
                else -> "Tap to add a point. Long-press or drag it off to delete."
            }
            Text(info, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { if (sel != null) { emit(CurveEditing.remove(shown, sel)); selected = -1 } },
                enabled = enabled && sel != null && CurveEditing.canRemove(shown, sel),
            ) { Icon(Icons.Filled.Delete, contentDescription = "Delete point") }
            if (onReset != null) TextButton(onClick = { selected = -1; onReset() }, enabled = enabled) { Text("Reset") }
        }
    }
}

private val PAD = 12.dp
