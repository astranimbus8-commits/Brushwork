package com.brushwork.paint.v110.a7

import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * v1.10 A7 step 4 (design 2.13, section 2.2's BLANK row): a new layer, an adjustment layer and a
 * vector layer go in as `BlankPixels` and cost 0 bytes until their first write, at every level
 * (here Classic with memory unknown, as every v1.9 test runs; [BlankLayersCostZeroTilesTest] at
 * TILES). The insert stays one undo step, the blank layer counts in the layer limit like any
 * other, nothing is materialized until a tool paints, and the first stroke makes it a flat layer
 * in one step. A layer made with content keeps its bitmap. A document with a blank layer saves
 * and opens as v1.9's does (old projects open).
 */
@RunWith(RobolectricTestRunner::class)
class BlankLayersCostZeroTest {
    private val kit = BlankKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun aNewLayerIsBlankAndOneStep() {
        val (c, doc) = kit.open()
        val steps = c.undoManager.undoCount
        val counted = c.effectiveLayerCount
        val made = PixelFacade.materializations
        val layer = c.addLayer()!!
        kit.idle()
        assertSame(layer, doc.layers[1])
        kit.assertBlank(layer)
        assertEquals("nothing materialized it", made, PixelFacade.materializations)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("it counts like any layer", counted + 1, c.effectiveLayerCount)
        assertEquals(0L, layer.pixelGeneration)
        c.undo()
        kit.idle()
        assertEquals(1, doc.layers.size)
        c.redo()
        kit.idle()
        assertSame(layer, doc.layers[1])
        kit.assertBlank(layer)
    }

    @Test
    fun aNewLayerInAFolderIsBlankAndOneStep() {
        val (c, doc) = kit.open()
        val folder = c.addFolder()!!
        kit.idle()
        assertTrue("the tree's insert (one LayerTreeAction), not v1.6's", doc.hasFolders)
        val steps = c.undoManager.undoCount
        val layer = c.addLayer()!!
        kit.idle()
        assertTrue(doc.indexOf(layer) >= 0)
        assertEquals("inside the open folder", folder.id, layer.parentId)
        kit.assertBlank(layer)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        c.undo()
        kit.idle()
        assertEquals(-1, doc.indexOf(layer))
        c.redo()
        kit.idle()
        assertTrue(doc.indexOf(layer) >= 0)
        kit.assertBlank(layer)
    }

    @Test
    fun theFirstStrokeMakesItFlatInOneStep() {
        val (c, doc) = kit.open()
        val layer = c.addLayer()!!
        kit.idle()
        val steps = c.undoManager.undoCount
        kit.stroke(c)
        assertEquals(ResidencyState.FLAT, layer.residency)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertTrue(layer.contentVersion > 0L)
        assertTrue("the stroke painted", Color.alpha(layer.bitmap.getPixel(124, 100)) > 0)
        assertEquals("the layer below unchanged", BOTTOM, doc.layers[0].bitmap.getPixel(124, 100))
        c.undo()
        kit.idle()
        assertTrue("undo leaves it transparent", layer.pixels.isEmpty())
    }

    @Test
    fun adjustmentAndVectorLayersAreBlank() {
        val (c, _) = kit.open()
        val made = PixelFacade.materializations
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(drawingColor = c.color), null)!!
        kit.idle()
        assertTrue(adjustment.isAdjustmentLayer)
        kit.assertBlank(adjustment)
        val vector = c.addVectorLayer()!!
        kit.idle()
        assertTrue(vector.isVectorLayer)
        kit.assertBlank(vector)
        assertEquals(made, PixelFacade.materializations)
    }

    @Test
    fun aLayerMadeWithContentKeepsItsBitmap() {
        val (c, _) = kit.open()
        val ink = c.addLayerWithContent("Ink", "Add ink") { it.drawRect(10f, 10f, 30f, 30f, Paint().apply { color = Color.RED }) }!!
        kit.idle()
        assertTrue(ink.pixels is FlatPixels)
        assertEquals(Color.RED, ink.bitmap.getPixel(20, 20))
        val nothing = c.addLayerWithContent("Nothing", "Add nothing") { }!!
        kit.idle()
        kit.assertBlank(nothing)
    }

    @Test
    fun aDocumentWithABlankLayerSavesAndOpens() {
        val (c, doc) = kit.open()
        val layer = c.addLayer()!!
        kit.idle()
        kit.assertBlank(layer)
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        runBlocking { repo.save(doc, null) }
        val loaded = runBlocking { repo.load(doc.id) }
        assertEquals(2, loaded.layers.size)
        assertEquals(layer.name, loaded.layers[1].name)
        assertTrue("opens transparent", loaded.layers[1].pixels.isEmpty())
        assertEquals(BOTTOM, loaded.layers[0].bitmap.getPixel(5, 5))
        loaded.layers.forEach { it.recycleBitmaps() }
    }
}

/** At TILES on the T606: the same blank layers (a residency that parks nothing keeps the states). */
@RunWith(RobolectricTestRunner::class)
class BlankLayersCostZeroTilesTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = BlankKit.Quiet())

    private val kit = BlankKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun newLayersAndAdjustmentLayersCostZero() {
        val (c, doc) = kit.open()
        val before = doc.layers.sumOf { it.pixels.residentBytes() }
        val layer = c.addLayer()!!
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(drawingColor = c.color), null)!!
        kit.idle()
        kit.assertBlank(layer)
        kit.assertBlank(adjustment)
        assertEquals("0 bytes more", before, doc.layers.sumOf { it.pixels.residentBytes() })
        c.selectLayer(layer)
        kit.idle()
        kit.stroke(c)
        assertEquals(ResidencyState.FLAT, layer.residency)
        assertFalse(layer.pixels.isEmpty())
        kit.assertBlank(adjustment)
    }
}

/** The documents, controllers and strokes of the two classes. */
private class BlankKit {
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A 256² document with one painted layer, active. */
    fun open(): Pair<EditorController, Document> {
        val doc = Smoke.document(256, 256, layers = 1)
        doc.layers[0].bitmap.eraseColor(BOTTOM)
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        idle()
        return c to doc
    }

    fun close() {
        controller?.dispose()
        scope?.cancel()
        idle()
    }

    fun stroke(c: EditorController) {
        c.pointerDown(ToolPoint(40f, 40f))
        for (i in 1..12) c.pointerMove(ToolPoint(40f + 14f * i, 40f + 10f * i))
        c.pointerUp(ToolPoint(208f, 160f))
        idle()
    }

    fun assertBlank(layer: Layer) {
        assertTrue("${layer.name} is blank: ${layer.pixels}", layer.pixels is BlankPixels)
        assertEquals(ResidencyState.BLANK, layer.residency)
        assertEquals(0L, layer.pixels.residentBytes())
        assertEquals(256, layer.width)
        assertEquals(256, layer.height)
    }

    /** A residency that parks nothing and frees nothing. */
    class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) = Unit

        override fun idleTick() = Unit
    }
}

private const val BOTTOM = 0xFF204060.toInt()
