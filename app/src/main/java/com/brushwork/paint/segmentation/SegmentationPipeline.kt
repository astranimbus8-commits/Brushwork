package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterMath
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Device-independent segmentation pipeline (pure Kotlin; the Android backends are injected).
 *
 * 1. The image is flattened over white and area-downscaled to a working copy (long side <=
 *    [workMaxSide]) and a smaller "matte" copy (<= [MATTE_MAX_SIDE]) for edge refinement.
 * 2. Scene targets: the scene model runs several passes over the working copy ([ScenePasses]:
 *    the letterboxed picture, overlapping aspect-fill crops, a mirrored letterbox; as many as
 *    fit in [passBudgetMs]), whose per-class probabilities are fused ([SceneFusion]) and summed
 *    per target with the "other"-class adoption rules ([SceneTargets]), then cleaned by
 *    hysteresis. Subject: ML Kit plus a zoomed second pass on the subject; without ML Kit, the
 *    object model tapped on the most salient object, or people + saliency.
 * 3. Edges: trimap + band matting with local color models ([Matting]) at matte resolution, then
 *    a color guided filter whose coefficients are evaluated with the FULL-resolution colors
 *    ([ColorGuidedFilter.upsample]), so only the output is allocated at full size.
 *
 * The analysis of the last image (working copies, fused scene probabilities, subject mask,
 * heuristics) is cached by content hash, so several targets on the same image run each model
 * once. Thread-safe; [segment] and [selectObject] are blocking.
 */
class SegmentationPipeline(
    private val sceneParser: SceneParser?,
    private val subjectBackend: SubjectBackend?,
    private val interactiveModel: InteractiveModel? = null,
    private val workMaxSide: Int = WORK_MAX_SIDE,
    private val passBudgetMs: Long = PASS_BUDGET_MS,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile private var cached: Analysis? = null

    /**
     * Per-pixel confidence 0..1 (size = image.size) for [target], or null if nothing could run.
     * Throws [CancellationException] when the calling thread is interrupted.
     */
    fun segment(image: PixelBuffer, target: SmartTarget): FloatArray? {
        if (target == SmartTarget.BACKGROUND) {
            val subject = segment(image, SmartTarget.SUBJECT) ?: return null
            for (i in subject.indices) subject[i] = 1f - subject[i]
            return subject
        }
        val check = { checkInterrupted() }
        check()
        val a = analysis(image)
        check()
        val soft = if (target == SmartTarget.SUBJECT) subjectSoft(a, image, check) else sceneSoft(a, target, check)
        check()
        if (soft == null) return FloatArray(image.size)
        return finish(a, image, soft, refine = true, check)
    }

    /** Result of [selectObject]: the selection strength per pixel, and whether the AI model made it. */
    class ObjectResult(val mask: FloatArray, val usedModel: Boolean)

    /**
     * Selects the object under [prompt] (image coordinates): the interactive model when it is
     * available, color region growing otherwise. [refineEdges] adds band matting (hair, fur,
     * foliage); without it the model's own outline is kept. Throws [CancellationException] when
     * [cancelled] turns true or the thread is interrupted.
     */
    fun selectObject(image: PixelBuffer, prompt: ObjectPrompt, refineEdges: Boolean = true, cancelled: () -> Boolean = { false }): ObjectResult {
        val check = {
            checkInterrupted()
            if (cancelled()) throw CancellationException("object selection cancelled")
        }
        check()
        val a = analysis(image)
        check()
        val wp = prompt.scaled(a.w.toFloat() / image.width, a.h.toFloat() / image.height)
        var usedModel = false
        var p: FloatArray? = null
        val model = interactiveModel
        if (model != null) {
            p = try {
                InteractiveSegmenter.segment(a.work, image, wp, model, check)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                log("object model failed", e)
                null
            }
            usedModel = p != null
        }
        check()
        val soft = p ?: RegionGrow.select(a.work, wp, check)
        if (MaskOps.maxValue(soft) < 0.5f) return ObjectResult(FloatArray(image.size), usedModel)
        val matteSoft = MaskOps.resizeArea(soft, a.w, a.h, a.mw, a.mh)
        // One model pixel of the first pass, in matte pixels: the uncertainty of the outline.
        val first = InteractiveSegmenter.firstCrop(a.w, a.h, 0f, 0f)
        val modelPx = if (usedModel) first.long.toFloat() / InteractiveSegmenter.MODEL_SIZE * a.matteScale else 0f
        val band = max(1.5f, max(modelPx * 1.5f, a.matteLong / 400f))
        val params = Matting.Params(band = band, widen = 1.5f, rounds = 2, radius = max(1, (band / 2f).roundToInt()), eps = 1e-4f)
        return ObjectResult(finish(a, image, Soft(matteSoft, params), refineEdges, check), usedModel)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("segmentation interrupted")
    }

    /** Drops the cached analysis (e.g. on memory pressure). */
    fun clearCache() {
        cached = null
    }

    /** A soft mask at matte resolution plus how to refine its edges. */
    private class Soft(val p: FloatArray, val params: Matting.Params)

    /** Edge refinement at matte resolution and the full-resolution color guided upsample. */
    private fun finish(a: Analysis, image: PixelBuffer, soft: Soft, refine: Boolean, check: () -> Unit): FloatArray {
        val alpha = if (refine) {
            Matting.refine(a.planes, soft.p, soft.params)
        } else {
            FloatArray(soft.p.size) { MaskOps.smoothstep(0.35f, 0.65f, soft.p[it]) }
        }
        check()
        val coef = ColorGuidedFilter.coefficients(a.planes, alpha, a.finalRadius, FINAL_EPS)
        check()
        return ColorGuidedFilter.upsample(image, coef)
    }

    // ------------------------------------------------------------------ scene targets

    private fun sceneSoft(a: Analysis, target: SmartTarget, check: () -> Unit): Soft? {
        val scene = a.scene(check)
        if (scene == null) {
            val hm = a.heuristic(target)
            if (MaskOps.maxValue(hm) < 0.05f) return null
            return Soft(a.contentToMatte(hm), Matting.Params(band = a.heuristicBand, widen = 1f, rounds = 2, radius = max(2, (a.heuristicBand / 2f).roundToInt())))
        }
        val p = a.targetProbability(scene, target) ?: return null
        val band = max(2f, scene.strideWork * a.matteScale * 0.75f)
        val sky = target == SmartTarget.SKY
        val params = Matting.Params(
            band = band,
            widen = 1f,
            globalColorModel = sky,
            colorReach = if (sky) band * 4f else 0f,
            rounds = 2,
            radius = max(2, (band / 2f).roundToInt()),
        )
        return Soft(MaskOps.resizeBilinear(p, scene.fw, scene.fh, a.mw, a.mh), params)
    }

    // ------------------------------------------------------------------ subject

    private fun subjectSoft(a: Analysis, image: PixelBuffer, check: () -> Unit): Soft? {
        val ml = a.subjectMask(image)
        check()
        // ML Kit finds nothing confident on many drawings: use the fallback instead of an empty mask.
        if (ml != null && MaskOps.maxValue(ml) >= 0.5f) {
            val band = max(1.5f, a.matteLong / 320f)
            return Soft(MaskOps.resizeArea(ml, a.w, a.h, a.mw, a.mh), Matting.Params(band = band, widen = 1.5f, rounds = 1, radius = max(1, band.roundToInt())))
        }
        val scene = a.scene(check)
        val person = scene?.let { s -> a.targetProbability(s, SmartTarget.PEOPLE)?.let { MaskOps.resizeBilinear(it, s.fw, s.fh, a.mw, a.mh) } }
        val tapped = a.interactiveSubject(image, check)?.let { MaskOps.resizeArea(it, a.w, a.h, a.mw, a.mh) }
        val base = tapped ?: a.contentToMatte(a.saliencyContent)
        val m = if (person != null) MaskOps.pointwiseMax(person, base) else base
        if (MaskOps.maxValue(m) < 0.05f) return null
        return Soft(m, Matting.Params(band = a.heuristicBand, widen = 1.5f, rounds = 2, radius = max(2, (a.heuristicBand / 2f).roundToInt())))
    }

    private fun analysis(image: PixelBuffer): Analysis {
        val key = MaskOps.contentHash(image)
        cached?.let { if (it.key == key && it.fullWidth == image.width && it.fullHeight == image.height) return it }
        return Analysis(key, image).also { cached = it }
    }

    /** Fused scene-model probabilities of one image on the fusion grid. */
    internal class SceneResult(val fw: Int, val fh: Int, val probs: FloatArray, val strideWork: Float, val passes: Int)

    /** Everything derived from one image that several targets share (never the full image). */
    private inner class Analysis(val key: Long, image: PixelBuffer) {
        val fullWidth = image.width
        val fullHeight = image.height
        val w: Int
        val h: Int
        val work: PixelBuffer
        val mw: Int
        val mh: Int
        val letterbox: Letterbox
        val content: PixelBuffer

        init {
            val size = MaskOps.fitWithin(image.width, image.height, workMaxSide)
            w = size[0]; h = size[1]
            work = MaskOps.resample(image, w, h)
            val m = MaskOps.fitWithin(w, h, MATTE_MAX_SIDE)
            mw = m[0]; mh = m[1]
            letterbox = Letterbox(w, h)
            content = MaskOps.resample(work, letterbox.contentWidth, letterbox.contentHeight)
        }

        val matte: PixelBuffer by lazy { MaskOps.resample(work, mw, mh) }
        val planes: ColorPlanes by lazy { ColorPlanes.of(matte) }
        val matteScale: Float get() = mw.toFloat() / w
        val matteLong: Int get() = max(mw, mh)

        /** Final guided-filter radius (matte px): ~2 px at 768. */
        val finalRadius: Int get() = max(1, (matteLong / 384f).roundToInt())

        /** Unknown-band half-width for heuristic masks. */
        val heuristicBand: Float get() = max(2f, matteLong / 128f)

        fun contentToMatte(m: FloatArray): FloatArray =
            MaskOps.resizeBilinear(m, letterbox.contentWidth, letterbox.contentHeight, mw, mh)

        // ---------------------------------------------------------------- scene model

        private val sceneLock = Any()
        private var sceneDone = false
        private var sceneResult: SceneResult? = null

        /** Fused class probabilities, or null if the scene model is unavailable. */
        fun scene(check: () -> Unit): SceneResult? {
            synchronized(sceneLock) {
                if (!sceneDone) {
                    sceneResult = sceneParser?.let { runScenePasses(it, check) }
                    sceneDone = true
                }
                return sceneResult
            }
        }

        private fun runPass(parser: SceneParser, input: PixelBuffer, geo: PassGeometry): PassGrid? {
            val scores = try {
                parser.run(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("scene parser failed", e)
                null
            } ?: return null
            return SceneProbabilities.fromScores(scores, ScenePasses.SIZE, geo.valid).also {
                if (it == null) log("scene parser returned an unusable result", null)
            }
        }

        private fun runScenePasses(parser: SceneParser, check: () -> Unit): SceneResult? {
            val (fw, fh) = SceneFusion.gridSize(w, h).let { it[0] to it[1] }
            val fusion = SceneFusion(w, h, fw, fh)
            val global = ScenePasses.global(w, h)
            val t0 = System.nanoTime()
            val first = runPass(parser, ScenePasses.renderGlobal(work, global), global) ?: return null
            fusion.add(first, global)
            var strideWork = first.stride / global.sx
            var passes = 1
            val perPass = max(1L, (System.nanoTime() - t0) / 1_000_000)
            // The pass set is chosen up front from the measured cost of the first pass, so it
            // is always balanced (never one lone crop that sharpens half of the picture).
            val affordable = ((passBudgetMs - perPass) / perPass).toInt()
            val extra = ScenePasses.plan(ScenePasses.crops(w, h), ScenePasses.global(w, h, flip = true), affordable)
            var filled: PixelBuffer? = null
            for (geo in extra) {
                // Safety net for passes that turn out much slower than the first (thermal throttling).
                val spent = (System.nanoTime() - t0) / 1_000_000
                if (spent + perPass > passBudgetMs + passBudgetMs / 4) break
                check()
                val input = if (geo.kind == PassGeometry.Kind.CROP) {
                    val f = filled ?: ScenePasses.fillSize(w, h).let { s -> MaskOps.resampleSmooth(work, s[0], s[1]) }.also { filled = it }
                    ScenePasses.renderCrop(f, geo)
                } else {
                    ScenePasses.renderGlobal(work, geo)
                }
                val grid = runPass(parser, input, geo) ?: continue
                fusion.add(grid, geo)
                strideWork = min(strideWork, grid.stride / geo.sx)
                passes++
            }
            check()
            return SceneResult(fw, fh, fusion.result(), strideWork, passes)
        }

        /** The working image area-averaged to the fusion grid (colors for the adoption rules). */
        private val cells: PixelBuffer by lazy { SceneFusion.gridSize(w, h).let { MaskOps.resample(work, it[0], it[1]) } }

        /** Hysteresis-cleaned probability of [target] on the fusion grid, or null if absent. */
        fun targetProbability(scene: SceneResult, target: SmartTarget): FloatArray? {
            val veg = if (target == SmartTarget.NATURE) {
                MaskOps.resizeArea(vegetationContent, letterbox.contentWidth, letterbox.contentHeight, scene.fw, scene.fh)
            } else null
            val p = SceneTargets.probability(scene.probs, scene.fw, scene.fh, target, cells, veg)
            // The model ran and found nothing: trust it (a heuristic would e.g. call a white ceiling sky).
            if (MaskOps.maxValue(p) < 0.5f) return null
            val clean = Hysteresis.apply(p, scene.fw, scene.fh, high = 0.6f, low = 0.35f, minSize = 2)
            return if (MaskOps.maxValue(clean) < 0.5f) null else clean
        }

        // ---------------------------------------------------------------- subject

        /** Interruptible: the holder may spend a long time in ML Kit (module download). */
        private val subjectLock = ReentrantLock()
        private var subject: FloatArray? = null

        /**
         * Subject confidence at working resolution (ML Kit, refined by a zoomed second pass);
         * failures are retried on the next call.
         */
        fun subjectMask(image: PixelBuffer): FloatArray? {
            try {
                subjectLock.lockInterruptibly()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("segmentation interrupted")
            }
            try {
                subject?.let { return it }
                val backend = subjectBackend ?: return null
                // ML Kit wants at least ~512 px; small images use the (enlarged) letterbox content.
                val input = if (max(w, h) >= letterbox.size) work else content
                // A sliver (e.g. 1280x1) has no subject to find, and ML Kit may reject it.
                if (min(input.width, input.height) < MIN_SUBJECT_SIDE) return null
                val raw = callSubject(backend, input) ?: return null
                val clamped = FloatArray(raw.size) { MaskOps.clamp01(raw[it]) }
                val m = if (input === work) clamped else MaskOps.resizeBilinear(clamped, input.width, input.height, w, h)
                val zoomed = if (MaskOps.maxValue(m) >= 0.5f) zoomSubject(backend, image, m, input.width.toFloat() / w) else m
                return zoomed.also { subject = it }
            } finally {
                subjectLock.unlock()
            }
        }

        private fun callSubject(backend: SubjectBackend, input: PixelBuffer): FloatArray? {
            val raw = try {
                backend.subjectMask(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("subject backend failed", e)
                null
            } ?: return null
            if (raw.size != input.size) {
                log("subject backend returned ${raw.size} values for ${input.width}x${input.height}", null)
                return null
            }
            return raw
        }

        /**
         * Second ML Kit pass on the subject's bounding box (+10 %), cut from the FULL image, when
         * that shows the subject at least 1.5x larger than the first pass did. Its answer replaces
         * the first one only where that was unsure (the edge band), and only if both agree on
         * the subject (IoU >= 0.6 inside the box).
         */
        private fun zoomSubject(backend: SubjectBackend, image: PixelBuffer, m: FloatArray, inputScale: Float): FloatArray {
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (m[y * w + x] >= 0.5f) {
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x1 < 0) return m
            val mx = (x1 - x0 + 1) * 0.1f; val my = (y1 - y0 + 1) * 0.1f
            val rx0 = max(0, floor(x0 - mx).toInt()); val ry0 = max(0, floor(y0 - my).toInt())
            val rx1 = min(w, ceil(x1 + 1 + mx).toInt()); val ry1 = min(h, ceil(y1 + 1 + my).toInt())
            val rw = rx1 - rx0; val rh = ry1 - ry0
            if (rw < 8 || rh < 8) return m
            val fs = image.width.toFloat() / w
            val fx0 = floor(rx0 * fs).toInt(); val fy0 = floor(ry0 * (image.height.toFloat() / h)).toInt()
            val fx1 = min(image.width, ceil(rx1 * fs).toInt()); val fy1 = min(image.height, ceil(ry1 * (image.height.toFloat() / h)).toInt())
            val fullLong = max(fx1 - fx0, fy1 - fy0)
            if (fullLong < ZOOM_MIN_SOURCE) return m
            val targetLong = fullLong.coerceIn(512, max(512, workMaxSide))
            val gain = targetLong / (max(rw, rh) * inputScale)
            if (gain < ZOOM_MIN_GAIN) return m
            val s = targetLong.toFloat() / fullLong
            val tw = max(MIN_SUBJECT_SIDE, ((fx1 - fx0) * s).roundToInt()); val th = max(MIN_SUBJECT_SIDE, ((fy1 - fy0) * s).roundToInt())
            val crop = MaskOps.resampleSmooth(MaskOps.crop(image, fx0, fy0, fx1, fy1), tw, th)
            checkInterrupted()
            val z = callSubject(backend, crop) ?: return m
            for (i in z.indices) z[i] = MaskOps.clamp01(z[i])
            val zw = MaskOps.resizeArea(z, tw, th, rw, rh)
            val gw = MaskOps.cropPlane(m, w, rx0, ry0, rx1, ry1)
            var inter = 0; var union = 0
            for (i in zw.indices) {
                val a = zw[i] >= 0.5f; val b = gw[i] >= 0.5f
                if (a && b) inter++
                if (a || b) union++
            }
            if (union == 0 || inter < 0.6f * union) return m
            // Where the first pass was unsure (plus a margin), take the zoomed answer.
            val unsure = FloatArray(rw * rh) { if (gw[it] > 0.05f && gw[it] < 0.95f) 1f else 0f }
            val dist = FilterMath.distanceToCoverage(unsure, rw, rh, 0.5f)
            val reach = max(2f, max(rw, rh) / 50f)
            val out = m.copyOf()
            for (y in 0 until rh) for (x in 0 until rw) {
                val i = y * rw + x
                val t = MaskOps.clamp01(1f - (dist[i] - reach) / reach)
                if (t > 0f) out[(ry0 + y) * w + rx0 + x] = gw[i] + (zw[i] - gw[i]) * t
            }
            return out
        }

        /**
         * No ML Kit: the interactive model tapped at the heart of the most salient object (the
         * point farthest from its outline). Null if there is no model or the answer is implausible.
         */
        fun interactiveSubject(image: PixelBuffer, check: () -> Unit): FloatArray? {
            synchronized(tapLock) {
                if (!tapDone) {
                    // A cancellation throws before anything is cached.
                    tapResult = computeInteractiveSubject(image, check)
                    tapDone = true
                }
                return tapResult
            }
        }

        private val tapLock = Any()
        private var tapDone = false
        private var tapResult: FloatArray? = null

        private fun computeInteractiveSubject(image: PixelBuffer, check: () -> Unit): FloatArray? {
            val model = interactiveModel ?: return null
            val sal = MaskOps.resizeBilinear(saliencyContent, letterbox.contentWidth, letterbox.contentHeight, w, h)
            val lab = Regions.label(BooleanArray(w * h) { sal[it] >= 0.5f }, w, h)
            if (lab.count == 0) return null
            var best = 1
            for (id in 2..lab.count) if (lab.sizes[id] > lab.sizes[best]) best = id
            val outside = FloatArray(w * h) { if (lab.ids[it] == best) 0f else 1f }
            val d = FilterMath.distanceToCoverage(outside, w, h, 0.5f)
            var bi = -1; var bd = -1f
            for (i in d.indices) if (lab.ids[i] == best && d[i] > bd) { bd = d[i]; bi = i }
            if (bi < 0) return null
            val p = try {
                InteractiveSegmenter.segment(work, image, ObjectPrompt.tap(bi % w + 0.5f, bi / w + 0.5f), model, check)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                log("object model failed", e)
                null
            } ?: return null
            val area = p.count { it >= 0.5f }.toFloat() / p.size
            return if (area in 0.002f..0.9f) p else null
        }

        // ---------------------------------------------------------------- heuristics

        private val skyContent by lazy { SceneHeuristics.sky(content) }
        val vegetationContent by lazy { SceneHeuristics.vegetation(content) }
        val saliencyContent: FloatArray by lazy { SceneHeuristics.saliency(content) }

        /** Heuristic fallback for [target] at letterbox-content resolution. */
        fun heuristic(target: SmartTarget): FloatArray = when (target) {
            SmartTarget.SKY -> skyContent
            SmartTarget.NATURE -> vegetationContent
            SmartTarget.BUILDINGS -> SceneHeuristics.buildings(content, skyContent, vegetationContent)
            SmartTarget.WATER -> SceneHeuristics.water(content, skyContent, vegetationContent)
            SmartTarget.PEOPLE -> SceneHeuristics.people(content, saliencyContent)
            SmartTarget.SUBJECT, SmartTarget.BACKGROUND -> saliencyContent
        }
    }

    companion object {
        /** Long side of the working resolution: models run here, output is upsampled. */
        const val WORK_MAX_SIDE = 1280

        /** Long side of the resolution where edges are refined (matting, guided coefficients). */
        const val MATTE_MAX_SIDE = 768

        /** Time budget for the scene model passes of one image (the global pass always runs). */
        const val PASS_BUDGET_MS = 2000L

        /** Shortest side worth sending to the subject backend (thinner inputs use the fallback). */
        const val MIN_SUBJECT_SIDE = 32

        /** The zoomed subject pass needs this many real pixels across the subject... */
        const val ZOOM_MIN_SOURCE = 256

        /** ...and must show it at least this much larger than the first pass. */
        const val ZOOM_MIN_GAIN = 1.5f

        private const val FINAL_EPS = 2e-4f
    }
}
