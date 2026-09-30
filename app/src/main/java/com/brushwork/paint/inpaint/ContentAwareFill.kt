package com.brushwork.paint.inpaint

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Content-aware fill, like Photoshop's: fills a hole (a selection or a painted area) with
 * texture and structure taken from the pixels around it. Pure Kotlin, JVM-testable.
 *
 * Usage (the Android side reads and writes bitmaps on the main thread, the rest runs anywhere):
 * ```
 * val plan = ContentAwareFill.plan(hole, params) ?: return   // null: empty hole
 * val pixels = <read plan.roi of the image>                   // non-premultiplied ARGB
 * val result = ContentAwareFill.run(plan, pixels, params, monitor)
 * <write result.composite(original pixels of result.rect)>    // or result.layerPixels()
 * ```
 */
object ContentAwareFill {

    /**
     * Decides what to fill and what to read: the binary hole (soft coverage > 0, plus [InpaintParams.expand]
     * pixels around its solid part), the soft composite weight, the region that changes and the
     * sampling region. Returns null when the hole is empty. Throws [InpaintException] when the
     * hole is too large or nothing around it can be sampled. Blocking, any thread.
     */
    fun plan(mask: HoleMask, params: InpaintParams = InpaintParams()): InpaintPlan? {
        val imgW = mask.imageWidth; val imgH = mask.imageHeight
        val e = params.expand.coerceIn(0, InpaintParams.MAX_EXPAND)
        val area = mask.rect.expand(e + 1).clip(imgW, imgH)
        if (area.isEmpty) return null
        // The region read must hold the hole's box: refuse early, before allocating for it.
        if (area.area > params.maxRoiPixels) {
            var n = 0L
            for (b in mask.alpha) if (b.toInt() != 0) n++
            if (n == 0L) return null
            throw InpaintException(TOO_LARGE)
        }
        val aw = area.width; val ah = area.height
        val soft = HoleOps.crop(mask.alpha, mask.rect, area)

        // Composite weight: the soft coverage, and around its solid part a band of `e` px that is
        // fully replaced, fading out over ~1.5 px (covers halos and anti-aliased edges).
        val weight = ByteArray(aw * ah)
        if (e == 0) {
            System.arraycopy(soft, 0, weight, 0, soft.size)
        } else {
            var hasCore = false
            val core = ByteArray(soft.size)
            for (i in soft.indices) if ((soft[i].toInt() and 0xFF) >= 128) { core[i] = 1; hasCore = true }
            if (!hasCore) for (i in soft.indices) if (soft[i].toInt() != 0) core[i] = 1
            val dist = HoleOps.chamferDistance(core, aw, ah)
            val reach = e + 0.5f
            for (i in soft.indices) {
                val s = soft[i].toInt() and 0xFF
                val dpx = if (dist[i] >= HoleOps.FAR) Float.MAX_VALUE else dist[i] / HoleOps.CHAMFER_STEP.toFloat()
                val ramp = ((reach - dpx) / 1.5f).coerceIn(0f, 1f)
                val v = max(s, (ramp * 255f).roundToInt())
                weight[i] = v.toByte()
            }
        }
        val holeArea = ByteArray(aw * ah)
        var holePixels = 0
        for (i in weight.indices) if (weight[i].toInt() != 0) { holeArea[i] = 1; holePixels++ }
        if (holePixels == 0) return null
        if (holePixels > params.maxHolePixels) throw InpaintException(TOO_LARGE)
        val box = HoleOps.bounds(holeArea, aw, ah)!!
        val changed = IRect(area.left + box.left, area.top + box.top, area.left + box.right, area.top + box.bottom)
        val holeInChanged = HoleOps.crop(holeArea, area, changed)
        val radius = HoleOps.holeRadius(holeInChanged, changed, imgW, imgH)
        if (radius == Int.MAX_VALUE) throw InpaintException(NOTHING_AROUND)

        val p = if (radius <= InpaintEngine.THIN_RADIUS) 5 else 7
        val band = (3 * radius + 4 * p).coerceIn(AUTO_BAND_MIN, AUTO_BAND_MAX)
        var roi = when (params.sampling) {
            SamplingArea.AUTO -> changed.expand(band + p)
            SamplingArea.RECTANGLE -> changed.expand(ceil(changed.width * 1.5).toInt() + p, ceil(changed.height * 1.5).toInt() + p)
            SamplingArea.WHOLE -> IRect(0, 0, imgW, imgH)
        }.clip(imgW, imgH)
        if (roi.area > params.maxRoiPixels) {
            // Keep the region around the hole that fits the budget.
            if (changed.expand(p).clip(imgW, imgH).area > params.maxRoiPixels) throw InpaintException(TOO_LARGE)
            var lo = p; var hi = max(imgW, imgH)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (changed.expand(mid).clip(imgW, imgH).area <= params.maxRoiPixels) lo = mid else hi = mid - 1
            }
            roi = roi.intersect(changed.expand(lo).clip(imgW, imgH))
        }
        val hole = HoleOps.crop(holeArea, area, roi)
        // AUTO: only known pixels within `band` of the hole are sources.
        val excluded = if (params.sampling == SamplingArea.AUTO) bandExclusion(hole, roi.width, roi.height, band) else null
        val w = HoleOps.crop(weight, area, changed)
        return InpaintPlan(imgW, imgH, roi, changed, holePixels, radius, hole, excluded, w)
    }

    /**
     * Fills the hole of [plan]. [pixels] are the NON-premultiplied pixels of [InpaintPlan.roi].
     * Blocking and CPU heavy (uses all cores): call it off the main thread. Throws
     * [kotlin.coroutines.cancellation.CancellationException] when [monitor] reports cancellation,
     * [InpaintException] when there is nothing to sample from.
     */
    fun run(plan: InpaintPlan, pixels: PixelBuffer, params: InpaintParams = InpaintParams(), monitor: InpaintMonitor = InpaintMonitor()): InpaintResult =
        InpaintEngine(plan, params, monitor).run(pixels)

    /** Convenience: plan + run on a whole image in memory (tests, small images). Null for an empty hole. */
    fun fill(image: PixelBuffer, hole: ByteArray, params: InpaintParams = InpaintParams(), monitor: InpaintMonitor = InpaintMonitor()): InpaintResult? {
        require(hole.size == image.size) { "hole size ${hole.size} != image ${image.width}x${image.height}" }
        val plan = plan(HoleMask.full(image.width, image.height, hole), params) ?: return null
        return run(plan, crop(image, plan.roi), params, monitor)
    }

    /** [image] with the fill applied (see [InpaintResult.composite]). */
    fun apply(image: PixelBuffer, result: InpaintResult, alphaLocked: Boolean = false): PixelBuffer {
        val out = image.copy()
        val r = result.rect
        val orig = IntArray(r.width * r.height)
        for (y in 0 until r.height) System.arraycopy(image.pixels, (r.top + y) * image.width + r.left, orig, y * r.width, r.width)
        val comp = result.composite(orig, alphaLocked)
        for (y in 0 until r.height) System.arraycopy(comp, y * r.width, out.pixels, (r.top + y) * image.width + r.left, r.width)
        return out
    }

    /** The [r] part of [image] as a new buffer. */
    fun crop(image: PixelBuffer, r: IRect): PixelBuffer {
        val out = PixelBuffer(r.width, r.height)
        for (y in 0 until r.height) System.arraycopy(image.pixels, (r.top + y) * image.width + r.left, out.pixels, y * r.width, r.width)
        return out
    }

    /**
     * 1 for pixels farther than [band] px from the [hole] ([w] x [h]), null if there are none.
     * Measured on a 4x coarser grid (the band's exact edge doesn't matter; this keeps planning
     * cheap on big regions) and rounded towards including more pixels.
     */
    private fun bandExclusion(hole: ByteArray, w: Int, h: Int, band: Int): ByteArray? {
        val sh = BAND_GRID_SHIFT
        val cw = ((w - 1) shr sh) + 1; val ch = ((h - 1) shr sh) + 1
        val coarse = ByteArray(cw * ch)
        for (y in 0 until h) {
            val row = y * w
            val crow = (y shr sh) * cw
            for (x in 0 until w) if (hole[row + x].toInt() != 0) coarse[crow + (x shr sh)] = 1
        }
        val d = HoleOps.chamferDistance(coarse, cw, ch)
        val limit = ((band shr sh) + 1) * HoleOps.CHAMFER_STEP
        val ex = ByteArray(w * h)
        var any = false
        for (y in 0 until h) {
            val row = y * w
            val crow = (y shr sh) * cw
            for (x in 0 until w) if (d[crow + (x shr sh)] > limit) { ex[row + x] = 1; any = true }
        }
        return if (any) ex else null
    }

    private const val BAND_GRID_SHIFT = 2

    /** Smallest / largest width of the automatic sampling band (px). */
    const val AUTO_BAND_MIN = 48
    const val AUTO_BAND_MAX = 400

    const val TOO_LARGE = "The area to fill is too large. Select a smaller area."
    const val NOTHING_AROUND = "There is nothing around the selection to fill it from."
}
