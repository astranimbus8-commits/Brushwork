package com.brushwork.paint.brush

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A rectangular pixel store holding NON-premultiplied ARGB ints (what Bitmap.getPixels returns). */
interface PixelSurface {
    val width: Int
    val height: Int
    fun read(left: Int, top: Int, w: Int, h: Int, out: IntArray)
    fun write(left: Int, top: Int, w: Int, h: Int, src: IntArray)
}

/** Reads a coverage mask (e.g. the selection) as values 0..255 into [out]. */
fun interface CoverageReader {
    fun read(left: Int, top: Int, w: Int, h: Int, out: IntArray)
}

/** Integer rectangle [left, right) x [top, bottom) (pure stand-in for android.graphics.Rect). */
class IntBox(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }

    fun set(o: IntBox) = set(o.left, o.top, o.right, o.bottom)

    fun intersects(o: IntBox): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    /** Clips to [0, w) x [0, h); returns false when the result is empty. */
    fun clip(w: Int, h: Int): Boolean {
        left = max(left, 0); top = max(top, 0)
        right = min(right, w); bottom = min(bottom, h)
        return !isEmpty
    }
}

/** [PixelSurface] over a plain IntArray (tests, offscreen work). */
class IntArraySurface(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) : PixelSurface {
    override fun read(left: Int, top: Int, w: Int, h: Int, out: IntArray) {
        for (y in 0 until h) System.arraycopy(pixels, (top + y) * width + left, out, y * w, w)
    }

    override fun write(left: Int, top: Int, w: Int, h: Int, src: IntArray) {
        for (y in 0 until h) System.arraycopy(src, y * w, pixels, (top + y) * width + left, w)
    }
}

/**
 * Smudge, blur and watercolor: dabs that read and rewrite the pixels under them directly.
 * Works in premultiplied float space on the dab rectangle only, weighted by the soft tip
 * profile and the selection. With [alphaLock] the alpha of every pixel is preserved.
 *
 * One instance per stroke (it tracks the smudge transport / the watercolor paint load).
 * Every dab goes through [prepare] (which never touches the surface) and, when that returns
 * true, [paint]; callers that record undo snapshot the prepared rectangle in between. Pure Kotlin.
 *
 * @param color non-premultiplied opaque paint color (watercolor).
 * @param limit bounds of the non-zero [selection] pixels (dabs outside it are skipped cheaply).
 */
class DirectPainter(
    val kind: StrokeKind,
    val preset: BrushPreset,
    private val surface: PixelSurface,
    private val selection: CoverageReader?,
    private val alphaLock: Boolean,
    color: Int,
    private val limit: IntBox? = null,
) {
    init {
        require(kind.isDirect) { "DirectPainter does not handle $kind" }
    }

    private val brushR = ((color shr 16) and 0xFF).toFloat()
    private val brushG = ((color shr 8) and 0xFF).toFloat()
    private val brushB = (color and 0xFF).toFloat()

    private var px = IntArray(0)
    private var shape = FloatArray(0)
    private var weight = FloatArray(0)
    private var sel = IntArray(0)
    private val box = IntBox()
    private val scratch = IntBox()
    private var lastDistance = Float.NaN

    /** Bounds of the pixels [dab] may change, clipped to the surface. Returns false if empty. */
    fun bounds(dab: Dab, out: IntBox): Boolean {
        val r = dab.diameter / 2f + 1f
        out.set(floor(dab.cx - r).toInt(), floor(dab.cy - r).toInt(), ceil(dab.cx + r).toInt(), ceil(dab.cy + r).toInt())
        return out.clip(surface.width, surface.height)
    }

    /** The dab accepted by the last successful [prepare], waiting for [paint]. */
    private var prepared: Dab? = null
    private var preparedRatio = 0f

    /**
     * Moves the stroke to [dab] (every dab must pass through here, painted or not, so travel
     * distances and the smudge transport stay continuous) and computes its weights without
     * touching the surface. Returns true, with the rectangle [paint] will rewrite in [out], only
     * when the dab changes pixels: not for dabs outside the surface or selection, with zero
     * strength, or smudge dabs that did not move a whole pixel.
     */
    fun prepare(dab: Dab, out: IntBox): Boolean {
        prepared = null
        val ratio = advance(dab)
        if (!bounds(dab, box)) return false
        if (limit != null && !box.intersects(limit)) return false
        if (kind == StrokeKind.SMUDGE && ox == 0 && oy == 0) return false
        val w = box.width
        val h = box.height
        ensure(w * h)
        selection?.read(box.left, box.top, w, h, sel)
        val strength = dab.alpha * preset.opacity
        val weightScale = when (kind) {
            StrokeKind.SMUDGE -> smudgeWeight(strength)
            StrokeKind.BLUR -> strength * (0.35f + 0.65f * preset.mixing)
            else -> strength
        }
        if (!computeWeights(dab, w, h, weightScale)) return false
        prepared = dab
        preparedRatio = ratio
        out.set(box)
        return true
    }

    /** Rewrites the pixels of the dab accepted by the last [prepare]. */
    fun paint() {
        val dab = prepared ?: return
        prepared = null
        val w = box.width
        val h = box.height
        surface.read(box.left, box.top, w, h, px)
        when (kind) {
            StrokeKind.SMUDGE -> smudge(w, h)
            StrokeKind.BLUR -> blur(dab, w, h)
            StrokeKind.WATERCOLOR -> watercolor(w, h, preparedRatio.coerceIn(0.01f, 1f))
            else -> {}
        }
        surface.write(box.left, box.top, w, h, px)
    }

    /** [prepare] + [paint] (no undo recording). Returns true when pixels were rewritten. */
    fun apply(dab: Dab): Boolean {
        if (!prepare(dab, scratch)) return false
        paint()
        return true
    }

    /**
     * Moves the stroke state to [dab]: returns the travel since the previous dab as a fraction
     * of the diameter and (smudge) computes the integer transport offset [ox], [oy].
     */
    private fun advance(dab: Dab): Float {
        val ratio = if (lastDistance.isNaN()) preset.spacing else (dab.distance - lastDistance) / max(1f, dab.diameter)
        lastDistance = dab.distance
        if (kind == StrokeKind.SMUDGE) {
            val ix = floor(dab.cx + 0.5f).toInt()
            val iy = floor(dab.cy + 0.5f).toInt()
            if (!hasPrev) {
                ox = 0; oy = 0
                hasPrev = true
            } else {
                ox = prevIx - ix
                oy = prevIy - iy
            }
            prevIx = ix
            prevIy = iy
        }
        return ratio
    }

    private fun ensure(n: Int) {
        if (px.size < n) {
            px = IntArray(n)
            shape = FloatArray(n)
            weight = FloatArray(n)
            if (selection != null) sel = IntArray(n)
        }
    }

    /** Fills [shape] (tip profile) and [weight] (profile * selection * strength). */
    private fun computeWeights(dab: Dab, w: Int, h: Int, strength: Float): Boolean {
        val radius = dab.diameter / 2f
        val rad = -dab.rotation * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        var any = false
        for (yy in 0 until h) {
            val dy = box.top + yy + 0.5f - dab.cy
            for (xx in 0 until w) {
                val i = yy * w + xx
                val dx = box.left + xx + 0.5f - dab.cx
                val lx = dx * cosA - dy * sinA
                val ly = dx * sinA + dy * cosA
                val s = TipShapes.coverage(preset.tip, preset.hardness, preset.roundness, lx, ly, radius)
                shape[i] = s
                var wt = s * strength
                if (selection != null) wt *= sel[i] / 255f
                weight[i] = wt
                if (wt > 0f) any = true
            }
        }
        return any
    }

    // ------------------------------------------------------------------ smudge

    // Smear model: each dab drags the pixels from where the tip was at the previous dab onto the
    // pixels under it, blended by the tip profile * mixing * strength. The blend makes the
    // carried paint lag behind the tip by (1 - weight) of the distance travelled, so it drains
    // out of the back of the tip and fresh canvas color enters at the front: at mixing m the
    // color is carried about 1 / (1 - m) diameters, independent of the dab spacing (100 %
    // carries it indefinitely). The transport offset is integer (rounded tip centers), so the
    // dragged pixels are not resampled; the soft profile uses the sub-pixel center.

    private var srcPx = IntArray(0)
    private val srcBox = IntBox()
    private var hasPrev = false
    private var prevIx = 0
    private var prevIy = 0
    /** Transport offset of the current dab: source pixel = destination pixel + (ox, oy). */
    private var ox = 0
    private var oy = 0

    /** Blend weight of the dragged pixels in the core of the tip (see the model above). */
    private fun smudgeWeight(strength: Float): Float = (preset.mixing * strength).coerceIn(0f, 1f)

    private fun smudge(w: Int, h: Int) {
        srcBox.set(box.left + ox, box.top + oy, box.right + ox, box.bottom + oy)
        if (!srcBox.clip(surface.width, surface.height)) return
        val sw = srcBox.width
        val sh = srcBox.height
        if (srcPx.size < sw * sh) srcPx = IntArray(sw * sh)
        surface.read(srcBox.left, srcBox.top, sw, sh, srcPx)
        for (yy in 0 until h) {
            val sy = box.top + yy + oy - srcBox.top
            if (sy < 0 || sy >= sh) continue
            for (xx in 0 until w) {
                val i = yy * w + xx
                val wt = weight[i]
                if (wt <= 0f) continue
                val sx = box.left + xx + ox - srcBox.left
                if (sx < 0 || sx >= sw) continue
                val s = srcPx[sy * sw + sx]
                val c = px[i]
                if (s == c) continue
                val sa = (s ushr 24).toFloat()
                val ca = (c ushr 24).toFloat()
                val sr = ((s shr 16) and 0xFF) * sa / 255f
                val sg = ((s shr 8) and 0xFF) * sa / 255f
                val sb = (s and 0xFF) * sa / 255f
                val cr = ((c shr 16) and 0xFF) * ca / 255f
                val cg = ((c shr 8) and 0xFF) * ca / 255f
                val cb = (c and 0xFF) * ca / 255f
                px[i] = pack(c, ca + (sa - ca) * wt, cr + (sr - cr) * wt, cg + (sg - cg) * wt, cb + (sb - cb) * wt)
            }
        }
    }

    // ------------------------------------------------------------------ blur

    private var gridA = FloatArray(0)
    private var gridR = FloatArray(0)
    private var gridG = FloatArray(0)
    private var gridB = FloatArray(0)
    private var gridTmp = FloatArray(0)
    private var rowBuf = IntArray(0)

    private fun blur(dab: Dab, w: Int, h: Int) {
        val radiusPx = max(1f, dab.diameter * (0.02f + 0.08f * preset.mixing))
        // Large dabs blur a downsampled copy (the result is smooth anyway) to bound the cost.
        val f = max(1, ceil(dab.diameter / MAX_BLUR_CELLS).toInt())
        val margin = ceil(radiusPx).toInt() + f
        val rl = max(0, box.left - margin)
        val rt = max(0, box.top - margin)
        val rr = min(surface.width, box.right + margin)
        val rbm = min(surface.height, box.bottom + margin)
        val rw = rr - rl
        val gw = (rw + f - 1) / f
        val gh = (rbm - rt + f - 1) / f
        val cells = gw * gh
        if (gridA.size < cells) {
            gridA = FloatArray(cells); gridR = FloatArray(cells); gridG = FloatArray(cells); gridB = FloatArray(cells); gridTmp = FloatArray(cells)
        }
        if (rowBuf.size < rw) rowBuf = IntArray(rw)
        java.util.Arrays.fill(gridA, 0, cells, 0f)
        java.util.Arrays.fill(gridR, 0, cells, 0f)
        java.util.Arrays.fill(gridG, 0, cells, 0f)
        java.util.Arrays.fill(gridB, 0, cells, 0f)
        // Box-downsample the region (premultiplied).
        for (y in rt until rbm) {
            surface.read(rl, y, rw, 1, rowBuf)
            val grow = ((y - rt) / f) * gw
            for (x in 0 until rw) {
                val c = rowBuf[x]
                val a = (c ushr 24).toFloat()
                if (a == 0f) continue
                val g = grow + x / f
                gridA[g] += a
                gridR[g] += ((c shr 16) and 0xFF) * a / 255f
                gridG[g] += ((c shr 8) and 0xFF) * a / 255f
                gridB[g] += (c and 0xFF) * a / 255f
            }
        }
        if (f > 1) {
            for (gy in 0 until gh) {
                val ch = min(f, rbm - (rt + gy * f))
                for (gx in 0 until gw) {
                    val cw = min(f, rw - gx * f)
                    val inv = 1f / (cw * ch)
                    val g = gy * gw + gx
                    gridA[g] *= inv; gridR[g] *= inv; gridG[g] *= inv; gridB[g] *= inv
                }
            }
        }
        val gr = max(1, (radiusPx / f).roundToInt())
        for (ch in arrayOf(gridA, gridR, gridG, gridB)) {
            repeat(2) {
                boxBlurH(ch, gridTmp, gw, gh, gr)
                boxBlurV(gridTmp, ch, gw, gh, gr)
            }
        }
        for (yy in 0 until h) {
            val gyf = (box.top + yy + 0.5f - rt) / f - 0.5f
            val gy0 = floor(gyf).toInt()
            val ty = gyf - gy0
            val y0 = gy0.coerceIn(0, gh - 1)
            val y1 = (gy0 + 1).coerceIn(0, gh - 1)
            for (xx in 0 until w) {
                val i = yy * w + xx
                val wt = weight[i]
                if (wt <= 0f) continue
                val gxf = (box.left + xx + 0.5f - rl) / f - 0.5f
                val gx0 = floor(gxf).toInt()
                val tx = gxf - gx0
                val x0 = gx0.coerceIn(0, gw - 1)
                val x1 = (gx0 + 1).coerceIn(0, gw - 1)
                val i00 = y0 * gw + x0; val i10 = y0 * gw + x1; val i01 = y1 * gw + x0; val i11 = y1 * gw + x1
                val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty); val w01 = (1 - tx) * ty; val w11 = tx * ty
                val ba = gridA[i00] * w00 + gridA[i10] * w10 + gridA[i01] * w01 + gridA[i11] * w11
                val br = gridR[i00] * w00 + gridR[i10] * w10 + gridR[i01] * w01 + gridR[i11] * w11
                val bg = gridG[i00] * w00 + gridG[i10] * w10 + gridG[i01] * w01 + gridG[i11] * w11
                val bb = gridB[i00] * w00 + gridB[i10] * w10 + gridB[i01] * w01 + gridB[i11] * w11
                val c = px[i]
                val ca = (c ushr 24).toFloat()
                val cr = ((c shr 16) and 0xFF) * ca / 255f
                val cg = ((c shr 8) and 0xFF) * ca / 255f
                val cb = (c and 0xFF) * ca / 255f
                px[i] = pack(c, ca + (ba - ca) * wt, cr + (br - cr) * wt, cg + (bg - cg) * wt, cb + (bb - cb) * wt)
            }
        }
    }

    // ------------------------------------------------------------------ watercolor

    private var loadR = brushR
    private var loadG = brushG
    private var loadB = brushB

    private fun watercolor(w: Int, h: Int, ratio: Float) {
        val n = w * h
        var sw = 0f
        var sa = 0f
        var sr = 0f
        var sg = 0f
        var sb = 0f
        for (i in 0 until n) {
            val s = shape[i]
            if (s <= 0f) continue
            val c = px[i]
            val a = (c ushr 24).toFloat()
            sw += s
            if (a == 0f) continue
            sa += s * a
            sr += s * ((c shr 16) and 0xFF) * a
            sg += s * ((c shr 8) and 0xFF) * a
            sb += s * (c and 0xFF) * a
        }
        val mixing = preset.mixing
        if (sw > 0f && sa > 1e-3f) {
            val coverage = (sa / sw) / 255f
            val pickUp = (1f - (1f - 0.85f * mixing).pow(ratio)) * coverage
            loadR += (sr / sa - loadR) * pickUp
            loadG += (sg / sa - loadG) * pickUp
            loadB += (sb / sa - loadB) * pickUp
        }
        val refill = 1f - (1f - 0.6f * (1f - mixing)).pow(ratio)
        loadR += (brushR - loadR) * refill
        loadG += (brushG - loadG) * refill
        loadB += (brushB - loadB) * refill
        for (i in 0 until n) {
            val wt = weight[i]
            if (wt <= 0f) continue
            val c = px[i]
            val ca = (c ushr 24).toFloat()
            val k = 1f - wt
            px[i] = pack(
                c,
                wt * 255f + ca * k,
                loadR * wt + ((c shr 16) and 0xFF) * ca / 255f * k,
                loadG * wt + ((c shr 8) and 0xFF) * ca / 255f * k,
                loadB * wt + (c and 0xFF) * ca / 255f * k,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Packs premultiplied channels (0..255 units) into a non-premultiplied int. With alpha lock
     * the original pixel's alpha is kept (transparent pixels stay untouched).
     */
    private fun pack(orig: Int, a: Float, r: Float, g: Float, b: Float): Int {
        if (a <= 0.25f) return if (alphaLock) orig else 0
        val inv = 255f / a
        val ri = (r * inv + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g * inv + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b * inv + 0.5f).toInt().coerceIn(0, 255)
        val ai = if (alphaLock) {
            val oa = orig ushr 24
            if (oa == 0) return orig
            oa
        } else (a + 0.5f).toInt().coerceIn(0, 255)
        if (ai == 0) return 0
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    companion object {
        /** Max blur grid cells across a dab. */
        const val MAX_BLUR_CELLS = 160f

        internal fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
            val div = 1f / (2 * r + 1)
            val last = w - 1
            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                for (k in -r..r) sum += src[row + k.coerceIn(0, last)]
                for (x in 0 until w) {
                    dst[row + x] = sum * div
                    sum += src[row + min(last, x + r + 1)] - src[row + max(0, x - r)]
                }
            }
        }

        internal fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
            val div = 1f / (2 * r + 1)
            val last = h - 1
            for (x in 0 until w) {
                var sum = 0f
                for (k in -r..r) sum += src[k.coerceIn(0, last) * w + x]
                for (y in 0 until h) {
                    dst[y * w + x] = sum * div
                    sum += src[min(last, y + r + 1) * w + x] - src[max(0, y - r) * w + x]
                }
            }
        }
    }
}
