package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 10 (R12 d, e; D31; I22): the node editing of a text path through the Text tool as
 * a finger and the node bar use it (the view is the identity). The path sheet's three entries
 * turn the guide into nodes and start the editing with the toast TextLabels19.NODES_STARTED (the
 * conversion is no step); every finished node edit (Add point, Delete point, Sharp corner, a node
 * dragged) is ONE in-tool step that Undo and Redo go through first, Add points included (one undo
 * takes all its points back); a finger picks the nearest node within 24 dp; ✓ (TextLabels19.
 * DONE_POINTS) ends the editing and returns to the editor's path settings with the nodes kept,
 * and the next ✓ commits the text as ONE document step. The 201st point is refused with
 * TextLabels19.NODES_MAX.
 */
@RunWith(RobolectricTestRunner::class)
class TextPathNodeEditRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val black = 0xFF000000.toInt()

    // ------------------------------------------------------------------ helpers

    private fun newController(w: Int = 600, h: Int = 400): Pair<EditorController, TextTool> {
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun tap(c: EditorController, at: Vec2) {
        c.pointerDown(ToolPoint(at.x, at.y))
        c.pointerUp(ToolPoint(at.x, at.y))
    }

    /** A finger from [from] to [to] in [steps] moves, then lifted. */
    private fun drag(c: EditorController, from: Vec2, to: Vec2, steps: Int = 4, beforeUp: () -> Unit = {}) {
        c.pointerDown(ToolPoint(from.x, from.y))
        for (k in 1..steps) {
            val p = from + (to - from) * (k.toFloat() / steps)
            c.pointerMove(ToolPoint(p.x, p.y))
        }
        beforeUp()
        c.pointerUp(ToolPoint(to.x, to.y))
    }

    /** A new text at the canvas center along a path of [type], its editor open (as the path sheet shows it). */
    private fun pathText(c: EditorController, tool: TextTool, type: TextPathType): TextPathSpec {
        tap(c, Vec2(300f, 200f))
        assertTrue("the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText("Wavy text here")
        tool.updateSpec { it.copy(sizePx = 24f, color = black) }
        tool.setPath(tool.item!!.path.copy(type = type))
        val path = tool.item!!.path
        assertEquals(type, path.type)
        assertNull("a shape of its own, no nodes yet", path.nodes)
        return path
    }

    private fun nodes(tool: TextTool): VSpline = TextPathNodes.of(tool.item!!.path) ?: throw AssertionError("the guide has no nodes")

    private fun at(tool: TextTool, i: Int): Vec2 = nodes(tool).points[i].let { Vec2(it.x, it.y) }

    /** 24 dp in document px at the current view. */
    private fun pickTolerance(c: EditorController): Float = c.viewTransform.let { it.screenToDocLength(it.dp(24f)) }

    // ------------------------------------------------------------------ the sheet's entries

    @Test
    fun theSheetsEntriesTurnTheGuideIntoNodesAndStartTheEditing() {
        val (c, tool) = newController()

        // Edit as path: the curve's 4 control points, the editor closed, the toast, no step yet.
        val curve = pathText(c, tool, TextPathType.CURVE)
        c.message = null
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_PATH))
        assertFalse("the entry closes the sheet", tool.editorOpen)
        assertTrue(tool.nodeEditor.active)
        assertSame("Undo and Redo go through the node editing first", tool.nodeEditor, c.inToolHistory)
        assertEquals(TextLabels19.NODES_STARTED, c.message)
        assertEquals(TextPathNodes.asPath(curve), nodes(tool))
        assertFalse(TextPathNodes.isPolyline(nodes(tool)))
        assertEquals(4, tool.nodeEditor.count)
        assertEquals("the same curve: the text stays", TextPathType.CURVE, tool.item!!.path.type)
        assertFalse("the conversion is no step", tool.nodeEditor.canUndo)
        assertEquals(-1, tool.nodeEditor.selected)
        tool.discard()
        assertFalse("the editing ends with the text", tool.nodeEditor.active)
        assertNull(c.inToolHistory)

        // Edit as polyline: a polyline of the curve.
        pathText(c, tool, TextPathType.CURVE)
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_POLYLINE))
        assertTrue(TextPathNodes.isPolyline(nodes(tool)))
        assertEquals(TextPathNodes.CUBIC_POLYLINE_SEGMENTS + 1, tool.nodeEditor.count)
        tool.discard()

        // Add points: the path nodes, then a node in the middle of every segment, as ONE step.
        val circle = pathText(c, tool, TextPathType.CIRCLE)
        assertTrue(tool.startNodes(TextPathNodeEntry.ADD_POINTS))
        val base = TextPathNodes.asPath(circle)!!
        assertEquals(2 * base.points.size, tool.nodeEditor.count)
        assertTrue(tool.nodeEditor.canUndo)
        assertEquals(TextLabels19.ADD_POINTS, tool.nodeEditor.undoLabel)
        assertEquals("Undo: " + TextLabels19.ADD_POINTS, HistoryLabels.undo(c))
        val steps = c.undoManager.undoCount
        assertEquals("Undo: " + TextLabels19.ADD_POINTS, HistoryLabels.performUndo(c))
        assertEquals("one undo takes every added point back", base.points.size, tool.nodeEditor.count)
        assertTrue("the editing goes on", tool.nodeEditor.active)
        assertEquals("no document step", steps, c.undoManager.undoCount)
        c.redo()
        assertEquals(2 * base.points.size, tool.nodeEditor.count)
        tool.discard()

        // Straight text has no guide: nothing starts.
        tap(c, Vec2(300f, 200f))
        tool.setText("Plain")
        c.message = null
        assertFalse(tool.startNodes(TextPathNodeEntry.EDIT_AS_PATH))
        assertFalse(tool.nodeEditor.active)
        assertNull(c.message)
        assertNull(c.inToolHistory)
        tool.discard()
    }

    // ------------------------------------------------------------------ one step per edit

    @Test
    fun everyNodeEditIsOneInToolStepThatUndoAndRedoGoThrough() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.CURVE)
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_PATH))
        val ed = tool.nodeEditor
        val start = tool.item!!.path
        val docSteps = c.undoManager.undoCount
        val specs = ArrayList<TextPathSpec>().apply { add(start) }

        // Add point with none selected: before the end, selected.
        ed.addPoint()
        assertEquals(5, ed.count)
        assertEquals(3, ed.selected)
        assertEquals(TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)
        specs += tool.item!!.path

        // Delete point: the selected node goes, nothing is selected.
        ed.deletePoint()
        assertEquals(4, ed.count)
        assertEquals(-1, ed.selected)
        assertEquals(TextPathNodeEditor.DELETE_POINT_STEP, ed.undoLabel)
        specs += tool.item!!.path

        // A tap on node 1 selects it; Sharp corner makes it a corner.
        tap(c, at(tool, 1))
        assertEquals(1, ed.selected)
        assertEquals("a tap is no step", tool.item!!.path, specs.last())
        assertTrue(ed.canSharp)
        ed.toggleSharp()
        assertTrue(ed.selectedSharp)
        assertEquals(TextPathNodeEditor.SHARP_CORNER_STEP, ed.undoLabel)
        specs += tool.item!!.path

        // A node dragged: the text re-flows while the finger moves, one step when it lifts.
        val from = at(tool, 2)
        val to = from + Vec2(40f, 30f)
        var live: TextPathSpec? = null
        drag(c, from, to) { live = tool.item!!.path }
        assertNotEquals("the guide follows the finger before it lifts", specs.last(), live)
        assertEquals(to.x, at(tool, 2).x, 0.01f)
        assertEquals(to.y, at(tool, 2).y, 0.01f)
        assertEquals(TextLabels19.MOVE_POINT, ed.undoLabel)
        specs += tool.item!!.path
        assertEquals("the node edits are no document steps", docSteps, c.undoManager.undoCount)

        // Undo takes them back one at a time, newest first; then Redo brings them back.
        for (k in specs.size - 2 downTo 0) {
            c.undo()
            assertEquals("undo to step $k", specs[k], tool.item!!.path)
            assertTrue(ed.active)
        }
        assertFalse(ed.canUndo)
        assertEquals(docSteps, c.undoManager.undoCount)
        for (k in 1 until specs.size) {
            c.redo()
            assertEquals("redo to step $k", specs[k], tool.item!!.path)
        }
        assertFalse(ed.canRedo)

        // A new edit after an undo drops what Redo could bring back.
        c.undo()
        tap(c, at(tool, 0))
        assertEquals(0, ed.selected)
        ed.addPoint()
        assertFalse(ed.canRedo)
        assertEquals(TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)

        // At the bottom of the node steps, Undo does what v1.8 did: the new pending text goes.
        while (ed.canUndo) c.undo()
        c.undo()
        assertNull(tool.item)
        assertFalse(ed.active)
        assertNull(c.inToolHistory)
        assertEquals(docSteps, c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ picking

    @Test
    fun aFingerPicksTheNearestNodeWithin24Dp() {
        val (c, tool) = newController()
        c.viewTransform.density = 2f
        val line = pathText(c, tool, TextPathType.LINE)
        // A line 400 px long: its ends lie far more than 24 dp apart.
        tool.setPath(line.copy(x1 = 100f, y1 = 200f, x2 = 500f, y2 = 200f))
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_POLYLINE))
        val ed = tool.nodeEditor
        assertEquals(2, ed.count)
        val tol = pickTolerance(c)
        assertEquals(48f, tol, 0.001f)
        val p0 = at(tool, 0)
        val p1 = at(tool, 1)
        assertTrue("the ends are far apart", p0.distanceTo(p1) > 4f * tol)
        val path = tool.item!!.path

        tap(c, p1 + Vec2(0f, 0.9f * tol))
        assertEquals("within 24 dp of the end", 1, ed.selected)
        tap(c, p0 + Vec2(0.5f * tol, 0f))
        assertEquals("the nearer node", 0, ed.selected)
        tap(c, p1 + Vec2(0f, 1.1f * tol))
        assertEquals("farther than 24 dp: nothing", -1, ed.selected)

        // A drag away from the nodes moves nothing (and is no step).
        drag(c, p1 + Vec2(0f, 3f * tol), p1 + Vec2(60f, 3f * tol))
        assertEquals(path, tool.item!!.path)
        assertFalse(ed.canUndo)
        assertTrue(ed.active)

        // A new node halfway: a finger near it picks it, not an end.
        ed.addPoint()
        val mid = at(tool, 1)
        val sel = ed.selected
        assertEquals(1, sel)
        tap(c, Vec2(-1000f, -1000f))
        assertEquals(-1, ed.selected)
        c.pointerDown(ToolPoint(mid.x + 0.4f * tol, mid.y))
        c.pointerUp(ToolPoint(mid.x + 0.4f * tol, mid.y))
        assertEquals(1, ed.selected)
        tool.discard()
    }

    // ------------------------------------------------------------------ done, ✓, the cap

    @Test
    fun doneReturnsToTheSheetAndTheNextCheckCommitsOneStep() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.CURVE)
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_PATH))
        val ed = tool.nodeEditor
        ed.addPoint()
        drag(c, at(tool, 1), at(tool, 1) + Vec2(-30f, 50f))
        val edited = tool.item!!.path
        val steps = c.undoManager.undoCount

        // ✓ while the nodes are edited: done editing points, back to the editor, nodes kept.
        tool.commit()
        assertFalse(ed.active)
        assertNull(c.inToolHistory)
        assertTrue("back to the path settings", tool.editorOpen)
        assertEquals(edited, tool.item!!.path)
        assertEquals("no step yet", steps, c.undoManager.undoCount)

        // The node bar's ✓ does the same.
        tool.confirmEditor()
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_PATH))
        assertEquals("the nodes are kept, not converted again", edited, tool.item!!.path)
        tool.doneNodes()
        assertFalse(ed.active)
        assertTrue(tool.editorOpen)

        // The next ✓ commits the text, nodes and all, as ONE step.
        tool.confirmEditor()
        tool.commit()
        assertNull(tool.item)
        assertEquals(steps + 1, c.undoManager.undoCount)
        val stored = TextCodec.decodeAll(c.activeLayer.textData).single()
        assertEquals(TextPathNodes.of(edited), TextPathNodes.of(stored.path))
        c.undo()
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun theTwoHundredAndFirstPointIsRefused() {
        val (c, tool) = newController()
        pathText(c, tool, TextPathType.CURVE)
        val many = VSpline(List(TextPathSpec.MAX_NODES - 1) { VSplinePoint(50f + it * 2.5f, 200f + (it % 2) * 10f) }, order = 2)
        tool.setPath(TextPathNodes.withNodes(tool.item!!.path, many))
        assertTrue(tool.startNodes(TextPathNodeEntry.EDIT_AS_POLYLINE))
        val ed = tool.nodeEditor
        assertEquals(TextPathSpec.MAX_NODES - 1, ed.count)
        ed.addPoint()
        assertEquals(TextPathSpec.MAX_NODES, ed.count)
        c.message = null
        ed.addPoint()
        assertEquals(TextLabels19.NODES_MAX, c.message)
        assertEquals(TextPathSpec.MAX_NODES, ed.count)
        c.message = null
        ed.addPoints()
        assertEquals(TextLabels19.NODES_MAX, c.message)
        assertEquals(TextPathSpec.MAX_NODES, ed.count)
        assertEquals("only the 200th point was a step", TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)
        c.undo()
        assertFalse(ed.canUndo)

        // Leaving the tool commits the text and ends the editing.
        c.selectTool(ToolId.BRUSH)
        assertFalse(ed.active)
        assertNull(c.inToolHistory)
    }
}
