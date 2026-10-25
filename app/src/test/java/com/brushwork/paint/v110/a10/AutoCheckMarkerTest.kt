package com.brushwork.paint.v110.a10

import android.content.ComponentCallbacks2
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeStorageProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A10 (design 2.10 "Marker", step 1): `engine-check.running` exists only while M2
 * allocates and goes in every way out of it (the step's end, a throw, a stop, `onTrimMemory`).
 * Found at a launch, it is recorded as final CLASSIC, `LOW_MEMORY` only with a low-memory exit
 * inside its 5 s window or at its second attempt; anything else re-runs the check.
 */
@RunWith(RobolectricTestRunner::class)
class AutoCheckMarkerTest {
    private val watch = A10Kit.MarkerWatch()
    private val exits = FakeExitInfo()

    @get:Rule
    val engine = EngineRule(memory = watch, storage = FakeStorageProbe(), exits = exits)

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theMarkerExistsOnlyWhileM2Allocates() {
        val env = A10Kit.Env()
        val steps = A10Kit.Steps(app)
        val seen = mutableListOf<String>()
        steps.before = { seen += "$it:${CheckMarker.file(app).exists()}" }
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, env, steps).runIfDue { false })
        assertEquals(listOf("m1:false", "m2:true", "m3:false", "m4:false", "m5:false"), seen)
        assertEquals("M2 saw it, first attempt, started now", listOf(CheckMarker.Marker(env.clock.nowMillis, 1)), watch.markers)
        assertFalse(CheckMarker.file(app).exists())
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
    }

    @Test
    fun aThrowInM2DeletesTheMarker() {
        val steps = A10Kit.Steps(app)
        steps.before = { if (it == "m2") throw IllegalStateException("killed in M2") }
        try {
            A10Kit.runner(app, A10Kit.Env(), steps).runIfDue { false }
            fail("the throw goes through")
        } catch (e: IllegalStateException) {
            assertEquals("killed in M2", e.message)
        }
        assertFalse(CheckMarker.file(app).exists())
        assertNull(CheckStore.read(app))
        // A probe that throws is an allocation failure, not an interruption: no marker either.
        watch.fake.probeFailure = OutOfMemoryError("probe")
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, A10Kit.Env()).runIfDue { false })
        assertFalse(CheckMarker.file(app).exists())
        assertEquals(CheckReasons.LOW_MEMORY, CheckStore.read(app)!!.reason)
    }

    @Test
    fun aLowMemoryExitInsideTheWindowIsRecordedAsClassicWithoutARerun() {
        val env = A10Kit.Env()
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, version = A10Kit.VERSION - 1, stepDown = 1, ranAt = env.clock.nowMillis - 60_000))
        val start = env.clock.nowMillis
        CheckMarker.write(app, CheckMarker.Marker(start, 1))
        exits.lowMemoryKill(start + 3_000)
        env.clock.advance(30_000)
        val runner = A10Kit.runner(app, env)
        assertTrue(runner.atLaunch())
        assertFalse(CheckMarker.file(app).exists())
        val r = CheckStore.read(app)!!
        assertEquals(EngineLevel.CLASSIC, r.level)
        assertEquals(CheckReasons.LOW_MEMORY, r.reason)
        assertFalse(r.provisional)
        assertEquals(0L, r.durationMs)
        assertEquals("the step-down is carried", 1, r.stepDown)
        assertEquals(A10Kit.VERSION, r.appVersionCode)
        assertEquals("recorded, so nothing is due", AutoCheckRunner.Outcome.NOT_DUE, runner.runIfDue { false })
        assertEquals(0, watch.fake.probeCalls)
        // Once per process: a second look finds nothing.
        assertFalse(runner.atLaunch())
    }

    @Test
    fun aSignal9ExitInsideTheWindowCountsToo() {
        val env = A10Kit.Env()
        val start = env.clock.nowMillis
        CheckMarker.write(app, CheckMarker.Marker(start, 1))
        exits.records += com.brushwork.paint.engine.budget.ExitRecord(start + 5_000, lowMemory = false, signaled9 = true, foreground = false)
        assertTrue(A10Kit.runner(app, env).atLaunch())
        assertEquals(EngineLevel.CLASSIC, CheckStore.read(app)!!.level)
    }

    @Test
    fun theSecondInterruptedAttemptInARowIsRecordedAsClassic() {
        val env = A10Kit.Env()
        CheckMarker.write(app, CheckMarker.Marker(env.clock.nowMillis, 2))
        env.clock.advance(20_000)
        val runner = A10Kit.runner(app, env)
        assertTrue("no exit at all, but attempt 2", runner.atLaunch())
        assertEquals(EngineLevel.CLASSIC, CheckStore.read(app)!!.level)
        assertEquals(CheckReasons.LOW_MEMORY, CheckStore.read(app)!!.reason)
        assertEquals(AutoCheckRunner.Outcome.NOT_DUE, runner.runIfDue { false })
    }

    @Test
    fun anExitOutsideTheWindowOnlyReRunsTheCheckWithTheNextAttempt() {
        val env = A10Kit.Env()
        CheckStore.write(app, A10Kit.result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, ranAt = env.clock.nowMillis))
        val start = env.clock.nowMillis + 1_000
        CheckMarker.write(app, CheckMarker.Marker(start, 1))
        exits.lowMemoryKill(start + AutoCheckRunner.MARKER_WINDOW_MS + 1)
        exits.lowMemoryKill(start - 1)
        env.clock.advance(60_000)
        val runner = A10Kit.runner(app, env)
        assertFalse(runner.atLaunch())
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.runIfDue { false })
        assertEquals("M2 ran inside attempt 2's marker", listOf(CheckMarker.Marker(env.clock.nowMillis, 2)), watch.markers)
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
        assertFalse(CheckMarker.file(app).exists())
        assertEquals("once per process", AutoCheckRunner.Outcome.NOT_DUE, runner.runIfDue { false })
    }

    @Test
    fun anUnreadableMarkerIsAFirstInterruptedAttempt() {
        CheckMarker.file(app).apply { parentFile?.mkdirs() }.writeText("torn")
        assertEquals(CheckMarker.Marker(0L, 1), CheckMarker.read(app))
        val runner = A10Kit.runner(app, A10Kit.Env())
        assertFalse(runner.atLaunch())
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.runIfDue { false })
        assertEquals(2, watch.markers.single()!!.attempts)
    }

    @Test
    fun aStopAndTrimMemoryDeleteTheMarker() {
        val runner = A10Kit.runner(app, A10Kit.Env())
        CheckMarker.write(app, CheckMarker.Marker(1L, 1))
        runner.requestStop()
        assertFalse(CheckMarker.file(app).exists())
        CheckMarker.write(app, CheckMarker.Marker(1L, 1))
        runner.onTrimMemory()
        assertFalse(CheckMarker.file(app).exists())
        // Through the app: EngineStartup registered the trim callback at onCreate.
        val brush = app as BrushworkApp
        CheckMarker.write(app, CheckMarker.Marker(1L, 1))
        brush.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertFalse(CheckMarker.file(app).exists())
        CheckMarker.write(app, CheckMarker.Marker(1L, 1))
        @Suppress("DEPRECATION")
        brush.onLowMemory()
        assertFalse(CheckMarker.file(app).exists())
    }
}
