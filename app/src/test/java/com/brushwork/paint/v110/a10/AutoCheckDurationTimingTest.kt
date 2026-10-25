package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A10 (design 2.10 and 2.17, J13): the whole automatic check stays within its ≤ 3 s on the
 * T606 through the x 6 proxy: PC [BUDGET_MS] for one run of M1-M5 with the real M3 (Deflate of 48
 * tiles) and the real M5 (the 64-layer composite and the Classic A/B), M1, M2 and M4 through the
 * fake probes (the phone's allocation and storage speeds are device checks, Q1). The median of
 * [RUNS] runs after [WARM] warm-ups, each a new first run on a new runner.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class AutoCheckDurationTimingTest {
    @get:Rule
    val engine = EngineRule(memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication()

    /** One first run of the check with the real M3 and M5, in ms. */
    private fun oneRun(): Double {
        CheckStore.file(app).delete()
        val runner = A10Kit.runner(app, A10Kit.Env(), A10Kit.Steps(app, m3 = null, m5 = null))
        val t0 = System.nanoTime()
        val outcome = runner.runIfDue { false }
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(AutoCheckRunner.Outcome.DONE, outcome)
        return ms
    }

    private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    @Test
    fun theWholeCheckStaysWithinItsProxyBudget() {
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), tries = 2, what = "the automatic check, M1-M5") {
            repeat(WARM) { oneRun() }
            median(List(RUNS) { oneRun() })
        }
        val r = CheckStore.read(app)!!
        assertTrue("its own duration is stored", r.durationMs > 0)
        assertTrue("M3 measured", r.deflateMBs > 0f && r.ratio > 1f)
        assertTrue("M5 measured", r.composite64Ms > 0f)
        println(
            "v110 A10 AutoCheckDurationTimingTest: the check M1-M5: median %.1f ms of %d after %d warm-ups (x 6 = %.0f ms; device budget 3000 ms); deflate %.0f MB/s, inflate %.0f MB/s, ratio %.2f, composite %.1f ms, below cache %s"
                .format(ms, RUNS, WARM, ms * 6, r.deflateMBs, r.inflateMBs, r.ratio, r.composite64Ms, r.belowCacheMinLayers),
        )
    }

    private companion object {
        /** 3 s on the T606 over the x 6 proxy. */
        const val BUDGET_MS = 500.0
        const val RUNS = 15
        const val WARM = 3
    }
}
