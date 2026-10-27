package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 A10 (design 2.10 "When it runs", review row 20): opening an artwork (or `ON_STOP`)
 * cancels the check before its next step; the steps it finished are kept and the next start runs
 * only the rest. Through [AutoCheckRunner.runIfDue] with a stop flag, through an editor that
 * opens mid-run, and on the check's own thread ([AutoCheckRunner.launchIfDue] and
 * [AutoCheckRunner.requestStop], as `EngineCheckHost` calls them).
 */
@RunWith(RobolectricTestRunner::class)
class CheckCancelledByOpenResumesTest {
    private val memory = FakeMemoryProbe(MemoryPreset.T606)

    @get:Rule
    val engine = EngineRule(memory = memory, storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aStopAfterM3KeepsM1ToM3AndTheResumeRunsOnlyM4AndM5() {
        val steps = A10Kit.Steps(app)
        var stopped = false
        steps.before = { if (it == "m3") stopped = true }
        val runner = A10Kit.runner(app, A10Kit.Env(), steps)
        assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { stopped })
        assertTrue(runner.inProgress)
        assertNull("nothing stored yet", CheckStore.read(app))
        assertEquals(listOf(1, 1, 1, 0, 0), counts(steps))
        assertFalse(CheckMarker.file(app).exists())

        stopped = false
        steps.before = {}
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.runIfDue { stopped })
        assertEquals("M1-M3 kept, M4 and M5 once", listOf(1, 1, 1, 1, 1), counts(steps))
        assertFalse(runner.inProgress)
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
    }

    @Test
    fun aStopInsideM3RunsM3AgainAtTheResume() {
        val steps = A10Kit.Steps(app, m3 = null)
        var stopped = false
        steps.before = { if (it == "m3") stopped = true }
        val runner = A10Kit.runner(app, A10Kit.Env(), steps)
        assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { stopped })
        assertEquals(listOf(1, 1, 1, 0, 0), counts(steps))
        stopped = false
        steps.before = {}
        steps.m3 = A10Kit.FAST_M3
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.runIfDue { stopped })
        assertEquals("M3 unfinished, so again", listOf(1, 1, 2, 1, 1), counts(steps))
    }

    @Test
    fun anEditorOpeningMidRunStopsItAndClosingResumes() {
        val env = A10Kit.Env()
        val steps = A10Kit.Steps(app)
        steps.before = { if (it == "m2") env.editorOpen = true }
        val runner = A10Kit.runner(app, env, steps)
        assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { false })
        assertEquals(listOf(1, 1, 0, 0, 0), counts(steps))
        assertEquals("nothing while it stays open", AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { false })
        assertEquals(listOf(1, 1, 0, 0, 0), counts(steps))
        env.editorOpen = false
        steps.before = {}
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.runIfDue { false })
        assertEquals(listOf(1, 1, 1, 1, 1), counts(steps))
    }

    @Test
    fun onTheChecksThreadARequestStopEndsTheRunAndTheNextLaunchResumes() {
        val steps = A10Kit.Steps(app)
        val inM3 = CountDownLatch(1)
        val release = CountDownLatch(1)
        steps.before = {
            if (it == "m3") {
                inM3.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val runner = A10Kit.runner(app, A10Kit.Env(), steps)
        val first = runner.launchIfDue()
        assertTrue(inM3.await(10, TimeUnit.SECONDS))
        runner.requestStop() // The gallery left (an artwork opened).
        release.countDown()
        assertEquals(AutoCheckRunner.Outcome.STOPPED, first.get(10, TimeUnit.SECONDS))
        assertEquals(listOf(1, 1, 1, 0, 0), counts(steps))

        steps.before = {}
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.launchIfDue().get(10, TimeUnit.SECONDS))
        assertEquals(listOf(1, 1, 1, 1, 1), counts(steps))
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
        assertEquals("then nothing is due", AutoCheckRunner.Outcome.NOT_DUE, runner.launchIfDue().get(10, TimeUnit.SECONDS))
    }

    @Test
    fun aResumedRunThatIsNoLongerDueEndsWithoutMeasuring() {
        val env = A10Kit.Env()
        val steps = A10Kit.Steps(app)
        var stopped = false
        steps.before = { if (it == "m2") stopped = true }
        val runner = A10Kit.runner(app, env, steps)
        assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { stopped })
        // Meanwhile another process of the app finished a run of this version.
        CheckStore.write(app, A10Kit.result(EngineLevel.TILES, com.brushwork.paint.storage.engine.CheckReasons.SLOW_STORAGE, ranAt = env.clock.nowMillis))
        stopped = false
        assertEquals(AutoCheckRunner.Outcome.NOT_DUE, runner.runIfDue { stopped })
        assertFalse(runner.inProgress)
        assertEquals(listOf(1, 1, 0, 0, 0), counts(steps))
        assertEquals(EngineLevel.TILES, CheckStore.read(app)!!.level)
    }

    private fun counts(steps: A10Kit.Steps) = listOf("m1", "m2", "m3", "m4", "m5").map { steps.calls(it) }
}
