package com.brushwork.paint.qa16

import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertRendered
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, linked frames and the rest of the editor, by the labels the user sees: the Text
 * tool tapping a full and an empty frame (it hands them to Text frames, no new text) and its
 * "Edit text"; the layer window's ⛓ badges and ⋮ › "Edit text"; "Duplicate layer" (the copy is a
 * plain text of its slice), "Merge down" and a brush stroke (the frame leaves; the story re-flows
 * into the frames left in the same step). Each is one step (I2), undone with two fingers and
 * redone with three; the story stays whole (I9, I1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.frameslayerssandbox"])
class FramesLayersUiTest {

    private lateinit var h: ChromeHarness

    private fun chain(): Pair<FramesUi, List<Layer>> {
        val s = h.editor(Smoke.document(600, 800, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }
        val f = FramesUi(s)
        f.pick()
        return f to f.chain()
    }

    private fun number(f: FramesUi, l: Layer) = f.c.doc.indexOf(l) + 1

    private fun openLayers(f: FramesUi) = click("Open layers (active layer ${f.c.doc.activeLayerIndex + 1})", exact = true)
    private fun closeLayers(f: FramesUi) = click("Close layers (active layer ${f.c.doc.activeLayerIndex + 1})", exact = true)

    /** Two fingers undo [after]'s step (the document as [before]), three redo it (as [after]). */
    private fun undoRedo(f: FramesUi, before: FrameFixtures.Snapshot, after: FrameFixtures.Snapshot, what: String) {
        f.ui.twoFingerUndo()
        assertSnapshot(f.c, before, "$what undone")
        f.ui.threeFingerRedo()
        assertSnapshot(f.c, after, "$what redone")
    }

    @Test
    fun framesWithTheTextToolAndTheLayerWindow() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("the Text tool: a tap on a full or an empty frame selects it; \"Edit text\" opens the story") { textTool() }
        h.section("the layer window: ⛓ badges, ⋮ › \"Edit text\"") { layerWindow() }
        h.section("\"Duplicate layer\": a plain text of the slice") { duplicate() }
        h.section("\"Merge down\": the story re-flows into the frames left") { merge() }
        h.section("a brush stroke on a frame: the story re-flows in the stroke's step") { rasterize() }
        dog.interrupt()
        h.finish()
    }

    private fun textTool() {
        val (f, frames) = chain()
        val (f1, f2, f3) = frames
        val c = f.c
        val id = f1.item().thread.storyId
        f.ui.tool(ToolId.TEXT.label)
        val text = c.currentTool as TextTool
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        f.tapFrame(f2)
        assertEquals("the tap went to Text frames", ToolId.TEXT_FRAMES, c.activeToolId)
        assertSame(f2, f.tool.selected)
        assertFalse("selected, not opened", f.tool.story.isOpen)
        assertFalse("no new text", text.hasPendingWork || has("Add text", exact = true))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(layers, c.doc.layers.size)
        assertTrue(has("Edit story", exact = true))

        // The Text tool's "Edit text" with the frame active: the story editor.
        f.ui.tool(ToolId.TEXT.label)
        click("Edit text", exact = true)
        assertEquals(ToolId.TEXT_FRAMES, c.activeToolId)
        assertTrue(f.tool.story.isOpen)
        assertSame(f2, f.tool.storyTarget)
        click("Cancel", exact = true)
        assertEquals(steps, c.undoManager.undoCount)

        // An empty frame (unlinked after frame 2).
        click("Unlink here", exact = true)
        assertEquals("", f3.item().text)
        val unlinked = c.undoManager.undoCount
        f.ui.tool(ToolId.TEXT.label)
        f.tapFrame(f3)
        assertEquals(ToolId.TEXT_FRAMES, c.activeToolId)
        assertSame(f3, f.tool.selected)
        assertFalse(has("Add text", exact = true))
        assertEquals(unlinked, c.undoManager.undoCount)
        assertEquals(layers, c.doc.layers.size)
        assertWhole(c, id)
        Smoke.assertQuiet(c, "Text tool on frames")
    }

    private fun layerWindow() {
        val (f, frames) = chain()
        val (f1, f2, f3) = frames
        val c = f.c
        openLayers(f)
        for ((k, l) in frames.withIndex()) {
            val badge = LayerLabels.badge(number(f, l), "Text frame ${k + 1} of 3")
            assertTrue("$badge: ${SmokeUi.shown().filter { "frame" in it }}", has(badge, exact = true))
        }
        click(LayerLabels.selectRow(number(f, f1)), exact = true)
        assertSame(f1, c.activeLayer)
        click(LayerLabels.MORE, exact = true)
        val steps = c.undoManager.undoCount
        click("Edit text", exact = true)
        assertEquals(ToolId.TEXT_FRAMES, c.activeToolId)
        assertTrue("the story editor opens", f.tool.story.isOpen)
        assertSame(f1, f.tool.storyTarget)
        assertEquals(FrameFixtures.STORY, f.tool.story.item!!.text)
        click("Cancel", exact = true)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
        Smoke.assertQuiet(c, "layer window")
    }

    private fun duplicate() {
        val (f, frames) = chain()
        val (f1, f2, f3) = frames
        val c = f.c
        val id = f1.item().thread.storyId
        openLayers(f)
        click(LayerLabels.selectRow(number(f, f2)), exact = true)
        val steps = c.undoManager.undoCount
        val before = snapshot(c)
        click(LayerLabels.DUPLICATE, exact = true)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val copy = c.activeLayer
        assertTrue(copy !== f2 && copy.isTextLayer)
        assertFalse("the copy is no frame", c.textThreads.isFrame(copy))
        assertFalse(copy.item().thread.isOn)
        assertEquals("the copy holds frame 2's slice", f2.item().text, copy.item().text)
        assertRendered(copy, "the copy")
        assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
        assertWhole(c, id)
        val after = snapshot(c)
        closeLayers(f)
        undoRedo(f, before, after, "duplicate")
        Smoke.assertQuiet(c, "duplicate")
    }

    private fun merge() {
        val (f, frames) = chain()
        val (f1, f2, f3) = frames
        val c = f.c
        val id = f1.item().thread.storyId
        openLayers(f)
        click(LayerLabels.selectRow(number(f, f1)), exact = true)
        val steps = c.undoManager.undoCount
        val before = snapshot(c)
        click(LayerLabels.MERGE, exact = true)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertTrue("frame 1 merged into the background", c.doc.indexOf(f1) < 0)
        assertFalse(c.doc.layers[0].isTextLayer)
        assertEquals(listOf(f2, f3), chainOf(c, f2))
        assertEquals("the story starts in frame 2 now", 0, f2.item().thread.start)
        assertEquals(id, f2.item().thread.storyId)
        assertWhole(c, id)
        val after = snapshot(c)
        closeLayers(f)
        undoRedo(f, before, after, "merge")
        assertWhole(c, id)
        f.ui.twoFingerUndo()
        assertEquals(listOf(f1, f2, f3), chainOf(c, f1))
        assertWhole(c, id)
        Smoke.assertQuiet(c, "merge")
    }

    private fun rasterize() {
        val (f, frames) = chain()
        val (f1, f2, f3) = frames
        val c = f.c
        val id = f1.item().thread.storyId
        f.select(f2)
        f.ui.tool(ToolId.BRUSH.label)
        assertSame(f2, c.activeLayer)
        val steps = c.undoManager.undoCount
        val before = snapshot(c)
        val b = f.box(f2)
        f.ui.stroke(b.left + 20f to b.centerY(), b.centerX() to b.centerY() + 10f, b.right - 20f to b.centerY())
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertFalse("frame 2 is a plain layer now", f2.isTextLayer)
        assertNotNull(c.doc.layers.firstOrNull { it === f2 })
        assertEquals(listOf(f1, f3), chainOf(c, f1))
        assertEquals("frame 3 goes on where frame 1 ends", f1.item().thread.end, f3.item().thread.start)
        assertWhole(c, id)
        System.err.println("a brush stroke on a frame is the step \"${c.undoManager.undoLabel}\"")
        val after = snapshot(c)
        undoRedo(f, before, after, "brush on a frame")
        Shots.save(c.compositor.renderFlattened(FrameFixtures.WHITE), "frames-after-brush.png")
        Smoke.assertQuiet(c, "rasterize")
    }
}
