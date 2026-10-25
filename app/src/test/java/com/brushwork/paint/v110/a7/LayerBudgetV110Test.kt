package com.brushwork.paint.v110.a7

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.PixelLedger
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.common.LayersLabels110
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A7 step 2 (design 2.4, 2.13 and 2.15, J3, I32): `LayerBudgetV110`'s limits and refusals per
 * level. Unknown memory gives every member of `LayerBudgetV19`; with R = 1100 MB and J = 512 MiB
 * (the T606 estimate of section 2.15) CLASSIC holds 84 phone layers, TILES 235, FULL with swap
 * only the 10,000 guard until neither R nor the store has room ("Not enough free storage for more
 * layers"), and FULL without swap TILES' count. Counts stay in v1.9's units (masks and array
 * sources count; blank layers count as layers and cost 0 bytes).
 *
 * The session of [engine] is FULL on the T606 with [swap], which the FULL budgets below read as
 * the session's store; the other budgets are built over their own ledgers. The documents' layers
 * are blank (no bitmap), except the few 256² ones of the canvas operation case.
 */
@RunWith(RobolectricTestRunner::class)
class LayerBudgetV110Test {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), swap = swap)

    private val made = mutableListOf<Bitmap>()

    @After
    fun recycle() {
        made.forEach { it.recycle() }
    }

    private fun budget(level: EngineLevel, r: Long = R, ledger: PixelLedger = PixelLedger()) =
        LayerBudgetV110(MemoryBudget(Budgets(J, r, known = true, stepDown = 0), ledger), level)

    private fun unknown(level: EngineLevel) =
        LayerBudgetV110(MemoryBudget(Budgets.v19(Runtime.getRuntime().maxMemory()), PixelLedger()), level)

    /** A [w] x [h] document of [layers] blank layers, the first [masks] of them masked with a blank mask. */
    private fun doc(w: Int, h: Int, layers: Int, masks: Int = 0): Document = Document("b", "Budget", w, h).apply {
        repeat(layers) { i ->
            this.layers += Layer(newLayerId(), "L$i", BlankPixels(w, h)).also { if (i < masks) it.maskPixels = BlankPixels(w, h) }
        }
    }

    private fun painted(d: Document): Layer {
        val b = BitmapUtils.createLayerBitmap(d.width, d.height).also { made += it }
        b.eraseColor(0x80336699.toInt())
        return Layer(d.newLayerId(), "Painted", b).also { d.layers += it }
    }

    @Test
    fun unknownMemoryGivesV19InEveryMember() {
        val docs = listOf(doc(PHONE_W, PHONE_H, 3, masks = 1), doc(2048, 2048, 20), doc(256, 256, 0))
        val sizes = listOf(PHONE_W to PHONE_H, 2048 to 2048, 4000 to 5000, 6000 to 6000, 10_001 to 10, 0 to 5, 256 to 256)
        for (level in EngineLevel.entries) {
            val b = unknown(level)
            val v = LayerBudgetV19
            for (d in docs) {
                assertEquals("$level used", v.used(d), b.used(d))
                assertEquals("$level maxLayers", v.maxLayers(d), b.maxLayers(d))
                for (n in listOf(0, 1, 2, 50)) for (extra in listOf(-1L, 0L, 1L shl 20)) {
                    assertEquals("$level canAdd $n $extra", v.canAdd(d, n, extra), b.canAdd(d, n, extra))
                }
                assertEquals(v.room(d), b.room(d))
                assertEquals(v.canAddAdjustment(d), b.canAddAdjustment(d))
                assertEquals(v.refusal(d), b.refusal(d))
                assertEquals(v.refusal(d, "x"), b.refusal(d, "x"))
            }
            // v1.9's canvas check reads every bitmap (a blank layer materializes): a small document.
            val small = doc(256, 256, 2, masks = 1)
            for ((w, h) in listOf(300 to 200, 10_001 to 200, 0 to 1)) {
                assertEquals("$level canTransformCanvas ${w}x$h", v.canTransformCanvas(small, w, h), b.canTransformCanvas(small, w, h))
            }
            for ((w, h) in sizes) {
                assertEquals("$level ${w}x$h raw", v.rawMaxLayers(w, h), b.rawMaxLayers(w, h))
                assertEquals(v.canvasFits(w, h), b.canvasFits(w, h))
                assertEquals(v.canvasFits(w, h, 7), b.canvasFits(w, h, 7))
                assertEquals(v.canvasCheck(w, h), b.canvasCheck(w, h))
                assertEquals(v.importMaxSide(w, h), b.importMaxSide(w, h))
                assertEquals(v.capacity(w, h), b.capacity(w, h))
            }
            assertEquals(v.canvasBudgetBytes(), b.canvasBudgetBytes())
            assertEquals(v.renderCacheCap(), b.renderCacheCap())
        }
    }

    @Test
    fun classicHoldsItsCountAndSaysTheV19Text() {
        val b = budget(EngineLevel.CLASSIC)
        val d = doc(PHONE_W, PHONE_H, 83)
        assertEquals(84, b.maxLayers(d))
        assertEquals(83, b.used(d))
        assertTrue(b.canAdd(d))
        assertEquals(1, b.room(d))
        assertFalse("an adjustment layer needs two", b.canAddAdjustment(d))
        d.layers += Layer(d.newLayerId(), "last", BlankPixels(PHONE_W, PHONE_H))
        assertFalse(b.canAdd(d))
        assertEquals(0, b.room(d))
        assertEquals("Layer limit reached (84) for this canvas size", b.refusal(d))
        assertEquals("Layer limit reached (84) for this canvas size: delete one", b.refusal(d, "delete one"))
        assertEquals(84, b.capacity(PHONE_W, PHONE_H))
    }

    @Test
    fun masksAndArraySourcesCountAsInV19() {
        val b = budget(EngineLevel.CLASSIC)
        val d = doc(PHONE_W, PHONE_H, 40, masks = 40)
        assertEquals("a mask counts as a layer", 80, b.used(d))
        assertEquals(4, b.room(d))
        assertTrue(b.canAdd(d, 4))
        assertFalse(b.canAdd(d, 5))
        // Array source bytes count by the layer they would fill.
        assertTrue(b.canAdd(d, 0, 4 * d.layerBytes))
        assertFalse(b.canAdd(d, 0, 4 * d.layerBytes + 1))
    }

    @Test
    fun tilesHoldsSection215sCounts() {
        val b = budget(EngineLevel.TILES)
        assertEquals(235, b.maxLayers(doc(PHONE_W, PHONE_H, 0)))
        assertEquals(139, b.maxLayers(doc(2048, 2048, 0)))
        assertEquals(16, b.maxLayers(doc(4000, 5000, 0)))
        assertEquals(2, b.maxLayers(doc(6000, 6000, 0)))
        assertEquals(235, b.capacity(PHONE_W, PHONE_H))
        val d = doc(PHONE_W, PHONE_H, 235)
        assertFalse(b.canAdd(d))
        assertEquals("Layer limit reached (235) for this canvas size", b.refusal(d))
    }

    @Test
    fun tilesNeverHoldsFewerThanClassic() {
        for (r in listOf((J * 0.55).toLong(), 512L shl 20, R, 2L shl 30)) {
            for ((w, h) in listOf(PHONE_W to PHONE_H, 2048 to 2048, 4000 to 5000, 6000 to 6000, 64 to 64)) {
                val l = w.toLong() * h * 4
                val classic = LayerBudgetV110.classicMaxLayers(r, J, l)
                val tiles = LayerBudgetV110.tilesMaxLayers(r, J, l)
                assertTrue("${w}x$h at $r: $tiles < $classic", tiles >= classic)
                assertTrue(tiles <= LayerBudget.MAX_LAYERS_GUARD)
                assertEquals(classic, budget(EngineLevel.CLASSIC, r).maxLayers(doc(w, h, 0)))
                assertEquals(tiles, budget(EngineLevel.TILES, r).maxLayers(doc(w, h, 0)))
            }
        }
    }

    @Test
    fun fullWithSwapHasOnlyTheGuardUntilStorageRunsOut() {
        val ledger = PixelLedger()
        val b = budget(EngineLevel.FULL, ledger = ledger)
        val d = doc(PHONE_W, PHONE_H, 500)
        assertEquals(LayerBudget.MAX_LAYERS_GUARD, b.maxLayers(d))
        assertTrue(b.canAdd(d))
        assertTrue(b.canAddAdjustment(d))
        assertEquals(LayerBudget.MAX_LAYERS_GUARD - 500, b.room(d))
        // R full and a store with less free space than one layer: adding stops for storage.
        ledger.add(LedgerCategory.TRANSIENT, R)
        assertTrue("the store still has room", b.canAdd(d))
        swap.capacityBytes = 1L shl 20
        assertFalse(b.canAdd(d))
        assertFalse(b.canAddAdjustment(d))
        assertEquals(0, b.room(d))
        assertEquals(EngineLabels110.STORAGE_FULL, b.refusal(d))
        assertEquals(EngineLabels110.STORAGE_FULL + ": x", b.refusal(d, "x"))
        // Room in R alone is enough again.
        ledger.sub(LedgerCategory.TRANSIENT, R)
        assertTrue(b.canAdd(d))
    }

    @Test
    fun fullWithoutSwapKeepsTilesCount() {
        swap.capacityBytes = 0L
        val b = budget(EngineLevel.FULL)
        val d = doc(PHONE_W, PHONE_H, 235)
        assertEquals(235, b.maxLayers(d))
        assertFalse(b.canAdd(d))
        assertEquals("Layer limit reached (235) for this canvas size", b.refusal(d))
        // The gallery's FULL has no store either: no fixed number there.
        assertNull(b.capacity(PHONE_W, PHONE_H))
        assertEquals(LayerBudget.MAX_LAYERS_GUARD.toLong(), b.rawMaxLayers(PHONE_W, PHONE_H))
    }

    @Test
    fun theStructuralGuardStopsEveryLevel() {
        // A tiny canvas reaches the guard by its count at CLASSIC and TILES.
        for (level in EngineLevel.entries) {
            val b = budget(level, r = 2L shl 30)
            val d = doc(16, 16, LayerBudget.MAX_LAYERS_GUARD - 1)
            assertEquals("$level", LayerBudget.MAX_LAYERS_GUARD, b.maxLayers(d))
            assertTrue("$level", b.canAdd(d))
            assertFalse("$level: two would pass the guard", b.canAdd(d, 2))
            assertFalse("$level", b.canAddAdjustment(d))
            // The guard refused the adjustment layer's two, not storage (FULL has the store's room).
            assertEquals("$level: the adjustment layer's refusal", LayersLabels110.GUARD, b.refusal(d))
            d.layers += Layer(d.newLayerId(), "last", BlankPixels(16, 16))
            assertFalse("$level", b.canAdd(d))
            assertEquals("$level", 0, b.room(d))
            assertEquals("$level", LayersLabels110.GUARD, b.refusal(d))
            assertEquals("$level", LayersLabels110.GUARD + ": x", b.refusal(d, "x"))
        }
    }

    @Test
    fun atFullWithSwapTheGuardIsSaidUnlessStorageIsWhatRanOut() {
        val ledger = PixelLedger()
        val b = budget(EngineLevel.FULL, ledger = ledger)
        val d = doc(PHONE_W, PHONE_H, LayerBudget.MAX_LAYERS_GUARD - 1)
        // R and the store both full at 9,999 layers: storage is what stops the next layer.
        ledger.add(LedgerCategory.TRANSIENT, R)
        swap.capacityBytes = 1L
        try {
            assertFalse(b.canAdd(d))
            assertEquals(EngineLabels110.STORAGE_FULL, b.refusal(d))
            // At 10,000 the guard is the reason, full store or not.
            d.layers += Layer(d.newLayerId(), "last", BlankPixels(PHONE_W, PHONE_H))
            assertFalse(b.canAdd(d))
            assertEquals(LayersLabels110.GUARD, b.refusal(d))
            assertEquals(LayersLabels110.GUARD + ": x", b.refusal(d, "x"))
        } finally {
            swap.capacityBytes = 256L shl 20
            ledger.sub(LedgerCategory.TRANSIENT, R)
        }
        // With room again, 10,000 layers still say the guard.
        assertEquals(LayersLabels110.GUARD, b.refusal(d))
    }

    @Test
    fun cachesAndCanvasBudgetsReadR() {
        val ledger = PixelLedger()
        assertEquals(R / 16, budget(EngineLevel.CLASSIC).renderCacheCap())
        assertEquals(R / 16, budget(EngineLevel.FULL).renderCacheCap())
        assertEquals(R - LayerBudgetV110.RESERVE_BYTES, budget(EngineLevel.CLASSIC).canvasBudgetBytes())
        assertEquals("never below v1.9's 0.55 J", (J * 0.55).toLong(), budget(EngineLevel.CLASSIC, r = (J * 0.55).toLong()).canvasBudgetBytes())
        val tiles = budget(EngineLevel.TILES, ledger = ledger)
        ledger.add(LedgerCategory.DISPLAY, 100L shl 20)
        assertEquals(R - (100L shl 20), tiles.canvasBudgetBytes())
        assertEquals("FULL adds the store's free bytes", R + swap.capacityBytes, budget(EngineLevel.FULL).canvasBudgetBytes())
    }

    @Test
    fun aCanvasOperationAtTilesNeedsTwiceTheLayersInTileRam() {
        val ledger = PixelLedger()
        val d = Document("c", "Canvas", 256, 256)
        painted(d)
        val layerBytes = d.layerBytes
        // Tile RAM of 3 layers: one painted layer (2 x 1) fits, two (2 x 2) do not.
        ledger.add(LedgerCategory.TRANSIENT, R - 3 * layerBytes)
        val tiles = budget(EngineLevel.TILES, ledger = ledger)
        assertNull(tiles.canTransformCanvas(d, 300, 200))
        painted(d)
        assertEquals(EngineLabels110.CANVAS_OP_TOO_BIG, tiles.canTransformCanvas(d, 300, 200))
        // Blank layers add nothing.
        repeat(5) { d.layers += Layer(d.newLayerId(), "blank", BlankPixels(256, 256)) }
        d.layers.removeAt(1)
        assertNull(tiles.canTransformCanvas(d, 300, 200))
        // The size rules come first; FULL with swap checks only them.
        assertEquals("Each side can be at most 10000 px.", tiles.canTransformCanvas(d, 10_001, 200))
        painted(d)
        val full = budget(EngineLevel.FULL, ledger = ledger)
        assertNull(full.canTransformCanvas(d, 300, 200))
        assertEquals("Each side can be at most 10000 px.", full.canTransformCanvas(d, 10_001, 200))
        // CLASSIC checks every bitmap against R less the reserve, as v1.9 did against 0.55 J:
        // 3 x 4000² (192 MB) fit in 916 MB, 3 x 10,000² (1.2 GB) do not.
        val classic = budget(EngineLevel.CLASSIC)
        val flat = Document("f", "Flat", 256, 256).apply { repeat(3) { painted(this) } }
        assertNull(classic.canTransformCanvas(flat, 4000, 4000))
        assertTrue(classic.canTransformCanvas(flat, 10_000, 10_000)!!.startsWith("Needs "))
    }

    @Test
    fun aRefusedFirstWriteSaysStorageOnlyAtFullWithSwap() {
        // The session is FULL with a store.
        assertEquals(EngineLabels110.STORAGE_FULL, LayerBudgetV110.firstWriteRefusal())
        swap.capacityBytes = 0L
        assertEquals(EngineLabels110.PAINT_REFUSED, LayerBudgetV110.firstWriteRefusal())
        swap.capacityBytes = 1L shl 20
        assertEquals(EngineLevel.FULL, EngineConfig.level)
        assertEquals(EngineLabels110.STORAGE_FULL, LayerBudgetV110.firstWriteRefusal())
    }

    @Test
    fun theSessionsBudgetIsAV110OnKnownMemory() {
        val current = LayerBudget.current
        assertTrue(current is LayerBudgetV110)
        assertTrue(EngineConfig.budget.budgets.known)
        // FULL with the session's store: no fixed limit.
        assertEquals(LayerBudget.MAX_LAYERS_GUARD, current.maxLayers(doc(PHONE_W, PHONE_H, 3)))
        assertNull(current.capacity(PHONE_W, PHONE_H))
    }

    private companion object {
        const val J = 512L shl 20
        const val R = 1_100_000_000L
        const val PHONE_W = 1080
        const val PHONE_H = 2408
    }
}
