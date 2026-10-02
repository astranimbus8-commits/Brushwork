package com.brushwork.paint.tools.text.frames

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.textframes.LINK_HINT
import com.brushwork.paint.ui.textframes.TextFrameToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Text frames tool on the user's phone (v1.6, §3.6a/d "TextFramesUiRobolectricTest"),
 * operated through its labels: draw a frame, the story editor (Placeholder "Fill the box" fills the
 * frame exactly; more text overflows), the red "+ N characters", "Link…" and the link hint, drawing
 * the second frame clears the overflow, "Unlink here", "Delete frame", "Threads"; every strip
 * button is at least 40 dp tall.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.frames.uisandbox"])
class TextFramesUiRobolectricTest {

    private fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..4) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 4f, y0 + (y1 - y0) * i / 4f))
        c.pointerUp(ToolPoint(x1, y1))
        SmokeUi.settle()
    }

    /** The clickable [label] is at least 40 dp tall and wide (I10, fingers). */
    private fun assertTouchTarget(label: String, density: Float) {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        while (n != null && !n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)) n = n.parent
        // Its own size (the strip scrolls sideways: what is off screen is clipped in the window).
        val size = requireNotNull(n) { "\"$label\" is not clickable" }.size
        assertTrue("\"$label\" is ${size.height / density} dp tall", size.height >= 40f * density - 1f)
        assertTrue("\"$label\" is ${size.width / density} dp wide", size.width >= 40f * density - 1f)
    }

    @Test
    fun drawFillOverflowLinkUnlinkAndDeleteThroughTheStrip() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT_FRAMES)
        val tool = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        // Hosted like the options strip: one row that scrolls sideways.
        activity.setContent {
            BrushworkTheme {
                Row(Modifier.horizontalScroll(rememberScrollState())) { TextFrameToolOptions(tool) }
            }
        }
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Drag on the canvas to draw a text frame"))
        assertTrue(SmokeUi.has("Threads", exact = true))
        assertFalse(SmokeUi.has("Edit story", exact = true))

        // Draw a frame: the story editor opens (no vertical text, no path, no box size).
        drag(c, 40f, 40f, 300f, 200f)
        assertTrue(tool.story.isOpen)
        assertTrue(SmokeUi.has("Add text", exact = true))
        assertFalse(SmokeUi.has("Vertical text", exact = true))
        assertFalse(SmokeUi.has("Shape / path", exact = true))
        assertFalse(SmokeUi.has("Fixed width (lines wrap)", exact = true))
        SmokeUi.assertWindowsLaidOut(2)

        // Placeholder "Fill the box": exactly as much as the frame holds (no overflow).
        tool.story.setSizePx(14f)
        SmokeUi.settle()
        SmokeUi.click("Fill the box", exact = true)
        SmokeUi.click("Insert", exact = true)
        assertTrue("the placeholder arrives", Smoke.pumpUntil { tool.story.item?.text?.isNotEmpty() == true })
        SmokeUi.settle()
        val filled = tool.story.item!!.text
        assertTrue(filled.length > 20)
        SmokeUi.click("OK", exact = true)
        val f1 = c.activeLayer
        assertTrue(c.textThreads.isFrame(f1))
        val item = TextCodec.decode(f1.textData)!!
        assertEquals(filled, item.thread.story)
        assertFalse("a filled frame does not overflow", item.thread.overset)
        assertFalse(SmokeUi.has(" characters"))
        Smoke.assertQuiet(c, "frame filled")

        // More text than fits: the red "+ N characters".
        SmokeUi.click("Edit story", exact = true)
        assertTrue(tool.story.isOpen)
        assertTrue(SmokeUi.has("Edit text", exact = true))
        SmokeUi.field("Text").type(FrameFixtures.STORY)
        SmokeUi.settle()
        SmokeUi.click("OK", exact = true)
        val over = TextThreadFlow.oversetCount(TextCodec.decode(f1.textData)!!)
        assertTrue(over > 0)
        assertTrue("the strip reads \"+ $over characters\"", SmokeUi.has("+ $over characters", exact = true))
        for (label in listOf("Edit story", "Link…", "Delete frame", "Threads")) assertTouchTarget(label, density)
        assertFalse("nothing after the only frame", SmokeUi.isEnabled("Unlink here"))
        Smoke.assertQuiet(c, "overflow")

        // Link…: the hint; the next frame drawn takes the overflow.
        SmokeUi.click("Link…", exact = true)
        assertSame(f1, tool.linkFrom)
        assertTrue(SmokeUi.has(LINK_HINT, exact = true))
        assertTouchTarget("Cancel link", density)
        drag(c, 40f, 260f, 560f, 760f)
        val f2 = c.activeLayer
        assertNull(tool.linkFrom)
        assertEquals(listOf(f1, f2), c.textThreads.framesOf(item.thread.storyId).map { it.layer })
        assertFalse("the second frame holds the rest", TextCodec.decode(f2.textData)!!.thread.overset)
        assertFalse(SmokeUi.has(" characters"))
        assertFalse(SmokeUi.has(LINK_HINT, exact = true))
        Smoke.assertQuiet(c, "linked")

        // Unlink here (frame 1): the text comes back as overflow of frame 1; frame 2 is empty.
        tool.select(f1)
        SmokeUi.settle()
        assertTrue(SmokeUi.isEnabled("Unlink here"))
        SmokeUi.click("Unlink here", exact = true)
        assertEquals("", TextCodec.decode(f2.textData)!!.text)
        assertTrue(SmokeUi.has("+ $over characters", exact = true))
        c.undo()
        SmokeUi.settle()
        assertEquals(listOf(f1, f2), c.textThreads.framesOf(item.thread.storyId).map { it.layer })

        // Delete frame (frame 2): the story heals into frame 1 (overflowing again), one step.
        tool.select(f2)
        SmokeUi.settle()
        val steps = c.undoManager.undoCount
        SmokeUi.click("Delete frame", exact = true)
        assertEquals(-1, c.doc.indexOf(f2))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue(TextCodec.decode(f1.textData)!!.thread.overset)

        // Threads off and on.
        SmokeUi.click("Threads", exact = true)
        assertFalse(tool.showThreads)
        SmokeUi.click("Threads", exact = true)
        assertTrue(tool.showThreads)
        Smoke.assertQuiet(c, "done")
        assertNotNull(RobolectricUi.windowRoots())
    }
}
