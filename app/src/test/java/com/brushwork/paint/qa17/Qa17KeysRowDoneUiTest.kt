package com.brushwork.paint.qa17

import android.view.MotionEvent
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.7 final QA polish (the array verifier's report), on the user's 392 dp phone: a value typed
 * into a field and committed with the keyboard's Done closes the operator-keys row at once, so the
 * next control a finger takes does not move under it.
 * - **A number field of the Array sheet** ("Constant X", a scrub field): typed, Done. The keys row
 *   is gone and the field has let the focus go. A finger then scrubs "Constant Y" (just below):
 *   its handle stays where the finger put it from the touch-down to the lift (before the fix the
 *   row stayed open, the scrub's touch-down closed it and the handle jumped 42 dp up under the
 *   finger), and the drag sets Constant Y in ONE "Edit array" step.
 * - **A slider's value editor** (Brush settings, "Hardness"): typed, Done; the keys row is gone.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.keysrowdonesandbox"])
class Qa17KeysRowDoneUiTest {

    @Test
    fun doneClosesTheKeysRowAndTheNextScrubDoesNotMove() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("a number field of the Array sheet: Done, then a scrub below it") { arrayField(Qa17ArrayUi(h)) }
        h.section("a slider's value editor: Done") { sliderEditor(h) }
        dog.interrupt()
        h.finish()
    }

    private fun keysOpen(u: Qa17ArrayUi): Boolean = u.s.tagged(V17Tags.OPERATOR_KEYS) != null

    private fun arrayField(u: Qa17ArrayUi) {
        u.editor(Smoke.document(512, 512, layers = 2, whiteBottom = true))
        val c = u.c
        // A raster array of a 100 × 100 square, made through the sheet (Line mode).
        u.seed(c.activeLayer, 20f, 20f, 120f, 120f, Qa17ArrayUi.RED)
        c.setSelection(u.rectSelection(14f, 14f, 126f, 126f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer = c.activeLayer
        val tool = c.currentTool as ArrayTool
        u.showArraySheet()

        // Typed, Done: committed, and the keys row closes with it.
        val steps = u.steps()
        u.intoView("Constant X") { SmokeUi.field("Constant X").node }
        SmokeUi.field("Constant X").focus()
        settle(2)
        assertTrue("typing in the field shows the keys row", keysOpen(u))
        SmokeUi.field("Constant X").type("30")
        settle(2)
        val done = requireNotNull(SmokeUi.field("Constant X").node.config[androidx.compose.ui.semantics.SemanticsActions.OnImeAction].action)
        done.invoke()
        settle(4)
        u.settleRenders("Constant X typed")
        assertEquals("Done applies 30", 30f, layer.array!!.spec.constantX, 1e-3f)
        assertEquals("ONE step", steps + 1, u.steps())
        assertFalse("Done closes the keys row at once; shown: ${SmokeUi.shown().take(60)}", keysOpen(u))
        assertFalse("the field let the focus go", SmokeUi.field("Constant X").focused)

        // A finger scrubs Constant Y, the field below: its handle does not move under the finger.
        val scrub = "Drag sideways to change Constant Y"
        u.intoView(scrub) { requireNotNull(SmokeUi.find(scrub, exact = true)) { "no \"$scrub\"" }.node }
        settle(20)
        val e = requireNotNull(SmokeUi.find(scrub, exact = true))
        val b0 = e.bounds
        fun handleTop(): Float = requireNotNull(SmokeUi.find(scrub, exact = true)) { "the handle went" }.bounds.top
        val touch = Smoke.Touch(e.window)
        val x0 = b0.center.x
        val y0 = b0.center.y
        val spec0: ArraySpec = layer.array!!.spec
        val before = u.steps()
        var worst = 0f
        u.s.touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, x0, y0))
        try {
            settle(1)
            worst = maxOf(worst, abs(handleTop() - b0.top))
            for (i in 1..10) {
                u.s.touch.idle(16)
                touch.send(MotionEvent.ACTION_MOVE, P(0, x0 + 60f * u.s.density * i / 10f, y0))
                settle(1)
                worst = maxOf(worst, abs(handleTop() - b0.top))
            }
        } finally {
            u.s.touch.idle(16)
            touch.send(MotionEvent.ACTION_UP, P(0, x0 + 60f * u.s.density, y0))
            settle(1, 16)
        }
        assertTrue("the handle stays under the finger: it moved ${worst / u.s.density} dp", worst <= 1f)
        u.settleRenders("Constant Y scrubbed")
        assertNotEquals("the scrub set Constant Y", spec0.constantY, layer.array!!.spec.constantY)
        assertEquals("Constant X kept", 30f, layer.array!!.spec.constantX, 1e-3f)
        assertEquals("the scrub is ONE step", before + 1, u.steps())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertNull("the preview handed over", tool.previewSpec)
        assertFalse(keysOpen(u))
        Smoke.assertQuiet(c, "array field")
    }

    private fun sliderEditor(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        Finger.tap(s, "Open brush settings")
        click("Type a value for Hardness")
        assertNotNull("the keys row under the slider", s.tagged(V17Tags.OPERATOR_KEYS))
        SmokeUi.field("Hardness").type("40")
        settle(4)
        requireNotNull(SmokeUi.field("Hardness").node.config[androidx.compose.ui.semantics.SemanticsActions.OnImeAction].action).invoke()
        settle(4)
        assertEquals("Done applies 40 %", 0.40f, c.presetFor(ToolId.BRUSH)!!.hardness, 1e-4f)
        assertNull("Done closes the keys row at once", s.tagged(V17Tags.OPERATOR_KEYS))
        Smoke.assertQuiet(c, "slider editor")
    }
}
