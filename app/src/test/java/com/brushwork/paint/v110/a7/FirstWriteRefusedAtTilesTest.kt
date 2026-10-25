package com.brushwork.paint.v110.a7

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
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
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.EngineLabels110
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 A7 step 2 (design 2.13): at TILES with R exhausted the first write to a blank or parked
 * layer is refused before any pixel changes: "Not enough memory to paint on this layer. Merge or
 * delete layers", no undo step, no partial stroke, the store and `pixelGeneration` unchanged, as a
 * locked layer is refused. Once R has room again the same stroke paints.
 *
 * The session is the T606 at TILES with a residency that parks nothing ([Quiet]), so the ledger
 * stays where the test puts it.
 */
@RunWith(RobolectricTestRunner::class)
class FirstWriteRefusedAtTilesTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = Quiet())

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

    /** A 256² document: a painted bottom layer and a top layer in [top], active. */
    private fun open(top: (Document) -> Layer): Pair<EditorController, Document> {
        val doc = Smoke.document(256, 256, layers = 1)
        doc.layers[0].bitmap.eraseColor(0xFF204060.toInt())
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
        c.pointerDown(ToolPoint(40f, 40f))
        for (i in 1..12) c.pointerMove(ToolPoint(40f + 14f * i, 40f + 10f * i))
        c.pointerUp(ToolPoint(208f, 160f))
        idle()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun aBlankLayersFirstWriteIsRefusedWhileRIsFull() {
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Blank", BlankPixels(256, 256)) }
        val layer = doc.layers[1]
        val bottom = pixels(doc.layers[0].bitmap)
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        val version = layer.contentVersion
        fill()
        c.message = null
        stroke(c)
        assertEquals(EngineLabels110.PAINT_REFUSED, c.message)
        assertEquals(LayerBudgetV110.firstWriteRefusal(), c.message)
        assertEquals("no undo step", steps, c.undoManager.undoCount)
        assertEquals(ResidencyState.BLANK, layer.residency)
        assertTrue(layer.pixels is BlankPixels)
        assertEquals(generation, layer.pixelGeneration)
        assertEquals(version, layer.contentVersion)
        assertTrue(bottom.contentEquals(pixels(doc.layers[0].bitmap)))
        // A whole-layer operation is refused the same way.
        c.message = null
        c.fillLayer(layer, 0xFFFF0000.toInt())
        assertEquals(EngineLabels110.PAINT_REFUSED, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ResidencyState.BLANK, layer.residency)
        // With room in R the stroke paints, as one step.
        empty()
        c.message = null
        stroke(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ResidencyState.FLAT, layer.residency)
        assertTrue("the stroke changed the content", layer.contentVersion > version)
    }

    @Test
    fun aParkedLayersWriteIsRefusedAndItsPixelsKept() {
        val painted = Smoke.document(256, 256, layers = 1).layers[0].bitmap.also { made += it }
        painted.eraseColor(0x80A03010.toInt())
        val before = pixels(painted)
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Parked", TiledPixels.fromBitmap(painted)) }
        val layer = doc.layers[1]
        assertEquals(ResidencyState.PARKED, layer.residency)
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        fill()
        c.message = null
        stroke(c)
        assertEquals(EngineLabels110.PAINT_REFUSED, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ResidencyState.PARKED, layer.residency)
        assertEquals(generation, layer.pixelGeneration)
        // Back in memory (no write) the pixels are those it had.
        empty()
        assertTrue(before.contentEquals(pixels(layer.bitmap)))
        assertEquals("a materialization is no new store", generation, layer.pixelGeneration)
    }

    /** A residency that parks nothing and frees nothing. */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) = Unit

        override fun idleTick() = Unit
    }
}
