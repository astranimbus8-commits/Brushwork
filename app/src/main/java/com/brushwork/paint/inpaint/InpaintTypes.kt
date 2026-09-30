package com.brushwork.paint.inpaint

import kotlin.coroutines.cancellation.CancellationException

/*
 * Content-aware fill (image completion) engine: pure Kotlin on NON-premultiplied ARGB pixels and
 * byte masks, no android.* imports, so it runs (and is tested) on the JVM.
 *
 * Pipeline (see ContentAwareFill): plan() turns a soft hole mask into a binary hole, a composite
 * weight and the region of the image to read; run() fills the hole with multi-scale PatchMatch
 * completion (Wexler EM voting + Barnes PatchMatch + Newson's refinements) and returns the fill.
 */

/** Pixel rectangle, right / bottom exclusive. */
data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val area: Long get() = if (isEmpty) 0L else width.toLong() * height

    fun expand(dx: Int, dy: Int = dx) = IRect(left - dx, top - dy, right + dx, bottom + dy)

    /** This rectangle clipped to [0, w) x [0, h). */
    fun clip(w: Int, h: Int) = IRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))

    fun intersect(o: IRect) = IRect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    fun contains(o: IRect) = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom
}

/**
 * The area to fill: soft coverage (0..255, like a selection) of the pixels in [rect] of an image
 * of [imageWidth] x [imageHeight]; everything outside [rect] is not part of the hole.
 */
class HoleMask(val imageWidth: Int, val imageHeight: Int, val rect: IRect, val alpha: ByteArray) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "empty image" }
        require(IRect(0, 0, imageWidth, imageHeight).contains(rect)) { "hole rect $rect outside ${imageWidth}x$imageHeight" }
        require(alpha.size.toLong() == rect.area) { "alpha size ${alpha.size} != ${rect.width}x${rect.height}" }
    }

    companion object {
        /** A mask covering the whole image. */
        fun full(width: Int, height: Int, alpha: ByteArray) = HoleMask(width, height, IRect(0, 0, width, height), alpha)
    }
}

/** Where source patches may come from (Photoshop's sampling area). */
enum class SamplingArea {
    /** A band around the hole whose width grows with the hole (default). */
    AUTO,

    /** The hole's bounding box grown by 1.5x its size on each side. */
    RECTANGLE,

    /** The whole image (up to [InpaintParams.maxRoiPixels] around the hole). */
    WHOLE,
}

/** Options of one fill. The same params + seed always give the same result. */
data class InpaintParams(
    val sampling: SamplingArea = SamplingArea.AUTO,
    /** Grow the hole by this many pixels (0..[MAX_EXPAND]) to cover halos / anti-aliased edges. */
    val expand: Int = DEFAULT_EXPAND,
    /** Smoothly corrects brightness / color differences at the fill's edge (membrane). */
    val colorAdaptation: Boolean = true,
    /** Different seeds give different plausible fills ("Refill"). */
    val seed: Int = 1,
    /** Above this many hole pixels the search runs downscaled (then refined at full size). */
    val maxWorkingHolePixels: Int = 250_000,
    /** Above this many pixels of sampling region the search runs downscaled. */
    val maxWorkingRoiPixels: Int = 1_500_000,
    /** Largest region read around the hole (WHOLE sampling is cropped to it). */
    val maxRoiPixels: Int = 8_000_000,
    /** Largest hole that can be filled at all. */
    val maxHolePixels: Int = 6_000_000,
    /** Weight of the texture features (Newson's lambda) in the patch distance. */
    val textureWeight: Float = 50f,
    /** Record where each filled pixel was copied from ([InpaintResult.sources]; for tests). */
    val recordSources: Boolean = false,
) {
    companion object {
        const val MAX_EXPAND = 10
        const val DEFAULT_EXPAND = 2
    }
}

/** A fill can't be done (the message is meant for the user). */
class InpaintException(message: String) : RuntimeException(message)

/**
 * Cancellation + progress for a running fill. [cancelled] is polled often (from worker threads);
 * [onProgress] receives 0..1 (from worker threads).
 */
class InpaintMonitor(
    private val cancelled: () -> Boolean = { false },
    private val onProgress: (Float) -> Unit = {},
) {
    fun isCancelled(): Boolean = cancelled()

    fun checkCancelled() {
        if (cancelled()) throw CancellationException("Content-aware fill cancelled")
    }

    internal fun progress(fraction: Float) = onProgress(fraction.coerceIn(0f, 1f))
}

/**
 * What [ContentAwareFill.plan] decided: the region of the image to read ([roi]) and the region the
 * fill changes ([changed]). Pass the [roi] pixels to [ContentAwareFill.run].
 */
class InpaintPlan internal constructor(
    val imageWidth: Int,
    val imageHeight: Int,
    /** Region of the image the fill reads (hole + sampling area). */
    val roi: IRect,
    /** Region the fill may change (bounding box of the expanded hole). */
    val changed: IRect,
    /** Number of pixels that are synthesized. */
    val holePixels: Int,
    /** Hole "radius": number of 3x3 erosions that empty it. */
    val holeRadius: Int,
    /** [roi]-sized, 1 = hole (unknown, synthesized). */
    internal val hole: ByteArray,
    /** [roi]-sized, 1 = known but not a source (outside the sampling band), or null. */
    internal val excluded: ByteArray?,
    /** [changed]-sized composite weight 0..255 of the fill over the original. */
    internal val weight: ByteArray,
)

/**
 * A finished fill over [rect] (= [InpaintPlan.changed], image coordinates): [fill] holds the
 * synthesized NON-premultiplied colors (0 where [weight] is 0), [weight] how much of them replaces
 * the original (0..255, soft at the edge).
 */
class InpaintResult internal constructor(
    val rect: IRect,
    val fill: IntArray,
    val weight: ByteArray,
    val seed: Int,
    /** When requested: image index of the pixel each filled pixel was copied from (-1 = none). */
    val sources: IntArray?,
) {
    /**
     * The original [rect] pixels ([original], non-premultiplied, rect-sized) with the fill blended
     * in by [weight]. With [alphaLocked] the original transparency is kept.
     */
    fun composite(original: IntArray, alphaLocked: Boolean = false): IntArray {
        require(original.size == fill.size) { "original size ${original.size} != ${fill.size}" }
        val out = IntArray(original.size)
        for (i in out.indices) {
            val a = weight[i].toInt() and 0xFF
            val o = original[i]
            if (a == 0) { out[i] = o; continue }
            val f = fill[i]
            if (a == 255 && !alphaLocked) { out[i] = f; continue }
            val oa = o ushr 24
            val fa = f ushr 24
            val ow = oa * (255 - a)
            val fw = fa * a
            val sw = ow + fw
            if (alphaLocked) {
                if (oa == 0 || sw == 0) { out[i] = o; continue }
                val r = (((o shr 16) and 0xFF) * ow + ((f shr 16) and 0xFF) * fw + sw / 2) / sw
                val g = (((o shr 8) and 0xFF) * ow + ((f shr 8) and 0xFF) * fw + sw / 2) / sw
                val b = ((o and 0xFF) * ow + (f and 0xFF) * fw + sw / 2) / sw
                out[i] = (oa shl 24) or (r shl 16) or (g shl 8) or b
                continue
            }
            val na = (sw + 127) / 255
            if (na == 0 || sw == 0) { out[i] = 0; continue }
            val r = (((o shr 16) and 0xFF) * ow + ((f shr 16) and 0xFF) * fw + sw / 2) / sw
            val g = (((o shr 8) and 0xFF) * ow + ((f shr 8) and 0xFF) * fw + sw / 2) / sw
            val b = ((o and 0xFF) * ow + (f and 0xFF) * fw + sw / 2) / sw
            out[i] = (na shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** The fill alone as layer pixels: its colors with alpha scaled by [weight] ("output to new layer"). */
    fun layerPixels(): IntArray {
        val out = IntArray(fill.size)
        for (i in out.indices) {
            val a = weight[i].toInt() and 0xFF
            if (a == 0) continue
            val f = fill[i]
            val na = ((f ushr 24) * a + 127) / 255
            out[i] = if (na == 0) 0 else (na shl 24) or (f and 0xFFFFFF)
        }
        return out
    }

    /** True if no pixel is (even partly) replaced. */
    val isEmpty: Boolean get() = weight.all { it.toInt() == 0 }

    /**
     * This fill limited to [clip] ([rect]-sized coverage 0..255, e.g. the selection): the weight
     * is multiplied by it, so nothing changes where [clip] is 0.
     */
    fun clipped(clip: ByteArray): InpaintResult {
        require(clip.size == weight.size) { "clip size ${clip.size} != ${weight.size}" }
        val w = ByteArray(weight.size) { i ->
            (((weight[i].toInt() and 0xFF) * (clip[i].toInt() and 0xFF) + 127) / 255).toByte()
        }
        return InpaintResult(rect, fill, w, seed, sources)
    }
}
