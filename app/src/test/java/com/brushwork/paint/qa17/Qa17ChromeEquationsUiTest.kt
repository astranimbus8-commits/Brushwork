package com.brushwork.paint.qa17

import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.ExpressionLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 item 15 (design §3.15) in the places the user types numbers in the real editor, on the
 * 392 dp phone (the components alone are covered by OperatorKeysRobolectricTest and
 * ExpressionFieldsRobolectricTest; the pill's X field on a path point by Qa17ChromePillToolsUiTest):
 * - **Brush size** ("Type brush size"): "(3+4)*2" reads "= 14 px" and OK applies 14; "/2" gives 7,
 *   "*1.5" 10.5; "-10" is a plain number, clamped to the 0.5 px minimum; "3+*" reads "Check the
 *   expression" with OK disabled and Cancel keeps the size. A real finger on the "Plus" key over
 *   the selected value appends ("10.5+"), and the field keeps its text focus.
 * - **A slider's value editor** (Brush settings, "Hardness"): "(3+4)*2" reads "= 14 %" and Done
 *   applies 14 %; "*1.5" 21 %; "/0" reads "Can't divide by 0" and Done applies nothing.
 * - **The Step popup** (a finger held on "#" of the pill): "*1.5" over the 10 px step reads
 *   "= 15 px" and OK sets 15; increments stay as they were (a long-press is not a toggle).
 * - **The canvas size dialog** (More options, "Canvas…", "Canvas size"): Width "/2" reads
 *   "= 200 px", Height "(3+4)*20" 140; "Change canvas size" makes the canvas 200 × 140.
 * One UI test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromeequationssandbox"])
class Qa17ChromeEquationsUiTest {

    @Test
    fun equationsInTheEditorsFields() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("brush size dialog") { brushSize(h) }
        h.section("a slider's value editor") { slider(h) }
        h.section("the Step popup") { stepPopup(h) }
        h.section("the canvas size dialog") { canvasSize(h) }
        dog.interrupt()
        h.finish()
    }

    /** The keyboard's Done key on the field labelled [label]. */
    private fun imeDone(label: String) {
        requireNotNull(SmokeUi.field(label).node.config.getOrNull(SemanticsActions.OnImeAction)?.action) { "\"$label\" has no Done" }.invoke()
        settle(4)
    }

    private fun readout(prefix: String): Boolean = SmokeUi.shown().any { it.startsWith(prefix) }

    private fun typed(field: String, text: String) {
        SmokeUi.field(field).type(text)
        settle(4)
    }

    // ================================================================== brush size

    private fun brushSize(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        fun size() = c.presetFor(ToolId.BRUSH)!!.size
        fun open() {
            Finger.tap(s, "Type brush size")
            assertTrue("the dialog: ${SmokeUi.shown().take(40)}", SmokeUi.has("0.5 – 1000 px", exact = true))
            assertNotNull("the operator keys", s.tagged(V17Tags.OPERATOR_KEYS))
        }
        for ((text, want) in listOf("(3+4)*2" to 14f, "/2" to 7f, "*1.5" to 10.5f)) {
            open()
            typed("Brush size", text)
            val shown = if (want % 1f == 0f) "= ${want.toInt()} px" else "= $want px"
            assertTrue("\"$text\" reads $shown: ${SmokeUi.shown().take(60)}", SmokeUi.has(shown, exact = true))
            assertTrue(SmokeUi.isEnabled("OK"))
            click("OK", exact = true)
            assertEquals("\"$text\"", want, size(), 1e-4f)
        }
        // A real finger on "Plus": the value is selected when the dialog opens, so "+" appends.
        open()
        assertEquals("10.5", SmokeUi.field("Brush size").text)
        val plus = requireNotNull(SmokeUi.find(ExpressionLabels.PLUS, exact = true))
        plus.tap()
        settle(4)
        assertEquals("the key appends", "10.5+", SmokeUi.field("Brush size").text)
        assertTrue("the field keeps the focus", SmokeUi.field("Brush size").node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true)
        typed("Brush size", "10.5+4.5")
        assertTrue(SmokeUi.has("= 15 px", exact = true))
        // Garbage: the readout says so, OK is off, Cancel keeps the size.
        typed("Brush size", "3+*")
        assertTrue("Check the expression: ${SmokeUi.shown().take(60)}", SmokeUi.has(ExpressionLabels.INVALID, exact = true))
        assertFalse("OK is disabled", SmokeUi.isEnabled("OK"))
        click("Cancel", exact = true)
        assertFalse("closed", SmokeUi.has("0.5 – 1000 px", exact = true))
        assertEquals(10.5f, size(), 1e-4f)
        // "-10" is a plain number (a sign, not "minus 10"): clamped to the minimum.
        open()
        typed("Brush size", "-10")
        click("OK", exact = true)
        assertEquals("clamped to 0.5 px", 0.5f, size(), 1e-4f)
        Smoke.assertQuiet(c, "brush size")
    }

    // ================================================================== a slider's value editor

    private fun slider(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        fun hardness() = c.presetFor(ToolId.BRUSH)!!.hardness
        Finger.tap(s, "Open brush settings")
        click("Type a value for Hardness")
        assertNotNull("the operator keys under the slider", s.tagged(V17Tags.OPERATOR_KEYS))
        typed("Hardness", "(3+4)*2")
        assertTrue("= 14 %: ${SmokeUi.shown().take(60)}", readout("= 14"))
        imeDone("Hardness")
        assertEquals(0.14f, hardness(), 1e-4f)
        click("Type a value for Hardness")
        typed("Hardness", "*1.5")
        assertTrue("= 21: ${SmokeUi.shown().take(60)}", readout("= 21"))
        imeDone("Hardness")
        assertEquals(0.21f, hardness(), 1e-4f)
        click("Type a value for Hardness")
        typed("Hardness", "/0")
        assertTrue(SmokeUi.has(ExpressionLabels.DIV_ZERO, exact = true))
        imeDone("Hardness")
        assertEquals("\"/0\" applies nothing", 0.21f, hardness(), 1e-4f)
        Smoke.assertQuiet(c, "slider")
    }

    // ================================================================== the Step popup

    private fun stepPopup(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        ui.tool("Path")
        assertTrue(c.currentTool is CurveTool)
        ui.tap(100f, 200f)
        ui.tap(300f, 100f)
        val was = c.increments.state.step(IncrementKind.LENGTH)
        val on = c.increments.enabled
        holdOn(s, INCREMENTS_LABEL)
        assertTrue("the Step popup: ${SmokeUi.shown().take(60)}", SmokeUi.has("Step for lengths", exact = true))
        val field = "${IncrementKind.LENGTH.label} step"
        assertNotNull("the operator keys", s.tagged(V17Tags.OPERATOR_KEYS))
        typed(field, "*1.5")
        assertTrue("= ${was * 1.5f}: ${SmokeUi.shown().take(60)}", readout("= 15"))
        click("OK", exact = true)
        assertFalse(SmokeUi.has("Step for lengths", exact = true))
        assertEquals(was * 1.5f, c.increments.state.step(IncrementKind.LENGTH), 1e-4f)
        assertEquals("a long-press is not a toggle", on, c.increments.enabled)
        Smoke.assertQuiet(c, "step popup")
    }

    /** A finger held on [label] (real time: Compose's long-press timeout runs on the wall clock). */
    private fun holdOn(s: ChromeScreen, label: String) {
        val r = requireNotNull(Finger.element(s, label)) { "no \"$label\"" }
        val (x, y) = Finger.px(s, r.center.x, r.center.y)
        s.touch.idle(300)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, x, y))
        s.touch.holdRealTime(900)
        s.touch.send(MotionEvent.ACTION_UP, P(0, x, y))
        settle(4)
    }

    // ================================================================== the canvas size dialog

    private fun canvasSize(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        Finger.tap(s, "More options")
        click("Canvas…", exact = true)
        SmokeUi.clickTab("Canvas size")
        assertTrue(SmokeUi.has("Change canvas size", exact = true))
        SmokeUi.field("Width").focus()
        settle(2)
        assertNotNull("the operator keys under the field", s.tagged(V17Tags.OPERATOR_KEYS))
        typed("Width", "/2")
        assertTrue("= 200 px: ${SmokeUi.shown().take(60)}", SmokeUi.has("= 200 px", exact = true))
        imeDone("Width")
        SmokeUi.field("Height").focus()
        settle(2)
        typed("Height", "(3+4)*20")
        assertTrue("= 140 px: ${SmokeUi.shown().take(60)}", SmokeUi.has("= 140 px", exact = true))
        imeDone("Height")
        assertTrue(SmokeUi.has("200 × 140 px", exact = true))
        val steps = c.undoManager.undoCount
        click("Change canvas size", exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); c.doc.width == 200 && c.busyMessage == null })
        assertEquals(200 to 140, c.doc.width to c.doc.height)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "canvas size")
    }
}
