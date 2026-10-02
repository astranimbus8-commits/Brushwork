package com.brushwork.paint.ui.placement

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextEditorHost
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 foundation (§4.4, §4.9): the text editor dialog and the numbers sheet work through
 * [TextEditorHost]. The Text tool is a host with every capability (the dialog is the v1.5
 * dialog); a host without text on a path, vertical text or an editable box size (a linked
 * story's editor) gets the same dialog with those sections hidden, editing the same text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.brushwork.paint.ui.placement.texthostsandbox"])
class TextEditorHostParityTest {

    /** A story-like host: the Text tool's members, without path, vertical text or box size. */
    private class Restricted(tool: TextTool) : TextEditorHost by tool {
        override val supportsPath: Boolean get() = false
        override val supportsVertical: Boolean get() = false
        override val supportsWrap: Boolean get() = false
        override val boxSizeEditable: Boolean get() = false
    }

    @Test
    fun theDialogWorksThroughTheHost() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(800, 600))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(tool.supportsPath && tool.supportsVertical && tool.supportsWrap && tool.boxSizeEditable)
        tool.startTextAt(400f, 300f)
        tool.updateBox { it.copy(width = 300f) }

        var host by mutableStateOf<TextEditorHost?>(tool)
        var numbers by mutableStateOf(false)
        activity.setContent {
            BrushworkTheme {
                val h = host
                if (h != null) { if (numbers) TextNumbersSheet(h) else TextEditorDialog(h) }
            }
        }
        SmokeUi.settle(20, 50)
        assertTrue("the v1.5 dialog: ${SmokeUi.shown().take(60)}", SmokeUi.has("Add text", exact = true))
        assertTrue(SmokeUi.has("Vertical text", exact = true))
        assertTrue(SmokeUi.has("SHAPE / PATH", exact = true))
        assertTrue(SmokeUi.has("Fixed width (lines wrap)", exact = true))

        // Edits through the host are the tool's.
        host!!.setText("Through the host")
        SmokeUi.settle()
        assertEquals("Through the host", tool.item?.text)

        host = Restricted(tool)
        SmokeUi.settle(20, 50)
        assertTrue(SmokeUi.has("Add text", exact = true))
        assertFalse("no vertical text", SmokeUi.has("Vertical text", exact = true))
        assertFalse("no text on a path", SmokeUi.has("SHAPE / PATH", exact = true))
        assertFalse("no box size", SmokeUi.has("Fixed width (lines wrap)", exact = true))
        assertTrue("the rest of the dialog is there", SmokeUi.has("Letter spacing", exact = true))
        host!!.updateSpec { it.copy(sizePx = 64f) }
        assertEquals(64f, tool.item!!.spec.sizePx, 0f)

        SmokeUi.click("OK", exact = true)
        assertFalse("OK through the host closes the tool's editor", tool.editorOpen)
        assertEquals("Through the host", tool.item?.text)

        // The numbers sheet through the host.
        numbers = true
        host = tool
        SmokeUi.settle(20, 50)
        assertTrue(SmokeUi.has("Position & size", exact = true))
        host!!.setCenterX(250f)
        assertEquals(250f, tool.item!!.cx, 0.01f)
        host = null
        SmokeUi.settle()
        tool.discard()
        Smoke.assertQuiet(c, "text host")
    }
}
