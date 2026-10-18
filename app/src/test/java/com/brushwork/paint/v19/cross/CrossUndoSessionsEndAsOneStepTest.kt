package com.brushwork.paint.v19.cross

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
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
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.v19.a1.A1Tools
import com.brushwork.paint.v19.a1.A1Tools.curveTool
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.v19.a9.A9Rig
import com.brushwork.paint.v19.cross.CrossUndoSnap.oneExactStep
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
 * - the bevel popup on Polyline points and on the Shape tool's points.
 * While a session is open Undo and Redo walk its own steps and the document history does not
 * move; after its ✓, one Undo gives back the document before the session exactly (data and
 * pixels) and one Redo the result exactly.
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
            assertFalse(s.isClosed)
            assertEquals("the session's steps are no document steps", steps, c.undoManager.undoCount)
            assertEquals("the texts stay while it is open", texts, items(layer))
            // ✓: the result lands in the background.
            c.message = null
            s.apply()
            val looper = shadowOf(Looper.getMainLooper())
            val end = System.currentTimeMillis() + 15_000
            while (c.filterSession != null && System.currentTimeMillis() < end) {
                looper.idleFor(Duration.ofMillis(10))
                Thread.sleep(1)
            }
            assertTrue("applied", s.isClosed)
            assertNull("the session's steps go with it", c.inToolHistory)
            assertFalse("the texts became pixels in the same step", layer.isTextLayer)
            assertEquals(String.format(Locale.ROOT, TextLabels19.RASTERIZED_TEXTS, layer.name, texts.size), c.message)
        }
        val k = c.doc.indexOf(layer)
        assertFalse("the filter changed the pixels", start.layers[k].pixels.contentEquals(result.layers[k].pixels))
        // One more Undo: the texts are back, editable, and their pixels are theirs.
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
                // Done editing points, then the editor's ✓ and the text's ✓: one step.
                tool.doneNodes()
                assertFalse(ed.active)
                assertNull(c.inToolHistory)
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

    @Test
    fun polylineAndShapeBevelPopupsEndAsOneStepEach() {
        // Polyline: five points, two corners bevelled, the popup changed three times and closed.
        val c = A1Tools.controller()
        val vector = c.activeLayer
        oneExactStep("the polyline bevel", c, null) {
            val t = c.curveTool(ToolId.POLYLINE)
            for (p in A1Tools.FIVE) assertTrue(t.addAnchor(p))
            t.selectPoints(PointSelection.of(t.pointCount, 1, 3))
            val steps = c.undoManager.undoCount
            assertTrue(t.bevelPoints())
            assertTrue(t.setBevelSegments(3))
            assertTrue(t.setBevelAmount(0.4f))
            assertTrue(t.setBevelSegments(2))
            assertEquals("each corner is three points", 5 - 2 + 2 * 3, t.pointCount)
            val bevelled = t.anchors
            t.closeBevelPopup()
            // One Undo takes the bevel with every popup change back, in the tool; Redo brings it.
            c.undo()
            assertEquals(5, t.pointCount)
            c.redo()
            assertEquals(bevelled, t.anchors)
            assertEquals("the in-tool steps are no document steps", steps, c.undoManager.undoCount)
            t.commit()
            assertFalse(t.hasPendingWork)
        }
        assertEquals(1, vector.vector!!.objects.size)

        // Shape: the rectangle in Points mode, one corner bevelled, the popup changed and closed.
        val d = rig.controller()
        oneExactStep("the shape bevel", d, null) {
            val tool = rig.shapeTool(d)
            rig.drawRect(d, tool)
            tool.setPointEditing(true)
            assertTrue(tool.pointsMode)
            val a = tool.docAnchors()!!
            val corner = a.indices.first { a[it].pos.distanceTo(Vec2(30f, 30f)) < 0.01f }
            tool.selectPoints(PointSelection.none(tool.pointCount).plusAll(listOf(corner)))
            val steps = d.undoManager.undoCount
            assertTrue(tool.bevelPoints())
            assertTrue(tool.bevelPopupOpen)
            tool.changeBevelSegments(3)
            tool.changeBevelAmount(0.3f)
            tool.changeBevelSegments(2)
            assertEquals("the corner is three points", 4 - 1 + 3, tool.pointCount)
            val bevelled = tool.docAnchors()
            tool.closeBevelPopup()
            assertFalse(tool.bevelPopupOpen)
            d.undo()
            assertEquals(4, tool.pointCount)
            d.redo()
            assertEquals(bevelled, tool.docAnchors())
            assertEquals("the in-tool steps are no document steps", steps, d.undoManager.undoCount)
            tool.commit()
            assertFalse(tool.hasPendingWork)
        }
    }
}
