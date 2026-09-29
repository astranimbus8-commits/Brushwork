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
 * One instance per stroke (it carries the smudge pick-up buffer / the watercolor paint load).
 * Pure Kotlin.
 *
 * @param color non-premultiplied opaque paint color (watercolor).
 * @param maxDiameter largest dab diameter of the stroke (sizes the smudge pick-up buffer).
 */
class DirectPainter(
    val kind: StrokeKind,
    val preset: BrushPreset,
    private val surface: PixelSurface,
    private val selection: CoverageReader?,
    private val alphaLock: Boolean,
    color: Int,
    private val maxDiameter: Float = preset.size,
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
    private var lastDistance = Float.NaN

    /** Bounds of the pixels [dab] may change, clipped to the surface. Returns false if empty. */
    fun bounds(dab: Dab, out: IntBox): Boolean {
        val r = dab.diameter / 2f + 1f
        out.set(floor(dab.cx - r).toInt(), floor(dab.cy - r).toInt(), ceil(dab.cx + r).toInt(), ceil(dab.cy + r).toInt())
        return out.clip(surface.width, surface.height)
    }

    /** Applies one resolved dab. Returns false when it was entirely outside the surface. */
    fun apply(dab: Dab): Boolean {
        if (!bounds(dab, box)) return false
        val ratio = if (lastDistance.isNaN()) preset.spacing else (dab.distance - lastDistance) / max(1f, dab.diameter)
        lastDistance = dab.distance
        val w = box.width
        val h = box.height
        ensure(w * h)
        surface.read(box.left, box.top, w, h, px)
        selection?.read(box.left, box.top, w, h, sel)
        var strength = dab.alpha * preset.opacity
        if (kind == StrokeKind.BLUR) strength *= 0.35f + 0.65f * preset.mixing
        if (!computeWeights(dab, w, h, strength)) return true
        val r = ratio.coerceIn(0.01f, 1f)
        when (kind) {
            StrokeKind.SMUDGE -> smudge(dab, w, h, r)
            StrokeKind.BLUR -> blur(dab, w, h)
            StrokeKind.WATERCOLOR -> watercolor(w, h, r)
            else -> {}
        }
        surface.write(box.left, box.top, w, h, px)
        return true
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

    private var pick: FloatArray? = null
    private var pickSize = 0
    private var pickHalf = 0
    private var pickFactor = 1

    private fun initPickup(dab: Dab) {
        val maxD = max(maxDiameter, dab.diameter)
        val f = max(1, ceil(maxD / MAX_PICKUP_CELLS).toInt())
        val size = ceil(maxD / f).toInt() + 4
        val half = size / 2
        val buf = FloatArray(size * size * 4)
        val icx = floor(dab.cx).toInt()
        val icy = floor(dab.cy).toInt()
        val xa = max(0, icx - half * f)
        val xb = min(surface.width, icx + (size - half) * f)
        if (xb > xa) {
            val row = IntArray(xb - xa)
            for (j in 0 until size) {
                val y = icy + (j - half) * f
                if (y < 0 || y >= surface.height) continue
                surface.read(xa, y, xb - xa, 1, row)
                for (i in 0 until size) {
                    val x = icx + (i - half) * f
                    if (x < xa || x >= xb) continue
                    val c = row[x - xa]
                    val a = (c ushr 24).toFloat()
                    val k = (j * size + i) * 4
                    buf[k] = a
                    buf[k + 1] = ((c shr 16) and 0xFF) * a / 255f
                    buf[k + 2] = ((c shr 8) and 0xFF) * a / 255f
                    buf[k + 3] = (c and 0xFF) * a / 255f
                }
            }
        }
        pick = buf
        pickSize = size
        pickHalf = half
        pickFactor = f
    }

    private fun smudge(dab: Dab, w: Int, h: Int, ratio: Float) {
        if (pick == null) initPickup(dab)
        val buf = pick ?: return
        val size = pickSize
        val half = pickHalf
        val f = pickFactor
        val icx = floor(dab.cx).toInt()
        val icy = floor(dab.cy).toInt()
        val mixing = preset.mixing
        // Fraction of the carried paint replaced by canvas color per dab, normalized by the
        // distance travelled: mixing^2 of the carried color survives one diameter of travel.
        val rate = if (mixing >= 0.999f) 0f else 1f - mixing.pow(2f * ratio)
        for (yy in 0 until h) {
            val y = box.top + yy
            val gy = Math.floorDiv(y - icy, f) + half
            if (gy < 0 || gy >= size) continue
            val updateRow = f == 1 || Math.floorMod(y - icy, f) == 0
            for (xx in 0 until w) {
                val i = yy * w + xx
                val s = shape[i]
                if (s <= 0f) continue
                val x = box.left + xx
                val gx = Math.floorDiv(x - icx, f) + half
                if (gx < 0 || gx >= size) continue
                val c = px[i]
                val ca = (c ushr 24).toFloat()
                val cr = ((c shr 16) and 0xFF) * ca / 255f
                val cg = ((c shr 8) and 0xFF) * ca / 255f
                val cb = (c and 0xFF) * ca / 255f
                val k = (gy * size + gx) * 4
                val pa = buf[k]
                val pr = buf[k + 1]
                val pg = buf[k + 2]
                val pb = buf[k + 3]
                val wt = weight[i]
                if (wt > 0f) {
                    px[i] = pack(c, ca + (pa - ca) * wt, cr + (pr - cr) * wt, cg + (pg - cg) * wt, cb + (pb - cb) * wt)
                }
                if (rate > 0f && updateRow && (f == 1 || Math.floorMod(x - icx, f) == 0)) {
                    val u = rate * s
                    buf[k] = pa + (ca - pa) * u
                    buf[k + 1] = pr + (cr - pr) * u
                    buf[k + 2] = pg + (cg - pg) * u
                    buf[k + 3] = pb + (cb - pb) * u
                }
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
        /** Max smudge pick-up cells across (bigger brushes carry a downsampled buffer). */
        const val MAX_PICKUP_CELLS = 384f
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
