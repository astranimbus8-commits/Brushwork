package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Manga focus (concentration) lines: wedge-shaped strokes that converge on the Center, thin at
 * their inner end and widest at the far edge of the canvas, leaving a clear area with a ragged
 * edge around the center. The clear area is an ellipse shaped like the canvas (at 100% it
 * touches the canvas edges), so wide panels get lines all around, not only at the sides.
 */
class RadialLineFilter : Filter("draw.radial_line", "Radial Line", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center", 0.5f, 0.5f),
        FilterParam.Slider("count", "Number of lines", 8f, 720f, 180f, 1f),
        FilterParam.Slider("thickness", "Thickness", 0.5f, 120f, 12f, 0.5f, pixels = true),
        FilterParam.Slider("thickness_var", "Thickness variation", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("inner", "Clear area size", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("jitter", "Ragged edge", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("oval", "Clear area width", 25f, 400f, 100f, 1f, "%"),
        FilterParam.Slider("spacing_var", "Spacing variation", 0f, 100f, 70f, 1f, "%"),
        FilterParam.Color("color", "Color", 0xFF000000.toInt(), useDrawingColor = true),
        DrawBlend.opacityParam(),
        FilterParam.Seed(),
    )

    /**
     * Line set in angle order; angles are relative to [base] and increase with the index.
     * [maxSinHalfAngle] bounds sin of the angular half-width of every wedge anywhere on the canvas.
     */
    internal class Lines(
        val base: Double, val step: Double,
        val cos: DoubleArray, val sin: DoubleArray,
        val startR: DoubleArray, val halfSlope: DoubleArray, val minStart: Double,
        val maxSinHalfAngle: Double,
    ) { val size get() = cos.size }

    internal fun buildLines(values: FilterValues, w: Int, h: Int, cx: Double, cy: Double, ctx: FilterContext): Lines {
        val n = values.int("count").coerceIn(1, 5000)
        val rnd = Random(values.seed())
        // Farthest canvas corner: every line reaches its full thickness there.
        val far = max(1e-3, max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy))))
        val inner = values.float("inner").coerceIn(0f, 100f) / 100.0
        val jitter = values.float("jitter").coerceIn(0f, 100f) / 100.0 * JITTER_SPAN
        // Semi-axes of the clear ellipse at 100%: the canvas' half extents, reshaped by "Clear
        // area width" without changing the area.
        val ratio = sqrt((values.float("oval") / 100.0).coerceIn(0.05, 20.0))
        val ax = 0.5 * w * ratio; val ay = 0.5 * h / ratio
        val thick = ctx.px(values.float("thickness")).toDouble().coerceAtLeast(0.0)
        val thickVar = values.float("thickness_var").coerceIn(0f, 100f) / 100.0
        val spaceVar = values.float("spacing_var").coerceIn(0f, 100f) / 100.0 * 0.9
        val base = rnd.nextDouble() * 2 * PI
        val step = 2 * PI / n
        val cs = DoubleArray(n); val sn = DoubleArray(n); val rs = DoubleArray(n); val slope = DoubleArray(n)
        var minStart = Double.MAX_VALUE
        var maxHalf = 0.0
        for (i in 0 until n) {
            val phi = base + (i + 0.5 + (rnd.nextDouble() - 0.5) * spaceVar) * step
            val c = cos(phi); val s = sin(phi)
            // Radius of the clear ellipse in this direction.
            val oval = 1.0 / sqrt((c / ax) * (c / ax) + (s / ay) * (s / ay))
            val start = (inner + jitter * rnd.nextDouble().pow(1.5)) * oval
            val width = thick * (1.0 - thickVar * rnd.nextDouble())
            cs[i] = c; sn[i] = s; rs[i] = start
            slope[i] = 0.5 * width / max(1.0, far - start)
            minStart = min(minStart, start)
            // Half width / distance grows along the wedge and peaks at the far corner.
            maxHalf = max(maxHalf, max(0.0, far - start) * slope[i] / far)
        }
        return Lines(base, step, cs, sn, rs, slope, minStart, maxHalf)
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val opacity = (values.float("opacity") / 100f).coerceIn(0f, 1f)
        if (opacity <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val p = values.point("center")
        val cx = p[0].toDouble() * w; val cy = p[1].toDouble() * h
        val lines = buildLines(values, w, h, cx, cy, ctx)
        val n = lines.size
        val color = values.color("color")
        val clear2 = max(0.0, lines.minStart - 1.0).let { it * it }
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
            val r2 = dx * dx + dy * dy
            if (r2 <= clear2) return@mapXY c
            // A wedge touches this pixel only if its axis is within half its width plus one
            // pixel of antialiasing: sin(angle) < maxSinHalfAngle + 1/r. tan(asin s) >= asin s
            // gives a cheap upper bound of that angle; +1.5 slots covers the spacing jitter.
            val s = lines.maxSinHalfAngle + 1.0 / sqrt(r2)
            val angle = if (s >= 0.7) PI / 2 else s / sqrt(1.0 - s * s)
            val reach = min(n / 2, ceil(angle / lines.step + 1.5).toInt())
            var rel = atan2(dy, dx) - lines.base
            rel -= 2 * PI * floor(rel / (2 * PI))
            val k0 = (rel / lines.step).toInt()
            var cov = 0f
            for (k in k0 - reach..k0 + reach) {
                val i = ((k % n) + n) % n
                val along = dx * lines.cos[i] + dy * lines.sin[i]
                val grow = along - lines.startR[i]
                if (grow <= 0.0) continue
                val perp = abs(dx * lines.sin[i] - dy * lines.cos[i])
                val cv = Coverage.line((grow * lines.halfSlope[i]).toFloat(), perp.toFloat())
                if (cv > cov) cov = cv
            }
            if (cov > 0f) DrawBlend.composite(c, color, cov * opacity, DrawBlend.NORMAL) else c
        }
    }

    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

    private companion object {
        /** "Ragged edge" at 100% lets a line start up to this fraction of the ellipse radius later. */
        const val JITTER_SPAN = 0.6
    }
}

/**
 * Manga speed lines: parallel streaks of random length, thickness and position running at the
 * given angle across the whole canvas, tapered to points.
 */
class SpeedLineFilter : Filter("draw.speed_line", "Speed Line", FilterCategory.DRAW) {
    override val generatesContent = true

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("angle", "Angle", 0f, 360f, 0f, 1f, "°"),
        FilterParam.Slider("density", "Density", 1f, 100f, 65f, 1f, "%"),
        FilterParam.Slider("thickness", "Thickness", 0.5f, 60f, 6f, 0.5f, pixels = true),
        FilterParam.Slider("thickness_var", "Thickness variation", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("length", "Length", 5f, 150f, 45f, 1f, "%"),
        FilterParam.Slider("length_var", "Length variation", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("gap", "Gaps", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Choice("taper", "Taper", TAPERS, TAPER_BOTH),
        FilterParam.Color("color", "Color", 0xFF000000.toInt(), useDrawingColor = true),
        DrawBlend.opacityParam(),
        FilterParam.Seed(),
    )

    /**
     * Streaks grouped by lane (CSR layout): lane `l` owns indices laneStart[l] until
     * laneStart[l + 1], sorted by [u0] and non-overlapping along u. Each streak has its own
     * lateral position [v] within half a lane spacing of the lane center.
     */
    internal class Streaks(
        val laneStart: IntArray,
        val u0: DoubleArray, val u1: DoubleArray, val v: DoubleArray, val halfW: FloatArray,
        val vMin: Double, val spacing: Double, val maxHalf: Double,
    ) { val lanes: Int get() = laneStart.size - 1 }

    internal fun buildStreaks(values: FilterValues, w: Int, h: Int, ctx: FilterContext): Streaks {
        val ang = Math.toRadians(values.float("angle").toDouble())
        val dxu = cos(ang); val dyu = sin(ang)
        val hw = w * 0.5; val hh = h * 0.5
        // Extents of the canvas in the rotated frame (u along the lines, v across).
        val uExt = abs(dxu) * hw + abs(dyu) * hh
        val vExt = abs(dyu) * hw + abs(dxu) * hh
        val thick = ctx.px(values.float("thickness")).toDouble().coerceAtLeast(1e-3)
        val thickVar = values.float("thickness_var").coerceIn(0f, 100f) / 100.0
        // Lane spacing relative to the thickness: 100% density packs lanes 1.2 widths apart.
        val density = values.float("density").coerceIn(0f, 100f) / 100.0
        val lanes = ceil(2 * vExt / (thick * (1.2 + 8.0 * (1.0 - density)))).toInt().coerceIn(1, 20000)
        val spacing = max(1e-3, 2 * vExt / lanes)
        // Every length below is relative to the canvas (no absolute pixel floors), and each lane
        // draws from its own random stream: the preview (a downscaled buffer whose size is
        // rounded) then produces the same streaks as the full-resolution apply.
        val length = max(1e-3, values.float("length").coerceIn(1f, 1000f) / 100.0 * 2 * uExt)
        val lenVar = values.float("length_var").coerceIn(0f, 100f) / 100.0
        val gap = values.float("gap").coerceIn(0f, 100f) / 100.0 * length
        val seed = values.seed()
        val laneStart = IntArray(lanes + 1)
        val u0 = DoubleList(); val u1 = DoubleList(); val vs = DoubleList(); val half = DoubleList()
        for (l in 0 until lanes) {
            laneStart[l] = u0.size
            val rnd = Random(Noise.hash(l, LANE_SALT, seed))
            val center = -vExt + (l + 0.5) * spacing
            var u = -uExt - rnd.nextDouble() * length
            while (u < uExt) {
                val len = length * max(MIN_LENGTH, 1.0 - lenVar * rnd.nextDouble())
                u0.add(u); u1.add(u + len)
                vs.add(center + (rnd.nextDouble() - 0.5) * LATERAL_JITTER * spacing)
                half.add(0.5 * thick * (1.0 - thickVar * rnd.nextDouble()))
                u += len + gap * (0.2 + 1.6 * rnd.nextDouble())
            }
        }
        laneStart[lanes] = u0.size
        val halfW = half.toArray().let { d -> FloatArray(d.size) { d[it].toFloat() } }
        return Streaks(laneStart, u0.toArray(), u1.toArray(), vs.toArray(), halfW, -vExt, spacing, 0.5 * thick)
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val opacity = (values.float("opacity") / 100f).coerceIn(0f, 1f)
        if (opacity <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val st = buildStreaks(values, w, h, ctx)
        val ang = Math.toRadians(values.float("angle").toDouble())
        val dxu = cos(ang); val dyu = sin(ang)
        val taper = values.choice("taper")
        val color = values.color("color")
        val lanes = st.lanes
        // Lanes whose streaks (offset up to half the jitter) can touch a pixel, plus AA.
        val margin = st.maxHalf + 1.0 + 0.5 * LATERAL_JITTER * st.spacing
        val reach = min(16, ceil(margin / st.spacing).toInt() + 1)
        val cx = w * 0.5; val cy = h * 0.5
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val px = x + 0.5 - cx; val py = y + 0.5 - cy
            val u = px * dxu + py * dyu
            val v = -px * dyu + py * dxu
            val l0 = floor((v - st.vMin) / st.spacing).toInt()
            var cov = 0f
            for (l in max(0, l0 - reach)..min(lanes - 1, l0 + reach)) {
                val from = st.laneStart[l]; val to = st.laneStart[l + 1]
                // Last streak starting before u + 0.5 (end-cap antialiasing margin).
                var lo = from; var hi = to - 1; var j = from - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (st.u0[mid] <= u + 0.5) { j = mid; lo = mid + 1 } else hi = mid - 1
                }
                for (k in max(from, j - 1)..j) {
                    val cv = streakCoverage(st, k, u, v, taper)
                    if (cv > cov) cov = cv
                }
            }
            if (cov > 0f) DrawBlend.composite(c, color, cov * opacity, DrawBlend.NORMAL) else c
        }
    }

    private fun streakCoverage(st: Streaks, k: Int, u: Double, v: Double, taper: Int): Float {
        val a = st.u0[k]; val b = st.u1[k]
        if (u < a - 0.5 || u > b + 0.5) return 0f
        val dist = abs(v - st.v[k]).toFloat()
        if (dist > st.halfW[k] + 1f) return 0f
        val s = ((u - a) / (b - a)).coerceIn(0.0, 1.0)
        val profile = when (taper) {
            TAPER_BOTH -> sin(PI * s)
            TAPER_END -> sqrt(min(1.0, s * 8.0)) * (1.0 - s)
            else -> 1.0
        }
        val cap = (min(u - a, b - u) + 0.5).coerceIn(0.0, 1.0).toFloat()
        return Coverage.line((st.halfW[k] * profile).toFloat(), dist) * cap
    }

    internal companion object {
        const val TAPER_BOTH = 0
        const val TAPER_END = 1
        const val TAPER_NONE = 2
        val TAPERS = listOf("Both ends", "Toward the end", "None")
        /** Lateral scatter of each streak within its lane, as a fraction of the lane spacing. */
        const val LATERAL_JITTER = 0.9
        /** Shortest streak as a fraction of the mean length (bounds the streak count per lane). */
        const val MIN_LENGTH = 0.02
        private const val LANE_SALT = 0x51EED
    }
}

/** Minimal growable double list (avoids boxing while generating lines). */
internal class DoubleList {
    private var data = DoubleArray(256)
    var size = 0; private set
    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun toArray(): DoubleArray = data.copyOf(size)
}
