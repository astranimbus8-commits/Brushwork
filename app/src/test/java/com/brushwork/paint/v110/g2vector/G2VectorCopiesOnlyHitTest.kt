package com.brushwork.paint.v110.g2vector

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
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

/**
 * v1.10 G2 (V6, A3 gaps): on a copies-only layer ("Array behind", `ArraySpec.hideSource`) the
 * hidden source objects are not hit by the vector tools while that layer is active: the hit test,
 * the object selection, the Curve tool's reopening tap and the Shape tool's opening tap find
 * nothing there (they still do on the source layer, after undo too). "Array behind this layer…"
 * on a whole vector layer whose render would hold the main thread past `VectorLayers`' budget
 * renders on a worker under the busy overlay: the same layer, pixels and ONE step as on the main
 * thread; Stop records nothing; a source changed meanwhile is made from what it holds then.
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorCopiesOnlyHitTest {
    private val kit = ObjectTestKit(512, 384)

    @After
    fun tearDown() {
        ArrayDraw.clearCaches()
        kit.close()
    }

    private fun EditorController.tap(p: Vec2) {
        pointerDown(ToolPoint(p.x, p.y))
        pointerUp(ToolPoint(p.x, p.y))
        kit.idle()
    }

    /** On the box's left line (a tap there reopens it in the Curve tool). */
    private val onBoxLine = Vec2(20f, 50f)

    /** Inside the ellipse shape object. */
    private val inShape = Vec2(300f, 200f)

    /** A controller whose active vector layer holds a box path and an ellipse shape. */
    private fun source(c: EditorController): Layer {
        val src = c.vec
        c.vectors.addObjects(src, listOf(kit.box(20f, 30f, 60f, 70f), kit.ellipse(300f, 200f)), "Add")
        kit.idle()
        return src
    }

    /** A heavier layer: [n] brush strokes and boxes over the document, so its render takes a while. */
    private fun heavySource(c: EditorController, n: Int = 120): Layer {
        val src = c.vec
        val objects = ArrayList<VObject>(n)
        for (k in 0 until n) {
            val x = 12f + (k % 12) * 26f
            val y = 12f + (k / 12) * 34f
            objects += if (k % 2 == 0) kit.stroke(x, y, x + 20f, y + 28f, seed = k.toLong()) else kit.box(x, y, x + 18f, y + 26f)
        }
        c.vectors.addObjects(src, objects, "Add")
        kit.idle()
        return src
    }

    private fun tol(c: EditorController) = c.docLength(8f)

    /** "Array behind" on [src]: ONE step [ArrayLabels110.ARRAY_BEHIND_STEP]; the copies-only layer made. */
    private fun behind(c: EditorController, src: Layer): Layer {
        val steps = c.undoManager.undoCount
        assertTrue(ArrayBehind.fromLayerBelow(c, src))
        kit.idle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels110.ARRAY_BEHIND_STEP, c.undoManager.undoLabel)
        val made = c.activeLayer
        assertTrue("copies only", ArrayBehind.isCopiesOnly(made))
        assertEquals("directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        return made
    }

    @Test
    fun theHiddenSourceIsNotHitWhileTheCopiesOnlyLayerIsActive() {
        val c = kit.controller()
        val src = source(c)
        assertTrue("the source's box is hit", c.vectors.hitTest(src, onBoxLine, tol(c)) is VPath)
        assertTrue("the source's shape is hit", c.vectors.hitTest(src, inShape, tol(c)) is VShape)
        val made = behind(c, src)
        assertEquals("the hidden source: both objects", 2, made.vector!!.objects.size)
        assertTrue(c.vectors.hidesObjects(made))
        assertFalse(c.vectors.hidesObjects(src))

        // The hit test: nothing on the copies-only layer, the source layer as before.
        assertNull("the hidden box", c.vectors.hitTest(made, onBoxLine, tol(c)))
        assertNull("the hidden shape", c.vectors.hitTest(made, inShape, tol(c)))
        assertNotNull("the source layer's box", c.vectors.hitTest(src, onBoxLine, tol(c)))

        // The object selection does not take it (the area becomes a pixel selection).
        assertFalse(c.vectors.selectObjects(kit.rectSelection(0, 0, 512, 384), SelectionMode.REPLACE))
        kit.idle()
        assertTrue("no object selected", c.vectors.selectedIds.isEmpty())

        // The Curve tool's tap does not reopen the hidden box (leaving the Array tool selects the
        // source again once the switch settles, so the copies-only row is selected back after it).
        val steps = c.undoManager.undoCount
        c.selectTool(ToolId.CURVE)
        kit.idle()
        c.selectLayer(made)
        kit.idle()
        assertSame("the copies-only layer is active", made, c.activeLayer)
        assertFalse("nothing open before the tap", (c.tools.getValue(ToolId.CURVE) as CurveTool).isReopened)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        c.tap(onBoxLine)
        assertFalse("the Curve tool reopened a hidden path", curve.isReopened)
        curve.discard()
        kit.idle()

        // The Shape tool's tap does not open the hidden ellipse.
        c.selectTool(ToolId.SHAPE)
        kit.idle()
        c.selectLayer(made)
        kit.idle()
        assertSame(made, c.activeLayer)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        c.tap(inShape)
        assertFalse("the Shape tool opened a hidden shape", shape.editingObject)
        shape.discard()
        kit.idle()
        assertEquals("the taps recorded nothing", steps, c.undoManager.undoCount)
        assertEquals("the hidden source is unchanged", 2, made.vector!!.objects.size)

        // Undo: the layer goes, the source is active, and its objects are hit by the same taps.
        c.undo()
        kit.idle()
        assertEquals(-1, c.doc.indexOf(made))
        assertSame(src, c.activeLayer)
        c.tap(inShape)
        assertTrue("the Shape tool opens the source's ellipse", shape.editingObject)
        shape.discard()
        kit.idle()
        c.selectTool(ToolId.CURVE)
        c.selectLayer(src)
        kit.idle()
        c.tap(onBoxLine)
        assertTrue("the Curve tool reopens the source's box", curve.isReopened)
        curve.discard()
        kit.idle()

        // Redo: the copies-only layer is back, and still hides its objects.
        c.redo()
        kit.idle()
        assertSame(made, c.activeLayer)
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertNull(c.vectors.hitTest(made, onBoxLine, tol(c)))
        assertNull(c.vectors.hitTest(made, inShape, tol(c)))
    }

    @Test
    fun aWholeVectorLayerGoesBehindFromAWorkerInOneStep() {
        val c = kit.controller()
        val src = heavySource(c)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        c.vectors.policy = VectorLayers.Policy.ASYNC
        assertTrue(ArrayBehind.fromLayerBelow(c, src))
        assertEquals("rendering under the busy overlay", VectorLayers.BUSY_LABEL, c.busyMessage)
        assertEquals("nothing made yet", layers, c.doc.layers.size)
        assertEquals("no step yet", steps, c.undoManager.undoCount)
        assertTrue("landed", kit.idleUntil { c.busyMessage == null })
        assertEquals(layers + 1, c.doc.layers.size)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels110.ARRAY_BEHIND_STEP, c.undoManager.undoLabel)
        val made = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals(c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertEquals("the source's objects", src.vector, made.vector)
        // The worker's pixels are the main thread's render of the copies.
        val ref = BitmapUtils.createLayerBitmap(512, 384)
        ArrayOps.renderTiles(Canvas(ref), ArrayDraw.effectiveVector(made.dataSnapshot())!!, c.doc.bounds, c.doc.bounds)
        assertArrayEquals("the copies' pixels", kit.pixels(ref), kit.pixels(made.bitmap))
        val drawn = kit.pixels(made.bitmap)
        assertTrue("copies drawn", drawn.any { it ushr 24 != 0 })

        c.undo()
        kit.idle()
        assertEquals(layers, c.doc.layers.size)
        assertSame(src, c.activeLayer)
        c.redo()
        kit.idle()
        assertSame(made, c.activeLayer)
        assertArrayEquals("redo: the same pixels", drawn, kit.pixels(made.bitmap))
        ref.recycle()
    }

    @Test
    fun stopRecordsNothingAndAChangedSourceIsMadeFromWhatItHolds() {
        val c = kit.controller()
        val src = heavySource(c)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        c.vectors.policy = VectorLayers.Policy.ASYNC
        assertTrue(ArrayBehind.fromLayerBelow(c, src))
        val stop = c.busyCancel
        assertNotNull("the overlay offers Stop", stop)
        stop!!()
        assertTrue(kit.idleUntil { c.busyMessage == null })
        assertEquals("stopped: no layer", layers, c.doc.layers.size)
        assertEquals("stopped: no step", steps, c.undoManager.undoCount)

        // The source changes while its copies render: the layer is made from what it holds then.
        assertTrue(ArrayBehind.fromLayerBelow(c, src))
        assertEquals(VectorLayers.BUSY_LABEL, c.busyMessage)
        c.vectors.policy = VectorLayers.Policy.SYNC
        c.vectors.addObjects(src, listOf(kit.box(400f, 300f, 440f, 340f)), "Add")
        assertTrue(kit.idleUntil { c.busyMessage == null })
        assertEquals("the edit and the array behind", steps + 2, c.undoManager.undoCount)
        assertEquals(ArrayLabels110.ARRAY_BEHIND_STEP, c.undoManager.undoLabel)
        val made = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals("the source as it is now", src.vector, made.vector)
        val ref = BitmapUtils.createLayerBitmap(512, 384)
        ArrayOps.renderTiles(Canvas(ref), ArrayDraw.effectiveVector(made.dataSnapshot())!!, c.doc.bounds, c.doc.bounds)
        assertArrayEquals(kit.pixels(ref), kit.pixels(made.bitmap))
        ref.recycle()
        c.undo()
        kit.idle()
        assertEquals(-1, c.doc.indexOf(made))
        assertEquals("the edit stays", steps + 1, c.undoManager.undoCount)
    }
}
