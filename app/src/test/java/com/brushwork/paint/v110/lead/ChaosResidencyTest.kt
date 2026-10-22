package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.os.Looper
import android.os.MessageQueue
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 F1 (design 2.0, 2.7, section 3.3; lead, frozen): [ChaosResidency], the test residency of
 * `-PengineMode` above CLASSIC. Park cuts a layer's flat content and mask into tiles (never a
 * folder, a pinned or a blank layer) and unpark gives back the same pixels and releases every tile.
 * Installed as `EngineConfig.residency`, it keeps the active layer FLAT and every other layer
 * PARKED once the task that changed `activeLayerIndex` ends (once per task however often the
 * index is set); with `parkChaos` it parks again at every idle of the main looper until it is
 * replaced. A parked document composites bit-equal to the flat one without a materialization.
 * [ClassicResidency] changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ChaosResidencyTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        idle()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The main queue going idle: its idle handlers run once, as `MessageQueue.next` runs them (the
     * paused looper of Robolectric does not run them itself); one that answers false is removed.
     */
    private fun queueIdle() {
        val q = Looper.getMainLooper().queue
        val f = MessageQueue::class.java.getDeclaredField("mIdleHandlers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val handlers = synchronized(q) { ArrayList(f.get(q) as ArrayList<MessageQueue.IdleHandler>) }
        for (h in handlers) if (!h.queueIdle()) q.removeIdleHandler(h)
    }

    private fun tiles() = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    private fun doc(layers: Int = 4): Document {
        val d = Document("chaos", "chaos", 512, 300)
        repeat(layers) { i ->
            d.layers += Layer(d.newLayerId(), "Layer $i", keep(F1Pixels.mixed(512, 300, 50L + i))).apply {
                if (i % 2 == 1) mask = keep(F1Pixels.mixedMask(512, 300, 60L + i))
                opacity = 0.9f
            }
        }
        return d
    }

    @Test
    fun parkAndUnparkKeepThePixelsAndReleaseEveryTile() {
        val before = tiles()
        val src = keep(F1Pixels.mixed(512, 300, 51L))
        val mask = keep(F1Pixels.mixedMask(512, 300, 52L))
        val l = Layer(1L, "L", src).apply { this.mask = mask }
        val chaos = ChaosResidency()
        assertTrue(chaos.park(l))
        assertEquals(ResidencyState.PARKED, l.residency)
        assertTrue(l.maskPixels is TiledPixels)
        assertFalse("already parked: nothing flat to park", chaos.park(l))
        chaos.unpark(l)
        assertEquals(ResidencyState.FLAT, l.residency)
        F1Pixels.assertBitEqual("content", src, keep(l.bitmap))
        F1Pixels.assertBitEqual("mask", mask, keep(l.mask!!))
        assertEquals("every tile let go", before, tiles())
        chaos.unpark(l)
        assertEquals("unparking a flat layer does nothing", ResidencyState.FLAT, l.residency)
    }

    @Test
    fun foldersPinnedAndBlankLayersAreNeverParked() {
        val chaos = ChaosResidency()
        val folder = Layer.newFolder(1L, "F")
        assertFalse(chaos.park(folder))
        assertEquals(ResidencyState.FOLDER, folder.residency)
        val blank = Layer(2L, "B", BlankPixels(64, 64))
        assertFalse(chaos.park(blank))
        assertEquals(ResidencyState.BLANK, blank.residency)
        val pinned = Layer(3L, "P", keep(F1Pixels.noise(64, 64, 53L)))
        pinned.pin().use { assertFalse(chaos.park(pinned)) }
        assertEquals(ResidencyState.FLAT, pinned.residency)
        val maskOnly = Layer(4L, "M", BlankPixels(64, 64)).apply { mask = keep(F1Pixels.grayNoise(64, 64, 54L)) }
        assertTrue("a flat mask parks alone", chaos.park(maskOnly))
        assertEquals(ResidencyState.BLANK, maskOnly.residency)
        assertTrue(maskOnly.maskPixels is TiledPixels)
        chaos.unpark(maskOnly)
    }

    @Test
    fun parkInactiveParksAllButTheActiveLayer() {
        val d = doc()
        d.activeLayerIndex = 2
        assertEquals(3, ChaosResidency().parkInactive(d))
        for ((i, l) in d.layers.withIndex()) {
            assertEquals("layer $i", if (i == 2) ResidencyState.FLAT else ResidencyState.PARKED, l.residency)
        }
        d.layers.forEach { it.recycleBitmaps() }
    }

    @Test
    fun asTheResidencyItKeepsTheActiveLayerFlatAtTheEndOfTheTask() {
        val d = doc()
        EngineConfig.residency = ChaosResidency()
        d.activeLayerIndex = 0
        d.activeLayerIndex = 3
        d.activeLayerIndex = 1
        assertTrue("nothing before the task ends", d.layers.all { it.residency == ResidencyState.FLAT })
        idle()
        for ((i, l) in d.layers.withIndex()) {
            assertEquals("layer $i", if (i == 1) ResidencyState.FLAT else ResidencyState.PARKED, l.residency)
        }
        d.activeLayerIndex = 2
        idle()
        assertEquals(ResidencyState.PARKED, d.layers[1].residency)
        assertEquals(ResidencyState.FLAT, d.layers[2].residency)
        d.layers.forEach { it.recycleBitmaps() }
    }

    @Test
    fun aParkedDocumentCompositesAsTheFlatOne() {
        val d = doc()
        val flat = keep(F1Pixels.flatten(d))
        d.activeLayerIndex = 3
        ChaosResidency().parkInactive(d)
        assertEquals(3, d.layers.count { it.residency == ResidencyState.PARKED })
        val count = PixelFacade.materializations
        PixelFacade.strict = true
        F1Pixels.assertBitEqual("parked", flat, keep(F1Pixels.flatten(d)))
        PixelFacade.strict = false
        assertEquals(count, PixelFacade.materializations)
        d.layers.forEach { it.recycleBitmaps() }
    }

    @Test
    fun parkChaosParksAgainAtEveryIdleUntilReplaced() {
        val d = doc(3)
        EngineConfig.residency = ChaosResidency(everyTask = true)
        d.activeLayerIndex = 2
        idle()
        assertEquals(ResidencyState.PARKED, d.layers[0].residency)
        keep(d.layers[0].bitmap)
        assertEquals("the facade made it flat", ResidencyState.FLAT, d.layers[0].residency)
        queueIdle()
        assertEquals("parked again at the next idle", ResidencyState.PARKED, d.layers[0].residency)
        assertEquals(ResidencyState.FLAT, d.layers[2].residency)
        EngineConfig.residency = ClassicResidency
        keep(d.layers[0].bitmap)
        queueIdle()
        queueIdle()
        assertEquals("replaced: no more parks", ResidencyState.FLAT, d.layers[0].residency)
        d.layers.forEach { it.recycleBitmaps() }
    }

    @Test
    fun classicChangesNothing() {
        val d = doc(3)
        EngineConfig.residency = ClassicResidency
        d.activeLayerIndex = 0
        d.activeLayerIndex = 2
        idle()
        assertTrue(d.layers.all { it.residency == ResidencyState.FLAT })
        d.layers.forEach { it.recycleBitmaps() }
    }
}
