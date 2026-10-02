package com.brushwork.paint.ui.layers

import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 integration (lead): the layers window's Clear / Fill on a vector layer work on objects like
 * the selection bar's (the layer stays a vector layer, one step), Cut on a vector layer is named
 * "Cut", and Merge down into a locked or hidden lower layer behaves the same for vector and
 * raster layers.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLayerMenuRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun layersWindowClearRemovesObjectsAndKeepsTheVectorLayer() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 200f, 300f, 220f)), "Add")
        val before = layer.vector!!
        val pixels = kit.pixels(layer.bitmap)
        // Alpha lock is about pixels: objects are still cleared (as the selection bar does).
        layer.alphaLocked = true
        val steps = c.undoManager.undoCount
        // With a selection: only the objects it touches go.
        c.setSelection(kit.rectSelection(30, 30, 150, 130), recordUndo = false)
        assertTrue(LayerOps.clear(c, layer))
        assertNotNull("still a vector layer", layer.vector)
        assertEquals(listOf(2L), layer.vector!!.objects.map { it.id })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Clear", c.undoManager.undoLabel)
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(pixels, kit.pixels(layer.bitmap))
        // Without a selection: every object.
        c.setSelection(null, recordUndo = false)
        assertTrue(LayerOps.clear(c, layer))
        assertNotNull(layer.vector)
        assertTrue(layer.vector!!.objects.isEmpty())
        assertArrayEquals(IntArray(kit.w * kit.h), kit.pixels(layer.bitmap))
    }

    @Test
    fun layersWindowFillAddsAFilledObject() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f)), "Add")
        val steps = c.undoManager.undoCount
        c.setSelection(kit.rectSelection(200, 100, 300, 180), recordUndo = false)
        assertTrue(LayerOps.fill(c, layer, 0xFF3366CC.toInt()))
        val content = layer.vector
        assertNotNull("still a vector layer", content)
        assertEquals(2, content!!.objects.size)
        val fill = content.objects.last() as VPath
        assertEquals(VPaint.Solid(0xFF3366CC.toInt()), fill.fill)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Fill", c.undoManager.undoLabel)
        assertEquals(0xFF3366CC.toInt(), layer.bitmap.getPixel(250, 140))
        assertArrayEquals(kit.render(content), kit.pixels(layer.bitmap))
        c.undo()
        assertEquals(1, layer.vector!!.objects.size)
        assertEquals(0, layer.bitmap.getPixel(250, 140))
    }

    @Test
    fun cutOnAVectorLayerIsOneStepNamedCut() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 200f, 300f, 220f)), "Add")
        val steps = c.undoManager.undoCount
        c.setSelection(kit.rectSelection(30, 30, 150, 130), recordUndo = false)
        assertTrue(c.cutSelection())
        assertNotNull(c.clipboard)
        assertEquals(listOf(2L), layer.vector!!.objects.map { it.id })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Cut", c.undoManager.undoLabel)
    }

    /** Merging into a locked or hidden lower layer: allowed from the controller, for vector and raster layers alike. */
    @Test
    fun mergeIntoALockedOrHiddenLowerLayerIsTheSameForVectorAndRasterLayers() {
        for (vector in listOf(true, false)) {
            for (lockIt in listOf(true, false)) {
                val c = kit.controller()
                val lower = c.vec
                if (!vector) lower.vector = null
                val upper = Layer(c.doc.newLayerId(), "Upper", BitmapUtils.createLayerBitmap(kit.w, kit.h))
                if (vector) upper.vector = VectorContent.EMPTY
                c.doc.layers += upper
                c.selectLayer(upper)
                if (vector) {
                    c.vectors.addObjects(lower, listOf(kit.box(40f, 40f, 140f, 120f)), "Add")
                    c.vectors.addObjects(upper, listOf(kit.stroke(200f, 200f, 300f, 220f)), "Add")
                } else {
                    Canvas(lower.bitmap).drawColor(0xFFCC0000.toInt())
                    Canvas(upper.bitmap).drawRect(10f, 10f, 30f, 30f, android.graphics.Paint().apply { color = 0xFF00CC00.toInt() })
                }
                if (lockIt) lower.locked = true else lower.visible = false
                val what = "${if (vector) "vector" else "raster"} into a ${if (lockIt) "locked" else "hidden"} layer"
                val steps = c.undoManager.undoCount
                c.mergeDown(upper)
                assertEquals("$what: merged", -1, c.doc.indexOf(upper))
                assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
                assertEquals("Merge down", c.undoManager.undoLabel)
                if (vector) assertEquals("$what: the objects stay editable", 2, lower.vector!!.objects.size)
                else assertEquals(0xFF00CC00.toInt(), lower.bitmap.getPixel(20, 20))
                c.undo()
                assertTrue(c.doc.indexOf(upper) >= 0)
            }
        }
    }

    /** The layers window refuses a locked lower layer and merges into a hidden one, whatever the kind of layer. */
    @Test
    fun layersWindowMergeTreatsVectorAndRasterLowerLayersAlike() {
        for (vector in listOf(true, false)) {
            for (lockIt in listOf(true, false)) {
                val c = kit.controller()
                val lower = c.vec
                if (!vector) lower.vector = null
                val upper = Layer(c.doc.newLayerId(), "Upper", BitmapUtils.createLayerBitmap(kit.w, kit.h))
                if (vector) upper.vector = VectorContent.EMPTY
                c.doc.layers += upper
                c.selectLayer(upper)
                if (vector) c.vectors.addObjects(upper, listOf(kit.stroke(200f, 200f, 300f, 220f)), "Add")
                if (lockIt) lower.locked = true else lower.visible = false
                LayerOps.mergeDown(c, upper)
                val merged = c.doc.indexOf(upper) < 0
                assertEquals("${if (vector) "vector" else "raster"}, ${if (lockIt) "locked" else "hidden"} lower layer", !lockIt, merged)
                if (merged && vector) assertNotNull(lower.vector)
            }
        }
    }
}
