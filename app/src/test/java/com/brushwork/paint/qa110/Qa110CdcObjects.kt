package com.brushwork.paint.qa110

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.ObjectColorTargets
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.hexDigits
import com.brushwork.paint.ui.common.CdcLabels110
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * v1.10 (R6, R7; A4 accept; I27): Change Drawing Color on objects in the editor, driven by
 * touches found by label, as the user reaches it: Tools, Filters, the filter's chip.
 * - A vector layer: the Filters sheet's banner says that Change Drawing Color keeps the layer
 *   editable; its panel shows [CdcLabels110.OBJECTS] and no rasterize warning; the color picked
 *   in its Color control shows on the canvas before OK (R7) while the layer is untouched; OK and
 *   ✓ give ONE document step named after the filter, and the layer is still a vector layer
 *   whose objects carry the new colors.
 * - A text layer with a pixel selection: the panel adds [CdcLabels110.SELECTION_WHOLE]; ✓ keeps
 *   a text layer, its text recolored, as one step.
 * Each part ends with [Qa19Ui.assertNoTextOverflow] over the texts new in 1.10 (the panel's
 * one-line header and slider rows are cut on purpose since v1.9).
 */
internal object Qa110CdcObjects {
    private const val W = 400
    private const val H = 300

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    private fun banner(kind: String) = "Applying rasterizes this $kind layer; ${cdc.name} keeps it editable"

    /** Every part at the width of the calling class, at font scale 1 and each of [Qa19Ui.FONT_SCALES]. */
    fun run(h: Qa19Harness, widthDp: Float) {
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val where = "${widthDp.toInt()} dp at font scale $scale"
            h.section("a vector layer, $where") {
                val kit = ObjectTestKit(W, H)
                try {
                    val s = h.editor(document(), fontScale = scale) { c ->
                        c.vectors.addObjects(c.doc.layers[1], listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(180f, 160f, 360f, 260f)), "Add")
                    }
                    assertEquals("the phone's width", widthDp, s.widthDp, 1f)
                    vectorLayer(s, where)
                } finally {
                    kit.close()
                }
            }
            h.section("a text layer with a pixel selection, $where") {
                val kit = ObjectTestKit(W, H)
                try {
                    val s = h.editor(document(), fontScale = scale) { c ->
                        val spec = TextSpec(sizePx = 40f, color = 0xFF202020.toInt(), strokeWidthPx = 2f, strokeColor = 0xFFFFD000.toInt())
                        val text = TextCodec.encodeAll(listOf(TextItem("Brush", spec, cx = 140f, cy = 110f, id = 1)))
                        check(DataRender.apply(c, c.doc.layers[1], LayerData(text = text), "Text", Rect(0, 0, W, H), allowHidden = false))
                        c.setSelection(kit.rectSelection(90, 85, 190, 135), recordUndo = false)
                    }
                    textLayerWithSelection(s, where)
                } finally {
                    kit.close()
                }
            }
        }
    }

    /** A white background and an empty vector layer above it, active. */
    private fun document(): Document {
        val doc = Document("cdc", "CDC", W, H)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(W, H).also { it.eraseColor(-1) })
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return doc
    }

    /** Tools, Filters (whose banner reads [banner] exactly), then the filter's chip: its panel, open. */
    private fun openCdc(c: EditorController, banner: String, where: String): FilterSession {
        Qa19Ui.click("Tools (current")
        Qa19Ui.click("Filters", exact = true)
        SmokeUi.assertPanelShown("Filters")
        assertTrue("$where: the banner \"$banner\"", Qa19Ui.has(banner, exact = true))
        Qa19Ui.assertNoTextOverflow("the Filters sheet, $where", except = { it != banner })
        Qa19Ui.click(cdc.name, exact = true)
        val s = requireNotNull(c.filterSession) { "$where: the panel opened" }
        assertEquals(cdc.id, s.filter.id)
        s.debounceMs = 0L
        await(s, where)
        return s
    }

    private fun await(s: FilterSession, where: String) = waitFor("$where: the preview") { s.hasPreview && !s.isRendering && !s.previewStale }

    /**
     * Runs the UI until [done], at most 2,000 rounds of a 20 ms frame and a 5 ms pause for the
     * background render (about 10 s; bounded by rounds, so this helper reads no clock).
     */
    private fun waitFor(what: String, done: () -> Boolean) {
        var rounds = 0
        while (!done()) {
            if (++rounds > 2000) throw AssertionError("not reached: $what")
            Qa19Ui.settle(steps = 1, stepMs = 20)
            Thread.sleep(5)
        }
        Qa19Ui.settle()
    }

    /** What the canvas shows for the filtered layer. */
    private fun shown(c: EditorController): IntArray {
        val b = BitmapUtils.createLayerBitmap(W, H)
        assertTrue("the canvas shows the preview", c.renderOverride!!.drawContent(Canvas(b)))
        return IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H); b.recycle() }
    }

    private fun assertNear(where: String, expected: Int, actual: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            assertTrue("$where: %08X for %08X".format(actual, expected), abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) <= 2)
        }
    }

    /** The texts of 1.10 the panel shows. */
    private val mine = setOf(CdcLabels110.OBJECTS, CdcLabels110.SELECTION_WHOLE)

    private fun vectorLayer(s: ChromeScreen, where: String) {
        val c = s.c
        val layer = c.doc.layers[1]
        val before = requireNotNull(layer.vector)
        val steps = c.undoManager.undoCount
        val session = openCdc(c, banner("vector"), where)
        assertTrue("$where: object mode", session.objectMode)
        assertTrue("$where: the subtitle", Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
        assertFalse("$where: no rasterize warning", Qa19Ui.has(" · applying rasterizes it"))
        assertFalse(Qa19Ui.has(CdcLabels110.SELECTION_WHOLE, exact = true))
        Qa19Ui.assertNoTextOverflow("the panel, $where", except = { it !in mine })

        // R7: the color being picked shows on the canvas before OK; the layer is untouched.
        val opening = session.values.color("color")
        val fill = 0xFFE04020.toInt()
        val inside = 80 * W + 90
        Qa19Ui.click("Choose color")
        assertTrue("$where: the picker", Qa19Ui.has("OK", exact = true))
        RobolectricUi.textFields().first { it.text == hexDigits(opening, true) }.type("FF00A040")
        Qa19Ui.settle()
        val green = 0xFF00A040.toInt()
        waitFor("$where: the pick previewed") { session.values.color("color") == green }
        await(session, where)
        assertTrue("$where: still picking", Qa19Ui.has("OK", exact = true))
        assertNear("$where: the box on the canvas, before OK", cdc.colorMapper(session.values)!!(fill), shown(c)[inside])
        assertNotEquals(cdc.colorMapper(session.values.copy().set("color", opening))!!(fill), shown(c)[inside])
        assertSame("$where: the layer is untouched", before, layer.vector)
        assertEquals(steps, c.undoManager.undoCount)
        Qa19Ui.assertNoTextOverflow("the picker over the panel, $where", except = { it !in mine })

        Qa19Ui.click("OK", exact = true)
        assertEquals("$where: OK is one panel step", 1, session.history.depth)
        val f = cdc.colorMapper(session.values)!!
        Qa19Ui.click("Apply filter")
        waitFor("$where: applied") { session.isClosed }
        assertEquals("$where: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.undoLabel)
        assertTrue("$where: still a vector layer", layer.isVectorLayer)
        assertEquals(ObjectColorSlots.map(before, null, f), layer.vector)
        Smoke.assertQuiet(c, "the vector layer, $where")
    }

    private fun textLayerWithSelection(s: ChromeScreen, where: String) {
        val c = s.c
        val layer = c.doc.layers[1]
        assertTrue(layer.isTextLayer)
        val text = layer.textData!!
        val pixels = IntArray(W * H).also { layer.bitmap.getPixels(it, 0, W, 0, 0, W, H) }
        val steps = c.undoManager.undoCount
        val session = openCdc(c, banner("text"), where)
        assertTrue(session.objectMode)
        assertEquals(ObjectColorTargets.Rule.SELECTION, session.objectRule)
        assertTrue("$where: the subtitle", Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
        assertTrue("$where: the second line", Qa19Ui.has(CdcLabels110.SELECTION_WHOLE, exact = true))
        assertFalse(Qa19Ui.has(" · applying rasterizes it"))
        Qa19Ui.assertNoTextOverflow("the panel with a selection, $where", except = { it !in mine })
        assertArrayEquals("$where: the layer is untouched while previewing", pixels, IntArray(W * H).also { layer.bitmap.getPixels(it, 0, W, 0, 0, W, H) })

        val f = cdc.colorMapper(session.values)!!
        Qa19Ui.click("Apply filter")
        waitFor("$where: applied") { session.isClosed }
        assertEquals("$where: one step", steps + 1, c.undoManager.undoCount)
        assertTrue("$where: still a text layer", layer.isTextLayer)
        val after = TextCodec.decodeAll(layer.textData!!)
        val orig = TextCodec.decodeAll(text)
        assertEquals(f(orig[0].spec.color), after[0].spec.color)
        assertEquals(f(orig[0].spec.strokeColor), after[0].spec.strokeColor)
        Smoke.assertQuiet(c, "the text layer, $where")
    }
}
