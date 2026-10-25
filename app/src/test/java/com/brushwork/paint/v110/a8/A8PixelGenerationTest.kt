package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DocumentBitmapsAction
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 4 (design 2.2 J5, 2.6; the lead's `PixelGenerationTest` covers the stores
 * themselves): `Layer.pixelGeneration` under the undo steps A8 rewrote. A pixel edit, its undo
 * and its redo change pixels in place: the generation stays, `contentVersion` moves. Steps that
 * exchange stores (a mask added or removed, a canvas change) bump it on each undo and redo, and
 * only for the layers whose store they exchange. A delete and its undo keep it. (On a PARKED
 * layer, see `UndoOnParkedLayerTest`: the same store, the same generation.)
 */
@RunWith(RobolectricTestRunner::class)
class A8PixelGenerationTest {
    @get:Rule
    val engine = EngineRule()

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun editor(n: Int = 2): EditorController {
        val doc = Document("a8", "a8", 300, 200).also { docs += it }
        repeat(n) { i ->
            val b = BitmapUtils.createLayerBitmap(300, 200).also { it.eraseColor(0xFF406080.toInt() + i); made += it }
            doc.layers += Layer(doc.newLayerId(), "Layer $i", b)
        }
        doc.activeLayerIndex = n - 1
        val sc = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
    }

    /** Runs [step] and asserts [layer]'s generation moved ([bumps]) or not, and its content version moved. */
    private fun assertGeneration(layer: Layer, bumps: Boolean, what: String, step: () -> Unit) {
        val g = layer.pixelGeneration
        val v = layer.contentVersion
        step()
        if (bumps) assertNotEquals("$what bumps the generation", g, layer.pixelGeneration) else assertEquals("$what keeps the generation", g, layer.pixelGeneration)
        assertTrue("$what counts a change", layer.contentVersion > v)
    }

    @Test
    fun aPixelEditItsUndoAndItsRedoKeepTheGeneration() {
        val c = editor()
        val l = c.doc.activeLayer
        val store = l.pixels
        assertGeneration(l, false, "the edit") { A8Kit.paint(c, l, Rect(20, 20, 280, 180), 0xFF3366AA.toInt()) }
        assertGeneration(l, false, "its undo") { c.undo() }
        assertGeneration(l, false, "its redo") { c.redo() }
        assertSame("the same store throughout", store, l.pixels)
    }

    @Test
    fun aMaskAddedAndRemovedBumpsOnEachUndoAndRedo() {
        val c = editor()
        val l = c.doc.activeLayer
        assertGeneration(l, true, "add mask") { c.addMask(l, fromSelection = false) }
        assertGeneration(l, true, "its undo") { c.undo() }
        assertGeneration(l, true, "its redo") { c.redo() }
        assertGeneration(l, true, "delete mask") { c.deleteMask(l) }
        assertGeneration(l, true, "its undo") { c.undo() }
        assertTrue(l.hasMask)
    }

    @Test
    fun aCanvasChangeBumpsOnlyTheLayersWhoseStoreItExchanges() {
        val c = editor()
        val changed = c.doc.layers[1]
        val kept = c.doc.layers[0]
        val doc = c.doc
        val replaced = BitmapUtils.createLayerBitmap(300, 200).also { made += it }
        val keptBitmap = kept.bitmap
        val action = DocumentBitmapsAction(
            "Replace",
            listOf(
                DocumentBitmapsAction.Entry(changed, changed.bitmap, null, replaced, null),
                DocumentBitmapsAction.Entry(kept, keptBitmap, null, keptBitmap, null),
            ),
            doc.width to doc.height, doc.width to doc.height, doc.dpi, doc.dpi, doc.colorMode, doc.colorMode,
        )
        val keptGeneration = kept.pixelGeneration
        val keptStore = kept.pixels
        assertGeneration(changed, true, "the change") { action.redo(c) }
        c.pushUndo(action)
        assertGeneration(changed, true, "its undo") { c.undo() }
        assertGeneration(changed, true, "its redo") { c.redo() }
        assertEquals("an entry with the same bitmap keeps its layer's generation", keptGeneration, kept.pixelGeneration)
        assertSame("and its store", keptStore, kept.pixels)
    }

    @Test
    fun aDeleteAndItsUndoKeepTheGeneration() {
        val c = editor(3)
        val l = c.doc.layers[1]
        val g = l.pixelGeneration
        val store = l.pixels
        c.deleteLayer(l)
        assertEquals(g, l.pixelGeneration)
        c.undo()
        assertEquals(g, l.pixelGeneration)
        assertSame(store, l.pixels)
        assertSame(l, c.doc.layers[1])
    }
}
