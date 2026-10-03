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
 * v1.6 final QA, adjustment masks on the user's phone (392 dp, the 1080 x 2408 canvas, 3 layers
 * below the adjustment layer and 2 above): the real editor, policy LIVE with an injected clock,
 * one `CanvasView.draw` per move as the phone's vsync. + Linear, an Exposure drag in the Adjust
 * sheet, + Radial, its radius handle and pin, + Brush, the layer window's opacity drag, then top
 * row Undo and Redo through all of it: every live drag is drawn from proxies, each action is one
 * step, and once refined the canvas is bit for bit the canvas no session touched (I7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.maskslivephonesandbox"])
class MasksLivePhoneQaTest {
    private val w = 1080
    private val h = 2408

    @Test
    fun theMasksFlowOnThePhonesCanvasIsLiveAndConverges() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val harness = ChromeHarness()
        harness.section("the Masks flow at 1080 x 2408") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            live.draw()
            val flow = MasksLiveFlow(live, w, h, "1080x2408")
            flow.createLinear()
            flow.exposureDrag()
            flow.addRadial()
            flow.radiusHandleDrag()
            flow.pinDrag()
            flow.brushStroke()
            flow.layerOpacityDrag()
            val history = flow.undoRedo(
                listOf("Opacity", "Mask: brush", "Move mask", "Edit mask", "Mask: radial", "Edit adjustment"),
                viaSession = setOf("Opacity", "Move mask", "Edit mask", "Mask: radial", "Edit adjustment"),
            )
            println("[qa16] 1080x2408 canvas ${live.width} x ${live.height} at zoom ${s.c.viewTransform.zoom}")
            flow.costs.forEach { println("[qa16] 1080x2408 $it") }
            (flow.notes + history).forEach { println("[qa16] 1080x2408 $it") }
            // Proxies at 1/2 (the fitted zoom is about 0.72), and every live drag proxy-cheap.
            flow.costs.forEach { assertEquals("${it.what}: proxy scale", 0.5f, it.scale) }
            flow.costs.forEach { it.assertCheap("1080x2408") }
            Smoke.assertQuiet(s.c, "masks flow 1080 x 2408")
        }
        dog.interrupt()
        harness.finish()
    }
}
