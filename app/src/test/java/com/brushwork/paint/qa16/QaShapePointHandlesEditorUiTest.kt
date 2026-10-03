package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsProperties
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.ln1p

/**
 * v1.6 final QA (handle scaling, increments everywhere): the Shape tool's Points handles on the
 * real editor at the user's phone size, increments on (Scale 25 %, Length 25 px): an ellipse
 * drawn with a finger, "Points", a point tapped; the Handles group's › steps one Scale increment,
 * its slider lands on the multiples, its typed-value dialog's + and slider too while a typed value
 * stays exact; a tangent handle dragged by a finger moves by whole Length steps from where it was
 * grabbed, the move in the info chip; each one in-tool step ("Undo point edit").
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shapehandleseditorsandbox"])
class QaShapePointHandlesEditorUiTest {

    private fun text(label: String): String = SmokeUi.field(label).node.config[SemanticsProperties.EditableText].text

    /** The Handles group slider's position for a factor [k] (10–400 %, logarithmic). */
    private fun pos(k: Double): Float = (ln1p((k - 0.1) / 0.1) / ln1p((4.0 - 0.1) / 0.1)).toFloat()

    @Test
    fun theShapePointHandlesWithIncrementsOnTheEditor() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("an ellipse's points: the Handles group and a handle drag in steps") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25", scale = "25")
            QaCurves.tool(s, "Shape")
            assertEquals(ToolId.SHAPE, c.activeToolId)
            val tool = c.currentTool as ShapeTool
            click("Shape type", exact = true)
            click(ShapeType.ELLIPSE.label, exact = true)
            assertEquals(ShapeType.ELLIPSE, tool.settings.type)
            QaCurves.snapOff(c)
            QaCurves.drag(s, Vec2(100f, 80f), Vec2(200f, 150f))
            assertTrue("a pending ellipse", tool.box != null)
            click("Points", exact = true)
            assertTrue(tool.pointsMode)
            for (label in listOf("Shorter handles", "Type handle scale", "Longer handles", "Handle scale", "In and out", "In", "Out", "All points")) {
                assertTrue("\"$label\" in the strip: ${SmokeUi.shown()}", has(label, exact = true))
            }
            val p0 = tool.docAnchors()!![0].pos
            QaCurves.tap(s, p0)
            assertEquals("the tap selected point 1", 0, tool.selectedPoint)
            fun out0() = ShapePoints.handles(tool.docAnchors()!!, 0, true).second
            val base = out0().length
            assertTrue("an ellipse's point has handles: $base", base > 10f)

            // › : one Scale step (25 %), back to 100 % at rest; one in-tool step.
            click("Longer handles")
            assertEquals("› : 125 %", base * 1.25f, out0().length, 1e-3f * base)
            assertEquals(1f, tool.handleScale, 0f)
            click("Undo point edit")
            assertEquals(base, out0().length, 1e-3f * base)

            // The slider: 160 % → 150 % (a 25 % multiple).
            QaCurves.setSlider("Handle scale", pos(1.6))
            assertEquals("the slider lands on 150 %", base * 1.5f, out0().length, 2e-3f * base)
            click("Undo point edit")
            assertEquals(base, out0().length, 1e-3f * base)

            // The typed-value dialog: + to the next multiple, its slider onto the multiples; typed exact.
            click("Type handle scale")
            assertTrue("the dialog: ${SmokeUi.shown()}", has("Handle scale", exact = true))
            assertEquals("100", text("Scale"))
            click("Increase Scale", exact = true)
            assertEquals("+ : 125", "125", text("Scale"))
            QaCurves.setSlider("Scale slider", pos(1.6))
            assertEquals("its slider: 150", "150", text("Scale"))
            SmokeUi.typeAndDone("Scale", "137")
            assertEquals("typed: exact", base * 1.37f, out0().length, 1e-3f * base)
            click("Undo point edit")
            assertEquals(base, out0().length, 1e-3f * base)

            // A finger on the out-handle's end: +37, +12 → +25, 0 from where it was grabbed.
            val a = tool.docAnchors()!![0].pos
            val start = a + out0()
            var held: String? = null
            QaCurves.drag(s, start, Vec2(37f, 12f)) { held = c.increments.readout }
            val end = tool.docAnchors()!![0].pos + out0()
            assertEquals("x: one 25 px step", start.x + 25f, end.x, 0.01f)
            assertEquals("y: no step", start.y, end.y, 0.01f)
            assertTrue("the info chip showed the move: $held", held != null)
            assertNull("gone when the finger lifts", c.increments.readout)
            click("Undo point edit")
            val back = tool.docAnchors()!![0].pos + out0()
            assertEquals(start.x, back.x, 0.01f)
            assertEquals(start.y, back.y, 0.01f)
            QaCurves.shot(s, "shape-point-handles")
            Smoke.assertQuiet(c, "shape handles")
        }
        dog.interrupt()
        h.finish()
    }
}
