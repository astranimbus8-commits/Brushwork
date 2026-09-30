package com.brushwork.paint.ui.layers

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Text layers in the layers window: "T" badge, "Edit text" in the ⋮ menu, double-tap on the row. */
@RunWith(RobolectricTestRunner::class)
class TextLayerPanelRobolectricTest {

    @Test
    fun textLayersCanBeEditedFromTheLayersWindow() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 1))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(200f, 150f)
        tool.setText("Title")
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val textLayer = c.activeLayer
        c.selectTool(ToolId.BRUSH)

        // Row snapshot: only the text layer is marked.
        val rows = LayerRowModel.build(c.doc, c.doc.layers.asReversed().toList())
        assertEquals(listOf(true, false), rows.map { it.isText })

        var open by mutableStateOf(true)
        activity.setContent { BrushworkTheme { if (open) LayersPanel(c, { open = false }, onImportPicture = {}) } }
        SmokeUi.settle()
        assertTrue("the text layer has a T badge", SmokeUi.has("Text layer", exact = true))

        // ⋮ menu -> Edit text: text tool, the text loaded, the editor open.
        SmokeUi.click("More layer actions")
        SmokeUi.click("Edit text", exact = true)
        assertEquals(ToolId.TEXT, c.activeToolId)
        assertSame(textLayer, tool.editingLayer)
        assertTrue(tool.editorOpen)
        tool.cancelEditor()
        tool.discard()
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()
        Smoke.assertQuiet(c, "menu edit")

        // Double-tap on the row does the same.
        val row = SmokeUi.find("Text: Title", exact = true) ?: throw AssertionError("no text row; shown: ${SmokeUi.shown()}")
        val b = row.bounds
        val touch = Smoke.Touch(row.window)
        touch.tap(b.center.x, b.center.y)
        touch.idle(40)
        touch.tap(b.center.x, b.center.y)
        touch.idle(400)
        SmokeUi.settle()
        assertEquals(ToolId.TEXT, c.activeToolId)
        assertSame(textLayer, tool.editingLayer)
        assertTrue(tool.editorOpen)
        tool.setText("New title")
        tool.confirmEditor()
        tool.commit()
        SmokeUi.settle()
        assertEquals("New title", TextCodec.decode(textLayer.textData)!!.text)
        assertTrue("the row follows the new name", SmokeUi.has("Text: New title", exact = true))

        // A plain layer: a single tap selects it, no "Edit text" in its menu.
        c.selectTool(ToolId.BRUSH)
        SmokeUi.click("Layer 1", exact = true)
        assertSame(c.doc.layers[0], c.activeLayer)
        SmokeUi.click("More layer actions")
        assertFalse(SmokeUi.has("Edit text", exact = true))

        // A painted-over text layer loses the badge (and the menu entry).
        c.editWholeLayer(textLayer, "Scribble") { it.setPixel(1, 1, -1) }
        assertNull(textLayer.textData)
        SmokeUi.settle()
        assertFalse(SmokeUi.has("Text layer", exact = true))
        Smoke.assertQuiet(c, "rasterized")
    }
}
