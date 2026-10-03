package com.brushwork.paint.qa16

import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.mask.MaskHandles
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, the "Fast adjustment preview" switch (§3.1a) while the live adjustment works, on
 * the user's phone (392 dp, 1080 x 2408, policy LIVE, injected clock), as the user flips it:
 * in the Masks tool's sheet while a handle's change still refines (the session ends, the very
 * next frame is the exact one), a handle drag with it off (exact frames that follow the finger,
 * one step), on again (the next touch is live); then in Settings while a change refines (it
 * counts from the next drag: the refinement finishes, the next drag is exact), and on again there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.fasttogglesandbox"])
class FastPreviewToggleLiveQaTest {
    private val w = 1080
    private val h = 2408

    @Test
    fun theSwitchInTheMasksSheetAndInSettingsTakesEffectAsItSays() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val harness = ChromeHarness()
        harness.section("Fast adjustment preview, live") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            val c = s.c
            live.draw()
            val flow = MasksLiveFlow(live, w, h, "fast")
            flow.createLinear()
            flow.addRadial()
            val adj = flow.adj

            /**
             * The radial's right handle dragged by [dx] document px, a frame per move; returns how
             * many frames a session drew. Without a session ([expectLive] false) the frames of moves
             * 3 and 7 are compared: the exact canvas follows the finger too.
             */
            fun handleDrag(dx: Float, expectLive: Boolean): Int {
                val radial = adj.maskSpec!!.components.last() as RadialMask
                val p = MaskHandles.position(radial, MaskHandles.Kind.RX_POS, c.viewTransform)!!
                val before = live.steps()
                var liveFrames = 0
                val shots = mutableListOf<IntArray>()
                live.canvasDrag(p.x to p.y, p.x + dx to p.y, moves = 8) { i ->
                    if (c.liveAdjust.isActive) { live.liveFrame("handle move $i"); liveFrames++ } else live.draw()
                    if (!expectLive && (i == 3 || i == 7)) shots += live.lastPixels()
                }
                assertEquals("handle drag: one step", before + 1, live.steps())
                assertEquals("Edit mask", c.undoManager.undoLabel)
                if (expectLive) {
                    assertTrue("a live session drew the drag ($liveFrames frames)", liveFrames >= 4)
                } else {
                    assertEquals("no session with the switch off", 0, liveFrames)
                    assertTrue("the exact frames follow the finger", meanDiff(shots[0], shots[1]) > 0.0)
                }
                return liveFrames
            }

            fun sheetSwitch(on: Boolean) {
                click("Components (")
                assertTrue("the Masks sheet's switch", has(FAST_ADJUST_PREVIEW_LABEL, exact = true))
                click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
                assertEquals(on, c.settings.fastAdjustPreview)
                assertEquals(on, c.liveAdjust.fastPreview)
                click("Close", exact = true)
                settle()
                assertFalse("the sheet closed", has(FAST_ADJUST_PREVIEW_LABEL, exact = true))
            }

            fun settingsSwitch(on: Boolean) {
                click("More options")
                click("Settings", exact = true)
                assertTrue("the Settings dialog", has("Editor settings", exact = true))
                click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
                assertEquals(on, c.settings.fastAdjustPreview)
                click("Close", exact = true)
                settle()
                assertFalse(has("Editor settings", exact = true))
            }

            // 1. Off in the Masks sheet while the handle's change refines: the session ends and
            // the next frame is the exact canvas (no refinement left to see).
            handleDrag(120f, expectLive = true)
            assertTrue("refining after the finger lifted", c.liveAdjust.isActive)
            sheetSwitch(on = false)
            assertFalse("turning it off ended the session", c.liveAdjust.isActive)
            val next = live.pixels()
            assertFalse(c.liveAdjust.isActive)
            assertSamePixels("the next frame after turning it off is exact", live.exact(), next, live.width)

            // 2. A handle drag with it off: v1.5's exact frames, one step, nothing left to refine.
            handleDrag(-80f, expectLive = false)
            assertFalse(c.liveAdjust.isActive)
            assertEquals("converged at once", 0, live.assertConverged("drag with the switch off"))

            // 3. On again in the sheet: the next touch is live.
            sheetSwitch(on = true)
            handleDrag(100f, expectLive = true)
            live.assertConverged("live again")

            // 4. Off in Settings while a change refines: it counts from the next drag (the
            // running refinement finishes as it would, the canvas converges), and the next drag
            // is exact.
            handleDrag(-60f, expectLive = true)
            assertTrue(c.liveAdjust.isActive)
            settingsSwitch(on = false)
            live.assertConverged("refinement after Settings turned it off")
            handleDrag(70f, expectLive = false)
            assertFalse("the live adjustment took the setting over", c.liveAdjust.fastPreview)
            live.assertConverged("exact drag after Settings")

            // 5. On in Settings: the next drag is live again.
            settingsSwitch(on = true)
            handleDrag(-50f, expectLive = true)
            live.assertConverged("live after Settings")
            Smoke.assertQuiet(c, "fast preview switch")
        }
        dog.interrupt()
        harness.finish()
    }
}
