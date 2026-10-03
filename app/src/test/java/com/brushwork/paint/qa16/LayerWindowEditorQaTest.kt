package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, the layer window in the real editor on the user's phone (392 dp): every control
 * tapped where it shows, one undo step each taken back by the top row's Undo, the + long press,
 * "New special layer", the blend dropdown, the opacity row (−, +, typed, dragged), the eye, a
 * row's tap and long press, the ≡ drag, "Edit text" on a linked text frame (the Text frames tool)
 * and the actions that leave the window ([LayerWindowQaFlow]); then the window's targets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.layerwindowsandbox"])
class LayerWindowEditorQaTest {
    @Test
    fun everyLayerWindowActionInTheEditorAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val harness = ChromeHarness()
        harness.section("layer window, 392 dp") {
            val s = harness.editor(Smoke.document(600, 800, layers = 3, whiteBottom = true))
            val flow = LayerWindowQaFlow(s, "392 dp")
            flow.open()
            flow.audit()
            flow.run()
            flow.log.forEach { println("[qa16] 392 dp $it") }
            Smoke.assertQuiet(s.c, "layer window 392 dp")
        }
        dog.interrupt()
        harness.finish()
    }
}

/** The same on a 360 x 740 dp phone (design §3.7.11 asks for 360 dp as well as 392). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.layerwindownarrowsandbox"])
class LayerWindowEditorNarrowQaTest {
    @Test
    fun everyLayerWindowActionInTheEditorAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val harness = ChromeHarness()
        harness.section("layer window, 360 dp") {
            val s = harness.editor(Smoke.document(600, 800, layers = 3, whiteBottom = true))
            val flow = LayerWindowQaFlow(s, "360 dp")
            flow.open()
            flow.audit()
            flow.run()
            flow.log.forEach { println("[qa16] 360 dp $it") }
            Smoke.assertQuiet(s.c, "layer window 360 dp")
        }
        dog.interrupt()
        harness.finish()
    }
}
