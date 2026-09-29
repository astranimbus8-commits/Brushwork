package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import java.lang.ref.SoftReference
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Makes the background transparent. "Automatic" uses the on-device subject segmentation
 * (ctx.services) when available; otherwise, or with "Background color", the background is
 * estimated from the colors along the image border and similar colors connected to the border
 * are removed with soft edges. The layer's RGB is kept; only alpha changes.
 */
class BackgroundRemovalFilter : Filter("ai.background_removal", "Background Removal", FilterCategory.AI) {

    override val params: List<FilterParam> = listOf(
        FilterParam.Choice("method", "Detection", listOf("Automatic (subject)", "Background color"), METHOD_AUTO),
        FilterParam.Slider("threshold", "Threshold", 1f, 99f, 40f, 1f, "%"),
        FilterParam.Slider("softness", "Edge softness", 0f, 20f, 1f, 0.5f, pixels = true),
        FilterParam.Slider("shift", "Edge shift", -20f, 20f, 0f, 0.5f, pixels = true),
        FilterParam.Toggle("contiguous", "Only colors connected to the border", true),
        FilterParam.Toggle("invert", "Invert (remove the subject)", false),
    )

    private class CachedMask(val w: Int, val h: Int, val hash: Long, val mask: FloatArray)

    /**
     * Last preview-sized segmentation, keyed by content (parameter changes reuse it). Softly
     * referenced: this filter instance lives as long as the app, the mask can be megabytes.
     */
    @Volatile private var cache: SoftReference<CachedMask>? = null

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val threshold = values.float("threshold").coerceIn(0f, 100f) / 100f
        val subject = if (values.choice("method") == METHOD_AUTO) subjectMask(src, ctx) else null
        ctx.checkCancelled()
        val fg: FloatArray = if (subject != null) {
            val band = 0.06f
            FloatArray(w * h) { Coverage.smoothstep(threshold - band, threshold + band, subject[it]) }
        } else {
            colorKeyForeground(src, threshold, values.bool("contiguous"), ctx)
        }
        ctx.progress(0.6f)
        val shift = ctx.px(values.float("shift"))
        if (shift >= 0.25f || shift <= -0.25f) shiftEdge(fg, w, h, shift, ctx)
        val soft = ctx.px(values.float("softness"))
        if (soft >= 0.3f) feather(fg, w, h, soft, ctx)
        val invert = values.bool("invert")
        val out = PixelBuffer(w, h)
        val s = src.pixels; val d = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * w until y1 * w) {
                val c = s[i]
                val keep = if (invert) 1f - fg[i] else fg[i]
                val a = ((c ushr 24) * keep.coerceIn(0f, 1f) + 0.5f).toInt()
                d[i] = if (a <= 0) 0 else (c and 0xFFFFFF) or (a shl 24)
            }
        }
        return out
    }

    /** Subject confidence from the segmentation service (cached for preview-sized images). */
    private fun subjectMask(src: PixelBuffer, ctx: FilterContext): FloatArray? {
        val services = ctx.services ?: return null
        // Only preview-sized results are cached: the full-resolution one is used once.
        val cacheable = src.size <= CACHE_MAX_PIXELS
        val hash = if (cacheable) contentHash(src.pixels) else 0L
        if (cacheable) cache?.get()?.let { c ->
            if (c.w == src.width && c.h == src.height && c.hash == hash) return c.mask
        }
        val mask = try {
            services.subjectMask(src)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        if (mask.size != src.size) return null
        if (cacheable) cache = SoftReference(CachedMask(src.width, src.height, hash, mask))
        return mask
    }

    /** 64-bit FNV-1a style hash over every pixel (a few ms for a preview-sized buffer). */
    private fun contentHash(p: IntArray): Long {
        var h = -3750763034362895579L
        for (v in p) { h = (h xor v.toLong()) * 1099511628211L }
        return h
    }

    // ------------------------------------------------------------------ color-key heuristic

    /**
     * Foreground coverage from background colors sampled along the border: pixels within the
     * color tolerance of a border color (and, if [contiguous], connected to the border through
     * such pixels) are background, with a soft band just beyond the tolerance.
     */
    internal fun colorKeyForeground(src: PixelBuffer, threshold: Float, contiguous: Boolean, ctx: FilterContext): FloatArray {
        val w = src.width; val h = src.height; val n = w * h
        val centers = borderColors(src)
        val fg = FloatArray(n) { 1f }
        if (centers.isEmpty()) {
            // Border fully transparent: nothing to key on, only transparent pixels count as background.
            for (i in 0 until n) if (src.pixels[i] ushr 24 < ALPHA_EMPTY) fg[i] = 0f
            return fg
        }
        val tol = 2f + threshold * 50f           // Delta E
        val band = max(2f, tol * 0.35f)
        // Distance to the nearest background color, quantized to 1/4 Delta E in a byte plane.
        val dist = ByteArray(n)
        val px = src.pixels
        val k = centers.size / 3
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val lab = FloatArray(3)
            for (i in y0 * w until y1 * w) {
                val c = px[i]
                if (c ushr 24 < ALPHA_EMPTY) { dist[i] = 0; continue }
                Lab.fromRgb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, lab)
                var best = Float.MAX_VALUE
                for (ci in 0 until k) {
                    val dl = lab[0] - centers[ci * 3]; val da = lab[1] - centers[ci * 3 + 1]; val db = lab[2] - centers[ci * 3 + 2]
                    best = min(best, dl * dl + da * da + db * db)
                }
                dist[i] = min(255f, sqrt(best) * 4f).toInt().toByte()
            }
        }
        val limit = min(255, ((tol + band) * 4f).toInt())
        val reached = if (contiguous) floodFromBorder(dist, w, h, limit, ctx) else null
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * w until y1 * w) {
                if (reached != null && reached[i].toInt() == 0) continue
                val d = (dist[i].toInt() and 0xFF) / 4f
                fg[i] = Coverage.smoothstep(tol, tol + band, d)
            }
        }
        return fg
    }

    /**
     * Lab centers (k*3) of the background colors found along the border; empty if the border is
     * transparent. A color counts as background when it occurs on at least three of the four
     * sides (a subject cut off by one or two edges does not), or when it is the most common one.
     */
    internal fun borderColors(src: PixelBuffer): FloatArray {
        val w = src.width; val h = src.height
        val perimeter = if (w == 1 || h == 1) w * h else 2 * (w + h) - 4
        val stride = max(1, perimeter / 4000)
        val samples = ArrayList<FloatArray>()
        val sides = ArrayList<Int>()
        var idx = 0
        fun visit(x: Int, y: Int, side: Int) {
            if (idx++ % stride != 0) return
            val c = src[x, y]
            if (c ushr 24 < ALPHA_EMPTY) return
            val lab = FloatArray(3)
            Lab.fromRgb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, lab)
            samples += lab
            sides += side
        }
        for (x in 0 until w) visit(x, 0, 0)
        if (h > 1) for (x in 0 until w) visit(x, h - 1, 1)
        for (y in 1 until h - 1) { visit(0, y, 2); if (w > 1) visit(w - 1, y, 3) }
        if (samples.isEmpty()) return FloatArray(0)
        val k = min(MAX_CLUSTERS, samples.size)
        // Farthest-point initialisation, then a few Lloyd iterations.
        val centers = ArrayList<FloatArray>(k)
        centers += samples[0].copyOf()
        val best = FloatArray(samples.size) { Float.MAX_VALUE }
        while (centers.size < k) {
            var far = 0; var farD = -1f
            for (i in samples.indices) {
                best[i] = min(best[i], d2(samples[i], centers.last()))
                if (best[i] > farD) { farD = best[i]; far = i }
            }
            if (farD < 4f) break // remaining samples are all within 2 Delta E of a center
            centers += samples[far].copyOf()
        }
        val counts = IntArray(centers.size)
        val label = IntArray(samples.size)
        repeat(6) {
            val sums = Array(centers.size) { FloatArray(3) }
            counts.fill(0)
            for ((si, s) in samples.withIndex()) {
                var bi = 0; var bd = Float.MAX_VALUE
                for (ci in centers.indices) { val d = d2(s, centers[ci]); if (d < bd) { bd = d; bi = ci } }
                label[si] = bi
                counts[bi]++
                for (c in 0..2) sums[bi][c] += s[c]
            }
            for (ci in centers.indices) if (counts[ci] > 0) for (c in 0..2) centers[ci][c] = sums[ci][c] / counts[ci]
        }
        // Per cluster and side: how many samples; a side "has" a cluster from 4% of its samples.
        val perSide = Array(centers.size) { IntArray(4) }
        val sideTotal = IntArray(4)
        for (si in samples.indices) { perSide[label[si]][sides[si]]++; sideTotal[sides[si]]++ }
        val largest = counts.indices.maxByOrNull { counts[it] } ?: 0
        val kept = centers.indices.filter { ci ->
            val onSides = (0..3).count { s -> sideTotal[s] > 0 && perSide[ci][s] >= max(1, (sideTotal[s] * 0.04f).toInt()) }
            ci == largest || onSides >= 3
        }
        return FloatArray(kept.size * 3) { centers[kept[it / 3]][it % 3] }
    }

    private fun d2(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return x * x + y * y + z * z
    }

    /**
     * Scanline flood fill from every border pixel whose distance code is below [limit], through
     * 4-connected pixels below [limit]. Returns 1 for reached pixels.
     */
    internal fun floodFromBorder(dist: ByteArray, w: Int, h: Int, limit: Int, ctx: FilterContext): ByteArray {
        val seen = ByteArray(w * h)
        fun open(i: Int) = seen[i].toInt() == 0 && (dist[i].toInt() and 0xFF) < limit
        var stack = IntArray(1024)
        var sp = 0
        fun push(x: Int, y: Int) {
            if (sp + 2 > stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = x; stack[sp++] = y
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        var spans = 0
        while (sp > 0) {
            val y = stack[--sp]; val x = stack[--sp]
            val row = y * w
            if (!open(row + x)) continue
            var xl = x; var xr = x
            while (xl > 0 && open(row + xl - 1)) xl--
            while (xr < w - 1 && open(row + xr + 1)) xr++
            for (xx in xl..xr) seen[row + xx] = 1
            for (side in 0..1) {
                val ny = if (side == 0) y - 1 else y + 1
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var xx = xl
                while (xx <= xr) {
                    if (open(nrow + xx)) {
                        push(xx, ny)
                        while (xx <= xr && open(nrow + xx)) xx++
                    } else xx++
                }
            }
            if (++spans and 0xFFF == 0) ctx.checkCancelled()
        }
        return seen
    }

    // ------------------------------------------------------------------ mask refinement

    /** Grows (positive) or shrinks (negative) the foreground by |[shift]| pixels, antialiased. */
    private fun shiftEdge(fg: FloatArray, w: Int, h: Int, shift: Float, ctx: FilterContext) {
        if (shift > 0f) {
            val d = DistanceField.compute(w, h, ctx) { fg[it] >= 0.5f }
            for (i in fg.indices) fg[i] = max(fg[i], (shift + 0.5f - d[i]).coerceIn(0f, 1f))
        } else {
            val s = -shift
            val d = DistanceField.compute(w, h, ctx) { fg[it] < 0.5f }
            for (i in fg.indices) fg[i] = min(fg[i], (d[i] - s + 0.5f).coerceIn(0f, 1f))
        }
    }

    /** Softens the mask edge with two box passes (a tent of radius ~[radius]) in place. */
    private fun feather(fg: FloatArray, w: Int, h: Int, radius: Float, ctx: FilterContext) {
        val r = max(1, (radius * 0.5f + 0.5f).toInt())
        repeat(2) {
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                val line = FloatArray(w)
                for (y in y0 until y1) boxLine(fg, y * w, 1, w, r, line)
            }
            Parallel.forRange(w, 4) { x0, x1 ->
                ctx.checkCancelled()
                val line = FloatArray(h)
                for (x in x0 until x1) boxLine(fg, x, w, h, r, line)
            }
        }
    }

    /** Box blur of [n] samples of [a] starting at [off] with stride [st], edges clamped. */
    private fun boxLine(a: FloatArray, off: Int, st: Int, n: Int, r: Int, line: FloatArray) {
        for (i in 0 until n) line[i] = a[off + i * st]
        var acc = 0f
        for (k in -r..r) acc += line[min(n - 1, max(0, k))]
        val norm = 1f / (2 * r + 1)
        for (i in 0 until n) {
            a[off + i * st] = acc * norm
            acc += line[min(n - 1, i + r + 1)] - line[max(0, i - r)]
        }
    }

    internal companion object {
        const val METHOD_AUTO = 0
        const val METHOD_COLOR = 1
        const val ALPHA_EMPTY = 16
        const val MAX_CLUSTERS = 6
        const val CACHE_MAX_PIXELS = 4_000_000
    }
}

/** Exact Euclidean distance transform (Felzenszwalb & Huttenlocher) from a predicate. */
internal object DistanceField {
    private const val INF = 1e20f

    /** Distance in pixels from each pixel to the nearest pixel where [inside] is true. */
    fun compute(w: Int, h: Int, ctx: FilterContext, inside: (Int) -> Boolean): FloatArray {
        val f = FloatArray(w * h)
        Parallel.forRows(h) { y0, y1 -> for (i in y0 * w until y1 * w) f[i] = if (inside(i)) 0f else INF }
        Parallel.forRange(w, 4) { x0, x1 ->
            ctx.checkCancelled()
            val col = FloatArray(h); val d = FloatArray(h); val v = IntArray(h); val z = FloatArray(h + 1)
            for (x in x0 until x1) {
                for (y in 0 until h) col[y] = f[y * w + x]
                edt(col, h, d, v, z)
                for (y in 0 until h) f[y * w + x] = d[y]
            }
        }
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val row = FloatArray(w); val d = FloatArray(w); val v = IntArray(w); val z = FloatArray(w + 1)
            for (y in y0 until y1) {
                val off = y * w
                for (x in 0 until w) row[x] = f[off + x]
                edt(row, w, d, v, z)
                for (x in 0 until w) f[off + x] = sqrt(d[x])
            }
        }
        return f
    }

    private fun edt(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val vk = v[k]
                s = ((f[q] + q.toFloat() * q) - (f[vk] + vk.toFloat() * vk)) / (2f * (q - vk))
                if (s <= z[k] && k > 0) k-- else break
            }
            k++
            v[k] = q; z[k] = s; z[k + 1] = Float.MAX_VALUE
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toFloat()
            d[q] = dq * dq + f[v[k]]
        }
    }
}
