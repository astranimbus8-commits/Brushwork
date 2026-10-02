package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.filters.PixelMapper
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.mask.MaskPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * v1.6 §3.1 C1: the fused NORMAL path of [AdjustmentStage] (direct `setPixels` into the target)
 * against a float reference and against a frozen copy of the v1.5 Skia path
 * ([AdjustmentStageV15Reference]): every live filter at opacities 0.2 / 0.5 / 1, spec and painted
 * masks, opaque and semi-transparent composites, Gradation Map's transparent stops, grayscale and
 * 1-bit documents, a scaled target and the Masks tool's half-resolution preview override. Also:
 * `directWrite = false` still runs the v1.5 path exactly, and the fused path's speed on a masked
 * 512² tile.
 */
@RunWith(RobolectricTestRunner::class)
class AdjustmentFusedPathRobolectricTest {
    // Two chunks across (CHUNK = 512) and over the parallel threshold.
    private val w = 600
    private val h = 90
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rnd = Random(16)

    @After
    fun tearDown() {
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun photo(doc: Document, alphaEnd: Int = 0xFF, hole: Boolean = false): Layer =
        Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(doc.width, doc.height)).also {
            val c = Canvas(it.bitmap)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), 0xFF2040C0.toInt(), (alphaEnd shl 24) or 0xF0E020, Shader.TileMode.CLAMP) })
            c.drawCircle(doc.width * 0.6f, doc.height * 0.4f, doc.height * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC03050.toInt() })
            if (hole) c.drawRect(0f, 0f, doc.width * 0.15f, doc.height.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }

    private fun adjustment(doc: Document, spec: AdjustmentSpec, mask: MaskSpec?, opacity: Float = 1f): Layer =
        Layer(doc.newLayerId(), "Adjust", BitmapUtils.createLayerBitmap(doc.width, doc.height)).also {
            it.adjustment = spec
            it.opacity = opacity
            if (mask != null) {
                it.mask = MaskSpecs.newMask(mask, doc.width, doc.height)
                it.maskSpec = mask
            }
        }

    private fun ramp(doc: Document) = MaskSpec(
        components = listOf(
            LinearMask(1, x0 = doc.width * 0.1f, y0 = 0f, x1 = doc.width * 0.85f, y1 = doc.height.toFloat()),
            RadialMask(2, cx = doc.width * 0.3f, cy = doc.height * 0.5f, rx = doc.width * 0.2f, ry = doc.height * 0.6f, feather = 0.7f),
        ),
        nextId = 3,
    )

    /** A random value of [p] (as AdjustmentEffectsTest does). */
    private fun randomValue(p: FilterParam): Any? = when (p) {
        is FilterParam.Slider -> (p.min + (0.15f + rnd.nextFloat() * 0.7f) * (p.max - p.min)).let { if (p.step >= 1f) Math.round(it).toFloat() else it }
        is FilterParam.Toggle -> rnd.nextBoolean()
        is FilterParam.Choice -> rnd.nextInt(p.options.size)
        is FilterParam.Seed -> rnd.nextInt(1, 1_000_000)
        is FilterParam.Color -> rnd.nextInt() or 0xFF000000.toInt()
        is FilterParam.Curve -> listOf(CurvePoint(0f, 0.1f), CurvePoint(0.4f, 0.7f), CurvePoint(1f, 0.9f))
        is FilterParam.Gradient -> listOf(GradientStop(0f, 0xFF102040.toInt()), GradientStop(0.6f, 0xFFE07030.toInt()), GradientStop(1f, 0xFFFFF0C0.toInt()))
        else -> null
    }

    /** Values of [f] that change the picture and can be live. */
    private fun movedValues(f: Filter): FilterValues {
        repeat(40) {
            val v = f.defaultValues()
            for (p in f.params) randomValue(p)?.let { v.set(p.key, it) }
            if (AdjustmentEffects.isLive(f, v) && AdjustmentEffects.mapperOf(AdjustmentEffects.spec(f, v)) != null) return v
        }
        return f.defaultValues()
    }

    /** The adjustment layer [adj] (on top of [doc]) drawn by the production stage into a fresh target. */
    private fun produced(doc: Document, directWrite: Boolean, override: LayerRenderOverride? = null): IntArray {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        Compositor(doc) { override }.drawDocument(Canvas(out), null, useOverrides = override != null, target = CompositeTarget(out, Matrix(), directWrite = directWrite))
        return pixels(out)
    }

    /** The same through the frozen v1.5 copy: the layers below by the compositor, then the reference stage. */
    private fun v15(doc: Document, override: LayerRenderOverride? = null): IntArray {
        val adj = doc.layers.last()
        val idx = doc.layers.lastIndex
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        val c = Canvas(out)
        val target = CompositeTarget(out, Matrix())
        Compositor(doc) { null }.drawDocument(c, null, useOverrides = false, target = target, layerRange = 0 until idx)
        AdjustmentStageV15Reference.draw(c, adj, RectF(doc.bounds), override, target, doc.colorMode)
        return pixels(out)
    }

    /** What lies below the adjustment layer (the composite of the layers under it). */
    private fun below(doc: Document): IntArray {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        Compositor(doc) { null }.drawDocument(Canvas(out), null, useOverrides = false, target = CompositeTarget.identity(out), layerRange = 0 until doc.layers.lastIndex)
        return pixels(out)
    }

    /** Float reference: lerp(below, F(below), m·opacity) at the composite's alpha. */
    private fun floatReference(doc: Document, mapper: PixelMapper, maskFactor: (Int) -> Float, opacity: Float): IntArray {
        val b = below(doc)
        val f = b.copyOf()
        mapper.map(f, 0, f.size)
        for (i in f.indices) {
            if (doc.colorMode != ColorMode.RGB && f[i] ushr 24 != 0) f[i] = (f[i] and 0xFF000000.toInt()) or (ColorModeOps.constrainPixel(f[i] or 0xFF000000.toInt(), doc.colorMode) and 0xFFFFFF)
            if (f[i] ushr 24 != b[i] ushr 24) f[i] = AdjustmentStage.revealed(b[i], f[i])
        }
        return IntArray(b.size) { i ->
            val k = maskFactor(i) * opacity
            var out = b[i] and 0xFF000000.toInt()
            for (sh in 0..16 step 8) {
                val bc = b[i] shr sh and 0xFF
                val fc = f[i] shr sh and 0xFF
                out = out or ((bc + (fc - bc) * k).roundToInt().coerceIn(0, 255) shl sh)
            }
            out
        }
    }

    private fun maskFactors(layer: Layer): (Int) -> Float {
        val m = layer.mask ?: return { 1f }
        val px = pixels(m)
        return { i -> (px[i] shr 16 and 0xFF) / 255f }
    }

    /** Largest channel difference over [a] and [b]; with [alphaAware], low-alpha colours get the premultiplication allowance. */
    private fun worst(a: IntArray, b: IntArray, alphaAware: Boolean = false): Int {
        var worst = 0
        for (i in a.indices) {
            val alpha = minOf(a[i] ushr 24, b[i] ushr 24)
            for (sh in 0..24 step 8) {
                val d = abs((a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF))
                val excess = if (alphaAware && sh < 24) {
                    if (alpha < 64) 0 else maxOf(0, d - (3 + 255 / alpha))
                } else d
                worst = maxOf(worst, excess)
            }
        }
        return worst
    }

    /** Prints how far apart the three renderings are (and how many channels differ by more than one level). */
    private fun stats(label: String, fused: IntArray, old: IntArray, ref: IntArray, aware: Boolean = false) {
        fun over1(a: IntArray, b: IntArray): Int { var n = 0; for (i in a.indices) for (sh in 0..24 step 8) if (abs((a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF)) > 1) n++; return n }
        println("[fused] $label: fused-v15 ${worst(fused, old, aware)} (${over1(fused, old)} ch >1), fused-float ${worst(fused, ref, aware)} (${over1(fused, ref)} ch >1), v15-float ${worst(old, ref, aware)} (${over1(old, ref)} ch >1) of ${fused.size * 4}")
    }

    @Test
    fun everyLiveFilterAtThreeOpacitiesMatchesTheV15PathAndTheFloatReference() {
        var checked = 0
        for (f in AdjustmentEffects.filters) {
            val values = movedValues(f)
            val spec = AdjustmentEffects.spec(f, values)
            val mapper = AdjustmentEffects.mapperOf(spec) ?: continue
            for (o in listOf(0.2f, 0.5f, 1f)) {
                val doc = Document("f", "f", w, h)
                doc.layers += photo(doc)
                val adj = adjustment(doc, spec, ramp(doc), o)
                doc.layers += adj
                val fused = produced(doc, directWrite = true)
                val old = v15(doc)
                assertArrayEquals("${f.id}: directWrite = false is exactly the v1.5 path", old, produced(doc, directWrite = false))
                stats("${f.id} at $o", fused, old, floatReference(doc, mapper, maskFactors(adj), (o * 255f + 0.5f).toInt() / 255f))
                val dv = worst(fused, old)
                assertTrue("${f.id} at $o: fused vs v1.5 differ by $dv", dv <= 1)
                val df = worst(fused, floatReference(doc, mapper, maskFactors(adj), (o * 255f + 0.5f).toInt() / 255f))
                assertTrue("${f.id} at $o: fused vs float differ by $df", df <= 1)
                checked++
            }
        }
        assertTrue("several filters checked ($checked)", checked >= 3 * 6)
    }

    @Test
    fun paintedMaskAndNoMask() {
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 0.8f).set("contrast", 30f))
        for (painted in listOf(true, false)) {
            val doc = Document("p", "p", w, h)
            doc.layers += photo(doc)
            val adj = adjustment(doc, spec, null, 0.7f)
            if (painted) {
                adj.mask = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt()).also {
                    Canvas(it).drawPaint(Paint().apply { shader = RadialGradient(w * 0.5f, h * 0.5f, w * 0.4f, -1, 0xFF000000.toInt(), Shader.TileMode.CLAMP) })
                }
            }
            doc.layers += adj
            val fused = produced(doc, directWrite = true)
            stats("painted=$painted", fused, v15(doc), floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, maskFactors(adj), (0.7f * 255f + 0.5f).toInt() / 255f))
            assertTrue("painted=$painted vs v1.5", worst(fused, v15(doc)) <= 1)
            assertTrue("painted=$painted vs float", worst(fused, floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, maskFactors(adj), (0.7f * 255f + 0.5f).toInt() / 255f)) <= 1)
        }
    }

    @Test
    fun semiTransparentCompositesKeepTheirAlpha() {
        val invert = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))
        for (o in listOf(0.2f, 0.5f, 1f)) {
            val doc = Document("t", "t", w, h)
            doc.layers += photo(doc, alphaEnd = 0x30, hole = true)
            val adj = adjustment(doc, invert, ramp(doc), o)
            doc.layers += adj
            val fused = produced(doc, directWrite = true)
            val b = below(doc)
            for (i in b.indices) assertTrue("alpha kept at $i", abs((b[i] ushr 24) - (fused[i] ushr 24)) <= 1)
            stats("semi at $o", fused, v15(doc), floatReference(doc, AdjustmentEffects.mapperOf(invert)!!, maskFactors(adj), (o * 255f + 0.5f).toInt() / 255f), aware = true)
            val dv = worst(fused, v15(doc), alphaAware = true)
            // v1.5 itself is one level beyond the allowance from the float reference here.
            assertTrue("vs v1.5 beyond the premultiplication allowance by $dv", dv <= 1)
            val df = worst(fused, floatReference(doc, AdjustmentEffects.mapperOf(invert)!!, maskFactors(adj), (o * 255f + 0.5f).toInt() / 255f), alphaAware = true)
            assertTrue("vs float beyond the premultiplication allowance by $df", df == 0)
        }
    }

    @Test
    fun gradationMapTransparentStopsShowTheImageBelow() {
        val gm = FilterRegistry.byId("adjust.gradation_map")!!
        val stops = listOf(GradientStop(0f, 0x00000000), GradientStop(0.5f, 0x80FF2060.toInt()), GradientStop(1f, 0xFF20FF60.toInt()))
        val spec = AdjustmentEffects.spec(gm, gm.defaultValues().set("gradient", stops))
        for (alphaEnd in listOf(0xFF, 0x60)) for (o in listOf(0.5f, 1f)) {
            val doc = Document("g", "g", w, h)
            doc.layers += photo(doc, alphaEnd = alphaEnd)
            val adj = adjustment(doc, spec, ramp(doc), o)
            doc.layers += adj
            val fused = produced(doc, directWrite = true)
            val aware = alphaEnd != 0xFF
            stats("gm alpha $alphaEnd at $o", fused, v15(doc), floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, maskFactors(adj), (o * 255f + 0.5f).toInt() / 255f), aware)
            // The v1.5 one-pass draw of a lowered alpha rounds twice: up to two levels from it.
            assertTrue("alpha $alphaEnd at $o vs v1.5", worst(fused, v15(doc), alphaAware = aware) <= if (aware) 1 else 2)
            assertTrue("alpha $alphaEnd at $o vs float", worst(fused, floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, maskFactors(adj), (o * 255f + 0.5f).toInt() / 255f), alphaAware = aware) <= if (aware) 0 else 1)
        }
    }

    @Test
    fun grayscaleAndOneBitDocumentsHoldTheEffectToTheirColors() {
        val tint = FilterRegistry.byId("adjust.invert")!!
        val spec = AdjustmentEffects.defaultSpec(tint)
        for (mode in listOf(ColorMode.GRAYSCALE, ColorMode.MONOCHROME)) {
            val doc = Document("m", "m", w, h)
            doc.colorMode = mode
            doc.layers += photo(doc)
            val adj = adjustment(doc, spec, ramp(doc), 0.6f)
            doc.layers += adj
            val fused = produced(doc, directWrite = true)
            stats("$mode", fused, v15(doc), floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, maskFactors(adj), (0.6f * 255f + 0.5f).toInt() / 255f))
            assertTrue("$mode vs v1.5", worst(fused, v15(doc)) <= 1)
            assertTrue("$mode vs float", worst(fused, floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, maskFactors(adj), (0.6f * 255f + 0.5f).toInt() / 255f)) <= 1)
        }
    }

    @Test
    fun aScaledTargetLikeAThumbnailMatchesTheV15Path() {
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", -0.7f))
        val doc = Document("s", "s", w, h)
        doc.layers += photo(doc, alphaEnd = 0xA0)
        doc.layers += adjustment(doc, spec, ramp(doc), 0.8f)
        for (scale in listOf(0.5f, 0.3f)) {
            val tw = (w * scale).roundToInt(); val th = (h * scale).roundToInt()
            val sx = tw.toFloat() / w; val sy = th.toFloat() / h
            fun render(direct: Boolean): IntArray {
                val out = BitmapUtils.createLayerBitmap(tw, th)
                val c = Canvas(out)
                c.scale(sx, sy)
                Compositor(doc) { null }.drawDocument(c, null, useOverrides = false, target = CompositeTarget(out, Matrix().apply { setScale(sx, sy) }, directWrite = direct))
                return pixels(out)
            }
            val ref = BitmapUtils.createLayerBitmap(tw, th).let { out ->
                val c = Canvas(out)
                c.scale(sx, sy)
                val target = CompositeTarget(out, Matrix().apply { setScale(sx, sy) })
                Compositor(doc) { null }.drawDocument(c, null, useOverrides = false, target = target, layerRange = 0 until 1)
                AdjustmentStageV15Reference.draw(c, doc.layers[1], RectF(doc.bounds), null, target)
                pixels(out)
            }
            assertArrayEquals("scale $scale: directWrite = false is the v1.5 path", ref, render(false))
            // Float reference at the target's scale: the composite below as drawn there, the mask
            // as the v1.5 paint samples it there.
            val belowPx = BitmapUtils.createLayerBitmap(tw, th).let { out ->
                val c = Canvas(out)
                c.scale(sx, sy)
                Compositor(doc) { null }.drawDocument(c, null, useOverrides = false, target = CompositeTarget(out, Matrix().apply { setScale(sx, sy) }), layerRange = 0 until 1)
                pixels(out)
            }
            val maskPx = BitmapUtils.createLayerBitmap(tw, th).let { out ->
                val c = Canvas(out)
                c.drawColor(-1)
                c.scale(sx, sy)
                c.drawBitmap(doc.layers[1].mask!!, 0f, 0f, BitmapUtils.newMaskApplyPaint())
                pixels(out)
            }
            val mapper = AdjustmentEffects.mapperOf(spec)!!
            val fpx = belowPx.copyOf().also { mapper.map(it, 0, it.size) }
            val k0 = (0.8f * 255f + 0.5f).toInt() / 255f
            val floatRef = IntArray(belowPx.size) { i ->
                val k = (maskPx[i] ushr 24) / 255f * k0
                var o = belowPx[i] and 0xFF000000.toInt()
                for (sh in 0..16 step 8) {
                    val bc = belowPx[i] shr sh and 0xFF
                    o = o or ((bc + ((fpx[i] shr sh and 0xFF) - bc) * k).roundToInt().coerceIn(0, 255) shl sh)
                }
                o
            }
            val fused = render(true)
            stats("scaled $scale", fused, ref, floatRef, aware = true)
            assertTrue("scale $scale: fused vs float", worst(fused, floatRef, alphaAware = true) == 0)
            // The v1.5 path is itself up to two levels beyond the allowance from the float reference.
            assertTrue("scale $scale: fused vs v1.5", worst(fused, ref, alphaAware = true) <= 2)
        }
        // The thumbnail API takes the fused path too (no crash, sensible size).
        val thumb = Compositor(doc) { null }.renderThumbnail(100)
        assertTrue(thumb.width <= 100 && thumb.height <= 100)
    }

    @Test
    fun theMasksToolsHalfResolutionPreviewOverride() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("o", "o", w, h)
        doc.layers += photo(doc)
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1.2f))
        val base = ramp(doc)
        val adj = adjustment(doc, spec, base, 0.9f)
        doc.layers += adj
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        try {
            val preview = MaskPreview(c)
            preview.begin(adj, base, null)
            val moved = base.copy(components = listOf(base.components[0], (base.components[1] as RadialMask).copy(cx = w * 0.55f)))
            preview.update(moved)
            val ov = c.renderOverride
            assertNotNull("the preview installs its override", ov)
            val fused = produced(doc, directWrite = true, override = ov)
            val old = v15(doc, ov)
            val ovMask = BitmapUtils.createLayerBitmap(w, h).also { b -> val cc = Canvas(b); cc.drawColor(-1); ov!!.drawMask(cc, BitmapUtils.newMaskApplyPaint()) }
            val ovPx = pixels(ovMask)
            stats("override", fused, old, floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, { i -> (ovPx[i] ushr 24) / 255f }, (0.9f * 255f + 0.5f).toInt() / 255f))
            val d = worst(fused, old)
            // The v1.5 path is itself two levels from the float reference here (its DST_IN of the
            // filtered preview rounds in premultiplied space); the fused path stays within one.
            assertTrue("preview override: fused vs v1.5 differ by $d", d <= 2)
            assertTrue(worst(fused, floatReference(doc, AdjustmentEffects.mapperOf(spec)!!, { i -> (ovPx[i] ushr 24) / 255f }, (0.9f * 255f + 0.5f).toInt() / 255f)) <= 1)
            // And the preview is what shows (it differs from the committed mask's rendering).
            assertTrue(!fused.contentEquals(produced(doc, directWrite = true)))
            preview.release()
        } finally {
            c.dispose()
        }
    }

    @Test
    fun fusedToneOnAMasked512TileIsFast() {
        val n = 512
        val doc = Document("perf", "perf", n, n)
        doc.layers += photo(doc)
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 0.6f))
        doc.layers += adjustment(doc, spec, ramp(doc), 0.85f)
        val comp = Compositor(doc) { null }
        val below = BitmapUtils.createLayerBitmap(n, n)
        comp.drawDocument(Canvas(below), null, useOverrides = false, target = CompositeTarget.identity(below), layerRange = 0 until 1)
        val tile = BitmapUtils.createLayerBitmap(n, n)
        val canvas = Canvas(tile)
        val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        fun stageMs(direct: Boolean): Double {
            val target = CompositeTarget(tile, Matrix(), directWrite = direct)
            var best = Double.MAX_VALUE
            repeat(12) {
                canvas.drawBitmap(below, 0f, 0f, src)
                val t0 = System.nanoTime()
                comp.drawDocument(canvas, Rect(0, 0, n, n), useOverrides = false, target = target, layerRange = 1 until 2)
                best = minOf(best, (System.nanoTime() - t0) / 1e6)
            }
            return best
        }
        val v15Ms = stageMs(false)
        val fusedMs = stageMs(true)
        println("[perf] masked Tone on a 512² tile: fused ${"%.2f".format(fusedMs)} ms, v1.5 Skia path ${"%.2f".format(v15Ms)} ms")
        // §3.1(c): "fused Tone on a masked 512² tile takes <= 3 ms x 4" (with the CI allowance on top).
        assertTrue("fused $fusedMs ms", fusedMs <= PerfBudget.ms(3.0 * 4))
        assertTrue("fused $fusedMs ms is no slower than the v1.5 path ($v15Ms ms)", fusedMs <= v15Ms * 1.2 + 0.5)
    }
}
