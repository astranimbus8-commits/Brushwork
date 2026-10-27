package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.MemSnapshot
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.EngineLabels110
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 A10 merge (design 2.12, the lead's commit): `NativeWatch.refusingNewLayers` has its
 * reader. From a CRITICAL watch tick (available memory under `threshold` + 256 MiB) until available
 * memory is back above `threshold` + 512 MiB, `MemoryBudget.admitNative` refuses
 * `NativePurpose.LAYER` while every other purpose is admitted as before; so the first write to a
 * blank or parked layer is refused before any pixel changes (no undo step, the residency kept)
 * and says [EngineLabels110.PAINT_REFUSED], also at FULL with a swap store (where a full store
 * would say [EngineLabels110.STORAGE_FULL]). Above `threshold` + 512 MiB the same stroke lands. A
 * new `EditorSession` refuses nothing.
 *
 * The session is the T606 at FULL with a roomy swap store and a residency that parks and swaps
 * nothing ([Quiet]); the governor's trims are noted, not run.
 */
@RunWith(RobolectricTestRunner::class)
class NativeWatchRefusesNewLayersTest {
    private val memory = FakeMemoryProbe(MemoryPreset.T606)

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = memory,
        storage = FakeStorageProbe(),
        exits = FakeExitInfo(),
        residency = Quiet(),
        swap = FakeSwapStore(capacityBytes = 1L shl 30),
    )

    private val mib = 1L shl 20
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null
    private val made = mutableListOf<Bitmap>()
    private lateinit var policy: GovernorPolicy
    private lateinit var gc: () -> Unit

    @Before
    fun setUp() {
        policy = MemoryGovernor.policy
        MemoryGovernor.policy = object : GovernorPolicy {
            override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) = Unit
            override fun admit(bytes: Long, purpose: String): Boolean = true
        }
        gc = NativeWatch.requestGc
        NativeWatch.requestGc = {}
        NativeWatch.reset()
    }

    @After
    fun tearDown() {
        NativeWatch.reset()
        controller?.dispose()
        scope?.cancel()
        idle()
        made.forEach { it.recycle() }
        NativeWatch.requestGc = gc
        MemoryGovernor.policy = policy
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** One watch tick with [aboveThreshold] of available memory over the threshold and no ledger drift. */
    private fun tick(aboveThreshold: Long): NativeWatch.Tick {
        memory.availMem = memory.threshold + aboveThreshold
        memory.nativeAllocated = EngineConfig.budget.ledger.native
        val s: MemSnapshot = memory.snapshot()
        return NativeWatch.tick(s)
    }

    /** Whether [purpose] is admitted for [bytes] now; a yes is given back at once. */
    private fun admits(purpose: NativePurpose, bytes: Long = mib): Boolean {
        val b = EngineConfig.budget
        val yes = b.admitNative(bytes, purpose)
        if (yes) b.release(bytes, purpose)
        return yes
    }

    private fun open(top: (Document) -> Layer): Pair<EditorController, Document> {
        val doc = Smoke.document(256, 256, layers = 1)
        doc.layers[0].bitmap.eraseColor(0xFF406020.toInt())
        doc.layers += top(doc)
        doc.activeLayerIndex = 1
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        idle()
        return c to doc
    }

    private fun stroke(c: EditorController) {
        c.pointerDown(ToolPoint(30f, 200f))
        for (i in 1..12) c.pointerMove(ToolPoint(30f + 15f * i, 200f - 12f * i))
        c.pointerUp(ToolPoint(210f, 56f))
        idle()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun aCriticalTickRefusesOnlyLayerAdmissionsUntilPlus512MiB() {
        assertTrue("plenty: a layer is admitted", admits(NativePurpose.LAYER))
        assertTrue(tick(100 * mib).critical)
        assertTrue(NativeWatch.refusingNewLayers)
        assertFalse("CRITICAL: no new painted layer", admits(NativePurpose.LAYER))
        for (p in listOf(NativePurpose.MASK, NativePurpose.TILE, NativePurpose.TRANSIENT, NativePurpose.DISPLAY, NativePurpose.UNDO)) {
            assertTrue("$p is admitted as before", admits(p))
        }
        assertFalse("between the lines: still refused", tick(400 * mib).critical)
        assertFalse(admits(NativePurpose.LAYER))
        tick(513 * mib)
        assertFalse(NativeWatch.refusingNewLayers)
        assertTrue("above +512 MiB: layers again", admits(NativePurpose.LAYER))
    }

    @Test
    fun whileRefusingABlankLayersFirstWriteIsRefusedForMemoryThenLandsAfterRecovery() {
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Blank", BlankPixels(256, 256)) }
        val layer = doc.layers[1]
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        val bottom = pixels(doc.layers[0].bitmap)
        assertEquals("FULL with a store: a refusal would be for storage", EngineLabels110.STORAGE_FULL, LayerBudgetV110.firstWriteRefusal())

        tick(100 * mib)
        assertEquals("while refusing it is for memory", EngineLabels110.PAINT_REFUSED, LayerBudgetV110.firstWriteRefusal())
        c.message = null
        stroke(c)
        assertEquals("the refusal is said", EngineLabels110.PAINT_REFUSED, c.message)
        assertEquals("no undo step", steps, c.undoManager.undoCount)
        assertEquals("still blank", ResidencyState.BLANK, layer.residency)
        assertEquals(generation, layer.pixelGeneration)
        assertTrue("nothing else drawn", bottom.contentEquals(pixels(doc.layers[0].bitmap)))

        tick(513 * mib)
        assertEquals(EngineLabels110.STORAGE_FULL, LayerBudgetV110.firstWriteRefusal())
        c.message = null
        stroke(c)
        assertEquals("the stroke lands", steps + 1, c.undoManager.undoCount)
        assertEquals(ResidencyState.FLAT, layer.residency)
    }

    @Test
    fun whileRefusingAParkedLayerKeepsItsPixels() {
        val painted = Smoke.document(256, 256, layers = 1).layers[0].bitmap.also { made += it }
        painted.eraseColor(0xC0108040.toInt())
        val before = pixels(painted)
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Parked", TiledPixels.fromBitmap(painted)) }
        val layer = doc.layers[1]
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        tick(100 * mib)
        c.message = null
        stroke(c)
        assertEquals(EngineLabels110.PAINT_REFUSED, c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ResidencyState.PARKED, layer.residency)
        assertEquals(generation, layer.pixelGeneration)
        tick(513 * mib)
        assertTrue(before.contentEquals(pixels(layer.bitmap)))
    }

    @Test
    fun aNewEditorSessionRefusesNothing() {
        tick(100 * mib)
        assertTrue(NativeWatch.refusingNewLayers)
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        app.openEditor("00000000-0000-0000-0000-0000000a10c1")
        try {
            assertFalse("the last editor's refusal ends with the new session", NativeWatch.refusingNewLayers)
        } finally {
            var closed = false
            app.closeEditor { closed = true }
            assertTrue(A10Kit.pumpUntil { closed })
        }
    }

    /** A residency that parks, swaps and frees nothing. */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) = Unit

        override fun idleTick() = Unit
    }
}
