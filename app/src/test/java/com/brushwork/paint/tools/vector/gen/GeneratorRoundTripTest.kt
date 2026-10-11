package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.pixels
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.vector.curveFacts
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 (item 6 with item 1a, design §3.7 and §3.1; I15, I16): a generated curve drawn with a
 * mirror ruler, as fingers and the top row's Undo / Redo use it:
 * - ✓ stores ONE path with its generator and its copies; saved and loaded it is the same path,
 *   and a tap on its copy (the ruler off by then) reopens it generated, with its copies;
 * - a drag of the size handle is ONE in-tool step (undo and redo restore it exactly) and the
 *   path stored again still matches its generator, as ONE document step undo and redo take back;
 * - "Remove mirror copies" keeps the generator;
 * - on a raster layer the curve and its copies are ONE pixel step;
 * - a slider frame that changes the point count leaves the strip's facts (and spec) as they were.
 */
@RunWith(RobolectricTestRunner::class)
class GeneratorRoundTripTest {
    private val mirror = SymmetrySettings(type = SymmetryType.MIRROR)

    /** x ↦ 400 − x: the mirror copy of [p] on the 400 px wide test document. */
    private fun mirrored(p: Vec2) = Vec2(400f - p.x, p.y)

    @Test
    fun aGeneratedCurveWithCopiesSurvivesSaveReopenAndHandleDrags() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller(vector = true)
            c.updateSymmetry(mirror)
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(CurveGenKind.GOLDEN, null))
            assertTrue("$id: the copies' maps are taken at the first point", t.hasCopies)
            // The whole curve moves left of the mirror's axis (the generator follows).
            val at = t.pillPosition.position!!
            t.pillPosition.setPosition(at.x - 90f, at.y)
            val g = t.generator
            assertNotNull("$id: a move of the whole curve keeps it", g)
            t.commit()
            val layer = c.activeLayer
            val stored = layer.vector!!.objects.single() as VPath
            assertEquals(g, stored.generator)
            assertFalse("$id: stored with its copies", stored.copies.isEmpty)
            assertTrue(CurveGenerators.matches(stored))

            // Saved and loaded: the same path, still its generator's.
            val loaded = VectorCodec.decode(VectorCodec.encode(layer.vector!!))
            assertEquals("$id: the round trip", layer.vector, loaded)
            layer.vector = loaded
            val back = loaded.objects.single() as VPath
            assertTrue("$id: I16 after loading", CurveGenerators.matches(back))

            // The ruler is off now; a tap on the copy's line reopens it, generated, with its copies.
            c.updateSymmetry(SymmetrySettings())
            c.tap(mirrored(onLine(ObjectCopiesSource.of(back))))
            assertTrue("$id: reopened from its copy", t.isReopened)
            assertEquals("$id: the generator kept", back.generator, t.generator)
            assertTrue(t.hasCopies)

            // A drag of the size handle: one in-tool step.
            val h = t.generatorHandles!!
            val anchors = t.anchors
            c.drag(h.size.x to h.size.y, h.size.x + 8f to h.size.y + 4f, h.size.x + 20f to h.size.y + 10f)
            val dragged = t.generator!!
            assertNotEquals("$id: the drag scaled the curve", back.generator!!.size, dragged.size, 0.01f)
            assertTrue(t.undoStep())
            assertEquals("$id: one undo takes the whole drag back", back.generator, t.generator)
            assertEquals(anchors, t.anchors)
            assertFalse("$id: nothing older in the tool", t.canUndoStep)
            assertTrue(t.redoStep())
            assertEquals("$id: redo brings it back exactly", dragged, t.generator)

            // Stored again: ONE document step, still generated, still mirrored.
            val steps = c.undoManager.undoCount
            t.commit()
            val edited = layer.vector!!.objects.single() as VPath
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals(back.id, edited.id)
            assertEquals(dragged, edited.generator)
            assertEquals(back.copies, edited.copies)
            assertTrue("$id: I16 after the edit", CurveGenerators.matches(edited))
            c.undo()
            assertEquals("$id: undo restores the path exactly", back, layer.vector!!.objects.single())
            c.redo()
            assertEquals("$id: redo brings the edit back", edited, layer.vector!!.objects.single())
        }
    }

    @Test
    fun removeMirrorCopiesKeepsTheGenerator() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller(vector = true)
            c.updateSymmetry(mirror)
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(CurveGenKind.ROSE, null))
            val g = t.generator
            assertTrue(t.removeCopies())
            assertEquals("$id: copies are no geometry", g, t.generator)
            assertTrue(t.undoStep())
            assertTrue("$id: undo brings the copies back", t.hasCopies)
            assertEquals(g, t.generator)
            assertTrue(t.redoStep())
            assertFalse(t.hasCopies)
            t.commit()
            val stored = c.activeLayer.vector!!.objects.single() as VPath
            assertTrue(stored.copies.isEmpty)
            assertEquals(g, stored.generator)
        }
    }

    @Test
    fun onARasterLayerTheCurveAndItsCopiesAreOnePixelStep() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller(vector = false)
            c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4))
            val t = c.tool(id)
            t.plainLine()
            val blank = pixels(c.activeLayer.bitmap)
            val steps = c.undoManager.undoCount
            assertTrue(t.startGenerator(CurveGenKind.SINE, null))
            t.commit()
            assertEquals("$id: one step", steps + 1, c.undoManager.undoCount)
            val drawn = pixels(c.activeLayer.bitmap)
            assertFalse("$id: painted", blank.contentEquals(drawn))
            c.undo()
            assertArrayEquals("$id: undo restores the pixels", blank, pixels(c.activeLayer.bitmap))
            c.redo()
            assertArrayEquals("$id: redo paints them again", drawn, pixels(c.activeLayer.bitmap))
        }
    }

    /**
     * A "Turns" slider frame changes the point count; the strip's facts stay equal, so the strip
     * builds no new spec while the slider moves (the generator sheet's 8 ms frame budget).
     */
    @Test
    fun aTurnsChangeKeepsTheStripFacts() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller(vector = true)
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(CurveGenKind.ARCHIMEDEAN, null))
            val facts = curveFacts(t)
            val n = t.pointCount
            t.beginNumericEdit()
            t.changeGenerator(t.generator!!.copy(turns = t.generator!!.turns + 2f))
            assertNotEquals("$id: more turns, more points", n, t.pointCount)
            assertEquals("$id: the same strip", facts, curveFacts(t))
            t.endNumericEdit()
        }
    }

    /**
     * "Edited by hand" shows once per editor session (§3.7), not once per curve tool: after the
     * Curve tool showed it, a hand edit of a generated Path shows nothing.
     */
    @Test
    fun theEditedHintShowsOncePerEditorAcrossCurveAndPath() {
        val c = controller(vector = true)
        val curve = c.tool(ToolId.CURVE)
        curve.plainLine()
        assertTrue(curve.startGenerator(CurveGenKind.ROSE, null))
        c.message = null
        curve.deleteAnchor(1)
        assertNull("the curve: hand edited", curve.generator)
        assertEquals(CurveGenLabels18.EDITED_HINT, c.message)
        curve.commit()

        val path = c.tool(ToolId.PATH)
        path.plainLine()
        assertTrue(path.startGenerator(CurveGenKind.ROSE, null))
        c.message = null
        path.deleteAnchor(1)
        assertNull("the path: hand edited", path.generator)
        assertNull("the hint showed already in this editor", c.message)
    }

    /** The source of a stored path (its own geometry, without the copies). */
    private object ObjectCopiesSource {
        fun of(p: VPath): VPath = com.brushwork.paint.vector.ObjectCopies.source(p) as VPath
    }
}
