package com.brushwork.paint.v110.a10

import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.engine.EngineStartup
import com.brushwork.paint.engine.ExitWatch
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.ExitRecord
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckNotices
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineStateStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 A10 (design 2.12 and 2.10, step 5): the step-down after low-memory closings.
 *
 * - The first counted closing sets `stepDown = 1`, keeps a chosen Full and the stored level, and
 *   makes the check due again at the gallery (a closing newer than the result), whose run carries
 *   the step-down and the closings.
 * - The second within 30 days sets `stepDown = 2`: the stored result is CLASSIC for low memory
 *   (final), a chosen Full becomes Automatic, and the low-memory closings' notice is raised; the
 *   gallery's re-run then says Classic for memory itself and adds no second notice. A chosen
 *   Classic stays and gets no notice. Later closings keep 2 and change nothing else.
 * - A closing older than 30 days does not make the next one the second.
 * - The notice comes back at the next start while it was never shown, and never after.
 * - The gallery's check waits until the start counted the closings, so it carries their
 *   step-down.
 */
@RunWith(RobolectricTestRunner::class)
class LmkStepDownTest {
    private val exits = FakeExitInfo(lmkSupported = true)

    @get:Rule
    val engine = EngineRule(memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = exits)

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = UiPrefs(app)
    private var mode = LayerStorageMode.AUTOMATIC

    /** The stored result's time; the runner's clock (`A10Kit.Env`) is about 11 days later. */
    private val ranAt = 1_699_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() = clean()

    @After
    fun tearDown() = clean()

    private fun clean() {
        EngineNotices.current = null
        prefs.engineNoticeShownAt = 0L
        prefs.engineLmkNoticeShownAt = 0L
        CheckStore.file(app).delete()
        EngineStateStore.file(app).delete()
        ExitWatch.markerFile(app).delete()
        File(app.noBackupFilesDir, ExitWatch.CLOSINGS_FILE).delete()
    }

    private fun env() = ExitWatch.Env(api = 34, bootCount = 5, exits = exits, mode = { mode }, setMode = { mode = it }, onMain = { it.run() })

    /** A low-memory exit at [at] of a process whose editor was in the foreground; counted at start. */
    private fun closedAt(at: Long): ExitWatch.Closing {
        exits.records += ExitRecord(at, lowMemory = true, signaled9 = true, foreground = true)
        val marker = ExitWatch.Marker(EngineLevel.FULL, 40, 1080, 2400, foreground = true, bootCount = 5, sinceMillis = at - 60_000, updatedAt = at - 1_000)
        return ExitWatch.atStart(app, marker, env())
    }

    private fun stored(): EngineCheckResult = CheckStore.readStored(app)!!

    /** The gallery's check, as `EngineCheckHost` runs it. */
    private fun galleryCheck(): AutoCheckRunner.Outcome {
        val env = A10Kit.Env().also { it.mode = mode }
        return A10Kit.runner(app, env).runIfDue { false }
    }

    @Test
    fun theFirstClosingStepsDownOnceAndTheCheckReRuns() {
        mode = LayerStorageMode.FULL
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = ranAt))
        assertEquals("nothing due before", AutoCheckRunner.Outcome.NOT_DUE, galleryCheck())

        assertEquals(ExitWatch.Closing.SURE, closedAt(ranAt + day))
        val s = stored()
        assertEquals(1, s.stepDown)
        assertEquals(listOf(ranAt + day), s.lmkDeathsAt)
        assertEquals("the level stays", EngineLevel.FULL, s.level)
        assertEquals("a chosen Full is kept after the first", LayerStorageMode.FULL, mode)
        assertNull("no notice after the first", EngineNotices.current)

        assertEquals("the gallery's check is due (a closing newer than the result)", AutoCheckRunner.Outcome.DONE, galleryCheck())
        val rerun = stored()
        assertTrue("a new result", rerun.ranAtMillis > ranAt)
        assertEquals("it carries the step-down", 1, rerun.stepDown)
        assertEquals("and the closings", listOf(ranAt + day), rerun.lmkDeathsAt)
        assertEquals("then nothing is due", AutoCheckRunner.Outcome.NOT_DUE, galleryCheck())
    }

    @Test
    fun theSecondClosingWithin30DaysMakesItClassicAndRaisesTheNotice() {
        mode = LayerStorageMode.FULL
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = ranAt, stepDown = 1, lmkDeathsAt = listOf(ranAt + day)))
        assertEquals(ExitWatch.Closing.SURE, closedAt(ranAt + 3 * day))
        val s = stored()
        assertEquals(2, s.stepDown)
        assertEquals(listOf(ranAt + day, ranAt + 3 * day), s.lmkDeathsAt)
        assertEquals("Classic", EngineLevel.CLASSIC, s.level)
        assertEquals("for low memory", CheckReasons.LOW_MEMORY, s.reason)
        assertFalse("final", s.provisional)
        assertEquals("the result's time stays", ranAt, s.ranAtMillis)
        assertEquals("a chosen Full becomes Automatic (the notice says Classic)", LayerStorageMode.AUTOMATIC, mode)
        assertEquals(EngineNotice.LowMemoryClosings, EngineNotices.current)

        assertEquals("the gallery's check re-runs", AutoCheckRunner.Outcome.DONE, galleryCheck())
        val rerun = stored()
        assertEquals(EngineLevel.CLASSIC, rerun.level)
        assertEquals(CheckReasons.LOW_MEMORY, rerun.reason)
        assertEquals(2, rerun.stepDown)
        assertEquals("no second notice after the closings' one", EngineNotice.LowMemoryClosings, EngineNotices.current)

        // Shown, then a later start: never again.
        CheckNotices.markShown(app, EngineNotice.LowMemoryClosings)
        EngineNotices.current = null
        ExitWatch.noticeAtStart(app, rerun, mode)
        CheckNotices.atStart(app, rerun, mode)
        assertNull("shown once", EngineNotices.current)
    }

    @Test
    fun theNoticeComesBackAtTheNextStartUntilShown() {
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = ranAt, stepDown = 1, lmkDeathsAt = listOf(ranAt + day)))
        closedAt(ranAt + 2 * day)
        assertEquals(EngineNotice.LowMemoryClosings, EngineNotices.current)

        // The process ended before the gallery drew it.
        EngineNotices.current = null
        ExitWatch.noticeAtStart(app, stored(), LayerStorageMode.CLASSIC)
        assertNull("a chosen Classic: no notice", EngineNotices.current)
        ExitWatch.noticeAtStart(app, stored(), LayerStorageMode.AUTOMATIC)
        assertEquals("raised again", EngineNotice.LowMemoryClosings, EngineNotices.current)
        CheckNotices.atStart(app, stored(), LayerStorageMode.AUTOMATIC)
        assertEquals("the check's notice does not replace it", EngineNotice.LowMemoryClosings, EngineNotices.current)

        CheckNotices.markShown(app, EngineNotice.LowMemoryClosings)
        EngineNotices.current = null
        ExitWatch.noticeAtStart(app, stored(), LayerStorageMode.AUTOMATIC)
        assertNull("shown: not again", EngineNotices.current)
    }

    @Test
    fun aChosenClassicStaysWithoutANotice() {
        mode = LayerStorageMode.CLASSIC
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = ranAt, stepDown = 1, lmkDeathsAt = listOf(ranAt + day)))
        closedAt(ranAt + 2 * day)
        assertEquals(2, stored().stepDown)
        assertEquals(EngineLevel.CLASSIC, stored().level)
        assertEquals(LayerStorageMode.CLASSIC, mode)
        assertNull(EngineNotices.current)
    }

    @Test
    fun laterClosingsKeepTwoAndChangeNothingElse() {
        // After the notice the user chose Full again.
        mode = LayerStorageMode.FULL
        val deaths = listOf(ranAt + day, ranAt + 2 * day)
        CheckStore.write(app, A10Kit.result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, ranAt = ranAt, stepDown = 2, lmkDeathsAt = deaths))
        closedAt(ranAt + 4 * day)
        assertEquals(2, stored().stepDown)
        assertEquals(deaths + (ranAt + 4 * day), stored().lmkDeathsAt)
        assertEquals("the user's new choice stays", LayerStorageMode.FULL, mode)
        assertNull("no second notice", EngineNotices.current)
    }

    /**
     * Review (A10 8/8): the gallery's check (on its own thread, 1 s after the first frame) may be
     * due for another reason before the start's thread has counted the last closing. It waits for
     * the count, so its run reads the result with the closing and carries the step-down instead of
     * writing back the result it read before.
     */
    @Test
    fun theGallerysCheckWaitsUntilTheStartCountedTheClosings() {
        // A new version: due at the gallery whatever the closings say.
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, version = A10Kit.VERSION - 1, ranAt = ranAt))
        val gate = CountDownLatch(1)
        EngineStartup.closingsCounted = gate
        try {
            val steps = A10Kit.Steps(app)
            val measuring = CountDownLatch(1)
            steps.before = { measuring.countDown() }
            val run = A10Kit.runner(app, A10Kit.Env().also { it.mode = mode }, steps).launchIfDue()
            assertFalse("nothing measured before the closings are counted", measuring.await(300, TimeUnit.MILLISECONDS))
            assertEquals("the start's thread counts one", ExitWatch.Closing.SURE, closedAt(ranAt + day))
            gate.countDown()
            assertEquals(AutoCheckRunner.Outcome.DONE, run.get(10, TimeUnit.SECONDS))
            val rerun = stored()
            assertEquals("the new version's result", A10Kit.VERSION, rerun.appVersionCode)
            assertEquals("it carries the closing's step-down", 1, rerun.stepDown)
            assertEquals("and the closing", listOf(ranAt + day), rerun.lmkDeathsAt)
        } finally {
            EngineStartup.closingsCounted = null
        }
    }

    /** The start's background part counts the closings, then lets the gallery's check go. */
    @Test
    fun theStartCountsTheClosingsThenOpensTheGate() {
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = ranAt))
        exits.records += ExitRecord(ranAt + day, lowMemory = true, signaled9 = true, foreground = true)
        val marker = ExitWatch.Marker(EngineLevel.FULL, 40, 1080, 2400, foreground = true, bootCount = 5, sinceMillis = ranAt + day - 60_000, updatedAt = ranAt + day - 1_000)
        val gate = CountDownLatch(1)
        EngineStartup.closingsCounted = gate
        try {
            EngineStartup.atStart(app as BrushworkApp, marker)
            assertEquals("open", 0L, gate.count)
            assertEquals("after the closing was counted", 1, stored().stepDown)
            EngineStartup.awaitClosingsCounted() // returns at once
        } finally {
            EngineStartup.closingsCounted = null
        }
    }

    @Test
    fun aClosingOlderThan30DaysDoesNotMakeTheNextOneTheSecond() {
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = ranAt, stepDown = 1, lmkDeathsAt = listOf(ranAt + day)))
        closedAt(ranAt + 32 * day)
        assertEquals("one closing in the window; the step-down never goes back by itself", 1, stored().stepDown)
        assertEquals("the old closing is dropped", listOf(ranAt + 32 * day), stored().lmkDeathsAt)
        assertEquals(EngineLevel.FULL, stored().level)
        assertNull(EngineNotices.current)
    }
}
