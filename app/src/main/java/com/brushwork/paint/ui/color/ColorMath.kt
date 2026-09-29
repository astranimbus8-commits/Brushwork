package com.brushwork.paint.ui.color

import com.brushwork.paint.core.ColorUtils
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hue (degrees, 0..360), saturation and brightness (0..1) of the color being edited.
 *
 * The picker keeps its own HSB alongside the packed color so that the hue (and saturation)
 * survive when the color becomes achromatic: gray has no hue and black has no saturation, and
 * recomputing them from RGB would make the wheel thumbs jump.
 */
data class Hsb(val h: Float, val s: Float, val b: Float) {
    fun toColor(alpha: Int = 255): Int = ColorUtils.hsvToColor(h, s, b, alpha)

    companion object {
        /**
         * HSB of [color], keeping [previous]'s hue when [color] is gray and its hue + saturation
         * when [color] is black.
         */
        fun fromColor(color: Int, previous: Hsb? = null): Hsb {
            val out = FloatArray(3)
            ColorUtils.colorToHsv(color, out)
            if (previous == null) return Hsb(out[0], out[1], out[2])
            return when {
                out[2] == 0f -> Hsb(previous.h, previous.s, 0f)
                out[1] == 0f -> Hsb(previous.h, 0f, out[2])
                else -> Hsb(out[0], out[1], out[2])
            }
        }
    }
}

/** Integer HSB values as shown in the slider fields (H 0..360, S/B 0..100). */
fun Hsb.displayH(): Int = h.roundToInt().coerceIn(0, 360)
fun Hsb.displayS(): Int = (s * 100f).roundToInt().coerceIn(0, 100)
fun Hsb.displayB(): Int = (b * 100f).roundToInt().coerceIn(0, 100)

/**
 * Geometry of the HSB wheel: a hue ring with a saturation/brightness square inscribed in it.
 * Hue 0 (red) is at the top and hue increases clockwise. All values are in pixels relative to
 * the wheel's own square bounds of side [size].
 */
class WheelLayout(val size: Float, ringWidth: Float, pad: Float) {
    val center = size / 2f
    val outerRadius = (size / 2f - pad).coerceAtLeast(1f)
    // Degenerate sizes (e.g. 0 px during the first layout pass) must not throw.
    val ringWidth = ringWidth.coerceIn(0f, outerRadius * 0.5f).coerceAtLeast(0.5f)
    val innerRadius = outerRadius - this.ringWidth
    /** Radius of the ring's center line (where the hue thumb sits). */
    val ringMid = outerRadius - this.ringWidth / 2f
    /** Half the side of the saturation/brightness square (inscribed with a small gap). */
    val squareHalf = ((innerRadius - pad) / SQRT2).coerceAtLeast(1f)
    val squareLeft = center - squareHalf
    val squareTop = center - squareHalf
    val squareSide = squareHalf * 2f

    enum class Zone { RING, SQUARE }

    /** Which control a touch at ([x], [y]) grabs: outside the inner circle = ring. */
    fun zoneAt(x: Float, y: Float): Zone =
        if (hypot(x - center, y - center) >= innerRadius) Zone.RING else Zone.SQUARE

    /** Hue (degrees 0..360) of the direction from the center to ([x], [y]). */
    fun hueAt(x: Float, y: Float): Float = hueOfDirection(x - center, y - center)

    /** Saturation (0..1, left to right) at [x], clamped to the square. */
    fun saturationAt(x: Float): Float = ((x - squareLeft) / squareSide).coerceIn(0f, 1f)

    /** Brightness (0..1, bottom to top) at [y], clamped to the square. */
    fun brightnessAt(y: Float): Float = (1f - (y - squareTop) / squareSide).coerceIn(0f, 1f)

    /** Center of the hue thumb for [hue]: returns (x, y). */
    fun huePoint(hue: Float): Pair<Float, Float> = Pair(hueX(hue), hueY(hue))

    /** x / y of the hue thumb center (allocation-free, for drawing). */
    fun hueX(hue: Float): Float = center + ringMid * cos(Math.toRadians((hue - 90f).toDouble())).toFloat()
    fun hueY(hue: Float): Float = center + ringMid * sin(Math.toRadians((hue - 90f).toDouble())).toFloat()

    /** Center of the saturation/brightness thumb: returns (x, y). */
    fun svPoint(s: Float, b: Float): Pair<Float, Float> = Pair(svX(s), svY(b))

    /** x of the saturation/brightness thumb for saturation [s], y for brightness [b]. */
    fun svX(s: Float): Float = squareLeft + s.coerceIn(0f, 1f) * squareSide
    fun svY(b: Float): Float = squareTop + (1f - b.coerceIn(0f, 1f)) * squareSide

    companion object {
        private val SQRT2 = sqrt(2f)

        /** Hue for a direction vector in screen space (y down): up = 0, right = 90, down = 180. */
        fun hueOfDirection(dx: Float, dy: Float): Float {
            if (dx == 0f && dy == 0f) return 0f
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            val h = ((deg % 360f) + 360f) % 360f
            return if (h >= 360f) 0f else h
        }
    }
}

/** Fraction (0..1) along a slider track of [width] px whose thumb has radius [thumbRadius]. */
fun sliderFraction(x: Float, width: Float, thumbRadius: Float): Float {
    val usable = width - 2f * thumbRadius
    if (usable <= 0f) return 0f
    return ((x - thumbRadius) / usable).coerceIn(0f, 1f)
}

/** Hex text the user is typing ("#" optional) -> color, or null while incomplete/invalid. */
fun parseHexInput(text: String, allowAlpha: Boolean): Int? {
    val s = text.trim().removePrefix("#")
    if (s.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return when (s.length) {
        3, 6 -> ColorUtils.parseHex(s)
        8 -> if (allowAlpha) ColorUtils.parseHex(s) else null
        else -> null
    }
}

/**
 * The color hex-field [text] stands for: 8 digits are a full ARGB value; 3 or 6 digits take
 * [alpha] when [withAlpha] (the alpha slider owns it) and are opaque otherwise. Null while the
 * text is incomplete or invalid.
 */
fun hexTextColor(text: String, withAlpha: Boolean, alpha: Int): Int? {
    val digits = text.trim().removePrefix("#")
    val parsed = parseHexInput(digits, withAlpha) ?: return null
    return if (digits.length == 8) parsed else ColorUtils.withAlpha(parsed, if (withAlpha) alpha else 255)
}

/** Hex digits (no '#') for the hex field. */
fun hexDigits(color: Int, withAlpha: Boolean): String = ColorUtils.toHex(color, withAlpha).removePrefix("#")

/** Opacity 0..255 as the whole percent shown by the opacity slider, and back. */
fun alphaToPercent(alpha: Int): Int = (alpha.coerceIn(0, 255) * 100f / 255f).roundToInt()
fun percentToAlpha(percent: Int): Int = (percent.coerceIn(0, 100) * 255f / 100f).roundToInt()
