package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.LetterScaleAlign
import com.brushwork.paint.tools.text.LetterScaleCurve
import com.brushwork.paint.tools.text.LetterScaleDirection
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.placement.letterAlignLabel
import com.brushwork.paint.ui.textframes.LINK_HINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA on the narrowest phone the design names (360 dp), on the full editor: the Text
 * frames strip shows a selected frame's first buttons and link mode's "Cancel link" without
 * scrolling, the rest ("Unlink here", "Delete frame") is reached by sliding the strip and works;
 * the red "+" is hit by a finger under this phone's view; and every letter-scaling control of the
 * story editor is reached in the dialog (at least 40 dp) and takes effect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.framesnarrowsandbox"])
class FramesNarrowUiTest {

    private lateinit var h: ChromeHarness

    private fun screen() =
        h.editor(Smoke.document(600, 800, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }

    @Test
    fun framesAt360Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("the strip, the red + and the frame buttons at 360 dp") { strip() }
        h.section("letter scaling in the story editor at 360 dp") { letters() }
        dog.interrupt()
        h.finish()
    }

    private fun strip() {
        val s = screen()
        assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
        val f = FramesUi(s)
        val c = s.c
        f.pick()
        f.drawFrame(40f, 40f, 300f, 240f)
        f.typeStory(FrameFixtures.STORY, 14)
        val f1 = c.activeLayer
        f.select(f1)
        for (label in listOf("Edit story", "Link…")) assertTrue("\"$label\" is on screen without sliding the strip", f.ui.wholly(label))
        assertTrue(SmokeUi.shown().any { it.matches(Regex("\\+ \\d+ characters")) })

        // Link mode from the strip: "Cancel link" on screen at once.
        click("Link…", exact = true)
        assertSame(f1, f.tool.linkFrom)
        assertTrue(has(LINK_HINT, exact = true))
        assertTrue("\"Cancel link\" is on screen", f.ui.wholly("Cancel link"))
        click("Cancel link", exact = true)
        assertNull(f.tool.linkFrom)

        // The red + under this phone's view, then frame 2.
        f.tapPort(f1)
        assertSame("the finger hits the red +", f1, f.tool.linkFrom)
        f.drawFrame(40f, 320f, 300f, 520f)
        val f2 = c.activeLayer
        assertEquals(listOf(f1, f2), chainOf(c, f1))
        val id = f1.item().thread.storyId
        assertWhole(c, id)

        // "Unlink here" on frame 1 (slid into view), undone; "Delete frame" on frame 2 (slid into view).
        f.select(f1)
        f.ui.reach("Unlink here")
        var steps = c.undoManager.undoCount
        click("Unlink here", exact = true)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TextFrameTool.UNLINK_LABEL, c.undoManager.undoLabel)
        assertEquals(listOf(f1), chainOf(c, f1))
        f.ui.twoFingerUndo()
        assertEquals(listOf(f1, f2), chainOf(c, f1))
        f.select(f2)
        f.ui.reach("Delete frame")
        steps = c.undoManager.undoCount
        click("Delete frame", exact = true)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue(c.doc.indexOf(f2) < 0)
        assertEquals(listOf(f1), chainOf(c, f1))
        assertTrue("the story goes back to overflowing frame 1", f1.item().thread.overset)
        assertWhole(c, id)
        Smoke.assertQuiet(c, "360 dp strip")
    }

    private fun letters() {
        val s = screen()
        val f = FramesUi(s)
        val c = s.c
        f.pick()
        f.drawFrame(40f, 40f, 560f, 200f)
        assertTrue(f.tool.story.isOpen)
        SmokeUi.field("Text").type("ELTON JOHN")
        settle()
        f.ui.textSizePx(48)
        f.ui.reach("Scale letters")
        click("Scale letters", exact = true)
        // Material chips are 32 dp tall (Compose extends their touch area to its 48 dp minimum, as
        // TouchTargetAuditTest allows); the other controls are at least 40 dp.
        val chips = listOf(
            letterAlignLabel(LetterScaleAlign.CENTER),
            letterAlignLabel(LetterScaleAlign.BASELINE),
            letterAlignLabel(LetterScaleAlign.TOP),
            LetterScaleDirection.END_TO_START.label,
            LetterScaleCurve.RATIO.label,
            "Each paragraph",
        )
        f.ui.reach("Type a value for Smallest letter")
        for (label in chips) f.ui.reach(label, CHIP_DP)
        f.ui.reach("Type a value for Smallest letter")
        click("Type a value for Smallest letter", exact = true)
        SmokeUi.typeAndDone("Smallest letter", "50")
        f.ui.reach(letterAlignLabel(LetterScaleAlign.BASELINE), CHIP_DP)
        click(letterAlignLabel(LetterScaleAlign.BASELINE), exact = true)
        f.ui.reach(LetterScaleDirection.END_TO_START.label, CHIP_DP)
        click(LetterScaleDirection.END_TO_START.label, exact = true)
        f.ui.reach("OK")
        val steps = c.undoManager.undoCount
        click("OK", exact = true)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TextFrameTool.ADD_LABEL, c.undoManager.undoLabel)
        val ls = c.activeLayer.item().spec.letterScale
        assertTrue(ls.isOn)
        assertEquals(50f, ls.smallestPercent, 0f)
        assertEquals(LetterScaleAlign.BASELINE, ls.align)
        assertEquals(LetterScaleDirection.END_TO_START, ls.direction)
        assertWhole(c, c.activeLayer.item().thread.storyId)
        Shots.save(c.compositor.renderFlattened(FrameFixtures.WHITE), "frames-letters-360dp.png")
        Smoke.assertQuiet(c, "360 dp letters")
    }

    private companion object {
        const val CHIP_DP = 32f
    }
}
