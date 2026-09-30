package com.brushwork.paint.tools.text

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The text tool's strip and editor sheet in a real activity, operated through their semantics:
 * "Edit text" for the active text layer, the box presets and fixed width, the vertical style,
 * an emptied text deleting its layer without a question, and the disabled box / vertical
 * options while a path is active.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.editsheetsandbox"])
class TextEditUiRobolectricTest {

    @Test
    fun editingATextLayerThroughTheSheet() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Tap the canvas to add text"))
        assertFalse(SmokeUi.has("Edit text", exact = true))

        tool.startTextAt(300f, 400f)
        tool.setText("Caption")
        tool.updateSpec { it.copy(sizePx = 40f) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        SmokeUi.settle()

        // The active layer is a text layer: the strip offers to edit it.
        SmokeUi.click("Edit text", exact = true)
        assertTrue(tool.editorOpen)
        assertNotNull(tool.editingLayer)
        SmokeUi.assertWindowsLaidOut(2)

        // Box: a preset, then a fixed width.
        SmokeUi.click("Rounded bubble", exact = true)
        assertEquals(1f, tool.item!!.spec.box.roundness, 0f)
        assertTrue(tool.item!!.spec.box.fill)
        SmokeUi.click("Fixed width (lines wrap)", exact = true)
        assertTrue(tool.item!!.spec.box.width > 0f)
        assertTrue(SmokeUi.has("Box width", exact = true))

        // Vertical text: upright letters by default, the manga style and column order as options.
        SmokeUi.click("Vertical text", exact = true)
        assertTrue(tool.item!!.spec.vertical)
        assertEquals(VerticalStyle.UPRIGHT, tool.item!!.spec.verticalStyle)
        SmokeUi.click("Sideways Latin (manga)", exact = true)
        assertEquals(VerticalStyle.MIXED, tool.item!!.spec.verticalStyle)
        SmokeUi.click("Left to right", exact = true)
        assertTrue(tool.item!!.spec.columnsLeftToRight)
        SmokeUi.click("Fixed height (columns wrap)", exact = true)
        assertTrue(tool.item!!.spec.box.height > 0f)
        assertTrue(SmokeUi.has("Box height", exact = true))

        SmokeUi.click("OK", exact = true)
        tool.commit()
        SmokeUi.settle()
        val stored = TextCodec.decode(c.activeLayer.textData)!!
        assertEquals("Caption", stored.text)
        assertTrue(stored.spec.vertical && stored.spec.columnsLeftToRight && stored.spec.box.roundness == 1f)
        assertEquals(VerticalStyle.MIXED, stored.spec.verticalStyle)
        Smoke.assertQuiet(c, "text edited")

        // Emptying the text deletes the layer at once (no question); undo brings it back.
        SmokeUi.click("Edit text", exact = true)
        assertTrue(tool.editorOpen)
        SmokeUi.field("Text").type("")
        SmokeUi.settle()
        SmokeUi.click("OK", exact = true)
        assertFalse(SmokeUi.has("Delete the text layer?"))
        assertEquals(1, c.doc.layers.size)
        assertEquals(null, tool.item)
        Smoke.assertQuiet(c, "emptied text deleted")
        c.undo()
        SmokeUi.settle()
        assertEquals(2, c.doc.layers.size)
        assertEquals("Caption", TextCodec.decode(c.activeLayer.textData)!!.text)

        // Text on a shape: box and vertical options are off (their values are kept).
        assertTrue(tool.editLayer(c.activeLayer, openEditor = true))
        tool.setPath(TextPathSpec(type = TextPathType.CIRCLE))
        SmokeUi.settle()
        assertTrue(tool.item!!.path.isActive)
        assertTrue(SmokeUi.has("Boxes aren't used"))
        assertFalse(SmokeUi.isEnabled("Vertical text"))
        assertTrue("kept", tool.item!!.spec.vertical && tool.item!!.spec.box.fill)
        tool.cancelEditor()
        tool.discard()
        SmokeUi.settle()
        Smoke.assertQuiet(c, "path discarded")
    }
}
