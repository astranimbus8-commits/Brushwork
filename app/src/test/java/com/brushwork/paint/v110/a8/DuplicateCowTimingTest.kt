package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 5 (design 2.6, 2.17 engine proxy `DuplicateCowTimingTest`): "Duplicate layer" of a
 * PARKED layer through the editor (`duplicateLayer`: the tool paused, the copy-on-write
 * `snapshot()` of its content and mask, the insert and its undo step) within PC 2 ms (device
 * 12 ms over the x 6 proxy; x 4 on CI). Section 2.17 gives this proxy no row: the budget is the
 * lead's to confirm; it is set where a copy of the layer's pixels could never fit (v1.9 copied
 * 10 MB for a phone layer) and the snapshot's O(tiles) cost easily does. The median of 15
 * duplicates after 3 warm-ups, each undone (and dropped from the redo stack by the next one), the
 * source parked throughout. On a 512 x 512 document always; at the phone's 1080 x 2408 off CI
 * only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class DuplicateCowTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = FakeSwapStore())

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

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** An editor over a [w] x [h] two-layer document (layer 1 active, layer 0 masked and parked) with its policy, tiles never sent out. */
    private fun editor(w: Int, h: Int): EditorController {
        val doc = Document("a8", "a8", w, h).also { docs += it }
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer $i", F1Pixels.mixed(w, h, 60L + i).also { made += it }) }
        doc.layers[0].mask = F1Pixels.mixedMask(w, h, 160L).also { made += it }
        doc.activeLayerIndex = 1
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        (EngineConfig.residency as ResidencyPolicy).tileRam = { Long.MAX_VALUE }
        A8Kit.idle()
        A8Kit.advance(2600)
        A8Kit.drain()
        assertEquals("parked", ResidencyState.PARKED, doc.layers[0].residency)
        return c
    }

    /** The median ms of [count] duplicates of layer 0 of [c], each undone, after [warm] warm-ups. */
    private fun duplicates(c: EditorController, count: Int = 15, warm: Int = 3): Double {
        val source = c.doc.layers[0]
        val ms = DoubleArray(count)
        for (k in 0 until warm + count) {
            assertEquals("parked before duplicate $k", ResidencyState.PARKED, source.residency)
            val t0 = System.nanoTime()
            val copy = c.duplicateLayer(source)
            val dt = (System.nanoTime() - t0) / 1e6
            assertNotNull(copy)
            assertTrue("a copy-on-write copy", copy!!.pixels is TiledPixels && copy.maskPixels is TiledPixels)
            c.undo()
            A8Kit.idle()
            if (k >= warm) ms[k - warm] = dt
        }
        return median(ms)
    }

    @Test
    fun aDuplicateOfAParkedLayer512() {
        val c = editor(512, 512)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "a duplicate of a parked 512 x 512 layer") { duplicates(c) }
        println("v110 A8 DuplicateCowTimingTest 512x512: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC %.0f ms, device %.0f ms)".format(ms, ms * 6, BUDGET_MS, BUDGET_MS * 6))
    }

    @Test
    fun aDuplicateOfAParkedPhoneLayer() {
        assumeTrue("the phone-size probe runs off CI", System.getenv("CI") == null)
        val c = editor(1080, 2408)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "a duplicate of a parked 1080 x 2408 layer") { duplicates(c) }
        println("v110 A8 DuplicateCowTimingTest 1080x2408: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC %.0f ms, device %.0f ms)".format(ms, ms * 6, BUDGET_MS, BUDGET_MS * 6))
    }

    private companion object {
        /** PC ms (device x 6). */
        const val BUDGET_MS = 2.0
    }
}
