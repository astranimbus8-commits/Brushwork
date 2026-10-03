package com.brushwork.paint.tools.text

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.placement.letterAlignLabel
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
 * v1.6 §3.5(a) review fix, in the real editor at the user's phone size: the options strip's
 * "Letters" chip (label "Letter scaling") is never a dead tap. The strip stays on screen while the
 * text editor's panel is open (or folded into its pill), so the chip opens the "Letter scaling"
 * sheet ON TOP of the editor; closing it shows the editor again. When the editor was opened over
 * an open Letter scaling sheet, the chip brings that sheet back on top. Nothing is history until
 * ✓, which is one step.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.letterssheetsandbox"])
class LettersSheetOverEditorUiRobolectricTest {

    @Test
    fun theLettersChipShowsItsSheetOverTheEditor() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.snapping.enabled = false
        val layersBefore = c.doc.layers.size
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        SmokeUi.settle()
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(300f, 400f)
        tool.setText("ELTON JOHN")
        SmokeUi.settle()
        SmokeUi.assertPanelShown("Add text")

        // The editor is open: the chip's sheet comes on top of it.
        SmokeUi.click("Letter scaling", exact = true)
        assertTrue(tool.lettersSheetOpen)
        SmokeUi.assertPanelShown("Letter scaling")
        SmokeUi.click("Scale letters", exact = true)
        assertTrue(tool.item!!.spec.letterScale.isOn)
        // Closing it shows the editor again, still editing the same (now scaled) text.
        SmokeUi.click("Close", exact = true)
        assertFalse(tool.lettersSheetOpen)
        assertTrue(tool.editorOpen)
        SmokeUi.assertPanelShown("Add text")
        assertTrue(tool.item!!.spec.letterScale.isOn)

        // The editor folded into its pill: the chip still shows its sheet.
        SmokeUi.click("Minimize", exact = true)
        assertTrue("the editor is in its pill: ${SmokeUi.pillTitles()}", SmokeUi.pillTitles().contains("Add text"))
        SmokeUi.click("Letter scaling", exact = true)
        SmokeUi.assertPanelShown("Letter scaling")
        SmokeUi.click(letterAlignLabel(LetterScaleAlign.TOP), exact = true)
        assertEquals(LetterScaleAlign.TOP, tool.item!!.spec.letterScale.align)
        SmokeUi.click("Close", exact = true)
        SmokeUi.assertPanelShown("Add text")

        // OK keeps the text pending. The chip opens the sheet; the editor opened again covers it;
        // the chip brings the sheet back on top (it was open all along, under the editor).
        SmokeUi.click("OK", exact = true)
        assertFalse(tool.editorOpen)
        assertTrue(tool.item != null)
        SmokeUi.click("Letter scaling", exact = true)
        SmokeUi.assertPanelShown("Letter scaling")
        tool.openEditor()
        SmokeUi.settle()
        SmokeUi.assertPanelShown("Edit text")
        SmokeUi.click("Letter scaling", exact = true)
        SmokeUi.assertPanelShown("Letter scaling")
        SmokeUi.click("Close", exact = true)
        SmokeUi.assertPanelShown("Edit text")
        SmokeUi.click("OK", exact = true)

        // Nothing was history while editing; ✓ is one step, and undo takes the text away.
        assertEquals(0, c.undoManager.undoCount)
        tool.commit()
        SmokeUi.settle()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(layersBefore + 1, c.doc.layers.size)
        val layer = c.doc.activeLayer
        val stored = TextCodec.decode(layer.textData)!!
        assertTrue(stored.spec.letterScale.isOn)
        assertEquals(LetterScaleAlign.TOP, stored.spec.letterScale.align)
        c.undo()
        SmokeUi.settle()
        assertEquals(layersBefore, c.doc.layers.size)
        Smoke.assertQuiet(c, "letters sheet over the editor")
    }
}
