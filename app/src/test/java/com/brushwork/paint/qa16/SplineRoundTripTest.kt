package com.brushwork.paint.qa16

import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.6 final QA: the Path tool's control points (`VPath.spline`) survive everything a user does
 * with an artwork, and the I9 check (`subpaths == [SplineBezier.toSubpath(spline)]`) still holds
 * afterwards, so the curve reopens in Path with its points: save and reload, Duplicate artwork,
 * SVG / PDF export brought back into an artwork of the same size (data kept) and of another size
 * (mapped), every canvas operation (rotate, flip, canvas size, resize image) and the Transform tool
 * (✓). Distort (a homography) turns the curve into a plain Bézier path, as designed.
 *
 * Two curves: an approximated one (order 5, a weight of 3: its Bézier form is a fit, I9 within
 * 0.5 px after an affine map) and an exact cyclic cubic.
 */
@RunWith(RobolectricTestRunner::class)
class SplineRoundTripTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun drawn(): EditorController {
        val c = CurveToolTestSupport.controller()
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for ((x, y) in listOf(40f to 130f, 100f to 30f, 170f to 140f, 240f to 40f, 320f to 130f)) c.tap(x, y)
        t.setOrder(5)
        t.select(2)
        t.setWeight(2, 3f)
        t.endNumericEdit()
        t.commit()
        for ((x, y) in listOf(60f to 270f, 140f to 200f, 230f to 280f, 320f to 210f)) c.tap(x, y)
        t.setCyclic(true)
        t.commit()
        val paths = paths(c.activeLayer)
        assertEquals("two Path-tool curves", 2, paths.size)
        assertEquals(5, paths[0].spline!!.order)
        assertTrue(paths[1].spline!!.cyclic)
        return c
    }

    private fun paths(layer: Layer): List<VPath> = layer.vector!!.objects.filterIsInstance<VPath>()

    /** Both curves keep a spline that passes the I9 check, and each reopens in Path with it. */
    private fun assertSplinesHold(c: EditorController, layer: Layer, where: String) {
        val paths = paths(layer)
        assertEquals("$where: two curves", 2, paths.size)
        for (p in paths) {
            assertTrue("$where: path ${p.id} keeps its spline", p.spline != null)
            assertTrue("$where: path ${p.id} passes the I9 check", SplineBezier.matches(p))
        }
        c.selectLayer(layer)
        val t = c.tool(ToolId.PATH)
        for (p in paths) {
            c.tap(CurveToolTestSupport.onLine(p))
            assertTrue("$where: a tap on path ${p.id} reopens it in Path", t.isReopened)
            assertEquals("$where: with its points", p.spline!!.sanitized(), t.spline)
            t.discard()
        }
        c.selectTool(ToolId.BRUSH)
    }

    @Test
    fun saveReloadAndDuplicateArtworkKeepTheSplines() {
        val c = drawn()
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        val before = paths(c.activeLayer)
        val loaded = runBlocking { repo.load(c.doc.id) }
        val lc = Smoke.controller(app, loaded)
        val layer = loaded.layers.single { it.isVectorLayer }
        assertEquals("reloaded exactly", before, paths(layer))
        assertSplinesHold(lc, layer, "reloaded")
        val copyId = runBlocking { repo.duplicate(c.doc.id) }
        val copy = runBlocking { repo.load(copyId) }
        val cc = Smoke.controller(app, copy)
        val cl = copy.layers.single { it.isVectorLayer }
        assertEquals("duplicated exactly", before, paths(cl))
        assertSplinesHold(cc, cl, "duplicate")
    }

    private fun exportFile(c: EditorController, format: VectorFormat): File =
        File(app.cacheDir, "spline-roundtrip.${format.extension}").apply { writeBytes(V15Fixtures.export(c, ExportOptions(format))) }

    private fun importEditable(c: EditorController, file: File) {
        val state = ExchangeUiState(c)
        state.context = app
        val before = c.doc.layers.size
        state.importUri(Uri.fromFile(file))
        assertTrue("asked", Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
        state.answerEditable()
        assertTrue("imported", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.size > before })
    }

    @Test
    fun svgAndPdfExportsComeBackWithTheirSplines() {
        for (format in VectorFormat.entries) {
            val source = drawn()
            val before = paths(source.activeLayer)
            val file = exportFile(source, format)
            // Same canvas size: the data comes back as it was.
            val same = CurveToolTestSupport.controller(vector = false)
            importEditable(same, file)
            val layer = same.doc.layers.single { it.isVectorLayer }
            assertEquals("$format, same size: the curves as they were", before.map { it.spline }, paths(layer).map { it.spline })
            assertEquals("$format, same size: the Bézier forms as they were", before.map { it.subpaths }, paths(layer).map { it.subpaths })
            assertSplinesHold(same, layer, "$format same size")
            // Another size: the curves are placed (scaled) with their splines.
            val doc = Document("small", "small", 200, 150)
            doc.layers += Layer(doc.newLayerId(), "Background", com.brushwork.paint.engine.BitmapUtils.createLayerBitmap(200, 150))
            val small = Smoke.controller(app, doc)
            importEditable(small, file)
            val placed = small.doc.layers.single { it.isVectorLayer }
            assertSplinesHold(small, placed, "$format half size")
            // Every control point follows the one placement (scale + offset) of the import.
            val a0 = before[1].spline!!.points[0]
            val a1 = before[1].spline!!.points[2]
            val b0 = paths(placed)[1].spline!!.points[0]
            val b1 = paths(placed)[1].spline!!.points[2]
            val s = (b1.x - b0.x) / (a1.x - a0.x)
            assertTrue("$format: smaller ($s)", s in 0.3f..0.55f)
            val ex = b0.x - s * a0.x
            val ey = b0.y - s * a0.y
            for ((p, q) in before.zip(paths(placed))) {
                for ((u, v) in p.spline!!.points.zip(q.spline!!.points)) {
                    assertEquals("$format: x placed", s * u.x + ex, v.x, 0.01f)
                    assertEquals("$format: y placed", s * u.y + ey, v.y, 0.01f)
                    assertEquals("$format: weight kept", u.weight, v.weight, 0f)
                }
            }
        }
    }

    @Test
    fun canvasOperationsMapTheSplines() {
        val c = drawn()
        val layer = c.activeLayer
        val original = paths(layer)
        fun op(name: String, run: () -> Boolean) {
            assertTrue("$name ran", run())
            assertTrue("$name done", Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering })
            assertSplinesHold(c, c.doc.layers.single { it.isVectorLayer }, name)
        }
        val steps = c.undoManager.undoCount
        op("Rotate 90° clockwise") { CanvasOps.applyRotate(c, CanvasRotation.CW_90) }
        op("Flip canvas horizontally") { CanvasOps.applyFlip(c, true) }
        op("Flip canvas vertically") { CanvasOps.applyFlip(c, false) }
        op("Canvas size") { CanvasOps.applyResizeCanvas(c, c.doc.width + 60, c.doc.height + 40, 2, 1, null) }
        op("Resize image") { CanvasOps.applyResizeImage(c, c.doc.width * 3 / 2, c.doc.height * 3 / 2, c.doc.dpi, Resample.BILINEAR) }
        op("Rotate 90° counter-clockwise") { CanvasOps.applyRotate(c, CanvasRotation.CCW_90) }
        assertEquals("one step each", steps + 6, c.undoManager.undoCount)
        repeat(6) { c.undo(); Smoke.pumpUntil { c.busyMessage == null } }
        assertEquals("undone exactly", original, paths(c.doc.layers.single { it.isVectorLayer }))
        assertSplinesHold(c, c.doc.layers.single { it.isVectorLayer }, "undone")
    }

    private fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil { c.currentTool.hasPendingWork })
        return c.currentTool as TransformTool
    }

    @Test
    fun transformKeepsTheSplinesAndDistortDropsThem() {
        val c = drawn()
        val layer = c.activeLayer
        val steps = c.undoManager.undoCount
        var t = transform(c)
        t.setRotation(25.0)
        t.endNumericEdit()
        t.setScalePercent(80.0)
        t.endNumericEdit()
        t.moveBy(12f, -7f)
        t.flip(horizontal = true)
        t.commit()
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && !c.vectors.isRendering })
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertSplinesHold(c, layer, "Transform ✓")
        // Distort: a homography; the curves become plain Bézier paths (no stale spline, I9).
        t = transform(c)
        t.mode = TransformTool.Mode.DISTORT
        val k = t.transformState!!.corner(2)
        c.drag(k.x to k.y, k.x + 15f to k.y + 10f, k.x + 30f to k.y + 20f)
        assertTrue("distorted", t.transformState!!.isDistorted)
        t.commit()
        t.mode = TransformTool.Mode.FREE
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && !c.vectors.isRendering })
        val distorted = paths(layer)
        assertEquals(2, distorted.size)
        for (p in distorted) {
            assertNull("distorted path ${p.id} has no spline", p.spline)
            assertTrue(!SplineBezier.matches(p))
        }
        c.undo()
        Smoke.pumpUntil { c.busyMessage == null }
        assertSplinesHold(c, layer, "Distort undone")
    }

    @Test
    fun flippingTheLayerKeepsTheSplines() {
        val c = drawn()
        val layer = c.activeLayer
        c.flipLayer(layer, horizontal = true)
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && !c.vectors.isRendering })
        assertSplinesHold(c, layer, "Flip layer horizontally")
        c.flipLayer(layer, horizontal = false)
        assertTrue(Smoke.pumpUntil { c.busyMessage == null && !c.vectors.isRendering })
        assertSplinesHold(c, layer, "Flip layer vertically")
    }
}
