package com.brushwork.paint.tools.select

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
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * "Snap to objects" of the selection tools: curve lasso points (the user's "curve select"),
 * polygon lasso corners and marquee corners snap to other layers' content, the canvas, drawn
 * lines and the other points, so a selection can be made exactly to a layer or a table cell.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionSnapRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /** The layer content every test snaps to (lines x 100 / 130 / 160, y 60 / 90 / 120). */
    private val content = Rect(100, 60, 160, 120)

    /** A 400 x 300 canvas at zoom 1, density 1 (snap distance 8 px); "Layer 1" holds [content]. */
    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        Canvas(doc.layers[0].bitmap).drawRect(content, Paint().apply { color = 0xFF000000.toInt() })
        doc.layers[0].markChanged()
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun lasso(c: EditorController, kind: LassoKind): LassoTool {
        c.selectTool(ToolId.LASSO)
        return (c.tools.getValue(ToolId.LASSO) as LassoTool).also { it.setKind(kind) }
    }

    private fun marquee(c: EditorController): MarqueeTool {
        c.selectTool(ToolId.MARQUEE)
        return c.tools.getValue(ToolId.MARQUEE) as MarqueeTool
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y)); pointerUp(ToolPoint(x, y))
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** Waits until the background rasterization published a selection and [busy] is false. */
    private fun awaitSelection(c: EditorController, busy: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while ((c.selection == null || busy()) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertTrue("the selection was applied", c.selection != null && !busy())
    }

    // ------------------------------------------------------------------ curve lasso

    @Test
    fun curveLassoPointsSnapAndTheSelectionEdgesLandExactlyOnTheLines() {
        val c = setup()
        val tool = lasso(c, LassoKind.CURVE)
        // Tapped a few px off the layer's corners: every point lands exactly on them.
        c.tap(103f, 63f); c.tap(157f, 58f); c.tap(162f, 117f); c.tap(98f, 122f)
        assertEquals(
            listOf(Vec2(100f, 60f), Vec2(160f, 60f), Vec2(160f, 120f), Vec2(100f, 120f)),
            tool.curve.anchors.map { it.pos },
        )
        assertTrue("no guide after a tap", tool.activeGuides.isEmpty())
        // A dragged point snaps once it moves (here to the canvas center's height) and shows a guide.
        c.pointerDown(ToolPoint(160f, 120f))
        c.pointerMove(ToolPoint(170f, 135f))
        c.pointerMove(ToolPoint(175f, 146f))
        assertEquals(Vec2(175f, 150f), tool.curve.anchors[2].pos)
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.Y && it.pos == 150f && it.label == "Canvas center" })
        // Back near the corner: it lands on the layer's corner and the other points' lines again.
        c.pointerMove(ToolPoint(163f, 118f))
        c.pointerUp(ToolPoint(163f, 118f))
        assertEquals(Vec2(160f, 120f), tool.curve.anchors[2].pos)
        assertTrue("guides are hidden when the finger lifts", tool.activeGuides.isEmpty())
        // Corners: the outline is the layer's rectangle, the selection exactly its content.
        for (i in 0 until 4) tool.curve.setSharp(i, true)
        c.tap(101f, 61f) // the first point closes the outline
        awaitSelection(c) { tool.busy }
        assertEquals(content, c.selection!!.bounds)
        assertEquals(255, c.selection!!.alphaAt(100, 60))
        assertEquals(0, c.selection!!.alphaAt(99, 60))
        assertEquals(0, c.selection!!.alphaAt(160, 119))
    }

    @Test
    fun curveLassoTapOnAPointNeverMovesItAndCancelLeavesNoTrace() {
        val c = setup()
        val tool = lasso(c, LassoKind.CURVE)
        c.snapping.enabled = false
        c.tap(104f, 63f); c.tap(250f, 200f)
        c.snapping.enabled = true
        // A tap on the point 4 px off the layer's edge selects it, it doesn't snap it.
        c.tap(104f, 63f)
        assertEquals(Vec2(104f, 63f), tool.curve.anchors[0].pos)
        assertEquals(0, tool.curve.selected)
        // A drag cancelled by a second finger: back where it was, no guide left.
        c.pointerDown(ToolPoint(104f, 63f))
        c.pointerMove(ToolPoint(120f, 80f))
        c.pointerMove(ToolPoint(133f, 92f))
        assertEquals(Vec2(130f, 90f), tool.curve.anchors[0].pos)
        assertFalse(tool.activeGuides.isEmpty())
        c.pointerCancel()
        assertEquals(Vec2(104f, 63f), tool.curve.anchors[0].pos)
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun curveLassoSnapsToTableLinesDrawnInALayer() {
        val c = setup()
        // Lines found in layer pixels (the Table filter's) come from the LineDetector; here a
        // layer offers them directly, the same kind of target.
        val table = c.doc.layers[0]
        c.snapping.addLayerFeatures { l ->
            if (l === table) listOf(SnapLine.drawn(SnapAxis.X, 250f, 0f, 300f, l.name), SnapLine.drawn(SnapAxis.Y, 200f, 0f, 400f, l.name)) else emptyList()
        }
        val tool = lasso(c, LassoKind.CURVE)
        c.tap(253f, 104f)
        assertEquals(Vec2(250f, 104f), tool.curve.anchors[0].pos)
        // A new point dragged before it is placed: it snaps to the horizontal table line.
        c.pointerDown(ToolPoint(330f, 260f))
        c.pointerMove(ToolPoint(325f, 230f))
        c.pointerMove(ToolPoint(320f, 204f))
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.Y && it.source == SnapSource.LINE && it.label == "Layer 1 line" })
        c.pointerUp(ToolPoint(320f, 204f))
        assertEquals(Vec2(320f, 200f), tool.curve.anchors[1].pos)
        assertTrue(tool.activeGuides.isEmpty())
    }

    // ------------------------------------------------------------------ polygon lasso

    @Test
    fun polygonCornersSnapNewAndDragged() {
        val c = setup()
        val tool = lasso(c, LassoKind.POLYGON)
        c.tap(103f, 63f); c.tap(157f, 58f); c.tap(162f, 117f)
        assertEquals(100f to 60f, tool.corner(0))
        assertEquals(160f to 60f, tool.corner(1))
        assertEquals(160f to 120f, tool.corner(2))
        // A new corner lines up with the first one (x) and the layer's bottom (y).
        c.tap(98f, 300f - 178f)
        assertEquals(100f to 120f, tool.corner(3))
        // Dragging a corner: snapped once it moved (layer center x, canvas center y), with guides.
        c.pointerDown(ToolPoint(160f, 120f))
        c.pointerMove(ToolPoint(150f, 140f))
        c.pointerMove(ToolPoint(131f, 146f))
        assertEquals(130f to 150f, tool.corner(2))
        assertEquals(2, tool.activeGuides.size)
        c.pointerUp(ToolPoint(131f, 146f))
        assertEquals(130f to 150f, tool.corner(2))
        assertTrue(tool.activeGuides.isEmpty())
        // A tap on a corner doesn't move it.
        c.tap(132f, 151f)
        assertEquals(130f to 150f, tool.corner(2))
        tool.discard()
        assertEquals(0, tool.vertexCount)
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun aPolygonCornerCancelledBySecondFingerLeavesNoTrace() {
        val c = setup()
        val tool = lasso(c, LassoKind.POLYGON)
        c.tap(103f, 63f); c.tap(157f, 58f)
        assertEquals(2, tool.vertexCount)
        // A new corner near the canvas center: it snaps there as the finger lands (guides shown)...
        c.pointerDown(ToolPoint(197f, 148f))
        assertTrue(tool.activeGuides.any { it.label == "Canvas center" })
        c.pointerMove(ToolPoint(199f, 149f))
        // ...and a second finger (pinch / two-finger undo) cancels it: no corner, no guides.
        c.pointerCancel()
        assertEquals(2, tool.vertexCount)
        assertTrue(tool.activeGuides.isEmpty())
        // A dragged corner cancelled: back where it was, no guides, its history step dropped.
        c.pointerDown(ToolPoint(157f, 58f))
        c.pointerMove(ToolPoint(170f, 80f))
        c.pointerMove(ToolPoint(197f, 148f))
        assertEquals(200f to 150f, tool.corner(1))
        c.pointerCancel()
        assertEquals(160f to 60f, tool.corner(1))
        assertTrue(tool.activeGuides.isEmpty())
        assertTrue(tool.undoStep())
        assertEquals("the undo took back the corner placed before, not the cancelled drag", 1, tool.vertexCount)
    }

    @Test
    fun polygonCornersFollowTheFingerWithSnappingOff() {
        val c = setup()
        c.snapping.enabled = false
        val tool = lasso(c, LassoKind.POLYGON)
        c.tap(103f, 63f); c.tap(157f, 58f)
        assertEquals(103f to 63f, tool.corner(0))
        assertEquals(157f to 58f, tool.corner(1))
        c.drag(157f to 58f, 140f to 80f, 131f to 92f)
        assertEquals(131f to 92f, tool.corner(1))
        assertTrue(tool.activeGuides.isEmpty())
    }

    // ------------------------------------------------------------------ marquee

    @Test
    fun marqueeCornersSnapToALayersBoundsSoTheSelectionEqualsThem() {
        val c = setup()
        val tool = marquee(c)
        c.pointerDown(ToolPoint(103f, 57f))
        c.pointerMove(ToolPoint(130f, 90f))
        c.pointerMove(ToolPoint(158f, 123f))
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 160f })
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 100f })
        c.pointerUp(ToolPoint(158f, 123f))
        assertTrue(tool.activeGuides.isEmpty())
        awaitSelection(c) { tool.busy }
        assertEquals(content, c.selection!!.bounds)
    }

    @Test
    fun marqueeFromCenterSquareKeepsItsConstraintsWhileSnapping() {
        val c = setup()
        val tool = marquee(c)
        tool.settings = tool.settings.copy(square = true, fromCenter = true)
        // The center snaps to the layer's center; the dragged side to its right edge, the
        // square follows.
        c.drag(128f to 92f, 140f to 100f, 158f to 110f)
        awaitSelection(c) { tool.busy }
        assertEquals(content, c.selection!!.bounds)
    }

    @Test
    fun marqueeIsExactlyAsBeforeWithSnappingOffAndATapStillDeselects() {
        val c = setup()
        c.snapping.enabled = false
        val tool = marquee(c)
        c.drag(103f to 57f, 130f to 90f, 158f to 123f)
        awaitSelection(c) { tool.busy }
        assertEquals(Rect(103, 57, 158, 123), c.selection!!.bounds)
        // Snapping on: a tap 3 px from a line is still a tap (deselects), not a tiny selection.
        c.snapping.enabled = true
        c.tap(197f, 147f)
        assertNull(c.selection)
        assertTrue(tool.activeGuides.isEmpty())
        // A second finger cancels the drag: nothing selected, no guides.
        c.pointerDown(ToolPoint(103f, 57f))
        c.pointerMove(ToolPoint(158f, 123f))
        c.pointerCancel()
        assertTrue(tool.activeGuides.isEmpty())
        assertNull(c.selection)
    }

    @Test
    fun aDragWhoseCornersSnapOntoOneLineKeepsTheSelection() {
        val c = setup()
        val tool = marquee(c)
        c.drag(250f to 200f, 280f to 230f, 320f to 260f)
        awaitSelection(c) { tool.busy }
        val before = c.selection!!
        // Down 3 px right of the layer's left edge, dragged down along it: both corners snap to
        // x = 100, the rectangle has no width. That is no tap: the selection must stay.
        c.pointerDown(ToolPoint(103f, 40f))
        c.pointerMove(ToolPoint(104f, 120f))
        c.pointerMove(ToolPoint(105f, 200f))
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 100f })
        c.pointerUp(ToolPoint(105f, 200f))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(tool.activeGuides.isEmpty())
        assertFalse(tool.busy)
        assertTrue("the selection was kept", c.selection === before)
        // The same drag with snapping off selects the 2 px wide strip under the finger, as before.
        c.snapping.enabled = false
        c.drag(103f to 40f, 104f to 120f, 105f to 200f)
        awaitSelection(c) { tool.busy }
        assertEquals(Rect(103, 40, 105, 200), c.selection!!.bounds)
    }

    @Test
    fun theOldSelectionIsATargetOnlyWhenAddingToIt() {
        val c = setup()
        val tool = marquee(c)
        c.drag(250f to 200f, 280f to 230f, 320f to 260f)
        awaitSelection(c) { tool.busy }
        assertEquals(Rect(250, 200, 320, 260), c.selection!!.bounds)
        // "New": the selection being replaced isn't a target (323 stays 323).
        c.pointerDown(ToolPoint(300f, 30f))
        c.pointerMove(ToolPoint(323f, 50f))
        assertFalse(tool.activeGuides.any { it.source == SnapSource.SELECTION })
        c.pointerCancel()
        // "Add": it is.
        tool.mode = SelectionMode.ADD
        c.pointerDown(ToolPoint(300f, 30f))
        c.pointerMove(ToolPoint(323f, 50f))
        assertTrue(tool.activeGuides.any { it.source == SnapSource.SELECTION && it.pos == 320f })
        c.pointerCancel()
    }
}
