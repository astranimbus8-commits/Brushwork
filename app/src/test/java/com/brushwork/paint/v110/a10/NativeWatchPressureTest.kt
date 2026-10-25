package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineStat
import com.brushwork.paint.engine.EngineStats
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.MemSnapshot
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A10 (design 2.12, step 5): `NativeWatch`'s pressure net. With available memory under
 * `threshold` + 256 MiB a tick acts as CRITICAL: the residency policy hears CRITICAL (it parks
 * every eligible layer and swaps tiles down) and the governor's caches are trimmed as for a
 * CRITICAL trim, at every such tick; new painted layers are refused from then until available
 * memory is above `threshold` + 512 MiB (in between nothing changes). A ledger drift of more than
 * 256 MiB from the native heap requests one background GC per episode. Unknown memory does
 * nothing. Every tick records the ledger's total. Under Robolectric the editor runs no watch
 * unless a test asks.
 */
@RunWith(RobolectricTestRunner::class)
class NativeWatchPressureTest {
    private val heard = mutableListOf<MemoryPressure>()

    /** A residency policy that notes what it hears. */
    private val hooks = object : ResidencyHooks {
        override fun activate(layer: Layer) = Unit
        override fun deactivated(layer: Layer) = Unit
        override fun pressure(p: MemoryPressure) {
            heard += p
        }
        override fun idleTick() = Unit
    }

    private val memory = FakeMemoryProbe(MemoryPreset.T606)

    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, memory = memory, residency = hooks)

    private val trims = mutableListOf<MemoryPressure>()
    private lateinit var policy: GovernorPolicy
    private var gcs = 0
    private lateinit var gc: () -> Unit

    @Before
    fun setUp() {
        policy = MemoryGovernor.policy
        MemoryGovernor.policy = object : GovernorPolicy {
            override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) {
                trims += pressure
            }
            override fun admit(bytes: Long, purpose: String): Boolean = true
        }
        gc = NativeWatch.requestGc
        NativeWatch.requestGc = { gcs++ }
        NativeWatch.reset()
        EngineStats.keepUnderRobolectric = true
        EngineStats.reset()
    }

    @After
    fun tearDown() {
        MemoryGovernor.policy = policy
        NativeWatch.requestGc = gc
        NativeWatch.reset()
        EngineStats.reset()
        EngineStats.keepUnderRobolectric = false
    }

    private val mib = 1L shl 20

    /** A snapshot with [aboveThreshold] of available memory over the threshold and the native heap at the ledger plus [drift]. */
    private fun snap(aboveThreshold: Long, drift: Long = 0L): MemSnapshot {
        memory.availMem = memory.threshold + aboveThreshold
        memory.nativeAllocated = EngineConfig.budget.ledger.native + drift
        return memory.snapshot()
    }

    @Test
    fun underThresholdPlus256MiBItActsAsCriticalAndRefusesUntilPlus512MiB() {
        assertFalse("plenty", NativeWatch.tick(snap(1024 * mib)).critical)
        assertTrue("nothing heard", heard.isEmpty() && trims.isEmpty())
        assertFalse(NativeWatch.refusingNewLayers)

        assertFalse("just at +256 MiB: not yet", NativeWatch.tick(snap(256 * mib)).critical)
        val t = NativeWatch.tick(snap(255 * mib))
        assertTrue("under +256 MiB: CRITICAL", t.critical)
        assertTrue("refusing", t.refusing && NativeWatch.refusingNewLayers)
        assertEquals("the residency policy hears CRITICAL", listOf(MemoryPressure.CRITICAL), heard)
        assertEquals("the governor trims as for CRITICAL", listOf(MemoryPressure.CRITICAL), trims)

        NativeWatch.tick(snap(100 * mib))
        assertEquals("every critical tick acts", 2, heard.size)
        assertEquals(2, trims.size)

        val between = NativeWatch.tick(snap(400 * mib))
        assertFalse("between the lines: not critical", between.critical)
        assertTrue("still refusing until +512 MiB", between.refusing)
        assertEquals("nothing more heard", 2, heard.size)
        assertTrue(NativeWatch.tick(snap(512 * mib)).refusing)

        assertFalse("above +512 MiB: new layers again", NativeWatch.tick(snap(513 * mib)).refusing)
        assertFalse(NativeWatch.refusingNewLayers)

        NativeWatch.tick(snap(10 * mib))
        assertTrue(NativeWatch.refusingNewLayers)
        NativeWatch.reset()
        assertFalse("a new session refuses nothing", NativeWatch.refusingNewLayers)
    }

    @Test
    fun aDriftOver256MiBRequestsOneGcPerEpisode() {
        assertFalse(NativeWatch.tick(snap(1024 * mib, drift = 256 * mib)).gcRequested)
        assertEquals("at 256 MiB: none", 0, gcs)
        assertTrue(NativeWatch.tick(snap(1024 * mib, drift = 300 * mib)).gcRequested)
        assertEquals(1, gcs)
        assertFalse("the same episode: no second GC", NativeWatch.tick(snap(1024 * mib, drift = 400 * mib)).gcRequested)
        assertEquals(1, gcs)
        NativeWatch.tick(snap(1024 * mib, drift = 10 * mib))
        assertTrue("a new episode", NativeWatch.tick(snap(1024 * mib, drift = -300 * mib)).gcRequested)
        assertEquals("the ledger above the heap counts too", 2, gcs)
        assertTrue("the drift alone is not pressure", heard.isEmpty())
    }

    @Test
    fun unknownMemoryDoesNothingAndEveryTickRecordsTheLedger() {
        val ledger = EngineConfig.budget.ledger
        ledger.add(LedgerCategory.TILES, 3 * mib)
        try {
            val unknown = MemoryPreset.UNKNOWN.snapshot
            val t = NativeWatch.tick(unknown)
            assertFalse(t.critical || t.refusing || t.gcRequested)
            assertTrue(heard.isEmpty() && trims.isEmpty() && gcs == 0)
            NativeWatch.tick(snap(1024 * mib))
            val peak = EngineStats.summary().single { it.stat == EngineStat.LEDGER_PEAK }
            assertEquals("each tick records the ledger's total", 2, peak.n)
            assertEquals(ledger.native.toFloat(), peak.p95)
        } finally {
            ledger.sub(LedgerCategory.TILES, 3 * mib)
        }
    }

    @Test
    fun underRobolectricTheEditorRunsNoWatchUnlessATestAsks() {
        assertFalse(NativeWatch.watchRuns)
        NativeWatch.runUnderRobolectric = true
        try {
            assertTrue(NativeWatch.watchRuns)
        } finally {
            NativeWatch.runUnderRobolectric = false
        }
    }
}
