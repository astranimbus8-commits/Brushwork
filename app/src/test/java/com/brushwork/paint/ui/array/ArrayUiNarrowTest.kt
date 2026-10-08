package com.brushwork.paint.ui.array

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 3, §3.3 a; area E) on the user's 392 dp phone, on the full editor, by finger and by
 * label: "Array from selection" opens the Array sheet at most half the screen high, the four
 * "Line | Circle | Curve | Transform" segments and the pinned "Remove array" / "Apply array" are
 * on screen at once, and every mode's controls are reached in the sheet and take effect as ONE
 * "Edit array" step each (Count's stepper, typed Line and Transform fields, Sweep's stepper and
 * "Rotate copies", a guide drawn on the canvas). "Edit source pixels" goes to the brush and the
 * Array tool, picked again from the tool menu, finishes the edit; the strip chip ("Array
 * settings") reopens a closed sheet; Apply and Remove are one step each, and a text array asks
 * "Apply turns the text into pixels" first. One test: Compose's frame clock serves only the
 * first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.array.arraynarrowsandbox"])
class ArrayUiNarrowTest {
    private lateinit var h: ChromeHarness

    @Test
    fun theArraySheetAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("a raster array and its four modes at 392 dp") { fourModes() }
        h.section("a text array asks before applying") { textApply() }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    private fun screen(): ChromeScreen = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))

    /** Runs [block] (a click, a typed value, a stroke) and asserts it recorded ONE step [label]. */
    private fun oneStep(c: EditorController, label: String, what: String, block: () -> Unit) {
        val steps = c.undoManager.undoCount
        block()
        settle(4)
        assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(what, label, c.undoManager.undoLabel)
    }

    private fun arrayTool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /**
     * Brings the text field labelled [label] wholly into view as a finger does, by scrolling the
     * sheet 60 dp at a time (a field has no click action, so [Qa16Ui.reach] does not find it).
     */
    private fun reachField(s: ChromeScreen, label: String) {
        repeat(40) {
            val n = SmokeUi.field(label).node
            val r = s.root
            val top = n.positionInWindow.y
            val bottom = top + n.size.height
            val b = n.boundsInWindow
            if (b.height >= n.size.height - 1f && top >= r.top - 0.5f && bottom <= r.bottom + 0.5f) {
                assertTrue("\"$label\" is at least 40 dp high", n.size.height >= 40f * s.density - 1f)
                return
            }
            var p: SemanticsNode? = n.parent
            while (p != null && !(p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { it.maxValue() > 0f } == true && p.config.getOrNull(SemanticsActions.ScrollBy) != null)) p = p.parent
            val sheet = p ?: throw AssertionError("\"$label\" is cut and nothing scrolls it: $b")
            val v = sheet.boundsInWindow
            val dy = when {
                bottom > minOf(v.bottom, r.bottom) -> 60f * s.density
                top < maxOf(v.top, r.top) -> -60f * s.density
                else -> throw AssertionError("\"$label\" cannot be brought wholly into view: $b")
            }
            requireNotNull(sheet.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, dy)
            settle(2)
        }
        throw AssertionError("\"$label\" never came wholly into view")
    }

    private fun fourModes() {
        val s = screen()
        val ui = Qa16Ui(s)
        val c = s.c
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(60f, 60f, 100f, 100f, Paint().apply { color = 0xFFDD2211.toInt() })
        src.markChanged()
        c.setSelection(Selection.fromPath(Path().apply { addRect(50f, 50f, 110f, 110f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        settle(4)

        // The selection bar's "Array": a new layer, the Array tool and its sheet.
        ui.reach(ArrayLabels.FROM_SELECTION)
        oneStep(c, ArrayLabels.BUTTON, "Array from selection") { click(ArrayLabels.FROM_SELECTION, exact = true) }
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer: Layer = c.activeLayer
        assertNotNull(layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val sheet = s.tagged(V17Tags.ARRAY_SHEET) ?: throw AssertionError("the Array sheet is not open; shown: ${SmokeUi.shown().take(60)}")
        assertTrue("the sheet is at most half the screen high (${sheet.height} of ${s.heightDp} dp)", sheet.height <= s.heightDp * 0.5f + 1f)
        assertTrue("the sheet fits the width", sheet.left >= -0.5f && sheet.right <= s.widthDp + 0.5f)
        for (m in ArrayMode.entries) assertTrue("\"${spokenName(m)}\" is on screen at once, 44 dp", ui.wholly(spokenName(m), 44f))
        for (label in listOf(ArrayLabels.REMOVE, ArrayLabels.APPLY)) assertTrue("\"$label\" is pinned on screen", ui.wholly(label))

        // Count: the stepper, one step.
        ui.reach("Increase Count")
        oneStep(c, ArrayLabels.EDIT, "Increase Count") { click("Increase Count", exact = true) }
        assertEquals(4, layer.array!!.spec.count)

        // Line: a typed constant offset.
        reachField(s, "Relative X")
        reachField(s, "Constant Y")
        oneStep(c, ArrayLabels.EDIT, "Constant Y") { SmokeUi.typeAndDone("Constant Y", "25") }
        assertEquals(25f, layer.array!!.spec.constantY, 1e-3f)

        // Circle: Sweep's stepper and "Rotate copies".
        ui.reach(ArrayLabels.CIRCLE, 44f)
        oneStep(c, ArrayLabels.EDIT, "Circle") { click(ArrayLabels.CIRCLE, exact = true) }
        assertEquals(ArrayMode.CIRCLE, layer.array!!.spec.mode)
        ui.reach("Decrease Sweep")
        oneStep(c, ArrayLabels.EDIT, "Decrease Sweep") { click("Decrease Sweep", exact = true) }
        assertEquals(345f, layer.array!!.spec.sweepDeg, 1e-3f)
        ui.reach("Rotate copies")
        oneStep(c, ArrayLabels.EDIT, "Rotate copies") { click("Rotate copies", exact = true) }
        assertFalse(layer.array!!.spec.rotateCopies)

        // Transform: typed Turn and Scale per copy.
        ui.reach(ArrayLabels.TRANSFORM, 44f)
        oneStep(c, ArrayLabels.EDIT, "Transform") { click(ArrayLabels.TRANSFORM, exact = true) }
        reachField(s, "Move X")
        reachField(s, "Turn")
        oneStep(c, ArrayLabels.EDIT, "Turn") { SmokeUi.typeAndDone("Turn", "45") }
        assertEquals(45f, layer.array!!.spec.turnDeg, 1e-3f)
        reachField(s, ArrayLabels.SCALE_PER_COPY)
        oneStep(c, ArrayLabels.EDIT, ArrayLabels.SCALE_PER_COPY) { SmokeUi.typeAndDone(ArrayLabels.SCALE_PER_COPY, "0.8") }
        assertEquals(0.8f, layer.array!!.spec.scale, 1e-3f)

        // Curve: "Draw guide", then a finger stroke on the canvas above the sheet.
        ui.reach(ArrayLabels.CURVE, 44f)
        oneStep(c, ArrayLabels.EDIT, "Curve") { click(ArrayLabels.CURVE, exact = true) }
        assertEquals(ArrayMode.CURVE, layer.array!!.spec.mode)
        reachField(s, ArrayLabels.COPY_SPACING)
        ui.reach("Align to curve")
        for (label in listOf(USE_PATH, DRAW_GUIDE)) ui.reach(label, 44f)
        click(DRAW_GUIDE, exact = true)
        assertEquals(ArrayTool.GuideInput.DRAW, arrayTool(c).guideInput)
        oneStep(c, ArrayLabels.EDIT, "a drawn guide") { ui.stroke(*Array(12) { i -> (40f + i * 25f) to (40f + 20f * kotlin.math.sin(i / 2.0).toFloat()) }) }
        val guide = layer.array!!.spec.guide ?: throw AssertionError("no guide")
        assertTrue(guide.anchors.size >= 2)
        // The canvas touch folded the sheet into its pill; it comes back from there.
        if (s.tagged(V17Tags.ARRAY_SHEET) == null) click("Show Array", exact = true)
        assertNotNull("the sheet is back", s.tagged(V17Tags.ARRAY_SHEET))

        // Line again, then "Edit source pixels": the brush paints the source alone.
        ui.reach(ArrayLabels.LINE, 44f)
        oneStep(c, ArrayLabels.EDIT, "Line") { click(ArrayLabels.LINE, exact = true) }
        ui.reach(ArrayLabels.EDIT_SOURCE)
        oneStep(c, ArrayLabels.EDIT_SOURCE, ArrayLabels.EDIT_SOURCE) { click(ArrayLabels.EDIT_SOURCE, exact = true) }
        assertEquals(ToolId.BRUSH, c.activeToolId)
        assertTrue(layer.array!!.spec.editingSource)
        assertNull("the sheet closed", s.tagged(V17Tags.ARRAY_SHEET))
        // The Array tool, picked again, finishes the edit.
        oneStep(c, ArrayLabels.FINISH_SOURCE, "the Array tool picked again") { ui.tool(ToolId.ARRAY.label) }
        assertFalse(layer.array!!.spec.editingSource)
        assertNotNull("the sheet opens with the tool", s.tagged(V17Tags.ARRAY_SHEET))

        // Closed with ✕, reopened from the strip's chip.
        click("Close", exact = true)
        assertNull(s.tagged(V17Tags.ARRAY_SHEET))
        assertTrue("the strip shows the array", has(SETTINGS, exact = true))
        click(SETTINGS, exact = true)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))

        // Apply (one step), undone with two fingers; Remove (one step).
        oneStep(c, ArrayLabels.APPLY, ArrayLabels.APPLY) { click(ArrayLabels.APPLY, exact = true) }
        assertNull(layer.array)
        assertNull("no array, no sheet", s.tagged(V17Tags.ARRAY_SHEET))
        assertTrue(has(NO_ARRAY_HINT, exact = true))
        ui.twoFingerUndo()
        assertNotNull("undo brings the live array back", layer.array)
        if (s.tagged(V17Tags.ARRAY_SHEET) == null) click(SETTINGS, exact = true)
        oneStep(c, ArrayLabels.REMOVE, ArrayLabels.REMOVE) { click(ArrayLabels.REMOVE, exact = true) }
        assertNull(layer.array)
        Smoke.assertQuiet(c, "four modes")
    }

    private fun textApply() {
        val s = screen()
        val c = s.c
        val item = TextItem("Hey", spec = TextSpec(sizePx = 40f, color = 0xFF000000.toInt()), cx = 120f, cy = 80f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        assertTrue(c.arrayWholeLayer(text))
        settle(4)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        // Asked first; "Cancel" keeps the live array.
        val steps = c.undoManager.undoCount
        click(ArrayLabels.APPLY, exact = true)
        assertTrue("the question is shown", has(ArrayLabels.APPLY_TEXT_ASK, exact = true))
        click("Cancel", exact = true)
        assertFalse(has(ArrayLabels.APPLY_TEXT_ASK, exact = true))
        assertEquals(steps, c.undoManager.undoCount)
        assertNotNull(text.array)
        // "Apply": pixels, one step.
        click(ArrayLabels.APPLY, exact = true)
        oneStep(c, ArrayLabels.APPLY, "the text applied") { click("Apply", exact = true) }
        assertNull(text.array)
        assertNull(text.textData)
        Smoke.assertQuiet(c, "text apply")
    }
}
