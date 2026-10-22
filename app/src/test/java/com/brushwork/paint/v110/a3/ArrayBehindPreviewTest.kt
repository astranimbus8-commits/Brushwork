package com.brushwork.paint.v110.a3

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4, UQ5, A3 step 4): the Array tool on a copies-only layer. While a slider, field or
 * handle adjusts it the copies show IN FRONT of every layer (the tool's overlay), and at the
 * release they drop BEHIND the source (the tiles draw them at the layer's place while the commit
 * renders, then the layer's cache). The first time per install `ArrayLabels110.BEHIND_HINT` says
 * so. Leaving the tool (the sheet closed, another tool) selects the source again, once (lead's
 * choice); work on another layer ends that.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayBehindPreviewTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun arrayTool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /**
     * A vector layer with box A [20, 30, 60, 70] (gray) and box B [60, 30, 100, 70] (red), A's
     * copies made behind it: copy 1 sits exactly under B, copy 2 at [100, 140]. Returns the source
     * and the copies-only layer (active, the Array tool open on it).
     */
    private fun behindA(c: EditorController): Pair<Layer, Layer> {
        val v = c.addVectorLayer()!!
        val objects = listOf(ArrayLookKit.box(20f, 30f, 60f, 70f), ArrayLookKit.box(60f, 30f, 100f, 70f, fill = RED))
        c.vectors.update(v, VectorContent.EMPTY.plus(objects).first, "Add")
        ArrayLookKit.settle(c)
        val a = v.vector!!.objects[0].id
        c.vectors.setSelection(v, setOf(a))
        BehindKit.oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromObjectsBelow(c, setOf(a))) }
        val made = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals(ToolId.ARRAY, c.activeToolId)
        return v to made
    }

    /** What the canvas shows: the tiles' composite (with the render override) and the tool's overlay on top. */
    private fun screen(c: EditorController): Bitmap {
        val bmp = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        c.compositor.drawDocument(Canvas(bmp), null, target = CompositeTarget.identity(bmp))
        arrayTool(c).drawOverlay(Canvas(bmp), c.viewTransform)
        return bmp
    }

    // y = 33: inside the boxes, clear of the tool's arrow and handles (drawn along y = 50).
    private fun at(b: Bitmap, x: Int) = b.getPixel(x, 33)

    @Test
    fun copiesComeToTheFrontWhileAdjustedAndGoBehindAtTheRelease() {
        val c = ArrayLookKit.controller(app)
        val (_, made) = behindA(c)
        assertEquals("made behind: copy 1 is under box B", RED, at(screen(c), 80))
        assertEquals(GRAY, at(screen(c), 120))
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val tool = arrayTool(c)
        val spec = made.array!!.spec

        // A slider dragging: in front of every layer, box B included.
        tool.preview(spec.copy(count = 4))
        assertSame(made, c.renderOverride?.layer)
        var s = screen(c)
        assertEquals("dragging: copy 1 in front of box B", GRAY, at(s, 80))
        assertEquals(GRAY, at(s, 120))
        assertEquals("the new copy 3", GRAY, at(s, 160))

        // Released: the commit renders, its preview drawn in the tiles at the layer's place.
        tool.commitPreview()
        assertNotNull("the preview stays up while the commit renders", c.renderOverride)
        s = screen(c)
        assertEquals("released: copy 1 behind box B again", RED, at(s, 80))
        assertEquals(GRAY, at(s, 120))
        assertEquals(GRAY, at(s, 160))

        // Adjusted again before the render landed: in front again.
        tool.preview(spec.copy(count = 5))
        s = screen(c)
        assertEquals(GRAY, at(s, 80))
        assertEquals("copy 4", GRAY, at(s, 200))
        tool.commitPreview()
        ArrayLookKit.settle(c)
        assertNull("landed", c.renderOverride)
        assertEquals(5, made.array!!.spec.count)
        s = screen(c)
        assertEquals("the cache: behind box B", RED, at(s, 80))
        assertEquals(GRAY, at(s, 200))
    }

    /**
     * The selection bar's path (A3 review): a raster selection's pixels made behind their layer,
     * under a red box on a layer above where copy 1 lands. The pixel commit path (the cache
     * rendered by `ArrayRenders`, the preview re-shown until it lands) puts the copies behind too.
     */
    @Test
    fun pixelCopiesComeToTheFrontWhileAdjustedAndGoBehindAtTheRelease() {
        val c = ArrayLookKit.controller(app)
        val src = c.doc.layers[1]
        c.selectLayer(src)
        val above = c.addLayer()!!
        assertTrue("a layer above the source", c.doc.indexOf(above) > c.doc.indexOf(src))
        Canvas(above.bitmap).drawRect(60f, 30f, 100f, 70f, android.graphics.Paint().apply { color = RED })
        assertSame(src, c.doc.layers[1])
        BehindKit.selectedSquare(c)
        BehindKit.oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromSelectionBelow(c)) }
        val made = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals("directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertEquals("made behind: copy 1 is under the red box", RED, at(screen(c), 80))
        assertEquals("the source itself", GRAY, at(screen(c), 40))
        assertEquals(GRAY, at(screen(c), 120))

        // A slider dragging: in front of every layer (the red box included), the hint once.
        val tool = arrayTool(c)
        val spec = made.array!!.spec
        c.message = null
        tool.preview(spec.copy(count = 4))
        assertEquals(ArrayLabels110.BEHIND_HINT, c.message)
        assertSame("the tiles leave the layer to the overlay", made, c.renderOverride?.layer)
        var s = screen(c)
        assertEquals("dragging: copy 1 in front of the red box", GRAY, at(s, 80))
        assertEquals("the new copy 3", GRAY, at(s, 160))
        assertEquals("the source keeps its place", GRAY, at(s, 40))

        // Released: behind the red box again, at once and once the cache landed.
        tool.commitPreview()
        s = screen(c)
        assertEquals("released: copy 1 behind the red box", RED, at(s, 80))
        assertEquals(GRAY, at(s, 160))
        ArrayLookKit.settle(c)
        assertNull("landed", c.renderOverride)
        assertEquals(4, made.array!!.spec.count)
        s = screen(c)
        assertEquals("the cache: behind the red box", RED, at(s, 80))
        assertEquals(GRAY, at(s, 160))
        assertEquals("one step for the edit", com.brushwork.paint.ui.common.ArrayLabels.EDIT, c.undoManager.undoLabel)
    }

    @Test
    fun theHintShowsOncePerInstall() {
        val c = ArrayLookKit.controller(app)
        val (_, made) = behindA(c)
        val tool = arrayTool(c)
        val spec = made.array!!.spec
        assertTrue("not shown yet", !UiPrefs(app).hintArrayBehind)
        c.message = null
        tool.preview(spec.copy(count = 4))
        assertEquals(ArrayLabels110.BEHIND_HINT, c.message)
        assertTrue("remembered", UiPrefs(app).hintArrayBehind)
        tool.commitPreview()
        ArrayLookKit.settle(c)
        c.message = null
        tool.preview(spec.copy(count = 5))
        assertNull("once", c.message)
        tool.commitPreview()
        ArrayLookKit.settle(c)

        // Another session on an install that showed it: not again.
        val c2 = ArrayLookKit.controller(app)
        UiPrefs(app).hintArrayBehind = true
        val (_, made2) = behindA(c2)
        c2.message = null
        arrayTool(c2).preview(made2.array!!.spec.copy(count = 4))
        assertNull(c2.message)

        // A v1.9 array (its source shown) has nothing to explain.
        val c3 = ArrayLookKit.controller(app)
        val plain = ArrayLookKit.pixelArray(c3)
        c3.message = null
        arrayTool(c3).preview(plain.array!!.spec.copy(count = 4))
        assertNull(c3.message)
        assertTrue(!UiPrefs(app).hintArrayBehind)
    }

    @Test
    fun leavingTheToolSelectsTheSourceAgain() {
        val c = ArrayLookKit.controller(app)
        val (v, made) = behindA(c)
        val tool = arrayTool(c)

        // The sheet closed (✕, Back): the source.
        tool.closeSheet()
        assertSame(v, c.activeLayer)

        // Another tool, once the switch is done; an edit in between (a pause) keeps it.
        ArrayBehind.fromLayerBelow(c, v)
        ArrayLookKit.settle(c)
        val second = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(second))
        ArrayLookKit.edit(c, second, second.array!!.spec.copy(count = 4))
        assertSame("an edit (a pause of the tool) keeps the layer", second, c.activeLayer)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        c.selectTool(ToolId.BRUSH)
        ArrayLookKit.settle(c)
        assertSame(v, c.activeLayer)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        // Once: back on the copies-only layer, leaving it again stays there.
        c.selectLayer(second)
        c.selectTool(ToolId.ARRAY)
        c.selectTool(ToolId.BRUSH)
        ArrayLookKit.settle(c)
        assertSame(second, c.activeLayer)

        // Work on another layer ends it.
        c.selectLayer(v)
        c.vectors.setSelection(v, setOf(v.vector!!.objects[1].id))
        ArrayBehind.fromObjectsBelow(c, setOf(v.vector!!.objects[1].id))
        ArrayLookKit.settle(c)
        val third = c.activeLayer
        c.selectLayer(made)
        c.selectTool(ToolId.BRUSH)
        ArrayLookKit.settle(c)
        assertSame("another layer was chosen", made, c.activeLayer)
        assertTrue(c.doc.indexOf(third) >= 0)

        // "Remove array and its layer" on it: the source.
        c.selectLayer(v)
        c.vectors.setSelection(v, setOf(v.vector!!.objects[0].id))
        ArrayBehind.fromObjectsBelow(c, setOf(v.vector!!.objects[0].id))
        ArrayLookKit.settle(c)
        val fourth = c.activeLayer
        tool.removeArray()
        ArrayLookKit.settle(c)
        assertEquals(-1, c.doc.indexOf(fourth))
        assertSame(v, c.activeLayer)

        // The source gone meanwhile: the layer stays selected.
        val raster = BehindKit.selectedSquare(c)
        ArrayBehind.fromSelectionBelow(c)
        ArrayLookKit.settle(c)
        val fifth = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(fifth))
        assertTrue(c.structure.delete(raster, keepChildren = false, label = "Delete layer"))
        assertSame("the row below the deleted source", fifth, c.activeLayer)
        tool.closeSheet()
        assertSame(fifth, c.activeLayer)
    }

    @Test
    fun closingTheEditorOnALayerMadeBehindSelectsNothing() {
        val c = ArrayLookKit.controller(app)
        val (_, made) = behindA(c)
        val version = c.layersVersion
        // The editor closes with the Array tool on the new layer: its last onDeactivate posts the
        // leave, which must not select the source on a released controller.
        c.dispose()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertSame(made, c.activeLayer)
        assertEquals("no layer change after the close", version, c.layersVersion)
    }
}
