package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class ScenePassesTest {
    private val c = SceneClasses.COUNT
    private val size = ScenePasses.SIZE

    // ------------------------------------------------------------------ softmax / labels

    @Test
    fun logSoftmaxIsNormalizedAndRejectsNonFinite() {
        val logits = FloatArray(c) { (it % 5) * 1.3f - 2f }
        logits[SceneClasses.SKY] = 9f
        val out = FloatArray(c)
        assertTrue(SceneProbabilities.logSoftmax(logits, 0, out, 0))
        assertEquals(1.0, out.sumOf { exp(it.toDouble()) }, 1e-5)
        assertEquals(SceneClasses.SKY, out.indices.maxByOrNull { out[it] })
        val p = FloatArray(c)
        SceneProbabilities.softmax(out, 0, p, 0)
        assertEquals(1f, p.sum(), 1e-5f)
        logits[3] = Float.NaN
        assertFalse(SceneProbabilities.logSoftmax(logits, 0, out, 0))
        // Scale-invariant to adding a constant (dequantization offsets).
        val shifted = FloatArray(c) { (it % 5) * 1.3f - 2f + 50f }
        val a = FloatArray(c); val b = FloatArray(c)
        SceneProbabilities.logSoftmax(FloatArray(c) { (it % 5) * 1.3f - 2f }, 0, a, 0)
        SceneProbabilities.logSoftmax(shifted, 0, b, 0)
        for (k in 0 until c) assertEquals(a[k], b[k], 1e-4f)
    }

    @Test
    fun logitsGridWeightsFollowTheValidArea() {
        val g = 64
        val values = FloatArray(g * g * c)
        val valid = floatArrayOf(0f, 64f, 512f, 448f) // a 4:3 letterbox
        val grid = SceneProbabilities.fromScores(SceneScores.Logits(g, g, values), size, valid)!!
        assertEquals(8f, grid.stride, 0f)
        assertEquals(0f, grid.weight[7 * g + 10], 0f) // cell rows 0..7 are padding
        assertEquals(1f, grid.weight[8 * g + 10], 0f)
        assertEquals(1f, grid.weight[55 * g + 10], 0f)
        assertEquals(0f, grid.weight[56 * g + 10], 0f)
        // A cell straddling the content edge is kept but trusted less.
        val odd = SceneProbabilities.fromScores(SceneScores.Logits(g, g, values), size, floatArrayOf(0f, 60f, 512f, 452f))!!
        assertTrue(odd.weight[7 * g] in 0.1f..0.6f)
        // Malformed outputs are rejected.
        assertNull(SceneProbabilities.fromScores(SceneScores.Logits(g, g, FloatArray(5)), size, valid))
        assertNull(SceneProbabilities.fromScores(SceneScores.Labels(size, ByteArray(10)), size, valid))
    }

    @Test
    fun labelsBecomeSmoothedBlockFractionsOfTheValidPixelsOnly() {
        val ids = ByteArray(size * size) { i ->
            val x = i % size; val y = i / size
            when {
                y < 64 || y >= 448 -> SceneClasses.SKY // padding labelled sky: must not count
                x < 2 -> SceneClasses.SKY
                else -> SceneClasses.TREE
            }.toByte()
        }
        val grid = SceneProbabilities.fromScores(SceneScores.Labels(size, ids), size, floatArrayOf(0f, 64f, 512f, 448f))!!
        val g = size / SceneProbabilities.LABEL_BLOCK
        assertEquals(128, grid.gw)
        fun p(cell: Int, k: Int) = exp(grid.logp[cell * c + k])
        val s = SceneProbabilities.LABEL_SMOOTHING
        // Block (0, 20): half sky, half tree.
        assertEquals((1 - s) * 0.5f + s / c, p(20 * g, SceneClasses.SKY), 1e-5f)
        assertEquals((1 - s) * 0.5f + s / c, p(20 * g, SceneClasses.TREE), 1e-5f)
        // Block (5, 20): tree only.
        assertEquals((1 - s) + s / c, p(20 * g + 5, SceneClasses.TREE), 1e-5f)
        // Padding blocks carry no weight.
        assertEquals(0f, grid.weight[10 * g + 5], 0f)
        assertEquals(1f, grid.weight[20 * g + 5], 0f)
        var total = 0f
        for (k in 0 until c) total += p(20 * g + 5, k)
        assertEquals(1f, total, 1e-4f)
    }

    // ------------------------------------------------------------------ geometry

    @Test
    fun globalGeometryRoundTripsAndMirrors() {
        for ((w, h) in listOf(1280 to 960, 300 to 1000, 1280 to 1, 7 to 5)) {
            for (flip in listOf(false, true)) {
                val g = ScenePasses.global(w, h, flip)
                for (fx in listOf(0f, 0.25f, 0.5f, 0.99f)) for (fy in listOf(0f, 0.5f, 0.99f)) {
                    val x = fx * w; val y = fy * h
                    assertEquals("$w x $h $flip", x, g.toWorkX(g.toInputX(x)), 1e-2f * maxOf(1f, x))
                    assertEquals(y, g.toWorkY(g.toInputY(y)), 1e-2f * maxOf(1f, y))
                }
                // The picture maps onto the valid (content) rectangle.
                val x0 = g.toInputX(0f); val x1 = g.toInputX(w.toFloat())
                assertEquals(g.valid[0], minOf(x0, x1), 0.6f)
                assertEquals(g.valid[2], maxOf(x0, x1), 0.6f)
                if (flip) assertTrue(x0 > x1)
            }
        }
    }

    @Test
    fun cropsCoverTheImageWithOverlap() {
        val w = 1280; val h = 720 // 16:9
        val crops = ScenePasses.crops(w, h)
        assertEquals(2, crops.size)
        assertEquals(0f, crops.first().region[0], 1e-3f)
        assertEquals(w.toFloat(), crops.last().region[2], 0.5f)
        assertTrue("overlap", crops[0].region[2] > crops[1].region[0])
        assertTrue(crops[0].cutEdges[2] && !crops[0].cutEdges[0])
        assertTrue(crops[1].cutEdges[0] && !crops[1].cutEdges[2])
        for (g in crops) {
            assertEquals(0f, g.toInputY(0f), 1e-3f)
            assertEquals(size.toFloat(), g.toInputY(h.toFloat()), 0.5f)
            assertEquals(0f, g.toInputX(g.region[0]), 1e-2f)
            assertEquals(size.toFloat(), g.toInputX(g.region[2]), 0.5f)
            // Trust tapers near a cut edge, not in the middle.
            val mid = (g.region[0] + g.region[2]) / 2
            assertEquals(1f, g.edgeWeight(mid, h / 2f, 64f), 0f)
        }
        assertTrue(crops[0].edgeWeight(crops[0].region[2] - 1f, 300f, 64f) < 0.05f)
        // Very long panoramas get more crops; square and tiny images none.
        val pano = ScenePasses.crops(2400, 800) // 1536 px wide when filled: 4 crops, <= 448 apart
        assertEquals(4, pano.size)
        for (k in 1 until pano.size) assertTrue((pano[k].region[0] - pano[k - 1].region[0]) * pano[k].sx <= ScenePasses.CROP_STRIDE + 1)
        assertTrue(ScenePasses.crops(1000, 1000).isEmpty())
        assertTrue(ScenePasses.crops(300, 200).isEmpty())
        assertTrue(ScenePasses.crops(4000, 2).isEmpty())
    }

    @Test
    fun renderedInputsPutPixelsWhereTheGeometrySays() {
        val w = 640; val h = 360
        val work = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) work[x, y] = if (x < w / 4) SegTestImages.RED else SegTestImages.SKY_BLUE
        for (flip in listOf(false, true)) {
            val g = ScenePasses.global(w, h, flip)
            val input = ScenePasses.renderGlobal(work, g)
            assertEquals(ScenePasses.PAD, input[256, 5]) // padding above the content
            val ix = g.toInputX(40f).toInt(); val iy = g.toInputY(180f).toInt()
            assertEquals("flip=$flip", SegTestImages.RED, input[ix, iy])
            val jx = g.toInputX(600f).toInt()
            assertEquals(SegTestImages.SKY_BLUE, input[jx, iy])
        }
        val crops = ScenePasses.crops(w, h)
        val fill = ScenePasses.fillSize(w, h)
        val filled = MaskOps.resampleSmooth(work, fill[0], fill[1])
        for (g in crops) {
            val input = ScenePasses.renderCrop(filled, g)
            for (x in listOf(10f, 150f, 300f, 630f)) {
                if (!g.contains(x, 100f)) continue
                val expected = if (x < w / 4) SegTestImages.RED else SegTestImages.SKY_BLUE
                assertEquals(expected, input[g.toInputX(x).toInt(), g.toInputY(100f).toInt()])
            }
        }
    }

    // ------------------------------------------------------------------ fusion

    /** A pass grid whose every cell says [probs] (per-class probabilities, other classes tiny). */
    private fun uniformGrid(g: Int, probs: Map<Int, Float>): PassGrid {
        val logp = FloatArray(g * g * c)
        val rest = (1f - probs.values.sum()) / (c - probs.size)
        for (cell in 0 until g * g) for (k in 0 until c) logp[cell * c + k] = ln(probs[k] ?: rest)
        return PassGrid(g, g, size.toFloat() / g, logp, FloatArray(g * g) { 1f })
    }

    @Test
    fun fusionKeepsConfidentGlobalAnswersAndAveragesUnsureOnes() {
        val w = 1280; val h = 720
        val (fw, fh) = SceneFusion.gridSize(w, h).let { it[0] to it[1] }
        assertEquals(128, fh)
        val crops = ScenePasses.crops(w, h)
        // Confident global sky; the crops insist on water: sky stays.
        val f1 = SceneFusion(w, h, fw, fh)
        f1.add(uniformGrid(64, mapOf(SceneClasses.SKY to 0.97f)), ScenePasses.global(w, h))
        for (g in crops) f1.add(uniformGrid(64, mapOf(SceneClasses.WATER to 0.97f)), g)
        val r1 = f1.result()
        val mid = (fh / 2 * fw + fw / 2) * c
        assertTrue(r1[mid + SceneClasses.SKY] > 0.9f)
        // Unsure global (sky 0.5, water 0.4); confident crops (water): water wins.
        val f2 = SceneFusion(w, h, fw, fh)
        f2.add(uniformGrid(64, mapOf(SceneClasses.SKY to 0.5f, SceneClasses.WATER to 0.4f)), ScenePasses.global(w, h))
        for (g in crops) f2.add(uniformGrid(64, mapOf(SceneClasses.WATER to 0.9f)), g)
        val r2 = f2.result()
        assertTrue(r2[mid + SceneClasses.WATER] > r2[mid + SceneClasses.SKY])
        var sum = 0f
        for (k in 0 until c) sum += r2[mid + k]
        assertEquals(1f, sum, 1e-4f)
        // The tile exponent: 0.5 when unsure, 0 when confident.
        assertEquals(0.5f, SceneFusion.tileWeight(0.4f, 1f), 1e-6f)
        assertEquals(0f, SceneFusion.tileWeight(0.95f, 1f), 1e-6f)
        assertEquals(0.25f, SceneFusion.tileWeight(0.4f, 0.5f), 1e-6f)
    }

    @Test
    fun fusedLabelsFromEveryPassAgreeOnTheBoundary() {
        // A perfect argmax model on a picture with a vertical sky | tree boundary at x = 700:
        // letterbox, crops and the mirrored pass must all map back to the same place.
        val w = 1280; val h = 720; val edge = 700
        val work = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) work[x, y] = if (x < edge) SegTestImages.SKY_BLUE else SegTestImages.FOLIAGE
        val parser = SceneParser { input ->
            SceneScores.Labels(size, ByteArray(size * size) { i ->
                val px = input.pixels[i]
                (if (px == SegTestImages.SKY_BLUE) SceneClasses.SKY else if (px == ScenePasses.PAD) SceneClasses.WATER else SceneClasses.TREE).toByte()
            })
        }
        val (fw, fh) = SceneFusion.gridSize(w, h).let { it[0] to it[1] }
        val passes = listOf(ScenePasses.global(w, h), ScenePasses.global(w, h, flip = true)) + ScenePasses.crops(w, h)
        val fill = ScenePasses.fillSize(w, h)
        val filled = MaskOps.resampleSmooth(work, fill[0], fill[1])
        for (only in passes.indices) {
            val f = SceneFusion(w, h, fw, fh)
            val g = passes[only]
            val input = if (g.kind == PassGeometry.Kind.CROP) ScenePasses.renderCrop(filled, g) else ScenePasses.renderGlobal(work, g)
            f.add(SceneProbabilities.fromScores(parser.run(input)!!, size, g.valid)!!, g)
            val r = f.result()
            val row = fh / 2
            var crossing = -1f
            for (fx in 1 until fw) {
                val a = r[(row * fw + fx - 1) * c + SceneClasses.SKY]; val b = r[(row * fw + fx) * c + SceneClasses.SKY]
                if (a >= 0.5f && b < 0.5f && g.contains((fx + 0.5f) * w / fw, h / 2f)) crossing = fx * w.toFloat() / fw
            }
            if (!g.contains(edge.toFloat(), h / 2f)) continue
            assertTrue("pass $g crossing at $crossing", abs(crossing - edge) < 2f * w / fw + 4f)
            // Padding (labelled water by the fake) never shows up.
            for (k in 0 until fw * fh) assertTrue(r[k * c + SceneClasses.WATER] < 0.1f)
        }
        assertNotNull(parser)
    }
}
