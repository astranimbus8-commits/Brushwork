package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Scene-parsing backend (the bundled LiteRT model on device, fakes in tests). */
fun interface SceneParser {
    /**
     * Class ids (0 until [SceneClasses.COUNT]) of the [Letterbox.size]² letterboxed model input
     * built from [content] (letterbox.contentWidth x contentHeight), or null if unavailable.
     */
    fun parse(content: PixelBuffer, letterbox: Letterbox): ByteArray?
}

/** Subject (salient foreground) backend (ML Kit on device, fakes in tests). */
fun interface SubjectBackend {
    /** Foreground confidence 0..1 for each pixel of the opaque [image], or null if unavailable. */
    fun subjectMask(image: PixelBuffer): FloatArray?
}

/**
 * Device-independent segmentation pipeline (pure Kotlin; the Android backends are injected).
 *
 * 1. The image is flattened over white and area-downscaled to a working copy (long side
 *    <= [workMaxSide]), and again into the letterbox content for the scene model (<= 512).
 * 2. A coarse mask is built at working resolution from the scene model classes (refined in a
 *    band around its boundary by local color statistics, fused with the sky heuristic for SKY),
 *    the subject backend, or the heuristics when a model is unavailable.
 * 3. A fast guided filter computes linear coefficients at working resolution and evaluates them
 *    with the FULL-resolution luma as guide, so edges follow the image at full detail while only
 *    the output is allocated at full size.
 *
 * The analysis of the last image (working copy, class map, subject mask, heuristics) is cached
 * by content hash, so asking for several targets on the same image runs each model once.
 * Thread-safe; [segment] is blocking.
 */
class SegmentationPipeline(
    private val sceneParser: SceneParser?,
    private val subjectBackend: SubjectBackend?,
    private val workMaxSide: Int = WORK_MAX_SIDE,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile private var cached: Analysis? = null

    /** Per-pixel confidence 0..1 (size = image.size) for [target], or null if nothing could run. */
    fun segment(image: PixelBuffer, target: SmartTarget): FloatArray? {
        if (target == SmartTarget.BACKGROUND) {
            val subject = segment(image, SmartTarget.SUBJECT) ?: return null
            for (i in subject.indices) subject[i] = 1f - subject[i]
            return subject
        }
        val a = analysis(image)
        val plan = if (target == SmartTarget.SUBJECT) subjectPlan(a) else scenePlan(a, target)
        if (plan === EMPTY) return FloatArray(image.size)
        val (meanA, meanB) = GuidedFilter.coefficients(a.lum, plan.mask, a.w, a.h, plan.radius, plan.eps)
        return MaskOps.guidedUpsample(image, meanA, meanB, a.w, a.h)
    }

    /** Drops the cached analysis (e.g. on memory pressure). */
    fun clearCache() {
        cached = null
    }

    /** Coarse working-resolution mask plus the guided-filter parameters to refine it with. */
    private class Plan(val mask: FloatArray, val radius: Int, val eps: Float)

    private fun subjectPlan(a: Analysis): Plan {
        val ml = a.subjectMask()
        // ML Kit finds nothing confident on many drawings: use the fallback instead of an empty mask.
        if (ml != null && MaskOps.maxValue(ml) >= 0.5f) return Plan(ml, a.fineRadius, 1e-3f)
        val person = a.classMask(SmartTarget.PEOPLE)
        val salient = a.saliencyWork
        val m = if (person != null) MaskOps.pointwiseMax(person, salient) else salient
        return if (MaskOps.maxValue(m) < 0.05f) EMPTY else Plan(m, a.radius, 2e-3f)
    }

    private fun scenePlan(a: Analysis, target: SmartTarget): Plan {
        val classMask = a.classMask(target)
        if (classMask == null) {
            val h = a.heuristic(target)
            return if (MaskOps.maxValue(h) < 0.05f) EMPTY else Plan(h, a.radius, 2e-3f)
        }
        // The model ran and found nothing: trust it (a heuristic would e.g. call a white ceiling sky).
        if (MaskOps.maxValue(classMask) < 0.5f) return EMPTY
        val extra = if (target == SmartTarget.SKY) a.skyHeuristicWork else null
        return Plan(MaskOps.refineBand(a.work, classMask, a.bandRadius, extra), a.radius, 1e-3f)
    }

    private fun analysis(image: PixelBuffer): Analysis {
        val key = MaskOps.contentHash(image)
        cached?.let { if (it.key == key && it.fullWidth == image.width && it.fullHeight == image.height) return it }
        return Analysis(key, image).also { cached = it }
    }

    /** Everything derived from one image that several targets share. */
    private inner class Analysis(val key: Long, image: PixelBuffer) {
        val fullWidth = image.width
        val fullHeight = image.height
        val w: Int
        val h: Int
        val work: PixelBuffer
        val lum: FloatArray
        val letterbox: Letterbox
        val content: PixelBuffer

        init {
            val size = MaskOps.fitWithin(image.width, image.height, workMaxSide)
            w = size[0]; h = size[1]
            work = MaskOps.resample(image, w, h)
            lum = MaskOps.luminance(work)
            letterbox = Letterbox(w, h)
            content = MaskOps.resample(work, letterbox.contentWidth, letterbox.contentHeight)
        }

        private val longSide = max(w, h)

        /** Guided-filter radius for coarse masks (~1/150 of the image). */
        val radius = max(2, (longSide / 150f).roundToInt())

        /** Guided-filter radius for masks that are already fine (ML Kit). */
        val fineRadius = max(1, (longSide / 400f).roundToInt())

        /** Half-width of the band re-classified around coarse model boundaries. */
        val bandRadius = max(3, (longSide / 80f).roundToInt())

        private val classLock = Any()
        private var classMap: ByteArray? = null

        /** Cropped class map (content resolution), or null if the scene parser is unavailable. */
        private fun classMap(): ByteArray? {
            synchronized(classLock) {
                classMap?.let { return it }
                val parser = sceneParser ?: return null
                val ids = try {
                    parser.parse(content, letterbox)
                } catch (e: Exception) {
                    log("scene parser failed", e)
                    null
                } ?: return null
                if (ids.size != letterbox.size * letterbox.size) {
                    log("scene parser returned ${ids.size} values", null)
                    return null
                }
                return letterbox.crop(ids).also { classMap = it }
            }
        }

        /** Binary scene-class mask for [target] upsampled (bilinear) to working resolution. */
        fun classMask(target: SmartTarget): FloatArray? {
            val map = classMap() ?: return null
            val cw = letterbox.contentWidth; val ch = letterbox.contentHeight
            return MaskOps.resizeBilinear(SceneClasses.targetMask(map, cw, ch, target), cw, ch, w, h)
        }

        private val subjectLock = Any()
        private var subject: FloatArray? = null

        /** Subject confidence at working resolution; failures are retried on the next call. */
        fun subjectMask(): FloatArray? {
            synchronized(subjectLock) {
                subject?.let { return it }
                val backend = subjectBackend ?: return null
                // ML Kit wants at least ~512 px; small images use the (enlarged) letterbox content.
                val input = if (max(w, h) >= letterbox.size) work else content
                val raw = try {
                    backend.subjectMask(input)
                } catch (e: Exception) {
                    log("subject backend failed", e)
                    null
                } ?: return null
                if (raw.size != input.size) {
                    log("subject backend returned ${raw.size} values for ${input.width}x${input.height}", null)
                    return null
                }
                val clamped = FloatArray(raw.size) { MaskOps.clamp01(raw[it]) }
                val m = if (input === work) clamped else MaskOps.resizeBilinear(clamped, input.width, input.height, w, h)
                return m.also { subject = it }
            }
        }

        private val skyContent by lazy { SceneHeuristics.sky(content) }
        private val vegetationContent by lazy { SceneHeuristics.vegetation(content) }
        private val saliencyContent by lazy { SceneHeuristics.saliency(content) }

        val saliencyWork: FloatArray by lazy { toWork(saliencyContent) }

        /** Sky heuristic at working resolution (sharper edges for fusion with the model). */
        val skyHeuristicWork: FloatArray by lazy { SceneHeuristics.sky(work) }

        /** Heuristic fallback for [target] at working resolution. */
        fun heuristic(target: SmartTarget): FloatArray = when (target) {
            SmartTarget.SKY -> skyHeuristicWork
            SmartTarget.NATURE -> toWork(vegetationContent)
            SmartTarget.BUILDINGS -> toWork(SceneHeuristics.buildings(content, skyContent, vegetationContent))
            SmartTarget.WATER -> toWork(SceneHeuristics.water(content, skyContent, vegetationContent))
            SmartTarget.PEOPLE -> toWork(SceneHeuristics.people(content, saliencyContent))
            SmartTarget.SUBJECT, SmartTarget.BACKGROUND -> saliencyWork
        }

        private fun toWork(m: FloatArray): FloatArray =
            MaskOps.resizeBilinear(m, letterbox.contentWidth, letterbox.contentHeight, w, h)
    }

    companion object {
        /** Long side of the working resolution: models and filters run here, output is upsampled. */
        const val WORK_MAX_SIDE = 1280

        private val EMPTY = Plan(FloatArray(0), 0, 1f)
    }
}
