package com.brushwork.paint.qa16

import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, adjustment masks on a 20 MP canvas (4000 x 5000, 3 layers below the adjustment
 * layer and 2 above) on the user's phone (392 dp): the real editor, policy LIVE with an injected
 * clock, one `CanvasView.draw` per move. + Linear, an Exposure drag, + Radial and its radius
 * handle, the layer window's opacity drag, then Undo and Redo through them: proxies at 1/4, each
 * live frame a small part of the exact frame and of the v1.5 stage (JVM-relative; T606 times are
 * device-only), one step per action, and the canvas exact again after each (I7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.maskslive20mpsandbox"])
class MasksLive20MpQaTest {
    private val w = 4000
    private val h = 5000

    @Test
    fun theMasksFlowOnA20MegapixelCanvasIsLiveAndConverges() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val harness = ChromeHarness()
        harness.section("the Masks flow at 4000 x 5000") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            live.draw()
            val flow = MasksLiveFlow(live, w, h, "4000x5000")
            flow.createLinear()
            flow.exposureDrag()
            flow.addRadial()
            flow.radiusHandleDrag()
            flow.layerOpacityDrag()
            val history = flow.undoRedo(
                listOf("Opacity", "Edit mask", "Mask: radial", "Edit adjustment"),
                viaSession = setOf("Opacity", "Edit mask", "Mask: radial", "Edit adjustment"),
            )
            println("[qa16] 4000x5000 canvas ${live.width} x ${live.height} at zoom ${s.c.viewTransform.zoom}")
            flow.costs.forEach { println("[qa16] 4000x5000 $it") }
            (flow.notes + history).forEach { println("[qa16] 4000x5000 $it") }
            flow.costs.forEach { assertEquals("${it.what}: proxy scale", 0.25f, it.scale) }
            flow.costs.forEach { it.assertCheap("4000x5000") }
            Smoke.assertQuiet(s.c, "masks flow 4000 x 5000")
        }
        dog.interrupt()
        harness.finish()
    }
}
