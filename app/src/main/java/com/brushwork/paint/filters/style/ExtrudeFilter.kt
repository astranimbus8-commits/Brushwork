package com.brushwork.paint.filters.style

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Parallel extrusion (ibisPaint "Extrude (Parallel)"): the silhouette is swept along a direction
 * into solid side walls placed behind the layer, a 3D block-letter look. Walls are shaded by depth
 * (farther = darker) and by how the silhouette edge they grow from faces the light.
 */
class ExtrudeParallelFilter : Filter("style.extrude_parallel", "Extrude (Parallel)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("angle", "Angle", 0f, 360f, 315f, 1f, "°"),
        FilterParam.Slider("depth", "Depth", 1f, 1000f, 30f, 1f, pixels = true),
        FilterParam.Choice("side_color", "Side color", listOf("Custom color", "Layer colors"), 0),
        FilterParam.Color("color", "Color", 0xFF5A5A5A.toInt()),
        FilterParam.Slider("depth_shading", "Depth shading", 0f, 100f, 40f, 1f, "%"),
        FilterParam.Slider("side_shading", "Side shading", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("light_angle", "Light angle", 0f, 360f, 135f, 1f, "°"),
        StyleMath.outputParam("Extrusion behind layer", "Extrusion only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val dir = StyleMath.direction(values.float("angle"))
        val depth = ctx.px(values.float("depth").coerceAtLeast(0f)).coerceAtMost(1.0e5f)
        val light = StyleMath.direction(values.float("light_angle"))
        val bb = StyleMath.contentBounds(src) ?: return src.copy()
        val ex = dir[0] * depth; val ey = dir[1] * depth
        val l = min(bb[0], floor(bb[0] + ex).toInt()) - 2
        val t = min(bb[1], floor(bb[1] + ey).toInt()) - 2
        val r = max(bb[2], ceil(bb[2] + ex).toInt()) + 2
        val b = max(bb[3], ceil(bb[3] + ey).toInt()) + 2
        return StyleMath.cropped(src, l, t, r, b) { img, _, _ ->
            ExtrudeSweep(
                img = img, dirX = dir[0], dirY = dir[1], depth = depth,
                customColor = values.color("color"),
                layerColors = values.choice("side_color") == 1,
                depthShade = StyleMath.percent(values.float("depth_shading")).coerceIn(0f, 1f),
                sideShade = StyleMath.percent(values.float("side_shading")).coerceIn(0f, 1f),
                lightX = light[0], lightY = light[1],
                only = values.choice("output") == 1,
            ).run(ctx)
        }
    }
}

/**
 * O(n) extrusion: the image is walked along digital lines parallel to the extrusion direction
 * (a shear, sampled with linear interpolation across lines), each line keeps a sliding-window
 * maximum of the alpha over the last `depth` pixels (monotonic deque), and results are sheared back
 * with one more linear interpolation. Only two line buffers per worker are kept in memory.
 */
private class ExtrudeSweep(
    val img: PixelBuffer,
    dirX: Float,
    dirY: Float,
    val depth: Float,
    val customColor: Int,
    val layerColors: Boolean,
    val depthShade: Float,
    val sideShade: Float,
    val lightX: Float,
    val lightY: Float,
    val only: Boolean,
) {
    private val w = img.width
    private val h = img.height
    private val px = img.pixels
    private val majorX = abs(dirX) >= abs(dirY)
    private val nu = if (majorX) w else h
    private val nv = if (majorX) h else w
    private val forward = (if (majorX) dirX else dirY) > 0f
    private val slope = ((if (majorX) dirY else dirX) / (if (majorX) dirX else dirY)).toDouble()
    private val stepLen = sqrt(1.0 + slope * slope).toFloat()
    private val maxAge = depth + 1f

    private class Line(n: Int) {
        val cov = FloatArray(n)
        val k = FloatArray(n)
        val shade = FloatArray(n)
        val color = IntArray(n)
    }

    private class Deque(n: Int) {
        val pos = IntArray(n)
        val value = FloatArray(n)
        val shade = FloatArray(n)
        val color = IntArray(n)
    }

    fun run(ctx: FilterContext): PixelBuffer {
        val out = if (only) PixelBuffer(w, h) else img.copy()
        val span = slope * (nu - 1)
        val rLo = floor(-max(0.0, span)).toInt()
        val rHi = floor((nv - 1) - min(0.0, span)).toInt() + 1
        val count = rHi - rLo
        Parallel.forRange(count, 8) { c0, c1 ->
            ctx.checkCancelled()
            var prev = Line(nu)
            var cur = Line(nu)
            val dq = Deque(nu)
            computeLine(rLo + c0, prev, dq)
            for (c in c0 until c1) {
                if ((c - c0) and 63 == 63) ctx.checkCancelled()
                val r = rLo + c + 1
                computeLine(r, cur, dq)
                emit(r, prev, cur, out.pixels)
                val tmp = prev; prev = cur; cur = tmp
            }
        }
        return out
    }

    private fun index(u: Int, v: Int): Int = if (majorX) v * w + u else u * w + v

    private fun alphaAt(u: Int, v: Int): Float = if (v < 0 || v >= nv) 0f else (px[index(u, v)] ushr 24) / 255f

    private fun alphaXY(x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= w || y >= h) 0f else (px[y * w + x] ushr 24) / 255f

    /** Outward silhouette normal at pixel (u, v) dotted with the light direction, in -1..1. */
    private fun wallShade(u: Int, v: Int): Float {
        val x = if (majorX) u else v
        val y = if (majorX) v else u
        val gx = (alphaXY(x + 1, y - 1) + 2f * alphaXY(x + 1, y) + alphaXY(x + 1, y + 1)) -
            (alphaXY(x - 1, y - 1) + 2f * alphaXY(x - 1, y) + alphaXY(x - 1, y + 1))
        val gy = (alphaXY(x - 1, y + 1) + 2f * alphaXY(x, y + 1) + alphaXY(x + 1, y + 1)) -
            (alphaXY(x - 1, y - 1) + 2f * alphaXY(x, y - 1) + alphaXY(x + 1, y - 1))
        val len = sqrt(gx * gx + gy * gy)
        if (len < 1e-3f) return 0f
        return -(gx * lightX + gy * lightY) / len
    }

    private fun cap(k: Float): Float = (depth - k + 0.5f).coerceIn(0f, 1f)

    /** Sliding-window maximum along sheared line [r] (positions v = r + slope * u). */
    private fun computeLine(r: Int, line: Line, dq: Deque) {
        var head = 0
        var tail = 0
        for (m in 0 until nu) {
            val u = if (forward) m else nu - 1 - m
            val v = r + slope * u
            val vf = floor(v)
            val v0 = vf.toInt()
            val f = (v - vf).toFloat()
            val a0 = alphaAt(u, v0)
            val a1 = alphaAt(u, v0 + 1)
            val a = a0 + (a1 - a0) * f
            if (a > 0.002f) {
                while (tail > head && dq.value[tail - 1] <= a) tail--
                val pv = if (a1 * f > a0 * (1f - f)) v0 + 1 else v0
                dq.pos[tail] = m
                dq.value[tail] = a
                dq.shade[tail] = wallShade(u, pv)
                dq.color[tail] = if (layerColors) px[index(u, pv)] else 0
                tail++
            }
            while (tail > head && (m - dq.pos[head]) * stepLen > maxAge) head++
            if (tail > head) {
                var best = head
                var bestCov = dq.value[head] * cap((m - dq.pos[head]) * stepLen)
                if (tail > head + 1) {
                    val c2 = dq.value[head + 1] * cap((m - dq.pos[head + 1]) * stepLen)
                    if (c2 > bestCov) { best = head + 1; bestCov = c2 }
                }
                line.cov[u] = bestCov
                line.k[u] = (m - dq.pos[best]) * stepLen
                line.shade[u] = dq.shade[best]
                line.color[u] = dq.color[best]
            } else {
                line.cov[u] = 0f
            }
        }
    }

    /** Writes every pixel lying between sheared lines r-1 and r (exactly one per u). */
    private fun emit(r: Int, prev: Line, cur: Line, out: IntArray) {
        val colorA = (customColor ushr 24) / 255f
        for (u in 0 until nu) {
            val t = (r - 1) + slope * u
            val vi = ceil(t).toInt()
            if (vi < 0 || vi >= nv) continue
            val f = (vi - t).toFloat()
            val c0 = prev.cov[u]; val c1 = cur.cov[u]
            val w0 = c0 * (1f - f); val w1 = c1 * f
            val cov = w0 + w1
            if (cov <= 0.002f) continue
            val inv = 1f / cov
            val k = (prev.k[u] * w0 + cur.k[u] * w1) * inv
            val shade = (prev.shade[u] * w0 + cur.shade[u] * w1) * inv
            val base = if (layerColors) (if (w1 > w0) cur.color[u] else prev.color[u]) else customColor
            val factor = (1f - depthShade * 0.7f * (k / max(depth, 1e-3f)).coerceIn(0f, 1f)) * (1f + sideShade * 0.45f * shade)
            val side = StyleMath.pack(
                1f,
                ((base shr 16) and 0xFF) * factor,
                ((base shr 8) and 0xFF) * factor,
                (base and 0xFF) * factor,
            )
            val a = cov.coerceIn(0f, 1f) * (if (layerColors) 1f else colorA)
            val i = index(u, vi)
            out[i] = if (only) StyleMath.solid(side, a) else StyleMath.behind(px[i], side, a)
        }
    }
}
