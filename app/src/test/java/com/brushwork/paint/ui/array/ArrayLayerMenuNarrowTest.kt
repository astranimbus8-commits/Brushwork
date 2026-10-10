package com.brushwork.paint.ui.array

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.LayerWalk
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
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 3, §3.3 a; area E review) on the user's 392 dp phone, by label: the layer window's
 * ⋮ "Array…" refuses a plain raster layer without a selection with its message and arrays a
 * whole text layer in place (one step "Array"; the Array tool and its sheet open); ⋮ "Edit array"
 * from another tool opens the Array tool and its sheet; ⋮ "Remove array" is one step that the
 * window's Undo takes back; ⋮ "Apply array" on a text array asks "Apply turns the text into
 * pixels" first; and the vector object bar's "Array from objects", scrolled to by a finger, moves
 * the selected object to a new array layer directly above. One test: Compose's frame clock
 * serves only the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.array.arraymenusandbox"])
class ArrayLayerMenuNarrowTest {
    private lateinit var h: ChromeHarness

    @Test
    fun theArrayEntriesOfTheLayerMenuAndTheObjectBarAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("layer ⋮: Array…, Edit array, Remove array, Apply array") { layerMenu() }
        h.section("the object bar's Array from objects") { objects() }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    private fun screen(): ChromeScreen = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))

    private fun arrayTool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /** Runs [block] and asserts it recorded ONE step [label]. */
    private fun oneStep(c: EditorController, label: String, what: String, block: () -> Unit) {
        val steps = c.undoManager.undoCount
        block()
        settle(4)
        assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(what, label, c.undoManager.undoLabel)
    }

    /** The active layer's ⋮ in the layer window, then [entry]. */
    private fun fromMenu(s: ChromeScreen, entry: String) {
        settle(4)
        LayerWalk.open(s)
        click(LayerLabels.MORE, exact = true)
        assertTrue("⋮ shows \"$entry\": ${SmokeUi.shown().take(60)}", has(entry, exact = true))
        click(entry, exact = true)
        settle(4)
    }

    private fun layerMenu() {
        val s = screen()
        val c = s.c
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        val item = TextItem("Hey", spec = TextSpec(sizePx = 40f, color = 0xFF000000.toInt()), cx = 120f, cy = 80f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        settle(4)

        // A plain raster layer without a selection: told what to do, nothing recorded.
        c.selectLayer(c.doc.layers[1])
        val steps = c.undoManager.undoCount
        fromMenu(s, ArrayLabels.OPEN)
        assertTrue("the refusal is shown", c.message == ArrayLabels.PLAIN_REFUSAL || has(ArrayLabels.PLAIN_REFUSAL, exact = true))
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(c.doc.layers[1].array)

        // The text layer as a whole, in place.
        c.selectLayer(text)
        val layers = c.doc.layers.size
        oneStep(c, ArrayLabels.BUTTON, "⋮ Array…") { fromMenu(s, ArrayLabels.OPEN) }
        assertEquals("in place", layers, c.doc.layers.size)
        assertNotNull(text.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertTrue("the sheet opens", arrayTool(c).sheetOpen)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        click("Close", exact = true)

        // "Edit array" from another tool.
        c.selectTool(ToolId.BRUSH)
        settle(4)
        fromMenu(s, ArrayLabels.EDIT)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertSame(text, c.activeLayer)
        assertTrue("the sheet opens", arrayTool(c).sheetOpen)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        click("Close", exact = true)

        // "Remove array": one step, taken back by the window's Undo.
        oneStep(c, ArrayLabels.REMOVE, "⋮ Remove array") { fromMenu(s, ArrayLabels.REMOVE) }
        assertNull(text.array)
        assertNotNull("the text stays", text.textData)
        settle(4)
        LayerWalk.open(s)
        Finger.tap(s, "Undo")
        settle(4)
        assertNotNull("Undo brings the live array back", text.array)

        // "Apply array" on a text array asks first.
        val before = c.undoManager.undoCount
        fromMenu(s, ArrayLabels.APPLY)
        assertTrue("the question is shown", has(ArrayLabels.APPLY_TEXT_ASK, exact = true))
        assertEquals(before, c.undoManager.undoCount)
        oneStep(c, ArrayLabels.APPLY, "the text applied") { click("Apply", exact = true) }
        assertNull(text.array)
        assertNull(text.textData)
        Smoke.assertQuiet(c, "layer menu")
    }

    private fun objects() {
        val s = screen()
        val ui = Qa16Ui(s)
        val c = s.c
        fun box(l: Float, t: Float) = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(l + 30f, t, true), VAnchor(l + 30f, t + 30f, true), VAnchor(l, t + 30f, true)), closed = true)),
            fill = VPaint.Solid(0xFF2244CC.toInt()),
        )
        val vl = Layer(c.doc.newLayerId(), "Boxes", BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height))
        vl.vector = VectorContent.EMPTY.plus(listOf(box(40f, 200f), box(150f, 120f))).first
        assertTrue(c.structure.insert(vl, label = "Test"))
        c.selectLayer(vl)
        val first = vl.vector!!.objects.first().id
        c.vectors.setSelection(vl, setOf(first))
        settle(4)
        ui.reach(ArrayLabels.FROM_OBJECTS)
        oneStep(c, ArrayLabels.BUTTON, ArrayLabels.FROM_OBJECTS) { click(ArrayLabels.FROM_OBJECTS, exact = true) }
        val made = c.activeLayer
        assertEquals("directly above the source", c.doc.indexOf(vl) + 1, c.doc.indexOf(made))
        assertEquals(listOf(first), made.vector!!.objects.map { it.id })
        assertEquals(1, vl.vector!!.objects.size)
        assertNotNull(made.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        Smoke.assertQuiet(c, "array from objects")
    }
}
