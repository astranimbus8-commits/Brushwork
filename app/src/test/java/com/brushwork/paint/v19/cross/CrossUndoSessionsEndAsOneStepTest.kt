package com.brushwork.paint.v19.cross

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.adjust.BrightnessContrastFilter
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextPathNodeEntry
import com.brushwork.paint.tools.text.TextPathNodes
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.v19.a1.A1Tools
import com.brushwork.paint.v19.a1.A1Tools.curveTool
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.v19.a9.A9Rig
import com.brushwork.paint.v19.cross.CrossUndoSnap.oneExactStep
import com.brushwork.paint.v19.cross.CrossUndoSnap.px
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorOps
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.Locale

/**
 * v1.9 Gate 2, lane text T3 (I22 undo walks; A2 x A3 x A1 x A9): sessions that keep steps of
 * their own END as exactly ONE document step:
 * - a filter session of several option changes, on a layer of several texts made by a merge
 *   (its ✓ makes the texts pixels in the same step);
 * - the node editing of a text path ("Add points", "Edit as path", "Edit as polyline", a node
 *   moved by a finger, a point added) on the merged layer's texts;
 * - the bevel popup on Polyline points and on the Shape tool's points, on a new line or shape
 *   and on one opened again (a polyline tapped, "Edit path"; a shape object, "Edit shape").
 * While a session is open Undo and Redo walk its own steps and the document history does not
 * move; after its ✓, one Undo gives back the document before the session exactly (data and
 * pixels) and one Redo the result exactly. The ✓ stores what the session's walk left: the
 * filter's values after its Undo and Redo, the nodes, the bevelled points.
 */
@RunWith(RobolectricTestRunner::class)
class CrossUndoSessionsEndAsOneStepTest {
    private val kit = A4Kit(320, 240)
    private val rig = A9Rig()

    @After
    fun tearDown() {
        rig.close()
        kit.close()
    }

    private fun items(layer: Layer): List<TextItem> = TextCodec.decodeAll(layer.textData)

    private fun assertRendered(what: String, layer: Layer) =
        assertArrayEquals("$what: the pixels are the texts (I1)", kit.renderTexts(layer), kit.pixels(layer.bitmap))

    /** "Lower" ("One", "Two") and "Upper" ("Three", "Four") merged down: one text layer of four texts. */
    private fun mergedTexts(c: EditorController): Layer {
        val lower = kit.textLayer(c, kit.text("One", 70f, 60f), kit.text("Two", 220f, 60f, 0xFFC02020.toInt()), name = "Lower")
        val upper = kit.textLayer(c, kit.text("Three", 80f, 170f, 0xFF208040.toInt()), kit.text("Four", 230f, 170f), name = "Upper")
        c.mergeDown(upper)
        kit.settle(c)
        assertTrue(lower.isTextLayer)
        assertEquals(4, items(lower).size)
        return lower
    }

    /** ✓ of [s]: the result lands in the background (about 15 s at most). */
    private fun applied(c: EditorController, s: FilterSession) {
        s.apply()
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + 15_000
        while (c.filterSession != null && System.currentTimeMillis() < end) {
            looper.idleFor(Duration.ofMillis(10))
            Thread.sleep(1)
        }
        assertTrue("applied", s.isClosed)
    }

    @Test
    fun aFilterSessionOfSeveralChangesEndsAsOneStep() {
        val c = kit.controller()
        val layer = mergedTexts(c)
        assertSame(layer, c.activeLayer)
        val texts = items(layer)
        val filter = BrightnessContrastFilter()
        val (start, result) = oneExactStep("the filter session", c, filter.name) {
            c.startFilter(filter)
            val s = c.filterSession
            assertNotNull(s)
            s!!.debounceMs = 0
            kit.idle()
            assertSame("the session owns Undo and Redo", s, c.inToolHistory)
            val steps = c.undoManager.undoCount
            // Three finished changes (a slider dragged through values is one).
            for (v in listOf(10f, 18f, 25f)) s.update("brightness", v)
            s.commitStep("brightness")
            s.update("contrast", 30f)
            s.commitStep("contrast")
            s.update("brightness", -20f)
            s.commitStep("brightness")
            assertEquals(3, s.history.depth)
            // Undo and Redo walk the session's own steps.
            c.undo()
            assertEquals(25f, s.values.float("brightness"))
            c.undo()
            assertEquals(0f, s.values.float("contrast"))
            c.redo()
            assertEquals(30f, s.values.float("contrast"))
            assertEquals("the walk leaves the second step's values", 25f, s.values.float("brightness"))
            assertFalse(s.isClosed)
            assertEquals("the session's steps are no document steps", steps, c.undoManager.undoCount)
            assertEquals("the texts stay while it is open", texts, items(layer))
            c.message = null
            applied(c, s)
            assertNull("the session's steps go with it", c.inToolHistory)
            assertFalse("the texts became pixels in the same step", layer.isTextLayer)
            assertEquals(String.format(Locale.ROOT, TextLabels19.RASTERIZED_TEXTS, layer.name, texts.size), c.message)
        }
        val k = c.doc.indexOf(layer)
        assertFalse("the filter changed the pixels", start.layers[k].pixels.contentEquals(result.layers[k].pixels))
        // ✓ applied the values the session's Undo and Redo left: a session that only sets them
        // (brightness 25, contrast 30) on the same texts gives the same pixels, level for level.
        c.undo()
        c.startFilter(BrightnessContrastFilter())
        val fresh = c.filterSession!!
        fresh.debounceMs = 0
        kit.idle()
        fresh.update("brightness", 25f)
        fresh.commitStep("brightness")
        fresh.update("contrast", 30f)
        fresh.commitStep("contrast")
        applied(c, fresh)
        assertArrayEquals("the values after the walk were applied", result.layers[k].pixels, px(layer.bitmap))
        // Undo: the texts are back, editable, and their pixels are theirs.
        c.undo()
        assertTrue(layer.isTextLayer)
        assertEquals(texts, items(layer))
        assertRendered("the filter undone", layer)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue("a text opens again", tool.editLayer(layer, index = 2))
        assertEquals(texts[2], tool.item)
        tool.discard()
    }

    /** A finger from [from] to [to], then lifted. */
    private fun drag(c: EditorController, from: Vec2, to: Vec2) {
        c.pointerDown(ToolPoint(from.x, from.y))
        for (k in 1..4) {
            val p = from + (to - from) * (k / 4f)
            c.pointerMove(ToolPoint(p.x, p.y))
        }
        c.pointerUp(ToolPoint(to.x, to.y))
    }

    @Test
    fun textPathNodeEditsEndAsOneStepEach() {
        val c = kit.controller()
        val layer = mergedTexts(c)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        val ed = tool.nodeEditor
        val runs = listOf(
            Triple(TextPathNodeEntry.ADD_POINTS, TextPathType.CIRCLE, 0),
            Triple(TextPathNodeEntry.EDIT_AS_PATH, TextPathType.CURVE, 1),
            Triple(TextPathNodeEntry.EDIT_AS_POLYLINE, TextPathType.CURVE, 3),
        )
        for ((entry, type, k) in runs) {
            val why = "$entry on text $k"
            val before = items(layer)
            assertTrue(tool.editLayer(layer, index = k))
            var edited = tool.item!!.path
            oneExactStep(why, c, "Edit text") {
                tool.setPath(tool.item!!.path.copy(type = type))
                assertTrue(tool.startNodes(entry))
                assertSame("$why: Undo and Redo go through the nodes", ed, c.inToolHistory)
                val steps = c.undoManager.undoCount
                // A node on the canvas moved by a finger: one step when it lifts.
                val nodes = TextPathNodes.of(tool.item!!.path)!!.points
                val i = nodes.indices.first { nodes[it].x in 20f..300f && nodes[it].y in 20f..220f }
                val from = Vec2(nodes[i].x, nodes[i].y)
                drag(c, from, from + Vec2(12f, 9f))
                assertEquals(why, TextLabels19.MOVE_POINT, ed.undoLabel)
                ed.addPoint()
                assertEquals(why, TextPathNodeEditor.ADD_POINT_STEP, ed.undoLabel)
                edited = tool.item!!.path
                // Undo takes the point back within the editing; Redo brings it again.
                c.undo()
                assertTrue("$why: still editing the nodes", ed.active)
                assertEquals(why, TextLabels19.MOVE_POINT, ed.undoLabel)
                c.redo()
                assertEquals(why, edited, tool.item!!.path)
                assertEquals("$why: the node edits are no document steps", steps, c.undoManager.undoCount)
                // Done editing points: the text stays open, its editor at the path settings, and
                // nothing is stored yet; then the editor's ✓ and the text's ✓: one step.
                tool.doneNodes()
                assertFalse(ed.active)
                assertNull(c.inToolHistory)
                assertEquals("$why: Done editing points stores nothing", steps, c.undoManager.undoCount)
                assertEquals("$why: the text stays open", edited, tool.item?.path)
                assertTrue("$why: back in the editor", tool.editorOpen)
                tool.confirmEditor()
                tool.commit()
                assertNull(tool.item)
            }
            val after = items(layer)
            assertEquals("$why: the nodes are stored", TextPathNodes.of(edited), TextPathNodes.of(after[k].path))
            assertEquals("$why: the same text", before[k].id, after[k].id)
            assertEquals("$why: the other texts are untouched", before.filterIndexed { j, _ -> j != k }, after.filterIndexed { j, _ -> j != k })
            assertRendered(why, layer)
        }
    }

    /** [actual] are [expected], point for point (document px). */
    private fun assertNear(what: String, expected: List<Vec2>, actual: List<Vec2>) {
        assertEquals("$what: the points", expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("$what: point $i: expected ${expected[i]}, got ${actual[i]}", expected[i].distanceTo(actual[i]) < 0.01f)
        }
    }

    /** The id and the stored points of the single line of [layer]. */
    private fun storedLine(layer: Layer): Pair<Long, List<Vec2>> {
        val p = layer.vector!!.objects.single() as VPath
        return p.id to p.subpaths.single().anchors.map { Vec2(it.x, it.y) }
    }

    /** A point on [p]'s line (a third along its outline), as `CurvePolylineReopenTest` taps it. */
    private fun onLine(p: VPath): Vec2 {
        val pts = VectorOps.toVectorPath(p).flatten(0.25f).single().points
        return pts[pts.size / 3]
    }

    /** The index of the shape point at ([x], [y]) (document px). */
    private fun pointAt(tool: ShapeTool, x: Float, y: Float): Int {
        val a = tool.docAnchors()!!
        val i = a.indices.minByOrNull { a[it].pos.distanceTo(Vec2(x, y)) }!!
        assertTrue("a point at ($x, $y): ${a[i].pos}", a[i].pos.distanceTo(Vec2(x, y)) < 0.01f)
        return i
    }

    /**
     * The open shape in Points mode, its corner at ([x], [y]) bevelled, the popup changed three
     * times and closed, then walked back and forth in the tool. Returns the bevelled points.
     */
    private fun bevelOneCorner(c: EditorController, tool: ShapeTool, x: Float, y: Float, segments: Int, amount: Float): List<Vec2> {
        tool.setPointEditing(true)
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.pointCount)
        tool.selectPoints(PointSelection.none(tool.pointCount).plusAll(listOf(pointAt(tool, x, y))))
        val steps = c.undoManager.undoCount
        assertTrue(tool.bevelPoints())
        assertTrue(tool.bevelPopupOpen)
        tool.changeBevelSegments(3)
        tool.changeBevelAmount(amount)
        tool.changeBevelSegments(segments)
        assertEquals("the corner is ${segments + 1} points", 4 - 1 + segments + 1, tool.pointCount)
        val bevelled = tool.docAnchors()!!
        tool.closeBevelPopup()
        assertFalse(tool.bevelPopupOpen)
        // One Undo takes the bevel with every popup change back, in the tool; Redo brings it.
        c.undo()
        assertEquals(4, tool.pointCount)
        c.redo()
        assertEquals(bevelled, tool.docAnchors())
        assertEquals("the in-tool steps are no document steps", steps, c.undoManager.undoCount)
        return bevelled.map { it.pos }
    }

    @Test
    fun polylineAndShapeBevelPopupsEndAsOneStepEach() {
        // Polyline, new: five points, two corners bevelled, the popup changed three times and closed.
        val c = A1Tools.controller()
        val vector = c.activeLayer
        val t = c.curveTool(ToolId.POLYLINE)
        var bevelled = emptyList<Vec2>()
        oneExactStep("the polyline bevel", c, "Polyline") {
            for (p in A1Tools.FIVE) assertTrue(t.addAnchor(p))
            t.selectPoints(PointSelection.of(t.pointCount, 1, 3))
            val steps = c.undoManager.undoCount
            assertTrue(t.bevelPoints())
            assertTrue(t.setBevelSegments(3))
            assertTrue(t.setBevelAmount(0.4f))
            assertTrue(t.setBevelSegments(2))
            assertEquals("each corner is three points", 5 - 2 + 2 * 3, t.pointCount)
            val made = t.anchors
            bevelled = made.map { it.pos }
            t.closeBevelPopup()
            c.undo()
            assertEquals(5, t.pointCount)
            c.redo()
            assertEquals(made, t.anchors)
            assertEquals("the in-tool steps are no document steps", steps, c.undoManager.undoCount)
            t.commit()
            assertFalse(t.hasPendingWork)
        }
        val (lineId, stored) = storedLine(vector)
        assertNear("the polyline keeps its bevel", bevelled, stored)

        // Polyline, reopened by a tap on it: its middle point bevelled, the popup changed, closed.
        val tap = onLine(vector.vector!!.objects.single() as VPath)
        var rebevelled = emptyList<Vec2>()
        oneExactStep("the reopened polyline's bevel", c, CurveTool.EDIT_PATH_LABEL) {
            c.pointerDown(ToolPoint(tap.x, tap.y))
            c.pointerUp(ToolPoint(tap.x, tap.y))
            assertTrue("the line opens in the Polyline tool", t.isReopened)
            assertNear("it opens as it was stored", stored, t.anchors.map { it.pos })
            val steps = c.undoManager.undoCount
            assertEquals(A1Tools.FIVE[2], t.anchors[4].pos)
            t.selectPoints(PointSelection.of(t.pointCount, 4))
            assertTrue(t.bevelPoints())
            assertTrue(t.setBevelSegments(1))
            assertTrue(t.setBevelAmount(0.25f))
            assertEquals("the middle corner is two points", 9 - 1 + 2, t.pointCount)
            val made = t.anchors
            rebevelled = made.map { it.pos }
            t.closeBevelPopup()
            c.undo()
            assertNear("Undo in the tool: the line as it was opened", stored, t.anchors.map { it.pos })
            c.redo()
            assertEquals(made, t.anchors)
            assertEquals("the in-tool steps are no document steps", steps, c.undoManager.undoCount)
            t.commit()
            assertFalse(t.isReopened)
        }
        val (sameId, restored) = storedLine(vector)
        assertEquals("the same line", lineId, sameId)
        assertNear("the reopened polyline keeps its new bevel", rebevelled, restored)

        // Shape, new, on a pixel layer: placed in a shape layer of its own, one corner bevelled.
        val d = rig.controller()
        val tool = rig.shapeTool(d)
        var shapeBevel = emptyList<Vec2>()
        oneExactStep("the shape bevel", d, ShapeTool.SHAPE_LABEL) {
            rig.drawRect(d, tool)
            shapeBevel = bevelOneCorner(d, tool, 30f, 30f, segments = 2, amount = 0.3f)
            tool.commit()
            assertFalse(tool.hasPendingWork)
        }
        val shapeLayer = d.doc.layers.single { it.isShapeLayer }
        val steps = d.undoManager.undoCount
        assertTrue(tool.editLayer(shapeLayer))
        tool.setPointEditing(true)
        assertNear("the shape layer keeps its bevel", shapeBevel, tool.docAnchors()!!.map { it.pos })
        tool.discard()
        assertEquals("opening it again is no step", steps, d.undoManager.undoCount)

        // Shape, reopened: a rectangle object of a vector layer, one corner bevelled, "Edit shape".
        val e = rig.controller(vectorLayer = true)
        val vec = e.activeLayer
        val st = rig.shapeTool(e)
        rig.drawRect(e, st)
        st.commit()
        CrossUndoSnap.settle(e)
        val id = (vec.vector!!.objects.single() as VShape).id
        var objectBevel = emptyList<Vec2>()
        oneExactStep("the reopened shape's bevel", e, ShapeTool.EDIT_SHAPE_LABEL) {
            assertTrue(st.editShapeObject(vec, id))
            assertTrue("the object is open", st.editingObject)
            objectBevel = bevelOneCorner(e, st, 110f, 90f, segments = 1, amount = 0.25f)
            st.commit()
            assertFalse(st.hasPendingWork)
        }
        assertEquals("the same object", id, vec.vector!!.objects.single().id)
        val objectSteps = e.undoManager.undoCount
        assertTrue(st.editShapeObject(vec, id))
        st.setPointEditing(true)
        assertNear("the object keeps its bevel", objectBevel, st.docAnchors()!!.map { it.pos })
        st.discard()
        assertEquals("opening it again is no step", objectSteps, e.undoManager.undoCount)
    }
}
