package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 foundation (§5.5, §5.10 item 8): the BrushTool seams — stroke hooks (record, replace,
 * refuse), the coverage source of the clone stamp and the undo label override.
 */
@RunWith(RobolectricTestRunner::class)
class BrushSeamsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 160
    private val h = 100

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun pixels(b: Bitmap) = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private class Point(val x: Float, val y: Float, val p: Float)

    private class FakeRecorder(
        override val replacesStroke: Boolean = false,
        override val ignoresSelection: Boolean = false,
        val onCommit: (String, Rect, () -> Boolean) -> Boolean = { _, _, pixels -> pixels() },
    ) : StrokeRecorder {
        val points = ArrayList<Point>()
        var commits = 0
        var cancels = 0
        var bounds: Rect? = null

        override fun point(x: Float, y: Float, rawPressure: Float) { points += Point(x, y, rawPressure) }

        override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
            commits++
            this.bounds = bounds
            return onCommit(label, bounds, commitPixels)
        }

        override fun cancel() { cancels++ }
    }

    private fun brush(c: EditorController): BrushTool {
        c.selectTool(ToolId.BRUSH)
        return c.tools.getValue(ToolId.BRUSH) as BrushTool
    }

    private val fed = listOf(
        ToolPoint(20f, 50f, 0.25f, 1L, isStylus = true),
        ToolPoint(40f, 52f, 0.5f, 2L, isStylus = true),
        ToolPoint(70f, 55f, 0.75f, 3L, isStylus = true),
        ToolPoint(100f, 50f, 0.9f, 4L, isStylus = true),
    )

    private fun draw(b: BrushTool, pts: List<ToolPoint> = fed) {
        b.onDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) b.onMove(p)
        b.onUp(pts.last())
    }

    @Test
    fun aRecorderGetsEveryPointWithRawPressureAndCommitsOnce() {
        val c = setup()
        val b = brush(c)
        val rec = FakeRecorder()
        val infos = ArrayList<StrokeInfo>()
        b.strokeHook = { info -> infos += info; StrokeHook.Record(rec) }
        // Fingers report pressure 0.5 raw (the stroke uses 1): the recorder sees what was fed.
        val finger = fed.map { it.copy(isStylus = false, pressure = 0.5f) }
        draw(b, finger)
        assertEquals(finger.map { Triple(it.x, it.y, it.pressure) }, rec.points.map { Triple(it.x, it.y, it.p) })
        assertEquals(1, rec.commits)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Brush", c.undoManager.undoLabel)
        val info = infos.single()
        assertEquals(ToolId.BRUSH, info.toolId)
        assertEquals(StrokeKind.PAINT, info.kind)
        assertFalse(info.isStylus)
        assertFalse(info.isPath)
        assertTrue(c.activeLayer === info.layer)
        assertNotNull(rec.bounds)
        assertTrue(rec.bounds!!.contains(60, 52))
        // Stylus pressures arrive raw too, the up point included.
        val rec2 = FakeRecorder()
        b.strokeHook = { StrokeHook.Record(rec2) }
        draw(b)
        assertEquals(fed.map { Triple(it.x, it.y, it.pressure) }, rec2.points.map { Triple(it.x, it.y, it.p) })
    }

    @Test
    fun aRecorderCommitWrappingTheBrushStepIsOneStep() {
        val c = setup()
        val b = brush(c)
        val layer = c.activeLayer
        layer.vector = VectorContent.EMPTY
        val rec = FakeRecorder(ignoresSelection = true) { _, _, commitPixels ->
            c.groupUndo("Brush") {
                c.keepLayerData(layer) { commitPixels() }
                c.setLayerData(layer, layer.dataSnapshot().copy(vector = VectorContent(nextId = 2)), "Brush")
            }
            true
        }
        b.strokeHook = { StrokeHook.Record(rec) }
        draw(b)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(2L, layer.vector!!.nextId)
        assertTrue("pixels were committed", pixels(layer.bitmap).any { it != 0 })
        c.undo()
        assertEquals(VectorContent.EMPTY, layer.vector)
        assertTrue(pixels(layer.bitmap).all { it == 0 })
    }

    @Test
    fun aReplacingRecorderPaintsNothing() {
        val c = setup()
        val b = brush(c)
        var pixelResult: Boolean? = null
        val rec = FakeRecorder(replacesStroke = true) { _, _, commitPixels -> pixelResult = commitPixels(); false }
        b.strokeHook = { StrokeHook.Record(rec) }
        b.onDown(fed[0])
        assertNull("no live stroke is shown", c.renderOverride)
        b.onMove(fed[1])
        b.onUp(fed[2])
        assertEquals(false, pixelResult)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(pixels(c.activeLayer.bitmap).all { it == 0 })
        assertEquals(3, rec.points.size)
    }

    @Test
    fun refusedStrokesToastAndCancelledOnesLeaveNoTrace() {
        val c = setup()
        val b = brush(c)
        b.strokeHook = { StrokeHook.Refuse("Watercolor needs a raster layer") }
        draw(b)
        assertEquals("Watercolor needs a raster layer", c.message)
        assertFalse(b.isStroking)
        assertEquals(0, c.undoManager.undoCount)
        val rec = FakeRecorder()
        b.strokeHook = { StrokeHook.Record(rec) }
        b.onDown(fed[0]); b.onMove(fed[1])
        b.onCancel()
        assertEquals(1, rec.cancels)
        assertEquals(0, rec.commits)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(pixels(c.activeLayer.bitmap).all { it == 0 })
    }

    @Test
    fun pathStrokesNeverAskTheHook() {
        val c = setup()
        val b = brush(c)
        var asked = 0
        b.strokeHook = { asked++; StrokeHook.Refuse("no") }
        val input = PathStrokeInput().apply { add(10f, 10f, 1f); add(60f, 40f, 1f); add(120f, 60f, 1f) }
        assertTrue(b.beginPath(input, seed = 7L))
        b.onUp(ToolPoint(120f, 60f, 1f, isStylus = true))
        assertEquals(0, asked)
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun aCoverageSourcePaintsItsPixelsAtTheOffset() {
        val c = setup()
        val b = brush(c)
        c.brush = c.brush.copy(size = 24f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f)
        // The source: a red square at (10..40, 40..60); painted 60 px to the right.
        val src = BitmapUtils.createLayerBitmap(w, h)
        Canvas(src).drawRect(10f, 38f, 40f, 62f, Paint().apply { color = 0xFFFF0000.toInt() })
        var prepared = 0
        var committed: Rect? = null
        var ended = 0
        val sourceShader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setTranslate(60f, 0f) }) }
        b.coverageSource = object : CoverageSource {
            override fun prepare(destDocRect: Rect) { prepared++ }
            override val shader: Shader get() = sourceShader
            override fun prepareCommit(commitRect: Rect) { committed = Rect(commitRect) }
            override fun endStroke() { ended++ }
        }
        b.undoLabelOverride = "Clone stamp"
        val pts = listOf(ToolPoint(72f, 50f), ToolPoint(85f, 50f), ToolPoint(98f, 50f))
        b.onDown(pts[0]); b.onMove(pts[1])
        // The live preview goes through the source too.
        val preview = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(preview), null, target = null)
        assertTrue(prepared > 0)
        assertEquals(0xFFFF0000.toInt(), preview.getPixel(85, 50))
        b.onUp(pts[2])
        assertNotNull(committed)
        assertEquals(1, ended)
        val layer = c.activeLayer
        assertEquals("the source's pixels land at the offset", 0xFFFF0000.toInt(), layer.bitmap.getPixel(85, 50))
        assertEquals(0xFFFF0000.toInt(), layer.bitmap.getPixel(92, 50))
        assertEquals("Clone stamp", c.undoManager.undoLabel)
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun withoutAShaderTheCoverageIsPaintedExactlyAsBefore() {
        // (The v1.4 copy of the painter lives in CompositorGoldenRobolectricTest; here the two
        // ways of building the style must agree bit for bit.)
        val cov = Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8)
        Canvas(cov).drawCircle(32f, 32f, 20f, Paint(Paint.ANTI_ALIAS_FLAG))
        val a = BitmapUtils.createLayerBitmap(64, 64)
        val bb = BitmapUtils.createLayerBitmap(64, 64)
        CoveragePainter().draw(Canvas(a), cov, Rect(0, 0, 64, 64), CoverageStyle(android.graphics.PorterDuff.Mode.SRC_OVER, 0xFF123456.toInt(), 0.7f, 0.4f), null)
        CoveragePainter().draw(Canvas(bb), cov, Rect(0, 0, 64, 64), CoverageStyle(android.graphics.PorterDuff.Mode.SRC_OVER, 0xFF123456.toInt(), 0.7f, 0.4f, shader = null), null)
        val pa = IntArray(64 * 64).also { a.getPixels(it, 0, 64, 0, 0, 64, 64) }
        val pb = IntArray(64 * 64).also { bb.getPixels(it, 0, 64, 0, 0, 64, 64) }
        assertArrayEquals(pa, pb)
        // A refused-in-the-pointer-gate check reads LayerData: an empty snapshot is NONE.
        assertEquals(LayerData.NONE, Layer(1, "x", a).dataSnapshot())
        assertEquals(1f, ViewTransform().density, 0f)
    }
}
