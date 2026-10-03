package com.brushwork.paint.qa16

import android.graphics.Color
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A finger walks the layer window (§3.7.7) in the editor on the user's phone, over the page with
 * layers of four kinds: every icon of the left column, the right strip, the blend and opacity
 * rows, the rows and the header, tapped with real touch events where it shows (the window lies
 * over the slider rows: its opacity row's − / + must be its own), does what its label says.
 */
internal object LayerWalk {
    fun open(s: ChromeScreen) {
        if (s.tagged(ChromeTags.LAYER_WINDOW) == null) Finger.tap(s, "Open layers (active layer ${s.c.doc.indexOf(s.c.activeLayer) + 1})")
        Smoke.pump(300); settle()
        assertNotNull("the layer window is open", s.tagged(ChromeTags.LAYER_WINDOW))
    }

    fun steps(s: ChromeScreen) = s.c.undoManager.undoCount

    /** Taps [label] in the window; the window stays open and one undo step is recorded; then the top row's Undo takes it back. */
    fun oneStepAndUndo(s: ChromeScreen, label: String, check: () -> Unit) {
        val before = steps(s)
        Finger.tap(s, label)
        assertTrue("$label finishes", Smoke.pumpUntil { settle(1); s.c.busyMessage == null && steps(s) > before })
        assertEquals("$label: one step", before + 1, steps(s))
        check()
        assertNotNull("$label: the window stays open", s.tagged(ChromeTags.LAYER_WINDOW))
        Finger.tap(s, "Undo")
        assertEquals("$label: undone", before, steps(s))
        assertNotNull("Undo leaves the window open", s.tagged(ChromeTags.LAYER_WINDOW))
    }

    /** Brings the row labelled [label] into the list's view by a finger, if it is scrolled out. */
    fun reveal(s: ChromeScreen, label: String) {
        val lw = s.tagged(ChromeTags.LAYER_WINDOW)!!
        for (i in 0 until 6) {
            val r = Finger.control(s, label)
            if (r != null && r.height >= 40f) return
            // Push the list up (rows below come into view), then pull it down (rows above).
            val (a, b) = if (i < 3) (lw.top + 300f) to (lw.top + 120f) else (lw.top + 120f) to (lw.top + 300f)
            Finger.slowDrag(s, (lw.left + 200f) to a, (lw.left + 200f) to b)
        }
        throw AssertionError("\"$label\" can't be scrolled into view")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.layerwalksandbox"])
class LayerWindowFingerWalkTest {
    @Test
    fun everyIconDoesWhatItSays() = Walk.run("layer window") { h ->
        val s = h.editor(IbisDocs.page(), setup = IbisDocs::fourKinds)
        val c = s.c
        assertEquals(5, c.doc.layers.size)
        LayerWalk.open(s)

        // A row: select layer 2 (the red disc) by a finger.
        LayerWalk.reveal(s, LayerLabels.selectRow(2))
        Finger.tap(s, LayerLabels.selectRow(2))
        val layer = c.doc.layers[1]
        assertSame("the row's tap selects it", layer, c.activeLayer)
        val red = 0xFFE02020.toInt()
        assertEquals(red, layer.bitmap.getPixel(150, 135))

        Finger.tap(s, LayerLabels.hide(2))
        assertFalse("the eye hides", layer.visible)
        Finger.tap(s, LayerLabels.show(2))
        assertTrue("…and shows", layer.visible)

        // The blend row.
        Finger.tap(s, LayerLabels.CLIPPING); assertTrue("Clipping", layer.clipping)
        Finger.tap(s, LayerLabels.CLIPPING); assertFalse(layer.clipping)
        Finger.tap(s, LayerLabels.ALPHA_LOCK); assertTrue("Alpha lock", layer.alphaLocked)
        Finger.tap(s, LayerLabels.ALPHA_LOCK); assertFalse(layer.alphaLocked)
        Finger.tap(s, LayerLabels.LOCK); assertTrue("Lock layer", layer.locked)
        Finger.tap(s, LayerLabels.LOCK); assertFalse(layer.locked)
        Finger.tap(s, LayerLabels.BLEND)
        assertTrue("the blend modes drop", SmokeUi.has("Multiply", exact = true))
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)

        // The opacity row (over the slider rows: its − / + are the window's, not the brush's).
        val brushSize = c.presetFor(ToolId.BRUSH)!!.size
        val brushOpacity = c.presetFor(ToolId.BRUSH)!!.opacity
        Finger.tap(s, LayerLabels.LESS_OPACITY)
        assertTrue("Less layer opacity: ${layer.opacity}", layer.opacity < 1f)
        Finger.tap(s, LayerLabels.MORE_OPACITY)
        assertEquals("More layer opacity", 1f, layer.opacity, 1e-4f)
        assertEquals("the brush size stays", brushSize, c.presetFor(ToolId.BRUSH)!!.size, 0f)
        assertEquals("the brush opacity stays", brushOpacity, c.presetFor(ToolId.BRUSH)!!.opacity, 0f)
        Finger.tap(s, LayerLabels.TYPE_OPACITY)
        assertEquals("Type layer opacity asks", 2, SmokeUi.windows().size)
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)

        // The right strip.
        LayerWalk.oneStepAndUndo(s, LayerLabels.FLIP_V) { assertEquals("flipped vertically", 0, Color.alpha(layer.bitmap.getPixel(150, 135))) }
        assertEquals(red, layer.bitmap.getPixel(150, 135))
        LayerWalk.oneStepAndUndo(s, LayerLabels.FLIP_H) {}
        LayerWalk.oneStepAndUndo(s, LayerLabels.CLEAR) { assertEquals("cleared", 0, Color.alpha(layer.bitmap.getPixel(150, 200))) }
        assertEquals(red, layer.bitmap.getPixel(150, 200))
        LayerWalk.oneStepAndUndo(s, LayerLabels.MERGE) { assertEquals(4, c.doc.layers.size) }
        assertEquals(5, c.doc.layers.size)
        c.selectLayer(layer); settle()
        LayerWalk.oneStepAndUndo(s, LayerLabels.DELETE) { assertEquals(4, c.doc.layers.size) }
        assertEquals(5, c.doc.layers.size)
        c.selectLayer(layer); settle()

        Finger.tap(s, LayerLabels.MASK)
        assertTrue("Layer mask opens the mask page", SmokeUi.has("Add gradient mask…", exact = true))
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)

        Finger.tap(s, LayerLabels.MORE)
        assertTrue("⋮ shows the layer menu", SmokeUi.has("Rename…", exact = true))
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)

        // The left column.
        LayerWalk.oneStepAndUndo(s, LayerLabels.ADD) { assertEquals(6, c.doc.layers.size) }
        c.selectLayer(layer); settle()
        LayerWalk.oneStepAndUndo(s, LayerLabels.DUPLICATE) { assertEquals(6, c.doc.layers.size) }
        c.selectLayer(layer); settle()
        LayerWalk.oneStepAndUndo(s, LayerLabels.FLIP_CANVAS_H) {}
        LayerWalk.oneStepAndUndo(s, LayerLabels.FLIP_CANVAS_V) { assertEquals("the canvas flipped", 0, Color.alpha(layer.bitmap.getPixel(150, 135))) }
        c.selectLayer(layer); settle()
        Finger.tap(s, LayerLabels.SPECIAL)
        assertTrue("the special layers drop", SmokeUi.has(LayerLabels.NEW_VECTOR, exact = true))
        SmokeUi.tap(LayerLabels.NEW_VECTOR, exact = true)
        assertTrue("New vector layer", c.activeLayer.isVectorLayer)
        assertEquals(6, c.doc.layers.size)
        Finger.tap(s, "Undo")
        assertEquals(5, c.doc.layers.size)
        c.selectLayer(layer); settle()

        // The transparency squares (view only).
        for (t in TransparencyDisplay.entries.reversed()) {
            Finger.tap(s, LayerLabels.transparency(t))
            assertEquals("the square sets the transparency display", t, c.settings.transparencyDisplay)
        }

        // Panels from the window: Filters for this layer, the Selection Layer row.
        Finger.tap(s, LayerLabels.FILTERS)
        SmokeUi.assertPanelShown("Filters")
        Finger.back()
        LayerWalk.open(s)
        LayerWalk.reveal(s, LayerLabels.SELECTION_ROW)
        Finger.tap(s, LayerLabels.SELECTION_ROW)
        SmokeUi.assertPanelShown("Selection")
        Finger.back()
        LayerWalk.open(s)

        // Import picture asks the system for a picture.
        Finger.tap(s, LayerLabels.IMPORT)
        assertNotNull("Import picture starts the picker", shadowOf(s.activity).nextStartedActivityForResult ?: shadowOf(s.activity).nextStartedActivity)
        LayerWalk.open(s)

        // Transform layer: the Transform tool, the window closes.
        c.selectLayer(layer); settle()
        Finger.tap(s, LayerLabels.TRANSFORM)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertNull("Transform layer closes the window", s.tagged(ChromeTags.LAYER_WINDOW))
        c.selectTool(ToolId.BRUSH); settle()

        // The header's ✕.
        LayerWalk.open(s)
        Finger.tap(s, LayerLabels.CLOSE)
        assertNull("✕ closes the window", s.tagged(ChromeTags.LAYER_WINDOW))
        Smoke.assertQuiet(c, "layer window walk")
    }
}
