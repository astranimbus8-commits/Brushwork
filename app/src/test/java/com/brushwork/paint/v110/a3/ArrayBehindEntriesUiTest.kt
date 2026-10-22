package com.brushwork.paint.v110.a3

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
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
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
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
 * v1.10 (R4, UQ4, UQ11, A3 step 3) on the user's 392 dp phone, by label: the layer window's ⋮
 * [ArrayLabels110.LAYER_MENU] on a text layer puts its copies on a new layer directly below (ONE
 * step [ArrayLabels110.ARRAY_BEHIND_STEP]; the Array tool and its sheet open); that layer's ⋮
 * offers [ArrayLabels110.REMOVE_WITH_LAYER] (not "Remove array"), which deletes it in one step;
 * and the selection bar's [ArrayLabels110.BEHIND] (tag `v110.sel.arrayBehind`) copies the
 * selected pixels behind, the selection staying, with no source edit in the sheet. One test:
 * Compose's frame clock serves only the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a3.behindentriessandbox"])
class ArrayBehindEntriesUiTest {
    private lateinit var h: ChromeHarness

    @Test
    fun theBehindEntriesOfTheLayerMenuAndTheSelectionBarAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("layer menu: the array behind a text layer, then its remove item") { layerMenu() }
        h.section("the selection bar's Behind") { selectionBar() }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    private fun screen(): ChromeScreen = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))

    private fun arrayTool(c: EditorController) = c.tools.getValue(ToolId.ARRAY) as ArrayTool

    /** Runs [block] and asserts it recorded ONE step [label]. */
    private fun oneStep(c: EditorController, label: String, block: () -> Unit) {
        val steps = c.undoManager.undoCount
        block()
        settle(4)
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
    }

    /** The active layer's ⋮ in the layer window, then [entry]. */
    private fun fromMenu(s: ChromeScreen, entry: String, absent: String? = null) {
        settle(4)
        LayerWalk.open(s)
        click(LayerLabels.MORE, exact = true)
        assertTrue("⋮ shows \"$entry\": ${SmokeUi.shown().take(60)}", has(entry, exact = true))
        if (absent != null) assertFalse("⋮ leaves out \"$absent\"", has(absent, exact = true))
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
        c.selectLayer(text)
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { fromMenu(s, ArrayLabels110.LAYER_MENU) }
        val made = c.activeLayer
        assertEquals("directly below the text", c.doc.indexOf(text) - 1, c.doc.indexOf(made))
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals(text.textData, made.textData)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertTrue("the sheet opens", arrayTool(c).sheetOpen)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        assertTrue("the sheet's remove item names the layer", has(ArrayLabels110.REMOVE_WITH_LAYER, exact = true))
        click("Close", exact = true)

        // The copies-only layer's own ⋮, from another tool.
        c.selectTool(ToolId.BRUSH)
        c.selectLayer(made)
        oneStep(c, ArrayLabels110.REMOVE_WITH_LAYER) { fromMenu(s, ArrayLabels110.REMOVE_WITH_LAYER, absent = ArrayLabels.REMOVE) }
        assertEquals("the layer is gone", -1, c.doc.indexOf(made))
        assertNotNull("the text stays", text.textData)
        Smoke.assertQuiet(c, "layer menu")
    }

    private fun selectionBar() {
        val s = screen()
        val c = s.c
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = 0xFF2244CC.toInt() })
        c.selectLayer(src)
        c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
        settle(4)
        Qa16Ui(s).reach(ArrayLabels110.BEHIND)
        assertNotNull("the item carries its tag", s.tagged(V110Tags.SEL_ARRAY_BEHIND))
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { click(ArrayLabels110.BEHIND, exact = true) }
        val made = c.activeLayer
        assertEquals("directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertNotNull("the selection stays", c.selection)
        assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        assertFalse("no source edit on a copies-only layer", has(ArrayLabels.EDIT_SOURCE, exact = true))
        assertNull("not offered on the copies-only layer", s.tagged(V110Tags.SEL_ARRAY_BEHIND))
        Smoke.assertQuiet(c, "selection bar")
    }
}
