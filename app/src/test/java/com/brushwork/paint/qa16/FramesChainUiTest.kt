package com.brushwork.paint.qa16

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.textframes.LINK_HINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, linked text frames on the full editor with fingers (the real view transform):
 * draw frame 1, type more than fits, see "+ N characters" and the red "+" on the canvas, tap
 * the "+", draw frame 2, drag frame 3 out of frame 2's "+"; then resize, move, unlink and delete
 * frames and edit the story — each ONE step (I2) that re-flows the story (I9, I1), undone with
 * two fingers and redone with three.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.frameschainsandbox"])
class FramesChainUiTest {

    private lateinit var h: ChromeHarness

    private fun screen(): ChromeScreen =
        h.editor(Smoke.document(600, 800, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }

    private fun storyOf(f: FramesUi) = f.c.activeLayer.item().thread.storyId

    /**
     * [action] is one step named [label]; two fingers undo it (the document exactly as before)
     * and three fingers redo it (exactly as after); [check] runs after the action and the redo.
     */
    private fun oneStep(f: FramesUi, label: String, action: () -> Unit, check: () -> Unit) {
        val c = f.c
        val steps = c.undoManager.undoCount
        val before = snapshot(c)
        action()
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
        check()
        val after = snapshot(c)
        f.ui.twoFingerUndo()
        assertEquals("$label undone", steps, c.undoManager.undoCount)
        assertSnapshot(c, before, "$label undone")
        f.ui.threeFingerRedo()
        assertEquals("$label redone", steps + 1, c.undoManager.undoCount)
        assertSnapshot(c, after, "$label redone")
        check()
    }

    @Test
    fun framesLinkedAndReflowedWithFingers() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("draw, overflow, the red +, tap it, draw frame 2, drag frame 3 out of its +") { build() }
        h.section("resize, move, unlink, delete: the story re-flows, one step each") { reflow() }
        h.section("the story editor: a second tap on the selected frame") { storyEditor() }
        dog.interrupt()
        h.finish()
    }

    private fun build() {
        val s = screen()
        val f = FramesUi(s)
        val c = s.c
        f.pick()
        assertTrue("the strip says how: ${SmokeUi.shown().take(40)}", has("Drag on the canvas to draw a text frame", exact = false))

        // Frame 1: drawn, the story editor opens ("Add text": no vertical text, no path).
        lateinit var f1: com.brushwork.paint.model.Layer
        oneStep(f, TextFrameTool.ADD_LABEL, action = {
            f.drawFrame(40f, 40f, 300f, 240f)
            assertTrue(f.tool.story.isOpen && f.tool.story.editingNew)
            assertTrue("the editor's title: ${SmokeUi.shown().take(30)}", has("Add text", exact = true))
            assertFalse("frames are horizontal", has("Vertical text", exact = true))
            assertFalse("frames are straight boxes", has("Shape / path", exact = true))
            assertTrue("letter scaling is offered", has("Scale letters", exact = true))
            f.typeStory(FrameFixtures.STORY, 14)
            f1 = c.activeLayer
        }) {
            assertTrue(c.textThreads.isFrame(f1))
            assertEquals(listOf(f1), chainOf(c, f1))
            assertWhole(c, f1.item().thread.storyId)
        }
        val box1 = f.box(f1)
        assertEquals("drawn where the finger went", 260f, box1.width(), 2f)
        assertEquals(200f, box1.height(), 2f)
        val th = f1.item().thread
        assertTrue("more text than fits", th.overset)
        val left = th.story.length - th.end
        f.select(f1)
        assertTrue("the strip counts it: ${SmokeUi.shown().take(40)}", has("+ $left characters", exact = true))
        val shot = f.capture()
        Shots.save(shot, "frames-overset-port.png")
        assertTrue("the red + is drawn at frame 1's out-port", f.redAt(shot, f.portOnCanvas(f1)) >= 40)

        // A tap on the + loads it (no step), then frame 2 is drawn: "Link frame".
        val steps = c.undoManager.undoCount
        f.tapPort(f1)
        assertSame(f1, f.tool.linkFrom)
        assertTrue("link mode says what to do", has(LINK_HINT, exact = true) && has("Cancel link", exact = true))
        assertEquals("a tap records nothing", steps, c.undoManager.undoCount)
        lateinit var f2: com.brushwork.paint.model.Layer
        oneStep(f, TextFrameTool.LINK_LABEL, action = {
            f.drawFrame(40f, 320f, 300f, 520f)
            f2 = c.activeLayer
        }) {
            assertEquals(listOf(f1, f2), chainOf(c, f1))
            assertWhole(c, th.storyId)
            assertNull("link mode ends", f.tool.linkFrom)
        }
        assertTrue("frame 2 overflows too", f2.item().thread.overset)
        assertEquals("frame 1's port is no longer red (its story goes on)", 0, f.redAt(f.capture(), f.portOnCanvas(f1)))

        // Frame 3 dragged straight out of frame 2's red +: its top-left corner on that +.
        val port2 = f.portOnCanvas(f2)
        lateinit var f3: com.brushwork.paint.model.Layer
        oneStep(f, TextFrameTool.LINK_LABEL, action = {
            f.drag(f.port(f2), f.window(c.viewTransform.docToScreen(Vec2(560f, 760f))))
            f3 = c.activeLayer
        }) {
            assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
            assertWhole(c, th.storyId)
        }
        val corner = c.viewTransform.docToScreen(FrameGeometry.Handle.TOP_LEFT.at(f.box(f3)))
        assertEquals("frame 3 starts at frame 2's +", port2.x, corner.x, c.viewTransform.dp(2f))
        assertEquals(port2.y, corner.y, c.viewTransform.dp(2f))
        val whole = f3.item().thread
        System.err.println("chain: ${listOf(f1, f2, f3).map { it.item().thread.let { t -> "${t.start}-${t.end}" } }} of ${whole.story.length}; frame 3 ${f.box(f3)}")
        assertFalse("the story fits in three frames", whole.overset)
        assertEquals(whole.story.length, whole.end)
        assertEquals("no red + any more", 0, f.redAt(f.capture(), f.portOnCanvas(f3)))
        assertFalse("no overset count", SmokeUi.shown().any { it.matches(Regex("\\+ \\d+ characters?")) })

        Shots.save(c.compositor.renderFlattened(FrameFixtures.WHITE), "frames-chain.png")
        Shots.save(f.capture(), "frames-chain-canvas.png")
        Smoke.assertQuiet(c, "chain")
    }

    private fun reflow() {
        val s = screen()
        val f = FramesUi(s)
        val c = s.c
        f.pick()
        val (f1, f2, f3) = f.chain()
        val id = f1.item().thread.storyId
        val t = c.viewTransform

        // Resize: frame 1's bottom-right dot dragged up and in; more of the story moves on.
        f.select(f1)
        val end1 = f1.item().thread.end
        oneStep(f, TextFrameTool.RESIZE_LABEL, action = {
            f.drag(f.handle(f1, FrameGeometry.Handle.BOTTOM_RIGHT), f.window(t.docToScreen(Vec2(200f, 140f))))
        }) {
            assertEquals("the bottom-right corner followed the finger", 200f, f.box(f1).right, 2f)
            assertEquals(140f, f.box(f1).bottom, 2f)
            assertTrue("frame 1 holds less", f1.item().thread.end < end1)
            assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
            assertWhole(c, id)
        }
        assertTrue("the rest no longer fits", f3.item().thread.overset)

        // Move: frame 1 dragged by its middle.
        val b0 = f.box(f1)
        oneStep(f, TextFrameTool.MOVE_LABEL, action = {
            val from = t.docToScreen(Vec2(b0.centerX(), b0.centerY()))
            f.drag(f.window(from), f.window(t.docToScreen(Vec2(b0.centerX() + 60f, b0.centerY() + 20f))))
        }) {
            assertEquals(b0.left + 60f, f.box(f1).left, 1.5f)
            assertEquals(b0.top + 20f, f.box(f1).top, 1.5f)
            assertEquals("the same size", b0.width(), f.box(f1).width(), 0.5f)
            assertWhole(c, id)
        }

        // Unlink after frame 2: frame 3 is left as an empty frame of its own; the rest is overset of frame 2.
        f.select(f2)
        oneStep(f, TextFrameTool.UNLINK_LABEL, action = { click("Unlink here", exact = true) }) {
            assertEquals(listOf(f1, f2), chainOf(c, f1))
            assertTrue(f2.item().thread.overset)
            assertTrue("frame 3 is still a frame", c.textThreads.isFrame(f3))
            assertEquals("frame 3 is empty", "", f3.item().text)
            assertTrue("of its own story", f3.item().thread.storyId != id)
            assertWhole(c, id)
            assertWhole(c, f3.item().thread.storyId)
        }
        f.ui.twoFingerUndo()
        assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
        assertWhole(c, id)

        // Delete the middle frame: frame 3 takes over where frame 1 ends (in the delete's step).
        f.select(f2)
        val layers = c.doc.layers.size
        val deleteLabel = run {
            val steps = c.undoManager.undoCount
            val before = snapshot(c)
            click("Delete frame", exact = true)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            assertEquals(layers - 1, c.doc.layers.size)
            assertTrue(c.doc.indexOf(f2) < 0)
            assertEquals(listOf(f1, f3), chainOf(c, f1))
            assertWhole(c, id)
            assertEquals("frame 3 starts where frame 1 ends", f1.item().thread.end, f3.item().thread.start)
            val after = snapshot(c)
            f.ui.twoFingerUndo()
            assertSnapshot(c, before, "delete undone")
            assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
            f.ui.threeFingerRedo()
            assertSnapshot(c, after, "delete redone")
            c.undoManager.undoLabel
        }
        System.err.println("Delete frame is the step \"$deleteLabel\"")
        Shots.save(c.compositor.renderFlattened(FrameFixtures.WHITE), "frames-after-delete.png")
        Smoke.assertQuiet(c, "re-flow")
    }

    private fun storyEditor() {
        val s = screen()
        val f = FramesUi(s)
        val c = s.c
        f.pick()
        val (f1, f2, f3) = f.chain()
        val id = f1.item().thread.storyId
        // A first tap selects frame 2, a second opens the story editor (the whole story).
        f.tapFrame(f2)
        assertSame(f2, f.tool.selected)
        assertFalse(f.tool.story.isOpen)
        val steps = c.undoManager.undoCount
        f.tapFrame(f2)
        assertTrue("a second tap opens the story", f.tool.story.isOpen)
        assertTrue(has("Edit text", exact = true))
        assertEquals("the whole story is edited", FrameFixtures.STORY, f.tool.story.item!!.text)
        // Cancel records nothing.
        click("Cancel", exact = true)
        assertFalse(f.tool.story.isOpen)
        assertEquals(steps, c.undoManager.undoCount)
        assertWhole(c, id)

        // "Edit story" on the strip; a shorter story: frame 3 is left empty; OK is one step.
        val short = FrameFixtures.LOREM
        oneStep(f, TextFrameTool.EDIT_LABEL, action = {
            click("Edit story", exact = true)
            assertTrue(f.tool.story.isOpen)
            f.typeStory(short, null)
        }) {
            assertEquals(short, f1.item().thread.story)
            assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
            assertWhole(c, id)
            assertFalse(f3.item().thread.overset)
        }
        Smoke.assertQuiet(c, "story editor")
    }
}
