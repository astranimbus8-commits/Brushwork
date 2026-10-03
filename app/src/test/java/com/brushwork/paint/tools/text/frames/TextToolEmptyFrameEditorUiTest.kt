package com.brushwork.paint.tools.text.frames

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 integration item 9 in the real editor at the user's phone size: with the Text tool, a
 * finger tap on an EMPTY frame (the one "Unlink here" leaves) selects that frame in the Text
 * frames tool — its strip ("Edit story", …) replaces the Text tool's — instead of starting a new
 * text on top of it: no "Add text" editor, no step, no new layer.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.frames.emptyframeeditorsandbox"])
class TextToolEmptyFrameEditorUiTest {

    private fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..4) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 4f, y0 + (y1 - y0) * i / 4f))
        c.pointerUp(ToolPoint(x1, y1))
        SmokeUi.settle()
    }

    private fun tapDoc(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
        SmokeUi.settle()
    }

    @Test
    fun aFingerTapOnAnEmptyFrameWithTheTextToolSelectsTheFrame() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        SmokeUi.settle()

        // Two linked frames, then "Unlink here" on the first: the second is an empty frame.
        c.selectTool(ToolId.TEXT_FRAMES)
        val frames = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        frames.storyPreviewMs = 0L
        frames.dragPreviewMs = 0L
        SmokeUi.settle()
        drag(c, 60f, 60f, 400f, 260f)
        assertTrue(frames.story.isOpen)
        frames.story.setText(FrameFixtures.STORY)
        frames.story.setSizePx(18f)
        frames.story.confirmEditor()
        SmokeUi.settle()
        val f1 = c.activeLayer
        assertTrue(c.textThreads.isFrame(f1))
        SmokeUi.click("Link…", exact = true)
        assertSame(f1, frames.linkFrom)
        drag(c, 60f, 380f, 540f, 700f)
        val f2 = c.activeLayer
        assertEquals(listOf(f1, f2), FrameFixtures.chainOf(c, f1))
        tapDoc(c, 200f, 160f)
        assertSame(f1, frames.selected)
        SmokeUi.click("Unlink here", exact = true)
        assertTrue("${f2.name} is still a frame", c.textThreads.isFrame(f2))
        assertEquals("${f2.name} is empty", "", FrameFixtures.itemOf(f2).text)

        // The Text tool: a finger tap in the middle of the empty frame.
        c.selectTool(ToolId.TEXT)
        SmokeUi.settle()
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        assertFalse("the Text tool's strip shows no frame buttons", SmokeUi.has("Edit story", exact = true))
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        val data = f2.textData
        val box = FrameFixtures.boxOf(f2)
        val canvas = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")
        val loc = IntArray(2)
        canvas.getLocationInWindow(loc)
        val at = c.viewTransform.docToScreen(box.centerX(), box.centerY())
        Smoke.Touch(activity.window.decorView).apply { idle(300); tap(at.x + loc[0], at.y + loc[1]) }
        SmokeUi.settle()

        assertEquals("the tap went to the Text frames tool", ToolId.TEXT_FRAMES, c.activeToolId)
        assertSame("the empty frame is selected", f2, frames.selected)
        assertSame(f2, c.activeLayer)
        assertTrue("its strip shows: ${SmokeUi.shown().take(60)}", SmokeUi.has("Edit story", exact = true) && SmokeUi.has("Delete frame", exact = true))
        assertFalse("no new text was started on it", text.hasPendingWork)
        assertFalse("no \"Add text\" editor", SmokeUi.has("Add text", exact = true))
        assertFalse(frames.story.isOpen)
        assertEquals("a tap records nothing", steps, c.undoManager.undoCount)
        assertEquals("no layer was added", layers, c.doc.layers.size)
        assertSame(data, f2.textData)
        assertNull(c.renderOverride)

        // "Edit story" types into the empty frame (its own one-frame story).
        SmokeUi.click("Edit story", exact = true)
        assertTrue(frames.story.isOpen)
        assertSame(f2, frames.storyTarget)
        frames.story.cancelEditor()
        SmokeUi.settle()
        assertEquals(steps, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "empty frame tapped with the Text tool")
    }
}
