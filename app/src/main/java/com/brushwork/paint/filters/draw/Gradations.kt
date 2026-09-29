package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Color, repetition and compositing parameters shared by the gradation filters (and Clouds). */
internal object GradationParams {
    const val MODE_FADE = 0
    const val MODE_TWO_COLORS = 1
    const val MODE_GRADIENT = 2
    val COLOR_MODES = listOf("Color → transparent", "Color → second color", "Gradient")

    /** Index = [Wave] shape constant. */
    val REPEAT_MODES = listOf("None", "Repeat", "Mirror", "Wave")

    val DEFAULT_GRADIENT = listOf(
        GradientStop(0f, 0xFF27206B.toInt()),
        GradientStop(0.5f, 0xFFE0476B.toInt()),
        GradientStop(1f, 0xFFFFD36E.toInt()),
    )

    fun colorParams(defaultMode: Int = MODE_FADE, color2: Int = -1): List<FilterParam> = listOf(
        FilterParam.Choice("colors", "Colors", COLOR_MODES, defaultMode),
        FilterParam.Color("color", "Color", 0xFF000000.toInt(), useDrawingColor = true),
        FilterParam.Color("color2", "Second color", color2),
        FilterParam.Gradient("gradient", "Gradient", DEFAULT_GRADIENT),
    )

    fun waveParams(defaultRepeat: Int, defaultContrast: Float = 0f): List<FilterParam> = listOf(
        FilterParam.Choice("repeat", "Repeat", REPEAT_MODES, defaultRepeat),
        FilterParam.Slider("contrast", "Contrast", 0f, 100f, defaultContrast, 1f, "%"),
        FilterParam.Toggle("reverse", "Reverse", false),
    )

    fun blendParams(): List<FilterParam> = listOf(DrawBlend.opacityParam(), DrawBlend.param())

    /** Gradient stops chosen by the "colors" parameter. */
    fun stops(v: FilterValues): List<GradientStop> {
        val c = v.color("color")
        return when (v.choice("colors")) {
            MODE_TWO_COLORS -> listOf(GradientStop(0f, c), GradientStop(1f, v.color("color2")))
            MODE_GRADIENT -> v.gradient("gradient")
            else -> listOf(GradientStop(0f, c), GradientStop(1f, c and 0x00FFFFFF))
        }
    }
}

/**
 * Resolved shading state of a gradation: maps a ramp position to a color and composites it
 * over the layer. Thread-safe (read-only after construction).
 */
internal class GradationStyle(v: FilterValues) {
    val shape: Int = v.choice("repeat").coerceIn(Wave.CLAMP, Wave.COSINE)
    private val contrast = (v.float("contrast") / 100f).coerceIn(0f, 1f).toDouble()
    private val reverse = v.bool("reverse")
    val opacity: Float = (v.float("opacity") / 100f).coerceIn(0f, 1f)
    private val blend = v.choice("blend").coerceIn(0, DrawBlend.NAMES.lastIndex)
    private val lut = GradientLut(GradationParams.stops(v))

    /**
     * Composites the gradation at ramp position [s] over [dst]. [fw] is the pixel footprint in
     * ramp units (used for antialiasing); [waveShape] overrides the user's repeat mode.
     */
    fun shade(dst: Int, s: Double, fw: Double, waveShape: Int = shape): Int {
        var t = Wave.profile(waveShape, s, fw)
        t = Wave.applyContrast(t, Wave.contrastGain(contrast, waveShape, fw))
        if (reverse) t = 1.0 - t
        return DrawBlend.composite(dst, lut.at(t.toFloat()), opacity, blend)
    }
}

/**
 * Parallel (linear) gradation: the colors run from the Start point to the End point along the
 * line joining them; bands are perpendicular to it. With a repeat mode the ramp repeats every
 * Start-End distance (stripes / waves).
 */
class ParallelGradationFilter : Filter("draw.parallel_gradation", "Parallel Gradation", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Point("start", "Start", 0.5f, 0.15f),
        FilterParam.Point("end", "End", 0.5f, 0.85f),
    ) + GradationParams.colorParams() + GradationParams.waveParams(Wave.CLAMP) + GradationParams.blendParams()

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val style = GradationStyle(values)
        if (style.opacity <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val a = values.point("start"); val b = values.point("end")
        val x0 = a[0].toDouble() * w; val y0 = a[1].toDouble() * h
        var dx = (b[0] - a[0]).toDouble() * w
        var dy = (b[1] - a[1]).toDouble() * h
        var len = sqrt(dx * dx + dy * dy)
        if (!(len > 1e-6)) { dx = 0.0; dy = 1.0; len = 1e-3 } // coincident points: hard edge, top to bottom
        val ux = dx / sqrt(dx * dx + dy * dy); val uy = dy / sqrt(dx * dx + dy * dy)
        len = max(len, 1e-3)
        // A pixel's footprint projected onto the gradient direction, in ramp units.
        val fw = (abs(ux) + abs(uy)) / len
        val sx = ux / len; val sy = uy / len
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            style.shade(c, (x + 0.5 - x0) * sx + (y + 0.5 - y0) * sy, fw)
        }
    }
}

/**
 * Concentric gradation: colors radiate from the Center; one ramp spans the Radius (a percentage
 * of half the canvas diagonal). Optional elliptical shape with rotation.
 */
class ConcentricGradationFilter : Filter("draw.concentric_gradation", "Concentric Gradation", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center", 0.5f, 0.5f),
        FilterParam.Slider("radius", "Radius", 0.5f, 200f, 100f, 0.5f, "%"),
        FilterParam.Slider("ellipse", "Ellipse ratio", 5f, 100f, 100f, 1f, "%"),
        FilterParam.Slider("angle", "Ellipse angle", 0f, 180f, 0f, 1f, "°"),
    ) + GradationParams.colorParams() + GradationParams.waveParams(Wave.CLAMP) + GradationParams.blendParams()

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val style = GradationStyle(values)
        if (style.opacity <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val c = values.point("center")
        val cx = c[0].toDouble() * w; val cy = c[1].toDouble() * h
        val halfDiag = 0.5 * sqrt(w.toDouble() * w + h.toDouble() * h)
        val radius = max(1e-3, values.float("radius").coerceIn(0.1f, 1000f) / 100.0 * halfDiag)
        val q = (values.float("ellipse") / 100.0).coerceIn(0.01, 1.0)
        val ang = Math.toRadians(values.float("angle").toDouble())
        val ca = cos(ang); val sa = sin(ang)
        val invQ2 = 1.0 / (q * q)
        return FilterMath.mapXY(src, ctx) { x, y, px ->
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
            // Rotate into the ellipse frame; the minor axis is v.
            val u = dx * ca + dy * sa
            val v = -dx * sa + dy * ca
            val re = sqrt(u * u + v * v * invQ2)
            // Pixel footprint = L1 norm of grad(re) (ring spacing shrinks along the minor axis).
            val fw = if (re > 1e-9) {
                val gx = (u * ca - v * sa * invQ2) / re
                val gy = (u * sa + v * ca * invQ2) / re
                (abs(gx) + abs(gy)) / radius
            } else 1.0 / (q * radius)
            style.shade(px, re / radius, fw)
        }
    }
}

/**
 * Radial line (angular / sunburst) gradation around the Center. "None" sweeps the colors once
 * around the circle (a conic gradient); repeat modes produce that many rays.
 */
class RadialLineGradationFilter : Filter("draw.radial_line_gradation", "Radial Line Gradation", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center", 0.5f, 0.5f),
        FilterParam.Slider("count", "Number of rays", 1f, 100f, 12f, 1f),
        FilterParam.Slider("angle", "Rotation", 0f, 360f, 0f, 1f, "°"),
    ) + GradationParams.colorParams() + GradationParams.waveParams(Wave.COSINE, 60f) + GradationParams.blendParams()

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val style = GradationStyle(values)
        if (style.opacity <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val c = values.point("center")
        val cx = c[0].toDouble() * w; val cy = c[1].toDouble() * h
        val rays = values.int("count").coerceIn(1, 1000)
        val rot = Math.toRadians(values.float("angle").toDouble())
        // "None" is a single sweep: a sawtooth with one ramp per turn closes seamlessly.
        val shape = if (style.shape == Wave.CLAMP) Wave.SAW else style.shape
        val ramps = when (style.shape) {
            Wave.CLAMP -> 1.0
            Wave.SAW -> rays.toDouble()
            else -> 2.0 * rays // mirror / cosine have two ramps per ray
        }
        val perRadian = ramps / (2 * PI)
        return FilterMath.mapXY(src, ctx) { x, y, px ->
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
            var a = atan2(dy, dx) - rot
            // Wrap to [-PI, PI): every shape is continuous across the wrap (see Wave), so no seam.
            a -= 2 * PI * kotlin.math.floor((a + PI) / (2 * PI))
            val r = max(sqrt(dx * dx + dy * dy), 1e-3)
            // Angular footprint of a pixel is ~1/r radians; at the center it averages to the mean.
            val fw = min(perRadian * 1.2 / r, ramps)
            style.shade(px, a * perRadian, fw, shape)
        }
    }
}
