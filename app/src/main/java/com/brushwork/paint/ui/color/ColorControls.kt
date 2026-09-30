package com.brushwork.paint.ui.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.ui.common.NumberAdjust
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/** Opaque alpha bits: drawing colors are always opaque. */
internal const val OPAQUE: Int = 0xFF000000.toInt()

private val HUE_COLORS = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

/** Wheel geometry for a wheel of [sizePx] pixels (ring = 12% of the size). */
private fun wheelLayout(sizePx: Float, density: Density): WheelLayout =
    WheelLayout(sizePx, sizePx * 0.12f, with(density) { 4.dp.toPx() })

/**
 * Clears text-field focus as soon as a touch starts here. A numeric or hex field that is being
 * edited then commits BEFORE this control changes the color; otherwise its later focus-loss
 * commit would re-apply the stale typed value over the change made here. Also hides the keyboard.
 * Must be given the focus manager of the window the control lives in (sheets and dialogs are
 * separate windows), i.e. read `LocalFocusManager.current` inside their content.
 */
internal fun Modifier.clearFocusOnPress(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        focusManager.clearFocus()
    }
}

/**
 * HSB wheel: drag the ring to change hue, the inner square to change saturation (x) and
 * brightness (y). Consumes its gestures so an enclosing scroll/sheet doesn't move.
 */
@Composable
fun HsbWheel(state: ColorEditState, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    Box(
        modifier
            .aspectRatio(1f)
            .clearFocusOnPress(focusManager)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val layout = wheelLayout(size.width.toFloat(), this@pointerInput)
                    val zone = layout.zoneAt(down.position.x, down.position.y)
                    fun apply(p: Offset) {
                        if (zone == WheelLayout.Zone.RING) state.setHsb(h = layout.hueAt(p.x, p.y))
                        else state.setHsb(s = layout.saturationAt(p.x), b = layout.brightnessAt(p.y))
                    }
                    down.consume()
                    apply(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        if (change.positionChange() != Offset.Zero) apply(change.position)
                        change.consume()
                    }
                }
            }
            .drawWithCache {
                // Everything that depends only on the size is built once per size; the square's
                // white -> hue gradient only when the hue changes (not on S/B drags).
                val layout = wheelLayout(size.width, this)
                val center = Offset(layout.center, layout.center)
                val ring = Brush.sweepGradient(HUE_COLORS, center)
                val ringStroke = Stroke(layout.ringWidth)
                val sqTopLeft = Offset(layout.squareLeft, layout.squareTop)
                val sqSize = Size(layout.squareSide, layout.squareSide)
                val darken = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = layout.squareTop, endY = layout.squareTop + layout.squareSide)
                val ringThumb = layout.ringWidth / 2f - 1.dp.toPx()
                val svThumb = 11.dp.toPx()
                val thumb = ThumbStyle(this)
                var squareHue = Float.NaN
                var square: Brush = SolidColor(Color.White)
                onDrawBehind {
                    // Hue 0 at the top: the sweep gradient starts at 3 o'clock, so rotate it.
                    rotate(-90f, pivot = center) { drawCircle(ring, radius = layout.ringMid, center = center, style = ringStroke) }
                    val hsb = state.hsb
                    val hueColor = Color(ColorUtils.hsvToColor(hsb.h, 1f, 1f))
                    if (hsb.h != squareHue) {
                        squareHue = hsb.h
                        square = Brush.horizontalGradient(listOf(Color.White, hueColor), startX = layout.squareLeft, endX = layout.squareLeft + layout.squareSide)
                    }
                    drawRect(square, topLeft = sqTopLeft, size = sqSize)
                    drawRect(darken, topLeft = sqTopLeft, size = sqSize)
                    drawThumb(Offset(layout.hueX(hsb.h), layout.hueY(hsb.h)), ringThumb, hueColor, thumb)
                    drawThumb(Offset(layout.svX(hsb.s), layout.svY(hsb.b)), svThumb, Color(state.color or OPAQUE), thumb)
                }
            }
    ) {
        WheelSemantics(state, Modifier.matchParentSize())
    }
}

/**
 * Screen-reader description of the wheel. A separate composable so that only this recomposes
 * when the color changes (the wheel itself must not, or its draw cache would be rebuilt).
 */
@Composable
private fun WheelSemantics(state: ColorEditState, modifier: Modifier) {
    val hsb = state.hsb
    val description = "Hue ${hsb.displayH()}°, saturation ${hsb.displayS()}%, brightness ${hsb.displayB()}%"
    Box(
        modifier.semantics {
            contentDescription = "Color wheel"
            stateDescription = description
        }
    )
}

/** Pre-built strokes for [drawThumb] (sizes in px for the given density). */
private class ThumbStyle(density: Density) {
    val ringWidth = with(density) { 3.dp.toPx() }
    val ring = Stroke(ringWidth)
    val outline = Stroke(with(density) { 1.5.dp.toPx() })
}

private val THUMB_OUTLINE = Color.Black.copy(alpha = 0.55f)

/** Thumb: color disc with a white ring and a thin dark outline (visible on any color). */
private fun DrawScope.drawThumb(center: Offset, radius: Float, fill: Color, style: ThumbStyle) {
    drawCircle(THUMB_OUTLINE, radius = radius + style.ringWidth / 2f, center = center, style = style.outline)
    drawCircle(Color.White, radius = radius, center = center, style = style.ring)
    drawCircle(fill, radius = (radius - style.ringWidth / 2f).coerceAtLeast(1f), center = center)
}

/**
 * Horizontal slider with a gradient track. [fraction] is 0..1. Horizontal drags and taps set the
 * value; vertical swipes pass through to an enclosing scroll.
 */
@Composable
fun GradientSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    track: Brush,
    thumbColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    checker: Boolean = false,
    valueText: String = "",
) {
    val onChange by rememberUpdatedState(onFractionChange)
    val focusManager = LocalFocusManager.current
    val thumbRadius: Dp = 12.dp
    val trackShape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(44.dp)
            .semantics {
                contentDescription = label
                if (valueText.isNotEmpty()) stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                setProgress { v -> onChange(v.coerceIn(0f, 1f)); true }
            }
            .clearFocusOnPress(focusManager)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onChange(sliderFraction(it.x, size.width.toFloat(), thumbRadius.toPx())) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onChange(sliderFraction(it.x, size.width.toFloat(), thumbRadius.toPx())) },
                ) { change, _ ->
                    change.consume()
                    onChange(sliderFraction(change.position.x, size.width.toFloat(), thumbRadius.toPx()))
                }
            }
            .drawWithCache {
                val r = thumbRadius.toPx()
                val thumb = ThumbStyle(this)
                onDrawWithContent {
                    drawContent()
                    val x = r + fraction.coerceIn(0f, 1f) * (size.width - 2f * r)
                    drawThumb(Offset(x, size.height / 2f), r, thumbColor, thumb)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = thumbRadius - 2.dp)
                .height(20.dp)
                .clip(trackShape)
                .then(if (checker) Modifier.checkerboard(5.dp) else Modifier)
                .background(track)
                .border(1.dp, BrushworkColors.ChromeBorder, trackShape)
        )
    }
}

/**
 * A labeled channel: gradient slider + numeric field (integer [value] in 0..[max]).
 * [onValueChange] should ignore a value equal to the channel's current one: the field re-sends
 * its number when it loses focus.
 */
@Composable
fun ChannelRow(
    label: String,
    value: Int,
    max: Int,
    track: Brush,
    thumbColor: Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fieldLabel: String = label,
    valueSuffix: String = "",
    checker: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GradientSlider(
            fraction = value / max.toFloat(),
            onFractionChange = { onValueChange((it * max).roundToInt().coerceIn(0, max)) },
            track = track,
            thumbColor = thumbColor,
            label = label,
            valueText = "$value$valueSuffix",
            checker = checker,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        NumberField(
            label = fieldLabel,
            value = value.toDouble(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, max)) },
            decimals = 0,
            min = 0.0,
            max = max.toDouble(),
            // The gradient slider beside it is this channel's slider.
            adjust = NumberAdjust.NONE,
            modifier = Modifier.width(84.dp),
        )
    }
}

/** R, G, B sliders (0..255); each track shows the color with that channel swept. */
@Composable
fun RgbSliders(state: ColorEditState, modifier: Modifier = Modifier) {
    val c = state.color
    val r = ColorUtils.red(c); val g = ColorUtils.green(c); val b = ColorUtils.blue(c)
    val thumb = Color(c or OPAQUE)
    // The callbacks compare with and read the state at call time, not the values captured here.
    Column(modifier) {
        ChannelRow("Red", r, 255, Brush.horizontalGradient(listOf(rgb(0, g, b), rgb(255, g, b))), thumb,
            { if (it != ColorUtils.red(state.color)) state.setRed(it) }, fieldLabel = "R")
        ChannelRow("Green", g, 255, Brush.horizontalGradient(listOf(rgb(r, 0, b), rgb(r, 255, b))), thumb,
            { if (it != ColorUtils.green(state.color)) state.setGreen(it) }, fieldLabel = "G")
        ChannelRow("Blue", b, 255, Brush.horizontalGradient(listOf(rgb(r, g, 0), rgb(r, g, 255))), thumb,
            { if (it != ColorUtils.blue(state.color)) state.setBlue(it) }, fieldLabel = "B")
    }
}

/** H (0..360°), S and B (0..100%) sliders. */
@Composable
fun HsbSliders(state: ColorEditState, modifier: Modifier = Modifier) {
    val hsb = state.hsb
    val thumb = Color(state.color or OPAQUE)
    // Unchanged whole values are ignored so a field re-sending its number on focus loss doesn't
    // round away the finer hue/saturation/brightness set with the wheel.
    Column(modifier) {
        ChannelRow("Hue", hsb.displayH(), 360, Brush.horizontalGradient(HUE_COLORS), Color(ColorUtils.hsvToColor(hsb.h, 1f, 1f)),
            { if (it != state.hsb.displayH()) state.setHsb(h = it.toFloat()) }, fieldLabel = "H°", valueSuffix = "°")
        ChannelRow("Saturation", hsb.displayS(), 100,
            Brush.horizontalGradient(listOf(Color(ColorUtils.hsvToColor(hsb.h, 0f, hsb.b)), Color(ColorUtils.hsvToColor(hsb.h, 1f, hsb.b)))),
            thumb, { if (it != state.hsb.displayS()) state.setHsb(s = it / 100f) }, fieldLabel = "S%", valueSuffix = "%")
        ChannelRow("Brightness", hsb.displayB(), 100,
            Brush.horizontalGradient(listOf(Color.Black, Color(ColorUtils.hsvToColor(hsb.h, hsb.s, 1f)))),
            thumb, { if (it != state.hsb.displayB()) state.setHsb(b = it / 100f) }, fieldLabel = "B%", valueSuffix = "%")
    }
}

/** Opacity slider over a checkerboard (0..100%). */
@Composable
fun AlphaSlider(state: ColorEditState, modifier: Modifier = Modifier) {
    val opaque = state.color or OPAQUE
    ChannelRow(
        label = "Opacity",
        value = alphaToPercent(state.alpha),
        max = 100,
        track = Brush.horizontalGradient(listOf(Color(opaque and 0x00FFFFFF), Color(opaque))),
        thumbColor = Color(state.color),
        onValueChange = { if (it != alphaToPercent(state.alpha)) state.setAlpha(percentToAlpha(it)) },
        fieldLabel = "A%",
        valueSuffix = "%",
        checker = true,
        modifier = modifier,
    )
}

private fun rgb(r: Int, g: Int, b: Int) = Color(ColorUtils.rgb(r, g, b))

/**
 * Hex entry. Applies as soon as a full value is typed (#RRGGBB, or #AARRGGBB when [withAlpha];
 * six digits then keep the current alpha); "#RGB" is expanded on Done. Invalid text reverts.
 */
@Composable
fun HexField(state: ColorEditState, withAlpha: Boolean, modifier: Modifier = Modifier) {
    val current = hexDigits(state.color, withAlpha)
    var text by remember { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val maxLen = if (withAlpha) 8 else 6

    fun apply(digits: String) {
        val c = hexTextColor(digits, withAlpha, state.alpha) ?: return
        if (c != state.color) state.setColor(c)
    }

    // While focused the field shows the typed text. The picker's own controls take focus away
    // first (clearFocusOnPress); if the color still changes some other way (e.g. the drawing
    // color is set from outside), text that no longer stands for it is replaced.
    if (focused) {
        LaunchedEffect(current) {
            if (hexTextColor(text, withAlpha, state.alpha) != state.color) text = current
        }
    }

    OutlinedTextField(
        value = if (focused) text else current,
        onValueChange = { raw ->
            val digits = raw.removePrefix("#").filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(maxLen).uppercase()
            text = digits
            if (digits.length == 6 || digits.length == 8) apply(digits)
        },
        label = { Text("Hex", maxLines = 1) },
        prefix = { Text("#") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { apply(text); focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { f ->
            if (f.isFocused && !focused) text = current
            if (!f.isFocused && focused && text.length == 3) apply(text)
            focused = f.isFocused
        },
    )
}

/** Segmented Wheel / RGB / HSB selector (plain row: safe inside dialogs). */
@Composable
fun ModeTabs(selected: PickerMode, onSelect: (PickerMode) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BrushworkColors.ChromeHigh)
            .padding(3.dp)
    ) {
        PickerMode.entries.forEach { m ->
            val isSel = m == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) BrushworkColors.AccentDim else Color.Transparent)
                    // Commit a field being edited before its tab goes away.
                    .selectable(selected = isSel, role = Role.Tab, onClick = { focusManager.clearFocus(); onSelect(m) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (isSel) Color.White else BrushworkColors.OnChrome, maxLines = 1)
            }
        }
    }
}

/** Mode-dependent picker body: wheel or slider set. */
@Composable
fun PickerBody(state: ColorEditState, mode: PickerMode, wheelMaxSize: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (mode) {
            PickerMode.WHEEL -> HsbWheel(state, Modifier.widthIn(max = wheelMaxSize).fillMaxWidth())
            PickerMode.RGB -> RgbSliders(state)
            PickerMode.HSB -> HsbSliders(state)
        }
    }
}

/**
 * Previous | current comparison. Tapping the previous half calls [onRevert].
 * Colors are shown as given (callers map them for grayscale documents).
 */
@Composable
fun CompareSwatch(previous: Int, current: Int, onRevert: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val focusManager = LocalFocusManager.current
    Row(
        modifier
            .clearFocusOnPress(focusManager)
            .height(48.dp)
            .clip(shape)
            .checkerboard(6.dp)
            .border(1.dp, BrushworkColors.ChromeBorder, shape)
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(previous))
                .clickable(onClickLabel = "Restore previous color", onClick = onRevert)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.BottomStart,
        ) { Text("Previous", style = MaterialTheme.typography.labelSmall, color = labelColorOn(previous), maxLines = 1) }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(current))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.BottomEnd,
        ) { Text("Current", style = MaterialTheme.typography.labelSmall, color = labelColorOn(current), maxLines = 1) }
    }
}

/** Black or white text, whichever reads better on [c] (semi-transparent colors sit on white). */
internal fun labelColorOn(c: Int): Color {
    val onWhite = ColorUtils.over(c, -1)
    return if (ColorUtils.luminance(onWhite) > 140) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.9f)
}
