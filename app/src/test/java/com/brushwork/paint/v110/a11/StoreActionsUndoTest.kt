package com.brushwork.paint.v110.a11

import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneImage
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SceneLayer
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntryDto
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.SaveThumbnail
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * v1.10 (A11 review, design 2.3, 2.5, 2.6): the store actions of A11 at FULL, driven through the
 * controller on a document opened from disk (inactive layers and masks parked):
 *  - "Clear" of a parked layer swaps a blank store in as ONE step, nothing materialized; undo puts
 *    back the very store, redo the blank;
 *  - "Apply mask" on a parked layer with a parked mask: undo puts back the very mask store and
 *    the pixels, redo the masked pixels and no mask;
 *  - a view of an adjustment layer (merge down, layered export, the autosave thumbnail) never
 *    reaches the real layer's mask through `Layer.mask`, whose materialization would release the
 *    shared tiles (the mask lost, also for the undo step that brings the layer back).
 */
@RunWith(RobolectricTestRunner::class)
class StoreActionsUndoTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private val w = 512
    private val h = 384

    /**
     * Layer 1 painted; 2 an adjustment layer (invert) with a mask, top half white ([blankMask]:
     * a blank mask); 3 painted with a mask (top half white); 4 painted and active.
     */
    private fun write(id: String, blankMask: Boolean = false) {
        A11Kit.freshRepo()
        val dir = A11Kit.dirOf(id)
        dir.mkdirs()
        val white = A11Kit.opaqueBytes(0xFFFFFF)
        val black = A11Kit.opaqueBytes(0x000000)
        fun writeMask(file: File) = A11Kit.writeRows(file, w, h) { y, n, into ->
            for (r in 0 until n) for (x in 0 until w) System.arraycopy(if (y + r < h / 2) white else black, 0, into, (r * w + x) * 4, 4)
        }
        val entries = (1L..4L).map { layerId ->
            val name = "Layer $layerId"
            val file = ProjectFormat.layerFile(layerId, 1)
            if (layerId == 2L) LayerCodec.writeBlank(File(dir, file), w, h)
            else A11Kit.writeRows(File(dir, file), w, h, A11Kit.PaintedRows(w, h, seed = 700L + layerId)::fill)
            when (layerId) {
                2L -> {
                    val mask = ProjectFormat.maskFile(layerId, 1)
                    if (blankMask) LayerCodec.writeBlank(File(dir, mask), w, h) else writeMask(File(dir, mask))
                    LayerEntryDto(
                        layerId, name, ProjectFormat.defaultProps(name), hasMask = true, file = file, maskFile = mask,
                        adjustment = AdjustmentCodec.encode(AdjustmentSpec(filterId = "adjust.invert")),
                    )
                }
                3L -> {
                    val mask = ProjectFormat.maskFile(layerId, 1)
                    writeMask(File(dir, mask))
                    LayerEntryDto(layerId, name, ProjectFormat.defaultProps(name), hasMask = true, file = file, maskFile = mask)
                }
                else -> LayerEntryDto(layerId, name, ProjectFormat.defaultProps(name), file = file)
            }
        }
        ProjectFormat.write(dir, ProjectFileDto(formatVersion = 3, id = id, name = id, width = w, height = h, activeLayerIndex = 3, layers = entries, revision = 1))
    }

    private fun bytes(p: PixelStore?): ByteArray = if (p == null) ByteArray(0) else A11Kit.bytesOf(p, w, h)

    /** Opens [id] in an editor session; [block] runs on the ready controller, then the session closes. */
    private fun withEditor(id: String, block: (EditorController) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val session = EditorSession(app, id)
        pumpUntil("the editor") { session.state is EditorSession.State.Ready }
        val c = (session.state as EditorSession.State.Ready).controller
        try {
            block(c)
        } finally {
            PixelFacade.strict = false
            var closed = false
            session.close { closed = true }
            pumpUntil("the session to close") { closed }
        }
    }

    @Test
    fun clearingAParkedLayerSwapsABlankStoreInAsOneStep() {
        write(ID)
        withEditor(ID) { c ->
            val layer = c.doc.layers[2]
            val store = layer.pixels
            assertTrue("layer 3 is parked", store is TiledPixels)
            val original = bytes(store)
            val steps = c.undoManager.undoCount
            val materialized = PixelFacade.materializations
            PixelFacade.strict = true
            c.clearLayer(layer)
            pumpUntil("the clear") { c.busyMessage == null }
            assertTrue("blank", layer.pixels is BlankPixels)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            c.undo()
            assertSame("undo: the very store", store, layer.pixels)
            assertArrayEquals("undo: the pixels", original, bytes(layer.pixels))
            c.redo()
            assertTrue("redo: blank", layer.pixels is BlankPixels)
            c.undo()
            assertSame("undo again: the very store", store, layer.pixels)
            PixelFacade.strict = false
            assertEquals("nothing was materialized", materialized, PixelFacade.materializations)
            assertArrayEquals(original, bytes(layer.pixels))
        }
    }

    @Test
    fun applyingAParkedMaskUndoesAndRedoesExactly() {
        write(ID)
        withEditor(ID) { c ->
            val layer = c.doc.layers[2]
            val mask = layer.maskPixels
            assertTrue("the mask is parked", mask is TiledPixels)
            val pixels = bytes(layer.pixels)
            val maskBytes = bytes(mask)
            val steps = c.undoManager.undoCount
            c.applyMask(layer)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            assertNull("no mask", layer.maskPixels)
            val applied = bytes(layer.pixels)
            assertFalse("the mask hid the bottom half", applied.contentEquals(pixels))
            c.undo()
            assertSame("undo: the very mask store", mask, layer.maskPixels)
            assertArrayEquals("undo: the mask", maskBytes, bytes(layer.maskPixels))
            assertArrayEquals("undo: the pixels", pixels, bytes(layer.pixels))
            c.redo()
            assertNull("redo: no mask", layer.maskPixels)
            assertArrayEquals("redo: the masked pixels", applied, bytes(layer.pixels))
            c.undo()
            assertArrayEquals("undo again: the mask", maskBytes, bytes(layer.maskPixels))
        }
    }

    @Test
    fun mergingAParkedAdjustmentLayerDownKeepsItsMaskForUndo() {
        write(ID)
        withEditor(ID) { c ->
            val doc = c.doc
            val lower = doc.layers[0]
            val adjustment = doc.layers[1]
            assertTrue("an adjustment layer", adjustment.isAdjustmentLayer)
            assertTrue("its mask is parked", adjustment.maskPixels is TiledPixels)
            assertTrue("the lower layer is parked", lower.pixels is TiledPixels)
            val lowerStore = lower.pixels
            val lowerBytes = bytes(lowerStore)
            val maskBytes = bytes(adjustment.maskPixels)
            val materialized = PixelFacade.materializations
            PixelFacade.strict = true
            c.mergeDown(adjustment)
            assertFalse("merged", adjustment in doc.layers)
            assertTrue("the merged pixels are FLAT", lower.pixels is FlatPixels)
            val merged = lower.pixels
            val mergedBytes = bytes(merged)
            // Invert where the mask is white (top half), the lower layer as it was below.
            val half = w * (h / 2) * 4
            assertFalse("the top half is inverted", mergedBytes.copyOfRange(0, half).contentEquals(lowerBytes.copyOfRange(0, half)))
            assertArrayEquals("the bottom half is the lower layer", lowerBytes.copyOfRange(half, lowerBytes.size), mergedBytes.copyOfRange(half, mergedBytes.size))
            c.undo()
            PixelFacade.strict = false
            assertEquals("nothing was materialized", materialized, PixelFacade.materializations)
            assertTrue("undo: back", adjustment in doc.layers)
            assertSame("undo: the lower layer's very store", lowerStore, lower.pixels)
            assertArrayEquals("undo: the lower layer", lowerBytes, bytes(lower.pixels))
            assertArrayEquals("undo: the adjustment layer's mask", maskBytes, bytes(adjustment.maskPixels))
            c.redo()
            assertSame("redo: the merged store", merged, lower.pixels)
        }
    }

    @Test
    fun aLayeredExportLeavesTheAdjustmentLayersParkedMaskIntact() {
        write(ID)
        withEditor(ID) { c ->
            val adjustment = c.doc.layers[1]
            val mask = adjustment.maskPixels
            assertTrue("the mask is parked", mask is TiledPixels)
            val maskBytes = bytes(mask)
            val scene = runBlocking {
                ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, includePayload = false), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
            }
            val images = ArrayList<SceneImage>()
            fun collect(l: SceneLayer) {
                l.mask?.image?.let { images += it }
                for (item in l.items) if (item is SceneItem.Image) images += item.image
                l.children.forEach { collect(it) }
            }
            scene.layers.forEach { collect(it) }
            assertTrue("pictures", images.isNotEmpty())
            runBlocking { for (img in images) assertNotNull(img.source.load()) }
            assertSame("the very mask store", mask, adjustment.maskPixels)
            assertArrayEquals("the mask", maskBytes, bytes(adjustment.maskPixels))
        }
    }

    /**
     * A11 merge (lead; I28): the payload of an SVG with Brushwork data reads every mask from its
     * store (`ExportSceneBuilder.payloadLayer`): nothing is materialized under strict, the parked
     * masks keep their very stores, and each layer's mask comes back from the payload's picture and
     * fill exactly.
     */
    @Test
    fun aPayloadExportReadsParkedMasksFromTheirStores() {
        write(ID)
        withEditor(ID) { c ->
            val masked = listOf(c.doc.layers[1], c.doc.layers[2])
            val stores = masked.map { it.maskPixels }
            assertTrue("the masks are parked", stores.all { it is TiledPixels })
            val expected = stores.map { bytes(it) }
            val materialized = PixelFacade.materializations
            PixelFacade.strict = true
            val scene = runBlocking {
                ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, includePayload = true), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
            }
            val images = sceneImages(scene.layers) + scene.payloadImages
            for ((i, layer) in masked.withIndex()) {
                val entry = scene.payload!!.layers.single { it.id == layer.id }
                assertTrue("${layer.name}: a mask in the payload", entry.hasMask)
                val img = entry.maskRef?.let { ref -> images.first { it.key == ref } }
                val argb = img?.let { runBlocking { it.source.load() } }
                val fill = (entry.maskFill shr 16) and 0xFF
                var wrong = 0
                var first = ""
                for (y in 0 until h) for (x in 0 until w) {
                    val inside = img != null && x in img.left until img.left + img.width && y in img.top until img.top + img.height
                    val got = if (inside) (argb!!.pixels[(y - img!!.top) * img.width + (x - img.left)] shr 16) and 0xFF else fill
                    val want = expected[i][(y * w + x) * 4].toInt() and 0xFF
                    if (got != want && wrong++ == 0) first = "at $x, $y: $got instead of $want"
                }
                assertEquals("${layer.name}: the payload's mask, pixels wrong (first $first)", 0, wrong)
            }
            PixelFacade.strict = false
            assertEquals("nothing was materialized", materialized, PixelFacade.materializations)
            for ((i, layer) in masked.withIndex()) assertSame("${layer.name}: the very mask store", stores[i], layer.maskPixels)
        }
    }

    private fun sceneImages(layers: List<SceneLayer>): List<SceneImage> = layers.flatMap { l ->
        listOfNotNull(l.mask?.image) + l.items.filterIsInstance<SceneItem.Image>().map { it.image } + sceneImages(l.children)
    }

    @Test
    fun theAutosaveThumbnailDrawsABlankAdjustmentMaskOffMainWithoutTheFacade() {
        write(ID, blankMask = true)
        withEditor(ID) { c ->
            val adjustment = c.doc.layers[1]
            assertTrue("the mask is blank", adjustment.maskPixels is BlankPixels)
            val snap = SaveThumbnail.take(c.doc)
            assertNotNull("rendered off main", snap)
            val materialized = PixelFacade.materializations
            var failure: Throwable? = null
            PixelFacade.strict = true
            try {
                val t = Thread {
                    try {
                        snap!!.render(128).recycle()
                    } catch (e: Throwable) {
                        failure = e
                    }
                }
                t.start()
                t.join()
            } finally {
                PixelFacade.strict = false
                snap!!.release()
            }
            assertNull("no materialization off main: $failure", failure)
            assertEquals("nothing was materialized", materialized, PixelFacade.materializations)
            assertTrue("the layer's mask is still blank", adjustment.maskPixels is BlankPixels)
        }
    }

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 3000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        const val ID = "storeactions"
    }
}
