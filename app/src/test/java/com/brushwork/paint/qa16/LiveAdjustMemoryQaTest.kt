package com.brushwork.paint.qa16

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Canvas
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, the live adjustment when memory is short, on the user's phone (392 dp,
 * 1080 x 2408, policy LIVE, injected clock), during Exposure drags in the Masks tool's Adjust
 * sheet: an `OutOfMemoryError` while a frame makes its proxies (thrown by a layer above the
 * adjustment layer as the proxy draws it) gives ONE toast, the rest of that drag is drawn exactly
 * (no crash, the sheet's one step, the canvas exact), and the next drag is live again; a session
 * over the memory cap even at 1/8 takes v1.5's exact path quietly; `onTrimMemory` mid-drag ends
 * the session (the drag goes on), and after a session it frees the kept buffers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.livememorysandbox"])
class LiveAdjustMemoryQaTest {
    private val w = 1080
    private val h = 2408

    /** "Above 1" drawn as always, except once, when armed, while a session draws: out of memory. */
    private class ThrowOnce(override val layer: Layer, val sessionDrawing: () -> Boolean) : LayerRenderOverride {
        var armed = false
        var thrown = 0
        override fun drawContent(canvas: Canvas): Boolean {
            if (armed && sessionDrawing()) {
                armed = false
                thrown++
                throw OutOfMemoryError("qa16: no room for the proxy")
            }
            return false
        }
    }

    @Test
    fun outOfMemoryAndTheMemoryCapFallBackToExactFramesWithoutACrash() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val harness = ChromeHarness()
        harness.section("live adjustment, memory short") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            val c = s.c
            live.draw()
            MasksLiveFlow(live, w, h, "memory").createLinear()
            settle()
            live.s.touch.idle(1300)
            var toasts = 0
            var drawingFrame = false

            /** One Exposure drag in the Adjust sheet, a frame per move; returns the moves a session drew. */
            fun exposureDrag(from: Float, to: Float, each: (Int) -> Unit = {}): Int {
                click("Adjust…", exact = true)
                val before = live.steps()
                var liveFrames = 0
                live.sliderDrag(live.sliderUnder("Exposure"), from, to, moves = 8) { i ->
                    val active = c.liveAdjust.isActive
                    drawingFrame = true
                    try {
                        live.draw()
                    } finally {
                        drawingFrame = false
                    }
                    if (active && c.liveAdjust.isActive) liveFrames++
                    if (c.message == LiveAdjust.OUT_OF_MEMORY) { toasts++; c.message = null }
                    each(i)
                }
                click("Close", exact = true)
                settle()
                assertEquals("the sheet's one step", before + 1, live.steps())
                assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
                return liveFrames
            }

            // 1. Out of memory while the proxies are made.
            val above = c.doc.layers.first { it.name == "Above 1" }
            val ov = ThrowOnce(above) { drawingFrame && c.liveAdjust.isActive }
            c.renderOverride = ov
            ov.armed = true
            var exactChecked = false
            val liveFrames = exposureDrag(0.5f, 0.8f) { i ->
                if (ov.thrown == 1 && i >= 5 && !exactChecked) {
                    assertFalse("the rest of the drag has no session", c.liveAdjust.isActive)
                    val frame = live.lastPixels()
                    assertSamePixels("move $i after the error: the exact frame", live.exact(), frame, live.width)
                    exactChecked = true
                }
            }
            assertEquals("the error was thrown once", 1, ov.thrown)
            assertEquals("one toast", 1, toasts)
            assertEquals("no frame of that drag was live after the error", 0, liveFrames)
            assertTrue(exactChecked)
            c.renderOverride = null
            live.assertConverged("after the out-of-memory drag")

            // 2. The next drag is live again, without a toast.
            assertTrue("the next drag is live", exposureDrag(0.8f, 0.6f) >= 4)
            assertEquals(1, toasts)
            live.assertConverged("live again")

            // 3. Over the memory cap even at 1/8: v1.5's exact path, quietly.
            c.liveAdjust.memoryCapBytes = 1L
            assertEquals("no session over the cap", 0, exposureDrag(0.6f, 0.4f) { assertFalse(c.liveAdjust.isActive) })
            assertEquals("no toast for the cap", 1, toasts)
            assertEquals(0L, c.liveAdjust.proxyBytes)
            live.assertConverged("over the cap")
            c.liveAdjust.memoryCapBytes = LiveAdjust.MEMORY_CAP_BYTES
            assertTrue("live again under the cap", exposureDrag(0.4f, 0.55f) >= 4)
            live.assertConverged("under the cap again")
            assertTrue("buffers kept for the next drag", c.liveAdjust.proxyBytes > 0)

            // 4. onTrimMemory: after a session it frees the kept buffers; mid-drag it ends the
            // session, the drag goes on (live again from the next move) and converges.
            val app = c.appContext as Application
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals("trim frees the kept buffers", 0L, c.liveAdjust.proxyBytes)
            var trimmed = false
            exposureDrag(0.55f, 0.75f) { i ->
                if (i == 4) {
                    assertTrue(c.liveAdjust.isActive)
                    app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                    assertFalse("a low-memory trim ends the session", c.liveAdjust.isActive)
                    trimmed = true
                }
            }
            assertTrue(trimmed)
            assertEquals("no toast for a trim", 1, toasts)
            live.assertConverged("trimmed mid-drag")
            Smoke.assertQuiet(c, "live adjustment memory")
        }
        dog.interrupt()
        harness.finish()
    }
}
