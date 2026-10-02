package com.brushwork.paint.vector

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 integration (lead): Flip layer on a vector layer whose brushes don't mirror into
 * themselves (paper grain here) renders the mirrored objects again, so the cache is exactly their
 * rendering (no texture seams on a later partial re-render), as ONE step, also when it renders in
 * the background, with the layer's mask flipped in the same step. Layers whose brushes mirror into
 * themselves keep the exact pixel flip.
 */
@RunWith(RobolectricTestRunner::class)
class FlipVectorLayerRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun grainStroke(x0: Float, y0: Float, x1: Float, y1: Float): VStroke {
        val n = 16
        return VStroke(
            0, preset = BrushLibrary.defaultBrush.copy(size = 18f, grain = 0.6f), color = 0xFF303030.toInt(), seed = 11L, stylus = false,
            points = PackedPoints(FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }, FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }, FloatArray(n) { 1f }),
        )
    }

    /** [px] (kit.w x kit.h) mirrored left-right. */
    private fun mirrored(px: IntArray): IntArray = IntArray(px.size) { i -> val y = i / kit.w; val x = i % kit.w; px[y * kit.w + (kit.w - 1 - x)] }

    private fun flipAndCheck(c: EditorController, async: Boolean) {
        val layer = c.vec
        val before = layer.vector!!
        val pixels = kit.pixels(layer.bitmap)
        val expected = VectorLayerOps.flipped(before, kit.w, kit.h, horizontal = true)!!
        assertFalse("grain makes flipped pixels differ from the mirrored objects' rendering", mirrored(pixels).contentEquals(kit.render(expected)))
        if (async) c.vectors.policy = VectorLayers.Policy.ASYNC
        val steps = c.undoManager.undoCount
        c.flipLayer(layer, horizontal = true)
        c.vectors.flushPending()
        assertEquals(expected, layer.vector)
        assertArrayEquals("the cache is the mirrored objects' rendering", kit.render(expected), kit.pixels(layer.bitmap))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Flip layer horizontally", c.undoManager.undoLabel)
        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(pixels, kit.pixels(layer.bitmap))
        c.redo()
        assertEquals(expected, layer.vector)
        assertArrayEquals(kit.render(expected), kit.pixels(layer.bitmap))
    }

    @Test
    fun aGrainyVectorLayerIsRenderedAgainFromItsMirroredObjects() {
        for (async in listOf(false, true)) {
            val c = kit.controller()
            c.vectors.addObjects(c.vec, listOf(grainStroke(40f, 60f, 300f, 200f), kit.box(320f, 40f, 420f, 150f)), "Add")
            flipAndCheck(c, async)
        }
    }

    @Test
    fun itsMaskFlipsInTheSameStep() {
        for (async in listOf(false, true)) {
            val c = kit.controller()
            val layer = c.vec
            c.vectors.addObjects(layer, listOf(grainStroke(40f, 60f, 300f, 200f)), "Add")
            c.addMask(layer, fromSelection = false)
            c.setEditingMask(layer, false)
            Canvas(layer.mask!!).drawRect(0f, 0f, 100f, kit.h.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
            val mask = kit.pixels(layer.mask!!)
            flipAndCheck(c, async)
            assertNotNull(layer.mask)
            assertArrayEquals("redo: the mask is mirrored", mirrored(mask), kit.pixels(layer.mask!!))
            c.undo()
            assertArrayEquals("undo: the mask is back", mask, kit.pixels(layer.mask!!))
        }
    }

    @Test
    fun aLayerWhoseBrushesMirrorIntoThemselvesFlipsItsPixelsExactly() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(40f, 60f, 300f, 200f), kit.box(320f, 40f, 420f, 150f)), "Add")
        val before = layer.vector!!
        val pixels = kit.pixels(layer.bitmap)
        c.flipLayer(layer, horizontal = true)
        assertEquals(VectorLayerOps.flipped(before, kit.w, kit.h, horizontal = true), layer.vector)
        assertArrayEquals(mirrored(pixels), kit.pixels(layer.bitmap))
        c.undo()
        assertArrayEquals(pixels, kit.pixels(layer.bitmap))
    }
}
