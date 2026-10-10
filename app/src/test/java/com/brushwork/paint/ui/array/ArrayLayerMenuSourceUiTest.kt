package com.brushwork.paint.ui.array

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.LayerWalk
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 3, §3.3 a; E's merge) on the user's 392 dp phone, by label: a raster array's layer ⋮
 * has "Edit source pixels" (one step; the last painting tool takes over) and, while the source is
 * being edited, "Finish source edit" (one step) instead; ⋮ "Edit array" reopens the Array sheet
 * even when the Array tool is already current on that layer with its sheet closed. One test:
 * Compose's frame clock serves only the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.array.arraysourcemenusandbox"])
class ArrayLayerMenuSourceUiTest {
    @Test
    fun theLayerMenuEditsARasterArraysSourceAndReopensTheSheet() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("layer ⋮: Edit source pixels, Finish source edit, Edit array") {
            val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
            val c = s.c
            val src = c.doc.layers[1]
            Canvas(src.bitmap).drawRect(60f, 60f, 100f, 100f, Paint().apply { color = 0xFFDD2211.toInt() })
            src.markChanged()
            c.setSelection(Selection.fromPath(Path().apply { addRect(50f, 50f, 110f, 110f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
            assertTrue(c.arrayFromSelection())
            c.setSelection(null, recordUndo = false)
            settle(4)
            val layer = c.activeLayer
            assertNotNull(layer.array?.pixels)
            val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool

            // "Edit array" with the Array tool current on this layer and its sheet closed.
            if (s.tagged(V17Tags.ARRAY_SHEET) != null) click("Close", exact = true)
            settle(4)
            assertFalse(tool.sheetOpen)
            fromMenu(s, ArrayLabels.EDIT)
            assertEquals(ToolId.ARRAY, c.activeToolId)
            assertTrue("the sheet reopens", tool.sheetOpen)
            assertNotNull(s.tagged(V17Tags.ARRAY_SHEET))
            click("Close", exact = true)

            // "Edit source pixels": one step, the brush paints the source.
            c.selectTool(ToolId.BRUSH)
            settle(4)
            oneStep(c, ArrayLabels.EDIT_SOURCE) { fromMenu(s, ArrayLabels.EDIT_SOURCE) }
            assertTrue(layer.array!!.spec.editingSource)
            assertSame(layer, c.activeLayer)
            assertEquals(c.lastPaintTool, c.activeToolId)

            // While editing: "Finish source edit" instead; one step.
            LayerWalk.open(s)
            click(LayerLabels.MORE, exact = true)
            assertFalse("no second \"Edit source pixels\"", has(ArrayLabels.EDIT_SOURCE, exact = true))
            assertTrue(has(ArrayLabels.FINISH_SOURCE, exact = true))
            oneStep(c, ArrayLabels.FINISH_SOURCE) { click(ArrayLabels.FINISH_SOURCE, exact = true) }
            assertFalse(layer.array!!.spec.editingSource)
            assertNotNull("the live array stays", layer.array)
            Smoke.assertQuiet(c, "layer menu source edit")
        }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    /** Runs [block] and asserts it recorded ONE step [label]. */
    private fun oneStep(c: EditorController, label: String, block: () -> Unit) {
        val steps = c.undoManager.undoCount
        block()
        settle(4)
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
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
}
