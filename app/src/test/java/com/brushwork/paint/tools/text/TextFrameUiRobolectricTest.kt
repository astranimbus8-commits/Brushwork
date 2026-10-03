package com.brushwork.paint.tools.text

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.ui.placement.FrameDividerOptions
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * Composes the text / frame option strips with their dialogs and sheets in a real activity, so
 * composition errors (missing state, bad layout constraints) surface in tests. Two test methods in
 * one sandbox: each first restarts Compose's main dispatcher ([SmokeUi.restartUiDispatcher]), or
 * the second would see its first composition and no recomposition (no dialog would open).
 */
@RunWith(RobolectricTestRunner::class)
class TextFrameUiRobolectricTest {

    @Before
    fun restartCompose() = SmokeUi.restartUiDispatcher()

    private fun settle() = repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }

    private fun controller(activity: ComponentActivity, tool: ToolId): EditorController {
        val doc = Document("t", "t", 600, 800)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(600, 800))
        val c = EditorController(activity.applicationContext, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(activity))
        c.selectTool(tool)
        return c
    }

    @Test
    fun textOptionsEditorAndNumbersSheetCompose() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = controller(activity, ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        settle()

        tool.startTextAt(300f, 400f)
        settle()
        val editor = ShadowDialog.getLatestDialog()
        assertTrue("the text editor dialog opens", editor != null && editor.isShowing)
        tool.setText("縦書き\nABC 12!?")
        tool.updateSpec { it.copy(vertical = true, bold = true, strokeWidthPx = 3f) }
        settle()
        tool.confirmEditor()
        settle()
        assertTrue(tool.hasPendingWork)

        tool.numbersOpen = true
        settle()
        tool.nudge(1f, 0f)
        settle()
        tool.numbersOpen = false
        settle()

        tool.openEditor()
        settle()
        tool.cancelEditor()
        settle()
        tool.commit()
        settle()
        assertEquals(2, c.doc.layers.size)
    }

    @Test
    fun frameOptionsSheetsCompose() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = controller(activity, ToolId.FRAME_DIVIDER)
        val tool = c.tools.getValue(ToolId.FRAME_DIVIDER) as FrameDividerTool
        activity.setContent { BrushworkTheme { FrameDividerOptions(tool) } }
        settle()

        tool.settingsOpen = true
        settle()
        tool.settings = tool.settings.copy(uniformMargins = false, rows = 2, cols = 2)
        settle()
        assertTrue(tool.createFrameLayer())
        tool.settingsOpen = false
        settle()
        assertEquals(FrameDividerTool.Status.READY, tool.status())

        tool.gridOpen = true
        settle()
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertTrue(tool.applyGrid(3, 2))
        tool.gridOpen = false
        settle()

        // Edited elsewhere: the strip switches to the out-of-sync state (and re-checks the pixels).
        c.editWholeLayer(c.doc.layers[1], "Scribble") { it.eraseColor(0xFF00FF00.toInt()) }
        settle()
        assertEquals(FrameDividerTool.Status.OUT_OF_SYNC, tool.status())
        c.undo()
        settle()
        assertEquals("undoing the scribble makes the frame usable again", FrameDividerTool.Status.READY, tool.status())
        tool.settingsOpen = true
        settle()
        tool.settingsOpen = false
        settle()
    }
}
