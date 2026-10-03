package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere: text move and font size) on the real editor at the user's
 * phone size, through what a finger reaches: Text in the tool menu, a tap on the canvas, the text
 * typed, the Size field of the text editor in px (its slider, its − / + and a typed value), OK,
 * then a finger drag on the text, ✓. With "#" on (More › Increments…: Length 25 px, Size 3 px) the
 * Size slider lands on multiples of 3 px, − / + go to the next multiple, a typed size is kept
 * exactly, and the move goes by whole 25 px steps per axis from where the finger went down (the
 * info chip says so). With "#" off the size buttons go by 1 px and the move follows the finger
 * (v1.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.textincrementssandbox"])
class QaTextIncrementsUiTest {

    /** The Size field's slider in the text editor (described exactly "Size"). */
    private fun setSizeSlider(f: Float) {
        val e = RobolectricUi.elements().last { e ->
            e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
                e.node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Size")
        }
        requireNotNull(e.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(f)
        settle(4)
    }

    /** Snapping to objects off, with the strip's chip where the strip has one. */
    private fun snapOff(s: ChromeScreen) {
        if (!s.c.snapping.enabled) return
        if (has("Snap to objects", exact = true) || has("Snap", exact = true)) QaCurves.snapOff(s.c) else s.c.snapping.enabled = false
        assertFalse(s.c.snapping.enabled)
    }

    /** Text tool, a tap at (200, 150), "Step" typed, the size in px. */
    private fun startText(s: ChromeScreen): TextTool {
        val c = s.c
        QaCurves.tool(s, "Text")
        assertEquals(ToolId.TEXT, c.activeToolId)
        val text = c.currentTool as TextTool
        snapOff(s)
        QaCurves.tap(s, 200f, 150f)
        assertTrue("a tap opens the text editor", text.editorOpen)
        SmokeUi.field("Text").type("Step")
        settle()
        click("Change unit", exact = true)
        click("${LengthUnit.PX.label} (${LengthUnit.PX.short})", exact = true)
        assertEquals(LengthUnit.PX, text.sizeUnit)
        return text
    }

    @Test
    fun textMoveAndFontSizeStepWithIncrementsOnAndAreV15Off() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("\"#\" on (Length 25, Size 3): Size slider, − / +, typed; a finger move; ✓") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25", size = "3")
            val text = startText(s)
            fun size() = text.item!!.spec.sizePx

            // The slider lands on multiples of 3 px (or a range end).
            for (f in listOf(0.31f, 0.47f, 0.66f)) {
                setSizeSlider(f)
                assertTrue("slider $f: ${size()} px is a multiple of 3", QaCurves.onStep(size(), 3f, 1e-2f))
            }
            // − / + go to the next multiple.
            SmokeUi.typeAndDone("Size", "31")
            assertEquals("typed: exact", 31f, size(), 1e-3f)
            SmokeUi.tap("Increase Size", exact = true)
            settle(2)
            assertEquals("+ : the next multiple of 3", 33f, size(), 1e-3f)
            SmokeUi.tap("Increase Size", exact = true)
            settle(2)
            assertEquals(36f, size(), 1e-3f)
            SmokeUi.tap("Decrease Size", exact = true)
            settle(2)
            assertEquals("− : one step back", 33f, size(), 1e-3f)
            SmokeUi.typeAndDone("Size", "40")
            assertEquals("typed: exact, never stepped", 40f, size(), 1e-3f)
            click("OK", exact = true)
            assertFalse(text.editorOpen)
            assertEquals("the size stays as typed", 40f, size(), 1e-3f)

            // A finger drag on the text (+37, +12) px: +25 px, 0 px.
            val steps = c.undoManager.undoCount
            val at = text.item!!.let { Vec2(it.cx, it.cy) }
            var held: String? = null
            QaCurves.drag(s, at, Vec2(37f, 12f)) { held = c.increments.readout }
            assertEquals("x: one 25 px step", at.x + 25f, text.item!!.cx, 1e-3f)
            assertEquals("y: under half a step stays", at.y, text.item!!.cy, 1e-3f)
            assertEquals("the info chip", "+25 px, 0 px", held)
            assertNull("the readout goes with the finger", c.increments.readout)
            // Another drag (−20, +40): −25, +50 from where this finger went down.
            val at2 = text.item!!.let { Vec2(it.cx, it.cy) }
            QaCurves.drag(s, at2, Vec2(-20f, 40f))
            assertEquals(at2.x - 25f, text.item!!.cx, 1e-3f)
            assertEquals(at2.y + 50f, text.item!!.cy, 1e-3f)
            QaCurves.shot(s, "text-increments")

            click("Apply text edit")
            assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
            assertEquals(40f, com.brushwork.paint.tools.text.TextCodec.decode(c.activeLayer.textData)!!.spec.sizePx, 1e-3f)
            Smoke.assertQuiet(c, "text on")
        }
        h.section("\"#\" off: − / + by 1 px and the move follows the finger (v1.5)") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOff()
            val text = startText(s)
            fun size() = text.item!!.spec.sizePx
            SmokeUi.typeAndDone("Size", "31")
            SmokeUi.tap("Increase Size", exact = true)
            settle(2)
            assertEquals("+ : 1 px", 32f, size(), 1e-3f)
            SmokeUi.tap("Decrease Size", exact = true)
            SmokeUi.tap("Decrease Size", exact = true)
            settle(2)
            assertEquals(30f, size(), 1e-3f)
            click("OK", exact = true)
            val at = text.item!!.let { Vec2(it.cx, it.cy) }
            QaCurves.drag(s, at, Vec2(37f, 12f))
            assertEquals("free", at.x + 37f, text.item!!.cx, 0.05f)
            assertEquals("free", at.y + 12f, text.item!!.cy, 0.05f)
            assertNull(c.increments.readout)
            Smoke.assertQuiet(c, "text off")
        }
        dog.interrupt()
        h.finish()
    }
}
