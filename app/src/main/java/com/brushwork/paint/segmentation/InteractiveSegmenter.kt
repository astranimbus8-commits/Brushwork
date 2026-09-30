package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the user pointed at: one tap, or a scribble (a polyline over the object), in the
 * coordinates of the image being segmented. [points] = x0, y0, x1, y1, ...
 */
class ObjectPrompt(val points: FloatArray) {
    init {
        require(points.size >= 2 && points.size % 2 == 0) { "need at least one point" }
    }

    val count: Int get() = points.size / 2
    fun x(i: Int) = points[i * 2]
    fun y(i: Int) = points[i * 2 + 1]

    /** The same prompt scaled by (sx, sy). */
    fun scaled(sx: Float, sy: Float): ObjectPrompt = ObjectPrompt(FloatArray(points.size) { if (it % 2 == 0) points[it] * sx else points[it] * sy })

    companion object {
        fun tap(x: Float, y: Float) = ObjectPrompt(floatArrayOf(x, y))
    }
}

/**
 * Tap-to-select with a prompted segmentation model (MagicTouch: RGB + a prior channel with the
 * tap drawn in it -> object probability), pure Kotlin around an injected [InteractiveModel]:
 *
 * 1. A first pass on the whole image (aspect clamped to 2:1 around the tap, stretched to the
 *    square model input, like MediaPipe feeds whole frames).
 * 2. The connected object under the tap is kept; a second pass zooms on its bounding box (from
 *    the full-resolution image when it has more detail), which gives the model 2-8x more pixels
 *    on the object; the zoomed answer replaces the first inside the box, feathered at its sides.
 * 3. Again only the object under the tap is kept (soft edges preserved).
 *
 * The result is a soft probability at WORKING resolution; edge refinement (matting, color
 * guided upsampling) happens in [SegmentationPipeline].
 */
internal object InteractiveSegmenter {
    const val MODEL_SIZE = 512

    /** Radius (model pixels) of the disc drawn for a tap (MediaPipe draws ~1-2 px). */
    const val PRIOR_RADIUS = 2f

    /** Longest aspect ratio fed to the model (longer images are cropped around the tap). */
    const val MAX_ASPECT = 2f

    /** The zoom pass runs when it magnifies the object at least this much. */
    const val MIN_ZOOM_GAIN = 1.4f

    /** A crop rectangle in working-image pixels (may extend past the image: edges repeat). */
    class Crop(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
        val long get() = max(width, height)
        override fun toString() = "Crop($x0,$y0 - $x1,$y1)"
    }

    /**
     * Object probability (size w*h of [work]) for [prompt] (working coordinates), or null if the
     * model gave no usable answer. [full] is the full-resolution original of [work] (may be the
     * same object). An all-zero array means "no object under the tap".
     */
    fun segment(work: PixelBuffer, full: PixelBuffer, prompt: ObjectPrompt, model: InteractiveModel, checkCancelled: () -> Unit): FloatArray? {
        val w = work.width; val h = work.height
        val (cx, cy) = center(prompt)
        val first = firstCrop(w, h, cx, cy)
        val p1 = pass(work, full, first, prompt, model) ?: return null
        checkCancelled()
        val comp = componentAt(p1, w, h, prompt) ?: return FloatArray(w * h)
        val zoom = zoomCrop(comp, w, h)
        var merged = p1
        if (zoom != null && first.long.toFloat() / zoom.long >= MIN_ZOOM_GAIN) {
            val p2 = pass(work, full, zoom, prompt, model)
            checkCancelled()
            if (p2 != null && componentAt(p2, w, h, prompt) != null) merged = blendZoom(p1, p2, w, h, zoom)
        }
        val keep = componentAt(merged, w, h, prompt) ?: return FloatArray(w * h)
        return keepComponent(merged, keep, w, h)
    }

    /** Center of the prompt (the tap, or the scribble's mean point). */
    fun center(prompt: ObjectPrompt): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (i in 0 until prompt.count) { sx += prompt.x(i); sy += prompt.y(i) }
        return sx / prompt.count to sy / prompt.count
    }

    /** The whole image, or a MAX_ASPECT window around (cx, cy) for very long images. */
    fun firstCrop(w: Int, h: Int, cx: Float, cy: Float): Crop {
        if (w <= h * MAX_ASPECT && h <= w * MAX_ASPECT) return Crop(0, 0, w, h)
        return if (w > h) {
            val cw = min(w, (h * MAX_ASPECT).roundToInt())
            val x0 = (cx - cw / 2f).roundToInt().coerceIn(0, w - cw)
            Crop(x0, 0, x0 + cw, h)
        } else {
            val ch = min(h, (w * MAX_ASPECT).roundToInt())
            val y0 = (cy - ch / 2f).roundToInt().coerceIn(0, h - ch)
            Crop(0, y0, w, y0 + ch)
        }
    }

    /**
     * The zoom window for an object whose component is [comp]: its bounding box plus 20 % (at
     * least 8 px) on each side, at most MAX_ASPECT, kept inside the image. Null if the object
     * already fills the image.
     */
    fun zoomCrop(comp: BooleanArray, w: Int, h: Int): Crop? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (comp[y * w + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        var bw = (x1 - x0 + 1).toFloat(); var bh = (y1 - y0 + 1).toFloat()
        val mx = max(8f, bw * 0.2f); val my = max(8f, bh * 0.2f)
        bw += 2 * mx; bh += 2 * my
        bw = max(bw, bh / MAX_ASPECT); bh = max(bh, bw / MAX_ASPECT)
        bw = max(bw, 32f); bh = max(bh, 32f)
        val cx = (x0 + x1 + 1) / 2f; val cy = (y0 + y1 + 1) / 2f
        val cw = min(w, ceil(bw).toInt()); val ch = min(h, ceil(bh).toInt())
        if (cw >= w && ch >= h) return null
        val nx0 = (cx - cw / 2f).roundToInt().coerceIn(0, w - cw)
        val ny0 = (cy - ch / 2f).roundToInt().coerceIn(0, h - ch)
        return Crop(nx0, ny0, nx0 + cw, ny0 + ch)
    }

    /**
     * Runs the model on [crop] of the working image (rendered from [full] when that has more
     * pixels) and returns its answer placed on the working grid (0 outside the crop).
     */
    fun pass(work: PixelBuffer, full: PixelBuffer, crop: Crop, prompt: ObjectPrompt, model: InteractiveModel): FloatArray? {
        val w = work.width; val h = work.height
        val input = renderCrop(work, full, crop)
        val prior = renderPrior(prompt, crop)
        val out = try {
            model.run(input, prior)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        if (out.size != MODEL_SIZE * MODEL_SIZE) return null
        for (i in out.indices) out[i] = MaskOps.clamp01(out[i])
        val placed = MaskOps.resizeArea(out, MODEL_SIZE, MODEL_SIZE, crop.width, crop.height)
        val result = FloatArray(w * h)
        for (y in max(0, crop.y0) until min(h, crop.y1)) {
            for (x in max(0, crop.x0) until min(w, crop.x1)) result[y * w + x] = placed[(y - crop.y0) * crop.width + (x - crop.x0)]
        }
        return result
    }

    /** The model input for [crop] (working coordinates), stretched to MODEL_SIZE². */
    fun renderCrop(work: PixelBuffer, full: PixelBuffer, crop: Crop): PixelBuffer {
        val scale = full.width.toFloat() / work.width
        // The full-resolution image only helps when the working copy would have to be enlarged
        // (the crop spans fewer working pixels than the model input) and it has more pixels.
        val src: PixelBuffer; val s: Float
        if (full !== work && scale > 1.05f && crop.long < MODEL_SIZE) {
            src = full; s = scale
        } else {
            src = work; s = 1f
        }
        val x0 = floor(crop.x0 * s).toInt(); val y0 = floor(crop.y0 * s).toInt()
        val x1 = max(x0 + 1, ceil(crop.x1 * s).toInt()); val y1 = max(y0 + 1, ceil(crop.y1 * s).toInt())
        return MaskOps.resampleSmooth(MaskOps.crop(src, x0, y0, x1, y1), MODEL_SIZE, MODEL_SIZE)
    }

    /**
     * The prior channel: discs of [PRIOR_RADIUS] model pixels at the prompt points, joined by a
     * stroke of the same width for scribbles; 1 inside, 0 elsewhere.
     */
    fun renderPrior(prompt: ObjectPrompt, crop: Crop): FloatArray {
        val prior = FloatArray(MODEL_SIZE * MODEL_SIZE)
        val sx = MODEL_SIZE.toFloat() / crop.width; val sy = MODEL_SIZE.toFloat() / crop.height
        fun mx(i: Int) = (prompt.x(i) - crop.x0) * sx
        fun my(i: Int) = (prompt.y(i) - crop.y0) * sy
        for (i in 0 until prompt.count) {
            disc(prior, mx(i), my(i))
            if (i > 0) {
                val ax = mx(i - 1); val ay = my(i - 1); val bx = mx(i); val by = my(i)
                val len = hypot(bx - ax, by - ay)
                val steps = ceil(len).toInt()
                for (k in 1 until steps) {
                    val t = k.toFloat() / steps
                    disc(prior, ax + (bx - ax) * t, ay + (by - ay) * t)
                }
            }
        }
        return prior
    }

    private fun disc(prior: FloatArray, cx: Float, cy: Float) {
        val r = PRIOR_RADIUS
        val x0 = max(0, floor(cx - r).toInt()); val x1 = min(MODEL_SIZE - 1, ceil(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt()); val y1 = min(MODEL_SIZE - 1, ceil(cy + r).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r * r) prior[y * MODEL_SIZE + x] = 1f
        }
        // A point between pixel centers still marks its nearest pixel.
        val nx = cx.toInt().coerceIn(0, MODEL_SIZE - 1); val ny = cy.toInt().coerceIn(0, MODEL_SIZE - 1)
        prior[ny * MODEL_SIZE + nx] = 1f
    }

    /**
     * The 8-connected region of `p >= 0.5` under the prompt (any of its points), or the region
     * nearest to the prompt within 3 % of the image size; null if there is none.
     */
    fun componentAt(p: FloatArray, w: Int, h: Int, prompt: ObjectPrompt): BooleanArray? {
        val lab = Regions.label(BooleanArray(w * h) { p[it] >= 0.5f }, w, h, eightConnected = true)
        if (lab.count == 0) return null
        val picked = BooleanArray(lab.count + 1)
        var any = false
        for (i in 0 until prompt.count) {
            val x = prompt.x(i).toInt(); val y = prompt.y(i).toInt()
            if (x in 0 until w && y in 0 until h) {
                val id = lab.ids[y * w + x]
                if (id != 0) { picked[id] = true; any = true }
            }
        }
        if (!any) {
            // Nearest region to the first prompt point (a tap just beside a thin object).
            val px = prompt.x(0); val py = prompt.y(0)
            val reach = max(3f, 0.03f * max(w, h))
            var best = -1; var bestD = reach * reach
            val x0 = max(0, (px - reach).toInt()); val x1 = min(w - 1, (px + reach).toInt())
            val y0 = max(0, (py - reach).toInt()); val y1 = min(h - 1, (py + reach).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val id = lab.ids[y * w + x]
                if (id == 0) continue
                val dx = x + 0.5f - px; val dy = y + 0.5f - py
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = id }
            }
            if (best < 0) return null
            picked[best] = true
        }
        return BooleanArray(w * h) { picked[lab.ids[it]] }
    }

    /**
     * [zoom]'s answer [p2] replaces [p1] inside the zoom window, feathered over 8 % of the
     * window near its sides that are not image edges.
     */
    fun blendZoom(p1: FloatArray, p2: FloatArray, w: Int, h: Int, zoom: Crop): FloatArray {
        val out = p1.copyOf()
        val feather = max(2f, 0.08f * zoom.long)
        for (y in max(0, zoom.y0) until min(h, zoom.y1)) for (x in max(0, zoom.x0) until min(w, zoom.x1)) {
            var d = Float.MAX_VALUE
            if (zoom.x0 > 0) d = min(d, x + 0.5f - zoom.x0)
            if (zoom.y0 > 0) d = min(d, y + 0.5f - zoom.y0)
            if (zoom.x1 < w) d = min(d, zoom.x1 - x - 0.5f)
            if (zoom.y1 < h) d = min(d, zoom.y1 - y - 0.5f)
            val t = if (d == Float.MAX_VALUE) 1f else MaskOps.clamp01(d / feather)
            val i = y * w + x
            out[i] = p1[i] + (p2[i] - p1[i]) * t
        }
        return out
    }

    /** [p] limited to [keep] plus 2 px around it (so the soft edge survives), 0 elsewhere. */
    fun keepComponent(p: FloatArray, keep: BooleanArray, w: Int, h: Int): FloatArray {
        val near = FloatArray(w * h) { if (keep[it]) 1f else 0f }
        val d = com.brushwork.paint.filters.FilterMath.distanceToCoverage(near, w, h, 0.5f)
        return FloatArray(w * h) { if (d[it] <= 2f) p[it] else 0f }
    }
}

/**
 * Color region growing around a tap or scribble: the fallback of the object select tool when the
 * model is not available. Grows (4-connected) through pixels whose CIELAB color stays close both
 * to the region's running mean and to their neighbour (so it stops at edges), with a tolerance
 * adapted to how varied the colors under the tap are; small holes are filled.
 */
internal object RegionGrow {

    fun select(img: PixelBuffer, prompt: ObjectPrompt, checkCancelled: () -> Unit = {}): FloatArray {
        val w = img.width; val h = img.height; val n = w * h
        val lab = toLab(img)
        checkCancelled()
        val seeds = ArrayList<Int>()
        val seedR = max(1, (max(w, h) / 400f).roundToInt())
        for (k in 0 until prompt.count) {
            val cx = prompt.x(k).toInt(); val cy = prompt.y(k).toInt()
            for (y in cy - seedR..cy + seedR) for (x in cx - seedR..cx + seedR) {
                if (x in 0 until w && y in 0 until h) seeds += y * w + x
            }
        }
        if (seeds.isEmpty()) return FloatArray(n)
        // Seed statistics over a slightly larger window around the first point on the image.
        var sL = 0.0; var sA = 0.0; var sB = 0.0; var cnt = 0
        val statR = seedR * 3
        val px0 = seeds[0] % w; val py0 = seeds[0] / w
        for (y in py0 - statR..py0 + statR) for (x in px0 - statR..px0 + statR) {
            if (x !in 0 until w || y !in 0 until h) continue
            val i = y * w + x
            sL += lab[i * 3]; sA += lab[i * 3 + 1]; sB += lab[i * 3 + 2]; cnt++
        }
        if (cnt == 0) return FloatArray(n)
        val mL = sL / cnt; val mA = sA / cnt; val mB = sB / cnt
        var v = 0.0
        for (y in py0 - statR..py0 + statR) for (x in px0 - statR..px0 + statR) {
            if (x !in 0 until w || y !in 0 until h) continue
            val i = y * w + x
            v += sq(lab[i * 3] - mL) + sq(lab[i * 3 + 1] - mA) + sq(lab[i * 3 + 2] - mB)
        }
        val sigma = sqrt(v / cnt).toFloat()
        val tol = (9f + 2.5f * sigma).coerceIn(10f, 30f)
        val step = tol * 0.55f
        val inRegion = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        var rL = 0.0; var rA = 0.0; var rB = 0.0; var rn = 0
        for (s in seeds) if (!inRegion[s]) {
            inRegion[s] = true; queue[tail++] = s
            rL += lab[s * 3]; rA += lab[s * 3 + 1]; rB += lab[s * 3 + 2]; rn++
        }
        val tol2 = tol * tol; val step2 = step * step
        var since = 0
        while (head < tail) {
            val i = queue[head++]
            if (++since >= 65536) { since = 0; checkCancelled() }
            val x = i % w; val y = i / w
            for (d in 0 until 4) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (inRegion[j]) continue
                val jl = lab[j * 3]; val ja = lab[j * 3 + 1]; val jb = lab[j * 3 + 2]
                val dm = sq(jl - rL / rn) + sq(ja - rA / rn) + sq(jb - rB / rn)
                if (dm > tol2) continue
                val dn = sq(jl - lab[i * 3]) + sq(ja - lab[i * 3 + 1]) + sq(jb - lab[i * 3 + 2])
                if (dn > step2) continue
                inRegion[j] = true
                queue[tail++] = j
                rL += jl; rA += ja; rB += jb; rn++
            }
        }
        Regions.fillHoles(inRegion, w, h, maxHoleSize = max(4, tail / 50))
        return FloatArray(n) { if (inRegion[it]) 1f else 0f }
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    private fun sq(v: Double) = v * v
    private fun sq(v: Float) = (v * v).toDouble()

    private val LINEAR = FloatArray(256) { v ->
        val c = v / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** CIELAB (D65) of every pixel (flattened over white), 3 floats per pixel. */
    fun toLab(img: PixelBuffer): FloatArray {
        val n = img.size
        val out = FloatArray(n * 3)
        com.brushwork.paint.core.Parallel.forRange(n, 4096) { s, e ->
            for (i in s until e) {
                val c = MaskOps.flattenOverWhite(img.pixels[i])
                val r = LINEAR[(c shr 16) and 0xFF]; val g = LINEAR[(c shr 8) and 0xFF]; val b = LINEAR[c and 0xFF]
                val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
                val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
                val fx = f(x); val fy = f(y); val fz = f(z)
                out[i * 3] = 116f * fy - 16f
                out[i * 3 + 1] = 500f * (fx - fy)
                out[i * 3 + 2] = 200f * (fy - fz)
            }
        }
        return out
    }

    private fun f(t: Float): Float = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
}
