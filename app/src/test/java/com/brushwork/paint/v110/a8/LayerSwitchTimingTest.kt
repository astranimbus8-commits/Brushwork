package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
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
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 3 (design 2.7, 2.17 "Layer switch, phone, tiles resident"): `selectLayer` to a
 * PARKED layer whose tiles are all resident makes it FLAT synchronously, within PC 10 ms (the
 * T606's 60 ms over the x 6 proxy; x 4 on CI): the median of 15 switches after 3 warm-ups, the
 * layer parked again (its own tick, 2 s after it was left) before each. On a 512 x 512 document
 * always; at the phone's 1080 x 2408 off CI only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class LayerSwitchTimingTest {
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

    /** An editor over a [w] x [h] two-layer document (layer 1 active) with its policy, tiles never sent out. */
    private fun editor(w: Int, h: Int): EditorController {
        val doc = Document("a8", "a8", w, h).also { docs += it }
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer $i", F1Pixels.mixed(w, h, 40L + i).also { made += it }) }
        doc.activeLayerIndex = 1
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        (EngineConfig.residency as ResidencyPolicy).tileRam = { Long.MAX_VALUE }
        return c
    }

    /** The median ms of [count] switches to layer 0 of [c], parked again before each, after [warm] warm-ups. */
    private fun switches(c: EditorController, count: Int = 15, warm: Int = 3): Double {
        val target = c.doc.layers[0]
        val other = c.doc.layers[1]
        val ms = DoubleArray(count)
        for (k in 0 until warm + count) {
            c.selectLayer(other)
            A8Kit.advance(2600)
            A8Kit.drain()
            assertEquals("parked before switch $k", ResidencyState.PARKED, target.residency)
            val t0 = System.nanoTime()
            c.selectLayer(target)
            val dt = (System.nanoTime() - t0) / 1e6
            assertEquals("FLAT when selectLayer returns", ResidencyState.FLAT, target.residency)
            assertNull("no hold", c.activating)
            if (k >= warm) ms[k - warm] = dt
        }
        return median(ms)
    }

    @Test
    fun aSwitchToAParkedLayerWithResidentTiles512() {
        val c = editor(512, 512)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(10.0), what = "a switch to a parked 512 x 512 layer") { switches(c) }
        println("v110 A8 LayerSwitchTimingTest 512x512: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 10 ms, device 60 ms)".format(ms, ms * 6))
    }

    @Test
    fun aSwitchToAParkedPhoneLayerWithResidentTiles() {
        assumeTrue("the phone-size probe runs off CI", System.getenv("CI") == null)
        val c = editor(1080, 2408)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(10.0), what = "a switch to a parked 1080 x 2408 layer") { switches(c) }
        println("v110 A8 LayerSwitchTimingTest 1080x2408: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; budget PC 10 ms, device 60 ms)".format(ms, ms * 6))
    }
}
