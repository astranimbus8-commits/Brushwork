package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.geom.StrokeHits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * v1.5 A1 (StrokeRaster parity, §4.9e): replays of transformed and cut strokes. A stroke whose
 * ends were cut by the partial eraser (`taperIn` / `taperOut` false) replays exactly like the
 * live stroke drawn without those tapers; a scaled stroke (`sizeScale`) like the live stroke of
 * the scaled brush; and the dab chain used for exact hit tests ([StrokeRaster.dabCircles]) is the
 * replay's: every painted pixel lies on it.
 */
@RunWith(RobolectricTestRunner::class)
class StrokeRasterA1RobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 420
    private val h = 300

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private class Capture : StrokeRecorder {
        override val replacesStroke = false
        override val ignoresSelection = false
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ps = ArrayList<Float>()
        override fun point(x: Float, y: Float, rawPressure: Float) { xs += x; ys += y; ps += rawPressure }
        override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean = commitPixels()
        override fun cancel() {}
        fun points() = PackedPoints(xs.toFloatArray(), ys.toFloatArray(), ps.toFloatArray())
    }

    private fun pts(stylus: Boolean): List<ToolPoint> = List(31) { i ->
        val t = i / 30f
        ToolPoint(60f + 300f * t, 150f + 60f * sin(t * 2f * PI.toFloat()), if (stylus) 0.3f + 0.7f * sin(t * PI.toFloat()) else 0.5f, i.toLong(), isStylus = stylus)
    }

    /** Draws a live stroke with [preset]; returns its pixels and what a replay needs. */
    private fun live(preset: BrushPreset, stylus: Boolean = false): Triple<IntArray, StrokeInfo, PackedPoints> {
        val c = setup()
        c.selectTool(ToolId.BRUSH)
        val b = c.tools.getValue(ToolId.BRUSH) as BrushTool
        c.brush = preset
        val cap = Capture()
        var info: StrokeInfo? = null
        b.strokeHook = { i -> info = i; StrokeHook.Record(cap) }
        val p = pts(stylus)
        b.onDown(p.first())
        for (q in p.subList(1, p.size - 1)) b.onMove(q)
        b.onUp(p.last())
        return Triple(px(c.activeLayer.bitmap), info!!, cap.points())
    }

    private fun replay(info: StrokeInfo, points: PackedPoints, preset: BrushPreset = info.preset, sizeScale: Float = 1f, taperIn: Boolean = true, taperOut: Boolean = true): IntArray {
        val out = BitmapUtils.createLayerBitmap(w, h)
        val doc = Rect(0, 0, w, h)
        StrokeRaster().render(Canvas(out), doc, preset, info.color, info.seed, info.isStylus, points, sizeScale, 1f, taperIn, taperOut, cut = doc)
        return px(out)
    }

    private fun maxDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF)))
        return m
    }

    @Test
    fun cutEndsReplayLikeTheLiveStrokeWithoutThoseTapers() {
        for ((id, base) in listOf(
            "gpen" to BrushLibrary.byId("gpen")!!,
            "dippen" to BrushLibrary.byId("dippen")!!,
            "tapered soft" to BrushLibrary.byId("softround")!!.copy(taperStart = 40f, taperEnd = 60f),
            "tapered pencil" to BrushLibrary.byId("pencil")!!.copy(taperStart = 25f, taperEnd = 30f),
        )) {
            assertTrue("$id has a finger taper", base.taperStart > 0f && base.taperEnd > 0f)
            // The live stroke without the start taper is the replay of the tapered stroke with taperIn = false.
            val (noStart, info, points) = live(base.copy(taperStart = 0f))
            val cut = replay(info, points, preset = base, taperIn = false)
            assertTrue("$id start: max diff ${maxDiff(noStart, cut)}", maxDiff(noStart, cut) <= 2)
            val (noEnd, info2, points2) = live(base.copy(taperEnd = 0f))
            val cutEnd = replay(info2, points2, preset = base, taperOut = false)
            assertTrue("$id end: max diff ${maxDiff(noEnd, cutEnd)}", maxDiff(noEnd, cutEnd) <= 2)
        }
    }

    @Test
    fun aScaledStrokeReplaysLikeTheScaledBrush() {
        for ((id, stylus) in listOf("gpen" to false, "softround" to true, "chalk" to false)) {
            val base = BrushLibrary.byId(id)!!
            val s = 1.6f
            val scaled = base.copy(size = base.size * s, taperStart = base.taperStart * s, taperEnd = base.taperEnd * s)
            val (livePx, info, points) = live(scaled, stylus)
            val r = replay(info, points, preset = base, sizeScale = s)
            var off = 0
            for (i in r.indices) if (maxDiff(intArrayOf(r[i]), intArrayOf(livePx[i])) > 2) off++
            val painted = livePx.count { it != 0 }
            assertTrue("$id: $off of $painted pixels off", off <= painted / 200)
        }
    }

    @Test
    fun theDabChainHoldsEveryPaintedPixel() {
        for ((id, stylus) in listOf("gpen" to false, "softround" to true, "airbrush" to true, "pencil" to false)) {
            val (livePx, info, points) = live(BrushLibrary.byId(id)!!, stylus)
            val d = StrokeRaster.dabCircles(info.preset, 1f, info.isStylus, info.seed, points)
            assertTrue(d.size >= 30)
            for (y in 0 until h) for (x in 0 until w) {
                if (livePx[y * w + x] ushr 24 == 0) continue
                val dist = StrokeHits.distance(d, x + 0.5f, y + 0.5f)
                assertTrue("$id ($x, $y) is $dist px off the dab chain", dist <= 2f)
            }
        }
        // No points, no dabs.
        assertEquals(0, StrokeRaster.dabCircles(BrushLibrary.defaultBrush, 1f, false, 1L, PackedPoints.EMPTY).size)
        assertArrayEquals(StrokeRaster.dabCircles(BrushLibrary.defaultBrush, 1f, false, 1L, PackedPoints(floatArrayOf(5f), floatArrayOf(6f), floatArrayOf(1f))).copyOf(2), floatArrayOf(5f, 6f), 1e-5f)
    }
}
