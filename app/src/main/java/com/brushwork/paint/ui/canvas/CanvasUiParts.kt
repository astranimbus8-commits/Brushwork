package com.brushwork.paint.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sign

internal enum class NoticeKind { INFO, WARNING, ERROR }

/**
 * Layout of a canvas tab inside the half-height sheet: [body] scrolls in the height left under
 * the tabs, [footer] (the tab's Apply button and why it is disabled) stays pinned below it, so
 * the primary action is always reachable.
 */
@Composable
internal fun ColumnScope.TabScaffold(footer: @Composable ColumnScope.() -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 4.dp),
        content = body,
    )
    Column(Modifier.fillMaxWidth(), content = footer)
}

/**
 * Runner for preset buttons that REPLACE field values (percent, dpi presets, crop shortcuts).
 * A focused NumberField doesn't show outside value changes and re-commits its own text when it
 * loses focus, which would overwrite the preset; so this clears focus first and runs the action
 * one frame later, after that commit. Actions must read the current state values themselves, not
 * values captured at composition. Apply buttons only need it next to a field with a min/max (the
 * resolution): fields commit in-range numbers while typing but clamp out-of-range text only on
 * focus loss.
 */
@Composable
internal fun rememberAfterFieldCommit(): (() -> Unit) -> Unit {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    return remember(focus, scope) {
        { action ->
            focus.clearFocus()
            scope.launch {
                withFrameNanos { }
                action()
            }
        }
    }
}

private val WarningColor = Color(0xFFFFC56B)

/** One line of explanatory text with an icon (info / warning / blocking error). */
@Composable
internal fun Notice(text: String, kind: NoticeKind = NoticeKind.INFO, modifier: Modifier = Modifier) {
    val (icon, tint) = when (kind) {
        NoticeKind.INFO -> Icons.Filled.Info to BrushworkColors.OnChromeDim
        NoticeKind.WARNING -> Icons.Filled.Warning to WarningColor
        NoticeKind.ERROR -> Icons.Filled.Error to BrushworkColors.Danger
    }
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (kind == NoticeKind.INFO) BrushworkColors.OnChromeDim else tint,
        )
    }
}

/** "Label ........ value" row used in summaries. */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.weight(0.4f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** Full-width primary action button. */
@Composable
internal fun ApplyButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent, contentColor = Color(0xFF002B55)),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/**
 * 3x3 anchor picker: where the existing image stays when the canvas grows or shrinks. Arrows
 * show the directions in which the canvas changes (inward when that side shrinks).
 */
@Composable
internal fun AnchorPicker(
    anchor: Int,
    onAnchorChange: (Int) -> Unit,
    shrinkX: Boolean,
    shrinkY: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val names = listOf("top left", "top", "top right", "left", "center", "right", "bottom left", "bottom", "bottom right")
    Column(modifier.clip(RoundedCornerShape(8.dp))) {
        for (row in 0..2) Row {
            for (col in 0..2) {
                val idx = row * 3 + col
                val ax = anchor % 3; val ay = anchor / 3
                val dx = col - ax; val dy = row - ay
                Box(
                    Modifier
                        .size(40.dp)
                        .padding(1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled, role = Role.RadioButton) { onAnchorChange(idx) }
                        .semantics {
                            contentDescription = "Anchor ${names[idx]}"
                            selected = idx == anchor
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(if (idx == anchor) BrushworkColors.Accent else BrushworkColors.ChromeHigh)
                        when {
                            idx == anchor -> drawRect(Color.White, topLeft = Offset(size.width * 0.3f, size.height * 0.3f), size = Size(size.width * 0.4f, size.height * 0.4f))
                            abs1(dx) && abs1(dy) -> drawArrow(
                                if (shrinkX) -dx.sign.toFloat() else dx.sign.toFloat(),
                                if (shrinkY) -dy.sign.toFloat() else dy.sign.toFloat(),
                            )
                            else -> drawCircle(BrushworkColors.OnChromeDim, radius = size.minDimension * 0.06f)
                        }
                    }
                }
            }
        }
    }
}

private fun abs1(v: Int) = v in -1..1

private fun DrawScope.drawArrow(dx: Float, dy: Float) {
    val c = center
    val len = size.minDimension * 0.28f
    val angle = atan2(dy, dx)
    val tip = Offset(c.x + cos(angle) * len, c.y + sin(angle) * len)
    val tail = Offset(c.x - cos(angle) * len, c.y - sin(angle) * len)
    val color = BrushworkColors.OnChrome
    val w = size.minDimension * 0.07f
    drawLine(color, tail, tip, strokeWidth = w)
    val head = len * 0.6f
    for (s in floatArrayOf(-1f, 1f)) {
        val a = angle + Math.PI.toFloat() + s * 0.6f
        drawLine(color, tip, Offset(tip.x + cos(a) * head, tip.y + sin(a) * head), strokeWidth = w)
    }
}

/**
 * Schematic of a canvas change: the new canvas ([newW] x [newH]) and where the old image
 * ([oldW] x [oldH], optionally [thumbnail]) sits in it at ([ox], [oy]). Parts of the old image
 * outside the new canvas are dimmed (they get cropped). [fill] tints the added area.
 */
@Composable
internal fun CanvasLayoutPreview(
    oldW: Int,
    oldH: Int,
    newW: Int,
    newH: Int,
    ox: Int,
    oy: Int,
    thumbnail: ImageBitmap?,
    modifier: Modifier = Modifier,
    fill: Color? = null,
) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp))) {
        drawRect(BrushworkColors.CanvasBackdrop)
        val minX = min(0, ox).toFloat(); val minY = min(0, oy).toFloat()
        val maxX = max(newW, ox + oldW).toFloat(); val maxY = max(newH, oy + oldH).toFloat()
        val pad = 8.dp.toPx()
        val s = min((size.width - 2 * pad) / (maxX - minX), (size.height - 2 * pad) / (maxY - minY))
        if (!(s > 0f) || !s.isFinite()) return@Canvas
        val offX = (size.width - (maxX - minX) * s) / 2f - minX * s
        val offY = (size.height - (maxY - minY) * s) / 2f - minY * s
        fun rx(x: Float) = offX + x * s
        fun ry(y: Float) = offY + y * s

        // New canvas (transparent area as a light checker tone, or the fill color).
        val canvasTopLeft = Offset(rx(0f), ry(0f))
        val canvasSize = Size(newW * s, newH * s)
        drawRect(fill ?: Color(0xFFBDBDBD), canvasTopLeft, canvasSize)
        if (fill == null) drawChecker(canvasTopLeft, canvasSize)

        // Old image: full rect dimmed, the part that stays at full strength.
        val imgTopLeft = Offset(rx(ox.toFloat()), ry(oy.toFloat()))
        val imgSize = Size(oldW * s, oldH * s)
        drawImageRect(thumbnail, imgTopLeft, imgSize, alpha = 0.3f)
        clipRect(canvasTopLeft.x, canvasTopLeft.y, canvasTopLeft.x + canvasSize.width, canvasTopLeft.y + canvasSize.height) {
            if (fill != null) drawRect(Color(0xFFBDBDBD), imgTopLeft, imgSize)
            drawImageRect(thumbnail, imgTopLeft, imgSize, alpha = 1f)
        }
        drawRect(BrushworkColors.Accent, imgTopLeft, imgSize, style = Stroke(width = 1.5.dp.toPx()))
        drawRect(Color.White, canvasTopLeft, canvasSize, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun DrawScope.drawChecker(topLeft: Offset, size: Size) {
    val cell = 6.dp.toPx()
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        var y = topLeft.y
        var row = 0
        while (y < topLeft.y + size.height) {
            var x = topLeft.x + if (row % 2 == 0) 0f else cell
            while (x < topLeft.x + size.width) {
                drawRect(Color(0xFF9E9E9E), Offset(x, y), Size(cell, cell))
                x += 2 * cell
            }
            y += cell
            row++
        }
    }
}

private fun DrawScope.drawImageRect(image: ImageBitmap?, topLeft: Offset, size: Size, alpha: Float) {
    if (image == null) {
        drawRect(BrushworkColors.AccentDim, topLeft, size, alpha = 0.6f * alpha + 0.2f)
        return
    }
    drawImage(
        image,
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
    )
}

/** Big full-width action row (icon, title, optional detail) for one-tap operations. */
@Composable
internal fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
        Icon(icon, contentDescription = null, tint = tint, modifier = iconModifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
    }
}
