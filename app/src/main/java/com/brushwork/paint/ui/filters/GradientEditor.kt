package com.brushwork.paint.ui.filters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Horizontal brush for [stops] (sorted); a single stop is drawn as a solid color. */
internal fun gradientBrush(stops: List<GradientStop>): Brush {
    val s = GradientEditing.sorted(stops)
    return when (s.size) {
        0 -> Brush.horizontalGradient(listOf(Color.Black, Color.White))
        1 -> Brush.horizontalGradient(listOf(Color(s[0].color), Color(s[0].color)))
        else -> Brush.horizontalGradient(*s.map { it.position to Color(it.color) }.toTypedArray())
    }
}

/**
 * Gradient editor: tap the bar to add a stop, drag stops (drag one far off the bar to delete it),
 * tap a stop to change its color, plus presets and reverse.
 */
@Composable
fun GradientEditor(
    stops: List<GradientStop>,
    onChange: (List<GradientStop>) -> Unit,
    defaultStops: List<GradientStop>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sorted = remember(stops) { GradientEditing.sorted(stops) }
    val current by rememberUpdatedState(sorted)
    val emit by rememberUpdatedState(onChange)
    var selected by remember { mutableIntStateOf(-1) }
    var pickerFor by remember { mutableIntStateOf(-1) }
    var removing by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT + LANE_HEIGHT)
                .semantics { contentDescription = "Gradient editor" }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val pad = SIDE_PAD.toPx()
                    val deleteDistance = 44.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val span = (size.width - 2 * pad).coerceAtLeast(1f)
                        fun pos(x: Float) = ((x - pad) / span).coerceIn(0f, 1f)
                        val start = current
                        val hit = GradientEditing.hitTest(start, pos(down.position.x), 18.dp.toPx() / span)
                        var working = start
                        var index = hit
                        if (hit < 0) {
                            val (list, i) = GradientEditing.add(start, pos(down.position.x))
                            working = list; index = i
                            emit(working)
                        }
                        selected = index
                        var moved = false
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            ch.consume()
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (!moved) continue
                            val y = ch.position.y
                            val off = y < -deleteDistance || y > size.height + deleteDistance
                            if (off && GradientEditing.canRemove(working, index)) {
                                if (!removing) { removing = true; selected = -1; emit(GradientEditing.remove(working, index)) }
                            } else {
                                removing = false
                                val (list, i) = GradientEditing.move(working, index, pos(ch.position.x))
                                working = list; index = i; selected = i
                                emit(working)
                            }
                        }
                        if (removing) removing = false
                        else if (!moved && hit >= 0) pickerFor = hit
                    }
                },
        ) {
            Box(
                Modifier
                    .padding(horizontal = SIDE_PAD)
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .clip(RoundedCornerShape(6.dp))
                    .checkerboard()
                    .background(gradientBrush(sorted)),
            )
            Canvas(Modifier.fillMaxSize()) {
                val pad = SIDE_PAD.toPx()
                val span = size.width - 2 * pad
                val top = BAR_HEIGHT.toPx() + 2.dp.toPx()
                val sw = 18.dp.toPx()
                sorted.forEachIndexed { i, s ->
                    val x = pad + s.position * span
                    val active = i == selected
                    val tri = Path().apply {
                        moveTo(x, top); lineTo(x - 6.dp.toPx(), top + 7.dp.toPx()); lineTo(x + 6.dp.toPx(), top + 7.dp.toPx()); close()
                    }
                    drawPath(tri, if (active) BrushworkColors.Accent else Color.White)
                    val boxTop = top + 7.dp.toPx()
                    drawRoundRect(Color.White, Offset(x - sw / 2, boxTop), Size(sw, sw), CornerRadius(4.dp.toPx()))
                    drawRoundRect(Color(s.color), Offset(x - sw / 2 + 2.dp.toPx(), boxTop + 2.dp.toPx()), Size(sw - 4.dp.toPx(), sw - 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                    if (active) drawRoundRect(BrushworkColors.Accent, Offset(x - sw / 2, boxTop), Size(sw, sw), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val sel = selected.takeIf { it in sorted.indices }
            if (sel != null) {
                ColorSwatch(sorted[sel].color, size = 32.dp, onClick = { if (enabled) pickerFor = sel })
                Spacer(Modifier.width(10.dp))
                Text("Stop at ${(sorted[sel].position * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { emit(GradientEditing.remove(sorted, sel)); selected = -1 },
                    enabled = enabled && GradientEditing.canRemove(sorted, sel),
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete color stop") }
            } else {
                Text(
                    if (removing) "Release to delete the stop" else "Tap the bar to add a stop, tap a stop to recolor it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = { emit(GradientEditing.reverse(sorted)); selected = -1 }, enabled = enabled) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Reverse gradient")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf("Default" to defaultStops) + GradientEditing.presets).forEach { (name, preset) ->
                val isCurrent = presetMatches(sorted, preset)
                Column(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled) { emit(GradientEditing.sorted(preset)); selected = -1 }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(64.dp, 22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .checkerboard(4.dp)
                            .background(gradientBrush(preset)),
                    )
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) BrushworkColors.Accent else BrushworkColors.OnChromeDim,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    val picking = pickerFor.takeIf { it in sorted.indices }
    if (picking != null) {
        ColorPickerDialog(
            initial = sorted[picking].color,
            onPick = { c -> emit(GradientEditing.recolor(current, picking, c)) },
            onDismiss = { pickerFor = -1 },
            title = "Stop color",
            showAlpha = true,
        )
    }
}

private fun presetMatches(a: List<GradientStop>, b: List<GradientStop>): Boolean {
    val sb = GradientEditing.sorted(b)
    return a.size == sb.size && a.indices.all { abs(a[it].position - sb[it].position) < 1e-4f && a[it].color == sb[it].color }
}

private val SIDE_PAD = 14.dp
private val BAR_HEIGHT = 34.dp
private val LANE_HEIGHT = 30.dp
