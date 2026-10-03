package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.roundToInt

/**
 * v1.6 final QA, letter scaling across linked frames: "HHHHHHHH HHHHHHHH" at 40 px, letters down
 * to 40 % (typed in the "Add text" editor of frame 1), one line per frame. Frame 2's first H
 * continues the ramp where frame 1's last H left it (letter 8 of 16 is 68 %, not 100 %); the
 * space takes no step; and frame 1 widened (one ✓-free drag of its dot) shows the same letters
 * on one line, frame 2 left empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.framesletterssandbox"])
class FramesLettersUiTest {

    private fun crop(pic: Bitmap, r: RectF): Bitmap {
        val l = r.left.roundToInt().coerceAtLeast(0)
        val t = r.top.roundToInt().coerceAtLeast(0)
        return Bitmap.createBitmap(pic, l, t, r.right.roundToInt().coerceAtMost(pic.width) - l, r.bottom.roundToInt().coerceAtMost(pic.height) - t)
    }

    private fun letters(pic: Bitmap, r: RectF) = InkLetters.letters(crop(pic, r), FrameFixtures.WHITE, FrameFixtures.BLACK)

    /** Letter k of 16 (the space takes no step) is 1 − 0.6·k/15 of the first. */
    private fun assertRamp(heights: List<Double>, where: String) {
        assertEquals("$where: 16 letters ($heights)", 16, heights.size)
        val full = heights[0]
        for ((k, hgt) in heights.withIndex()) assertEquals("$where: letter $k of $heights", full * (1 - 0.6 * k / 15.0), hgt, 0.6)
    }

    @Test
    fun theRampRunsOnThroughTheChain() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("frame 2's first letter continues frame 1's ramp") {
            val s = h.editor(Smoke.document(600, 800, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }
            val f = FramesUi(s)
            val c = s.c
            f.pick()
            f.drawFrame(40f, 40f, 300f, 110f)
            SmokeUi.field("Text").type(STORY)
            settle()
            // Bold: at 40 % (16 px) a regular H's stem is ~1.5 px and may straddle two pixel
            // columns, so no column holds its full height; a bold stem (~2.5 px) always fills one.
            click("Bold", exact = true)
            f.ui.textSizePx(40)
            click("Scale letters", exact = true)
            click("Type a value for Smallest letter", exact = true)
            SmokeUi.typeAndDone("Smallest letter", "40")
            val steps = c.undoManager.undoCount
            click("OK", exact = true)
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals(TextFrameTool.ADD_LABEL, c.undoManager.undoLabel)
            val f1 = c.activeLayer
            assertTrue("scaled", f1.item().spec.letterScale.isOn)
            assertEquals("one line in frame 1", "HHHHHHHH ", f1.item().text)
            assertTrue(f1.item().thread.overset)

            f.tapPort(f1)
            f.drawFrame(40f, 180f, 300f, 250f)
            val f2 = c.activeLayer
            assertEquals(listOf(f1, f2), chainOf(c, f1))
            assertEquals("HHHHHHHH", f2.item().text)
            assertTrue("the same scaling in every frame", f2.item().spec.letterScale == f1.item().spec.letterScale)
            assertWhole(c, f1.item().thread.storyId)
            val pic = c.compositor.renderFlattened(FrameFixtures.WHITE)
            Shots.save(pic, "frames-letters-chain.png")
            val a = letters(pic, f.box(f1))
            val b = letters(pic, f.box(f2))
            System.err.println("frame 1: ${a.joinToString { "%.2f".format(it.stemHeight) }}\nframe 2: ${b.joinToString { "%.2f".format(it.stemHeight) }}")
            assertEquals(8, a.size)
            assertEquals(8, b.size)
            val chained = (a + b).map { it.stemHeight }
            assertRamp(chained, "two frames")
            assertTrue("frame 2 starts small (${b[0].stemHeight} vs ${a[0].stemHeight})", b[0].stemHeight < a[0].stemHeight * 0.75)

            // Frame 1 widened by its right dot: the whole story on one line, the same letters.
            f.select(f1)
            val t = c.viewTransform
            f.drag(f.handle(f1, FrameGeometry.Handle.RIGHT), f.window(t.docToScreen(Vec2(580f, f.box(f1).centerY()))))
            assertEquals(TextFrameTool.RESIZE_LABEL, c.undoManager.undoLabel)
            assertEquals(STORY, f1.item().text)
            assertEquals("frame 2 is left empty", "", f2.item().text)
            assertWhole(c, f1.item().thread.storyId)
            val one = c.compositor.renderFlattened(FrameFixtures.WHITE)
            Shots.save(one, "frames-letters-one-frame.png")
            val line = letters(one, f.box(f1)).map { it.stemHeight }
            assertRamp(line, "one frame")
            for (k in line.indices) assertEquals("letter $k is the same size in one frame as in two", chained[k], line[k], 0.6)
            Smoke.assertQuiet(c, "letters across frames")
        }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val STORY = "HHHHHHHH HHHHHHHH"
    }
}
