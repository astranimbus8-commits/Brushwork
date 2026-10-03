package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.LetterScaleCurve
import com.brushwork.paint.tools.text.LetterScaleDirection
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, letter scaling: the user's ELTON JOHN picture (text-scale-elton-john.png) made
 * the way the user makes it — Text tool, a tap on the canvas, "ELTON JOHN" typed, Bold, 59 px,
 * "Scale letters" on, the smallest letter typed as 62 % (26 / 42 of the first), ✓ — and measured
 * on the flattened picture: nine letters whose flat capitals step down by one equal step each
 * (the space between the words takes no step), their centres on one line (Center), their bottoms
 * on the base line (Baseline) or their tops on one line (Top); End → beginning grows instead; Same
 * ratio shrinks by a constant ratio. Each picture is also written for the lead
 * (letters-elton-*.png).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.eltonsandbox"])
class LettersEltonJohnUiTest {

    private lateinit var h: ChromeHarness

    private fun elton(shot: String, configure: () -> Unit = {}): List<InkLetters.Letter> {
        val s = h.editor(Smoke.document(440, 90, layers = 2, whiteBottom = false)) { it.color = WHITE; it.snapping.enabled = false }
        val ui = Qa16Ui(s)
        val c = s.c
        ui.tool("Text")
        val text = c.currentTool as TextTool
        ui.tap(220f, 45f)
        assertTrue("a tap opens the text editor", text.editorOpen)
        SmokeUi.field("Text").type("ELTON JOHN")
        settle()
        click("Bold", exact = true)
        ui.textSizePx(59)
        assertEquals(59f, text.item!!.spec.sizePx, 0.01f)
        click("Scale letters", exact = true)
        click("Type a value for Smallest letter", exact = true)
        SmokeUi.typeAndDone("Smallest letter", "62")
        configure()
        click("OK", exact = true)
        val steps = c.undoManager.undoCount
        click("Apply text edit")
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        val stored = layer.item().spec.letterScale
        assertEquals(62f, stored.smallestPercent, 0f)
        val pic = c.compositor.renderFlattened(BG)
        Shots.save(pic, shot)
        val letters = InkLetters.letters(pic, BG, WHITE)
        System.err.println("$shot: ${stored}\n  " + letters.joinToString("\n  "))
        Smoke.assertQuiet(c, shot)
        return letters
    }

    /** E, N, H, N (flat capitals: their stems are their heights) by letter index 0, 4, 7, 8 (L and T may touch). */
    private fun flat(l: List<InkLetters.Letter>): Map<Int, InkLetters.Letter> {
        assertTrue("8 or 9 runs of ink (L and T may touch): $l", l.size in 8..9)
        val n = l.size
        return mapOf(0 to l[0], 4 to l[n - 5], 7 to l[n - 2], 8 to l[n - 1])
    }

    private fun assertEvenRamp(flat: Map<Int, InkLetters.Letter>, growing: Boolean) {
        // Letter k of 9 is 1 − (1 − 0.62)·k/8 of the full size (End → beginning: of letter 8 − k).
        val full = if (growing) flat.getValue(8).stemHeight else flat.getValue(0).stemHeight
        for ((k, letter) in flat) {
            val u = (if (growing) 8 - k else k) / 8.0
            val expected = full * (1 - 0.38 * u)
            assertEquals("letter $k: ${letter.stemHeight} vs $expected", expected, letter.stemHeight, 0.6)
        }
        // The space takes no step: from N (letter 4) to the end is as far as from the start to N.
        val a = flat.getValue(0).stemHeight - flat.getValue(4).stemHeight
        val b = flat.getValue(4).stemHeight - flat.getValue(8).stemHeight
        assertEquals("equal halves (a step for the space would make them 4 : 5): $a vs $b", 1.0, b / a, 0.1)
    }

    @Test
    fun eltonJohnLikeTheUsersPicture() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("Center, Even steps, Beginning → end (the picture)") {
            val l = elton("letters-elton-center.png")
            val f = flat(l)
            assertEvenRamp(f, growing = false)
            // The first letter is 42 px tall like the picture's E (59 px Bold).
            assertEquals(42.0, f.getValue(0).stemHeight, 1.5)
            val centres = f.values.map { it.stemCentre }
            assertTrue("one centre line: $centres", centres.max() - centres.min() <= LINE)
            // Round letters (O, J) on the same line, give or take their overshoot.
            for (x in l) assertEquals("$x", centres.average(), x.centre, 1.5)
            // The picture's letters (rows of ink, measured on text-scale-elton-john.png): O 38,
            // N 34, J 33, O 32, H 28, N 26 px from the end; each within a pixel.
            val picture = listOf(38, 34, 33, 32, 28, 26)
            val ours = l.takeLast(6).map { it.height }
            for (i in picture.indices) assertEquals("like the picture: $ours vs $picture", picture[i].toDouble(), ours[i].toDouble(), 1.0)
        }
        h.section("Baseline") {
            val f = flat(elton("letters-elton-baseline.png") { click("Letters: Baseline", exact = true) })
            assertEvenRamp(f, growing = false)
            val bottoms = f.values.map { it.stemBottom }
            assertTrue("one base line: $bottoms", bottoms.max() - bottoms.min() <= LINE)
        }
        h.section("Top") {
            val f = flat(elton("letters-elton-top.png") { click("Letters: Top", exact = true) })
            assertEvenRamp(f, growing = false)
            val tops = f.values.map { it.stemTop }
            assertTrue("one top line: $tops", tops.max() - tops.min() <= LINE)
        }
        h.section("End → beginning") {
            val f = flat(elton("letters-elton-end-to-start.png") { click(LetterScaleDirection.END_TO_START.label, exact = true) })
            assertEvenRamp(f, growing = true)
            val centres = f.values.map { it.stemCentre }
            assertTrue("one centre line: $centres", centres.max() - centres.min() <= LINE)
        }
        h.section("Same ratio") {
            val f = flat(elton("letters-elton-ratio.png") { click(LetterScaleCurve.RATIO.label, exact = true) })
            // f = 0.62^(k/8): a constant ratio from letter to letter.
            val full = f.getValue(0).stemHeight
            for ((k, letter) in f) assertEquals("letter $k", full * Math.pow(0.62, k / 8.0), letter.stemHeight, 0.6)
            // Same ratio is not Even: letter 4 is √0.62 = 78.7 % (Even: 81 %).
            assertTrue(f.getValue(4).stemHeight < full * 0.80)
        }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        /** The picture's background. */
        const val BG = 0xFF090B0C.toInt()

        /**
         * How far apart the letters' lines may be (px): a glyph's baseline lands on a whole pixel
         * (raster text), so each letter is within ½ px of its exact line, plus anti-aliasing.
         */
        const val LINE = 1.25

    }
}
