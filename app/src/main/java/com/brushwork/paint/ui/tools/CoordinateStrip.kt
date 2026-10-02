package com.brushwork.paint.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.theme.BrushworkColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/*
 * The X / Y coordinate strip under the tool options (v1.5 §4.6, owned by A4): two rows with a
 * value and a slider per axis for whatever the current tool is placing (the transformed
 * selection or objects, a pending shape or text, the selected curve point, a mask component's
 * pin, the clone source; see CoordinateSources.kt). It lives in the top chrome, but the editor
 * leaves its height out of the canvas fit inset, so showing it never moves the canvas (V11).
 *
 * Each row: `X ‹ 1,234 px › ━━●━━`. ‹ › move by 1 px (hold to repeat); tapping the value types it
 * in the tool's unit; the slider is absolute over the canvas and a quarter of it on each side
 * (the value's own place included). Moving the finger more than [FINE_DISTANCE_DP] away from
 * the track vertically turns it into fine mode (a tenth of the movement, "Fine"); with "Snap to
 * objects" on it has detents at 0, the centre and the edge (a haptic tick). The strip folds into
 * one line "X 1,234 · Y 567 px" (remembered). A finished drag, arrow run or typed value ends the
 * tool's edit (`endPositionEdit`: one undo step per edit, per the tool's own model).
 */

/** Height of one axis row (fits the 40 dp touch targets). */
private val ROW_HEIGHT = 40.dp

/** Height of the folded line. */
private val FOLDED_HEIGHT = 28.dp

/** Distance from the track (vertical, dp) beyond which a drag is fine (0.1×). */
private const val FINE_DISTANCE_DP = 48f

/** Pull of a detent (dp of finger travel). */
private const val DETENT_DP = 8f

/** Movement of the fine mode relative to the finger. */
private const val FINE_SCALE = 0.1f

@Composable
fun CoordinateStrip(controller: EditorController) {
    val tool = controller.tools[controller.activeToolId] ?: return
    val source = remember(tool) { coordinateSourceOf(tool) } ?: return
    val target = source.target
    // Rounded to 0.1 px: only a visible change recomposes the strip.
    val pos by remember(target) {
        derivedStateOf { target.position?.takeIf { it.x.isFinite() && it.y.isFinite() }?.let { Vec2(round1(it.x), round1(it.y)) } }
    }
    val p = pos ?: return
    controller.docVersion // (the canvas size can change)
    val doc = controller.doc
    val dpi = doc.dpi.toDouble()
    val unit = source.unit()
    val snap = controller.snapping.enabled
    var folded by remember { mutableStateOf(controller.settings.coordinateStripFolded) }
    fun setFolded(v: Boolean) {
        folded = v
        controller.settings.coordinateStripFolded = v
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(BrushworkColors.Chrome.copy(alpha = 0.82f))
            .blockCanvasTouches()
            .padding(horizontal = 12.dp),
    ) {
        if (folded) {
            FoldedLine(p, target.label, unit, dpi) { setFolded(false) }
        } else {
            AxisRow(
                axis = "X", value = p.x, extent = doc.width.toFloat(), unit = unit, dpi = dpi, snap = snap,
                current = { target.position?.x },
                onBegin = { target.beginPositionEdit() },
                onSet = { v -> target.setPosition(v, null) },
                onEnd = { target.endPositionEdit() },
            ) {
                Text(
                    target.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = BrushworkColors.OnChromeDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 40.dp, max = 64.dp).padding(start = 4.dp),
                )
            }
            AxisRow(
                axis = "Y", value = p.y, extent = doc.height.toFloat(), unit = unit, dpi = dpi, snap = snap,
                current = { target.position?.y },
                onBegin = { target.beginPositionEdit() },
                onSet = { v -> target.setPosition(null, v) },
                onEnd = { target.endPositionEdit() },
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClickLabel = "Fold the X / Y strip", role = Role.Button) { setFolded(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = "Fold the X / Y strip", tint = BrushworkColors.OnChrome)
                }
            }
        }
    }
}

/** [v] rounded to 0.1. */
private fun round1(v: Float): Float = round(v * 10f) / 10f

/** A length in px shown in [unit]: px with thousands separators, others with the unit's decimals. */
internal fun formatCoordinate(px: Float, unit: LengthUnit, dpi: Double): String {
    if (unit != LengthUnit.PX) return Units.format(px.toDouble(), unit, dpi, withSuffix = false)
    val v = round1(px)
    val whole = abs(v - round(v)) < 1e-3f
    val s = if (whole) String.format(Locale.US, "%,d", round(v).toLong()) else String.format(Locale.US, "%,.1f", v)
    return s.replace('-', '−')
}

/** The folded strip: one line with both values; tapping it unfolds the strip. */
@Composable
private fun FoldedLine(p: Vec2, label: String, unit: LengthUnit, dpi: Double, onUnfold: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(FOLDED_HEIGHT)
            .clickable(onClickLabel = "Unfold the X / Y strip", role = Role.Button, onClick = onUnfold),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "X ${formatCoordinate(p.x, unit, dpi)} · Y ${formatCoordinate(p.y, unit, dpi)} ${unit.short}",
            style = MaterialTheme.typography.labelLarge,
            color = BrushworkColors.OnChrome,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = BrushworkColors.OnChromeDim, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
        Icon(Icons.Filled.ExpandMore, contentDescription = "Unfold the X / Y strip", tint = BrushworkColors.OnChrome, modifier = Modifier.size(20.dp))
    }
}

/** The slider's range for a value [v] on an axis of [extent] px: the canvas and a quarter of it on each side, [v] included. */
internal fun axisRange(v: Float, extent: Float): ClosedFloatingPointRange<Float> {
    val lo = min(-0.25f * extent, v)
    val hi = max(1.25f * extent, v)
    return lo..hi
}

/** One axis: name, ‹ value ›, the slider and a [trailing] slot. */
@Composable
private fun AxisRow(
    axis: String,
    value: Float,
    extent: Float,
    unit: LengthUnit,
    dpi: Double,
    snap: Boolean,
    current: () -> Float?,
    onBegin: () -> Unit,
    onSet: (Float) -> Unit,
    onEnd: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    // An arrow held down (its first press begins the edit, the release ends it: one step).
    val arrowHeld = remember { booleanArrayOf(false) }
    fun arrow(delta: Float) {
        if (!arrowHeld[0]) { arrowHeld[0] = true; onBegin() }
        current()?.let { onSet(it + delta) }
    }
    val arrowReleased = { arrowHeld[0] = false; onEnd() }
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(axis, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = BrushworkColors.OnChrome, modifier = Modifier.width(16.dp))
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "$axis minus 1 pixel", onRelease = arrowReleased) { arrow(-1f) }
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Type $axis", role = Role.Button) { typing = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${formatCoordinate(value, unit, dpi)} ${unit.short}",
                style = MaterialTheme.typography.labelLarge,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrushworkColors.ChromeHigh)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "$axis plus 1 pixel", onRelease = arrowReleased) { arrow(1f) }
        AxisTrack(axis, value, extent, snap, onBegin, onSet, onEnd, Modifier.weight(1f).fillMaxHeight())
        trailing()
    }
    if (typing) {
        val r = axisRange(value, extent)
        val span = (r.endInclusive - r.start).coerceAtLeast(1f)
        ValueInputDialog(
            title = "$axis position",
            label = axis,
            initial = unit.fromPx(value.toDouble(), dpi).toFloat(),
            format = { Units.formatNumber(it.toDouble(), unit.decimals) },
            parse = { t -> Units.parse(t)?.toFloat()?.takeIf { it.isFinite() } },
            step = { v, up -> (v + if (up) unit.defaultStep.toFloat() else -unit.defaultStep.toFloat()) },
            toFraction = { v -> ((unit.toPx(v.toDouble(), dpi).toFloat() - r.start) / span) },
            fromFraction = { f -> unit.fromPx((r.start + f * span).toDouble(), dpi).toFloat() },
            rangeText = "Canvas: 0 – ${Units.format(extent.toDouble(), unit, dpi)}",
            suffix = unit.short,
            onApply = { v ->
                onBegin()
                onSet(unit.toPx(v.toDouble(), dpi).toFloat())
                onEnd()
            },
            onDismiss = { typing = false },
        )
    }
}

/**
 * The math of one slider drag (pure; see [CoordinateStrip]): the value jumps to the finger, then
 * follows it (a tenth of the movement in fine mode, re-anchored when the mode changes so the
 * value never jumps), clamped to [range], pulled onto [detents] within [detentPx] of finger
 * travel.
 */
internal class AxisDrag(
    private val range: ClosedFloatingPointRange<Float>,
    /** Usable track width (px). */
    private val widthPx: Float,
    private val fineDistancePx: Float,
    private val detents: List<Float>,
    private val detentPx: Float,
) {
    private val span = (range.endInclusive - range.start).coerceAtLeast(1e-3f)
    private var anchorX = 0f
    private var anchorValue = 0f
    private var raw = 0f

    /** True while the finger is far enough from the track for fine mode. */
    var fine = false
        private set

    /** The detent the value sits on (null when none). */
    var detent: Float? = null
        private set

    /** Value for a finger down at [x] (track px from its left end). */
    fun down(x: Float): Float {
        anchorX = x
        anchorValue = range.start + (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * span
        raw = anchorValue
        fine = false
        return pull(raw, 1f)
    }

    /** Value for the finger at [x] (track px), [dy] px above / below the track's middle. */
    fun move(x: Float, dy: Float): Float {
        val nowFine = abs(dy) > fineDistancePx
        if (nowFine != fine) {
            fine = nowFine
            anchorX = x
            anchorValue = raw
        }
        val scale = if (fine) FINE_SCALE else 1f
        raw = (anchorValue + (x - anchorX) / widthPx.coerceAtLeast(1f) * span * scale).coerceIn(range.start, range.endInclusive)
        return pull(raw, scale)
    }

    private fun pull(v: Float, scale: Float): Float {
        val reach = detentPx / widthPx.coerceAtLeast(1f) * span * scale
        val d = detents.firstOrNull { abs(it - v) <= reach }
        detent = d
        return d ?: v
    }
}

/**
 * The absolute slider of one axis (see [AxisDrag]): a track with a thumb, "Fine" while the
 * finger is far from it. Accessibility services (and tests) can set its value directly.
 */
@Composable
private fun AxisTrack(axis: String, value: Float, extent: Float, snap: Boolean, onBegin: () -> Unit, onSet: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val latestBegin by rememberUpdatedState(onBegin)
    val latestSet by rememberUpdatedState(onSet)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestValue by rememberUpdatedState(value)
    var fine by remember { mutableStateOf(false) }
    // While a finger drags, the range stays the one it started with (the thumb never jumps).
    var dragRange by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val range = dragRange ?: axisRange(value, extent)
    val accent = BrushworkColors.Accent
    val trackColor = BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
    Box(
        modifier
            .semantics {
                contentDescription = "$axis slider"
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range)
                setProgress { v ->
                    latestBegin()
                    latestSet(v)
                    latestEnd()
                    true
                }
            }
            .pointerInput(extent, snap) {
                val pad = 10.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = axisRange(latestValue, extent)
                    dragRange = r
                    val detents = if (snap) listOf(0f, extent / 2f, extent) else emptyList()
                    val drag = AxisDrag(r, size.width - 2 * pad, FINE_DISTANCE_DP.dp.toPx(), detents, DETENT_DP.dp.toPx())
                    var last = drag.down(down.position.x - pad)
                    var lastDetent = drag.detent
                    // The whole drag is one edit, however long the finger rests on the way.
                    latestBegin()
                    latestSet(last)
                    down.consume()
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            val v = drag.move(ch.position.x - pad, ch.position.y - size.height / 2f)
                            fine = drag.fine
                            if (drag.detent != null && drag.detent != lastDetent) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastDetent = drag.detent
                            if (v != last) {
                                last = v
                                latestSet(v)
                            }
                            ch.consume()
                        }
                    } finally {
                        fine = false
                        dragRange = null
                        latestEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val pad = 10.dp.toPx()
            val w = size.width - 2 * pad
            val cy = size.height / 2f
            val span = (range.endInclusive - range.start).coerceAtLeast(1e-3f)
            fun xOf(v: Float) = pad + ((v - range.start) / span).coerceIn(0f, 1f) * w
            // The track, the canvas part brighter, and the thumb.
            drawLine(trackColor, Offset(pad, cy), Offset(pad + w, cy), strokeWidth = 2.dp.toPx())
            drawLine(BrushworkColors.OnChrome.copy(alpha = 0.7f), Offset(xOf(0f), cy), Offset(xOf(extent), cy), strokeWidth = 2.dp.toPx())
            if (snap) for (d in listOf(0f, extent / 2f, extent)) drawCircle(BrushworkColors.OnChromeDim, 2.dp.toPx(), Offset(xOf(d), cy))
            drawCircle(accent, if (fine) 6.dp.toPx() else 8.dp.toPx(), Offset(xOf(value), cy))
        }
        if (fine) {
            Text(
                "Fine",
                fontSize = 10.sp,
                color = BrushworkColors.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp),
            )
        }
    }
    Spacer(Modifier.width(2.dp))
}
