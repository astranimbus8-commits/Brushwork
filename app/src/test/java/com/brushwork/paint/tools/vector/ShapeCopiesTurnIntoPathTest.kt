package com.brushwork.paint.tools.vector

import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F review (design §3.1 with v1.7's "Turn into path"): a shape with symmetry copies turned
 * into a path keeps them.
 * - Mirror, a new shape on a raster layer: the shape layer it is placed in (one step, with its
 *   copies) becomes a vector layer whose ONE path keeps the copies (`VPath.copies`), one step more.
 * - Mirror, a shape object of a vector layer: replaced in place by a path with the same copies.
 * - Perspective array, a new shape on a raster layer: the source is placed in a shape layer (a
 *   shape layer can't keep perspective copies), then the vector layer holds the source path and
 *   one plain path per copy (the perspective bake, [MirrorLabels18.PERSPECTIVE_BAKED]).
 */
@RunWith(RobolectricTestRunner::class)
class ShapeCopiesTurnIntoPathTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    @Test
    fun turnIntoPathKeepsTheCopiesOrBakesThePerspectiveOnes() {
        // 1. Mirror, raster layer: one path with the copies.
        run {
            val c = kit.controller()
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            val before = c.doc.layers.map { it.id }.toSet()
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            val copies = ObjectCopies.fromSymmetry(ObjectCopies.mapsAt(c, 40f, 60f))!!
            assertEquals(2, copies.count)
            val steps = c.undoManager.undoCount
            assertTrue(tool.turnIntoPath())
            c.vectors.flushPending()
            assertEquals("the shape, then Turn into path", steps + 2, c.undoManager.undoCount)
            val layer = c.doc.layers.single { it.id !in before }
            assertTrue(layer.isVectorLayer)
            val path = layer.vector!!.objects.single() as VPath
            assertEquals("the copies stay the path's", copies, path.copies)
            assertArrayEquals("data and pixels together", kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
            assertEquals(255, kit.alphaAt(layer.bitmap, 70, 100))
            assertEquals("the copy", 255, kit.alphaAt(layer.bitmap, 250, 100))
            // One undo: the shape layer, with its copies. (The Path tool, which opened the path,
            // closes it untouched first: how it holds a path's copies is area E's.)
            assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
            c.currentTool.discard()
            assertEquals(steps + 2, c.undoManager.undoCount)
            c.undo()
            c.vectors.flushPending()
            assertTrue(layer.isShapeLayer)
            assertEquals(copies, ShapeCodec.decode(layer.shapeData!!)!!.copies)
            assertEquals(255, kit.alphaAt(layer.bitmap, 250, 100))
        }
        // 2. Mirror, a vector layer's shape object: replaced in place, the copies kept.
        run {
            val c = kit.controller(vector = true)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            kit.drawRect(c, 40f, 60f, 100f, 140f)
            tool.commit()
            c.vectors.flushPending()
            val layer = c.doc.activeLayer
            val shape = layer.vector!!.objects.single() as VShape
            assertEquals(2, shape.shape.copies.count)
            // Opened from its copy.
            kit.tap(c, 250f, 100f)
            assertTrue(tool.hasPendingWork)
            val steps = c.undoManager.undoCount
            assertTrue(tool.turnIntoPath())
            c.vectors.flushPending()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            val path = layer.vector!!.objects.single() as VPath
            assertEquals("same id", shape.id, path.id)
            assertEquals(shape.shape.copies, path.copies)
            assertArrayEquals(kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
            assertEquals(255, kit.alphaAt(layer.bitmap, 250, 100))
        }
        // 3. Perspective array, raster layer: the source path plus one plain path per copy.
        run {
            val c = kit.controller(symmetry = SymmetryType.PERSPECTIVE_ARRAY)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            val before = c.doc.layers.map { it.id }.toSet()
            val maps = ObjectCopies.mapsAt(c, 150f, 130f)
            assertTrue(ObjectCopies.isPerspective(maps))
            kit.drawRect(c, 150f, 130f, 170f, 150f)
            assertTrue(tool.hasCopies)
            val steps = c.undoManager.undoCount
            c.message = null
            assertTrue(tool.turnIntoPath())
            c.vectors.flushPending()
            assertEquals("the shape, then Turn into path", steps + 2, c.undoManager.undoCount)
            assertEquals(MirrorLabels18.PERSPECTIVE_BAKED, c.message)
            val layer = c.doc.layers.single { it.id !in before }
            assertTrue(layer.isVectorLayer)
            val objects = layer.vector!!.objects
            assertEquals("one path per map", maps.size, objects.size)
            assertTrue(objects.all { it is VPath })
            assertTrue("none keeps copies", objects.none { ObjectCopies.hasCopies(it) })
            assertArrayEquals("data and pixels together", kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
            // The copies are only on the vector layer: nothing was painted on the raster one.
            assertFalse(c.doc.layers.first().bitmap.let { b -> (0 until b.height).any { y -> (0 until b.width).any { x -> b.getPixel(x, y) != 0 } } })
            // One undo: the shape layer of the source (a shape layer keeps no perspective copies).
            c.currentTool.discard()
            c.undo()
            c.vectors.flushPending()
            assertTrue(layer.isShapeLayer)
            assertTrue(ShapeCodec.decode(layer.shapeData!!)!!.copies.isEmpty)
            assertNotEquals(0, kit.alphaAt(layer.bitmap, 160, 140))
        }
    }
}
