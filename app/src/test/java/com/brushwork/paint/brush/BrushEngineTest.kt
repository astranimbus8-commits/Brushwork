package com.brushwork.paint.brush

import com.brushwork.paint.tools.ToolId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Pure-JVM tests of the brush engine math (no Android classes involved). */
class BrushEngineTest {

    private data class Sample(val x: Float, val y: Float, val p: Float, val d: Float)

    private fun sample(spacing: Float, build: StrokeSampler.() -> Unit): List<Sample> {
        val out = ArrayList<Sample>()
        StrokeSampler({ _, _ -> spacing }, { x, y, p, d -> out += Sample(x, y, p, d) }).build()
        return out
    }

    @Test
    fun samplerSpacesDabsEvenlyAlongAStraightLine() {
        val s = sample(2f) {
            begin(0f, 0f, 1f)
            for (i in 1..10) add(i * 10f, 0f, 1f)
            end()
        }
        assertEquals(0f, s.first().x, 0f)
        assertEquals(100f, s.last().x, 1e-3f)
        assertEquals(51, s.size)
        for (i in 1 until s.size) {
            assertEquals(2f, s[i].d - s[i - 1].d, 1e-3f)
            assertEquals(0f, s[i].y, 1e-4f)
        }
    }

    @Test
    fun samplerSmoothsCornersAndInterpolatesPressure() {
        val s = sample(1f) {
            begin(0f, 0f, 0f)
            add(50f, 0f, 0.5f)
            add(50f, 50f, 1f)
            end()
        }
        // Curve cuts the corner: no sample lands exactly on (50, 0).
        assertTrue(s.none { abs(it.x - 50f) < 0.5f && abs(it.y) < 0.5f })
        // Pressure rises monotonically from 0 to 1.
        for (i in 1 until s.size) assertTrue(s[i].p >= s[i - 1].p - 1e-4f)
        assertEquals(1f, s.last().p, 1e-3f)
        // Consecutive samples are ~1 px apart (sub-pixel, no gaps).
        for (i in 1 until s.size) assertTrue(hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y) <= 1.01f)
    }

    @Test
    fun samplerTapEmitsOneDab() {
        val s = sample(3f) { begin(5f, 5f, 1f); add(5f, 5f, 1f); end() }
        assertEquals(1, s.size)
    }

    @Test
    fun dynamicsPressureAndTapers() {
        val preset = BrushPreset("t", "t", BrushTip.ROUND_HARD, size = 20f, minSizeRatio = 0.2f, pressureSize = true, taperStart = 50f, taperEnd = 50f)
        val stylus = StrokeDynamics(preset, isStylus = true, seed = 1)
        assertEquals(4f, stylus.liveDiameter(0f, 0f), 1e-4f)
        assertEquals(20f, stylus.liveDiameter(1f, 0f), 1e-4f)
        assertFalse(stylus.hasTaper) // tapers are for fingers

        val finger = StrokeDynamics(preset, isStylus = false, seed = 1)
        assertEquals(20f * StrokeDynamics.TAPER_MIN, finger.liveDiameter(1f, 0f), 1e-3f)
        assertEquals(20f, finger.liveDiameter(1f, 60f), 1e-3f)
        assertEquals(1f, finger.taperFactor(100f, 300f), 1e-4f)
        assertTrue(finger.taperFactor(290f, 300f) < 0.5f)
        // Taps are never tapered, short strokes scale their tapers.
        assertEquals(1f, finger.taperFactor(0f, 0.5f), 0f)
        assertTrue(finger.taperFactor(20f, 40f) > 0.9f)
        val dab = finger.newDab(10f, 10f, 1f, 150f)
        finger.resolve(dab, 300f)
        assertEquals(20f, dab.diameter, 1e-4f)
        assertEquals(1f, dab.alpha, 1e-4f)
    }

    @Test
    fun dynamicsTinyDiametersFadeInsteadOfShrinkingBelowOnePixel() {
        val d = StrokeDynamics(BrushPreset("t", "t", BrushTip.ROUND_HARD, size = 0.5f, pressureSize = false), true, 3)
        val dab = d.newDab(0f, 0f, 1f, 0f)
        d.resolve(dab, null)
        assertEquals(1f, dab.diameter, 0f)
        assertEquals(0.5f, dab.alpha, 1e-4f)
        assertEquals(0.5f, d.spacing(1f, 0f), 1e-4f)
    }

    @Test
    fun tipShapesHaveTheRightProfiles() {
        val hard = TipShapes.rasterize(BrushTip.ROUND_HARD, 20f, 22, 1f, 1f, 0f, 0, true)
        assertEquals(255, hard[11 * 22 + 11].toInt() and 0xFF)
        assertEquals(0, hard[0].toInt() and 0xFF)
        val soft = TipShapes.rasterize(BrushTip.ROUND_SOFT, 20f, 22, 0f, 1f, 0f, 0, true)
        val mid = soft[11 * 22 + 16].toInt() and 0xFF
        assertTrue("soft falloff $mid", mid in 30..220)
        // Aliased tips are binary.
        val px = TipShapes.rasterize(BrushTip.ROUND_HARD, 5f, 5, 1f, 1f, 0f, 0, false)
        assertTrue(px.all { (it.toInt() and 0xFF) == 0 || (it.toInt() and 0xFF) == 255 })
        val one = TipShapes.rasterize(BrushTip.SQUARE, 1f, 1, 1f, 1f, 0f, 0, false)
        assertEquals(255, one[0].toInt() and 0xFF)
        // A flat calligraphy tip is wider than tall; rotated 90 degrees it's the other way round.
        val flat = TipShapes.rasterize(BrushTip.CALLIGRAPHY, 20f, 22, 1f, 0.25f, 0f, 0, true)
        assertTrue((flat[11 * 22 + 19].toInt() and 0xFF) > 200)
        assertEquals(0, flat[19 * 22 + 11].toInt() and 0xFF)
        val turned = TipShapes.rasterize(BrushTip.CALLIGRAPHY, 20f, 22, 1f, 0.25f, 90f, 0, true)
        assertTrue((turned[19 * 22 + 11].toInt() and 0xFF) > 200)
        // Textured variants differ.
        val p0 = TipShapes.rasterize(BrushTip.PENCIL, 16f, 18, 0.6f, 1f, 0f, 0, true)
        val p1 = TipShapes.rasterize(BrushTip.PENCIL, 16f, 18, 0.6f, 1f, 0f, 1, true)
        assertFalse(p0.contentEquals(p1))
        val spray = TipShapes.rasterize(BrushTip.SPRAY, 40f, 42, 0.5f, 1f, 0f, 2, true)
        val covered = spray.count { it.toInt() != 0 }
        assertTrue("spray is dotted: $covered", covered in 40 until 42 * 42 / 2)
    }

    @Test
    fun hugeTipsRasterizeQuicklyWithTheRightShape() {
        val start = System.nanoTime()
        val n = 1000
        val square = TipShapes.rasterize(BrushTip.SQUARE, n.toFloat(), n, 1f, 1f, 0f, 0, false)
        val round = TipShapes.rasterize(BrushTip.ROUND_HARD, n.toFloat(), n, 1f, 1f, 0f, 0, false)
        val aa = TipShapes.rasterize(BrushTip.ROUND_HARD, TipCache.MAX_AA_TIP, TipCache.MAX_AA_TIP.toInt() + 2, 0.9f, 1f, 0f, 0, true)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("rasterizing took $ms ms", ms < 3000)
        assertEquals(255, square[0].toInt() and 0xFF)
        assertEquals(255, square[n * n - 1].toInt() and 0xFF)
        assertEquals(255, round[500 * n + 500].toInt() and 0xFF)
        assertEquals(0, round[0].toInt() and 0xFF)
        assertEquals(255, round[500 * n + 1].toInt() and 0xFF) // left edge of the circle
        assertTrue(round.all { (it.toInt() and 0xFF) == 0 || (it.toInt() and 0xFF) == 255 })
        val c = TipCache.MAX_AA_TIP.toInt() / 2 + 1
        assertEquals(255, aa[c * (TipCache.MAX_AA_TIP.toInt() + 2) + c].toInt() and 0xFF)
    }

    @Test
    fun tipSizeQuantization() {
        // Anti-aliased tips stop growing at the cap; bigger dabs scale the capped tip.
        assertEquals(TipCache.MAX_AA_TIP, TipCache.bucketDiameter(TipCache.bucketOf(TipCache.MAX_AA_TIP)), 1e-2f)
        assertTrue(TipCache.bucketDiameter(TipCache.bucketOf(37f)) >= 37f)
        assertTrue(TipCache.bucketDiameter(TipCache.bucketOf(37f)) < 37f * 1.19f)
        // Aliased tips are exact up to 128 px, then within 1.6 %.
        for (d in 1..128) assertEquals(d, TipCache.aliasedDiameter(d.toFloat()))
        for (d in 129..1000) {
            val q = TipCache.aliasedDiameter(d.toFloat())
            assertTrue("$d -> $q", abs(q - d) <= d * 0.016f + 0.5f)
        }
        val distinct = (129..1000).map { TipCache.aliasedDiameter(it.toFloat()) }.toSet().size
        assertTrue("distinct huge aliased tips: $distinct", distinct < 250)
    }

    @Test
    fun paperTextureTilesSeamlessly() {
        val t = TipShapes.paperTexture(64)
        assertTrue(t.all { it in 0f..1f })
        assertTrue(t.toSet().size > 30)
    }

    private fun surface(w: Int, h: Int, fill: (Int, Int) -> Int) = IntArraySurface(w, h, IntArray(w * h) { fill(it % w, it / w) })

    private fun runDabs(painter: DirectPainter, preset: BrushPreset, from: Float, to: Float, y: Float) {
        val dyn = StrokeDynamics(preset, true, 7)
        StrokeSampler({ p, d -> dyn.spacing(p, d) }, { x, yy, p, d ->
            val dab = dyn.newDab(x, yy, p, d)
            dyn.resolve(dab, null)
            painter.apply(dab)
        }).apply { begin(from, y, 1f); add(to, y, 1f); end() }
    }

    @Test
    fun smudgeCarriesColorAndRespectsAlphaLockAndSelection() {
        val red = 0xFFFF0000.toInt()
        val preset = BrushLibrary.defaultSmudge.copy(size = 20f, mixing = 0.95f, pressureSize = false)
        val s = surface(80, 40) { x, _ -> if (x < 30) red else 0 }
        runDabs(DirectPainter(StrokeKind.SMUDGE, preset, s, null, false, 0), preset, 20f, 60f, 20f)
        assertTrue("dragged into transparency", (s.pixels[20 * 80 + 45] ushr 24) > 80)
        assertEquals(0, s.pixels[2 * 80 + 70]) // far from the stroke

        val locked = surface(80, 40) { x, _ -> if (x < 30) red else 0 }
        runDabs(DirectPainter(StrokeKind.SMUDGE, preset, locked, null, true, 0), preset, 20f, 60f, 20f)
        assertEquals(0, locked.pixels[20 * 80 + 45])

        val selected = surface(80, 40) { x, _ -> if (x < 30) red else 0 }
        val onlyLeft = CoverageReader { l, t, w, h, out -> for (i in 0 until w * h) out[i] = if (l + i % w < 40) 255 else 0 }
        runDabs(DirectPainter(StrokeKind.SMUDGE, preset, selected, onlyLeft, false, 0), preset, 20f, 60f, 20f)
        assertEquals(0, selected.pixels[20 * 80 + 45])
        assertTrue((selected.pixels[20 * 80 + 35] ushr 24) > 80)
    }

    @Test
    fun smudgeColorFadesOverAFewDiameters() {
        val red = 0xFFFF0000.toInt()
        // mixing 0.75: the dragged color lags behind the tip at 1/4 of its speed, so it has
        // drained out after about 4 diameters.
        val preset = BrushLibrary.defaultSmudge.copy(size = 20f, mixing = 0.75f, pressureSize = false)
        val s = surface(300, 40) { x, _ -> if (x < 40) red else 0 }
        runDabs(DirectPainter(StrokeKind.SMUDGE, preset, s, null, false, 0), preset, 30f, 280f, 20f)
        val near = s.pixels[20 * 300 + 50] ushr 24
        val far = s.pixels[20 * 300 + 160] ushr 24 // 6 diameters past the red edge
        assertTrue("dragged just past the edge: $near", near > 100)
        assertTrue("faded after 6 diameters: $far", far < 30)
        // Alpha decreases steadily along the smear (no carried blob, no periodic stamping).
        var prev = 256
        for (x in 60..200 step 10) {
            val a = s.pixels[20 * 300 + x] ushr 24
            assertTrue("x=$x alpha $a after $prev", a <= prev + 2)
            prev = a
        }
        // Full strength carries the color much further.
        val strong = BrushLibrary.defaultSmudge.copy(size = 20f, mixing = 1f, pressureSize = false)
        val s2 = surface(300, 40) { x, _ -> if (x < 40) red else 0 }
        runDabs(DirectPainter(StrokeKind.SMUDGE, strong, s2, null, false, 0), strong, 30f, 280f, 20f)
        assertTrue((s2.pixels[20 * 300 + 160] ushr 24) > 150)
    }

    @Test
    fun smudgeHasNoPeriodicStampingAcrossStripes() {
        // 8 px stripes smeared sideways: along the stroke center the result must be smooth
        // (neighboring pixels differ little), not a comb at the dab spacing.
        val preset = BrushLibrary.defaultSmudge.copy(size = 40f, pressureSize = false)
        val s = surface(260, 60) { x, _ -> if ((x / 8) % 2 == 0) 0xFF2060E0.toInt() else 0xFFF0C020.toInt() }
        runDabs(DirectPainter(StrokeKind.SMUDGE, preset, s, null, false, 0), preset, 20f, 240f, 30f)
        var maxJump = 0
        for (x in 120 until 200) {
            val a = s.pixels[30 * 260 + x]
            val b = s.pixels[30 * 260 + x + 1]
            for (sh in intArrayOf(0, 8, 16)) maxJump = maxOf(maxJump, abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)))
        }
        assertTrue("max neighbor difference $maxJump", maxJump < 40)
    }

    @Test
    fun blurAveragesInPremultipliedSpace() {
        val preset = BrushLibrary.defaultBlur.copy(size = 24f, mixing = 1f, pressureSize = false)
        // Opaque white next to fully transparent (black RGB): blurring must not darken the white.
        val s = surface(60, 40) { x, _ -> if (x < 30) -1 else 0 }
        runDabs(DirectPainter(StrokeKind.BLUR, preset, s, null, false, 0), preset, 30f, 30.1f, 20f)
        runDabs(DirectPainter(StrokeKind.BLUR, preset, s, null, false, 0), preset, 30f, 30.1f, 20f)
        val edge = s.pixels[20 * 60 + 31]
        assertTrue("alpha spread ${edge ushr 24}", (edge ushr 24) in 10..250)
        assertEquals("color stays white", 0xFFFFFF, edge and 0xFFFFFF)
    }

    @Test
    fun watercolorPicksUpCanvasColor() {
        val preset = BrushLibrary.byId("watercolor")!!.copy(size = 20f, flow = 0.6f, pressureOpacity = false, mixing = 0.9f)
        val s = surface(100, 30) { x, _ -> if (x < 40) 0xFFFFFF00.toInt() else 0 }
        runDabs(DirectPainter(StrokeKind.WATERCOLOR, preset, s, null, false, 0xFF0000FF.toInt()), preset, 10f, 90f, 15f)
        val p = s.pixels[15 * 100 + 60]
        assertTrue((p ushr 24) > 100)
        assertTrue("yellow carried: ${Integer.toHexString(p)}", ((p shr 16) and 0xFF) > 60)
    }

    @Test
    fun boxBlurPreservesConstantsAndMass() {
        val w = 9; val h = 5
        val src = FloatArray(w * h) { 3f }
        val dst = FloatArray(w * h)
        DirectPainter.boxBlurH(src, dst, w, h, 2)
        assertTrue(dst.all { abs(it - 3f) < 1e-5f })
        val spike = FloatArray(w * h).also { it[2 * w + 4] = 10f }
        DirectPainter.boxBlurH(spike, dst, w, h, 1)
        assertEquals(10f, dst.sum(), 1e-4f)
    }

    @Test
    fun libraryHasPresetsForEveryPaintTool() {
        assertTrue(BrushLibrary.presetsFor(ToolId.BRUSH).size >= 14)
        assertEquals(3, BrushLibrary.presetsFor(ToolId.ERASER).size)
        assertTrue(BrushLibrary.presetsFor(ToolId.SMUDGE).size >= 2)
        assertTrue(BrushLibrary.presetsFor(ToolId.BLUR).size >= 2)
        assertTrue(BrushLibrary.presetsFor(ToolId.FILL).isEmpty())
        val ids = (BrushLibrary.all + BrushLibrary.erasers + BrushLibrary.smudges + BrushLibrary.blurs).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(BrushLibrary.defaultBrush, BrushLibrary.all.first())
        for (id in ToolId.entries) {
            val list = BrushLibrary.presetsFor(id)
            if (list.isNotEmpty()) assertTrue(BrushLibrary.defaultFor(id) in list)
            list.forEach { assertEquals(id, BrushLibrary.toolOf(it.id)) }
            list.forEach { assertEquals(it, it.sanitized()) }
        }
        assertEquals(StrokeKind.WATERCOLOR, StrokeKind.of(ToolId.BRUSH, BrushLibrary.byId("watercolor")!!))
        assertEquals(StrokeKind.ERASE, StrokeKind.of(ToolId.ERASER, BrushLibrary.defaultEraser))
        assertFalse(BrushLibrary.byId("pixelpen")!!.antiAlias)
    }

    @Test
    fun presetsSerializeAndSanitize() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val p = BrushLibrary.byId("calligraphy")!!.copy(size = 77f)
        val back = json.decodeFromString(BrushPreset.serializer(), json.encodeToString(BrushPreset.serializer(), p))
        assertEquals(p, back)
        val bad = p.copy(size = 5000f, opacity = -1f, spacing = 0f, roundness = Float.NaN, angle = -30f)
        val s = bad.sanitized()
        assertEquals(BrushLimits.MAX_SIZE, s.size, 0f)
        assertEquals(0f, s.opacity, 0f)
        assertEquals(BrushLimits.MIN_SPACING, s.spacing, 0f)
        assertEquals(1f, s.roundness, 0f)
        assertEquals(330f, s.angle, 1e-4f)
    }
}
