package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.check.AutoCheck
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckInputs
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.engine.check.Decision
import com.brushwork.paint.engine.check.M2Outcome
import com.brushwork.paint.engine.check.M5Result
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A10 (design 2.10, step 1): the automatic check's decision. The four cases the design
 * names, through the runner and its cold starts: (a) a 4 GB phone whose first run finds little
 * free memory (M2 `MARGIN_LIMITED`) reaches FULL at the next cold start; (b) a run interrupted at
 * M2 re-runs at the next cold start, not at the next update; (c) a low-RAM phone stays CLASSIC
 * (M2 never runs); (d) an update re-run with a slow M5 keeps an existing FULL. Then the rules,
 * their boundaries and the provisional runs as probe sets on [AutoCheck.decide].
 */
@RunWith(RobolectricTestRunner::class)
class AutoCheckDecisionTest {
    private val memory = FakeMemoryProbe(MemoryPreset.T606)
    private val storage = FakeStorageProbe()
    private val exits = FakeExitInfo()

    @get:Rule
    val engine = EngineRule(memory = memory, storage = storage, exits = exits)

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun caseA_aFourGigabytePhoneBusyAtTheFirstRunReachesFullAtTheNextColdStart() {
        val env = A10Kit.Env()
        memory.availMem = 1100L shl 20 // M2's cap: 1100 - 216 - 768 = 116 MiB, below 128 MiB.
        val first = A10Kit.runner(app, env)
        assertEquals(AutoCheckRunner.Outcome.DONE, first.runIfDue { false })
        val provisional = CheckStore.read(app)!!
        assertEquals(EngineLevel.CLASSIC, provisional.level)
        assertTrue(provisional.provisional)
        assertEquals(M2Outcome.MARGIN_LIMITED, provisional.m2Outcome)
        assertEquals(1, provisional.inconclusiveRuns)
        // Not again in the same process.
        assertEquals(AutoCheckRunner.Outcome.NOT_DUE, first.runIfDue { false })

        // The next cold start, with room.
        memory.availMem = 1843L shl 20
        env.clock.advance(60_000)
        val next = A10Kit.runner(app, env)
        assertEquals(AutoCheckRunner.Outcome.DONE, next.runIfDue { false })
        val full = CheckStore.read(app)!!
        assertEquals(EngineLevel.FULL, full.level)
        assertFalse(full.provisional)
        assertEquals(M2Outcome.REACHED, full.m2Outcome)
        assertEquals(0, full.inconclusiveRuns)
        assertEquals(AutoCheckRunner.Outcome.NOT_DUE, A10Kit.runner(app, env).runIfDue { false })
    }

    @Test
    fun caseB_anInterruptedRunReRunsAtTheNextColdStartNotAtTheNextUpdate() {
        val env = A10Kit.Env()
        CheckStore.write(app, A10Kit.result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, ranAt = env.clock.nowMillis))
        // A stored result of this version: nothing is due.
        assertEquals(AutoCheckRunner.Outcome.NOT_DUE, A10Kit.runner(app, env).runIfDue { false })
        // A run that died in M2 (a swipe: no low-memory exit) left its marker.
        env.clock.advance(10_000)
        CheckMarker.write(app, CheckMarker.Marker(env.clock.nowMillis, 1))
        env.clock.advance(10_000)
        val runner = A10Kit.runner(app, env)
        assertEquals("same version, next cold start", AutoCheckRunner.Outcome.DONE, runner.runIfDue { false })
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
        assertFalse(CheckMarker.file(app).exists())
    }

    @Test
    fun caseC_aLowRamPhoneStaysClassicWithoutProbing() {
        for (preset in listOf(MemoryPreset.LOW_RAM, MemoryPreset.GB3)) {
            CheckStore.file(app).delete()
            val probe = FakeMemoryProbe(preset)
            val steps = A10Kit.Steps(app)
            withProbe(probe) {
                assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, A10Kit.Env(), steps).runIfDue { false })
            }
            val r = CheckStore.read(app)!!
            assertEquals("$preset", EngineLevel.CLASSIC, r.level)
            assertEquals("$preset", CheckReasons.LOW_MEMORY, r.reason)
            assertFalse("$preset", r.provisional)
            assertEquals("$preset: M2 never runs", 0, probe.probeCalls)
            assertEquals("$preset: nor M3-M5", 0, steps.calls("m3") + steps.calls("m4") + steps.calls("m5"))
        }
    }

    @Test
    fun caseD_anUpdateReRunWithASlowCompositeKeepsAnExistingFull() {
        val env = A10Kit.Env()
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, version = A10Kit.VERSION - 1, ranAt = env.clock.nowMillis))
        env.clock.advance(1000)
        val steps = A10Kit.Steps(app, m5 = M5Result(compositeMs = 85f, belowCacheMinLayers = null))
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, env, steps).runIfDue { false })
        val r = CheckStore.read(app)!!
        assertEquals(EngineLevel.FULL, r.level)
        assertEquals(A10Kit.VERSION, r.appVersionCode)
        assertEquals(85f, r.composite64Ms)
        assertFalse("the slow composite turns the above projection off", r.aboveProjection)
        // Without a previous FULL or TILES the same composite is CLASSIC.
        CheckStore.file(app).delete()
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, env, steps).runIfDue { false })
        assertEquals(CheckReasons.SLOW_COMPOSITING, CheckStore.read(app)!!.reason)
        assertEquals(EngineLevel.CLASSIC, CheckStore.read(app)!!.level)
    }

    @Test
    fun theRulesAndTheirBoundaries() {
        val gib = 1L shl 30
        val cases = listOf(
            Case("T606, everything fast", base, null, EngineLevel.FULL, "", above = true),
            Case("composite 45 ms: no above projection", base.copy(composite64Ms = 45f), null, EngineLevel.FULL, ""),
            Case("composite 30 ms: above projection", base.copy(composite64Ms = 30f), null, EngineLevel.FULL, "", above = true),
            Case("composite 60 ms is not slow", base.copy(composite64Ms = 60f), null, EngineLevel.FULL, ""),
            Case("composite 61 ms, no previous", base.copy(composite64Ms = 61f), null, EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING),
            Case("composite 80 ms keeps TILES", base.copy(composite64Ms = 80f), prev(EngineLevel.TILES, CheckReasons.SLOW_STORAGE), EngineLevel.TILES, CheckReasons.SLOW_STORAGE),
            Case("composite 80 ms after CLASSIC", base.copy(composite64Ms = 80f), prev(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY), EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING),
            Case("composite 80 ms, FULL kept but storage now full", base.copy(composite64Ms = 80f, freeBytes = gib), prev(EngineLevel.FULL), EngineLevel.TILES, CheckReasons.SLOW_STORAGE),
            Case("deflate 19 MB/s", base.copy(deflateMBs = 19f), null, EngineLevel.TILES, CheckReasons.SLOW_STORAGE, above = true),
            Case("write 19 MB/s", base.copy(writeMBs = 19f), null, EngineLevel.TILES, CheckReasons.SLOW_STORAGE, above = true),
            Case("free just under 2 GiB", base.copy(freeBytes = 2 * gib - 1), null, EngineLevel.TILES, CheckReasons.SLOW_STORAGE, above = true),
            Case("free 2 GiB", base.copy(freeBytes = 2 * gib), null, EngineLevel.FULL, "", above = true),
            Case("the read speed never decides", base.copy(readMBs = 1f), null, EngineLevel.FULL, "", above = true),
            Case("3 GB phone (2816 MiB)", base.copy(totalMem = 2816L shl 20), null, EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY),
            Case("low-RAM phone", base.copy(lowRam = true), null, EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY),
            Case("32-bit on the T606's memory", base.copy(is64Bit = false), null, EngineLevel.FULL, "", above = true),
            Case("step-down 2 on 8 GB", base.copy(totalMem = 7552L shl 20, stepDown = 2), prev(EngineLevel.FULL), EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY),
            Case("step-down 1", base.copy(stepDown = 1), null, EngineLevel.FULL, "", above = true),
            Case("M2 failed at 64 MiB", base.copy(nativeProbeBytes = 64L shl 20, m2Outcome = M2Outcome.ALLOC_FAILED), prev(EngineLevel.FULL), EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY),
            Case("M2 failed at 192 MiB", base.copy(nativeProbeBytes = 192L shl 20, m2Outcome = M2Outcome.ALLOC_FAILED), null, EngineLevel.FULL, "", above = true),
            Case("M2 margin-limited at 200 MiB is conclusive", base.copy(nativeProbeBytes = 200L shl 20, m2Outcome = M2Outcome.MARGIN_LIMITED), null, EngineLevel.FULL, "", above = true),
            Case(
                "inconclusive, no previous: provisional CLASSIC", inconclusive, null,
                EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = true, runs = 1,
            ),
            Case(
                "inconclusive after a final FULL: provisional FULL", inconclusive, prev(EngineLevel.FULL),
                EngineLevel.FULL, "", above = true, provisional = true, runs = 1,
            ),
            Case(
                "inconclusive after a provisional TILES: capped at TILES", inconclusive, prev(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, provisional = true, runs = 1),
                EngineLevel.TILES, CheckReasons.SLOW_STORAGE, above = true, provisional = true, runs = 2,
            ),
            Case(
                "the third inconclusive run in a row is final CLASSIC", inconclusive, prev(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = true, runs = 2),
                EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, runs = 3,
            ),
            Case(
                "inconclusive after a final result starts counting again", inconclusive, prev(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, runs = 2),
                EngineLevel.TILES, CheckReasons.SLOW_STORAGE, above = true, provisional = true, runs = 1,
            ),
            Case("a conclusive run after a provisional CLASSIC", base, prev(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = true, runs = 2), EngineLevel.FULL, "", above = true),
        )
        val failures = cases.mapNotNull { c ->
            val d = AutoCheck.decide(c.inputs, c.previous)
            val want = Decision(c.level, c.reason, c.provisional, c.runs, c.above)
            if (d == want) null else "${c.name}: got $d, want $want"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun rStableIsSectionTwoFourWithoutTheStepDown() {
        val mib = 1L shl 20
        val j = 512L * mib
        assertEquals((3788 * mib * 0.30).toLong(), AutoCheck.rStable(3788 * mib, lowRam = false, is64Bit = true, javaMax = j))
        assertEquals("32-bit: x 0.6", (3072 * mib * 0.30 * 0.6).toLong(), AutoCheck.rStable(3072 * mib, lowRam = false, is64Bit = false, javaMax = j))
        assertEquals("64-bit ceiling", 2048 * mib, AutoCheck.rStable(8192 * mib, lowRam = false, is64Bit = true, javaMax = j))
        assertEquals("32-bit ceiling", 700 * mib, AutoCheck.rStable(8192 * mib, lowRam = false, is64Bit = false, javaMax = j))
        assertEquals("floor 0.55 J", (j * 0.55).toLong(), AutoCheck.rStable(1024 * mib, lowRam = true, is64Bit = false, javaMax = j))
        assertTrue(AutoCheck.rStable(1024 * mib, lowRam = true, is64Bit = false, javaMax = j) < AutoCheck.MIN_R_STABLE)
    }

    private class Case(
        val name: String,
        val inputs: CheckInputs,
        val previous: EngineCheckResult?,
        val level: EngineLevel,
        val reason: String,
        val above: Boolean = false,
        val provisional: Boolean = false,
        val runs: Int = 0,
    )

    private val base = CheckInputs(
        totalMem = 3788L shl 20, availMem = 1843L shl 20, threshold = 216L shl 20, lowRam = false, is64Bit = true, javaMax = 512L shl 20,
        nativeProbeBytes = 256L shl 20, m2Outcome = M2Outcome.REACHED, deflateMBs = 80f, inflateMBs = 250f, ratio = 3f,
        writeMBs = 120f, readMBs = 250f, freeBytes = 8L shl 30, composite64Ms = 20f, belowCacheMinLayers = 32,
    )

    private val inconclusive = base.copy(nativeProbeBytes = 100L shl 20, m2Outcome = M2Outcome.MARGIN_LIMITED)

    private fun prev(level: EngineLevel, reason: String = "", provisional: Boolean = false, runs: Int = 0) =
        A10Kit.result(level, reason, provisional = provisional, inconclusiveRuns = runs)

    /** Runs [block] with [probe] as the memory probe (the rule's own is put back). */
    private fun withProbe(probe: FakeMemoryProbe, block: () -> Unit) {
        val old = EngineConfig.memoryProbe
        EngineConfig.memoryProbe = probe
        try {
            block()
        } finally {
            EngineConfig.memoryProbe = old
        }
    }
}
