package com.brushwork.paint.segmentation

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where one scene-model pass looks: an affine map from WORKING-image coordinates (continuous,
 * pixel centers at +0.5) to model-input coordinates, optionally mirrored horizontally.
 *
 * - [region]: the part of the working image the pass sees (x0, y0, x1, y1).
 * - [valid]: the part of the model input that shows the picture (the rest is padding).
 * - [cutEdges]: which sides of [region] (left, top, right, bottom) cut through the picture (a
 *   crop edge that is not an image edge): the model lacks context there, so its answers near
 *   those sides are trusted less.
 */
internal class PassGeometry(
    val kind: Kind,
    val size: Int,
    val sx: Float,
    val sy: Float,
    val tx: Float,
    val ty: Float,
    val flip: Boolean,
    val region: FloatArray,
    val valid: FloatArray,
    val cutEdges: BooleanArray,
) {
    enum class Kind { GLOBAL, CROP }

    fun toInputX(x: Float): Float = (x * sx + tx).let { if (flip) size - it else it }
    fun toInputY(y: Float): Float = y * sy + ty
    fun toWorkX(ix: Float): Float = ((if (flip) size - ix else ix) - tx) / sx
    fun toWorkY(iy: Float): Float = (iy - ty) / sy

    fun contains(x: Float, y: Float): Boolean = x >= region[0] && y >= region[1] && x < region[2] && y < region[3]

    /**
     * Trust 0..1 of this pass at working position (x, y): tapers to ~0 within [taper] input
     * pixels of a cut edge (tent weights across crop overlaps), 1 elsewhere.
     */
    fun edgeWeight(x: Float, y: Float, taper: Float): Float {
        var w = 1f
        if (cutEdges[0]) w = min(w, ramp((x - region[0]) * sx, taper))
        if (cutEdges[1]) w = min(w, ramp((y - region[1]) * sy, taper))
        if (cutEdges[2]) w = min(w, ramp((region[2] - x) * sx, taper))
        if (cutEdges[3]) w = min(w, ramp((region[3] - y) * sy, taper))
        return w
    }

    private fun ramp(d: Float, taper: Float): Float = ((d - taper * 0.125f) / (taper * 0.875f)).coerceIn(0.02f, 1f)

    override fun toString(): String = "$kind flip=$flip region=${region.contentToString()} valid=${valid.contentToString()}"
}

/** Which passes the scene model runs on an image, and how they are rendered. */
internal object ScenePasses {
    const val SIZE = Letterbox.MODEL_SIZE

    /** Horizontal / vertical step between neighbouring aspect-fill crops (64 px overlap). */
    const val CROP_STRIDE = 448

    /** Crops are skipped when they would enlarge the working image more than this. */
    const val MAX_CROP_ENLARGEMENT = 2f

    /** Crops are skipped for nearly square images (the global pass already fills the input). */
    const val MIN_CROP_ASPECT = 1.15f

    /** The whole [w]x[h] working image letterboxed into the model input (optionally mirrored). */
    fun global(w: Int, h: Int, flip: Boolean = false): PassGeometry {
        val lb = Letterbox(w, h, SIZE)
        return PassGeometry(
            PassGeometry.Kind.GLOBAL, SIZE,
            sx = lb.contentWidth.toFloat() / w, sy = lb.contentHeight.toFloat() / h,
            tx = lb.offsetX.toFloat(), ty = lb.offsetY.toFloat(), flip = flip,
            region = floatArrayOf(0f, 0f, w.toFloat(), h.toFloat()),
            valid = floatArrayOf(lb.offsetX.toFloat(), lb.offsetY.toFloat(), (lb.offsetX + lb.contentWidth).toFloat(), (lb.offsetY + lb.contentHeight).toFloat()),
            cutEdges = BooleanArray(4),
        )
    }

    /** Size of the working image scaled so its SHORT side fills the model input. */
    fun fillSize(w: Int, h: Int): IntArray {
        val s = SIZE.toFloat() / min(w, h)
        return intArrayOf(max(SIZE, (w * s).roundToInt()), max(SIZE, (h * s).roundToInt()))
    }

    /**
     * Overlapping [SIZE]² crops covering the aspect-fill scaled image (short side = [SIZE]),
     * evenly spaced with at most [CROP_STRIDE] between them. Empty for nearly square or tiny
     * images, where they would add nothing over the global pass.
     */
    fun crops(w: Int, h: Int): List<PassGeometry> {
        val long = max(w, h); val short = min(w, h)
        if (short <= 0 || long.toFloat() / short < MIN_CROP_ASPECT) return emptyList()
        if (SIZE.toFloat() / short > MAX_CROP_ENLARGEMENT) return emptyList()
        val (fw, fh) = fillSize(w, h).let { it[0] to it[1] }
        val sx = fw.toFloat() / w; val sy = fh.toFloat() / h
        val horizontal = fw >= fh
        val span = (if (horizontal) fw else fh) - SIZE
        val n = if (span <= 0) 1 else ceil(span.toFloat() / CROP_STRIDE).toInt() + 1
        return List(n) { k ->
            val start = if (n == 1) 0 else (span.toLong() * k / (n - 1)).toInt()
            val cx0 = if (horizontal) start else 0
            val cy0 = if (horizontal) 0 else start
            PassGeometry(
                PassGeometry.Kind.CROP, SIZE, sx, sy, tx = -cx0.toFloat(), ty = -cy0.toFloat(), flip = false,
                region = floatArrayOf(cx0 / sx, cy0 / sy, (cx0 + SIZE) / sx, (cy0 + SIZE) / sy),
                valid = floatArrayOf(0f, 0f, SIZE.toFloat(), SIZE.toFloat()),
                cutEdges = booleanArrayOf(cx0 > 0, cy0 > 0, cx0 + SIZE < fw, cy0 + SIZE < fh),
            )
        }
    }

    /**
     * The passes to run after the global one when [affordable] more fit in the time budget:
     * the mirrored pass and every crop; or only the crops (every part of the picture refined
     * alike); or only the mirrored pass. Never a subset of the crops, which would sharpen one
     * side of the picture and not the other. The mirrored pass comes first.
     */
    fun plan(crops: List<PassGeometry>, flip: PassGeometry, affordable: Int): List<PassGeometry> = when {
        affordable >= crops.size + 1 -> listOf(flip) + crops
        crops.isNotEmpty() && affordable >= crops.size -> crops
        affordable >= 1 -> listOf(flip)
        else -> emptyList()
    }

    /** Mid gray: the Autoseg input value 0 (the padding the model was exported with). */
    const val PAD = 0xFF808080.toInt()

    /**
     * The model input of the global pass: the working image resampled into the letterbox
     * content, gray padding around it, mirrored when [geo] is flipped.
     */
    fun renderGlobal(work: PixelBuffer, geo: PassGeometry): PixelBuffer {
        val x0 = geo.valid[0].toInt(); val y0 = geo.valid[1].toInt()
        val cw = geo.valid[2].toInt() - x0; val ch = geo.valid[3].toInt() - y0
        val content = MaskOps.resampleSmooth(work, cw, ch)
        val out = PixelBuffer.filled(SIZE, SIZE, PAD)
        for (y in 0 until ch) {
            val src = y * cw
            val dst = (y0 + y) * SIZE
            if (!geo.flip) {
                System.arraycopy(content.pixels, src, out.pixels, dst + x0, cw)
            } else {
                for (x in 0 until cw) out.pixels[dst + SIZE - 1 - (x0 + x)] = content.pixels[src + x]
            }
        }
        return out
    }

    /** The model input of a crop pass, cut out of [filled] (the aspect-fill scaled image). */
    fun renderCrop(filled: PixelBuffer, geo: PassGeometry): PixelBuffer {
        val cx0 = (-geo.tx).roundToInt(); val cy0 = (-geo.ty).roundToInt()
        val out = PixelBuffer(SIZE, SIZE)
        for (y in 0 until SIZE) {
            val sy = min(filled.height - 1, cy0 + y)
            for (x in 0 until SIZE) out.pixels[y * SIZE + x] = filled.pixels[sy * filled.width + min(filled.width - 1, cx0 + x)]
        }
        return out
    }
}

/**
 * Fuses the log-probabilities of several passes onto one grid over the working image
 * ([fw]x[fh] cells, center-aligned). Global passes (letterbox, mirrored letterbox) are averaged
 * into the global estimate G; crop passes into the tile estimate T with feathered weights. The
 * result is a product of experts `P ~ exp((1 - a) G + a T)` with `a = 0.5` where the global
 * estimate is unsure, falling to 0 where it is confident (max P >= 0.9): a crop that only sees
 * blue must not turn confident sky into water.
 */
internal class SceneFusion(val w: Int, val h: Int, val fw: Int, val fh: Int) {
    private val c = SceneClasses.COUNT
    private val sumG = FloatArray(fw * fh * c)
    private val wG = FloatArray(fw * fh)
    private var sumT: FloatArray? = null
    private var wT: FloatArray? = null

    /** Adds one pass ([grid] computed for the input described by [geo]). */
    fun add(grid: PassGrid, geo: PassGeometry) {
        val global = geo.kind == PassGeometry.Kind.GLOBAL
        val sum: FloatArray
        val wsum: FloatArray
        if (global) {
            sum = sumG; wsum = wG
        } else {
            sum = sumT ?: FloatArray(fw * fh * c).also { sumT = it }
            wsum = wT ?: FloatArray(fw * fh).also { wT = it }
        }
        val taper = 64f
        val cellW = w.toFloat() / fw; val cellH = h.toFloat() / fh
        Parallel.forRows(fh) { y0, y1 ->
            val tmp = FloatArray(c)
            for (fy in y0 until y1) {
                val y = (fy + 0.5f) * cellH
                for (fx in 0 until fw) {
                    val x = (fx + 0.5f) * cellW
                    if (!geo.contains(x, y)) continue
                    val wv = sample(grid, geo.toInputX(x), geo.toInputY(y), tmp)
                    if (wv <= 0f) continue
                    val pw = wv * geo.edgeWeight(x, y, taper)
                    val cell = fy * fw + fx
                    val o = cell * c
                    for (k in 0 until c) sum[o + k] += pw * tmp[k]
                    wsum[cell] += pw
                }
            }
        }
    }

    /**
     * Bilinear sample of [grid]'s log-probabilities at input position (ix, iy) into [out],
     * weighting each of the 4 cells by its validity. Returns the sample's validity 0..1
     * (0 = nothing valid nearby).
     */
    private fun sample(grid: PassGrid, ix: Float, iy: Float, out: FloatArray): Float {
        val gx = (ix / grid.stride - 0.5f).coerceIn(0f, (grid.gw - 1).toFloat())
        val gy = (iy / grid.stride - 0.5f).coerceIn(0f, (grid.gh - 1).toFloat())
        val x0 = min(gx.toInt(), grid.gw - 1); val y0 = min(gy.toInt(), grid.gh - 1)
        val x1 = min(x0 + 1, grid.gw - 1); val y1 = min(y0 + 1, grid.gh - 1)
        val tx = gx - x0; val ty = gy - y0
        val w00 = (1 - tx) * (1 - ty) * grid.weight[y0 * grid.gw + x0]
        val w10 = tx * (1 - ty) * grid.weight[y0 * grid.gw + x1]
        val w01 = (1 - tx) * ty * grid.weight[y1 * grid.gw + x0]
        val w11 = tx * ty * grid.weight[y1 * grid.gw + x1]
        val total = w00 + w10 + w01 + w11
        if (total < 0.2f) return 0f
        val o00 = (y0 * grid.gw + x0) * c; val o10 = (y0 * grid.gw + x1) * c
        val o01 = (y1 * grid.gw + x0) * c; val o11 = (y1 * grid.gw + x1) * c
        val inv = 1f / total
        val lp = grid.logp
        for (k in 0 until c) out[k] = (w00 * lp[o00 + k] + w10 * lp[o10 + k] + w01 * lp[o01 + k] + w11 * lp[o11 + k]) * inv
        return min(1f, total)
    }

    /** True once at least one pass contributed anywhere. */
    val isEmpty: Boolean get() = wG.none { it > 0f } && (wT?.none { it > 0f } ?: true)

    /**
     * The fused per-class probabilities (fh*fw*COUNT, NHWC). Cells no pass saw get a uniform
     * distribution. The accumulators are released.
     */
    fun result(): FloatArray {
        val out = FloatArray(fw * fh * c)
        val st = sumT; val wt = wT
        Parallel.forRange(fw * fh, 256) { s, e ->
            val lg = FloatArray(c); val pg = FloatArray(c)
            for (cell in s until e) {
                val o = cell * c
                val g = wG[cell]
                val t = wt?.get(cell) ?: 0f
                if (g <= 0f && t <= 0f) {
                    for (k in 0 until c) out[o + k] = 1f / c
                    continue
                }
                if (g > 0f) {
                    val inv = 1f / g
                    for (k in 0 until c) lg[k] = sumG[o + k] * inv
                } else {
                    val inv = 1f / t
                    for (k in 0 until c) lg[k] = st!![o + k] * inv
                }
                if (g > 0f && t > 0f && st != null) {
                    SceneProbabilities.softmax(lg, 0, pg, 0)
                    var maxP = 0f
                    for (k in 0 until c) if (pg[k] > maxP) maxP = pg[k]
                    val alpha = tileWeight(maxP, t)
                    if (alpha > 0f) {
                        val inv = 1f / t
                        for (k in 0 until c) lg[k] = (1f - alpha) * lg[k] + alpha * st[o + k] * inv
                    }
                }
                SceneProbabilities.softmax(lg, 0, out, o)
            }
        }
        sumT = null; wT = null
        return out
    }

    companion object {
        /**
         * Exponent of the tile estimate in the product of experts: 0.5 where the global estimate
         * is unsure, 0 where it is confident, scaled by the tiles' coverage weight.
         */
        fun tileWeight(globalMaxP: Float, tileCoverage: Float): Float =
            0.5f * (1f - MaskOps.smoothstep(0.75f, 0.9f, globalMaxP)) * min(1f, tileCoverage)

        /** Fusion grid size for a [w]x[h] working image: ~128 cells across the short side. */
        fun gridSize(w: Int, h: Int): IntArray {
            val cell = max(1f, min(w, h) / 128f)
            return intArrayOf(max(1, (w / cell).roundToInt()), max(1, (h / cell).roundToInt()))
        }
    }
}
