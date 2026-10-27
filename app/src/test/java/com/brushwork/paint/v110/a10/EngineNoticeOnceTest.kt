package com.brushwork.paint.v110.a10

import com.brushwork.paint.UiPrefs
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckNotices
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.gallery.GalleryNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 A10 (design 2.11, step 2): the gallery's engine notice, shown once with an OK button only.
 *
 * - At 392 and 360 dp and each font scale, the gallery's notice slot (`GalleryNotices`) shows each
 *   reason's text whole ([EngineLabels110.CHECK_CLASSIC_MEMORY], [EngineLabels110.CHECK_CLASSIC_SLOW],
 *   [EngineLabels110.CHECK_TILES], [EngineLabels110.LMK_NOTICE]) with OK; drawn, it counts as shown
 *   (`UiPrefs`), OK hides it, and the next start does not raise it again.
 * - Nothing is announced for FULL, a provisional result, an interrupted run, a chosen mode or the
 *   same level and reason again; a notice raised but never drawn comes back at the next start; the
 *   low-memory closings' notice is not followed by a check notice; a chosen mode settles a waiting
 *   one.
 * - Through the runner: a low-RAM phone's check announces Classic for memory; a run interrupted by
 *   a low-memory exit, recorded as Classic, announces nothing.
 *
 * One UI test (the frame clock serves the first test of a sandbox); its cases are sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a10.enginenoticeoncesandbox"])
class EngineNoticeOnceTest {
    private val exits = FakeExitInfo()

    @get:Rule
    val engine = EngineRule(memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = exits)

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = UiPrefs(app)
    private val auto = LayerStorageMode.AUTOMATIC

    /** No notice waiting, none shown yet, no stored check. */
    private fun fresh() {
        EngineNotices.current = null
        prefs.engineNoticeShownAt = 0L
        prefs.engineLmkNoticeShownAt = 0L
        CheckStore.file(app).delete()
        CheckMarker.delete(app)
    }

    private fun result(level: EngineLevel, reason: String = "", ranAt: Long, provisional: Boolean = false): EngineCheckResult =
        A10Kit.result(level, reason, ranAt = ranAt, provisional = provisional, inconclusiveRuns = if (provisional) 1 else 0)

    @Test
    fun theNoticeShowsOnceWithOkOnly() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                val where = "at ${width.substringBefore('-')} and font scale $scale"
                h.section("$where: each reason in the gallery") {
                    RuntimeEnvironment.setQualifiers(width)
                    eachReason(h, scale, where)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
        h.section("nothing for FULL, a provisional result, an interrupted run, a choice or the same result") { nothingToAnnounce() }
        h.section("a notice raised but never drawn comes back at the next start") { raisedAgainAtStart() }
        h.section("the closings' notice is not followed by a check notice; a choice settles a waiting one") { closingsAndChoices() }
        h.section("through the runner") { throughTheRunner() }
    }

    private fun eachReason(h: Qa19Harness, scale: Float, where: String) {
        val cases = listOf(
            EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY) to EngineLabels110.CHECK_CLASSIC_MEMORY,
            EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING) to EngineLabels110.CHECK_CLASSIC_SLOW,
            EngineNotice.CheckChose(EngineLevel.TILES, CheckReasons.SLOW_STORAGE) to EngineLabels110.CHECK_TILES,
            EngineNotice.LowMemoryClosings to EngineLabels110.LMK_NOTICE,
        )
        var ranAt = 1_700_000_000_000L
        for ((notice, text) in cases) {
            fresh()
            ranAt += 1000
            val stored = (notice as? EngineNotice.CheckChose)?.let { result(it.level, it.reason, ranAt) }
            if (stored != null) {
                CheckStore.write(app, stored)
                CheckNotices.afterCheck(app, stored, previous = null, mode = auto, announce = true)
            } else {
                EngineNotices.current = notice // raised by the closings' count (step 5)
            }
            assertEquals("$where: $text raised", notice, EngineNotices.current)
            val a = h.show(scale) { GalleryNotices() }
            assertEquals(where, if (RuntimeEnvironment.getQualifiers().contains("w360dp")) 360 else 392, a.resources.configuration.screenWidthDp)
            assertTrue("$where: the text shows; shown ${Qa19Ui.shown()}", A10Kit.pumpUntil(5_000) { Qa19Ui.settle(1); Qa19Ui.has(text, exact = true) })
            assertTrue("$where: OK", Qa19Ui.has("OK", exact = true))
            Qa19Ui.assertNoTextOverflow("$where, $text")
            if (stored != null) {
                assertEquals("$where: drawn counts as shown", ranAt, prefs.engineNoticeShownAt)
            } else {
                assertTrue("$where: the closings' notice noted", prefs.engineLmkNoticeShownAt > 0L)
            }
            Qa19Ui.click("OK", exact = true)
            assertFalse("$where: OK hides it", Qa19Ui.has(text, exact = true))
            assertNull("$where: OK clears it", EngineNotices.current)
            // The next start: not again.
            CheckNotices.atStart(app, stored, auto)
            assertNull("$where: not raised again at the next start", EngineNotices.current)
        }
    }

    private fun nothingToAnnounce() {
        fresh()
        var ranAt = 1_700_000_100_000L
        fun settles(what: String, r: EngineCheckResult, previous: EngineCheckResult?, mode: LayerStorageMode = auto, announce: Boolean = true) {
            ranAt += 1000
            EngineNotices.current = null
            CheckNotices.afterCheck(app, r, previous, mode, announce)
            assertNull("$what: no notice", EngineNotices.current)
            assertEquals("$what: counts as announced", r.ranAtMillis, prefs.engineNoticeShownAt)
            CheckNotices.atStart(app, r, mode)
            assertNull("$what: nothing at the next start", EngineNotices.current)
        }
        settles("FULL", result(EngineLevel.FULL, ranAt = ranAt + 1), previous = null)
        settles("a provisional Classic", result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, ranAt + 2, provisional = true), previous = null)
        settles("an interrupted run", result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, ranAt + 3), previous = null, announce = false)
        settles("Full chosen", result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, ranAt + 4), previous = null, mode = LayerStorageMode.FULL)
        settles("Classic chosen", result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, ranAt + 5), previous = null, mode = LayerStorageMode.CLASSIC)
        val tiles = result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, ranAt + 6)
        settles("the same level and reason again (an update's re-run)", tiles, previous = tiles.copy(ranAtMillis = ranAt))
        // A waiting check notice is withdrawn when a newer result has nothing to say.
        val classic = result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, ranAt + 10)
        CheckNotices.afterCheck(app, classic, previous = tiles, mode = auto, announce = true)
        assertEquals(EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY), EngineNotices.current)
        val full = result(EngineLevel.FULL, ranAt = ranAt + 20)
        CheckNotices.afterCheck(app, full, previous = classic, mode = auto, announce = true)
        assertNull("the newer FULL withdraws the waiting notice", EngineNotices.current)
        assertEquals(full.ranAtMillis, prefs.engineNoticeShownAt)
        // But a new reason after a provisional run is announced.
        val slow = result(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING, ranAt + 30)
        CheckNotices.afterCheck(app, slow, previous = result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, ranAt + 25, provisional = true), mode = auto, announce = true)
        assertEquals(EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING), EngineNotices.current)
    }

    private fun raisedAgainAtStart() {
        fresh()
        val r = result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, 1_700_000_200_000L)
        CheckStore.write(app, r)
        CheckNotices.afterCheck(app, r, previous = null, mode = auto, announce = true)
        assertEquals(EngineNotice.CheckChose(EngineLevel.TILES, CheckReasons.SLOW_STORAGE), EngineNotices.current)
        assertEquals("not drawn yet", 0L, prefs.engineNoticeShownAt)
        // The process ends before the gallery draws it.
        EngineNotices.current = null
        CheckNotices.atStart(app, CheckStore.read(app), auto)
        assertEquals("raised again", EngineNotice.CheckChose(EngineLevel.TILES, CheckReasons.SLOW_STORAGE), EngineNotices.current)
        CheckNotices.markShown(app, EngineNotices.current!!)
        assertEquals(r.ranAtMillis, prefs.engineNoticeShownAt)
        EngineNotices.current = null
        CheckNotices.atStart(app, CheckStore.read(app), auto)
        assertNull("shown: never again", EngineNotices.current)
        // Nor when the user chose a mode before the next start.
        prefs.engineNoticeShownAt = 0L
        CheckNotices.atStart(app, CheckStore.read(app), LayerStorageMode.FULL)
        assertNull("a chosen mode: nothing", EngineNotices.current)
    }

    private fun closingsAndChoices() {
        fresh()
        EngineNotices.current = EngineNotice.LowMemoryClosings
        val r = result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, 1_700_000_300_000L)
        CheckNotices.afterCheck(app, r, previous = result(EngineLevel.FULL, ranAt = 1_700_000_250_000L), mode = auto, announce = true)
        assertEquals("the closings' notice stays alone", EngineNotice.LowMemoryClosings, EngineNotices.current)
        assertEquals("the Classic result counts as announced", r.ranAtMillis, prefs.engineNoticeShownAt)

        fresh()
        val waiting = result(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING, 1_700_000_400_000L)
        CheckNotices.afterCheck(app, waiting, previous = null, mode = auto, announce = true)
        CheckNotices.onModeChosen(app, LayerStorageMode.AUTOMATIC)
        assertEquals("Automatic again keeps it", EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING), EngineNotices.current)
        CheckNotices.onModeChosen(app, LayerStorageMode.FULL)
        assertNull("a chosen Full settles it", EngineNotices.current)
        assertEquals(waiting.ranAtMillis, prefs.engineNoticeShownAt)
        EngineNotices.current = EngineNotice.LowMemoryClosings
        CheckNotices.onModeChosen(app, LayerStorageMode.CLASSIC)
        assertEquals("the closings' notice is not a check notice", EngineNotice.LowMemoryClosings, EngineNotices.current)
    }

    private fun throughTheRunner() {
        fresh()
        val env = A10Kit.Env()
        EngineConfig.memoryProbe = FakeMemoryProbe(MemoryPreset.LOW_RAM)
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, env).runIfDue { false })
        val low = CheckStore.read(app)!!
        assertEquals(EngineLevel.CLASSIC, low.level)
        assertEquals("a low-RAM phone's check announces Classic for memory", EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY), EngineNotices.current)
        assertEquals("not shown until drawn", 0L, prefs.engineNoticeShownAt)

        // An interrupted run recorded as Classic: nothing, and the waiting notice is settled.
        EngineConfig.memoryProbe = FakeMemoryProbe(MemoryPreset.T606)
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, version = A10Kit.VERSION - 1, ranAt = env.clock.nowMillis - 60_000))
        val start = env.clock.nowMillis
        CheckMarker.write(app, CheckMarker.Marker(start, 1))
        exits.lowMemoryKill(start + 3_000)
        env.clock.advance(30_000)
        assertTrue(A10Kit.runner(app, env).atLaunch())
        val recorded = CheckStore.read(app)!!
        assertEquals(EngineLevel.CLASSIC, recorded.level)
        assertNull("an interrupted run announces nothing", EngineNotices.current)
        assertEquals(recorded.ranAtMillis, prefs.engineNoticeShownAt)
    }
}
