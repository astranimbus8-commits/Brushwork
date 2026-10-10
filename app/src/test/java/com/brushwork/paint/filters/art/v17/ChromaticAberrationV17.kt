package com.brushwork.paint.filters.art.v17

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * v1.8 F0: a FROZEN copy of the v1.7 chromatic-aberration filters (`filters/art/
 * ChromaticAberration.kt` and the `ArtMath` helpers they use, at 885793b4), in test sources only:
 * v1.8's fringes are checked against it pixel for pixel for the six old color orders
 * (`FringeGoldenTest`) and timed against it (`FringePerfTest`). The same arithmetic, in the same
 * order, on the same rows in parallel, so the output is byte-equal and the cost the same. Never
 * change it.
 *
 * [moving] and [zooming] take what v1.7's `apply` took: the values `distance`, `angle` (Moving),
 * `order` (0..5, [COLOR_ORDER_NAMES]) and `center` (Zooming), and the context's scale.
 */
object ChromaticAberrationV17 {
    /** v1.7's six channel orders: the first channel moves forward, the second stays, the third moves backward. */
    val COLOR_ORDER_NAMES = listOf("RGB", "RBG", "GRB", "GBR", "BRG", "BGR")

    /** v1.7's `ArtMath.colorOrderFactors`: +1 forward, 0 centered, -1 backward for R, G and B of [order]. */
    fun colorOrderFactors(order: Int): IntArray {
        val name = COLOR_ORDER_NAMES[ArtMathV17.clampInt(order, 0, COLOR_ORDER_NAMES.lastIndex)]
        val k = IntArray(3)
        for ((i, ch) in name.withIndex()) {
            val factor = 1 - i
            when (ch) { 'R' -> k[0] = factor; 'G' -> k[1] = factor; else -> k[2] = factor }
        }
        return k
    }

    /** v1.7's Chromatic Aberration (Moving). */
    fun moving(src: PixelBuffer, values: FilterValues, ctx: FilterContext = FilterContext()): PixelBuffer {
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        if (dist < 1e-3f) return src.copy()
        val angle = values.float("angle")
        val vx = ArtMathV17.unitX(angle) * dist; val vy = ArtMathV17.unitY(angle) * dist
        val k = colorOrderFactors(values.choice("order"))
        val rx = -k[0] * vx; val ry = -k[0] * vy
        val gx = -k[1] * vx; val gy = -k[1] * vy
        val bx = -k[2] * vx; val by = -k[2] * vy
        return ChannelShiftV17.recombine(src, ctx) { x, y, pos ->
            val cx = x + 0.5f; val cy = y + 0.5f
            pos[0] = cx + rx; pos[1] = cy + ry
            pos[2] = cx + gx; pos[3] = cy + gy
            pos[4] = cx + bx; pos[5] = cy + by
        }
    }

    /** v1.7's Chromatic Aberration (Zooming). */
    fun zooming(src: PixelBuffer, values: FilterValues, ctx: FilterContext = FilterContext()): PixelBuffer {
        val dist = ctx.px(values.float("distance").coerceAtLeast(0f))
        if (dist < 1e-3f) return src.copy()
        val w = src.width; val h = src.height
        val pt = values.point("center")
        val cx = pt[0].coerceIn(0f, 1f) * w; val cy = pt[1].coerceIn(0f, 1f) * h
        val rRef = max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))
        if (rRef < 1e-3f) return src.copy()
        val s = (dist / rRef).coerceAtMost(0.9f)
        val k = colorOrderFactors(values.choice("order"))
        val fr = 1f - k[0] * s; val fg = 1f - k[1] * s; val fb = 1f - k[2] * s
        return ChannelShiftV17.recombine(src, ctx) { x, y, pos ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            pos[0] = cx + dx * fr; pos[1] = cy + dy * fr
            pos[2] = cx + dx * fg; pos[3] = cy + dy * fg
            pos[4] = cx + dx * fb; pos[5] = cy + dy * fb
        }
    }
}

/** v1.7's `ChannelShift`: each output pixel's R, G and B from three bilinear samples. */
internal object ChannelShiftV17 {
    inline fun recombine(
        src: PixelBuffer,
        ctx: FilterContext,
        crossinline positions: (x: Int, y: Int, pos: FloatArray) -> Unit,
    ): PixelBuffer {
        val w = src.width; val h = src.height
        val out = PixelBuffer(w, h)
        val s = src.pixels; val d = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val pos = FloatArray(6)
            val acc = FloatArray(2)
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    positions(x, y, pos)
                    sample(s, w, h, pos[0], pos[1], 16, acc); val pr = acc[0]; val ar = acc[1]
                    sample(s, w, h, pos[2], pos[3], 8, acc); val pg = acc[0]; val ag = acc[1]
                    sample(s, w, h, pos[4], pos[5], 0, acc); val pb = acc[0]; val ab = acc[1]
                    val a = max(ar, max(ag, ab))
                    d[row + x] = if (a < 0.5f) 0 else {
                        val inv = 1f / a
                        (clamp(a) shl 24) or (clamp(pr * inv) shl 16) or (clamp(pg * inv) shl 8) or clamp(pb * inv)
                    }
                }
            }
        }
        return out
    }

    fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    fun sample(p: IntArray, w: Int, h: Int, fx: Float, fy: Float, shift: Int, acc: FloatArray) {
        val x = fx - 0.5f; val y = fy - 0.5f
        val xf = floor(x); val yf = floor(y)
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val tx = x - xf; val ty = y - yf
        val xa = ArtMathV17.clampInt(x0, 0, w - 1); val xb = ArtMathV17.clampInt(x0 + 1, 0, w - 1)
        val r0 = ArtMathV17.clampInt(y0, 0, h - 1) * w; val r1 = ArtMathV17.clampInt(y0 + 1, 0, h - 1) * w
        val c00 = p[r0 + xa]; val c10 = p[r0 + xb]; val c01 = p[r1 + xa]; val c11 = p[r1 + xb]
        val a00 = (c00 ushr 24) * (1f - tx) * (1f - ty); val a10 = (c10 ushr 24) * tx * (1f - ty)
        val a01 = (c01 ushr 24) * (1f - tx) * ty; val a11 = (c11 ushr 24) * tx * ty
        acc[0] = a00 * ((c00 shr shift) and 0xFF) + a10 * ((c10 shr shift) and 0xFF) +
            a01 * ((c01 shr shift) and 0xFF) + a11 * ((c11 shr shift) and 0xFF)
        acc[1] = a00 + a10 + a01 + a11
    }
}

/** v1.7's `ArtMath` helpers (and `FilterMath.hash01`) that the frozen filters use. */
internal object ArtMathV17 {
    fun unitX(degrees: Float): Float = cos(Math.toRadians(degrees.toDouble())).toFloat()
    fun unitY(degrees: Float): Float = sin(Math.toRadians(degrees.toDouble())).toFloat()

    fun clampInt(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    /** v1.7's `FilterMath.hash01`: deterministic hash-based random in [0, 1). */
    fun hash01(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0x7FFFFFFF) ushr 7) / 16777216f
    }
}
