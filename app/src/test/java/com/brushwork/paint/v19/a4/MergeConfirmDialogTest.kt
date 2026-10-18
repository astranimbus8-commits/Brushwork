package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 (R19, A4): the layer window's "Merge down" on two data layers that would become pixels asks
 * first: [LayerLabels19.MERGE_TITLE], the reason in the body. Cancel leaves everything as it was
 * (no step); [LayerLabels19.MERGE_CONFIRM] merges as v1.8 did (one step) and says so
 * ([LayerLabels19.MERGED_AS_PIXELS]); [LayerLabels19.KEEP_EDITABLE], offered only for
 * [LayerLabels19.WHY_OVERLAP], keeps the objects (one step). A pair that keeps its type merges at
 * once, without the question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class MergeConfirmDialogTest {

    private class Pair3(val c: EditorController, val lower: Layer, val upper: Layer)

    /**
     * The editor with "Lower" (one box) and "Upper" (two overlapping boxes, active) vector layers
     * over the white "Layer 1", [setup] applied, and the layer window open.
     */
    private fun editor(h: Qa19Harness, kit: A4Kit, setup: (EditorController, Layer, Layer) -> Unit): Pair3 {
        lateinit var lower: Layer
        lateinit var upper: Layer
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true)) { c ->
            lower = kit.vectorLayer(c, listOf(kit.box(40f, 40f, 160f, 140f)), "Lower")
            upper = kit.vectorLayer(c, listOf(kit.box(120f, 100f, 240f, 200f, 0xFF3070D0.toInt()), kit.box(200f, 150f, 320f, 250f)), "Upper")
            setup(c, lower, upper)
            kit.settle(c)
        }
        Qa19Ui.click("Open layers")
        assertEquals("the upper layer is active", upper, s.c.activeLayer)
        return Pair3(s.c, lower, upper)
    }

    private fun dialogShown(): Boolean = Qa19Ui.tagged(V19Tags.MERGE_DIALOG) != null

    @Test
    fun theMergeDialog() = Qa19Ui.uiTest { h ->
        val kit = A4Kit(400, 300)
        val multiply: (EditorController, Layer, Layer) -> Unit = { c, _, u -> c.setLayerProps(u, u.props().copy(blendMode = LayerBlendMode.MULTIPLY)) }
        h.section("Cancel: nothing merged, no step") {
            val p = editor(h, kit, multiply)
            val steps = p.c.undoManager.undoCount
            Qa19Ui.click(LayerLabels.MERGE, exact = true)
            assertTrue("the question", dialogShown())
            assertTrue(Qa19Ui.has(LayerLabels19.MERGE_TITLE, exact = true))
            assertTrue("the reason", Qa19Ui.has(String.format(LayerLabels19.MERGE_BODY, LayerLabels19.WHY_BLEND), exact = true))
            assertFalse("no Keep editable for a blend mode", Qa19Ui.has(LayerLabels19.KEEP_EDITABLE, exact = true))
            assertEquals("nothing yet", steps, p.c.undoManager.undoCount)
            Qa19Ui.click("Cancel", exact = true)
            assertFalse("closed", dialogShown())
            assertEquals("no step", steps, p.c.undoManager.undoCount)
            assertEquals(listOf("Layer 1", "Lower", "Upper"), p.c.doc.layers.map { it.name })
            assertTrue(p.lower.isVectorLayer && p.upper.isVectorLayer)
        }
        h.section("Merge as pixels: one step, pixels, the confirmation") {
            val p = editor(h, kit, multiply)
            val steps = p.c.undoManager.undoCount
            Qa19Ui.click(LayerLabels.MERGE, exact = true)
            Qa19Ui.click(LayerLabels19.MERGE_CONFIRM, exact = true)
            assertFalse("closed", dialogShown())
            assertEquals("one step", steps + 1, p.c.undoManager.undoCount)
            assertEquals("Merge down", p.c.undoManager.undoLabel)
            assertEquals(listOf("Layer 1", "Lower"), p.c.doc.layers.map { it.name })
            assertFalse("pixels", p.lower.isVectorLayer)
            assertTrue("the confirmation", Qa19Ui.has(String.format(LayerLabels19.MERGED_AS_PIXELS, LayerLabels19.WHY_BLEND), exact = true))
            p.c.undo()
            kit.settle(p.c)
            assertEquals("undone", listOf("Layer 1", "Lower", "Upper"), p.c.doc.layers.map { it.name })
            assertTrue(p.lower.isVectorLayer)
        }
        h.section("Keep editable: see-through overlapping objects stay objects") {
            val p = editor(h, kit) { c, _, u -> c.setLayerProps(u, u.props().copy(opacity = 0.5f)) }
            assertEquals(LayerLabels19.WHY_OVERLAP, p.c.layerMerge.rasterReason(p.upper, p.lower))
            val steps = p.c.undoManager.undoCount
            Qa19Ui.click(LayerLabels.MERGE, exact = true)
            assertTrue(Qa19Ui.has(String.format(LayerLabels19.MERGE_BODY, LayerLabels19.WHY_OVERLAP), exact = true))
            assertTrue(Qa19Ui.has(LayerLabels19.KEEP_EDITABLE_NOTE, exact = true))
            Qa19Ui.click(LayerLabels19.KEEP_EDITABLE, exact = true)
            kit.settle(p.c)
            assertFalse("closed", dialogShown())
            assertEquals("one step", steps + 1, p.c.undoManager.undoCount)
            assertTrue("objects kept", p.lower.isVectorLayer)
            assertEquals(listOf(1f, 0.5f, 0.5f), p.lower.vector!!.objects.map { it.opacity })
            assertEquals("the layer at 100 %", 1f, p.lower.opacity)
            assertFalse("no pixels confirmation", Qa19Ui.has(LayerLabels19.MERGED_AS_PIXELS.substringBefore("%")))
        }
        h.section("a pair that keeps its type: merged at once, no question") {
            val p = editor(h, kit) { _, _, _ -> }
            val steps = p.c.undoManager.undoCount
            Qa19Ui.click(LayerLabels.MERGE, exact = true)
            kit.settle(p.c)
            assertFalse("no question", dialogShown())
            assertEquals(steps + 1, p.c.undoManager.undoCount)
            assertNotNull(p.lower.vector)
            assertEquals(3, p.lower.vector!!.objects.size)
        }
        kit.close()
    }
}
