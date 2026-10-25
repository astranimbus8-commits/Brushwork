package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.ExitRecord
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeStorageProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A10 (design 2.10 "Marker", review row 20): the user swipes the app away while M2 runs.
 * The swipe is an ordinary exit (neither `REASON_LOW_MEMORY` nor signal 9), so the next launch
 * only re-runs the check, at attempt 2, and the phone gets its real level, not CLASSIC. A second
 * swipe in the same M2 is recorded as CLASSIC (two interruptions in a row).
 */
@RunWith(RobolectricTestRunner::class)
class CheckInterruptedBySwipeReRunsTest {
    private val watch = A10Kit.MarkerWatch()
    private val exits = FakeExitInfo()

    @get:Rule
    val engine = EngineRule(memory = watch, storage = FakeStorageProbe(), exits = exits)

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aSwipeDuringM2ReRunsTheCheckAndTheSecondInARowIsClassic() {
        val env = A10Kit.Env()
        // The first run: the app is swiped away inside M2 (the runner never gets to finish).
        val swiped = A10Kit.Steps(app)
        swiped.before = { if (it == "m2") throw SwipedAway() }
        runCatching { A10Kit.runner(app, env, swiped).runIfDue { false } }
        assertEquals("nothing stored", null, CheckStore.read(app))
        // A killed process runs no finally: put the marker back as the kill leaves it.
        CheckMarker.write(app, CheckMarker.Marker(env.clock.nowMillis, 1))
        exits.records += ExitRecord(env.clock.nowMillis + 800, lowMemory = false, signaled9 = false, foreground = true)

        // The next launch: a re-run, M2 inside attempt 2's marker, and the phone's real level.
        env.clock.advance(120_000)
        val next = A10Kit.runner(app, env)
        assertFalse(next.atLaunch())
        assertEquals(AutoCheckRunner.Outcome.DONE, next.runIfDue { false })
        assertEquals(listOf(CheckMarker.Marker(env.clock.nowMillis, 2)), watch.markers)
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
        assertFalse(CheckMarker.file(app).exists())

        // A later update whose run is swiped twice in a row: CLASSIC at the third launch.
        env.versionCode = A10Kit.VERSION + 1
        env.clock.advance(60_000)
        CheckMarker.write(app, CheckMarker.Marker(env.clock.nowMillis, 1))
        env.clock.advance(60_000)
        val inM2 = mutableListOf<CheckMarker.Marker?>()
        val steps = A10Kit.Steps(app)
        steps.before = {
            if (it == "m2") {
                inM2 += CheckMarker.read(app)
                throw SwipedAway()
            }
        }
        runCatching { A10Kit.runner(app, env, steps).runIfDue { false } }
        assertEquals("the re-run's M2 is attempt 2", listOf(CheckMarker.Marker(env.clock.nowMillis, 2)), inM2)
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
        CheckMarker.write(app, inM2.single()!!)
        env.clock.advance(60_000)
        assertEquals("attempt 2 interrupted: recorded", true, A10Kit.runner(app, env).atLaunch())
        val r = CheckStore.read(app)!!
        assertEquals(EngineLevel.CLASSIC, r.level)
        assertEquals(CheckReasons.LOW_MEMORY, r.reason)
        assertEquals(A10Kit.VERSION + 1, r.appVersionCode)
    }

    private class SwipedAway : RuntimeException("swiped away")
}
