package com.brushwork.paint.qa110

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.LayerWalk
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 QA (R4, UQ4, A3; I27): the array behind its layer from each of its three entries, at 392
 * and 360 dp and at each font scale, by the labels the user sees:
 * - The selection bar's [ArrayLabels110.BEHIND] (tag [V110Tags.SEL_ARRAY_BEHIND]) on a raster
 *   selection: the selected pixels' copies on a new layer directly below the source, ONE step
 *   [ArrayLabels110.ARRAY_BEHIND_STEP]; the layer holds the copies only (no source edit in the
 *   sheet, its remove item [ArrayLabels110.REMOVE_WITH_LAYER]) and the source keeps its pixels.
 *   The sheet's ✕ ("Close") leaves the tool's layer: the source is selected again.
 * - The vector object bar's [ArrayLabels110.BEHIND] (its More row) on one selected box: that box's
 *   copies, with its id, on a new layer directly below, ONE step; the vector layer keeps both
 *   boxes. Another tool picked in the tool menu leaves: the vector layer is selected again.
 * - The layer window's ⋮ [ArrayLabels110.LAYER_MENU] on a text layer: the text's copies directly
 *   below, ONE step, the Array tool and its sheet open; "Close" selects the text again.
 * Each screen's texts stay inside their boxes ([Qa19Ui.textOverflows]), but for the lead's
 * raster selection bar and layer window, whose own words are cut at 1.3 and 2.0 already; the
 * bar's Behind is cut only where its v1.7 neighbor "Array" is cut too. Copy 0 is not
 * drawn on a copies-only layer (`CopiesOnlyDrawTest`); its look and costs are other tests'.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.arraybehindsandbox"])
class Qa110ArrayBehindUiTest {

    @Test
    fun theArrayBehindFromItsThreeEntries() = Qa19Ui.uiTest { h ->
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val dp = if (width == Qa19Ui.Q360) 360 else 392
            val where = "at $dp dp and font scale $scale"
            h.section("$where: the selection bar") {
                RuntimeEnvironment.setQualifiers(width)
                selectionBar(screen(h, scale, dp, where), where)
            }
            h.section("$where: the object bar") {
                RuntimeEnvironment.setQualifiers(width)
                objectBar(screen(h, scale, dp, where), where)
            }
            h.section("$where: the layer menu") {
                RuntimeEnvironment.setQualifiers(width)
                layerMenu(screen(h, scale, dp, where), where)
            }
        }
    }

    /** A 400 x 300 page with a white bottom layer and an empty layer 1, at [scale]; snapping off. */
    private fun screen(h: com.brushwork.paint.qa19.Qa19Harness, scale: Float, dp: Int, where: String): ChromeScreen {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), fontScale = scale) { it.snapping.enabled = false }
        assertEquals("$where: the phone's width", dp, s.activity.resources.configuration.screenWidthDp)
        return s
    }

    private fun arrayTool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /** Renders and the tool's work landed, the screen settled. */
    private fun landed(c: EditorController, where: String) {
        assertTrue("$where: the work landed", Smoke.pumpUntil { Qa19Ui.settle(1); !c.vectors.isRendering && !c.arrayRenders.isPending && c.busyMessage == null })
        Qa19Ui.settle()
    }

    /** Runs [block] and asserts it recorded ONE step [ArrayLabels110.ARRAY_BEHIND_STEP]; the new layer. */
    private fun behindStep(c: EditorController, where: String, block: () -> Unit): Layer {
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        block()
        landed(c, where)
        assertEquals("$where: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(where, ArrayLabels110.ARRAY_BEHIND_STEP, c.undoManager.undoLabel)
        assertEquals("$where: one new layer", layers + 1, c.doc.layers.size)
        val made = c.activeLayer
        assertTrue("$where: the new layer holds the copies only", ArrayBehind.isCopiesOnly(made))
        assertEquals("$where: in the Array tool", ToolId.ARRAY, c.activeToolId)
        return made
    }

    /** The open Array sheet of a copies-only layer: no source edit, the remove item names the layer. */
    private fun copiesOnlySheet(s: ChromeScreen, where: String) {
        assertNotNull("$where: the sheet opens", s.tagged(V17Tags.ARRAY_SHEET))
        assertTrue("$where: the sheet is open", arrayTool(s.c).sheetOpen)
        assertFalse("$where: no source edit on a copies-only layer", Qa19Ui.has(ArrayLabels.EDIT_SOURCE, exact = true))
        assertTrue("$where: the remove item names the layer", Qa19Ui.has(ArrayLabels110.REMOVE_WITH_LAYER, exact = true))
    }

    /** The sheet's ✕: the tool's layer is left, [source] selected again. */
    private fun closeSelects(s: ChromeScreen, source: Layer, where: String) {
        Qa19Ui.click("Close", exact = true)
        landed(s.c, where)
        assertSame("$where: closing the sheet selects the source again", source, s.c.activeLayer)
    }

    private fun selectionBar(s: ChromeScreen, where: String) {
        val c = s.c
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = 0xFF2244CC.toInt() })
        c.selectLayer(src)
        c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
        Qa19Ui.settle()
        val before = IntArray(400 * 300).also { src.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        Qa16Ui(s).reach(ArrayLabels110.BEHIND)
        assertNotNull("$where: the item carries its tag", s.tagged(V110Tags.SEL_ARRAY_BEHIND))
        noOverflow("$where, the selection bar", ChromeTags.SELECTION_BAR)
        behindNoWorseThanArray(where)
        val made = behindStep(c, "$where, the selection bar's Behind") { Qa19Ui.click(ArrayLabels110.BEHIND, exact = true) }
        assertEquals("$where: directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertNotNull("$where: the selection stays", c.selection)
        val after = IntArray(400 * 300).also { src.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        assertTrue("$where: the source keeps its pixels", before.contentEquals(after))
        assertTrue("$where: the copies are drawn", hasInk(made))
        copiesOnlySheet(s, where)
        noOverflow("$where, the sheet after the selection bar's Behind", ChromeTags.SELECTION_BAR)
        closeSelects(s, src, "$where, the selection bar")
        Smoke.assertQuiet(c, "$where, the selection bar")
        ArrayDraw.clearCaches()
    }

    private fun objectBar(s: ChromeScreen, where: String) {
        val c = s.c
        val v = c.addVectorLayer()!!
        val boxes = listOf(ArrayLookKit.box(20f, 30f, 60f, 70f), ArrayLookKit.box(200f, 30f, 240f, 70f, fill = ArrayLookKit.RED))
        c.vectors.update(v, VectorContent.EMPTY.plus(boxes).first, "Add")
        landed(c, where)
        c.selectLayer(v)
        val first = v.vector!!.objects.first().id
        c.vectors.setSelection(v, setOf(first))
        Qa19Ui.settle()
        // The object bar's More row holds Behind (after Array); opened by its toggle unless already open.
        Qa19Ui.openObjectRow()
        Qa16Ui(s).reach(ArrayLabels110.BEHIND)
        Qa19Ui.assertNoTextOverflow("$where, the object bar")
        val made = behindStep(c, "$where, the object bar's Behind") { Qa19Ui.click(ArrayLabels110.BEHIND, exact = true) }
        assertEquals("$where: directly below the vector layer", c.doc.indexOf(v) - 1, c.doc.indexOf(made))
        assertEquals("$where: the box's copies, with its id", listOf(first), made.vector!!.objects.map { it.id })
        assertEquals("$where: the vector layer keeps both boxes", 2, v.vector!!.objects.size)
        copiesOnlySheet(s, where)
        Qa19Ui.assertNoTextOverflow("$where, the sheet after the object bar's Behind")

        // Another tool, picked in the tool menu: the vector layer again.
        Qa16Ui(s).tool(ToolId.BRUSH.label)
        landed(c, where)
        assertEquals("$where: the Brush", ToolId.BRUSH, c.activeToolId)
        assertSame("$where: another tool selects the vector layer again", v, c.activeLayer)
        Smoke.assertQuiet(c, "$where, the object bar")
        ArrayDraw.clearCaches()
    }

    private fun layerMenu(s: ChromeScreen, where: String) {
        val c = s.c
        val item = TextItem("Hey", spec = TextSpec(sizePx = 40f, color = 0xFF000000.toInt()), cx = 120f, cy = 80f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        c.selectLayer(text)
        Qa19Ui.settle()
        LayerWalk.open(s)
        Qa19Ui.click(LayerLabels.MORE, exact = true)
        assertTrue("$where: ⋮ shows \"${ArrayLabels110.LAYER_MENU}\": ${Qa19Ui.shown().take(60)}", Qa19Ui.has(ArrayLabels110.LAYER_MENU, exact = true))
        noOverflow("$where, the layer menu", ChromeTags.LAYER_WINDOW)
        val made = behindStep(c, "$where, the layer menu's item") { Qa19Ui.click(ArrayLabels110.LAYER_MENU, exact = true) }
        assertEquals("$where: directly below the text", c.doc.indexOf(text) - 1, c.doc.indexOf(made))
        assertEquals("$where: the text's copies", text.textData, made.textData)
        copiesOnlySheet(s, where)
        noOverflow("$where, the sheet after the layer menu's item", ChromeTags.LAYER_WINDOW)
        closeSelects(s, text, "$where, the layer menu")
        Smoke.assertQuiet(c, "$where, the layer menu")
        ArrayDraw.clearCaches()
    }

    /**
     * I27 for what this test shows: no text on screen overflows its box, except inside the lead's
     * [foreign] chrome (the raster selection bar, the layer window), whose own items are cut at
     * font scale 1.3 and 2.0 already ("Deselect", "Content aware"; the window's layer names) and
     * are checked by their own tests. The Behind item in that bar: [behindNoWorseThanArray].
     */
    private fun noOverflow(where: String, vararg foreign: String) {
        val bad = Qa19Ui.textOverflows { e -> foreign.none { StripTestKit.under(e.node, it) } }
        assertTrue("texts overflow their box $where:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * The raster selection bar's [ArrayLabels110.BEHIND] is a cell of the lead's bar like v1.7's
     * [ArrayLabels.BUTTON] next to it: its word is never cut where that one's is whole (at font
     * scale 2.0 the bar cuts every cell's word, its own included).
     */
    private fun behindNoWorseThanArray(where: String) {
        val bar = Qa19Ui.textOverflows { e -> StripTestKit.under(e.node, ChromeTags.SELECTION_BAR) }
        fun cut(label: String) = bar.any { it.startsWith("\"$label\" ") }
        assertTrue("$where: \"${ArrayLabels110.BEHIND}\" cut while \"${ArrayLabels.BUTTON}\" is whole:\n" + bar.joinToString("\n"), !cut(ArrayLabels110.BEHIND) || cut(ArrayLabels.BUTTON))
    }

    /** Whether [layer] shows any ink (alpha above 8) on its bitmap. */
    private fun hasInk(layer: Layer): Boolean {
        val px = IntArray(layer.bitmap.width * layer.bitmap.height)
        layer.bitmap.getPixels(px, 0, layer.bitmap.width, 0, 0, layer.bitmap.width, layer.bitmap.height)
        return px.any { it ushr 24 > 8 }
    }
}
