package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * "Snap to objects" of the curve / polyline tools (the user's "when selecting a curve point ...
 * snap to objects"): new, inserted and dragged anchors and tangent handle ends snap to other
 * layers, the canvas and the path's other points; taps never move a point; off = as before.
 */
@RunWith(RobolectricTestRunner::class)
class CurveSnapRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /**
     * A 400 x 300 canvas at zoom 1, density 1 (snap distance 8 px, touch slop 6 px): "Layer 1" has
     * content at (100, 60)-(160, 120) (lines x 100 / 130 / 160, y 60 / 90 / 120), the canvas center
     * is (200, 150); the curve goes into the empty "Layer 2".
     */
    private fun setup(polyline: Boolean = false): Pair<EditorController, CurveTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        fill(doc.layers[0].bitmap, Rect(100, 60, 160, 120))
        doc.layers[0].markChanged()
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE
        c.selectTool(id)
        val tool = c.tools.getValue(id) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, fill = false, closed = false) }
        return c to tool
    }

    private fun fill(b: Bitmap, r: Rect) = Canvas(b).drawRect(r, Paint().apply { color = 0xFF000000.toInt() })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y)); pointerUp(ToolPoint(x, y))
        idle()
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
        idle()
    }

    private fun pos(tool: CurveTool, i: Int) = tool.anchors[i].pos

    @Test
    fun aDraggedAnchorSnapsToAnotherLayersLeftEdgeWithinReachAndNotBeyond() {
        val (c, tool) = setup()
        c.tap(40f, 200f); c.tap(300f, 250f)
        assertEquals(Vec2(40f, 200f), pos(tool, 0))
        // 5 px from the layer's left edge (x = 100): it lands exactly on it, with its guide.
        c.pointerDown(ToolPoint(40f, 200f))
        c.pointerMove(ToolPoint(60f, 205f))
        c.pointerMove(ToolPoint(105f, 210f))
        assertEquals(Vec2(100f, 210f), pos(tool, 0))
        val g = tool.activeGuides.single { it.axis == SnapAxis.X }
        assertEquals(100f, g.pos)
        assertEquals("Layer 1 left", g.label)
        // Farther than the snap distance (9 px) the finger alone decides again: the guide lets go.
        c.pointerMove(ToolPoint(109f, 210f))
        assertEquals(Vec2(109f, 210f), pos(tool, 0))
        assertTrue(tool.activeGuides.isEmpty())
        c.pointerMove(ToolPoint(106f, 211f))
        c.pointerUp(ToolPoint(106f, 211f))
        assertEquals(Vec2(100f, 211f), pos(tool, 0))
        assertTrue("guides are hidden when the finger lifts", tool.activeGuides.isEmpty())
        // 10 px away: not snapped.
        c.drag(100f to 211f, 120f to 215f, 110f to 212f)
        assertEquals(Vec2(110f, 212f), pos(tool, 0))
    }

    @Test
    fun aDraggedAnchorLinesUpWithAnotherAnchorOfThePath() {
        val (c, tool) = setup()
        c.tap(40f, 200f); c.tap(300f, 250f)
        c.pointerDown(ToolPoint(300f, 250f))
        c.pointerMove(ToolPoint(302f, 230f))
        c.pointerMove(ToolPoint(305f, 204f))
        assertEquals("same height as the first point", Vec2(305f, 200f), pos(tool, 1))
        val g = tool.activeGuides.single()
        assertEquals(SnapAxis.Y, g.axis)
        assertEquals(200f, g.pos)
        assertEquals(SnapSource.POINT, g.source)
        assertEquals("Point", g.label)
        // Within reach on both axes: it lands exactly on the other point.
        c.pointerMove(ToolPoint(44f, 197f))
        assertEquals(Vec2(40f, 200f), pos(tool, 1))
        c.pointerCancel()
        assertEquals("a cancelled drag leaves no trace", Vec2(300f, 250f), pos(tool, 1))
        assertTrue("and no guides", tool.activeGuides.isEmpty())
    }

    @Test
    fun aNewTappedAnchorSnapsAtOnceAndAnInsertedOneToo() {
        val (c, tool) = setup()
        // Near the canvas center.
        c.tap(196f, 147f)
        assertEquals(Vec2(200f, 150f), pos(tool, 0))
        assertTrue("no guide stays after the tap", tool.activeGuides.isEmpty())
        // Near the layer's right edge (x) and the first point's height (y).
        c.tap(163f, 154f)
        assertEquals(Vec2(160f, 150f), pos(tool, 1))
        tool.discard()
        // Inserted on the path: snapped too (to the layer's left edge).
        c.tap(40f, 200f); c.tap(300f, 252f)
        val onPath = Vec2(103f, 200f + 52f * 63f / 260f)
        c.tap(onPath.x, onPath.y)
        assertEquals(3, tool.anchors.size)
        assertEquals(100f, pos(tool, 1).x)
        assertEquals(onPath.y, pos(tool, 1).y, 1e-3f)
    }

    @Test
    fun aTapOnAnExistingPointNeverMovesIt() {
        val (c, tool) = setup()
        // Typed in (numeric entry is never snapped): 3 px from the layer's left edge.
        assertTrue(tool.addAnchor(Vec2(103f, 230f)))
        tool.deselect()
        c.tap(104f, 231f)
        assertEquals(Vec2(103f, 230f), pos(tool, 0))
        assertEquals("the tap selected it", 0, tool.selected)
        // A wiggle within the touch slop doesn't move it either.
        c.drag(103f to 230f, 106f to 232f, 104f to 231f)
        assertEquals(Vec2(103f, 230f), pos(tool, 0))
    }

    @Test
    fun withSnappingOffAnchorsLandUnderTheFingerOrOnTheGridAsBefore() {
        val (c, tool) = setup(polyline = true)
        c.snapping.enabled = false
        c.tap(196f, 147f)
        assertEquals(Vec2(196f, 147f), pos(tool, 0))
        c.drag(196f to 147f, 150f to 120f, 103f to 118f)
        assertEquals(Vec2(103f, 118f), pos(tool, 0))
        assertTrue(tool.activeGuides.isEmpty())
        // Grid snapping on: the old square-grid snap, exactly.
        c.updateGrid(GridSettings(enabled = true, type = GridType.SQUARE, spacingPx = 25f, snap = true))
        c.tap(212f, 263f)
        assertEquals(c.snapToGrid(Vec2(212f, 263f)), pos(tool, 1))
        assertEquals(Vec2(200f, 275f), pos(tool, 1))
        // Snapping on with the grid: objects first (the layer's right edge), the grid on the axes
        // that didn't snap.
        c.snapping.enabled = true
        c.tap(163f, 263f)
        assertEquals(Vec2(160f, 275f), pos(tool, 2))
    }

    @Test
    fun aTangentHandleEndSnapsToItsAnchorsHeightAndLetsGoBeyond() {
        val (c, tool) = setup()
        c.tap(40f, 200f); c.tap(150f, 240f); c.tap(300f, 150f)
        c.tap(150f, 240f)
        assertEquals(1, tool.selected)
        val a = pos(tool, 1)
        val end = a + tool.handlesOf(1).second
        assertTrue("the automatic tangent isn't level: $end", end.y < a.y - 5f)
        c.pointerDown(ToolPoint(end.x, end.y))
        c.pointerMove(ToolPoint(end.x + 10f, end.y + 2f))
        c.pointerMove(ToolPoint(215f, 236f))
        // 4 px above the anchor's height: a level tangent (its own anchor is a target).
        assertEquals(Vec2(65f, 0f), tool.anchors[1].handleOut)
        assertEquals(0f, tool.anchors[1].handleIn!!.y, 1e-4f)
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.Y && it.pos == 240f })
        // 15 px above: free again.
        c.pointerMove(ToolPoint(215f, 225f))
        c.pointerUp(ToolPoint(215f, 225f))
        assertEquals(Vec2(65f, -15f), tool.anchors[1].handleOut)
        assertTrue(tool.activeGuides.isEmpty())
        // Snapping off: the handle follows the finger exactly.
        c.snapping.enabled = false
        c.drag(215f to 225f, 220f to 230f, 215f to 237f)
        assertEquals(Vec2(65f, -3f), tool.anchors[1].handleOut)
    }

    @Test
    fun aCommittedSnappedCurveIsOneUndoStepAndDrawnThroughTheSnappedPoints() {
        val (c, tool) = setup(polyline = true)
        tool.update { it.copy(plainWidth = 2f) }
        c.tap(103f, 250f); c.tap(103f, 280f)
        assertEquals(Vec2(100f, 250f), pos(tool, 0))
        // The second point lines up with the first: an upright line at x = 100.
        assertEquals(Vec2(100f, 280f), pos(tool, 1))
        tool.commit()
        val layer = c.doc.layers[1]
        assertEquals(1, c.undoManager.undoCount)
        assertTrue("painted on x = 100", layer.bitmap.getPixel(99, 265) ushr 24 != 0 && layer.bitmap.getPixel(100, 265) ushr 24 != 0)
        assertEquals("nothing at x = 103", 0, layer.bitmap.getPixel(103, 265) ushr 24)
    }
}
