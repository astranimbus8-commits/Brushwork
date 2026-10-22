package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Layer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.2, J5; lead, frozen): [Layer.pixelGeneration] increases exactly when the
 * content or the mask is replaced by another store (the `pixels` and `maskPixels` setters, and the
 * `bitmap` and `mask` setters through them) and never when the same pixels move to another store:
 * park, unpark, a facade materialization of a blank or parked store, a pin. So a cache keyed on
 * the generation survives every idle park.
 */
@RunWith(RobolectricTestRunner::class)
class PixelGenerationTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    @Test
    fun replacingAStoreBumpsAndTheSameStoreDoesNot() {
        val l = Layer(1L, "L", keep(F1Pixels.noise(300, 200, 41L)))
        var g = l.pixelGeneration
        val same = l.pixels
        l.pixels = same
        assertEquals("the same store", g, l.pixelGeneration)
        l.pixels = BlankPixels(300, 200)
        assertEquals("a blank store", ++g, l.pixelGeneration)
        val tiled = TiledPixels.fromBitmap(keep(F1Pixels.mixed(300, 200, 42L)))
        l.pixels = tiled
        assertEquals("a tiled store", ++g, l.pixelGeneration)
        l.pixels = FlatPixels(keep(F1Pixels.noise(300, 200, 43L)))
        assertEquals("a flat store", ++g, l.pixelGeneration)
        val mask = BlankPixels(300, 200)
        l.maskPixels = mask
        assertEquals("a mask added", ++g, l.pixelGeneration)
        l.maskPixels = mask
        assertEquals("the same mask store", g, l.pixelGeneration)
        l.maskPixels = null
        assertEquals("the mask removed", ++g, l.pixelGeneration)
        l.bitmap = keep(F1Pixels.noise(300, 200, 44L))
        assertEquals("the bitmap setter replaces the store", ++g, l.pixelGeneration)
        l.mask = keep(F1Pixels.grayNoise(300, 200, 45L))
        assertEquals("the mask setter too", ++g, l.pixelGeneration)
        tiled.let { com.brushwork.paint.engine.tiles.TileOps.releaseStore(it) }
    }

    @Test
    fun parkUnparkMaterializeAndPinsNeverBump() {
        val src = keep(F1Pixels.mixed(512, 300, 46L))
        val mask = keep(F1Pixels.mixedMask(512, 300, 47L))
        val l = Layer(1L, "L", src).apply { this.mask = mask }
        val g = l.pixelGeneration
        val chaos = ChaosResidency()
        assertTrue(chaos.park(l))
        assertEquals(ResidencyState.PARKED, l.residency)
        assertTrue(l.maskPixels is TiledPixels)
        assertEquals("park", g, l.pixelGeneration)
        chaos.unpark(l)
        assertEquals(ResidencyState.FLAT, l.residency)
        assertEquals("unpark", g, l.pixelGeneration)
        F1Pixels.assertBitEqual("unparked content", src, keep(l.bitmap))
        F1Pixels.assertBitEqual("unparked mask", mask, keep(l.mask!!))

        assertTrue(chaos.park(l))
        keep(l.bitmap)
        keep(l.mask!!)
        assertEquals("a facade materialization", g, l.pixelGeneration)
        l.pin().close()
        l.pinResident().close()
        assertEquals("pins", g, l.pixelGeneration)

        val blank = Layer(2L, "B", BlankPixels(64, 64))
        val gb = blank.pixelGeneration
        keep(blank.bitmap)
        assertEquals("a blank materialization", gb, blank.pixelGeneration)
        assertEquals(ResidencyState.FLAT, blank.residency)
    }
}
