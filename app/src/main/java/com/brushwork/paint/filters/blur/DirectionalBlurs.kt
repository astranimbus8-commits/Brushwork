package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance from ([cx], [cy]) to the farthest corner of a [w] x [h] image. */
internal fun farthestCorner(cx: Float, cy: Float, w: Int, h: Int): Float {
    val dx = max(cx, w - cx)
    val dy = max(cy, h - cy)
    return sqrt(dx * dx + dy * dy)
}

/** Centre of a Point parameter in buffer pixels (clamped to the image). */
internal fun FilterValues.centerPx(key: String, src: PixelBuffer): FloatArray {
    val p = point(key)
    val nx = p.getOrElse(0) { 0.5f }.takeIf { it.isFinite() } ?: 0.5f
    val ny = p.getOrElse(1) { 0.5f }.takeIf { it.isFinite() } ?: 0.5f
    return floatArrayOf(nx.coerceIn(0f, 1f) * src.width, ny.coerceIn(0f, 1f) * src.height)
}

/**
 * Zooming Blur: radial streaks around a centre point, like a camera zooming during the exposure or
 * manga speed lines. Streak length grows with the distance from the centre (Strength = streak
 * length as a fraction of that distance); everything inside Center radius stays sharp.
 */
class ZoomingBlurFilter : Filter("blur.zooming", "Zooming Blur", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("strength", "Strength", 0f, 100f, 30f, step = 1f, suffix = "%"),
        FilterParam.Slider("centerRadius", "Center radius", 0f, 2000f, 0f, step = 1f, pixels = true),
        FilterParam.Choice("direction", "Streaks", listOf("Outward", "Both ways")),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width
        val h = src.height
        val (cx, cy) = values.centerPx("center", src).let { it[0] to it[1] }
        val k = values.float("strength").coerceIn(0f, 100f) / 100f * MAX_STREAK
        val r0 = ctx.px(values.float("centerRadius")).coerceAtLeast(0f)
        val both = values.choice("direction") == 1
        // Samples are taken at radius r0 + (r - r0) * s: s < 1 looks toward the centre, so content
        // is smeared outward; "both ways" also samples beyond the pixel.
        val sMin = if (both) 1f - k / 2f else 1f - k
        val sMax = if (both) 1f + k / 2f else 1f
        val maxStreak = max(0f, farthestCorner(cx, cy, w, h) - r0) * (sMax - sMin)
        if (!(maxStreak >= 0.5f)) return src.copy()

        val passes = Progressive.passesFor(maxStreak + 1f)
        // Scales are spaced evenly in log space so that the passes compose exactly.
        val lnMin = ln(sMin.toDouble())
        val step = (ln(sMax.toDouble()) - lnMin) / (Progressive.effectiveSamples(passes) - 1)
        return Progressive.run(src, passes, ctx) { pass, input, out ->
            val params = Progressive.passParams(pass, lnMin, step)
            val scales = FloatArray(params.size) { exp(params[it]).toFloat() }
            val inPx = input.pixels
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                val acc = SampleAccumulator(inPx, w, h)
                for (y in y0 until y1) {
                    val vy = y + 0.5f - cy
                    val row = y * w
                    for (x in 0 until w) {
                        val vx = x + 0.5f - cx
                        val r = sqrt(vx * vx + vy * vy)
                        val d = r - r0
                        if (d <= 1e-3f) { out[row + x] = inPx[row + x]; continue }
                        val ux = vx / r
                        val uy = vy / r
                        acc.reset()
                        for (s in scales) {
                            val rr = r0 + d * s
                            acc.addInside(cx + ux * rr, cy + uy * rr)
                        }
                        out[row + x] = acc.result(inPx[row + x])
                    }
                }
            }
        }
    }

    private companion object {
        /** Streak length at 100 % strength, as a fraction of the distance to the centre. */
        const val MAX_STREAK = 0.95f
    }
}

/**
 * Spin Blur: rotational blur around a centre point (wheels, spinning objects). Angle is the arc the
 * content sweeps; pixels far from the centre get longer streaks. With a spin direction the streak
 * trails behind the motion only.
 */
class SpinBlurFilter : Filter("blur.spin", "Spin Blur", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("angle", "Angle", 0f, 180f, 20f, step = 1f, suffix = "°"),
        FilterParam.Choice("direction", "Spin", listOf("Both ways", "Clockwise", "Counterclockwise")),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width
        val h = src.height
        val (cx, cy) = values.centerPx("center", src).let { it[0] to it[1] }
        val theta = values.float("angle").coerceIn(0f, 360f) * (PI / 180.0)
        // Screen y points down, so a positive angle rotates clockwise. A pixel averages the content
        // at angles +phi from it: content ahead of a clockwise spin -> phi in [0, theta].
        val phiMin = when (values.choice("direction")) {
            1 -> 0.0
            2 -> -theta
            else -> -theta / 2.0
        }
        val maxArc = farthestCorner(cx, cy, w, h) * theta
        if (!(maxArc >= 0.5)) return src.copy()

        val passes = Progressive.passesFor(maxArc.toFloat() + 1f)
        val step = theta / (Progressive.effectiveSamples(passes) - 1)
        return Progressive.run(src, passes, ctx) { pass, input, out ->
            val phis = Progressive.passParams(pass, phiMin, step)
            val cosT = FloatArray(phis.size) { cos(phis[it]).toFloat() }
            val sinT = FloatArray(phis.size) { sin(phis[it]).toFloat() }
            val inPx = input.pixels
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                val acc = SampleAccumulator(inPx, w, h)
                for (y in y0 until y1) {
                    val vy = y + 0.5f - cy
                    val row = y * w
                    for (x in 0 until w) {
                        val vx = x + 0.5f - cx
                        acc.reset()
                        for (i in cosT.indices) {
                            val c = cosT[i]
                            val s = sinT[i]
                            acc.addInside(cx + vx * c - vy * s, cy + vx * s + vy * c)
                        }
                        out[row + x] = acc.result(inPx[row + x])
                    }
                }
            }
        }
    }
}

/**
 * Motion Blur: straight streaks along Angle (the direction of motion; 0° = right, 90° = up) over
 * Distance pixels, centred on the content or trailing behind it.
 */
class MotionBlurFilter : Filter("blur.motion", "Motion Blur", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("angle", "Angle", 0f, 360f, 0f, step = 1f, suffix = "°"),
        FilterParam.Slider("distance", "Distance", 0f, 1000f, 40f, step = 1f, pixels = true),
        FilterParam.Choice("direction", "Streak", listOf("Both ways", "Trailing")),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width
        val h = src.height
        val length = ctx.px(values.float("distance"))
        if (!(length >= 0.5f)) return src.copy()
        val angle = values.float("angle") * (PI / 180.0)
        val dx = cos(angle)
        val dy = -sin(angle) // screen y points down
        // A pixel averages the content at p + t * dir. Trailing: t in [0, L] (content ahead of
        // the pixel), so every object leaves its streak behind it.
        val tMin = if (values.choice("direction") == 1) 0.0 else -length / 2.0

        val passes = Progressive.passesFor(length + 1f)
        val step = length.toDouble() / (Progressive.effectiveSamples(passes) - 1)
        return Progressive.run(src, passes, ctx) { pass, input, out ->
            val ts = Progressive.passParams(pass, tMin, step)
            val ox = FloatArray(ts.size) { (ts[it] * dx).toFloat() }
            val oy = FloatArray(ts.size) { (ts[it] * dy).toFloat() }
            val inPx = input.pixels
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                val acc = SampleAccumulator(inPx, w, h)
                for (y in y0 until y1) {
                    val py = y + 0.5f
                    val row = y * w
                    for (x in 0 until w) {
                        val px = x + 0.5f
                        acc.reset()
                        for (i in ox.indices) acc.addInside(px + ox[i], py + oy[i])
                        out[row + x] = acc.result(inPx[row + x])
                    }
                }
            }
        }
    }
}
