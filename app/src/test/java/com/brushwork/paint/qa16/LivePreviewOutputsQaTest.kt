package com.brushwork.paint.qa16

import android.graphics.Bitmap
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskHandles
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, I7 (previews are views) for the live adjustment on the user's phone (392 dp,
 * 1080 x 2408, policy LIVE, injected clock): while an Exposure drag and a mask handle drag are
 * drawn from proxies, and while the change refines after the finger lifts, what the app writes
 * or reads out — the export (`renderFlattened`, on white as JPG), the saved / layer-window
 * thumbnail (`renderThumbnail`), the eyedropper — is exactly what a compositor that never saw a
 * session renders from the same document; the layer's mask stays the committed one until the
 * handle lets go; and "Apply to layer below" pressed while the change is still refining gives
 * the pixels it gives once the canvas is exact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.liveoutputssandbox"])
class LivePreviewOutputsQaTest {
    private val w = 1080
    private val h = 2408
    private val white = 0xFFFFFFFF.toInt()

    private fun Bitmap.px(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun nothingPreviewOnlyReachesExportThumbnailsTheEyedropperOrApplyBelow() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val harness = ChromeHarness()
        harness.section("outputs while live") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            val c = s.c
            live.draw()
            val flow = MasksLiveFlow(live, w, h, "outputs")
            flow.createLinear()
            flow.addRadial()
            val adj = flow.adj
            val eyedropper = c.tools.getValue(ToolId.EYEDROPPER) as EyedropperTool
            eyedropper.settings = eyedropper.settings.copy(source = SampleSource.CANVAS, sampleSize = 1)
            val points = listOf(540 to 1080, 700 to 1100, 300 to 600, 800 to 2000, 540 to 300, 200 to 1700)
            var checks = 0

            /** Export, thumbnail and eyedropper equal a session-free compositor's render of the same document. */
            fun assertOutputsExact(where: String) {
                val fresh = Compositor(c.doc) { null }
                val jpg = c.compositor.renderFlattened(white)
                val jpgRef = fresh.renderFlattened(white)
                assertSamePixels("$where: export on white", jpgRef.px(), jpg.px(), w)
                val png = fresh.renderFlattened()
                assertSamePixels("$where: export", png.px(), c.compositor.renderFlattened().px(), w)
                val thumb = c.compositor.renderThumbnail(512)
                val thumbRef = fresh.renderThumbnail(512)
                assertEquals(thumbRef.width, thumb.width)
                assertSamePixels("$where: thumbnail (saved with the project, the layer window's preview)", thumbRef.px(), thumb.px(), thumb.width)
                for ((x, y) in points) {
                    val p = png.getPixel(x, y)
                    val want = EyedropperTool.averageOpaque(intArrayOf(p), 1)
                    assertEquals("$where: eyedropper at ($x, $y)", want, eyedropper.sample(x + 0.5f, y + 0.5f))
                }
                listOf(jpg, jpgRef, png, thumb, thumbRef).forEach { it.recycle() }
                checks++
            }

            // 1. Mid Exposure drag, the frame drawn from proxies.
            settle()
            live.s.touch.idle(1300)
            click("Adjust…", exact = true)
            var midDrag = 0
            live.sliderDrag(live.sliderUnder("Exposure"), 0.5f, 0.85f, moves = 10) { i ->
                if (c.liveAdjust.isActive) {
                    live.liveFrame("Exposure move $i")
                    if (i == 5 || i == 9) { assertOutputsExact("Exposure move $i (live)"); midDrag++ }
                } else {
                    live.draw()
                }
            }
            assertEquals("checked twice under the finger", 2, midDrag)
            // 2. Refining after the sheet closes (one step), before any tile is exact again.
            click("Close", exact = true)
            settle()
            assertTrue("the change refines", c.liveAdjust.isActive)
            assertOutputsExact("after Close, refining")
            live.assertConverged("Exposure")
            assertOutputsExact("Exposure, converged")

            // 3. Mid radius-handle drag: the preview mask is the canvas's only (useOverrides is
            // false for outputs) and the layer's mask and spec stay the committed ones.
            val radial = adj.maskSpec!!.components.last() as RadialMask
            val p = MaskHandles.position(radial, MaskHandles.Kind.RX_POS, c.viewTransform)!!
            val specBefore = adj.maskSpec
            val maskBefore = adj.mask!!.px()
            var handleChecks = 0
            live.canvasDrag(p.x to p.y, p.x + 160f to p.y, moves = 8) { i ->
                if (c.liveAdjust.isActive) {
                    live.liveFrame("handle move $i")
                    if (i == 6) {
                        assertSame("the spec is committed when the handle lets go", specBefore, adj.maskSpec)
                        assertSamePixels("handle move $i: the layer's mask (saved with the project)", maskBefore, adj.mask!!.px(), w)
                        assertOutputsExact("handle move $i (live)")
                        handleChecks++
                    }
                } else {
                    live.draw()
                }
            }
            assertEquals(1, handleChecks)
            assertFalse("the handle's step changed the mask", maskBefore.contentEquals(adj.mask!!.px()))
            assertOutputsExact("handle, released")
            live.assertConverged("handle")

            // 4. "Apply to layer below" while an Exposure change still refines, then once exact.
            click("Adjust…", exact = true)
            live.sliderDrag(live.sliderUnder("Exposure"), 0.85f, 0.65f, moves = 6) { live.draw() }
            click("Close", exact = true)
            settle()
            click("Open layers")
            assertTrue("still refining when the layer is applied", c.liveAdjust.isActive)
            val idx = c.doc.indexOf(adj)
            val below = c.doc.layers[idx - 1]
            val n = live.steps()
            click(LayerLabels.APPLY_BELOW, exact = true)
            assertEquals("one step", n + 1, live.steps())
            assertEquals("the adjustment layer is applied", -1, c.doc.indexOf(adj))
            val appliedRefining = c.doc.layers[idx - 1].bitmap.px()
            live.assertConverged("applied while refining")
            assertOutputsExact("applied while refining")
            click("Undo", exact = true)
            assertEquals("Undo brings the adjustment layer back", idx, c.doc.indexOf(adj))
            assertSame(below, c.doc.layers[idx - 1])
            live.assertConverged("undo of apply")
            click(LayerLabels.APPLY_BELOW, exact = true)
            assertEquals(n + 1, live.steps())
            assertSamePixels("Apply to layer below: refining vs exact", c.doc.layers[idx - 1].bitmap.px(), appliedRefining, w)
            live.assertConverged("applied when exact")
            println("[qa16] outputs: $checks checks of export, thumbnail and eyedropper; apply step \"${c.undoManager.undoLabel}\"")
            click(LayerLabels.CLOSE, exact = true)
            Smoke.assertQuiet(c, "live outputs")
        }
        dog.interrupt()
        harness.finish()
    }
}
