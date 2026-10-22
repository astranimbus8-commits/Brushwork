package com.brushwork.paint.v110.lead

import android.graphics.Canvas
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.NewLayer
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 F1 (design 2.7, finding 7; lead, frozen): every writer of `Document.activeLayerIndex` goes
 * through its setter, so under [ChaosResidency] (every inactive layer parked at the end of each
 * task) an undo or redo of a delete, a merge or an import leaves the active layer RESIDENT (FLAT in
 * F1) and the others parked, exactly as a tap in the layers panel does, with no change at the call
 * sites.
 */
@RunWith(RobolectricTestRunner::class)
class ActiveLayerResidentAfterUndoTest {
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        controller?.dispose()
        scope?.cancel()
        F1Pixels.resetEngine()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): Pair<EditorController, Document> {
        val doc = Smoke.document(256, 256, layers = 4)
        doc.layers.forEachIndexed { i, l -> Canvas(l.bitmap).drawColor((0x80 shl 24) or (0x102030 * (i + 1))) }
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        EngineConfig.residency = ChaosResidency()
        doc.activeLayerIndex = doc.layers.lastIndex
        idle()
        return c to doc
    }

    private fun assertActiveResident(doc: Document, where: String) {
        idle()
        val active = doc.activeLayer
        assertEquals("$where: the active layer", ResidencyState.FLAT, active.residency)
        for (l in doc.layers) {
            if (l !== active) assertEquals("$where: $l", ResidencyState.PARKED, l.residency)
        }
    }

    @Test
    fun undoAndRedoOfADelete() {
        val (c, doc) = open()
        assertActiveResident(doc, "opened")
        c.deleteLayer(doc.activeLayer)
        assertActiveResident(doc, "deleted")
        c.undo()
        assertActiveResident(doc, "delete undone")
        assertEquals(4, doc.layers.size)
        c.redo()
        assertActiveResident(doc, "delete redone")
        assertEquals(3, doc.layers.size)
    }

    @Test
    fun undoAndRedoOfAMerge() {
        val (c, doc) = open()
        c.mergeDown(doc.activeLayer)
        assertActiveResident(doc, "merged")
        assertEquals(3, doc.layers.size)
        c.undo()
        assertActiveResident(doc, "merge undone")
        assertEquals(4, doc.layers.size)
        c.redo()
        assertActiveResident(doc, "merge redone")
        assertEquals(3, doc.layers.size)
    }

    @Test
    fun undoAndRedoOfAnImport() {
        val (c, doc) = open()
        val bitmap = BitmapUtils.createLayerBitmap(256, 256).apply { eraseColor(0xFF336699.toInt()) }
        val created = ImportLayers.insert(c, listOf(NewLayer("Imported", bitmap)), "Import")
        assertEquals(1, created.size)
        assertActiveResident(doc, "imported")
        assertTrue(doc.activeLayer === created[0])
        c.undo()
        assertActiveResident(doc, "import undone")
        assertEquals(4, doc.layers.size)
        c.redo()
        assertActiveResident(doc, "import redone")
        assertEquals(5, doc.layers.size)
    }

    @Test
    fun aTapInThePanelDoesTheSame() {
        val (c, doc) = open()
        c.selectLayer(0)
        assertActiveResident(doc, "selected the bottom layer")
        c.selectLayer(2)
        assertActiveResident(doc, "selected layer 3")
    }
}
