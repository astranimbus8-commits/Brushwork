package com.brushwork.paint.v110.a8

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.transform.LayerBoundsCache
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 (7/7 review, design 2.2, J5): asking the smart-guide cache for the bounds of many big
 * parked layers (a Transform or a snap session starting over a phone-size document) costs main
 * less than a T606 frame: the layers are queued and read off main. Before the review each was
 * edge-scanned on main: 352.8 ms PC median for 24 parked 1080 x 2408 layers (half masked,
 * `F1Pixels.mixed`), 14.7 ms per layer, about 17.5 s for 199 layers at x6. The budget is 16 ms on
 * the T606 for 24 layers, so 2.5 ms on the PC. Phone size off CI only (`CI` unset).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class A8BoundsRequestTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = FakeSwapStore())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        scope.cancel()
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        baseline.assertBack(docs)
    }

    @Test
    fun askingForTheBoundsOfManyBigParkedLayersCostsMainLessThanAFrame() {
        assumeTrue(System.getenv("CI") == null)
        val w = 1080
        val h = 2408
        val n = 24
        assertTrue(w.toLong() * h > LayerBoundsCache.SYNC_PIXELS)
        val d = Document("a8", "a8", w, h).also { docs += it }
        repeat(n + 1) { i ->
            d.layers += Layer(d.newLayerId(), "Layer $i", F1Pixels.mixed(w, h, 300L + i)).apply {
                if (i % 2 == 1) mask = F1Pixels.mixedMask(w, h, 500L + i)
            }
        }
        d.activeLayerIndex = n
        val (p, _) = A8Kit.policy(d)
        A8Kit.idle()
        p.idleTick()
        A8Kit.advance(5000)
        p.idleTick()
        A8Kit.drain()
        val layers = d.layers.take(n)
        layers.forEach { assertEquals("${it.name} parked", ResidencyState.PARKED, it.residency) }

        PerfBudget.measuredWithin(2.5, what = "request over $n parked ${w}x$h layers on main") {
            val runs = DoubleArray(5) {
                val cache = LayerBoundsCache(scope, detectLines = true) {}
                try {
                    PerfBudget.timeMs { cache.request(layers) }
                } finally {
                    cache.clear()
                }
            }
            runs.sorted()[2].also { println("v110 A8 bounds request: $n parked ${w}x$h layers on main, median %.3f ms (x6 %.1f ms)".format(it, it * 6)) }
        }
    }
}
