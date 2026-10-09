package com.brushwork.paint.tools.vector.spline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.H
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.INK
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.W
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 area B review: the curve tools' new features driven end to end through the controller,
 * as a finger user would: the user's own item-5 example with the default brush stroke, a drag
 * inside the gizmo box, the trash cell on a pending path and on one selected point, a rotated
 * Curve saved and reopened, the controller's undo and redo of a group gesture, "Select several"
 * ending with the edited object, and all of it on a vector layer inside a folder.
 */
@RunWith(RobolectricTestRunner::class)
class CurveReviewFlowsRobolectricTest {

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    private fun opaqueNear(c: EditorController, p: Vec2, r: Int = 5): Int {
        val b = c.activeLayer.bitmap
        var n = 0
        for (y in (p.y.toInt() - r)..(p.y.toInt() + r)) for (x in (p.x.toInt() - r)..(p.x.toInt() + r)) {
            if (x !in 0 until W || y !in 0 until H) continue
            if (Vec2(x.toFloat(), y.toFloat()).distanceTo(p) <= r && (b.getPixel(x, y) ushr 24) >= 128) n++
        }
        return n
    }

    /**
     * The user's words (item 5): a Path of 3 points whose first and last are at 0 % draws a stroke
     * that swells in the middle, with the tool's default stroke (the current brush), on a vector and
     * a raster layer; the same with 4 points. Thickness set as the strip does: tap a point, set it.
     */
    @Test
    fun theUsersThreeAndFourPointPathsWithTheirEndsAtZeroShowAStrokeWithTheDefaultBrush() {
        val shapes = listOf(
            listOf(60f to 220f, 200f to 60f, 340f to 220f),
            listOf(50f to 220f, 150f to 70f, 250f to 70f, 350f to 220f),
        )
        for (vector in listOf(true, false)) for (points in shapes) {
            val c = controller(vector)
            c.brush = BrushLibrary.defaultBrush.copy(size = 14f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            val t = c.tool(ToolId.PATH)
            assertEquals("the default stroke", CurveStroke.BRUSH, t.settings.stroke)
            for ((x, y) in points) c.tap(x, y)
            for (i in listOf(0, points.lastIndex)) {
                val (x, y) = points[i]
                t.deselect()
                c.tap(x, y)
                assertEquals("vector $vector, ${points.size} points: the tap selects point $i", i, t.selectedPoint)
                t.setWidth(i, 0f)
                t.endNumericEdit()
            }
            t.flushPreview()
            val s = t.spline!!
            val line = VectorOps.toVectorPath(VPath(1L, subpaths = listOf(SplineBezier.toSubpath(s)))).flatten(0.25f).first().points
            val mid = line[line.size / 2]
            assertEquals(listOf(0f) + List(points.size - 2) { 1f } + 0f, s.points.map { it.width })
            // (The live stroke is the painting tool's own, drawn over the layer until ✓.)
            assertTrue("vector $vector, ${points.size} points: the brush stroke is live", t.brushLive)
            t.commit()
            assertEquals(CurveTool.PATH_LABEL, c.undoManager.undoLabel)
            assertTrue("vector $vector, ${points.size} points: the stroke shows in the middle", opaqueNear(c, mid) > 20)
            // The ends are at 0 %: nothing right at the first point.
            assertEquals("vector $vector, ${points.size} points: the end is thin", 0, opaqueNear(c, Vec2(points[0].first, points[0].second), 1))
        }
    }

    /** §3.1: a drag inside the gizmo box that does not start on a point (or a handle) moves the group: one step, undone exactly. */
    @Test
    fun aDragInsideTheBoxAwayFromThePointsMovesTheGroup() {
        val pts = listOf(Vec2(40f, 150f), Vec2(200f, 40f), Vec2(360f, 150f), Vec2(200f, 260f))
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val c = controller()
            val t = c.tool(id)
            for (p in pts) assertTrue(t.addAnchor(p))
            t.selectPoints(PointSelection.of(4, 0, 1, 2))
            val vt = c.viewTransform
            val layout = PointGizmo().layout(listOf(pts[0], pts[1], pts[2]), vt)!!
            val grab = c.docLength(48f)
            // A place inside the box (MOVE), well away from every point and handle.
            var start: Vec2? = null
            loop@ for (y in 50..250 step 5) for (x in 50..350 step 5) {
                val q = Vec2(x.toFloat(), y.toFloat())
                if (PointGizmo().hit(layout, vt.docToScreen(q), vt) != PointGizmo.Part.MOVE) continue
                if (pts.any { it.distanceTo(q) < grab }) continue
                start = q
                break@loop
            }
            assertNotNull("$id: a free place inside the box", start)
            val from = start!!
            val n = steps(t)
            c.drag(from.x to from.y, from.x + 5f to from.y, from.x + 20f to from.y - 10f)
            for (i in 0..2) assertEquals("$id point $i", pts[i] + Vec2(20f, -10f), t.pointAt(i))
            assertEquals("$id: the unselected point stays", pts[3], t.pointAt(3))
            assertEquals("$id: still the group", listOf(0, 1, 2), t.pointSelection.indices)
            assertEquals("$id: one step", n + 1, steps(t))
            c.undo()
            assertEquals("$id: undone exactly", pts, (0..3).map { t.pointAt(it) })
            assertEquals("$id: with its selection", listOf(0, 1, 2), t.pointSelection.indices)
            c.redo()
            assertEquals("$id: redone", pts[1] + Vec2(20f, -10f), t.pointAt(1))
            t.discard()
        }
    }

    /**
     * §3.13: the trash cell on a pending (never applied) path is an in-tool step: the app's undo
     * brings the path back, redo deletes it again; switching tools afterwards writes nothing.
     */
    @Test
    fun aTrashedPendingPathComesBackWithTheAppsUndoAndLeavesNothingBehind() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val c = controller()
            val layer = c.activeLayer
            val t = c.tool(id)
            t.plainLine()
            for (p in listOf(Vec2(60f, 60f), Vec2(200f, 120f), Vec2(320f, 60f))) assertTrue(t.addAnchor(p))
            t.deselect()
            val before = (0..2).map { t.pointAt(it) }
            t.objectDeletion!!.delete()
            assertEquals("$id", 0, t.pointCount)
            c.undo()
            assertEquals("$id: back", before, (0..2).map { t.pointAt(it) })
            c.redo()
            assertEquals("$id: deleted again", 0, t.pointCount)
            c.undo()
            assertEquals("$id: back again", 3, t.pointCount)
            c.redo()
            c.selectTool(ToolId.BRUSH)
            assertEquals("$id: nothing written", VectorContent.EMPTY, layer.vector)
            assertEquals("$id: no document step", 0, c.undoManager.undoCount)
        }
    }

    /**
     * I12 (one point selected behaves as v1.6) and §3.13: with ONE point selected the trash cell
     * deletes it as the strip's "Delete point" does, also from a 2-point path; with two of three
     * selected the minimum-points refusal applies.
     */
    @Test
    fun theTrashCellWithOnePointSelectedDeletesItAsV16() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val c = controller()
            val t = c.tool(id)
            t.plainLine()
            for (p in listOf(Vec2(60f, 60f), Vec2(200f, 120f))) assertTrue(t.addAnchor(p))
            t.select(0)
            assertEquals("$id", "Delete selected points", t.objectDeletion!!.deleteLabel)
            c.message = null
            t.objectDeletion!!.delete()
            assertEquals("$id: deleted", listOf(Vec2(200f, 120f)), listOf(t.pointAt(0)))
            assertEquals("$id", 1, t.pointCount)
            assertNull("$id: no refusal", c.message)
            assertTrue(t.undoStep())
            assertEquals("$id", 2, t.pointCount)
            assertTrue(t.addAnchor(Vec2(320f, 60f)))
            t.selectPoints(PointSelection.of(3, 0, 1))
            assertFalse("$id: one would be left", t.deleteSelectedPoints())
            assertEquals("$id", 3, t.pointCount)
            assertNotNull("$id: the minimum-points toast", c.message)
            t.discard()
        }
    }

    /** §3.12 with the Curve tool: a group turned with its dragged tangents, applied, saved, loaded and reopened keeps every anchor and handle. */
    @Test
    fun aRotatedCurveWithDraggedHandlesSavedAndReopenedKeepsItsHandles() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        for (p in listOf(Vec2(80f, 200f), Vec2(160f, 80f), Vec2(260f, 220f), Vec2(330f, 90f))) assertTrue(t.addAnchor(p))
        t.toggleSelectAll()
        t.applyHandleScale(140f)
        assertTrue(t.anchors.all { it.handleOut != null && it.handleIn != null })
        t.beginGroupEdit("Rotate")
        t.setGroupTransform(Affine2.rotateAbout(Vec2(200f, 150f), 90f) * Affine2.scaleAbout(Vec2(200f, 150f), 0.6f, 0.6f))
        t.endGroupEdit()
        val want = t.anchors
        val handles = want.indices.map { t.handlesOf(it) }
        t.commit()
        val p = layer.vector!!.objects.single() as VPath
        val back = VectorCodec.decode(VectorCodec.encode(layer.vector!!))
        assertEquals(layer.vector, back)
        layer.vector = back
        c.selectTool(ToolId.CURVE)
        c.tap(onLine(p))
        assertTrue(t.isReopened)
        assertEquals(want.size, t.anchors.size)
        for (i in want.indices) {
            assertEquals("anchor $i x", want[i].x, t.anchors[i].x, 1e-3f)
            assertEquals("anchor $i y", want[i].y, t.anchors[i].y, 1e-3f)
            val (hi, ho) = t.handlesOf(i)
            assertEquals("handle in $i", handles[i].first.x, hi.x, 1e-2f)
            assertEquals("handle in $i", handles[i].first.y, hi.y, 1e-2f)
            assertEquals("handle out $i", handles[i].second.x, ho.x, 1e-2f)
            assertEquals("handle out $i", handles[i].second.y, ho.y, 1e-2f)
        }
        t.discard()
    }

    /** "Select several" turns itself off when the edited object changes or the tool closes (§3.1). */
    @Test
    fun selectSeveralEndsWithTheEditedObjectAndTheTool() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for (p in listOf(Vec2(40f, 60f), Vec2(120f, 30f), Vec2(200f, 60f))) c.tap(p)
        t.commit()
        for (p in listOf(Vec2(40f, 240f), Vec2(120f, 210f), Vec2(200f, 240f), Vec2(300f, 200f))) c.tap(p)
        t.commit()
        val (a, b) = layer.vector!!.objects.map { it as VPath }
        c.tap(onLine(a))
        assertTrue(t.isReopened)
        t.selectSeveral = true
        assertTrue(t.selectSeveral)
        t.commit()
        assertFalse(t.selectSeveral)
        c.tap(onLine(b))
        assertTrue(t.isReopened)
        assertEquals(4, t.pointCount)
        assertFalse("another object: off", t.selectSeveral)
        t.selectSeveral = true
        c.selectTool(ToolId.BRUSH)
        c.selectTool(ToolId.PATH)
        assertFalse("the tool closed: off", t.selectSeveral)
    }

    /**
     * Folders (§3.8 with area B's tools): a Path on a vector layer inside a folder is drawn,
     * applied, reopened, scaled through the pill and applied (one step each, undo and redo exact),
     * deleted with the trash cell and brought back, and handed to the Path tool by [CurveTool.openPath]
     * with its points selected; the tree stays valid throughout.
     */
    @Test
    fun aPathOnAVectorLayerInsideAFolder() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", W, H)
        val bg = Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(W, H))
        val folder = Layer.newFolder(doc.newLayerId(), "Folder")
        val vec = Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also {
            it.vector = VectorContent.EMPTY
            it.parentId = folder.id
        }
        doc.layers += listOf(bg, vec, folder)
        doc.activeLayerIndex = 1
        assertNull(LayerTree.check(doc.layers))
        val c = EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = INK
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for (p in listOf(Vec2(40f, 240f), Vec2(110f, 50f), Vec2(190f, 250f), Vec2(270f, 40f), Vec2(350f, 230f))) c.tap(p)
        t.setSharp(2, true)
        t.commit()
        assertEquals(1, c.undoManager.undoCount)
        val p = vec.vector!!.objects.single() as VPath
        assertTrue("I9", SplineBezier.matches(p))
        // Reopened inside the folder; the pill scales the whole path about its centre.
        c.tap(onLine(p))
        assertTrue(t.isReopened)
        assertEquals(CurveTool.CENTER_LABEL, t.pillPosition.label)
        val centre = t.pillPosition.position!!
        t.objectScale!!.setScale(50f, 50f)
        t.commit()
        assertEquals(2, c.undoManager.undoCount)
        val scaled = vec.vector!!.objects.single() as VPath
        assertTrue("I9 after the scale", SplineBezier.matches(scaled))
        for (i in p.spline!!.points.indices) {
            val a = p.spline!!.points[i]
            val b = scaled.spline!!.points[i]
            assertEquals("point $i", centre.x + (a.x - centre.x) * 0.5f, b.x, 1e-3f)
            assertTrue(b.sharp == a.sharp)
        }
        c.undo()
        assertEquals(p, vec.vector!!.objects.single())
        c.redo()
        assertEquals(scaled, vec.vector!!.objects.single())
        // Saved and loaded: still a Path.
        vec.vector = VectorCodec.decode(VectorCodec.encode(vec.vector!!))
        assertTrue(SplineBezier.matches(vec.vector!!.objects.single() as VPath))
        // The trash cell deletes it from the folder's layer as one step; undo brings it back.
        c.tap(onLine(scaled))
        assertTrue(t.isReopened)
        assertEquals("Delete path", t.objectDeletion!!.deleteLabel)
        t.objectDeletion!!.delete()
        assertTrue(vec.vector!!.objects.isEmpty())
        assertEquals("Delete path", c.undoManager.undoLabel)
        c.undo()
        assertEquals(1, vec.vector!!.objects.size)
        val restored = vec.vector!!.objects.single() as VPath
        // openPath from another tool: the Path tool opens it with the points handed over selected.
        c.selectTool(ToolId.BRUSH)
        (c.tools.getValue(ToolId.CURVE) as CurveTool).openPath(vec.id, restored.id, intArrayOf(1, 3, 99))
        assertEquals(ToolId.PATH, c.activeToolId)
        assertTrue(t.isReopened)
        assertEquals(listOf(1, 3), t.pointSelection.indices)
        t.discard()
        assertNull(LayerTree.check(doc.layers))
        assertEquals(listOf(bg, vec, folder), doc.layers)
        assertEquals(folder.id, vec.parentId)
    }
}
