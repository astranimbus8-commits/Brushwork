package com.brushwork.paint.v110.a11

import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntryDto
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * v1.10 (A11, design 2.5 "Canvas ops", 4.4 A11 step 4): at FULL a canvas rotation runs layer by
 * layer on the stores. With `PixelFacade.strict` on, nothing is materialized on main; every
 * inactive layer's result is parked (tiled, a blank layer stays blank), the active one is FLAT,
 * the pixels equal what v1.9's rotation of the same bitmaps gives, every pin and snapshot is let
 * go of, undo puts back the very stores the layers held (by reference, no pixel copied) and redo
 * the results.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsLayerByLayerTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private val w = 512
    private val h = 384

    /** Layer 1 painted, 2 blank, 3 painted with a mask (top half visible), 4 painted and active. */
    private fun write(id: String) {
        A11Kit.freshRepo()
        val dir = A11Kit.dirOf(id)
        dir.mkdirs()
        val white = A11Kit.opaqueBytes(0xFFFFFF)
        val black = A11Kit.opaqueBytes(0x000000)
        val entries = (1L..4L).map { layerId ->
            val file = ProjectFormat.layerFile(layerId, 1)
            if (layerId == 2L) LayerCodec.writeBlank(File(dir, file), w, h)
            else A11Kit.writeRows(File(dir, file), w, h, A11Kit.PaintedRows(w, h, seed = 300L + layerId)::fill)
            if (layerId == 3L) {
                val mask = ProjectFormat.maskFile(layerId, 1)
                A11Kit.writeRows(File(dir, mask), w, h) { y, n, into ->
                    for (r in 0 until n) for (x in 0 until w) System.arraycopy(if (y + r < h / 2) white else black, 0, into, (r * w + x) * 4, 4)
                }
                LayerEntryDto(layerId, "Layer $layerId", ProjectFormat.defaultProps("Layer $layerId"), hasMask = true, file = file, maskFile = mask)
            } else {
                LayerEntryDto(layerId, "Layer $layerId", ProjectFormat.defaultProps("Layer $layerId"), file = file)
            }
        }
        ProjectFormat.write(dir, ProjectFileDto(formatVersion = 3, id = id, name = id, width = w, height = h, activeLayerIndex = 3, layers = entries, revision = 1))
    }

    private fun bytes(p: PixelStore?, pw: Int, ph: Int): ByteArray = if (p == null) ByteArray(0) else A11Kit.bytesOf(p, pw, ph)

    @Test
    fun aFullRotationRunsLayerByLayerOnTheStores() {
        write(ID)
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val session = EditorSession(app, ID)
        pumpUntil("the editor") { session.state is EditorSession.State.Ready }
        val c = (session.state as EditorSession.State.Ready).controller
        val doc = c.doc
        val layers = doc.layers.toList()
        assertTrue("layers 1 and 3 are tiled", layers[0].pixels is TiledPixels && layers[2].pixels is TiledPixels)
        assertTrue("layer 2 is blank", layers[1].pixels is BlankPixels)
        assertTrue("the mask is tiled", layers[2].maskPixels is TiledPixels)
        assertTrue("the active layer is FLAT", layers[3].pixels is FlatPixels)

        // v1.9's rotation of the same pixels (FLAT results of a lease-free snapshot), for reference.
        val reference = run {
            val snap = CanvasSnapshot.of(doc)
            val r = CanvasOps.rotate(snap, CanvasRotation.CW_90)
            val out = r.layers.map { bytes(it.pixels, h, w) to bytes(it.maskPixels, h, w) }
            r.recycle(snap)
            out
        }
        val stores = layers.map { it.pixels to it.maskPixels }
        val original = layers.map { bytes(it.pixels, w, h) to bytes(it.maskPixels, w, h) }
        val generations = layers.map { it.pixelGeneration }

        val materialized = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            assertTrue("started", CanvasOps.applyRotate(c, CanvasRotation.CW_90))
            pumpUntil("the rotation") { c.busyMessage == null }
        } finally {
            PixelFacade.strict = false
        }
        assertEquals("nothing was materialized on main", materialized, PixelFacade.materializations)
        assertEquals(h, doc.width)
        assertEquals(w, doc.height)
        assertTrue("layer 1 parked", layers[0].pixels is TiledPixels)
        assertTrue("layer 2 still blank", layers[1].pixels is BlankPixels)
        assertTrue("layer 3 and its mask parked", layers[2].pixels is TiledPixels && layers[2].maskPixels is TiledPixels)
        assertTrue("the active layer is FLAT", layers[3].pixels is FlatPixels)
        for (i in layers.indices) {
            assertArrayEquals("layer ${i + 1}", reference[i].first, bytes(layers[i].pixels, h, w))
            assertArrayEquals("mask ${i + 1}", reference[i].second, bytes(layers[i].maskPixels, h, w))
            assertTrue("layer ${i + 1}: a new generation", generations[i] != layers[i].pixelGeneration)
        }
        assertEquals("every pin is let go of", 0, layers.sumOf { it.pins })
        val results = layers.map { it.pixels to it.maskPixels }

        undo(c)
        assertEquals(w, doc.width)
        for (i in layers.indices) {
            assertSame("undo: layer ${i + 1}'s own store", stores[i].first, layers[i].pixels)
            assertSame("undo: mask ${i + 1}'s own store", stores[i].second, layers[i].maskPixels)
            assertArrayEquals("undo: layer ${i + 1}", original[i].first, bytes(layers[i].pixels, w, h))
            assertArrayEquals("undo: mask ${i + 1}", original[i].second, bytes(layers[i].maskPixels, w, h))
        }
        redo(c)
        for (i in layers.indices) {
            assertSame("redo: layer ${i + 1}'s result", results[i].first, layers[i].pixels)
            assertSame("redo: mask ${i + 1}'s result", results[i].second, layers[i].maskPixels)
        }

        var closed = false
        session.close { closed = true }
        pumpUntil("the session to close") { closed }
    }

    private fun undo(c: EditorController) = c.undo()

    private fun redo(c: EditorController) = c.redo()

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 3000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        const val ID = "canvasops"
    }
}
