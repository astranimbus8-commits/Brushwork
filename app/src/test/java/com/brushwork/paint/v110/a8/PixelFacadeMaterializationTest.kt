package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 6 (design 2.2, 2.16 "Facade and residency", I28): under `PixelFacade.strict` (a
 * materialization outside the allow-list throws) an editor at FULL over 200 layers, every other one
 * masked, all parked but the active one, materializes nothing for: a frame composited from the
 * parked stores, a pixel edit pushed to the history (and its undo and redo, also once its layer is
 * parked), a delete of a parked masked layer pushed and undone, `LayerBudget.used`, and a layer
 * switch, which makes only the new active layer FLAT. (Save and autosave read the stores in A11's
 * `LayerEntries`; the layers panel is A9's, after its step 4.)
 */
@RunWith(RobolectricTestRunner::class)
class PixelFacadeMaterializationTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap, strict = true)

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
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** An editor over [n] noisy 128 x 96 layers (every odd one masked), the top one active, every other one parked. */
    private fun parkedEditor(n: Int = 200): EditorController {
        val d = Document("a8", "a8", W, H).also { docs += it }
        repeat(n) { i ->
            d.layers += Layer(d.newLayerId(), "Layer $i", F1Pixels.mixed(W, H, 300L + i).also { made += it }).apply {
                if (i % 2 == 1) mask = F1Pixels.mixedMask(W, H, 900L + i).also { made += it }
            }
        }
        d.activeLayerIndex = n - 1
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), d, sc).also { controller = it }
        assertNotNull("the editor installed its policy at FULL", EngineConfig.residency as? ResidencyPolicy)
        A8Kit.idle()
        A8Kit.advance(3000)
        A8Kit.drain()
        for (l in d.layers) {
            val expected = if (l === d.activeLayer) ResidencyState.FLAT else ResidencyState.PARKED
            assertEquals("${l.name}", expected, l.residency)
        }
        return c
    }

    /** Runs [step] and asserts it materialized nothing (strict would have thrown). */
    private fun none(what: String, step: () -> Unit) {
        val before = PixelFacade.materializations
        step()
        assertEquals("$what materializes nothing", before, PixelFacade.materializations)
    }

    @Test
    fun aFrameOverParkedMaskedLayersMaterializesNothing() {
        val c = parkedEditor()
        val out = BitmapUtils.createLayerBitmap(W, H).also { made += it }
        none("a frame") { Compositor(c.doc) { null }.drawDocument(Canvas(out), null, target = null) }
        assertTrue("the parked layers stay parked", c.doc.layers.count { it.residency == ResidencyState.PARKED } == c.doc.layers.size - 1)
    }

    @Test
    fun anEditPushedUndoneAndRedoneMaterializesNothing() {
        val c = parkedEditor()
        val active = c.doc.activeLayer
        none("a pixel edit pushed") { A8Kit.paint(c, active, Rect(10, 10, 100, 80), 0xFF3366AA.toInt()) }
        none("its undo") { c.undo() }
        none("its redo") { c.redo() }

        // The edited layer parked: its undo and redo swap tiles in place.
        none("a switch") {
            c.selectLayer(c.doc.layers[0])
            A8Kit.drain()
        }
        A8Kit.advance(3000)
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, active.residency)
        none("an undo on a parked layer") { c.undo() }
        none("a redo on a parked layer") { c.redo() }
        assertEquals(ResidencyState.PARKED, active.residency)
    }

    @Test
    fun aDeleteOfAParkedMaskedLayerAndLayerBudgetUsedMaterializeNothing() {
        val c = parkedEditor()
        val masked = c.doc.layers[101]
        assertTrue(masked.hasMask)
        assertEquals(ResidencyState.PARKED, masked.residency)
        none("LayerBudget.used") { LayerBudget.current.used(c.doc) }
        none("a delete pushed") { c.deleteLayer(masked) }
        assertEquals(199, c.doc.layers.size)
        none("its undo") { c.undo() }
        assertSame(masked, c.doc.layers[101])
        none("LayerBudget.used again") { LayerBudget.current.used(c.doc) }
    }

    @Test
    fun aSwitchMakesOnlyTheNewActiveLayerFlat() {
        val c = parkedEditor()
        val next = c.doc.layers[51]
        none("a switch") {
            c.selectLayer(next)
            A8Kit.drain()
        }
        assertSame(next, c.doc.activeLayer)
        for (l in c.doc.layers) {
            val expected = if (l === next || l === c.doc.layers.last()) ResidencyState.FLAT else ResidencyState.PARKED
            assertEquals("${l.name}", expected, l.residency)
        }
    }

    private companion object {
        const val W = 128
        const val H = 96
    }
}
