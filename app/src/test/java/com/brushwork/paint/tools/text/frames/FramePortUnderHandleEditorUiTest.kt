package com.brushwork.paint.tools.text.frames

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.textframes.LINK_HINT
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
 * v1.6 integration item 20 in the real editor at the user's phone size, with fingers: the second
 * frame is drawn by dragging from the first frame's red "+" (so its top-left handle lies on that
 * port); a finger TAP on the port then loads it (the link hint and "Cancel link" show, no story
 * editor opens), and "Cancel link" leaves everything as it was.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.frames.portundereditorsandbox"])
class FramePortUnderHandleEditorUiTest {

    private fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..4) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 4f, y0 + (y1 - y0) * i / 4f))
        c.pointerUp(ToolPoint(x1, y1))
        SmokeUi.settle()
    }

    @Test
    fun aFingerTapOnThePortUnderTheNewFramesCornerLoadsThePort() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        SmokeUi.settle()
        c.selectTool(ToolId.TEXT_FRAMES)
        val tool = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        SmokeUi.settle()

        // Frame 1 overflows: its red "+".
        drag(c, 40f, 40f, 300f, 240f)
        tool.story.setText(FrameFixtures.STORY)
        tool.story.setSizePx(18f)
        tool.story.confirmEditor()
        SmokeUi.settle()
        val f1 = c.activeLayer
        assertTrue(FrameFixtures.itemOf(f1).thread.overset)

        // A finger presses the red "+" and drags: frame 2, linked after frame 1 and selected.
        val view = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        fun window(s: Vec2) = (s.x + loc[0]) to (s.y + loc[1])
        val t = c.viewTransform
        val port = FramePorts.outPort(t, FrameFixtures.boxOf(f1))
        val end = t.docToScreen(Vec2(560f, 700f))
        val touch = Smoke.Touch(activity.window.decorView)
        touch.idle(300)
        touch.stroke(window(port), window(Vec2((port.x + end.x) / 2f, (port.y + end.y) / 2f)), window(end))
        SmokeUi.settle()
        val chain = FrameFixtures.chainOf(c, f1)
        assertEquals("a frame was drawn from the port and linked", 2, chain.size)
        val f2 = chain[1]
        assertSame(f2, tool.selected)
        val corner = t.docToScreen(FrameFixtures.boxOf(f2).left, FrameFixtures.boxOf(f2).top)
        assertEquals("its top-left handle lies on the port", port.x, corner.x, t.dp(2f))
        assertEquals(port.y, corner.y, t.dp(2f))
        assertTrue(SmokeUi.has("Edit story", exact = true))
        val steps = c.undoManager.undoCount
        val data1 = f1.textData
        val data2 = f2.textData

        // A finger tap on the port: the port's (link mode from frame 1), not the handle's.
        touch.idle(300)
        touch.tap(window(port).first, window(port).second)
        SmokeUi.settle()
        assertSame("the port under the handle is loaded", f1, tool.linkFrom)
        assertFalse("no story editor", tool.story.isOpen)
        assertTrue("the link hint shows: ${SmokeUi.shown().take(60)}", SmokeUi.has(LINK_HINT, exact = true))
        assertEquals("a tap records nothing", steps, c.undoManager.undoCount)
        assertSame(data1, f1.textData)
        assertSame(data2, f2.textData)

        SmokeUi.click("Cancel link", exact = true)
        assertNull(tool.linkFrom)
        assertEquals(steps, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "port under a handle")
    }
}
