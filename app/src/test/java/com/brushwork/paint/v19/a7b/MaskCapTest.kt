package com.brushwork.paint.v19.a7b

import android.content.Context
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentLayerOps
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.layers.LayerOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A7b step 6 (design 3.2 A7b, R9): a layer mask is a full-size ARGB bitmap, as much memory as a
 * layer, so `effectiveLayerCount` counts it (turned off or not) and `addMask` needs room like a
 * new layer: at the limit it says "Layer limit reached (n) for this canvas size" and changes
 * nothing. An artwork already over the limit still opens; only adding stops.
 */
@RunWith(RobolectricTestRunner::class)
class MaskCapTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aMaskCountsAsALayerTurnedOffOrNot() {
        val c = Smoke.controller(app, Smoke.document(64, 48, layers = 2))
        assertEquals(2, c.effectiveLayerCount)
        val layer = c.doc.layers[0]
        c.addMask(layer, fromSelection = false)
        assertNotNull(layer.mask)
        assertEquals(3, c.effectiveLayerCount)
        layer.maskEnabled = false
        assertEquals("a mask turned off still holds its memory", 3, c.effectiveLayerCount)
        c.undo()
        assertNull(layer.mask)
        assertEquals(2, c.effectiveLayerCount)
        c.redo()
        assertEquals(3, c.effectiveLayerCount)
        c.deleteMask(layer)
        assertEquals(2, c.effectiveLayerCount)
        Smoke.assertQuiet(c, "mask count")
    }

    @Test
    fun atTheLimitNoMaskIsAddedAndTheMessageSaysSo() {
        val c = Smoke.controller(app, Smoke.document(64, 64, layers = 99))
        assumeTrue("the heap allows the 100-layer cap (${c.maxLayers})", c.maxLayers == 100)
        val layers = c.doc.layers
        // The last room: the mask takes it.
        c.addMask(layers[0], fromSelection = false)
        assertNotNull(layers[0].mask)
        assertEquals(100, c.effectiveLayerCount)
        assertFalse(c.canAddLayer)
        val steps = c.undoManager.undoCount
        c.message = null

        c.addMask(layers[1], fromSelection = false)
        assertNull(layers[1].mask)
        assertEquals(c.layerLimitMessage(), c.message)
        assertTrue(c.message!!.startsWith("Layer limit reached (100)"))
        assertEquals("no step", steps, c.undoManager.undoCount)

        // The layer window's "Add mask" (mask turned on) goes through it too.
        c.message = null
        LayerOps.addMask(c, layers[2], fromSelection = false)
        assertNull(layers[2].mask)
        assertEquals(c.layerLimitMessage(), c.message)
        assertEquals(steps, c.undoManager.undoCount)

        // Deleting a mask makes room again.
        c.deleteMask(layers[0])
        assertEquals(99, c.effectiveLayerCount)
        c.addMask(layers[1], fromSelection = false)
        assertNotNull(layers[1].mask)
        assertEquals(100, c.effectiveLayerCount)
    }

    @Test
    fun anAdjustmentLayerFromASelectionTakesItsTwoRoomsAtOnce() {
        val tone = FilterRegistry.byId("adjust.tone")!!
        val values = tone.defaultValues().set("exposure", 1.1f)
        val c = Smoke.controller(app, Smoke.document(64, 64, layers = 98))
        assumeTrue("the heap allows the 100-layer cap (${c.maxLayers})", c.maxLayers == 100)
        val box = Path().apply { addRect(8f, 8f, 40f, 40f, Path.Direction.CW) }
        c.setSelection(Selection.fromPath(box, 64, 64))
        // The layer and its mask (the selection): the last two rooms.
        val made = AdjustmentLayerOps.fromFilter(c, tone, values)
        assertNotNull(made)
        assertNotNull("the selection became its mask", made!!.mask)
        assertEquals(100, c.effectiveLayerCount)

        // One room left: no adjustment layer (it would need two).
        c.deleteMask(made)
        assertEquals(99, c.effectiveLayerCount)
        c.setSelection(Selection.fromPath(box, 64, 64))
        c.message = null
        assertNull(AdjustmentLayerOps.fromFilter(c, tone, values))
        assertTrue("${c.message}", c.message?.startsWith("Layer limit reached") == true)
        assertEquals(99, c.effectiveLayerCount)
    }

    @Test
    fun anArtworkOverTheLimitStillOpensWithItsMasks() {
        val doc = Smoke.document(64, 64, layers = 100)
        doc.layers.take(10).forEach { it.mask = BitmapUtils.createMaskBitmap(64, 64) }
        val c = Smoke.controller(app, doc)
        assumeTrue("the heap allows the 100-layer cap (${c.maxLayers})", c.maxLayers == 100)
        assertEquals(100, c.doc.layers.size)
        assertEquals(10, c.doc.layers.count { it.mask != null })
        assertEquals(110, c.effectiveLayerCount)
        assertFalse(c.canAddLayer)
        c.message = null
        c.addMask(c.doc.layers[50], fromSelection = false)
        assertNull(c.doc.layers[50].mask)
        assertEquals(c.layerLimitMessage(), c.message)
    }
}
