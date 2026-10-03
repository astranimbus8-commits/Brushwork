package com.brushwork.paint.tools.mask

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 §3.1b / §3.1d: an Adjust-sheet slider drag no longer recomposes the layer UI per sample.
 * `AdjustmentEdit.preview` leaves `layersVersion` alone (the sheet follows the edit's own
 * `version`), bumps it once when the layer's automatic name changes, and the layer UI catches up
 * at `flush`. The canvas still redraws on every move (Robolectric's EXACT policy: plain
 * invalidation), and the drag is still ONE "Edit adjustment" step.
 */
@RunWith(RobolectricTestRunner::class)
class AdjustmentEditNotifyTest {
    private val w = 64
    private val h = 48
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    private fun setup(): Layer {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("n", "n", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(4f, 4f, 60f, 44f, Paint().apply { color = 0xFF3366AA.toInt() })
        }
        doc.activeLayerIndex = 0
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.MASK)
        val tone = FilterRegistry.byId("adjust.tone")!!
        return c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), null)!!
    }

    private val tool get() = c.tools.getValue(ToolId.MASK) as MaskTool

    @Test
    fun previewMovesDoNotBumpTheLayerListButTheEditsVersion() {
        val adj = setup()
        val tone = FilterRegistry.byId("adjust.tone")!!
        assertEquals(LiveAdjust.Policy.EXACT, c.liveAdjust.policy)
        val edit = tool.adjustmentEdit(adj)
        val steps = c.undoManager.undoCount
        val layersBefore = c.layersVersion
        val versionBefore = edit.version
        for (i in 1..25) {
            c.tiles.update(c.compositor, null)
            edit.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", i / 10f)))
            // Every move still redraws the canvas (EXACT: the v1.5 invalidation).
            assertTrue("move $i redraws", c.tiles.hasDirty)
        }
        assertEquals("no layer-list recomposition per move", layersBefore, c.layersVersion)
        assertEquals("the sheet follows every move", versionBefore + 25, edit.version)
        assertEquals("no step yet", steps, c.undoManager.undoCount)
        assertFalse("EXACT: no live session", c.liveAdjust.isActive)

        // Amount (opacity) moves don't bump it either.
        for (i in 1..5) edit.preview(adj.adjustment, 1f - i / 10f, adj.name)
        assertEquals(layersBefore, c.layersVersion)
        assertEquals(versionBefore + 30, edit.version)

        // The step is recorded at flush, and the layer UI refreshes then.
        edit.flush()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
        assertTrue("flush refreshes the layer UI", c.layersVersion > layersBefore)
    }

    @Test
    fun aNewAutomaticNameRefreshesTheLayerListOnce() {
        val adj = setup()
        val edit = tool.adjustmentEdit(adj)
        val before = c.layersVersion
        val invert = FilterRegistry.byId("adjust.invert")!!
        edit.preview(AdjustmentEffects.spec(invert, invert.defaultValues()), adj.opacity, "Invert 1")
        assertEquals("Invert 1", adj.name)
        assertEquals("one bump for the new name", before + 1, c.layersVersion)
        // Further moves with the same name: no bump.
        edit.preview(adj.adjustment, 0.5f, "Invert 1")
        assertEquals(before + 1, c.layersVersion)
        edit.flush()
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
        c.undo()
        assertEquals("Tone 1", adj.name)
    }

    @Test
    fun revertPutsTheLayerBackAndRefreshesTheLayerList() {
        val adj = setup()
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec0 = adj.adjustment
        val edit = tool.adjustmentEdit(adj)
        val steps = c.undoManager.undoCount
        edit.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", 1f)))
        val before = c.layersVersion
        edit.revert()
        assertEquals(spec0, adj.adjustment)
        assertTrue(c.layersVersion > before)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertFalse(edit.isPending)
    }
}
