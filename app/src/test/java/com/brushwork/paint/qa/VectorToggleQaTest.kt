package com.brushwork.paint.qa

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.vector.VStroke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA (v1.5 §4.9, §7 checklist 1): the Vector button on a new canvas, as a user meets it. On
 * converts the empty "Layer 1" in place; off goes back to a raster layer; on again goes back to
 * the SAME vector layer (never a second one, never the Background converted), with no undo step;
 * undoing the conversion drops back to raster mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorToggleQaTest {
    private var rig: VectorQaRig? = null

    @After
    fun tearDown() { rig?.close() }

    private fun newRig(background: Int?) = VectorQaRig(background = background).also { rig = it }

    private fun toggleOnOffOnReusesTheVectorLayer(background: Int?) {
        val r = newRig(background)
        val c = r.c
        assertEquals(listOf("Background", "Layer 1"), c.doc.layers.map { it.name })
        val layer1 = c.activeLayer
        assertFalse(c.isVectorMode)
        c.toggleVectorMode()
        r.checkpoint("Vector on (convert Layer 1)")
        assertEquals("Convert to vector layer", c.undoManager.undoLabel)
        assertSame("converted in place", layer1, c.activeLayer)
        assertEquals("Vector 1", layer1.name)
        assertTrue(c.isVectorMode)
        assertEquals(2, c.doc.layers.size)
        r.tool(ToolId.BRUSH)
        c.brush = c.brush.copy(size = 10f)
        r.stroke(100f to 100f, 300f to 160f, 500f to 120f)
        r.checkpoint("a stroke")
        assertTrue(layer1.vector!!.objects.single() is VStroke)

        // Off: back to a raster layer (Layer 1 itself became the vector layer: the one below).
        c.toggleVectorMode()
        r.checkpoint("Vector off", steps = 0)
        assertFalse(c.isVectorMode)
        assertEquals("Background", c.activeLayer.name)
        assertNull(c.activeLayer.vector)

        // On again: the vector layer the user just left, not a new one, not a converted Background.
        c.toggleVectorMode()
        r.checkpoint("Vector on again", steps = 0)
        assertTrue(c.isVectorMode)
        assertSame("the same vector layer again", layer1, c.activeLayer)
        assertEquals(listOf("Background", "Vector 1"), c.doc.layers.map { it.name })
        assertNull("the Background stays a raster layer", c.doc.layers[0].vector)
        assertEquals(1, layer1.vector!!.objects.size)
    }

    @Test
    fun whiteCanvasVectorOffAndOnAgainReusesTheVectorLayer() = toggleOnOffOnReusesTheVectorLayer(0xFFFFFFFF.toInt())

    @Test
    fun transparentCanvasVectorOffAndOnAgainReusesTheVectorLayer() = toggleOnOffOnReusesTheVectorLayer(null)

    @Test
    fun undoingTheConversionDropsBackToRasterMode() {
        val r = newRig(0xFFFFFFFF.toInt())
        val c = r.c
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        assertTrue(c.isVectorMode)
        r.undoAndCheck("undo convert")
        assertFalse(c.isVectorMode)
        assertEquals("Layer 1", c.activeLayer.name)
        assertNull(c.activeLayer.vector)
        r.redoAndCheck("redo convert")
        assertTrue(c.isVectorMode)
        assertEquals("Vector 1", c.activeLayer.name)
    }

    @Test
    fun aPaintedLayerGetsAVectorLayerAboveAndTheToggleGoesBackAndForth() {
        val r = newRig(0xFFFFFFFF.toInt())
        val c = r.c
        r.tool(ToolId.BRUSH)
        c.brush = c.brush.copy(size = 10f)
        r.stroke(100f to 300f, 400f to 320f)
        r.checkpoint("raster stroke")
        val raster = c.activeLayer
        c.toggleVectorMode()
        r.checkpoint("Vector on (add)")
        assertEquals("Add vector layer", c.undoManager.undoLabel)
        val vec = c.activeLayer
        assertEquals("Vector 1", vec.name)
        assertEquals(listOf("Background", "Layer 1", "Vector 1"), c.doc.layers.map { it.name })
        r.stroke(100f to 100f, 400f to 120f)
        r.checkpoint("vector stroke")
        c.toggleVectorMode()
        r.checkpoint("off", steps = 0)
        assertSame("back to the layer it came from", raster, c.activeLayer)
        c.toggleVectorMode()
        r.checkpoint("on", steps = 0)
        assertSame("the vector layer right above is reused", vec, c.activeLayer)
        assertEquals(3, c.doc.layers.size)
    }
}
