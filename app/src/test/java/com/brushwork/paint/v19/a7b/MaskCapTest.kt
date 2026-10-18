package com.brushwork.paint.v19.a7b

import android.content.Context
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentLayerOps
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
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

    /**
     * The lead at A7b's merge: the two other ways a mask is made check the room too, the layer
     * window's "Add mask" on a layer whose mask was turned off (`LayerOps.addEnabledMask`) and an
     * editable mask made by the Mask tool or "Add gradient mask…" (`MaskEdits.addSpecMask`).
     */
    @Test
    fun aTurnedOffLayerMaskAndAnEditableMaskNeedRoomToo() {
        val c = Smoke.controller(app, Smoke.document(64, 64, layers = 99))
        assumeTrue("the heap allows the 100-layer cap (${c.maxLayers})", c.maxLayers == 100)
        val layers = c.doc.layers
        c.addMask(layers[0], fromSelection = false)
        assertEquals(100, c.effectiveLayerCount)
        val spec = MaskSpec(components = listOf(RadialMask(1, cx = 32f, cy = 32f, rx = 20f, ry = 16f, feather = 0.5f)), nextId = 2)
        val steps = c.undoManager.undoCount

        layers[1].maskEnabled = false
        c.message = null
        LayerOps.addMask(c, layers[1], fromSelection = false)
        assertNull("no mask at the cap", layers[1].mask)
        assertFalse("still turned off", layers[1].maskEnabled)
        assertEquals(c.layerLimitMessage(), c.message)
        assertEquals("no step", steps, c.undoManager.undoCount)

        c.message = null
        assertFalse(MaskEdits.apply(c, layers[2], spec, "Add gradient mask"))
        assertNull("no editable mask at the cap", layers[2].mask)
        assertNull(layers[2].maskSpec)
        assertEquals(c.layerLimitMessage(), c.message)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals(100, c.effectiveLayerCount)

        // With room again each is made, as one step, and counts.
        c.deleteMask(layers[0])
        LayerOps.addMask(c, layers[1], fromSelection = false)
        assertNotNull(layers[1].mask)
        assertTrue("turned on in the same step", layers[1].maskEnabled)
        assertEquals(100, c.effectiveLayerCount)
        c.deleteMask(layers[1])
        assertTrue(MaskEdits.apply(c, layers[2], spec, "Add gradient mask"))
        assertNotNull(layers[2].mask)
        assertEquals(spec, layers[2].maskSpec)
        assertEquals(100, c.effectiveLayerCount)
    }

    /** The lead at A7b's merge: a duplicated layer or folder brings its masks, which need room too. */
    @Test
    fun duplicatingALayerOrAFolderCountsTheCopiesMasks() {
        val c = Smoke.controller(app, Smoke.document(64, 64, layers = 98))
        assumeTrue("the heap allows the 100-layer cap (${c.maxLayers})", c.maxLayers == 100)
        val layers = c.doc.layers
        c.addMask(layers[0], fromSelection = false)
        assertEquals(99, c.effectiveLayerCount)
        assertTrue("one room left", c.canAddLayer)
        val steps = c.undoManager.undoCount

        // The layer and its mask would need two.
        c.message = null
        assertNull(c.duplicateLayer(layers[0]))
        assertEquals(c.layerLimitMessage(), c.message)
        assertEquals(98, c.doc.layers.size)
        assertEquals("no step", steps, c.undoManager.undoCount)

        // So would a folder holding it.
        val folder = c.putInNewFolder(layers[0])!!
        val inFolder = c.undoManager.undoCount
        c.message = null
        assertNull(c.duplicateLayer(folder))
        assertEquals(c.layerLimitMessage(), c.message)
        assertEquals(99, c.doc.layers.size)
        assertEquals("no step", inFolder, c.undoManager.undoCount)
        assertEquals(99, c.effectiveLayerCount)

        // A layer without a mask takes the last room.
        assertNotNull(c.duplicateLayer(c.doc.layers.first { it.mask == null && !it.isFolder }))
        assertEquals(100, c.effectiveLayerCount)
    }

    /** The lead at A7b's merge: an array's room check counts the layer masks, as `effectiveLayerCount` does. */
    @Test
    fun anArraysRoomCheckCountsTheMasks() {
        val c = Smoke.controller(app, Smoke.document(64, 64, layers = 99))
        assumeTrue("the heap allows the 100-layer cap (${c.maxLayers})", c.maxLayers == 100)
        assertTrue("room for one more layer", ArraySources.hasRoom(c, 1, 0L))
        c.addMask(c.doc.layers[0], fromSelection = false)
        assertEquals(100, c.effectiveLayerCount)
        assertFalse("the mask took the last room", ArraySources.hasRoom(c, 1, 0L))
        assertTrue("an array without new layers or pixels still fits", ArraySources.hasRoom(c, 0, 0L))
        c.deleteMask(c.doc.layers[0])
        assertTrue(ArraySources.hasRoom(c, 1, 0L))
    }

    /** The lead at A7b's merge: an import counts the masks it brings (an adjustment layer's two rooms already hold its own). */
    @Test
    fun anImportCountsTheMasksItBrings() {
        val props = LayerProps("A", 1f, LayerBlendMode.NORMAL, true, false, false, false, true)
        val raster = PayloadLayer(1, props)
        val masked = PayloadLayer(2, props, hasMask = true)
        val adjustment = PayloadLayer(3, props, PayloadKind.ADJUSTMENT, adjustment = AdjustmentSpec())
        val maskedAdjustment = PayloadLayer(4, props, PayloadKind.ADJUSTMENT, hasMask = true, adjustment = AdjustmentSpec())
        assertEquals(emptyList<PayloadLayer>(), PayloadImport.fitting(listOf(masked), 1))
        assertEquals(listOf(masked), PayloadImport.fitting(listOf(masked, raster), 2))
        assertEquals(listOf(masked, raster), PayloadImport.fitting(listOf(masked, raster), 3))
        assertEquals(listOf(maskedAdjustment), PayloadImport.fitting(listOf(maskedAdjustment, raster), 2))
        assertEquals(listOf(maskedAdjustment, raster), PayloadImport.fitting(listOf(maskedAdjustment, raster), 3))
        assertEquals("an adjustment layer without a mask takes one of its two", listOf(adjustment, raster), PayloadImport.fitting(listOf(adjustment, raster), 2))
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
