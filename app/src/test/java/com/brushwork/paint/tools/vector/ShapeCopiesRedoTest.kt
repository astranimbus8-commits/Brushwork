package com.brushwork.paint.tools.vector

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F review (design §3.1, I17): every commit of a shape with symmetry copies is ONE step that
 * undo takes back whole and redo brings back whole (the same data and the same pixels):
 * - a mirrored cog with a hole placed in a shape layer of its own (Editable on, raster layer);
 * - a mirrored half-alpha fill painted on the raster layer (Editable off; the union spec);
 * - a mirrored shape object of a vector layer (one object keeping its copies);
 * - a perspective array's copies as pixels (Editable on, raster layer);
 * - a perspective array's copies baked into plain paths (vector layer);
 * - a shape layer with copies reopened from its copy and moved.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeCopiesRedoTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    /**
     * [commit] is ONE step; [snapshot] (the data and pixels it changes) is [before] again after
     * undo and what the commit made again after redo.
     */
    private fun oneStepRoundTrip(c: EditorController, commit: () -> Unit, snapshot: () -> Any, before: Any) {
        val steps = c.undoManager.undoCount
        commit()
        c.vectors.flushPending()
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        val after = snapshot()
        assertFalse("the commit changed something", same(before, after))
        c.undo()
        c.vectors.flushPending()
        assertTrue("undo takes it all back", same(before, snapshot()))
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        c.vectors.flushPending()
        assertTrue("redo brings it all back", same(after, snapshot()))
        assertEquals(steps + 1, c.undoManager.undoCount)
    }

    private fun same(a: Any, b: Any): Boolean = when {
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it]!!, b[it]!!) }
        a is IntArray && b is IntArray -> a.contentEquals(b)
        else -> a == b
    }

    @Test
    fun aCommitWithCopiesIsOneStepBothWays() {
        // 1. A mirrored cog with a hole: a shape layer of its own.
        run {
            val c = kit.controller()
            val tool = kit.rig.cogTool(c, CogParams(teeth = 9, hole = 0.4f))
            kit.rig.drag(c, 20f to 60f, 60f to 100f, 100f to 140f)
            val ids = { c.doc.layers.map { it.id } }
            val before = ids()
            var layerId = -1L
            fun snap(): Any {
                val l = c.doc.layers.firstOrNull { it.id == layerId } ?: return listOf(ids())
                return listOf(ids(), ShapeCodec.decode(l.shapeData!!)!!, kit.rig.pixels(l.bitmap))
            }
            oneStepRoundTrip(c, {
                tool.commit()
                layerId = c.doc.activeLayer.id
            }, ::snap, listOf(before))
            val layer = c.doc.layers.single { it.id == layerId }
            assertTrue(layer.isShapeLayer)
            assertEquals(2, ShapeCodec.decode(layer.shapeData!!)!!.copies.count)
            assertEquals("the copy's ring", 255, kit.alphaAt(layer.bitmap, 235, 100))
            assertEquals("the copy's hole", 0, kit.alphaAt(layer.bitmap, 260, 100))
        }
        // 2. A mirrored half-alpha fill painted on the raster layer.
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = false, fill = 0x80E02020.toInt())
            val layer = c.doc.activeLayer
            kit.drawRect(c, 120f, 60f, 180f, 140f)
            val before = kit.rig.pixels(layer.bitmap)
            oneStepRoundTrip(c, { tool.commit() }, { kit.rig.pixels(layer.bitmap) }, before)
            assertFalse(layer.isShapeLayer)
            assertEquals("the overlap is not darker", kit.alphaAt(layer.bitmap, 130, 100), kit.alphaAt(layer.bitmap, 150, 100))
            assertEquals("the copy", kit.alphaAt(layer.bitmap, 130, 100), kit.alphaAt(layer.bitmap, 190, 100))
        }
        // 3. A mirrored shape object of a vector layer.
        run {
            val c = kit.controller(vector = true)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            val layer = c.doc.activeLayer
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            val snap = { listOf(layer.vector!!.objects.toList(), kit.rig.pixels(layer.bitmap)) }
            oneStepRoundTrip(c, { tool.commit() }, snap, snap())
            assertEquals(2, (layer.vector!!.objects.single() as VShape).shape.copies.count)
            assertArrayEquals("data and pixels together", kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
        }
        // 4. A perspective array's copies as pixels on the raster layer.
        run {
            val c = kit.controller(symmetry = SymmetryType.PERSPECTIVE_ARRAY)
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = true)
            val layer = c.doc.activeLayer
            val layers = c.doc.layers.size
            kit.drawRect(c, 150f, 130f, 170f, 150f)
            val before = kit.rig.pixels(layer.bitmap)
            oneStepRoundTrip(c, { tool.commit() }, { kit.rig.pixels(layer.bitmap) }, before)
            assertEquals("no shape layer", layers, c.doc.layers.size)
        }
        // 5. A perspective array's copies baked into plain paths of the vector layer.
        run {
            val c = kit.controller(vector = true, symmetry = SymmetryType.PERSPECTIVE_ARRAY)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            val layer = c.doc.activeLayer
            kit.drawRect(c, 150f, 130f, 170f, 150f)
            val snap = { listOf(layer.vector!!.objects.toList(), kit.rig.pixels(layer.bitmap)) }
            oneStepRoundTrip(c, { tool.commit() }, snap, snap())
            assertTrue("the source and its baked copies", layer.vector!!.objects.size > 1)
            assertArrayEquals("data and pixels together", kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
        }
        // 6. A shape layer with copies reopened from its copy and moved.
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            tool.commit()
            val layer = c.doc.activeLayer
            assertTrue(layer.isShapeLayer)
            val snap = { listOf(ShapeCodec.decode(layer.shapeData!!)!!, kit.rig.pixels(layer.bitmap)) }
            val before = snap()
            kit.tap(c, 250f, 100f)
            assertNotNull("opened from the copy", tool.editingLayer)
            oneStepRoundTrip(c, {
                tool.place(tool.pendingObject()!!.box.translated(10f, 0f))
                tool.commit()
            }, snap, before)
            assertEquals("the copy moved left", 255, kit.alphaAt(layer.bitmap, 215, 100))
            assertEquals(0, kit.alphaAt(layer.bitmap, 275, 100))
        }
    }
}
