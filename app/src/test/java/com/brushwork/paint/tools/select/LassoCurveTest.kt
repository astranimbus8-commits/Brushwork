package com.brushwork.paint.tools.select

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.ui.editor.HistoryLabels
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
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Curve lasso: the selection follows the smooth curve through the tapped points (not the
 * polygon of chords), sharp points make corners, one point edit per undo / redo, points can be
 * dragged, long-pressed (sharp / smooth / delete), inserted on the outline, and a touch that a
 * second finger cancels leaves no trace. Freehand and polygon keep working next to it.
 */
@RunWith(RobolectricTestRunner::class)
class LassoCurveTest {

    private fun controller(size: Int = 200): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", size, size)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(size, size))
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx))
    }

    private fun lasso(c: EditorController): LassoTool {
        c.selectTool(ToolId.LASSO)
        return (c.tools.getValue(ToolId.LASSO) as LassoTool).also { it.setKind(LassoKind.CURVE) }
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y)); pointerUp(ToolPoint(x, y))
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** Waits until the lasso's background rasterization has published its result ([done]). */
    private fun awaitSelection(c: EditorController, tool: LassoTool, done: () -> Boolean = { c.selection != null }) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!(done() && !tool.busy) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertTrue("the selection was applied", done())
        assertFalse("the lasso is ready again", tool.busy)
    }

    private fun anchors(vararg xy: Float, sharp: Boolean = false): List<CurveAnchor> =
        (xy.indices step 2).map { CurveAnchor(xy[it], xy[it + 1], sharp = sharp) }

    private fun curveMask(a: List<CurveAnchor>, size: Int, aa: Boolean = true): Selection =
        LassoTool.rasterize(LassoCurveGeometry.path(a), size, size, aa)

    private fun polygonMask(a: List<CurveAnchor>, size: Int): Selection =
        LassoTool.rasterize(LassoTool.polygonPath(a.flatMap { listOf(it.x, it.y) }.toFloatArray()), size, size, true)

    private fun area(s: Selection): Long = s.toBytes().sumOf { (it.toInt() and 0xFF).toLong() }

    /**
     * A point halfway between the outline of segment [s] and its chord, where they are furthest
     * apart for t in [tFrom]..[tTo] (asserting they are at least [minGap] px apart there, so the
     * point is clear of both edges' anti-aliasing).
     */
    private fun betweenCurveAndChord(a: List<CurveAnchor>, s: Int, tFrom: Float = 0.05f, tTo: Float = 0.95f, minGap: Float = 7f): Vec2 {
        val (p0, c1, c2, p1) = CurveGeometry.segment(a, s, closed = true, tension = 0f, polyline = false)
        var best = p0; var bestChord = p0; var bestD = -1f
        var t = tFrom
        while (t <= tTo) {
            val q = VectorPath.cubicPoint(p0, c1, c2, p1, t)
            val onChord = Geometry.projectOnSegment(q, p0, p1)
            val d = q.distanceTo(onChord)
            if (d > bestD) { best = q; bestChord = onChord; bestD = d }
            t += 0.01f
        }
        assertTrue("the curve leaves the chord by $bestD px", bestD >= minGap)
        return best.lerp(bestChord, 0.5f)
    }

    private fun Selection.at(p: Vec2) = alphaAt(p.x.toInt(), p.y.toInt())

    // ------------------------------------------------------------------ geometry

    @Test
    fun selectionFollowsTheSmoothCurveNotTheChords() {
        val size = 400
        // Convex: the smooth outline bulges out past every chord.
        val diamond = anchors(200f, 60f, 340f, 200f, 200f, 340f, 60f, 200f)
        val bulge = betweenCurveAndChord(diamond, 0)
        assertEquals("inside the curve", 255, curveMask(diamond, size).at(bulge))
        assertEquals("outside the polygon of chords", 0, polygonMask(diamond, size).at(bulge))
        assertEquals("center", 255, curveMask(diamond, size).alphaAt(200, 200))
        assertEquals("well outside", 0, curveMask(diamond, size).alphaAt(350, 60))

        // Concave notch: near the notch the smooth outline runs inside the chord, and the strip
        // between them (inside the polygon) is NOT selected.
        val notch = anchors(60f, 80f, 200f, 260f, 340f, 80f, 340f, 340f, 60f, 340f)
        val dent = betweenCurveAndChord(notch, 0, tFrom = 0.5f)
        assertEquals("the polygon would select it", 255, polygonMask(notch, size).at(dent))
        assertEquals("the curve lasso does not", 0, curveMask(notch, size).at(dent))
        assertEquals("inside both", 255, curveMask(notch, size).alphaAt(200, 320))
    }

    @Test
    fun sharpPointsMakeCornersAndAllSharpIsThePolygon() {
        val size = 400
        val smooth = anchors(200f, 60f, 340f, 200f, 200f, 340f, 60f, 200f)
        val sharp = smooth.map { it.copy(sharp = true) }
        val oneSharp = smooth.mapIndexed { i, a -> if (i == 1) a.copy(sharp = true) else a }
        assertTrue(
            "every point sharp: exactly the polygon",
            curveMask(sharp, size).toBytes().contentEquals(polygonMask(sharp, size).toBytes()),
        )
        val aSmooth = area(curveMask(smooth, size))
        val aOne = area(curveMask(oneSharp, size))
        val aSharp = area(curveMask(sharp, size))
        assertTrue("a sharp point pulls its bulges in ($aSharp < $aOne < $aSmooth)", aSharp < aOne && aOne < aSmooth)
        // The bulge next to the sharp point is gone, the one away from it stays.
        val bulge = betweenCurveAndChord(smooth, 1, tFrom = 0.05f, tTo = 0.3f, minGap = 5f)
        assertEquals(255, curveMask(smooth, size).at(bulge))
        assertTrue("near the sharp corner", curveMask(oneSharp, size).at(bulge) < 255)
        val far = betweenCurveAndChord(smooth, 3)
        assertEquals("the opposite side keeps its curve", 255, curveMask(oneSharp, size).at(far))
    }

    @Test
    fun tappingTheFirstPointClosesTheCurveIntoASelection() {
        val c = controller()
        val tool = lasso(c)
        assertEquals(LassoKind.CURVE, tool.kind)
        for ((x, y) in listOf(100f to 20f, 170f to 100f, 100f to 180f, 30f to 100f)) c.tap(x, y)
        assertEquals(4, tool.curve.count)
        assertTrue(tool.hasPendingWork)
        c.tap(102f, 22f)
        assertFalse("closed", tool.hasPendingWork)
        awaitSelection(c, tool)
        val sel = c.selection!!
        // Pixel (136, 54) lies ~5 px outside the chord from (100, 20) to (170, 100) (the polygon
        // lasso would leave it out) and ~8 px inside the curve through those points.
        assertTrue(Geometry.distanceToSegment(Vec2(136.5f, 54.5f), Vec2(100f, 20f), Vec2(170f, 100f)) > 4f)
        assertEquals(0, polygonMask(anchors(100f, 20f, 170f, 100f, 100f, 180f, 30f, 100f), 200).alphaAt(136, 54))
        assertEquals(255, sel.alphaAt(136, 54))
        assertEquals(255, sel.alphaAt(100, 100))
        assertEquals(0, sel.alphaAt(190, 190))
        // Undo of the document history takes the selection back (one step).
        c.undo()
        assertNull(c.selection)
    }

    // ------------------------------------------------------------------ editing

    @Test
    fun undoTakesBackOnePointAndRedoBringsItBack() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f)
        assertEquals(3, tool.curve.count)
        assertTrue(tool.canUndoStep)
        c.undo()
        assertEquals("only the last point is gone", 2, tool.curve.count)
        assertTrue(tool.hasPendingWork)
        assertTrue(tool.canRedoStep)
        c.redo()
        assertEquals(3, tool.curve.count)
        assertEquals(Vec2(150f, 150f), tool.curve.anchors[2].pos)
        assertFalse(tool.canRedoStep)
        // The strip's buttons do the same.
        tool.undoLastCorner()
        assertEquals(2, tool.curve.count)
        tool.redoStep()
        assertEquals(3, tool.curve.count)
        // A new point drops what could be redone.
        c.undo()
        c.tap(60f, 160f)
        assertEquals(3, tool.curve.count)
        assertEquals(0, tool.curve.redoCount)
        // Undo down to nothing: the curve is gone and the document history is next.
        repeat(3) { c.undo() }
        assertFalse(tool.hasPendingWork)
        assertFalse(c.canUndo)
        assertTrue("the strip's redo still brings points back", tool.redoStep())
        assertEquals(1, tool.curve.count)
    }

    @Test
    fun pointsCanBeDraggedDeletedAndMadeSharpOneUndoStepEach() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f); c.tap(50f, 150f)
        // 14 px from the second point: the touch drags it instead of adding one.
        c.drag(160f to 60f, 170f to 50f, 175f to 40f)
        assertEquals(4, tool.curve.count)
        assertEquals(Vec2(175f, 40f), tool.curve.anchors[1].pos)
        // Dragging the first point moves it (a tap there would close the curve).
        c.drag(52f to 52f, 40f to 40f, 30f to 30f)
        assertEquals(Vec2(30f, 30f), tool.curve.anchors[0].pos)
        assertTrue(tool.hasPendingWork)
        c.undo()
        assertEquals(Vec2(50f, 50f), tool.curve.anchors[0].pos)
        c.undo()
        assertEquals(Vec2(150f, 50f), tool.curve.anchors[1].pos)

        // A long press selects a point for the strip's actions; lifting keeps it selected.
        c.pointerDown(ToolPoint(150f, 150f))
        assertTrue("the long press is taken", c.pointerLongPress(ToolPoint(150f, 150f)))
        c.pointerUp(ToolPoint(150f, 150f))
        assertEquals(2, tool.curve.selected)
        assertEquals(4, tool.curve.count)
        tool.curve.setSharp(2, true)
        assertTrue(tool.curve.anchors[2].sharp)
        c.undo()
        assertFalse("undo makes it smooth again", tool.curve.anchors[2].sharp)
        c.redo()
        assertTrue(tool.curve.anchors[2].sharp)
        tool.curve.deleteAnchor(2)
        assertEquals(3, tool.curve.count)
        assertEquals(-1, tool.curve.selected)
        c.undo()
        assertEquals(4, tool.curve.count)
        assertEquals(Vec2(150f, 150f), tool.curve.anchors[2].pos)

        // A plain tap on a point toggles its selection; a long press elsewhere is not taken.
        c.tap(150f, 50f)
        assertEquals(1, tool.curve.selected)
        c.tap(150f, 50f)
        assertEquals(-1, tool.curve.selected)
        c.pointerDown(ToolPoint(100f, 100f))
        assertFalse(c.pointerLongPress(ToolPoint(100f, 100f)))
        c.pointerCancel()
        assertEquals(4, tool.curve.count)
        tool.discard()
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun undoRedoFeedbackNamesPointsInCurveModeAndCornersForThePolygon() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f)
        // What the two-finger tap / undo button shows before it takes the point back.
        assertEquals("Undo: last point", HistoryLabels.undo(c))
        c.undo()
        assertEquals("Redo: last point", HistoryLabels.redo(c))
        tool.discard()
        tool.setKind(LassoKind.POLYGON)
        c.tap(50f, 50f); c.tap(150f, 50f)
        assertEquals("the polygon keeps its wording", "Undo: last corner", HistoryLabels.undo(c))
        tool.discard()
    }

    @Test
    fun nonFiniteTouchesAddNothing() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f)
        c.tap(Float.NaN, 20f)
        c.tap(Float.POSITIVE_INFINITY, Float.NaN)
        assertEquals(3, tool.curve.count)
        // A bad sample in the middle of a drag or at lift-off keeps the last good position.
        c.pointerDown(ToolPoint(40f, 170f))
        c.pointerMove(ToolPoint(45f, 175f))
        c.pointerMove(ToolPoint(Float.NaN, Float.NaN))
        c.pointerUp(ToolPoint(Float.NaN, 180f))
        assertEquals(4, tool.curve.count)
        assertEquals(Vec2(45f, 175f), tool.curve.anchors[3].pos)
        c.drag(150f to 150f, 160f to 165f, 170f to 170f)
        c.pointerDown(ToolPoint(170f, 170f))
        c.pointerMove(ToolPoint(180f, 185f))
        c.pointerUp(ToolPoint(Float.NaN, Float.NaN))
        assertEquals(Vec2(180f, 185f), tool.curve.anchors[2].pos)
        assertTrue(tool.curve.anchors.all { it.x.isFinite() && it.y.isFinite() })
        tool.commit()
        awaitSelection(c, tool)
        assertEquals(255, c.selection!!.alphaAt(120, 100))
    }

    @Test
    fun deletingTheLastPointThrowsTheCurveAway() {
        val c = controller()
        val tool = lasso(c)
        c.selectAll()
        c.tap(50f, 50f); c.tap(150f, 50f)
        c.tap(50f, 50f) // selects the first point (two points don't close)
        assertEquals(0, tool.curve.selected)
        tool.curve.deleteAnchor(0)
        assertEquals(1, tool.curve.count)
        // Deleting down to one point is still one undo step.
        c.undo()
        assertEquals(2, tool.curve.count)
        tool.curve.deleteAnchor(1)
        tool.curve.deleteAnchor(0)
        // No point left: nothing pending and no in-tool steps kept, so the next undo is the
        // document's (the select all) instead of silently skipping stranded point edits.
        assertFalse(tool.hasPendingWork)
        assertEquals(0, tool.curve.undoCount)
        assertEquals(0, tool.curve.redoCount)
        assertNotNull(c.selection)
        c.undo()
        assertNull(c.selection)
        // A new curve starts clean.
        c.tap(60f, 60f)
        assertEquals(1, tool.curve.count)
        assertEquals(1, tool.curve.undoCount)
    }

    @Test
    fun aCancelledTouchLeavesNoTrace() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f)
        c.undo()
        val redoBefore = tool.curve.redoCount
        val undoBefore = tool.curve.undoCount
        // The first finger of a two-finger tap / pinch lands on empty canvas.
        c.pointerDown(ToolPoint(60f, 170f))
        c.pointerMove(ToolPoint(70f, 175f))
        c.pointerCancel()
        assertEquals(2, tool.curve.count)
        assertEquals(redoBefore, tool.curve.redoCount)
        assertEquals(undoBefore, tool.curve.undoCount)
        // ... or on a point, and drags it before the second finger lands.
        c.pointerDown(ToolPoint(150f, 50f))
        c.pointerMove(ToolPoint(120f, 90f))
        assertEquals(Vec2(120f, 90f), tool.curve.anchors[1].pos)
        c.pointerCancel()
        assertEquals(Vec2(150f, 50f), tool.curve.anchors[1].pos)
        assertEquals(redoBefore, tool.curve.redoCount)
        assertEquals(undoBefore, tool.curve.undoCount)
        assertTrue("the redo step survived", tool.redoStep())
        assertEquals(3, tool.curve.count)
    }

    @Test
    fun aTapOnTheOutlineInsertsAPointThereAndADragPlacesItWhereTheFingerLifts() {
        val c = controller()
        val tool = lasso(c)
        c.tap(100f, 30f); c.tap(170f, 100f); c.tap(100f, 170f); c.tap(30f, 100f)
        val seg = CurveGeometry.segment(tool.curve.anchors, 0, closed = true, tension = 0f, polyline = false)
        val mid = VectorPath.cubicPoint(seg[0], seg[1], seg[2], seg[3], 0.5f)
        c.tap(mid.x, mid.y)
        assertEquals(5, tool.curve.count)
        assertEquals("inserted between the first two points", mid, tool.curve.anchors[1].pos)
        c.undo()
        assertEquals(4, tool.curve.count)
        // Away from the outline: a drag appends the point where the finger lifts.
        c.drag(20f to 190f, 15f to 195f, 10f to 196f)
        assertEquals(5, tool.curve.count)
        assertEquals(Vec2(10f, 196f), tool.curve.anchors[4].pos)
        tool.commit()
        awaitSelection(c, tool)
        assertFalse(tool.hasPendingWork)
    }

    // ------------------------------------------------------------------ preview

    @Test
    fun thePreviewIsTheCurvedOutlineWithMarchingAnts() {
        val c = controller()
        val tool = lasso(c)
        for ((x, y) in listOf(100f to 20f, 170f to 100f, 100f to 180f, 30f to 100f)) c.tap(x, y)
        fun shot(): Bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { c.drawOverlays(Canvas(it), 0f) }
        fun inked(b: Bitmap, x: Int, y: Int, r: Int = 1): Boolean =
            (x - r..x + r).any { i -> (y - r..y + r).any { j -> b.getPixel(i, j) ushr 24 > 0 } }
        val first = shot()
        // The view transform is the identity here: the outline passes through (143.75, 50), the
        // middle of the first curved stretch, not through (135, 60), the middle of its chord.
        assertTrue("drawn along the curve", inked(first, 144, 50))
        assertFalse("not along the chord", inked(first, 135, 60))

        // The dashes march: each drawn frame asks for the next one ~66 ms later (one at a time).
        var invalidations = 0
        c.onInvalidate = { invalidations++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        assertEquals("one frame requested", 1, invalidations)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals("nothing more until that frame is drawn", 1, invalidations)
        val later = shot()
        assertFalse("the dashes moved", first.sameAs(later))
        assertTrue(tool.hasPendingWork)

        // Without points nothing keeps redrawing.
        tool.discard()
        c.drawOverlays(Canvas(first), 0f)
        invalidations = 0
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(0, invalidations)
    }

    // ------------------------------------------------------------------ modes and options

    @Test
    fun selectionModesAntiAliasAndTheModeSwitch() {
        val c = controller()
        val tool = lasso(c)
        // Hard edges: only 0 and 255.
        tool.settings = tool.settings.copy(antiAlias = false)
        for ((x, y) in listOf(100f to 20f, 170f to 100f, 100f to 180f, 30f to 100f)) c.tap(x, y)
        tool.commit()
        awaitSelection(c, tool)
        assertTrue(c.selection!!.toBytes().all { it.toInt() == 0 || it.toInt() == -1 })
        // Subtract a curve from "select all".
        c.selectAll()
        tool.mode = SelectionMode.SUBTRACT
        for ((x, y) in listOf(100f to 60f, 140f to 100f, 100f to 140f, 60f to 100f)) c.tap(x, y)
        assertEquals(4, tool.curve.count)
        tool.commit()
        awaitSelection(c, tool) { c.selection?.alphaAt(100, 100) == 0 }
        assertEquals(255, c.selection!!.alphaAt(10, 10))

        // Switching the mode drops an unfinished curve; the choice is remembered.
        tool.mode = SelectionMode.REPLACE
        c.tap(50f, 50f); c.tap(150f, 50f)
        assertTrue(tool.hasPendingWork)
        tool.setKind(LassoKind.POLYGON)
        assertFalse(tool.hasPendingWork)
        assertEquals(0, tool.curve.count)
        assertTrue(tool.settings.polygon)
        tool.setKind(LassoKind.CURVE)
        assertEquals("a new lasso starts in curve mode", LassoKind.CURVE, LassoTool(c).kind)
        // setPolygonMode(false) always means freehand, also coming from curve mode.
        tool.setPolygonMode(false)
        assertEquals(LassoKind.FREEHAND, tool.kind)
        assertEquals(LassoKind.FREEHAND, LassoTool(c).kind)

        // Freehand still selects by dragging around.
        c.deselect()
        c.pointerDown(ToolPoint(40f, 40f))
        for ((x, y) in listOf(160f to 40f, 160f to 160f, 40f to 160f)) c.pointerMove(ToolPoint(x, y))
        c.pointerUp(ToolPoint(40f, 45f))
        awaitSelection(c, tool)
        assertEquals(255, c.selection!!.alphaAt(100, 100))
    }

    @Test
    fun switchingToolsClosesACurveOfThreeOrMorePointsAndDropsAShorterOne() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f)
        c.selectTool(ToolId.BRUSH)
        assertFalse(tool.hasPendingWork)
        assertNull("two points make no selection", c.selection)
        c.selectTool(ToolId.LASSO)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(100f, 150f)
        c.selectTool(ToolId.BRUSH)
        assertFalse(tool.hasPendingWork)
        awaitSelection(c, tool)
        assertNotNull(c.selection)
        assertEquals(255, c.selection!!.alphaAt(100, 80))
    }
}
