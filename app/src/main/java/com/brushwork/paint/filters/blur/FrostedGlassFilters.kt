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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared parameters and helpers of the three Frosted Glass variants. */
internal object FrostedGlass {
    const val MAX_SAMPLES = 8

    fun smoothnessParam() = FilterParam.Slider("smoothness", "Smoothness", 0f, 100f, 0f, step = 1f, suffix = "%")

    /** Samples averaged per pixel: 1 (crisp grain) at 0 % up to [MAX_SAMPLES] (soft) at 100 %. */
    fun sampleCount(values: FilterValues): Int {
        val smoothness = values.float("smoothness").takeIf { !it.isNaN() } ?: 0f
        return 1 + (smoothness.coerceIn(0f, 100f) / 100f * (MAX_SAMPLES - 1)).roundToInt()
    }

    /**
     * Uniform random value in [0, 1) for pixel ([x], [y]) from random stream [k] of [seed].
     *
     * Every input goes through a full avalanche mix (murmur3 finaliser) in turn, so neighbouring
     * pixels, streams and seeds give independent values: the several values drawn per pixel
     * (distance, direction, extra smoothness samples) must not be correlated, or the grain shows
     * streaks or a preferred direction.
     */
    fun random(x: Int, y: Int, seed: Int, k: Int): Float {
        var h = mix(seed * -0x61c88647 + k)
        h = mix(h + y * -0x7a143589)
        h = mix(h + x * 0x27d4eb2f)
        return (h ushr 8) / 16777216f // 24 bits: exact in a float, always < 1
    }

    /** murmur3 fmix32 finaliser. */
    private fun mix(v: Int): Int {
        var h = v
        h = (h xor (h ushr 16)) * -0x7a143595
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        return h xor (h ushr 16)
    }

    /**
     * Displaces every pixel by `offset(x, y, sample, out)` (written into out[0], out[1]) for
     * [samples] random samples and averages them (premultiplied, clamped to the image). Pixels for
     * which [offset] returns false are copied unchanged.
     */
    inline fun scatter(
        src: PixelBuffer,
        samples: Int,
        ctx: FilterContext,
        crossinline offset: (x: Int, y: Int, sample: Int, out: FloatArray) -> Boolean,
    ): PixelBuffer {
        val w = src.width
        val h = src.height
        val s = src.pixels
        val result = PixelBuffer(w, h)
        val d = result.pixels
        Parallel.forRows(h) { y0, y1 ->
            val acc = SampleAccumulator(s, w, h)
            val o = FloatArray(2)
            for (y in y0 until y1) {
                if ((y - y0) % Progressive.CANCEL_ROWS == 0) ctx.checkCancelled()
                val row = y * w
                for (x in 0 until w) {
                    acc.reset()
                    var moved = false
                    for (k in 0 until samples) {
                        if (!offset(x, y, k, o)) break
                        moved = true
                        acc.addClamped(x + 0.5f + o[0], y + 0.5f + o[1])
                    }
                    d[row + x] = if (moved) acc.result(s[row + x]) else s[row + x]
                }
            }
        }
        return result
    }
}

/**
 * Frosted Glass (Normal): every pixel is replaced by a pixel from a random spot within Radius, so the
 * picture looks as if seen through frosted glass. Variance shapes the scatter distance (a Gaussian
 * spread of Radius x Variance, cut off at Radius): 100 % spreads nearly evenly over the whole disc,
 * lower values keep most pixels close with occasional far jumps.
 * Smoothness averages several scattered samples for a softer, less grainy result.
 */
class FrostedGlassFilter : Filter("blur.frosted_glass", "Frosted Glass (Normal)", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("radius", "Radius", 0f, 200f, 12f, step = 1f, pixels = true),
        FilterParam.Slider("variance", "Variance", 0f, 100f, 70f, step = 1f, suffix = "%"),
        FrostedGlass.smoothnessParam(),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val radius = min(ctx.px(values.float("radius")), maxReach(src))
        val variance = values.float("variance").coerceIn(0f, 100f) / 100f
        if (!(radius >= 0.25f) || !(variance > 0f)) return src.copy()
        val rho = radiusLut(radius, radius * variance)
        val dirX = FloatArray(DIRS) { cos(it * 2.0 * PI / DIRS).toFloat() }
        val dirY = FloatArray(DIRS) { sin(it * 2.0 * PI / DIRS).toFloat() }
        val seed = values.seed()
        return FrostedGlass.scatter(src, FrostedGlass.sampleCount(values), ctx) { x, y, k, o ->
            val dist = rho[(FrostedGlass.random(x, y, seed, 2 * k) * LUT).toInt()]
            val dir = (FrostedGlass.random(x, y, seed, 2 * k + 1) * DIRS).toInt()
            o[0] = dirX[dir] * dist
            o[1] = dirY[dir] * dist
            true
        }
    }

    private companion object {
        const val LUT = 4096
        const val DIRS = 1024

        /**
         * Inverse CDF of a 2-D Gaussian displacement length (Rayleigh, [sigma]) truncated at
         * [radius], tabulated over u in [0, 1). For sigma >> radius this tends to uniform-in-disc.
         */
        fun radiusLut(radius: Float, sigma: Float): FloatArray {
            val s = max(sigma.toDouble(), 1e-3)
            val r = radius.toDouble()
            val trunc = 1.0 - exp(-r * r / (2.0 * s * s))
            return FloatArray(LUT) {
                val u = (it + 0.5) / LUT
                (s * sqrt(-2.0 * ln(1.0 - u * trunc))).coerceAtMost(r).toFloat()
            }
        }
    }
}

/**
 * Frosted Glass (Zooming): random scatter along the line to a centre point, giving a grainy
 * zoom-burst. The scatter grows from nothing at Center radius to the full Radius at the image edge.
 */
class FrostedGlassZoomingFilter : Filter("blur.frosted_glass_zooming", "Frosted Glass (Zooming)", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("radius", "Radius", 0f, 500f, 40f, step = 1f, pixels = true),
        FilterParam.Slider("centerRadius", "Center radius", 0f, 2000f, 0f, step = 1f, pixels = true),
        FrostedGlass.smoothnessParam(),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val radius = min(ctx.px(values.float("radius")), maxReach(src))
        if (!(radius >= 0.25f)) return src.copy()
        val c = values.centerPx("center", src)
        val cx = c[0]
        val cy = c[1]
        val r0 = ctx.px(values.float("centerRadius")).coerceAtLeast(0f)
        val ramp = max(1f, max(src.width, src.height) * 0.5f - r0)
        val seed = values.seed()
        return FrostedGlass.scatter(src, FrostedGlass.sampleCount(values), ctx) { x, y, k, o ->
            val vx = x + 0.5f - cx
            val vy = y + 0.5f - cy
            val r = sqrt(vx * vx + vy * vy)
            if (r <= r0 || r < 1e-3f) {
                false
            } else {
                val f = ((r - r0) / ramp).coerceAtMost(1f)
                val t = (2f * FrostedGlass.random(x, y, seed, k) - 1f) * radius * f / r
                o[0] = vx * t
                o[1] = vy * t
                true
            }
        }
    }
}

/**
 * Frosted Glass (Moving): random scatter along one direction, like a grainy motion blur.
 * Direction is the axis angle (0° = horizontal, 90° = vertical).
 */
class FrostedGlassMovingFilter : Filter("blur.frosted_glass_moving", "Frosted Glass (Moving)", FilterCategory.BLUR) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("radius", "Radius", 0f, 500f, 30f, step = 1f, pixels = true),
        FilterParam.Slider("angle", "Direction", 0f, 180f, 0f, step = 1f, suffix = "°"),
        FrostedGlass.smoothnessParam(),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val radius = min(ctx.px(values.float("radius")), maxReach(src))
        if (!(radius >= 0.25f)) return src.copy()
        val angle = (values.float("angle").takeIf { it.isFinite() } ?: 0f) * (PI / 180.0)
        val dx = (cos(angle) * radius).toFloat()
        val dy = (-sin(angle) * radius).toFloat() // screen y points down
        val seed = values.seed()
        return FrostedGlass.scatter(src, FrostedGlass.sampleCount(values), ctx) { x, y, k, o ->
            val t = 2f * FrostedGlass.random(x, y, seed, k) - 1f
            o[0] = dx * t
            o[1] = dy * t
            true
        }
    }
}
