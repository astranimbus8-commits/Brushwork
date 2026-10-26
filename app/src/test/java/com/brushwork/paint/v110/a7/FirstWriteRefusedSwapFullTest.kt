package com.brushwork.paint.v110.a7

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.EngineLabels110
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 A7 step 2 (design 2.13): at FULL with the swap store and R both full, the first write to a
 * blank or parked layer is refused before any pixel changes (no undo step, no partial stroke, the
 * store and `pixelGeneration` unchanged), and adding a layer says "Not enough free storage for
 * more layers". The refused write's text at FULL is the same ([LayerBudgetV110.firstWriteRefusal]),
 * and `EditorController` shows it (its two first-write sites, the A7 merge's lead commit).
 *
 * The session is the T606 at FULL with a store smaller than one layer and a residency that parks
 * and swaps nothing ([Quiet]).
 */
@RunWith(RobolectricTestRunner::class)
class FirstWriteRefusedSwapFullTest {
    private val swap = FakeSwapStore(capacityBytes = 64L shl 10)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), residency = Quiet(), swap = swap)

    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null
    private var filled = 0L
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        empty()
        controller?.dispose()
        scope?.cancel()
        idle()
        made.forEach { it.recycle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(top: (Document) -> Layer): Pair<EditorController, Document> {
        val doc = Smoke.document(256, 256, layers = 1)
        doc.layers[0].bitmap.eraseColor(0xFF406020.toInt())
        doc.layers += top(doc)
        doc.activeLayerIndex = 1
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        idle()
        return c to doc
    }

    /** Fills R so that no layer can be admitted and the tile RAM is 0. */
    private fun fill() {
        val b = EngineConfig.budget
        filled = (b.nativeBudget - (b.ledger.native - b.ledger.of(LedgerCategory.TILES))).coerceAtLeast(0L)
        b.ledger.add(LedgerCategory.TRANSIENT, filled)
    }

    private fun empty() {
        if (filled > 0L) EngineConfig.budget.ledger.sub(LedgerCategory.TRANSIENT, filled)
        filled = 0L
    }

    private fun stroke(c: EditorController) {
        c.pointerDown(ToolPoint(30f, 200f))
        for (i in 1..12) c.pointerMove(ToolPoint(30f + 15f * i, 200f - 12f * i))
        c.pointerUp(ToolPoint(210f, 56f))
        idle()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun withSwapAndRFullABlankLayersFirstWriteIsRefused() {
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Blank", BlankPixels(256, 256)) }
        val layer = doc.layers[1]
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        val bottom = pixels(doc.layers[0].bitmap)
        assertEquals(EngineLabels110.STORAGE_FULL, LayerBudgetV110.firstWriteRefusal())
        fill()
        assertFalse(swap.canWrite(doc.layerBytes))
        c.message = null
        stroke(c)
        assertEquals("the storage refusal is said", EngineLabels110.STORAGE_FULL, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ResidencyState.BLANK, layer.residency)
        assertEquals(generation, layer.pixelGeneration)
        assertTrue(bottom.contentEquals(pixels(doc.layers[0].bitmap)))
        assertEquals(0, swap.writes)
        // Adding a layer stops for storage, not at a number.
        val layers = doc.layers.size
        c.message = null
        assertNull(c.addLayer())
        assertEquals(EngineLabels110.STORAGE_FULL, c.message)
        assertEquals(layers, doc.layers.size)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(LayerBudget.MAX_LAYERS_GUARD, c.maxLayers)
        // With room in R both work again.
        empty()
        stroke(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ResidencyState.FLAT, layer.residency)
        assertNotNull(c.addLayer())
    }

    @Test
    fun withSwapAndRFullAParkedLayerKeepsItsPixels() {
        val painted = Smoke.document(256, 256, layers = 1).layers[0].bitmap.also { made += it }
        painted.eraseColor(0xC0108040.toInt())
        val before = pixels(painted)
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Parked", TiledPixels.fromBitmap(painted)) }
        val layer = doc.layers[1]
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        fill()
        c.message = null
        stroke(c)
        assertEquals(EngineLabels110.STORAGE_FULL, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ResidencyState.PARKED, layer.residency)
        assertEquals(generation, layer.pixelGeneration)
        empty()
        assertTrue(before.contentEquals(pixels(layer.bitmap)))
        assertEquals(generation, layer.pixelGeneration)
    }

    /** A residency that parks, swaps and frees nothing. */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) = Unit

        override fun idleTick() = Unit
    }
}
