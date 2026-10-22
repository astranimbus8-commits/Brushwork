package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.2, J5, I28; lead, frozen): the [Layer] pixel facade. In Classic a layer made
 * from a bitmap is [FlatPixels] and every getter gives v1.9's bitmap, never materializing; a
 * folder is [FolderPixels] behind `Layer.FOLDER_BITMAP`. The `bitmap` and `mask` getters
 * materialize a blank or parked store once (counted, the same pixels, FLAT afterwards, the tiles
 * released, no generation bump), and under [PixelFacade.strict] only on the allow-list
 * (`paintTarget`). [Layer.hasMask], [Layer.owns], [Layer.admitWrite], the size and the pins
 * never materialize; a pin forbids park until it closes, a resident pin makes every tile
 * resident, and [Layer.recycleBitmaps] recycles a flat store and releases a tiled one.
 */
@RunWith(RobolectricTestRunner::class)
class FacadeContractTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled && it !== Layer.FOLDER_BITMAP) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun tiles() = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    @Test
    fun classicLayersAreFlatAndGiveV19sBitmaps() {
        val b = keep(F1Pixels.noise(300, 200, 1L))
        val m = keep(F1Pixels.grayNoise(300, 200, 2L))
        val l = Layer(1L, "L", b)
        val count = PixelFacade.materializations
        PixelFacade.strict = true
        assertTrue(l.pixels is FlatPixels)
        assertSame(b, (l.pixels as FlatPixels).bitmap)
        assertSame(b, l.bitmap)
        assertSame(b, l.paintTarget)
        assertNull(l.maskPixels)
        assertFalse(l.hasMask)
        assertNull(l.mask)
        l.mask = m
        assertTrue(l.hasMask)
        assertSame(m, l.mask)
        assertSame(m, (l.maskPixels as FlatPixels).bitmap)
        l.editingMask = true
        assertSame(m, l.paintTarget)
        assertEquals(300, l.width)
        assertEquals(200, l.height)
        assertEquals(ResidencyState.FLAT, l.residency)
        assertEquals("a flat layer never materializes", count, PixelFacade.materializations)
    }

    @Test
    fun aFolderIsFolderPixelsBehindTheSharedBitmap() {
        val f = Layer.newFolder(5L, "F")
        assertSame(FolderPixels, f.pixels)
        assertSame(Layer.FOLDER_BITMAP, f.bitmap)
        assertEquals(ResidencyState.FOLDER, f.residency)
        assertTrue(f.owns(Layer.FOLDER_BITMAP))
        val g = f.pixelGeneration
        f.bitmap = Layer.FOLDER_BITMAP
        assertEquals("the folder bitmap again is no replacement", g, f.pixelGeneration)
        assertSame(FolderPixels, Layer(6L, "G", Layer.FOLDER_BITMAP).pixels)
        f.recycleBitmaps()
        assertFalse("never recycled", Layer.FOLDER_BITMAP.isRecycled)
        assertTrue(f.admitWrite())
    }

    @Test
    fun aBlankStoreMaterializesOnceAsATransparentFlatBitmap() {
        val l = Layer(1L, "L", BlankPixels(300, 200))
        assertEquals(ResidencyState.BLANK, l.residency)
        assertEquals(300, l.width)
        assertEquals(200, l.height)
        assertFalse(l.owns(keep(BitmapUtils.createLayerBitmap(300, 200))))
        assertTrue("F1 admits every write", l.admitWrite())
        val count = PixelFacade.materializations
        val g = l.pixelGeneration
        assertEquals("size, owns and admitWrite never materialize", count, PixelFacade.materializations)
        val b = keep(l.bitmap)
        assertEquals(count + 1, PixelFacade.materializations)
        assertEquals(300, b.width)
        assertEquals(200, b.height)
        assertTrue(F1Pixels.pixels(b).all { it == 0 })
        assertEquals(ResidencyState.FLAT, l.residency)
        assertSame(b, (l.pixels as FlatPixels).bitmap)
        assertTrue(l.owns(b))
        assertSame("FLAT now: the same bitmap", b, l.bitmap)
        assertEquals(count + 1, PixelFacade.materializations)
        assertEquals("a materialization is no replacement (J5)", g, l.pixelGeneration)
    }

    @Test
    fun aParkedStoreMaterializesTheSamePixelsAndReleasesItsTiles() {
        val src = keep(F1Pixels.mixed(512, 512, 3L))
        val mask = keep(F1Pixels.mixedMask(512, 512, 4L))
        val before = tiles()
        val l = Layer(1L, "L", TiledPixels.fromBitmap(src))
        l.maskPixels = TiledPixels.fromBitmap(mask)
        val content = l.pixels as TiledPixels
        val maskStore = l.maskPixels as TiledPixels
        assertEquals(ResidencyState.PARKED, l.residency)
        assertTrue("the cut counted its own tiles", tiles() > before)
        val count = PixelFacade.materializations
        val g = l.pixelGeneration
        assertTrue("hasMask never materializes", l.hasMask)
        assertFalse(l.owns(src))
        assertEquals(count, PixelFacade.materializations)

        val b = keep(l.bitmap)
        F1Pixels.assertBitEqual("content", src, b)
        assertEquals(count + 1, PixelFacade.materializations)
        assertEquals(0, content.occupied())
        assertEquals(ResidencyState.FLAT, l.residency)
        val m = keep(l.mask!!)
        F1Pixels.assertBitEqual("mask", mask, m)
        assertEquals(count + 2, PixelFacade.materializations)
        assertEquals(0, maskStore.occupied())
        assertEquals("every tile let go", before, tiles())
        assertEquals("materializations are no replacement (J5)", g, l.pixelGeneration)
        assertTrue(l.owns(b))
        assertTrue(l.owns(m))
    }

    @Test
    fun strictAllowsOnlyTheAllowList() {
        val l = Layer(1L, "L", BlankPixels(64, 64))
        l.maskPixels = BlankPixels(64, 64)
        PixelFacade.strict = true
        assertThrows(IllegalStateException::class.java) { l.bitmap }
        assertThrows(IllegalStateException::class.java) { l.mask }
        assertEquals("nothing happened", ResidencyState.BLANK, l.residency)
        val count = PixelFacade.materializations
        val target = keep(l.paintTarget)
        assertSame("the first write materializes on the allow-list", target, (l.pixels as FlatPixels).bitmap)
        l.editingMask = true
        val maskTarget = keep(l.paintTarget)
        assertSame(maskTarget, (l.maskPixels as FlatPixels).bitmap)
        assertEquals(count + 2, PixelFacade.materializations)
        assertSame("FLAT: no throw", target, l.bitmap)
    }

    @Test
    fun theSettersReplaceTheStore() {
        val l = Layer(1L, "L", BlankPixels(32, 32))
        val g = l.pixelGeneration
        val b = keep(BitmapUtils.createLayerBitmap(32, 32))
        l.bitmap = b
        assertSame(b, (l.pixels as FlatPixels).bitmap)
        assertEquals(g + 1, l.pixelGeneration)
        val store = BlankPixels(32, 32)
        l.pixels = store
        assertEquals(g + 2, l.pixelGeneration)
        l.pixels = store
        assertEquals("the same store: no bump", g + 2, l.pixelGeneration)
        val m = keep(BitmapUtils.createLayerBitmap(32, 32))
        l.mask = m
        assertSame(m, (l.maskPixels as FlatPixels).bitmap)
        assertEquals(g + 3, l.pixelGeneration)
        l.mask = null
        assertNull(l.maskPixels)
        assertFalse(l.hasMask)
        assertEquals(g + 4, l.pixelGeneration)
    }

    @Test
    fun aPinForbidsParkUntilItCloses() {
        val l = Layer(1L, "L", keep(F1Pixels.noise(300, 200, 5L)))
        val chaos = ChaosResidency()
        val pin = l.pin()
        assertEquals(1, l.pins)
        assertFalse("pinned: not parked", chaos.park(l))
        assertEquals(ResidencyState.FLAT, l.residency)
        pin.close()
        pin.close()
        assertEquals("closing twice is closing once", 0, l.pins)
        assertTrue(chaos.park(l))
        assertEquals(ResidencyState.PARKED, l.residency)
        val count = PixelFacade.materializations
        l.pin().use { assertEquals(1, l.pins) }
        assertEquals("a pin never materializes", count, PixelFacade.materializations)
        assertEquals(ResidencyState.PARKED, l.residency)
        chaos.unpark(l)
    }

    @Test
    fun aResidentPinMakesEveryTileResidentAndAPinNeverDoes() {
        val src = keep(F1Pixels.noise(512, 300, 6L))
        val store = TiledPixels.fromBitmap(src)
        val t = store.tileAt(1, 0)!!
        // Packed as swap would leave it: the bytes alone.
        val packed = TileOps.pack(t.bitmap!!)
        t.bitmap!!.recycle()
        EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
        t.bitmap = null
        t.packed = packed
        assertEquals(TileState.PACKED, t.state)
        val l = Layer(1L, "L", store)
        l.pin().use { assertEquals("a plain pin never makes a tile resident", TileState.PACKED, t.state) }
        val pin = l.pinResident()
        assertEquals(TileState.RESIDENT, t.state)
        assertEquals(1, t.pins)
        pin.close()
        assertEquals(0, t.pins)
        F1Pixels.assertBitEqual("the pixels survived", src, keep(store.materialize()))
        l.recycleBitmaps()
    }

    @Test
    fun recycleBitmapsRecyclesFlatAndReleasesTiles() {
        val b = F1Pixels.noise(64, 64, 7L)
        val m = F1Pixels.grayNoise(64, 64, 8L)
        val flat = Layer(1L, "L", b).apply { mask = m }
        flat.recycleBitmaps()
        assertTrue(b.isRecycled)
        assertTrue(m.isRecycled)
        val before = tiles()
        val src = keep(F1Pixels.mixed(512, 512, 9L))
        val parked = Layer(2L, "P", TiledPixels.fromBitmap(src))
        val store = parked.pixels as TiledPixels
        assertTrue(tiles() > before)
        parked.recycleBitmaps()
        assertEquals(0, store.occupied())
        assertEquals(before, tiles())
        assertNotSame(src, Layer.FOLDER_BITMAP)
    }
}
