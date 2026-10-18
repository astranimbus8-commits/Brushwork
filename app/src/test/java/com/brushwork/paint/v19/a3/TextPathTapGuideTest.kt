package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextPathNodeEntry
import com.brushwork.paint.tools.text.TextPathNodes
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 13 (R12, the cuttable extra): while a text path's nodes are edited, a tap on the
 * guide off any node adds a node there ([TextPathNodeEditor.tapGuide], [TextPathNodes.pointOnGuide]),
 * selected, as ONE in-tool step named "Add point" that Undo takes back (no document step):
 * - on a polyline exactly at the tap (the guide keeps its shape), on the closing segment of a
 *   closed guide between its last and first node;
 * - on path nodes, on the line between two nodes where it is tapped, or, for a tap on the curve,
 *   at the point of the line nearest to it (the curve moves closer, as with "Add points");
 * - a tap within 24 dp of a node picks it, a tap off the guide adds nothing, a drag adds nothing,
 *   nor does the first finger of a two-finger gesture (the node is added when the finger lifts);
 * - at 200 points the tap is refused with TextLabels19.NODES_MAX.
 */
@RunWith(RobolectricTestRunner::class)
class TextPathTapGuideTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val black = 0xFF000000.toInt()

    private fun newController(w: Int = 600, h: Int = 400): Pair<EditorController, TextTool> {
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.viewTransform.density = 1f
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, at: Vec2) {
        c.pointerDown(ToolPoint(at.x, at.y))
        c.pointerUp(ToolPoint(at.x, at.y))
    }

    /** A new text at the canvas center whose path is [path] (with [type]), its editor open. */
    private fun pathText(c: EditorController, tool: TextTool, type: TextPathType, path: (TextPathSpec) -> TextPathSpec = { it }) {
        tap(c, Vec2(300f, 200f))
        assertTrue(tool.editorOpen && tool.editingNew)
        tool.setText("Tap the guide")
        tool.updateSpec { it.copy(sizePx = 24f, color = black) }
        // A new type takes its default shape where the text is; the same type keeps what is set.
        tool.setPath(tool.item!!.path.copy(type = type))
        tool.setPath(path(tool.item!!.path))
    }

    private fun nodes(tool: TextTool): VSpline = TextPathNodes.of(tool.item!!.path) ?: throw AssertionError("the guide has no nodes")

    private fun at(tool: TextTool, i: Int): Vec2 = nodes(tool).points[i].let { Vec2(it.x, it.y) }

    private fun near(what: String, expected: Vec2, actual: Vec2, tol: Float = 1e-3f) =
        assertTrue("$what: $expected, was $actual", expected.distanceTo(actual) <= tol)

    /** 24 dp in document px at the current view. */
    private fun pickTolerance(c: EditorController): Float = c.viewTransform.let { it.screenToDocLength(it.dp(24f)) }

    /** The distance from [p] to the guide of [n] (its sampled line). */
    private fun distanceToGuide(n: VSpline, p: Vec2): Float {
        val g = TextPathNodes.guide(n)
        var best = Float.MAX_VALUE
        for (i in 0 until g.vertexCount - 1) best = minOf(best, Geometry.projectOnSegment(p, g.vertex(i), g.vertex(i + 1)).distanceTo(p))
        return best
    }

    @Test
    fun aTapOnAPolylineAddsANodeThereAsOneStep() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.LINE) { it.copy(x1 = 100f, y1 = 200f, x2 = 500f, y2 = 200f) }
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_POLYLINE))
        val ed = tool.nodeEditor
        assertEquals(2, ed.count)
        val tol = pickTolerance(c)
        assertEquals("24 dp at density 1", 24f, tol, 1e-3f)
        val before = tool.item!!.path
        val length = TextPathNodes.guide(nodes(tool)).length
        val docSteps = c.undoManager.undoCount

        // A tap half the tolerance off the line, far from both ends: a node on the line there.
        tap(c, Vec2(300f, 200f + 0.5f * tol))
        assertEquals(3, ed.count)
        near("the new node", Vec2(300f, 200f), at(tool, 1))
        assertEquals("selected", 1, ed.selected)
        assertEquals(TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)
        assertEquals("Undo: " + TextPathNodeEditor.ADD_POINT_STEP, HistoryLabels.undo(c))
        assertEquals("the guide keeps its shape", length, TextPathNodes.guide(nodes(tool)).length, 1e-3)
        near("an end stays", Vec2(100f, 200f), at(tool, 0))
        near("an end stays", Vec2(500f, 200f), at(tool, 2))
        val one = tool.item!!.path

        // Off the guide: nothing is added and nothing is selected.
        tap(c, Vec2(400f, 200f + 1.5f * tol))
        assertEquals(3, ed.count)
        assertEquals(-1, ed.selected)
        assertEquals(one, tool.item!!.path)
        // Within 24 dp of a node: the tap picks it.
        tap(c, Vec2(300f + 0.4f * tol, 200f))
        assertEquals(1, ed.selected)
        assertEquals(3, ed.count)
        // A drag along the line adds nothing (and is no step).
        c.pointerDown(ToolPoint(420f, 200f))
        for (k in 1..4) c.pointerMove(ToolPoint(420f + 12f * k, 200f))
        c.pointerUp(ToolPoint(468f, 200f))
        assertEquals(one, tool.item!!.path)
        // The first finger of a two-finger gesture adds nothing.
        c.pointerDown(ToolPoint(420f, 200f))
        c.pointerCancel()
        assertEquals(one, tool.item!!.path)
        assertEquals(TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)

        // Another tap between the first end and the new node: it goes between them.
        tap(c, Vec2(200f, 200f))
        assertEquals(4, ed.count)
        near("between", Vec2(200f, 200f), at(tool, 1))
        assertEquals(1, ed.selected)
        assertEquals("no document step", docSteps, c.undoManager.undoCount)

        // Undo takes them back one at a time; Redo brings them back.
        c.undo()
        assertEquals(one, tool.item!!.path)
        c.undo()
        assertEquals(before, tool.item!!.path)
        assertFalse(ed.canUndo)
        c.redo()
        assertEquals(one, tool.item!!.path)
        assertTrue(ed.active)
        tool.discard()
    }

    @Test
    fun aClosedGuideTakesANodeOnItsClosingSegment() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.CURVE)
        val square = VSpline(listOf(VSplinePoint(100f, 100f), VSplinePoint(300f, 100f), VSplinePoint(300f, 300f), VSplinePoint(100f, 300f)), order = 2, cyclic = true)
        tool.setPath(TextPathNodes.withNodes(tool.item!!.path, square))
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_POLYLINE))
        val ed = tool.nodeEditor
        assertEquals(4, ed.count)
        assertTrue(TextPathNodes.isClosed(nodes(tool)))
        // The closing segment runs from the last node back to the first.
        tap(c, Vec2(100f, 200f))
        assertEquals(5, ed.count)
        near("on the closing segment, after the last node", Vec2(100f, 200f), at(tool, 4))
        assertEquals(4, ed.selected)
        assertTrue(TextPathNodes.isClosed(nodes(tool)))
        tap(c, Vec2(200f, 100f))
        assertEquals(6, ed.count)
        near("on the first segment", Vec2(200f, 100f), at(tool, 1))
        assertEquals(listOf(100f to 100f, 200f to 100f, 300f to 100f, 300f to 300f, 100f to 300f, 100f to 200f), nodes(tool).points.map { it.x to it.y })
        tool.discard()
    }

    @Test
    fun pathNodesTakeANodeOnTheirLinesOrForTheCurve() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.CURVE) {
            it.copy(x1 = 100f, y1 = 300f, cx1 = 200f, cy1 = 50f, cx2 = 400f, cy2 = 50f, x2 = 500f, y2 = 300f)
        }
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_PATH))
        val ed = tool.nodeEditor
        val start = nodes(tool)
        assertFalse(TextPathNodes.isPolyline(start))
        assertEquals("the curve's control points", listOf(100f to 300f, 200f to 50f, 400f to 50f, 500f to 300f), start.points.map { it.x to it.y })
        val tol = pickTolerance(c)

        // On the line between the two middle nodes (the editor draws it): a node there.
        tap(c, Vec2(250f, 50f + 0.3f * tol))
        assertEquals(5, ed.count)
        near("on the line", Vec2(250f, 50f), at(tool, 2))
        assertEquals(2, ed.selected)
        c.undo()
        assertEquals(start, nodes(tool))

        // On the curve, far from the lines and the nodes: its middle is (300, 112.5).
        val onCurve = Vec2(300f, 112.5f)
        assertTrue("the tap is on the guide", distanceToGuide(start, onCurve) < 0.5f)
        assertTrue("away from the lines", 112.5f - 50f > tol)
        tap(c, onCurve)
        assertEquals(5, ed.count)
        val added = at(tool, 2)
        near("on the line nearest to the tapped point of the curve", Vec2(300f, 50f), added, tol = 1f)
        assertEquals(2, ed.selected)
        assertTrue("the curve moves closer to the new node", distanceToGuide(nodes(tool), added) < distanceToGuide(start, added))
        assertEquals(TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)
        tool.discard()
    }

    @Test
    fun theTwoHundredAndFirstTapIsRefused() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.CURVE)
        // 199 nodes close together, then one far away: a long last segment to tap.
        val pts = List(TextPathSpec.MAX_NODES - 1) { VSplinePoint(50f + it, 200f + (it % 2) * 3f) } + VSplinePoint(550f, 200f)
        tool.setPath(TextPathNodes.withNodes(tool.item!!.path, VSpline(pts, order = 2)))
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_POLYLINE))
        val ed = tool.nodeEditor
        assertEquals(TextPathSpec.MAX_NODES, ed.count)
        val path = tool.item!!.path
        c.message = null
        tap(c, Vec2(450f, 200f))
        assertEquals(TextLabels19.NODES_MAX, c.message)
        assertEquals(TextPathSpec.MAX_NODES, ed.count)
        assertEquals(path, tool.item!!.path)
        assertFalse("no step", ed.canUndo)
        assertNull(ed.undoLabel)
        tool.discard()
    }
}
