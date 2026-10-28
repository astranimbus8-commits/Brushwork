package com.brushwork.paint.ui.layers

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.OptionsStripPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.PillSharpCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text layers in the layers window: "T" badge, "Edit text" in the ⋮ menu, double-tap on the row.
 * Then (v1.4) the shape strip's points and shape layers in the layers window.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.textpanelsandbox"])
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
        // (The badge names its row, I10: "Layer 2: text layer".)
        val textBadge = LayerLabels.badge(c.doc.indexOf(textLayer) + 1, LayerLabels.TEXT_BADGE)
        assertEquals("Layer 2: text layer", textBadge)
        assertTrue("the text layer has a T badge", SmokeUi.has(textBadge, exact = true))

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

        // A single tap on a text row selects it at once (no waiting for a second tap)...
        c.selectLayer(c.doc.layers[0])
        SmokeUi.settle()
        val row = SmokeUi.find("Text: Title", exact = true) ?: throw AssertionError("no text row; shown: ${SmokeUi.shown()}")
        val b = row.bounds
        val touch = Smoke.Touch(row.window)
        touch.tap(b.center.x, b.center.y)
        assertSame("selected right away", textLayer, c.activeLayer)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        // ...and a second tap soon after (a double tap) edits its text.
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
        assertFalse(SmokeUi.has(textBadge, exact = true))
        Smoke.assertQuiet(c, "rasterized")

        // v1.4 shape layers, in this same test: a Compose test needs a sandbox of its own and
        // every sandbox costs test heap.
        shapeLayersAndPoints(activity, c)
    }

    /**
     * The shape strip's "Points" chip with the selected point's actions, then shape layers in the
     * layers window: shape badge, "Edit shape" in the ⋮ menu, double-tap on the row. v1.10 G2 (V4):
     * the Shape strip as the app shows it (the v1.5 strip function is gone): "Points" on row 1, the
     * point actions on the More row, and Sharp / Smooth on the X / Y pill's cell (v1.10 R12),
     * composed under the strip.
     */
    private fun shapeLayersAndPoints(activity: ComponentActivity, c: EditorController) {
        c.selectLayer(c.doc.layers[0])
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool

        // ------------------------------------------------ the strip: points of the pending shape
        var strip by mutableStateOf(true)
        var panel by mutableStateOf(false)
        activity.setContent {
            BrushworkTheme {
                if (strip) Column(Modifier.fillMaxWidth()) {
                    OptionsStripPanel(c, Modifier.fillMaxWidth())
                    tool.pointCorners?.let { PillSharpCell(it) }
                }
                if (panel) LayersPanel(c, { panel = false }, onImportPicture = {})
            }
        }
        SmokeUi.settle()
        assertFalse("no Points without a shape", SmokeUi.has("Points", exact = true))
        assertTrue(StripTestKit.openMoreRow())
        assertTrue(SmokeUi.has("Editable", exact = true))
        StripTestKit.closeMoreRow()
        assertTrue(tool.ensurePending())
        tool.place(ShapeBox(200f, 150f, 120f, 80f))
        SmokeUi.settle()
        SmokeUi.click("Points", exact = true)
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.points!!.size)
        tool.selectPoint(1)
        SmokeUi.settle()
        SmokeUi.click("Smooth", exact = true)
        assertTrue(tool.points!![1].smooth)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Sharp corner", exact = true))
        assertTrue(StripTestKit.openMoreRow())
        SmokeUi.click("Delete point", exact = true)
        assertEquals(3, tool.points!!.size)
        SmokeUi.settle()
        // v1.8 (F4, V9): the top row's Undo (not shown in this rig: called as it calls it) takes back the point edit.
        HistoryLabels.performUndo(c)
        SmokeUi.settle()
        assertEquals(4, tool.points!!.size)
        SmokeUi.settle()
        if (!StripTestKit.moreRowShown()) assertTrue(StripTestKit.openMoreRow())
        SmokeUi.click("Reset shape", exact = true)
        assertNull(tool.points)
        StripTestKit.closeMoreRow()
        tool.commit()
        val shapeLayer = c.activeLayer
        assertTrue(shapeLayer.isShapeLayer)
        c.selectTool(ToolId.BRUSH)
        Smoke.assertQuiet(c, "shape strip")

        // ------------------------------------------------ the layers window
        val rows = LayerRowModel.build(c.doc, c.doc.layers.asReversed().toList())
        assertEquals(c.doc.layers.asReversed().map { it === shapeLayer }, rows.map { it.isShape })
        strip = false
        panel = true
        SmokeUi.settle()
        val shapeBadge = LayerLabels.badge(c.doc.indexOf(shapeLayer) + 1, LayerLabels.SHAPE_BADGE)
        assertTrue("the shape layer has a badge \"$shapeBadge\"", SmokeUi.has(shapeBadge, exact = true))

        // ⋮ menu -> Edit shape: shape tool, the shape opened.
        SmokeUi.click("More layer actions")
        SmokeUi.click("Edit shape", exact = true)
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertSame(shapeLayer, tool.editingLayer)
        tool.discard()
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()
        Smoke.assertQuiet(c, "menu edit shape")

        // A single tap selects the row at once, a double tap opens the shape.
        c.selectLayer(c.doc.layers[0])
        SmokeUi.settle()
        val row = SmokeUi.find(shapeLayer.name, exact = true) ?: throw AssertionError("no shape row; shown: ${SmokeUi.shown()}")
        val b = row.bounds
        val touch = Smoke.Touch(row.window)
        touch.tap(b.center.x, b.center.y)
        assertSame("selected right away", shapeLayer, c.activeLayer)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        touch.idle(40)
        touch.tap(b.center.x, b.center.y)
        touch.idle(400)
        SmokeUi.settle()
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertSame(shapeLayer, tool.editingLayer)
        tool.discard()

        // A plain layer has no "Edit shape".
        c.selectTool(ToolId.BRUSH)
        SmokeUi.click("Layer 1", exact = true)
        SmokeUi.click("More layer actions")
        assertFalse(SmokeUi.has("Edit shape", exact = true))

        // Painted over: the badge goes.
        c.editWholeLayer(shapeLayer, "Scribble") { it.setPixel(1, 1, -1) }
        assertNull(shapeLayer.shapeData)
        SmokeUi.settle()
        assertFalse(SmokeUi.has(shapeBadge, exact = true))
        Smoke.assertQuiet(c, "shape rasterized")
    }
}
