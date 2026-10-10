package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.FramesUi
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextKerns
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.placement.gapCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 item 17 (design §3.17) across two linked text frames, with fingers on the 392 dp phone
 * (the model side is TextKernThreadsRobolectricTest's; the one-text dialog Qa17ChromeKerningUiTest's):
 * - Frame 1 drawn and typed ("AVATAR WAVE TOWER." eight times, 18 px), its red "+" tapped, frame 2
 *   drawn: the story flows on into frame 2.
 * - Frame 2 selected, "Edit story": the dialog's field holds the WHOLE story. The cursor in an
 *   "AV" of frame 2 names it ("Between “A” and “V”"); "-100*3" in Kerning reads "= -300" and
 *   Done sets −300 there. OK is one step "Edit story"; both frames hold the story kern; frame 1's
 *   pixels stay bit for bit (data only), frame 2 draws it; every frame is its rendering (I1, I9).
 * - Frame 1, "Edit story": letters selected across the frame boundary (the last five of frame 1,
 *   the first five of frame 2) take 120 at every gap; one step; both frames change.
 * - Frame 1, "Edit story": "Typed first. " at the cursor at the start of the story moves every
 *   kern with its letters (the "AV" keeps its −300) while the text re-flows across the frames.
 * - Two fingers on the canvas undo the three edits one at a time, each back to the exact
 *   document before it (I2); three fingers redo them.
 * The frames unkerned, kerned and typed into are rendered to chrome-kern-5/6/7-*.png.
 * One UI test, own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromekernframessandbox"])
class Qa17ChromeKernFramesUiTest {
    private companion object {
        val STORY = "AVATAR WAVE TOWER. ".repeat(8).trim()
        const val SIZE_PX = 18
        const val TYPED = "Typed first. "
    }

    @Test
    fun kerningAcrossTwoLinkedFrames() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("kerning across two linked frames") { frames(h) }
        dog.interrupt()
        h.finish()
    }

    /** Puts the text field's cursor or selection at [start, end), as a finger does. */
    private fun selectText(start: Int, end: Int) {
        SmokeUi.field("Text").focus()
        settle(2)
        val set = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.SetSelection)?.action
            ?: throw AssertionError("the text field has no selection action")
        set(start, end, false)
        settle()
    }

    /** The document as the user sees it (every layer over white), twice as large. */
    private fun composite(c: EditorController): Bitmap {
        val b = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        val canvas = Canvas(b)
        for (l in c.doc.layers) if (l.visible) canvas.drawBitmap(l.bitmap, 0f, 0f, null)
        return Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, false)
    }

    /** Opens the story editor from [layer]'s frame ("Edit story" on the strip). */
    private fun editStory(fr: FramesUi, layer: Layer) {
        fr.select(layer)
        click(TextFrameTool.EDIT_LABEL, exact = true)
        assertTrue("the story editor", fr.tool.story.isOpen)
    }

    /** OK in the story editor: one step "Edit story". */
    private fun ok(fr: FramesUi) {
        val c = fr.c
        val steps = c.undoManager.undoCount
        click("OK", exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); c.busyMessage == null })
        assertFalse(fr.tool.story.isOpen)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TextFrameTool.EDIT_LABEL, c.undoManager.undoLabel)
    }

    private fun frames(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }
        val c = s.c
        val fr = FramesUi(s)
        fr.pick()

        // Frame 1 drawn and typed: more than fits. Its red "+", then frame 2.
        fr.drawFrame(20f, 20f, 180f, 120f)
        assertTrue("the story editor opened for the new frame", fr.tool.story.isOpen && fr.tool.story.editingNew)
        fr.typeStory(STORY, SIZE_PX)
        val f1 = c.activeLayer
        assertTrue("frame 1 overflows", itemOf(f1).thread.overset)
        fr.tapPort(f1)
        assertSame("frame 1's + is loaded", f1, fr.tool.linkFrom)
        fr.drawFrame(220f, 20f, 380f, 280f)
        val f2 = c.activeLayer
        assertEquals(listOf(f1, f2), chainOf(c, f1))
        val id = itemOf(f1).thread.storyId
        assertEquals(STORY, itemOf(f2).thread.story)
        assertFalse("the story fits in two frames", itemOf(f2).thread.overset)
        assertWhole(c, id)
        val snap0 = snapshot(c)
        val px1 = pixels(f1.bitmap)
        val px2 = pixels(f2.bitmap)
        Qa17Shots.save(composite(c), "kern-5-frames-unkerned")

        // An "AV" of frame 2: the cursor names it, "-100*3" sets -300 there.
        val th2 = itemOf(f2).thread
        val g = (th2.start + 4 until th2.end - 1).first { STORY[it] == 'A' && STORY[it + 1] == 'V' }
        editStory(fr, f2)
        assertEquals("the whole story is edited", STORY, SmokeUi.field("Text").text)
        selectText(g + 1, g + 1)
        assertTrue("the gap named: ${SmokeUi.shown().take(80)}", SmokeUi.has(KerningLabels.between("A", "V"), exact = true))
        SmokeUi.field(KerningLabels.KERNING).focus()
        settle(2)
        SmokeUi.field(KerningLabels.KERNING).type("-100*3")
        settle(4)
        assertTrue("the readout: ${SmokeUi.shown().take(80)}", SmokeUi.shown().any { it.startsWith("= ") && it.contains("300") })
        val done = SmokeUi.field(KerningLabels.KERNING).node.config.getOrNull(SemanticsActions.OnImeAction)?.action
            ?: throw AssertionError("Kerning has no Done")
        done()
        settle(4)
        assertEquals(listOf(TextKern(g, -300)), fr.tool.story.item!!.kerns)
        ok(fr)
        assertEquals("frame 1 holds the story kern", listOf(TextKern(g, -300)), itemOf(f1).kerns)
        assertEquals("frame 2 holds the story kern", listOf(TextKern(g, -300)), itemOf(f2).kerns)
        assertWhole(c, id)
        assertTrue("frame 1's pixels are kept (data only)", px1.contentEquals(pixels(f1.bitmap)))
        assertFalse("frame 2 draws the kern", px2.contentEquals(pixels(f2.bitmap)))
        val snap1 = snapshot(c)

        // Letters across the frame boundary: the last five of frame 1, the first five of frame 2.
        val end1 = itemOf(f1).thread.end
        assertEquals("frame 2 goes on where frame 1 ends", end1, itemOf(f2).thread.start)
        val sel = (end1 - 5) to (end1 + 5)
        val gaps = TextKerns.gaps(sel.first, sel.second, STORY)!!
        val active = TextKerns.applying(STORY, gaps)
        assertTrue("gaps on both sides of the boundary: $active", active.any { it < end1 - 1 } && active.any { it >= end1 })
        assertFalse(g in active)
        val before1 = pixels(f1.bitmap)
        val before2 = pixels(f2.bitmap)
        editStory(fr, f1)
        selectText(sel.first, sel.second)
        assertTrue("the gaps named: ${SmokeUi.shown().take(80)}", SmokeUi.has(gapCaption(STORY, gaps), exact = true))
        SmokeUi.typeAndDone(KerningLabels.KERNING, "120")
        val kerned = (active.map { TextKern(it, 120) } + TextKern(g, -300)).sortedBy { it.index }
        assertEquals(kerned, fr.tool.story.item!!.kerns)
        ok(fr)
        assertEquals(kerned, itemOf(f1).kerns)
        assertEquals(kerned, itemOf(f2).kerns)
        assertWhole(c, id)
        assertFalse("frame 1 draws its kerns", before1.contentEquals(pixels(f1.bitmap)))
        assertFalse("frame 2 draws its kerns", before2.contentEquals(pixels(f2.bitmap)))
        val snap2 = snapshot(c)
        Qa17Shots.save(composite(c), "kern-6-frames-kerned")

        // Typed at the start of the story: every kern moves with its letters.
        editStory(fr, f1)
        selectText(0, 0)
        val insert = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action
            ?: throw AssertionError("the text field has no insert action")
        insert(AnnotatedString(TYPED))
        settle()
        val story3 = TYPED + STORY
        assertEquals(story3, SmokeUi.field("Text").text)
        val moved = kerned.map { TextKern(it.index + TYPED.length, it.value) }
        assertEquals("every kern moved with its letters", moved, fr.tool.story.item!!.kerns)
        ok(fr)
        assertEquals(story3, itemOf(f1).thread.story)
        assertEquals(moved, itemOf(f1).kerns)
        assertEquals(moved, itemOf(f2).kerns)
        val av = moved.single { it.value == -300 }.index
        assertEquals("the -300 is still between A and V", "AV", story3.substring(av, av + 2))
        assertWhole(c, id)
        val snap3 = snapshot(c)
        Qa17Shots.save(composite(c), "kern-7-frames-typed-first")

        // Two fingers undo each edit to the exact document before it; three fingers redo them.
        val steps = c.undoManager.undoCount
        for ((k, before) in listOf(snap2, snap1, snap0).withIndex()) {
            fr.ui.twoFingerUndo()
            assertEquals("undo ${k + 1}", steps - k - 1, c.undoManager.undoCount)
            assertSnapshot(c, before, "undo ${k + 1}")
        }
        assertTrue("no kerns", itemOf(f1).kerns.isEmpty() && itemOf(f2).kerns.isEmpty())
        assertTrue(px1.contentEquals(pixels(f1.bitmap)) && px2.contentEquals(pixels(f2.bitmap)))
        repeat(3) { fr.ui.threeFingerRedo() }
        assertEquals(steps, c.undoManager.undoCount)
        assertSnapshot(c, snap3, "redone")
        assertWhole(c, id)
        Smoke.assertQuiet(c, "kern frames")
    }
}
