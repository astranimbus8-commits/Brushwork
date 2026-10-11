package com.brushwork.paint.tools.vector

import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.1, Perspective array ruler): perspective copies can't be kept by an object,
 * so a shape drawn with them
 * - with Editable on over a raster layer is drawn as pixels on the CURRENT layer (no shape
 *   layer), with the hint [MirrorLabels18.PERSPECTIVE_PIXELS] once per session;
 * - on a vector layer becomes the source plus one plain path per copy, in ONE undo step, with the
 *   hint [MirrorLabels18.PERSPECTIVE_BAKED] once per session.
 */
@RunWith(RobolectricTestRunner::class)
class ShapePerspectivePixelsTest {
    private val kit = ShapeCopiesKit()

    @After
    fun close() = kit.close()

    // A 20 px square in the middle of the default perspective cell (56 px around the centre).
    private val x0 = 150f
    private val y0 = 130f
    private val x1 = 170f
    private val y1 = 150f

    @Test
    fun perspectiveCopiesBecomePixelsOrPlainPaths() {
        // 1. Editable on, raster layer: pixels on the current layer.
        run {
            val c = kit.controller(symmetry = SymmetryType.PERSPECTIVE_ARRAY)
            val maps = ObjectCopies.mapsAt(c, x0, y0)
            assertTrue(ObjectCopies.isPerspective(maps))
            // A copy whose square's centre is on the canvas, away from the source.
            val copy = maps.drop(1).map { kit.mapped(it, 160f, 140f) }
                .first { (x, y) -> x in 10f..310f && y in 10f..270f && (x - 160f) * (x - 160f) + (y - 140f) * (y - 140f) > 900f }
            val tool = kit.rectTool(c, ShapeStyle.FILL, editable = true)
            val layer = c.doc.activeLayer
            val layers = c.doc.layers.size
            kit.drawRect(c, x0, y0, x1, y1)
            assertTrue("the copy shows while the shape is open", kit.shownAt(c, copy.first.toInt(), copy.second.toInt()))
            val steps = c.undoManager.undoCount
            c.message = null
            tool.commit()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertEquals("no shape layer", layers, c.doc.layers.size)
            assertFalse(layer.isShapeLayer)
            assertEquals(MirrorLabels18.PERSPECTIVE_PIXELS, c.message)
            assertEquals(255, kit.alphaAt(layer.bitmap, 160, 140))
            assertNotEquals("the copy", 0, kit.alphaAt(layer.bitmap, copy.first.toInt(), copy.second.toInt()))
            // Once per session.
            c.message = null
            kit.drawRect(c, x0 - 100f, y0, x1 - 100f, y1)
            tool.commit()
            assertNotEquals(MirrorLabels18.PERSPECTIVE_PIXELS, c.message)
            c.undo()
            c.undo()
            assertEquals(0, kit.alphaAt(layer.bitmap, copy.first.toInt(), copy.second.toInt()))
        }
        // 2. Vector layer: the source plus plain paths, one step.
        run {
            val c = kit.controller(vector = true, symmetry = SymmetryType.PERSPECTIVE_ARRAY)
            val maps = ObjectCopies.mapsAt(c, x0, y0)
            val tool = kit.rectTool(c, ShapeStyle.STROKE_FILL, editable = true)
            val layer = c.doc.activeLayer
            kit.drawRect(c, x0, y0, x1, y1)
            val steps = c.undoManager.undoCount
            c.message = null
            tool.commit()
            c.vectors.flushPending()
            assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
            assertEquals(MirrorLabels18.PERSPECTIVE_BAKED, c.message)
            val objects = layer.vector!!.objects
            assertEquals("one object per map", maps.size, objects.size)
            assertTrue("the source", objects.first() is VShape)
            assertTrue("plain paths", objects.drop(1).all { it is VPath })
            assertTrue("no copies kept", objects.none { ObjectCopies.hasCopies(it) })
            assertArrayEquals("data and pixels together", kit.render(layer.vector!!), kit.rig.pixels(layer.bitmap))
            // Once per session.
            c.message = null
            kit.drawRect(c, x0 - 100f, y0, x1 - 100f, y1)
            tool.commit()
            c.vectors.flushPending()
            assertNotEquals(MirrorLabels18.PERSPECTIVE_BAKED, c.message)
            c.undo()
            c.undo()
            c.vectors.flushPending()
            assertTrue(layer.vector!!.objects.isEmpty())
            assertNull(layer.shapeData)
        }
    }
}
