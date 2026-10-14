package com.brushwork.paint.v19.a7a

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.diagnostics.FreezeWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A7a step 3: the freeze watchdog, tick by tick on a fake clock (no Android, no real time):
 * one FREEZE after the main thread has not answered for 4 s, once per freeze, armed again once it
 * answers; a process held as a whole (a gap between ticks) is no freeze; one post per tick at most.
 * A freeze becomes a quiet FREEZE report (Diagnostics only) with the main thread's stack.
 */
@RunWith(RobolectricTestRunner::class)
class FreezeWatchdogTest {
    private var now = 0L
    private val posted = ArrayList<Runnable>()
    private val freezes = ArrayList<Long>()
    private val stack = arrayOf(StackTraceElement("com.brushwork.paint.tools.select.magnetic.MagneticLassoTool", "trace", "MagneticLassoTool.kt", 311))

    private val dog = FreezeWatchdog(
        clock = { now },
        postToMain = { posted += it },
        mainStack = { stack },
        onFreeze = { blocked, s ->
            assertTrue(s === stack)
            freezes += blocked
        },
    )

    /** The main thread answers what was posted. */
    private fun mainAnswers() {
        val r = posted.toList()
        posted.clear()
        r.forEach { it.run() }
    }

    private fun tickAfter(ms: Long) {
        now += ms
        dog.tick()
    }

    @Test
    fun aMainThreadThatAnswersIsNeverAFreeze() {
        dog.tick()
        repeat(30) {
            assertEquals("one ping per tick", 1, posted.size)
            mainAnswers()
            tickAfter(FreezeWatchdog.INTERVAL_MS)
        }
        assertEquals(0, dog.freezes)
        assertTrue(freezes.isEmpty())
    }

    @Test
    fun fourSecondsBlockedIsOneFreezeReportedOnce() {
        dog.tick() // ping at 0
        assertEquals(1, posted.size)
        tickAfter(1_000)
        tickAfter(1_000)
        tickAfter(1_000)
        assertEquals("3 s is not yet a freeze", 0, dog.freezes)
        assertEquals("no new ping while one is unanswered", 1, posted.size)
        tickAfter(1_000)
        assertEquals(1, dog.freezes)
        assertEquals(listOf(4_000L), freezes)
        repeat(10) { tickAfter(1_000) }
        assertEquals("one report per freeze", 1, dog.freezes)
        assertEquals(1, posted.size)

        // The main thread answers: the next tick pings again, and a second freeze is a second report.
        mainAnswers()
        tickAfter(1_000)
        assertEquals(1, posted.size)
        repeat(4) { tickAfter(1_000) }
        assertEquals(2, dog.freezes)
        assertEquals(4_000L, freezes.last())
    }

    @Test
    fun aGapBetweenTicksIsTheWholeProcessHeldNotAFreeze() {
        dog.tick() // ping at 0, never answered (the phone sleeps)
        tickAfter(30_000)
        assertEquals("a gap of 30 s starts over", 0, dog.freezes)
        assertEquals("and pings again", 2, posted.size)
        posted.clear()
        repeat(3) { tickAfter(1_000) }
        assertEquals(0, dog.freezes)
        tickAfter(1_000)
        assertEquals("4 s after the new ping", 1, dog.freezes)
    }

    @Test
    fun resetForgetsAnUnansweredPing() {
        dog.tick()
        tickAfter(1_000)
        tickAfter(1_000)
        dog.reset() // the activity stopped
        posted.clear()
        tickAfter(1_000)
        assertEquals(1, posted.size)
        repeat(3) { tickAfter(1_000) }
        assertEquals(0, dog.freezes)
        tickAfter(1_000)
        assertEquals(1, dog.freezes)
    }

    @Test
    fun theDefaultsAreAOneSecondPingAndAFourSecondFreeze() {
        assertEquals(1_000L, dog.intervalMs)
        assertEquals(4_000L, dog.freezeMs)
    }

    @Test
    fun aFreezeIsAQuietReportWithTheMainThreadsStack() {
        val app: Context = ApplicationProvider.getApplicationContext()
        CrashLog.deleteAll(app)
        CrashLog.recordFreeze(4_200L, stack)
        CrashLog.drain()
        val r = CrashLog.reports(app).single()
        assertEquals(CrashReport.Kind.FREEZE, r.kind)
        assertTrue(r.id, r.id.endsWith("-freeze-quiet"))
        assertEquals("Freeze: 4.2 s", r.summary)
        val lines = r.text.lines()
        assertTrue(r.text, lines.contains("Where: main thread blocked 4.2 s"))
        assertTrue(r.text, lines.contains("Fatal: no"))
        val main = lines.indexOf("Main thread:")
        assertTrue(r.text, main > 0 && lines[main + 1].contains("MagneticLassoTool.trace(MagneticLassoTool.kt:311)"))
        assertFalse("never on the gallery's card", CrashLog.unseen(app)?.id == r.id)
        CrashLog.deleteAll(app)
    }
}
