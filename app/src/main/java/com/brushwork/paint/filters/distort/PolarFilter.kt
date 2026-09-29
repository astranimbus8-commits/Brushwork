package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polar Coordinates. "Rectangular to polar" wraps the image around the center into a ring (the
 * top edge on the outer rim, reading clockwise — the manga sound-effect trick); "Polar to
 * rectangular" is the exact inverse and unwraps a ring into a strip. Margins add empty space
 * around the source before wrapping (bigger center hole / a gap between the ends).
 */
class PolarCoordinatesFilter : Filter("distort.polar_coordinates", "Polar Coordinates", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("mode", "Conversion", listOf("Rectangular to polar", "Polar to rectangular")),
        FilterParam.Slider("phase", "Phase", 0f, 360f, 0f, 1f, "°"),
        FilterParam.Slider("radius", "Radius", 10f, 200f, 100f, 1f, "%"),
        FilterParam.Slider("marginV", "Top & bottom margins", 0f, 200f, 0f, 1f, "%"),
        FilterParam.Slider("marginH", "Left & right margins", 0f, 200f, 0f, 1f, "%"),
        FilterParam.Toggle("insideOut", "Top edge at center", false),
        FilterParam.Point("center", "Center"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val g = Geometry(
            w = w.toFloat(), h = h.toFloat(),
            cx = values.point("center")[0] * w, cy = values.point("center")[1] * h,
            rMax = max(1e-3f, DistortMath.percentRadius(values.float("radius"), w, h)),
            mH = values.float("marginH").coerceAtLeast(0f) / 200f * w,
            mV = values.float("marginV").coerceAtLeast(0f) / 200f * h,
            phaseTurns = values.float("phase") / 360f,
            insideOut = values.bool("insideOut"),
        )
        val toPolar = values.choice("mode") == 0
        // Polar to rectangular: the angle depends only on the column, so tabulate its cos / sin for
        // the two sub-sample columns of every pixel.
        val cosT: FloatArray
        val sinT: FloatArray
        if (toPolar) {
            cosT = FloatArray(0); sinT = FloatArray(0)
        } else {
            cosT = FloatArray(2 * w); sinT = FloatArray(2 * w)
            for (i in 0 until 2 * w) {
                val theta = g.angleOfColumn(i * 0.5f + 0.25f)
                cosT[i] = cos(theta); sinT[i] = sin(theta)
            }
        }
        val out = PixelBuffer(w, h)
        val d = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    // 2x2 supersampling: both directions compress strongly near the center / rim.
                    var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                    for (s in 0 until 4) {
                        val py = y + 0.25f + 0.5f * (s shr 1)
                        val c = if (toPolar) {
                            g.samplePolar(src, x + 0.25f + 0.5f * (s and 1), py)
                        } else {
                            val col = 2 * x + (s and 1)
                            g.sampleRect(src, cosT[col], sinT[col], py)
                        }
                        val a = (c ushr 24).toFloat()
                        if (a <= 0f) continue
                        sa += a
                        sr += ((c shr 16) and 0xFF) * a
                        sg += ((c shr 8) and 0xFF) * a
                        sb += (c and 0xFF) * a
                    }
                    d[row + x] = if (sa < 0.5f) 0 else {
                        val inv = 1f / sa
                        ColorUtils.argb((sa * 0.25f + 0.5f).toInt(), (sr * inv + 0.5f).toInt(), (sg * inv + 0.5f).toInt(), (sb * inv + 0.5f).toInt())
                    }
                }
            }
        }
        return out
    }

    /** Mapping between the padded source strip and the disc (all values in buffer pixels). */
    private class Geometry(
        val w: Float,
        val h: Float,
        val cx: Float,
        val cy: Float,
        val rMax: Float,
        /** Margin on EACH of the left and right sides. */
        val mH: Float,
        /** Margin on EACH of the top and bottom sides. */
        val mV: Float,
        val phaseTurns: Float,
        val insideOut: Boolean,
    ) {
        private val stripW = w + 2f * mH
        private val stripH = h + 2f * mV
        private val wrapX = mH <= 0f

        /** Output is the disc: find where (px, py) came from in the rectangular source. */
        fun samplePolar(src: PixelBuffer, px: Float, py: Float): Int {
            val dx = px - cx; val dy = py - cy
            val rho = sqrt(dx * dx + dy * dy) / rMax
            if (rho > 1f) return 0
            // 0 turns at 12 o'clock, increasing clockwise (y points down).
            val turns = DistortMath.atan2(dy, dx) / DistortMath.TWO_PI + 0.25f - phaseTurns
            val f = turns - floor(turns)
            val u = f * stripW - mH
            val v = (if (insideOut) rho else 1f - rho) * stripH - mV
            if (v < 0f || v > h) return 0
            if (wrapX) {
                return DistortMath.sampleWrapX(src, u, v)
            }
            if (u < 0f || u > w) return 0
            return src.sampleBilinear(u, v)
        }

        /** Polar angle (radians, y down) that output column position [px] of the strip unwraps. */
        fun angleOfColumn(px: Float): Float {
            val f = (px + mH) / stripW
            return DistortMath.TWO_PI * (f + phaseTurns) - DistortMath.PI * 0.5f
        }

        /**
         * Output is the strip: find where the point at row position [py] of the column with angle
         * cos / sin ([cosA], [sinA]) (see [angleOfColumn]) came from in the disc.
         */
        fun sampleRect(src: PixelBuffer, cosA: Float, sinA: Float, py: Float): Int {
            val t = (py + mV) / stripH
            val rho = if (insideOut) t else 1f - t
            val r = rho * rMax
            return src.sampleBilinear(cx + r * cosA, cy + r * sinA, transparentOutside = true)
        }
    }
}
